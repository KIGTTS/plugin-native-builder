package com.kigtts.engines.builder

import com.kigtts.plugin.api.PluginStorage
import kotlinx.serialization.json.*
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The user's local publisher key never leaves this extension's private Appdata. */
class NativePackageWriter(private val storage: PluginStorage) {
    fun pack(project: NativeProject, dex: File): File {
        val payload = linkedMapOf<String, File>()
        dex.listFiles().orEmpty().filter { it.name.matches(Regex("classes([2-9]|[1-9][0-9]+)?\\.dex")) }.sortedBy { it.name }.forEach { payload["code/${it.name}"] = it }
        require("code/classes.dex" in payload)
        listOf("assets", "lib").forEach { directory -> project.root.resolve(directory).walkTopDown().filter { it.isFile }.forEach { payload[it.relativeTo(project.root).invariantSeparatorsPath] = it } }
        payload["assets/licenses/PROJECT-LICENSE.txt"] = project.root.resolve("LICENSE")
        payload["assets/licenses/NOTICE.md"] = project.root.resolve("NOTICE.md")
        val spec = project.spec.toMutableMap()
        spec["runtime"] = JsonPrimitive("android-dex")
        spec["abis"] = JsonArray(project.root.resolve("lib").listFiles().orEmpty().filter { it.isDirectory }.map { JsonPrimitive(it.name) })
        spec["files"] = JsonObject(payload.mapValues { JsonPrimitive(BuilderFiles.digest(it.value)) })
        val manifest = JsonObject(spec).toString().toByteArray(Charsets.UTF_8)
        val key = key()
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(key.private); update(manifest); sign() }
        val output = storage.file("exports/${project.id}-${project.version}.kigplugin").also { requireNotNull(it.parentFile).mkdirs() }
        ZipOutputStream(output.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put("plugin.json", manifest); put("signature/public.der", key.public.encoded); put("signature/manifest.sig", signature)
            payload.forEach { (name, file) -> zip.putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
        }
        return output
    }
    private fun key(): KeyPair {
        val private = storage.file("config/publisher-private.der")
        val public = storage.file("config/publisher-public.der")
        if (private.isFile && public.isFile) {
            val factory = KeyFactory.getInstance("RSA")
            return KeyPair(factory.generatePublic(X509EncodedKeySpec(public.readBytes())), factory.generatePrivate(PKCS8EncodedKeySpec(private.readBytes())))
        }
        require(!private.exists() && !public.exists()) { "本地签名密钥不完整，请恢复备份" }
        return KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair().also {
            requireNotNull(private.parentFile).mkdirs(); private.writeBytes(it.private.encoded); public.writeBytes(it.public.encoded)
        }
    }
}
