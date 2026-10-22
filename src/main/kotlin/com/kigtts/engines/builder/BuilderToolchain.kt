package com.kigtts.engines.builder

import com.kigtts.plugin.api.PluginJson
import com.kigtts.plugin.api.PluginStorage
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

data class BuilderToolchain(val token: String, val name: String, val directory: File, val files: Map<String, String>,
    val dex: List<String>, val classpath: List<String>, val languages: Set<String>, val javaClass: String,
    val kotlinClass: String, val d8Class: String, val manifestHash: String = "")

class BuilderToolchains(private val storage: PluginStorage) {
    fun stage(source: File): BuilderToolchain {
        val token = UUID.randomUUID().toString()
        val directory = storage.directory("toolchains/$token")
        BuilderFiles.unpack(storage, source, directory, 512 * 1024 * 1024L, 256)
        return read(token)
    }
    fun current(): BuilderToolchain? {
        val config = storage.file("config/toolchain.txt")
        return if (config.isFile) runCatching {
            val saved = config.readLines(); require(saved.size == 2)
            val toolchain = read(saved[0]); require(BuilderFiles.digest(toolchain.directory.resolve("toolchain.json")) == saved[1]) { "工具链配置已变化，需要重新确认" }
            toolchain
        }.getOrNull() else null
    }
    fun confirm(toolchain: BuilderToolchain) {
        verify(toolchain)
        storage.file("config/toolchain.txt").also { requireNotNull(it.parentFile).mkdirs() }.writeText(toolchain.token + "\n" + BuilderFiles.digest(toolchain.directory.resolve("toolchain.json")), Charsets.UTF_8)
    }
    fun read(token: String): BuilderToolchain {
        require(UUID.fromString(token).toString() == token)
        val directory = storage.file("toolchains/$token")
        val manifest = directory.resolve("toolchain.json")
        require(manifest.isFile && manifest.length() <= 256 * 1024)
        val obj = PluginJson.parseToJsonElement(manifest.readText(Charsets.UTF_8)).jsonObject
        fun list(key: String) = obj.getValue(key).jsonArray.map { it.jsonPrimitive.content }
        val files = obj.getValue("files").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content.also { require(it.matches(Regex("[a-f0-9]{64}"))) } }
        val languages = list("languages").toSet(); require(languages.isNotEmpty() && setOf("java", "kotlin").containsAll(languages))
        val dex = list("dexFiles"); require(dex.isNotEmpty() && dex.all { it.endsWith(".dex") && it in files })
        val classpath = list("classpath"); require(classpath.isNotEmpty() && classpath.all { it.endsWith(".jar") && it in files })
        fun type(key: String, default: String) = (obj[key]?.jsonPrimitive?.content ?: default).also { require(it.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*"))) }
        return BuilderToolchain(token, obj.getValue("name").jsonPrimitive.content.take(160), directory, files, dex, classpath, languages,
            type("javaClass", "org.eclipse.jdt.core.compiler.batch.BatchCompiler"), type("kotlinClass", "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"),
            type("d8Class", "com.android.tools.r8.D8"), BuilderFiles.digest(manifest)).also(::verify)
    }
    fun verify(toolchain: BuilderToolchain) {
        require(toolchain.manifestHash.isEmpty() || BuilderFiles.digest(toolchain.directory.resolve("toolchain.json")) == toolchain.manifestHash) { "工具链配置已变化，需要重新检查" }
        val files = toolchain.directory.walkTopDown().filter { it.isFile && it != toolchain.directory.resolve("toolchain.json") }.toList()
        require(files.map { it.relativeTo(toolchain.directory).invariantSeparatorsPath }.toSet() == toolchain.files.keys) { "工具链文件清单不匹配" }
        toolchain.files.forEach { (path, hash) -> require(BuilderFiles.digest(BuilderFiles.relative(toolchain.directory, path)) == hash) { "工具链校验失败" } }
        require(toolchain.files.keys.any { it.startsWith("licenses/") }) { "工具链缺少许可证" }
    }
    fun loader(toolchain: BuilderToolchain, parent: ClassLoader): ClassLoader {
        verify(toolchain)
        val dexPath = toolchain.dex.joinToString(File.pathSeparator) { BuilderFiles.relative(toolchain.directory, it).absolutePath }
        val cache = storage.directory("tmp/toolchain-${toolchain.token}")
        toolchain.dex.forEach { require(BuilderFiles.relative(toolchain.directory, it).setReadOnly()) }
        return Class.forName("dalvik.system.DexClassLoader").getConstructor(String::class.java, String::class.java, String::class.java, ClassLoader::class.java)
            .newInstance(dexPath, cache.absolutePath, null, parent) as ClassLoader
    }
}
