package com.kigtts.engines.builder

import com.kigtts.plugin.api.PluginEnvironment

internal object OfficialNativeTools {
    private const val FILE = "kigtts-java-kotlin-sdk38-r7.kigtools"
    private const val SHA256 = "de7588acb7e48a6b421a932bfd6e9a9e5a782d06d2815267c7fbea14ddf9ddd3"
    private const val ROOT = "https://github.com/KIGTTS/plugin-native-builder/releases/download/v0.8.9"
    private const val RETIRED_MANIFEST = "4ea6a2cbc861da98e56233c9c39e44838f80011a6006fa2dab91f454a9bf2d42"

    fun isRetired(toolchain: BuilderToolchain) = toolchain.manifestHash == RETIRED_MANIFEST

    suspend fun install(environment: PluginEnvironment, toolchains: BuilderToolchains): BuilderToolchain {
        val source = environment.download("$ROOT/$FILE", "downloads/$FILE", SHA256)
        try {
            val staged = toolchains.stage(source)
            require(staged.isolated && staged.languages == setOf("java", "kotlin")) { throw BuilderIssue("error.toolchain_changed") }
            toolchains.confirm(staged)
            return staged
        } finally { source.delete() }
    }
}
