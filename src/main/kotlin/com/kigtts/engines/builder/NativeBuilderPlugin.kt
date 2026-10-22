package com.kigtts.engines.builder

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.ConcurrentHashMap

class NativeBuilderPlugin : KigPlugin {
    override val descriptor = PluginDescriptor("tools.native-builder", "原生构建扩展", "编译 Java 和 Kotlin 插件", "0.8.0")
    private lateinit var environment: PluginEnvironment
    private lateinit var projects: NativeProjects
    private lateinit var toolchains: BuilderToolchains
    private lateinit var packages: NativePackageWriter
    private var toolchain: BuilderToolchain? = null
    private val pendingTools = mutableMapOf<String, BuilderToolchain>()
    private val pendingProjects = mutableMapOf<String, Pair<NativeProject, Map<String, String>>>()
    private val dirs = mutableMapOf<String, String>()
    private val editing = mutableMapOf<String, String>()
    private val creating = mutableMapOf<String, String>()
    private val deletes = mutableMapOf<String, Pair<NativeProject, List<String>>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var build: Job? = null
    private val revisions = MutableStateFlow(0L)
    override val uiChanges get() = revisions
    private var problem = ""
    private val status = ConcurrentHashMap<String, String>()
    private val outputs = ConcurrentHashMap<String, File>()
    private val pageCache = LinkedHashMap<String, PluginPage>(16, .75f, true)
    override suspend fun attach(environment: PluginEnvironment) {
        this.environment = environment; projects = NativeProjects(environment.storage); toolchains = BuilderToolchains(environment.storage)
        packages = NativePackageWriter(environment.storage); toolchain = toolchains.current()
    }
    override val contributions get() = PluginContributions(
        drawerEntries = listOf(DrawerEntry("manager", "原生构建扩展", "manager", PluginIcon.EDIT)),
        settingsEntries = listOf(SettingsEntry("builder", "原生构建扩展", "manager", PluginIcon.EDIT, "developer", "开发工具", "Java 与 Kotlin 插件")),
        automationActions = listOf("import", "build", "open"))
    override fun page(pageId: String, context: PluginContext): PluginPage? = if (pageId == "manager") NativeBuilderPages.manager(projects.projects(), toolchain, problem)
        else synchronized(pageCache) { pageCache[pageId] }
    override suspend fun execute(actionId: String, context: PluginContext): PluginOutcome {
        val action = actionId.substringBefore('/'); val id = actionId.substringAfter('/', "")
        return try {
            val result = when (action) {
                "choose-project" -> PluginOutcome.ImportResource("导入原生项目", "imports/projects", "import", zip = true)
                "import" -> stage(projects.stage(environment.storage.file(requireNotNull(context.inputs["resourcePath"]))))
                "project-confirm" -> {
                    require(context.inputs["_callerPlugin"] == null)
                    val (pending, hashes) = pendingProjects.remove(id) ?: error("检查页面已失效")
                    require(hashFiles(pending.root) == hashes) { "源代码已变化，请重新检查" }
                    open(NativeBuilderPages.project(projects.confirm(pending)))
                }
                "new" -> PluginOutcome.Menu("新建项目", listOf(PageAction("new-java", "Java", PluginIcon.TEXT), PageAction("new-kotlin", "Kotlin", PluginIcon.TEXT)))
                "new-java", "new-kotlin" -> PluginOutcome.TextInput("项目名称", "name/${action.substringAfter('-')}")
                "name" -> stage(projects.stage(NativeTemplates.create(environment.storage, context.inputs["value"].orEmpty(), id)))
                "project" -> open(NativeBuilderPages.project(projects.project(id)))
                "open" -> PluginOutcome.OpenPage("manager")
                "files" -> open(files(projects.project(id)))
                "file-open" -> {
                    val project = projects.project(id); val path = context.inputs["path"].orEmpty()
                    val file = if (path.isEmpty()) project.root else projects.file(project, path)
                    if (file.isDirectory) { dirs[id] = path; open(files(project)) }
                    else {
                        require((path.startsWith("sources/") || path.startsWith("assets/") || path in setOf("plugin-spec.json", "LICENSE", "NOTICE.md")) && file.isFile && file.length() <= 256 * 1024 && file.readBytes().none { it == 0.toByte() })
                        require(file.readText(Charsets.UTF_8).length <= 64000)
                        editing[id] = path; open(editor(project, path))
                    }
                }
                "file-parent" -> {
                    val project = projects.project(id); val next = context.inputs["directory"] ?: dirs[id].orEmpty().substringBeforeLast('/', "")
                    if (next.isNotEmpty()) require(projects.file(project, next).isDirectory)
                    dirs[id] = next; open(files(project))
                }
                "file-write" -> { val project = projects.project(id); projects.save(project, editing.getValue(id), context.inputs["value"].orEmpty()); open(editor(project, editing.getValue(id))) }
                "file-operation" -> operation(projects.project(id), context)
                "file-new" -> {
                    context.inputs["directory"]?.let { dirs[id] = it }
                    PluginOutcome.Menu("新建", listOf(PageAction("file-new-folder/$id", "文件夹", PluginIcon.ADD),
                        PageAction("file-new-java/$id", "Java 文件", PluginIcon.TEXT), PageAction("file-new-kotlin/$id", "Kotlin 文件", PluginIcon.TEXT),
                        PageAction("file-new-text/$id", "文本资源", PluginIcon.TEXT)))
                }
                "file-new-folder", "file-new-java", "file-new-kotlin", "file-new-text" -> {
                    creating[id] = action.substringAfterLast('-'); PluginOutcome.TextInput("名称", "file-create/$id")
                }
                "file-create" -> {
                    val project = projects.project(id); val name = context.inputs["value"].orEmpty()
                    require(name.length in 1..100 && !name.contains('/'))
                    val kind = creating.remove(id) ?: error("创建操作已失效")
                    val base = dirs[id].orEmpty().ifEmpty { if (kind == "text") "assets" else "sources" }
                    require(base == "sources" || base.startsWith("sources/") || base == "assets" || base.startsWith("assets/"))
                    if (kind == "text") require(base == "assets" || base.startsWith("assets/"))
                    val suffix = if (kind == "java") ".java" else if (kind == "kotlin") ".kt" else ""
                    val path = "$base/" + name + if (suffix.isNotEmpty() && !name.endsWith(suffix)) suffix else ""
                    val target = projects.file(project, path); require(!target.exists()); requireNotNull(target.parentFile).mkdirs()
                    if (kind == "folder") { require(target.mkdir()); open(files(project)) }
                    else { target.writeText("", Charsets.UTF_8); editing[id] = path; open(editor(project, path)) }
                }
                "file-delete-confirm" -> {
                    require(context.inputs["_callerPlugin"] == null)
                    val (project, paths) = deletes.remove(id) ?: error("确认失效")
                    paths.forEach { projects.file(project, it).deleteRecursively() }; open(files(project))
                }
                "file-import" -> { context.inputs["directory"]?.let { dirs[id] = it }; PluginOutcome.ImportResource("导入项目资源", "imports/resources", "file-imported/$id", multiple = true) }
                "file-imported" -> {
                    val project = projects.project(id); val directory = dirs[id].orEmpty()
                    require(directory == "assets" || directory.startsWith("assets/") || directory == "dependencies")
                    val paths = context.inputs["resourcePaths"]?.let { PluginJson.decodeFromString<List<String>>(it) } ?: listOf(requireNotNull(context.inputs["resourcePath"]))
                    val plans = paths.map { path ->
                        val source = environment.storage.file(path)
                        require(source.isFile && source.length() <= 16 * 1024 * 1024L)
                        if (directory == "dependencies") require(source.extension == "jar")
                        val target = projects.file(project, "$directory/${source.name}"); require(!target.exists()); source to target
                    }
                    require(plans.map { it.second.name.lowercase() }.distinct().size == plans.size)
                    val added = mutableListOf<File>()
                    try {
                        plans.forEach { (source, target) -> added += target; source.copyTo(target) }
                        val all = project.root.walkTopDown().filter { it.isFile }.toList()
                        require(all.size <= 512 && all.sumOf { it.length() } <= 64 * 1024 * 1024L)
                    } catch (failure: Throwable) { added.forEach { it.delete() }; throw failure }
                    open(files(project))
                }
                "choose-toolchain" -> PluginOutcome.ImportResource("导入构建工具", "imports/toolchains", "toolchain-import", zip = true)
                "toolchain-import" -> toolchains.stage(environment.storage.file(requireNotNull(context.inputs["resourcePath"]))).let { pendingTools[it.token] = it; open(NativeBuilderPages.toolchain(it)) }
                "toolchain-confirm" -> {
                    require(context.inputs["_callerPlugin"] == null)
                    val pending = pendingTools.remove(id) ?: error("检查页面已失效")
                    toolchains.confirm(pending); toolchain = pending; problem = ""; PluginOutcome.OpenPage("manager")
                }
                "build" -> begin(projects.project(id.ifEmpty { requireNotNull(context.inputs["projectId"]) }))
                "cancel" -> { build?.cancel(); PluginOutcome.Message("已请求取消；编译器退出后不会发布结果") }
                "share" -> outputs.getValue(id).let { PluginOutcome.ExportResource("保存或分享插件", PluginResource(it.relativeTo(environment.storage.root).invariantSeparatorsPath), it.name, "application/octet-stream") }
                "source-export" -> export(projects.project(id))
                "delete" -> open(PluginPage("delete/$id", "删除项目", listOf(PageBlock.Notice("删除此源码项目？已安装插件不受影响。"), PageBlock.Actions(listOf(PageAction("delete-confirm/$id", "确认删除", PluginIcon.CLOSE))))))
                "delete-confirm" -> { require(context.inputs["_callerPlugin"] == null); projects.remove(id); PluginOutcome.OpenPage("manager") }
                else -> PluginOutcome.Message("未知操作")
            }
            revisions.value++; result
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { PluginOutcome.Message(failure.message?.take(250) ?: "操作失败") }
    }
    private fun stage(project: NativeProject): PluginOutcome {
        val token = UUID.randomUUID().toString(); pendingProjects[token] = project to hashFiles(project.root)
        return open(NativeBuilderPages.review(project, token))
    }
    private fun begin(project: NativeProject): PluginOutcome {
        require(build?.isActive != true) { "请等待当前构建结束" }
        val selected = toolchain ?: error("请先配置 Android 兼容的构建工具")
        val workspace = environment.storage.directory("tmp/build-${UUID.randomUUID()}")
        val snapshot = workspace.resolve("source"); project.root.copyRecursively(snapshot)
        val source = projects.read(snapshot)
        outputs.remove(project.id); status[project.id] = "准备构建"
        cache(NativeBuilderPages.build(project, "准备构建", true, false))
        build = scope.launch {
            try {
                val loader = toolchains.loader(selected, javaClass.classLoader!!)
                val dex = NativeCompiler(loader, selected).compile(source, workspace) { text -> update(project, text, true, false) }
                currentCoroutineContext().ensureActive()
                val packed = packages.pack(source, dex)
                currentCoroutineContext().ensureActive()
                outputs[project.id] = packed
                update(project, "构建完成，插件仍需在管理器中确认安装", false, true)
            } catch (cancel: CancellationException) { update(project, "构建已取消", false, false) }
            catch (failure: Throwable) {
                problem = failure.message?.take(1000) ?: "构建工具不兼容当前运行时"
                update(project, "构建失败：$problem", false, false)
            } finally { workspace.deleteRecursively() }
        }
        return PluginOutcome.OpenPage("build/${project.id}")
    }
    private fun update(project: NativeProject, text: String, running: Boolean, ready: Boolean) {
        status[project.id] = text; cache(NativeBuilderPages.build(project, text, running, ready)); revisions.value++
    }
    private fun files(project: NativeProject): PluginPage {
        val directory = dirs[project.id].orEmpty()
        val root = if (directory.isEmpty()) project.root else projects.file(project, directory)
        val items = root.listFiles().orEmpty().sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }).map {
            PluginFileItem(it.relativeTo(project.root).invariantSeparatorsPath, it.name, it.isDirectory, if (it.isFile) it.length() else 0)
        }
        return PluginPage("files/${project.id}", "项目文件", listOf(PageBlock.Files("文件", items, directory, "file-open/${project.id}", "file-operation/${project.id}",
            "file-import/${project.id}", "file-new/${project.id}", parentAction = "file-parent/${project.id}")))
    }
    private fun editor(project: NativeProject, path: String) = PluginPage("edit/${project.id}", path.substringAfterLast('/'), listOf(
        PageBlock.SourceEditor("native-${project.id}-${path.hashCode()}", projects.file(project, path).readText(Charsets.UTF_8), "file-write/${project.id}",
            when { path.endsWith(".kt") -> "kotlin"; path.endsWith(".java") -> "java"; else -> path.substringAfterLast('.', "text") })))
    private fun operation(project: NativeProject, context: PluginContext): PluginOutcome {
        val paths = PluginJson.decodeFromString<List<String>>(requireNotNull(context.inputs["paths"])); require(paths.isNotEmpty())
        val sources = paths.map { path -> require(path.startsWith("sources/") || path.startsWith("assets/") || path.startsWith("dependencies/")); projects.file(project, path).also { require(it.exists()) } }
        return when (context.inputs["op"]) {
            "delete" -> { val token = UUID.randomUUID().toString(); deletes[token] = project to paths
                open(PluginPage("file-delete/$token", "删除文件", listOf(PageBlock.Notice("删除所选 ${paths.size} 个项目？"), PageBlock.Actions(listOf(PageAction("file-delete-confirm/$token", "确认删除", PluginIcon.CLOSE)))))) }
            "rename" -> {
                require(sources.size == 1)
                val name = context.inputs["destination"].orEmpty(); require(name.isNotBlank() && !name.contains('/'))
                val source = sources.single(); val target = BuilderFiles.relative(project.root, source.relativeTo(project.root).invariantSeparatorsPath.substringBeforeLast('/') + "/$name")
                require(!target.exists()); java.nio.file.Files.move(source.toPath(), target.toPath()); open(files(project))
            }
            "move", "copy" -> {
                val destination = context.inputs["destination"].orEmpty(); require(listOf("sources", "assets", "dependencies").any { destination == it || destination.startsWith("$it/") })
                val folder = projects.file(project, destination); require(folder.isDirectory)
                val targets = sources.map { folder.resolve(it.name).canonicalFile }; require(targets.map { it.name.lowercase() }.distinct().size == targets.size)
                sources.zip(targets).forEach { (source, target) -> require(!target.exists() && !target.toPath().startsWith(source.toPath())) }
                val applied = mutableListOf<Pair<File, File>>()
                try { sources.zip(targets).forEach { (source, target) -> applied += source to target
                    if (context.inputs["op"] == "copy") source.copyRecursively(target) else java.nio.file.Files.move(source.toPath(), target.toPath()) }
                    val all = project.root.walkTopDown().filter { it.isFile }.toList(); require(all.size <= 512 && all.sumOf { it.length() } <= 64 * 1024 * 1024L)
                }
                catch (failure: Throwable) { applied.asReversed().forEach { (source, target) -> if (context.inputs["op"] == "copy") target.deleteRecursively() else if (target.exists()) java.nio.file.Files.move(target.toPath(), source.toPath()) }; throw failure }
                open(files(project))
            }
            else -> error("未知文件操作")
        }
    }
    private fun export(project: NativeProject): PluginOutcome {
        val output = environment.storage.file("exports/${project.id}.kigsource").also { requireNotNull(it.parentFile).mkdirs() }
        ZipOutputStream(output.outputStream()).use { zip -> project.root.walkTopDown().filter { it.isFile }.forEach { file ->
            zip.putNextEntry(ZipEntry(file.relativeTo(project.root).invariantSeparatorsPath)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
        } }
        return PluginOutcome.ExportResource("分享源码项目", PluginResource(output.relativeTo(environment.storage.root).invariantSeparatorsPath), output.name, "application/zip")
    }
    private fun hashFiles(root: File) = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).invariantSeparatorsPath to BuilderFiles.digest(it) }
    private fun open(page: PluginPage): PluginOutcome { cache(page); return PluginOutcome.OpenPage(page.id) }
    private fun cache(page: PluginPage) = synchronized(pageCache) {
        fun bytes(value: PluginPage) = PluginJson.encodeToString(PluginPage.serializer(), value).toByteArray().size
        require(bytes(page) <= 400 * 1024) { "页面内容过大，请导出后编辑" }
        if (page.blocks.any { it is PageBlock.SourceEditor }) pageCache.entries.removeAll { (id, value) -> id != page.id && value.blocks.any { it is PageBlock.SourceEditor } }
        pageCache[page.id] = page; while (pageCache.size > 32 || pageCache.values.sumOf(::bytes) > 400 * 1024) pageCache.remove(pageCache.keys.first())
    }
    override suspend fun close() { scope.cancel(); withTimeoutOrNull(3000) { build?.join() } }
}
