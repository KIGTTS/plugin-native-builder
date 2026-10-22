#Requires -Version 7.0
param([switch]$ExperimentalKotlin, [switch]$SkipBuild,
    [string]$OutputDirectory = '', [string]$RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..')))
$ErrorActionPreference = 'Stop'
$utf8 = [Text.UTF8Encoding]::new($false)
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $RepoRoot 'build/native-toolchains' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
$env:JAVA_HOME = 'C:/Program Files/Microsoft/jdk-17.0.17.10-hotspot'
if (-not $SkipBuild) {
    & (Join-Path $RepoRoot '.tools/gradle-8.13/bin/gradle.bat') --project-dir $RepoRoot --gradle-user-home (Join-Path $RepoRoot '.gradle-user-home') --console=plain :plugin-api:jar :engine-plugins:native-builder:collectDesktopToolchainInputs
    if ($LASTEXITCODE -ne 0) { throw 'Toolchain input resolution failed' }
}
$inputs = Join-Path $RepoRoot 'engine-plugins/native-builder/build/desktop-toolchain-inputs'
$work = Join-Path $OutputDirectory ([Guid]::NewGuid().ToString())
$code = Join-Path $work 'code'; $classpath = Join-Path $work 'classpath'; $licenses = Join-Path $work 'licenses'
[IO.Directory]::CreateDirectory($code) | Out-Null
[IO.Directory]::CreateDirectory($classpath) | Out-Null
[IO.Directory]::CreateDirectory($licenses) | Out-Null
$allJars = @(Get-ChildItem -LiteralPath $inputs -Filter '*.jar')
$compilerJars = @($allJars | Where-Object { $_.Name -match '^ecj-|^d8\.jar$' })
if ($compilerJars.Count -ne 2) { throw 'ECJ and D8 compiler jars are required' }
if ($ExperimentalKotlin) {
    $compilerJars += @($allJars | Where-Object { $_.Name -match '^kotlin-compiler-embeddable-|^kotlin-daemon-embeddable-|^kotlin-reflect-|^trove4j-' })
    if (-not ($compilerJars.Name -match '^kotlin-compiler-embeddable-')) { throw 'Kotlin compiler is missing' }
}
Copy-Item -LiteralPath (Join-Path $RepoRoot '.tools/android-sdk/platforms/android-36/android.jar') -Destination (Join-Path $classpath 'android.jar')
foreach ($jar in @($allJars | Where-Object { $_.Name -match '^plugin-api\.jar$|^kotlin-stdlib-|^kotlinx-coroutines-core-jvm-|^kotlinx-serialization-.*-jvm-|^annotations-' })) {
    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $classpath $jar.Name)
}
& (Join-Path $RepoRoot '.tools/android-sdk/build-tools/36.0.0/d8.bat') --release --min-api 26 --lib (Join-Path $classpath 'android.jar') --output $code @($compilerJars.FullName)
if ($LASTEXITCODE -ne 0) { throw 'Compiler DEX conversion failed' }
# Keep full library notices embedded in compiler/dependency archives.
foreach ($jar in $allJars) {
    $zip = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
    try {
        foreach ($entry in $zip.Entries | Where-Object { $_.FullName -match '(^|/)(LICENSE[^/]*|NOTICE[^/]*|about\.html)$' }) {
            $target = Join-Path $licenses ($jar.BaseName + '-' + $entry.Name)
            if (-not (Test-Path -LiteralPath $target)) { [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $false) }
        }
    } finally { $zip.Dispose() }
}
$notice = @'
This bundle contains unmodified ECJ/R8 tools converted to Android DEX and compile-only Android/API/Kotlin libraries.
ECJ source and EPL license: https://github.com/eclipse-jdt/eclipse.jdt.core
R8 source and BSD license: https://r8.googlesource.com/r8
Kotlin source and Apache license: https://github.com/JetBrains/kotlin
Android SDK and Apache source: https://source.android.com
KIGTTS plugin-api source/MIT: https://github.com/LHT02/KIGTTSNEXT
Embedded licenses/notices are preserved beside this notice. This bundle has not been verified on ART.
Standard desktop Kotlin compiler dependencies may require Android-specific adaptations; experimental Kotlin mode is not a compatibility certification.
'@
[IO.File]::WriteAllText((Join-Path $licenses 'NOTICE.txt'), $notice, $utf8)
Copy-Item -LiteralPath (Join-Path $RepoRoot 'LICENSE') -Destination (Join-Path $licenses 'KIGTTS-MIT.txt')
$files = [ordered]@{}
foreach ($file in Get-ChildItem -LiteralPath $work -Recurse -File | Sort-Object FullName) {
    $relative = [IO.Path]::GetRelativePath($work, $file.FullName).Replace('\', '/')
    $files[$relative] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
}
$manifest = [ordered]@{
    name = if ($ExperimentalKotlin) { 'Java + experimental Kotlin tools (ART unverified)' } else { 'Java tools (ART unverified)' }
    languages = @(if ($ExperimentalKotlin) { 'java'; 'kotlin' } else { 'java' })
    dexFiles = @(Get-ChildItem -LiteralPath $code -Filter '*.dex' | ForEach-Object { 'code/' + $_.Name })
    classpath = @(Get-ChildItem -LiteralPath $classpath -Filter '*.jar' | ForEach-Object { 'classpath/' + $_.Name })
    javaClass = 'org.eclipse.jdt.core.compiler.batch.BatchCompiler'
    kotlinClass = 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler'
    d8Class = 'com.android.tools.r8.D8'
    files = $files
}
[IO.File]::WriteAllText((Join-Path $work 'toolchain.json'), ($manifest | ConvertTo-Json -Depth 8), $utf8)
$output = Join-Path $OutputDirectory ('kigtts-' + $(if ($ExperimentalKotlin) { 'java-kotlin-experimental' } else { 'java' }) + '-' + [Guid]::NewGuid().ToString().Substring(0,8) + '.kigtools')
[IO.Compression.ZipFile]::CreateFromDirectory($work, $output, [IO.Compression.CompressionLevel]::Optimal, $false)
$bundle = Get-Item -LiteralPath $output
[pscustomobject]@{ Path = $bundle.FullName; Bytes = $bundle.Length; SHA256 = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash } | Format-List
