plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "com.kigtts.engines.builder"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { jvmToolchain(17) }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("builder.androidJar", android.sdkDirectory.resolve("platforms/android-36/android.jar").absolutePath) }
    }
}
tasks.register<Sync>("collectPluginDependencies") {
    from(configurations.getByName("debugRuntimeClasspath"))
    into(layout.buildDirectory.dir("plugin-dependencies"))
}
tasks.register<Sync>("collectDesktopToolchainInputs") {
    from(configurations.getByName("debugUnitTestRuntimeClasspath").incoming.artifactView {
        attributes.attribute(org.gradle.api.attributes.Attribute.of("artifactType", String::class.java), "jar")
    }.files) {
        include("ecj-*.jar", "kotlin-compiler-embeddable-*.jar", "kotlin-daemon-embeddable-*.jar", "kotlin-reflect-*.jar",
            "kotlin-stdlib-*.jar", "trove4j-*.jar", "kotlinx-coroutines-core-jvm-*.jar", "kotlinx-serialization-*-jvm-*.jar",
            "annotations-*.jar", "plugin-api.jar", "d8.jar")
    }
    into(layout.buildDirectory.dir("desktop-toolchain-inputs"))
}
dependencies {
    compileOnly(project(":plugin-android-api"))
    testImplementation(project(":plugin-android-api"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.eclipse.jdt:ecj:3.39.0")
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")
    testImplementation(files(android.sdkDirectory.resolve("build-tools/36.0.0/lib/d8.jar")))
}
