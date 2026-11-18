package com.kigtts.engines.builder

import com.kigtts.plugin.api.PluginStorage
import com.kigtts.plugin.api.PLUGIN_API_VERSION
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

internal object NativeTemplates {
    fun create(storage: PluginStorage, name: String, language: String, text: BuilderText): File {
        require(name.length in 1..64 && language in setOf("java", "kotlin"))
        val root = storage.directory("new/${UUID.randomUUID()}")
        val id = "local.plugin-${UUID.randomUUID().toString().take(8)}"
        val entry = "com.kigtts.user.GeneratedPlugin"
        root.resolve("plugin-spec.json").writeText(buildJsonObject {
            put("id", id); put("name", name); put("version", "0.1.0"); put("versionCode", 1); put("entryClass", entry)
            put("apiVersion", PLUGIN_API_VERSION); put("minSdk", 26); put("runtime", "android-dex"); put("license", "MIT"); put("capabilities", JsonArray(listOf(JsonPrimitive("ui"))))
        }.toString(), Charsets.UTF_8)
        root.resolve("LICENSE").writeText("""
            MIT License

            Copyright (c) 2026 Plugin author

            Permission is hereby granted, free of charge, to any person obtaining a copy
            of this software and associated documentation files (the "Software"), to deal
            in the Software without restriction, including without limitation the rights
            to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
            copies of the Software, and to permit persons to whom the Software is
            furnished to do so, subject to the following conditions:

            The above copyright notice and this permission notice shall be included in all
            copies or substantial portions of the Software.

            THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
            IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
            FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
            AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
            LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
            OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
            SOFTWARE.
        """.trimIndent() + "\n", Charsets.UTF_8)
        val notice = text("template.notice", "api" to PLUGIN_API_VERSION.toString())
        root.resolve("NOTICE.md").writeText("# $name\n\n$notice\n", Charsets.UTF_8)
        val source = root.resolve("sources/com/kigtts/user/GeneratedPlugin." + if (language == "java") "java" else "kt")
        requireNotNull(source.parentFile).mkdirs()
        fun literal(value: String) = JsonPrimitive(value).toString().let {
            if (language == "kotlin") it.replace("$", "\\$") else it
        }
        val title = literal(name)
        val description = literal(text("template.description"))
        val greeting = literal(text("template.greeting"))
        val greet = literal(text("template.greet"))
        val tools = literal(text("template.tools"))
        source.writeText(if (language == "java") """
            package com.kigtts.user;
            import com.kigtts.plugin.api.*;
            import kotlin.coroutines.Continuation;
            import java.util.Collections;
            public final class GeneratedPlugin implements KigPlugin {
              public PluginDescriptor getDescriptor() { return new PluginDescriptor("$id", $title, $description, "0.1.0", $PLUGIN_API_VERSION, false, "MIT", Collections.emptyList()); }
              public PluginContributions getContributions() {
                return new PluginContributions(Collections.emptyList(), Collections.singletonList(new DrawerEntry("main", $title, "main", PluginIcon.TEXT, false)),
                  Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), null);
              }
              public PluginPage page(String pageId, PluginContext context) {
                return new PluginPage("main", $title, Collections.singletonList(new PageBlock.Actions(
                  Collections.singletonList(new PageAction("hello", $greet, PluginIcon.TEXT)))), null);
              }
              public Object execute(String action, PluginContext context, Continuation<? super PluginOutcome> continuation) {
                return new PluginOutcome.Message($greeting);
              }
            }
        """.trimIndent() else """
            package com.kigtts.user
            import com.kigtts.plugin.api.*
            class GeneratedPlugin : KigPlugin {
              override val descriptor = PluginDescriptor("$id", $title, $description, version = "0.1.0")
              override val contributions = PluginContributions(drawerEntries = listOf(DrawerEntry("main", $title, "main", PluginIcon.TEXT)))
              override fun page(pageId: String, context: PluginContext) = PluginPage("main", $title,
                listOf(PageBlock.Group($tools, listOf(PageBlock.Actions(listOf(PageAction("hello", $greet, PluginIcon.TEXT)))))))
              override suspend fun execute(actionId: String, context: PluginContext): PluginOutcome = PluginOutcome.Message($greeting)
            }
        """.trimIndent(), Charsets.UTF_8)
        return root
    }
}
