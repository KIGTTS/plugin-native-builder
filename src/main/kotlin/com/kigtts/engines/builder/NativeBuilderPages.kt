package com.kigtts.engines.builder

import com.kigtts.plugin.api.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class NativeBuilderPages(private val text: BuilderText) {
    private fun projectHeader(project: NativeProject, opens: Boolean = false): ManagementItem {
        val icon = project.spec["icon"] as? JsonObject
        val image = icon?.get("path")?.jsonPrimitive?.content?.takeIf { path ->
            path.startsWith("assets/") && path.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
                path.substringAfterLast('.').lowercase() in setOf("svg", "png", "jpg", "jpeg", "webp") &&
                runCatching { BuilderFiles.relative(project.root, path).let { it.isFile && it.length() in 1..1_048_576 } }.getOrDefault(false)
        }?.let { PluginResource("projects/${project.id}/$it", "tools.native-builder") }
        return ManagementItem(project.id, project.name, "v${project.version} · ${project.languages.joinToString(" / ")}",
            PluginIcon.TEXT, image = image, symbol = icon?.get("symbol")?.jsonPrimitive?.content ?: "code",
            primaryAction = if (opens) "project/${project.id}" else null)
    }

    fun manager(projects: List<NativeProject>) = PluginPage("manager", text("title"), listOf(
        PageBlock.Group(text("project"), listOf(PageBlock.Actions(listOf(PageAction("choose-project", text("project.import_source"), PluginIcon.ADD), PageAction("new", text("project.new"), PluginIcon.EDIT))))),
        PageBlock.Group(text("toolchain"), listOf(PageBlock.Actions(listOf(PageAction("toolchains", text("toolchain.manage"), PluginIcon.SETTINGS, symbol = "inventory_2"))))),
    ), ManagementList("native-projects", projects.map { projectHeader(it, opens = true) }, emptyText = text("project.empty")))

    fun toolchains(toolchain: BuilderToolchain?, problem: String) = PluginPage("toolchains", text("toolchain.manage"), listOf(
        PageBlock.Group(text("toolchain"), listOf(PageBlock.Text(toolchain?.let { text("toolchain.ready", "name" to it.name, "languages" to it.languages.joinToString()) } ?: text("toolchain.missing")),
            PageBlock.Actions(listOf(
                PageAction("download-toolchain", text("toolchain.download"), PluginIcon.ADD),
                PageAction("choose-toolchain", text("toolchain.import"), PluginIcon.ADD))))),
    ) + if (problem.isNotEmpty()) listOf(PageBlock.Notice(problem)) else emptyList())
    fun project(project: NativeProject) = PluginPage("project/${project.id}", project.name, listOf(
        PageBlock.Group(text("project"), listOf(
            PageBlock.Actions(listOf(PageAction("files/${project.id}", text("project.files"), PluginIcon.MENU),
                PageAction("build/${project.id}", text("build.start"), PluginIcon.ADD), PageAction("source-export/${project.id}", text("project.share_source"), PluginIcon.EXPORT),
                PageAction("delete/${project.id}", text("project.delete"), PluginIcon.CLOSE))))),
    ), header = projectHeader(project))
    fun toolchain(toolchain: BuilderToolchain) = PluginPage("toolchain/${toolchain.token}", text("toolchain.import"), listOf(
        PageBlock.Group(toolchain.name, listOf(PageBlock.Text(text("toolchain.files", "count" to toolchain.files.size.toString(), "languages" to toolchain.languages.joinToString())),
            PageBlock.Notice(text("toolchain.review")))),
        PageBlock.Actions(listOf(PageAction("toolchain-confirm/${toolchain.token}", text("toolchain.confirm"), PluginIcon.ADD))),
    ))
    fun review(project: NativeProject, token: String) = PluginPage("review/$token", text("project.review"), listOf(
        PageBlock.Group(project.name, listOf(PageBlock.Text("${project.version} · ${project.languages.joinToString(" / ")}"),
            PageBlock.Notice(text("project.review_notice")))),
        PageBlock.Actions(listOf(PageAction("project-confirm/$token", text("project.save"), PluginIcon.ADD))),
    ))
    fun build(project: NativeProject, state: String, running: Boolean, ready: Boolean) = PluginPage("build/${project.id}", text("build"), listOf(
        PageBlock.Group(project.name, listOf(PageBlock.Text(state))),
        PageBlock.Actions(if (running) listOf(PageAction("cancel", text("build.cancel"), PluginIcon.CLOSE)) else if (ready)
            listOf(PageAction("debug/${project.id}", text("build.debug"), PluginIcon.TEXT),
                PageAction("share/${project.id}", text("build.share"), PluginIcon.EXPORT)) else
            listOf(PageAction("build/${project.id}", text("build.retry"), PluginIcon.ADD))),
    ))
}
