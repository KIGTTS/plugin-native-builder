package com.kigtts.engines.builder

import com.kigtts.plugin.api.PluginStorage
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

object BuilderFiles {
    fun relative(root: File, path: String): File {
        require(path.isNotBlank() && path.length <= 240 && !path.startsWith('/') && !path.contains('\\') && !path.contains(':') && !path.contains('\u0000'))
        require(path.split('/').none { it in setOf("", ".", "..") }) { "路径越界" }
        return root.resolve(path).canonicalFile.also { require(it.toPath().startsWith(root.canonicalFile.toPath()) && it != root.canonicalFile) }
    }
    fun digest(file: File): String = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { val size = input.read(buffer); if (size < 0) break; hash.update(buffer, 0, size) }
        hash.digest().joinToString("") { "%02x".format(it) }
    }
    fun unpack(storage: PluginStorage, source: File, target: File, maxBytes: Long, maxFiles: Int) {
        require(source.canonicalFile.toPath().startsWith(storage.root.canonicalFile.toPath()))
        val names = hashSetOf<String>(); var total = 0L
        fun checked(path: String, size: Long): File {
            require(names.add(path.lowercase()) && names.size <= maxFiles) { "文件过多或重名" }
            total += size; require(total <= maxBytes)
            return relative(target, path).also { requireNotNull(it.parentFile).mkdirs() }
        }
        try {
            if (source.isDirectory) source.walkTopDown().filter { it.isFile }.forEach { file ->
                require(file.canonicalFile.toPath().startsWith(source.canonicalFile.toPath()))
                file.copyTo(checked(file.relativeTo(source).invariantSeparatorsPath, file.length()))
            } else ZipFile(source).use { zip ->
                require(source.length() <= maxBytes)
                zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                    val destination = checked(entry.name, 0)
                    zip.getInputStream(entry).use { input -> destination.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) { val size = input.read(buffer); if (size < 0) break; total += size; require(total <= maxBytes); output.write(buffer, 0, size) }
                    } }
                }
            }
        } catch (failure: Throwable) { target.deleteRecursively(); throw failure }
    }
}
