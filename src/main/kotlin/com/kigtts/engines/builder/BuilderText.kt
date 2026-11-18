package com.kigtts.engines.builder

import com.kigtts.plugin.api.PluginStrings

internal class BuilderText(private val strings: PluginStrings, private val locale: String) {
    operator fun invoke(key: String, vararg arguments: Pair<String, String>, fallback: String = key): String =
        strings.text(key, locale, arguments.toMap(), fallback)
}

internal class BuilderIssue(val key: String) : IllegalArgumentException(key)
