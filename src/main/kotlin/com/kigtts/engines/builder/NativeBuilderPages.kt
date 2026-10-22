package com.kigtts.engines.builder

import com.kigtts.plugin.api.*

object NativeBuilderPages {
    fun manager(projects: List<NativeProject>, toolchain: BuilderToolchain?, problem: String) = PluginPage("manager", "原生构建扩展", listOf(
        PageBlock.Group("项目", listOf(PageBlock.Actions(listOf(PageAction("choose-project", "导入源码", PluginIcon.ADD), PageAction("new", "新建项目", PluginIcon.EDIT))))),
        PageBlock.Group("构建工具", listOf(PageBlock.Text(toolchain?.let { "已配置 ${it.name}；支持 ${it.languages.joinToString("、")}" } ?: "尚未配置 Android 兼容的构建工具"),
            PageBlock.Actions(listOf(PageAction("choose-toolchain", "导入构建工具", PluginIcon.ADD))))),
    ) + if (problem.isNotEmpty()) listOf(PageBlock.Notice(problem)) else emptyList(),
        ManagementList("native-projects", projects.map { ManagementItem(it.id, it.name, it.languages.joinToString("、"), PluginIcon.TEXT, primaryAction = "project/${it.id}") }, emptyText = "导入 Java 或 Kotlin 插件项目"))
    fun project(project: NativeProject) = PluginPage("project/${project.id}", project.name, listOf(
        PageBlock.Group("项目", listOf(PageBlock.Text("${project.version} · ${project.languages.joinToString(" / ")}"),
            PageBlock.Actions(listOf(PageAction("files/${project.id}", "项目文件", PluginIcon.MENU),
                PageAction("build/${project.id}", "构建并打包", PluginIcon.ADD), PageAction("source-export/${project.id}", "分享源码", PluginIcon.EXPORT),
                PageAction("delete/${project.id}", "删除项目", PluginIcon.CLOSE))))),
    ))
    fun toolchain(toolchain: BuilderToolchain) = PluginPage("toolchain/${toolchain.token}", "导入构建工具", listOf(
        PageBlock.Group(toolchain.name, listOf(PageBlock.Text("${toolchain.files.size} 个文件 · ${toolchain.languages.joinToString("、")}"),
            PageBlock.Notice("构建工具将以原生插件的权限运行。请确认来源和许可证。普通桌面 Kotlin 编译器不一定兼容 Android。"))),
        PageBlock.Actions(listOf(PageAction("toolchain-confirm/${toolchain.token}", "确认使用", PluginIcon.ADD))),
    ))
    fun review(project: NativeProject, token: String) = PluginPage("review/$token", "导入项目", listOf(
        PageBlock.Group(project.name, listOf(PageBlock.Text("${project.version} · ${project.languages.joinToString(" / ")}"),
            PageBlock.Notice("导入只保存源代码。构建后的原生插件需要在插件管理器中确认来源与权限后安装。"))),
        PageBlock.Actions(listOf(PageAction("project-confirm/$token", "保存项目", PluginIcon.ADD))),
    ))
    fun build(project: NativeProject, state: String, running: Boolean, ready: Boolean) = PluginPage("build/${project.id}", "构建", listOf(
        PageBlock.Group(project.name, listOf(PageBlock.Text(state))),
        PageBlock.Actions(if (running) listOf(PageAction("cancel", "取消构建", PluginIcon.CLOSE)) else if (ready)
            listOf(PageAction("share/${project.id}", "保存或分享插件", PluginIcon.EXPORT)) else listOf(PageAction("build/${project.id}", "重新构建", PluginIcon.ADD))),
    ))
}
