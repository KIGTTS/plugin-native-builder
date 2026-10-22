package com.kigtts.engines.builder

import com.kigtts.plugin.api.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.zip.ZipFile

/** Real desktop compiler fixtures. They intentionally do not claim Android/ART compatibility. */
class NativeBuilderTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture(storage: PluginStorage): BuilderToolchain {
        val directory = storage.directory("test-toolchain")
        val jars = listOf(File(System.getProperty("builder.androidJar")),
            PluginDescriptor::class.java, kotlin.Unit::class.java, kotlinx.coroutines.Job::class.java,
            kotlinx.serialization.KSerializer::class.java, kotlinx.serialization.json.JsonElement::class.java,
            org.jetbrains.annotations.NotNull::class.java).map { value ->
            if (value is File) value else File((value as Class<*>).protectionDomain.codeSource.location.toURI())
        }.distinct()
        val classpath = jars.map { source -> require(source.isFile); source.copyTo(directory.resolve(source.name)); source.name }
        return BuilderToolchain("fixture", "Desktop test only", directory, emptyMap(), emptyList(), classpath,
            setOf("java", "kotlin"), "org.eclipse.jdt.core.compiler.batch.BatchCompiler", "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "com.android.tools.r8.D8")
    }
    @Test fun realJavaPluginCompilesToDexAndSignedPackageIsVerifiable() = runBlocking {
        compileAndVerify("java")
    }
    @Test fun realKotlinPluginCompilesToDexAndSignedPackageIsVerifiable() = runBlocking {
        compileAndVerify("kotlin")
    }
    private suspend fun compileAndVerify(language: String) {
        val storage = PluginStorage(temp.newFolder())
        val root = NativeTemplates.create(storage, "示例插件", language)
        val projects = NativeProjects(storage)
        val project = projects.confirm(projects.stage(root))
        val toolchain = fixture(storage)
        val output = storage.directory("tmp/compile")
        val stages = mutableListOf<String>()
        val dex = NativeCompiler(javaClass.classLoader!!, toolchain).compile(project, output, stages::add)
        assertTrue(dex.resolve("classes.dex").readBytes().take(3).toByteArray().contentEquals("dex".toByteArray()))
        assertEquals("生成 Android 代码", stages.last())
        val archive = NativePackageWriter(storage).pack(project, dex)
        ZipFile(archive).use { zip ->
            fun bytes(path: String) = zip.getInputStream(zip.getEntry(path)).use { it.readBytes() }
            val raw = bytes("plugin.json")
            val manifest = PluginJson.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
            assertEquals(project.entryClass, manifest.getValue("entryClass").jsonPrimitive.content)
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes("signature/public.der")))
            assertTrue(Signature.getInstance("SHA256withRSA").run { initVerify(key); update(raw); verify(bytes("signature/manifest.sig")) })
            val hashes = manifest.getValue("files").jsonObject
            assertTrue("code/classes.dex" in hashes)
            hashes.forEach { (path, value) ->
                val extracted = storage.file("tmp/extracted/${path}").also { requireNotNull(it.parentFile).mkdirs(); it.writeBytes(bytes(path)) }
                assertEquals(value.jsonPrimitive.content, BuilderFiles.digest(extracted))
            }
            assertFalse(zip.entries().asSequence().any { it.name.contains("private") || it.name.startsWith("sources/") })
        }
    }
    @Test fun changedToolchainPayloadAndUnsafePathsAreRejected() {
        val storage = PluginStorage(temp.newFolder())
        val token = java.util.UUID.randomUUID().toString(); val root = storage.directory("toolchains/$token")
        root.resolve("classes.dex").writeBytes("dexfake".toByteArray()); root.resolve("android.jar").writeBytes("platform".toByteArray())
        root.resolve("licenses").mkdir(); root.resolve("licenses/LICENSE").writeText("MIT")
        val files = listOf("classes.dex", "android.jar", "licenses/LICENSE")
        root.resolve("toolchain.json").writeText(buildJsonObject {
            put("name", "Fixture"); put("languages", JsonArray(listOf(JsonPrimitive("java"))))
            put("dexFiles", JsonArray(listOf(JsonPrimitive("classes.dex")))); put("classpath", JsonArray(listOf(JsonPrimitive("android.jar"))))
            put("files", JsonObject(files.associateWith { JsonPrimitive(BuilderFiles.digest(root.resolve(it))) }))
        }.toString())
        val toolchain = BuilderToolchains(storage).read(token)
        root.resolve("classes.dex").writeText("tampered")
        try { BuilderToolchains(storage).confirm(toolchain); fail() } catch (_: IllegalArgumentException) {}
        try { BuilderFiles.relative(root, "../private.der"); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun templatesAreRealImportableProjectsAndDataStaysScoped() {
        val storage = PluginStorage(temp.newFolder()); val projects = NativeProjects(storage)
        for (language in listOf("java", "kotlin")) {
            val created = projects.confirm(projects.stage(NativeTemplates.create(storage, "项目", language)))
            assertEquals(1, created.languages.size)
            assertTrue(created.root.canonicalFile.toPath().startsWith(storage.root.canonicalFile.toPath()))
            try { projects.save(created, "../private", "bad"); fail() } catch (_: IllegalArgumentException) {}
        }
        assertEquals(2, projects.projects().size)
    }
    @Test fun optionalExternalNativeTemplateRepositoryCompiles() = runBlocking {
        val root = System.getenv("KIGTTS_TEMPLATE_ROOT")?.let(::File) ?: return@runBlocking
        for (name in listOf("native-hello", "native-java-hello")) {
            val storage = PluginStorage(temp.newFolder())
            val imported = storage.directory("imports/template")
            root.resolve("templates/$name").copyRecursively(imported)
            val store = NativeProjects(storage); val project = store.confirm(store.stage(imported))
            val dex = NativeCompiler(javaClass.classLoader!!, fixture(storage)).compile(project, storage.directory("tmp/build")) { }
            assertTrue(NativePackageWriter(storage).pack(project, dex).isFile)
        }
    }
    @Test fun optionalProducedAndroidToolchainIsActuallyCompleteAndHashValid() {
        val file = System.getenv("KIGTTS_TOOLCHAIN_FILE")?.let(::File) ?: return
        val storage = PluginStorage(temp.newFolder())
        val imported = storage.file("imports/tools.kigtools").also { requireNotNull(it.parentFile).mkdirs(); file.copyTo(it) }
        val store = BuilderToolchains(storage); val staged = store.stage(imported)
        store.confirm(staged)
        assertTrue(staged.dex.isNotEmpty()); assertEquals(setOf("java"), staged.languages)
        assertEquals(staged.token, store.current()!!.token)
    }
}
