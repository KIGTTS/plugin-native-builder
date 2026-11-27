#Requires -Version 7.0
param([switch]$JavaOnly, [switch]$SkipBuild,
    [Parameter(Mandatory)][string]$AsmJar,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$PlatformJar,
    [string]$PlatformNotice,
    [string]$PluginApiJar,
    [string]$OutputDirectory, [string]$BundleName,
    [string]$RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')))
$ErrorActionPreference = 'Stop'
$utf8 = [Text.UTF8Encoding]::new($false)
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $RepoRoot 'build/native-toolchains' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17.' }
if (-not $AndroidSdk) { throw 'Set ANDROID_HOME or specify -AndroidSdk.' }
if (-not $PlatformJar) { $PlatformJar = Join-Path $AndroidSdk 'platforms/android-36/android.jar' }
$AsmJar = (Resolve-Path -LiteralPath $AsmJar).Path
if (-not $SkipBuild) {
    & (Join-Path $RepoRoot 'gradlew.bat') --project-dir $RepoRoot --console=plain :collectDesktopToolchainInputs
    if ($LASTEXITCODE -ne 0) { throw 'Toolchain input resolution failed' }
}
$inputs = Join-Path $RepoRoot 'build/desktop-toolchain-inputs'
$work = Join-Path $OutputDirectory ([Guid]::NewGuid().ToString())
$code = Join-Path $work 'code'; $classpath = Join-Path $work 'classpath'; $licenses = Join-Path $work 'licenses'
[IO.Directory]::CreateDirectory($code) | Out-Null
[IO.Directory]::CreateDirectory($classpath) | Out-Null
[IO.Directory]::CreateDirectory($licenses) | Out-Null
$allJars = @(Get-ChildItem -LiteralPath $inputs -Filter '*.jar')
$compilerJars = @($allJars | Where-Object { $_.Name -match '^ecj-|^d8\.jar$' })
if ($compilerJars.Count -ne 2) { throw 'ECJ and D8 compiler jars are required' }
if (-not $JavaOnly) {
    $compilerJars += @($allJars | Where-Object { $_.Name -match '^kotlin-compiler-embeddable-|^kotlin-daemon-embeddable-|^kotlin-reflect-|^kotlin-stdlib-|^kotlinx-coroutines-core-jvm-|^trove4j-|^annotations-|^stax-api-' })
    if (-not ($compilerJars.Name -match '^kotlin-compiler-embeddable-')) { throw 'Kotlin compiler is missing' }
}
Copy-Item -LiteralPath $PlatformJar -Destination (Join-Path $classpath 'android.jar')
if ($PlatformNotice) { Copy-Item -LiteralPath $PlatformNotice -Destination (Join-Path $licenses 'Android-NOTICE.txt') }
foreach ($jar in @($allJars | Where-Object { $_.Name -match '^plugin-api\.jar$|^kotlin-stdlib-|^kotlinx-coroutines-core-jvm-|^kotlinx-serialization-.*-jvm-|^annotations-' })) {
    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $classpath $jar.Name)
}
if ($PluginApiJar) { Copy-Item -LiteralPath $PluginApiJar -Destination (Join-Path $classpath 'plugin-api.jar') -Force }
$compatibility = Join-Path $OutputDirectory ('compiler-compat-' + [Guid]::NewGuid().ToString())
$compatibilityClasses = Join-Path $compatibility 'classes'
[IO.Directory]::CreateDirectory($compatibilityClasses) | Out-Null
$shim = Join-Path $PSScriptRoot 'android-compiler-compat/javax/lang/model/SourceVersion.java'
$io = Join-Path $PSScriptRoot 'android-compiler-compat/com/kigtts/tools/CompilerIo.java'
$patcher = Join-Path $PSScriptRoot 'AndroidCompilerPatcher.java'
$longMap = Join-Path $PSScriptRoot 'android-compiler-compat/com/kigtts/tools/CompilerLongMap.java'
$javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$jarTool = Join-Path $env:JAVA_HOME 'bin/jar.exe'
$linkageSource = Join-Path $PSScriptRoot 'compiler-linkage/java/lang/invoke/LambdaMetafactory.java'
$linkageClasses = Join-Path $compatibility 'linkage'
[IO.Directory]::CreateDirectory($linkageClasses) | Out-Null
& $javac --release 8 -d $linkageClasses $linkageSource
if ($LASTEXITCODE -ne 0) { throw 'Java Lambda linkage stub did not compile' }
& $jarTool --create --file (Join-Path $classpath 'android-java-linkage.jar') -C $linkageClasses .
if ($LASTEXITCODE -ne 0) { throw 'Could not archive Java compile-only linkage stubs' }
& $javac -cp $AsmJar -d $compatibility $patcher
if ($LASTEXITCODE -ne 0) { throw 'Compiler adaptation tool failed to compile' }
& $javac --release 8 -d $compatibilityClasses $shim $io
if ($LASTEXITCODE -ne 0) { throw 'Android compatibility class failed to compile' }
if (-not $JavaOnly) {
    $kotlinCompiler = ($compilerJars | Where-Object Name -like 'kotlin-compiler-embeddable-*').FullName
    & $javac --release 8 -cp $kotlinCompiler -d $compatibilityClasses $longMap
    if ($LASTEXITCODE -ne 0) { throw 'Kotlin compatibility class failed to compile' }
}
$compilerInputs = @(
    foreach ($jar in $compilerJars) {
        $adapted = Join-Path $compatibility $jar.Name
        & $java -cp ($compatibility + [IO.Path]::PathSeparator + $AsmJar) AndroidCompilerPatcher $jar.FullName $adapted
        if ($LASTEXITCODE -ne 0) { throw "Compiler adaptation failed: $($jar.Name)" }
        $adapted
    }
)
$compilerInputs += @(Get-ChildItem -LiteralPath $compatibilityClasses -Recurse -Filter '*.class' | ForEach-Object FullName)
& (Join-Path $AndroidSdk 'build-tools/36.0.0/d8.bat') -JXmx4g --release --min-api 26 --lib (Join-Path $classpath 'android.jar') --output $code @compilerInputs
if ($LASTEXITCODE -ne 0) { throw 'Compiler DEX conversion failed' }
# Keep the compiler message bundles, parser tables and services alongside its DEX.
$dexFiles = @(Get-ChildItem -LiteralPath $code -Filter '*.dex')
$archivePath = Join-Path $code 'compiler.jar'
$compilerArchive = [IO.Compression.ZipFile]::Open($archivePath, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($dex in $dexFiles) {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($compilerArchive, $dex.FullName, $dex.Name) | Out-Null
    }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($jar in $compilerJars) {
        $inputArchive = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
        try {
            foreach ($resource in $inputArchive.Entries) {
                # PathUtil locates its distribution by looking up its own resource in the archive.
                $locationMarker = $resource.FullName -in @('org/jetbrains/kotlin/utils/PathUtil.class',
                    'org/jetbrains/kotlin/cli/common/CompilerSystemProperties.class')
                if (-not $resource.Name -or ($resource.FullName.EndsWith('.class') -and -not $locationMarker) -or
                    $resource.FullName -notmatch '^(org/eclipse/|com/android/tools/r8/|org/jetbrains/kotlin/|kotlin/|messages/|misc/|resources/|META-INF/(services|extensions)/)|^(META-INF/compiler\.version|kotlinManifest\.properties|custom-formatters\.js)$|\.kotlin_(module|builtins)$' -or
                    -not $seen.Add($resource.FullName)) { continue }
                $inputStream = $resource.Open()
                $outputStream = $compilerArchive.CreateEntry($resource.FullName).Open()
                try { $inputStream.CopyTo($outputStream) }
                finally { $inputStream.Dispose(); $outputStream.Dispose() }
            }
        } finally { $inputArchive.Dispose() }
    }
} finally { $compilerArchive.Dispose() }
foreach ($dex in $dexFiles) { Remove-Item -LiteralPath $dex.FullName }
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
This bundle contains compiler tools adapted to Android and converted to DEX, plus compile-only libraries.
ECJ source and EPL license: https://github.com/eclipse-jdt/eclipse.jdt.core
R8 source and BSD license: https://r8.googlesource.com/r8
Kotlin source and Apache license: https://github.com/JetBrains/kotlin
StAX API source and licensing: https://central.sonatype.com/artifact/javax.xml.stream/stax-api/1.0-2
Android SDK and Apache source: https://source.android.com
KIGTTS plugin-api source/MIT: https://github.com/LHT02/KIGTTSNEXT
Embedded licenses and notices are preserved beside this notice.
'@
[IO.File]::WriteAllText((Join-Path $licenses 'NOTICE.txt'), $notice, $utf8)
Copy-Item -LiteralPath (Join-Path $RepoRoot 'LICENSE') -Destination (Join-Path $licenses 'KIGTTS-MIT.txt')
$sources = Join-Path $work 'sources'
[IO.Directory]::CreateDirectory($sources) | Out-Null
Copy-Item -LiteralPath @($shim, $io, $patcher, $linkageSource) -Destination $sources
if (-not $JavaOnly) { Copy-Item -LiteralPath $longMap -Destination $sources }
$files = [ordered]@{}
foreach ($file in Get-ChildItem -LiteralPath $work -Recurse -File | Sort-Object FullName) {
    $relative = [IO.Path]::GetRelativePath($work, $file.FullName).Replace('\', '/')
    $files[$relative] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
}
$manifest = [ordered]@{
    name = if ($JavaOnly) { 'KIGTTS Java' } else { 'KIGTTS Java + Kotlin' }
    languages = @(if ($JavaOnly) { 'java' } else { 'java'; 'kotlin' })
    isolated = -not $JavaOnly
    dexFiles = @('code/compiler.jar')
    classpath = @(Get-ChildItem -LiteralPath $classpath -Filter '*.jar' | ForEach-Object { 'classpath/' + $_.Name })
    javaClass = 'org.eclipse.jdt.core.compiler.batch.BatchCompiler'
    kotlinClass = 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler'
    d8Class = 'com.android.tools.r8.D8'
    files = $files
}
[IO.File]::WriteAllText((Join-Path $work 'toolchain.json'), ($manifest | ConvertTo-Json -Depth 8), $utf8)
if (-not $BundleName) { $BundleName = 'kigtts-' + $(if ($JavaOnly) { 'java' } else { 'java-kotlin' }) + '-' + [Guid]::NewGuid().ToString().Substring(0,8) + '.kigtools' }
if ($BundleName -notmatch '^[a-zA-Z0-9._-]+\.kigtools$') { throw 'Invalid toolchain output name' }
$output = Join-Path $OutputDirectory $BundleName
[IO.Compression.ZipFile]::CreateFromDirectory($work, $output, [IO.Compression.CompressionLevel]::Optimal, $false)
$bundle = Get-Item -LiteralPath $output
[pscustomobject]@{ Path = $bundle.FullName; Bytes = $bundle.Length; SHA256 = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash } | Format-List
