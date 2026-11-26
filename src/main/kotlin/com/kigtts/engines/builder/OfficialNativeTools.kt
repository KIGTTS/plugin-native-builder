package com.kigtts.engines.builder

import com.kigtts.plugin.api.PluginEnvironment

internal object OfficialNativeTools {
    private const val FILE = "kigtts-java-sdk38-r7.kigtools"
    private const val SHA256 = "b4a82480fc1c7c84d480d36c803404178d5ccd436f921bae1b855e06c9a3b530"
    private const val ROOT = "https://github.com/KIGTTS/plugin-native-builder/releases/download/v0.8.7"
    private const val RETIRED_MANIFEST = "4ea6a2cbc861da98e56233c9c39e44838f80011a6006fa2dab91f454a9bf2d42"

    fun isRetired(toolchain: BuilderToolchain) = toolchain.manifestHash == RETIRED_MANIFEST

    suspend fun install(environment: PluginEnvironment, toolchains: BuilderToolchains): BuilderToolchain {
        val source = environment.download("$ROOT/$FILE", "downloads/$FILE", SHA256)
        try {
            val staged = toolchains.stage(source)
            toolchains.confirm(staged)
            return staged
        } finally { source.delete() }
    }
}
