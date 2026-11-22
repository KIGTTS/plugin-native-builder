package com.kigtts.engines.builder

import com.kigtts.plugin.api.PluginEnvironment

internal object OfficialNativeTools {
    private const val FILE = "kigtts-java-36-r1.kigtools"
    private const val SHA256 = "df767458f2e42e4da9d3867b6281b5019e93855b24779283bf28559a5db6aa42"
    private const val ROOT = "https://github.com/KIGTTS/plugin-native-builder/releases/download/v0.8.3"

    suspend fun install(environment: PluginEnvironment, toolchains: BuilderToolchains): BuilderToolchain {
        val source = environment.download("$ROOT/$FILE", "downloads/$FILE", SHA256)
        try {
            val staged = toolchains.stage(source)
            toolchains.confirm(staged)
            return staged
        } finally { source.delete() }
    }
}
