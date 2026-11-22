package com.kigtts.engines.builder

import com.kigtts.plugin.api.PluginEnvironment

internal object OfficialNativeTools {
    private const val FILE = "kigtts-java-36-r6.kigtools"
    private const val SHA256 = "1301c592999dd68d341e1bc3ac62a30d962c68b882f9062c60b9d46433d284f8"
    private const val ROOT = "https://github.com/KIGTTS/plugin-native-builder/releases/download/v0.8.5"
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
