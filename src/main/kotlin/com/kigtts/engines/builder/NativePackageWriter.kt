package com.kigtts.engines.builder

import com.kigtts.plugin.api.PluginStorage
import com.kigtts.plugin.api.AppBroker
import com.kigtts.plugin.api.AppCommand
import com.kigtts.plugin.api.AppReply
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
    suspend fun pack(project: NativeProject, dex: File, broker: AppBroker?, interactive: Boolean): File {
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
        val (publicKey, signature) = sign(project.id, manifest, broker, interactive)
        val output = storage.file("exports/${project.id}-${project.version}.kigplugin").also { requireNotNull(it.parentFile).mkdirs() }
        ZipOutputStream(output.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put("plugin.json", manifest); put("signature/public.der", publicKey); put("signature/manifest.sig", signature)
            payload.forEach { (name, file) -> zip.putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
        }
        return output
    }
    private suspend fun sign(id: String, raw: ByteArray, broker: AppBroker?, interactive: Boolean): Pair<ByteArray, ByteArray> {
        val reply = broker?.request(AppCommand.ManagedCall(id, "editor-signing", buildJsonObject {
            put("manifest", java.util.Base64.getEncoder().encodeToString(raw))
            put("interactive", interactive)
        }))
        if (reply is AppReply.Failure) throw IllegalStateException(reply.message)
        if (reply is AppReply.Data && reply.value["default"]?.jsonPrimitive?.booleanOrNull != true) {
            val decoder = java.util.Base64.getDecoder()
            return decoder.decode(reply.value.getValue("publicKey").jsonPrimitive.content) to
                decoder.decode(reply.value.getValue("signature").jsonPrimitive.content)
        }
        val signer = key()
        return signer.public.encoded to Signature.getInstance("SHA256withRSA").apply { initSign(signer.private); update(raw) }.sign()
    }
    private fun key(): KeyPair {
        val private = storage.file("config/publisher-private.der")
        val public = storage.file("config/publisher-public.der")
        if (private.isFile && public.isFile) {
            val factory = KeyFactory.getInstance("RSA")
            return KeyPair(factory.generatePublic(X509EncodedKeySpec(public.readBytes())), factory.generatePrivate(PKCS8EncodedKeySpec(private.readBytes())))
        }
        require(!private.exists() && !public.exists()) { throw BuilderIssue("error.signing") }
        return KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair().also {
            requireNotNull(private.parentFile).mkdirs(); private.writeBytes(it.private.encoded); public.writeBytes(it.public.encoded)
        }
    }
}
