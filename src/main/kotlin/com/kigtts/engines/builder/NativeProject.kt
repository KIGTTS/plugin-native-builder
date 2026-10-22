package com.kigtts.engines.builder

import com.kigtts.plugin.api.PLUGIN_API_VERSION
import com.kigtts.plugin.api.PluginJson
import com.kigtts.plugin.api.PluginStorage
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

data class NativeProject(val root: File, val spec: JsonObject, val id: String, val name: String, val entryClass: String, val minSdk: Int) {
    val version get() = spec.getValue("version").jsonPrimitive.content
    val languages get() = root.resolve("sources").walkTopDown().filter { it.isFile }.mapNotNull {
        when (it.extension) { "java" -> "Java"; "kt" -> "Kotlin"; else -> null }
    }.toSet()
}

class NativeProjects(private val storage: PluginStorage) {
    fun projects(): List<NativeProject> = storage.directory("projects").listFiles().orEmpty().filter { it.isDirectory }
        .mapNotNull { runCatching { read(it) }.getOrNull() }.sortedBy { it.name }
    fun project(id: String): NativeProject { safeId(id); return read(storage.file("projects/$id")) }
    fun stage(source: File): NativeProject {
        val root = storage.directory("staging/${UUID.randomUUID()}")
        BuilderFiles.unpack(storage, source, root, 64 * 1024 * 1024L, 512)
        return read(root)
    }
    fun confirm(project: NativeProject): NativeProject {
        require(projects().size < 32 || projects().any { it.id == project.id })
        val target = storage.file("projects/${project.id}")
        val backup = storage.file("backup/${project.id}").also { requireNotNull(it.parentFile).mkdirs() }
        backup.deleteRecursively()
        if (target.exists()) java.nio.file.Files.move(target.toPath(), backup.toPath())
        try { java.nio.file.Files.move(project.root.toPath(), target.toPath()); target.resolve("assets").mkdirs(); target.resolve("dependencies").mkdirs(); backup.deleteRecursively(); return read(target) }
        catch (failure: Throwable) { if (backup.exists()) java.nio.file.Files.move(backup.toPath(), target.toPath()); throw failure }
    }
    fun file(project: NativeProject, path: String) = BuilderFiles.relative(project.root, path)
    fun save(project: NativeProject, path: String, value: String) {
        require((path.startsWith("sources/") && (path.endsWith(".java") || path.endsWith(".kt"))) || path.startsWith("assets/") || path in setOf("plugin-spec.json", "LICENSE", "NOTICE.md"))
        require(value.length <= 64000 && value.toByteArray().size <= 256 * 1024)
        val file = file(project, path)
        val original = if (path == "plugin-spec.json") file.readText(Charsets.UTF_8) else null
        file.writeText(value, Charsets.UTF_8)
        if (original != null) try { require(read(project.root).id == project.id) { "项目 ID 需要另建项目才能修改" } }
        catch (failure: Throwable) { file.writeText(original, Charsets.UTF_8); throw failure }
    }
    fun remove(id: String) { project(id).root.deleteRecursively() }
    fun read(root: File): NativeProject {
        val specFile = root.resolve("plugin-spec.json")
        require(specFile.isFile && specFile.length() <= 64 * 1024)
        val spec = PluginJson.parseToJsonElement(specFile.readText(Charsets.UTF_8)).jsonObject
        fun string(key: String) = spec.getValue(key).jsonPrimitive.content
        val id = string("id"); safeId(id)
        val name = string("name"); require(name.length in 1..160)
        val entry = string("entryClass"); require(entry.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")))
        require(spec.getValue("apiVersion").jsonPrimitive.int == PLUGIN_API_VERSION)
        require(spec.getValue("versionCode").jsonPrimitive.int > 0 && string("version").matches(Regex("[0-9A-Za-z][0-9A-Za-z.+_-]{0,31}")))
        val minSdk = spec["minSdk"]?.jsonPrimitive?.int ?: 26; require(minSdk in 26..36)
        require((spec["runtime"]?.jsonPrimitive?.content ?: "android-dex") == "android-dex")
        require(root.resolve("LICENSE").isFile && root.resolve("NOTICE.md").isFile) { "项目需要 LICENSE 和 NOTICE.md" }
        val sources = root.resolve("sources").walkTopDown().filter { it.isFile }.toList()
        require(sources.size in 1..64 && sources.all { it.extension in setOf("kt", "java") } && sources.sumOf { it.length() } <= 1024 * 1024)
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            require(path in setOf("plugin-spec.json", "LICENSE", "NOTICE.md") || path.startsWith("sources/") || path.startsWith("assets/") ||
                (path.startsWith("dependencies/") && path.endsWith(".jar")) || (path.startsWith("lib/") && path.endsWith(".so"))) { "不支持的项目文件" }
        }
        return NativeProject(root, spec, id, name, entry, minSdk)
    }
    private fun safeId(id: String) { require(id.matches(Regex("[a-z][a-z0-9.-]{0,79}")) && id !in setOf("runtime.lua", "tools.native-builder")) }
}
