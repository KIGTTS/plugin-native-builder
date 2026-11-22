package com.kigtts.engines.builder

import com.kigtts.plugin.api.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class NativeBuilderPages(private val text: BuilderText) {
    fun manager(projects: List<NativeProject>, toolchain: BuilderToolchain?, problem: String) = PluginPage("manager", text("title"), listOf(
        PageBlock.Group(text("project"), listOf(PageBlock.Actions(listOf(PageAction("choose-project", text("project.import_source"), PluginIcon.ADD), PageAction("new", text("project.new"), PluginIcon.EDIT))))),
        PageBlock.Group(text("toolchain"), listOf(PageBlock.Text(toolchain?.let { text("toolchain.ready", "name" to it.name, "languages" to it.languages.joinToString()) } ?: text("toolchain.missing")),
            PageBlock.Actions(listOfNotNull(
                PageAction("download-toolchain", text("toolchain.download"), PluginIcon.ADD).takeIf { toolchain == null },
                PageAction("choose-toolchain", text("toolchain.import"), PluginIcon.ADD))))),
    ) + if (problem.isNotEmpty()) listOf(PageBlock.Notice(problem)) else emptyList(),
        ManagementList("native-projects", projects.map { ManagementItem(it.id, it.name, it.languages.joinToString(), PluginIcon.TEXT,
            symbol = (it.spec["icon"] as? JsonObject)?.get("symbol")?.jsonPrimitive?.content,
            primaryAction = "project/${it.id}") }, emptyText = text("project.empty")))
    fun project(project: NativeProject) = PluginPage("project/${project.id}", project.name, listOf(
        PageBlock.Group(text("project"), listOf(PageBlock.Text("${project.version} · ${project.languages.joinToString(" / ")}"),
            PageBlock.Actions(listOf(PageAction("project-name/${project.id}", text("project.name"), PluginIcon.EDIT),
                PageAction("project-icon/${project.id}", text("project.icon"), PluginIcon.IMAGE))),
            PageBlock.Actions(listOf(PageAction("files/${project.id}", text("project.files"), PluginIcon.MENU),
                PageAction("build/${project.id}", text("build.start"), PluginIcon.ADD), PageAction("source-export/${project.id}", text("project.share_source"), PluginIcon.EXPORT),
                PageAction("delete/${project.id}", text("project.delete"), PluginIcon.CLOSE))))),
    ))
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
