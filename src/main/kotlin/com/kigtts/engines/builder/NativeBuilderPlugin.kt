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
    override val descriptor = PluginDescriptor("tools.native-builder", "原生构建扩展", "编译 Java 和 Kotlin 插件", "0.8.9")
    override val localization = PluginLocalization("zh-CN", listOf("zh-CN", "zh-CN-x-casual", "en", "ko", "ja"))
    private var strings = PluginStrings(emptyMap())
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
    private val outputs = ConcurrentHashMap<String, File>()
    private val pageCache = LinkedHashMap<String, PluginPage>(16, .75f, true)
    override suspend fun attach(environment: PluginEnvironment) {
        strings = PluginStrings.fromAssets(environment.assets)
        this.environment = environment; projects = NativeProjects(environment.storage); toolchains = BuilderToolchains(environment.storage)
        packages = NativePackageWriter(environment.storage)
        toolchain = toolchains.current()?.takeUnless(OfficialNativeTools::isRetired)
    }
    override val contributions get() = contributionsFor(if (::environment.isInitialized) environment.locale else "zh-CN")
    override fun contributions(context: PluginContext) = contributionsFor(context.locale)
    private fun contributionsFor(locale: String): PluginContributions {
        val text = BuilderText(strings, locale)
        val title = text("title", fallback = "原生构建扩展")
        return PluginContributions(
            drawerEntries = listOf(DrawerEntry("manager", title, "manager", PluginIcon.EDIT)),
            settingsEntries = listOf(SettingsEntry("builder", title, "manager", PluginIcon.EDIT, "developer",
                text("category", fallback = "开发工具"), text("description", fallback = "Java 与 Kotlin 插件"))),
            automationActions = listOf("import", "build", "open", "new"))
    }

    override fun page(pageId: String, context: PluginContext): PluginPage? {
        val pages = NativeBuilderPages(BuilderText(strings, context.locale))
        return when (pageId) {
            "manager" -> pages.manager(projects.projects())
            "toolchains" -> pages.toolchains(toolchain, problem)
            else -> synchronized(pageCache) { pageCache[pageId] }
        }
    }

    override suspend fun execute(actionId: String, context: PluginContext): PluginOutcome {
        val text = BuilderText(strings, context.locale)
        val pages = NativeBuilderPages(text)
        val action = actionId.substringBefore('/'); val id = actionId.substringAfter('/', "")
        return try {
            val result = when (action) {
                "choose-project" -> PluginOutcome.ImportResource(text("project.import"), "imports/projects", "import", zip = true)
                "import" -> stage(projects.stage(environment.storage.file(requireNotNull(context.inputs["resourcePath"]))), text)
                "project-confirm" -> {
                    require(context.inputs["_callerPlugin"] == null)
                    val (pending, hashes) = pendingProjects.remove(id) ?: throw BuilderIssue("error.review_expired")
                    require(hashFiles(pending.root) == hashes) { throw BuilderIssue("error.source_changed") }
                    open(pages.project(projects.confirm(pending)))
                }
                "new" -> PluginOutcome.Menu(text("project.new"), listOf(PageAction("new-java", "Java", PluginIcon.TEXT), PageAction("new-kotlin", "Kotlin", PluginIcon.TEXT)))
                "new-java", "new-kotlin" -> PluginOutcome.TextInput(text("project.name"), "name/${action.substringAfter('-')}")
                "editor-create" -> {
                    require(context.inputs["_callerPlugin"] == null)
                    val packageName = requireNotNull(context.inputs["id"])
                    require(projects.projects().none { it.id == packageName })
                    val template = NativeTemplates.create(environment.storage, context.inputs["name"].orEmpty(),
                        context.inputs["language"].orEmpty(), text, packageName, context.inputs["author"].orEmpty())
                    try { open(pages.project(projects.confirm(projects.stage(template)))) }
                    finally { template.deleteRecursively() }
                }
                "editor-preset" -> {
                    require(context.inputs["_callerPlugin"] == null)
                    val path = requireNotNull(context.inputs["resourcePath"])
                    require(path.startsWith("imports/presets/"))
                    val source = environment.storage.file(path)
                    val pending = try { projects.stage(source) } finally { source.deleteRecursively() }
                    if (projects.projects().any { it.id == pending.id }) {
                        pending.root.deleteRecursively()
                        throw BuilderIssue("error.project_id")
                    }
                    stage(pending, text)
                }
                "name" -> {
                    val template = NativeTemplates.create(environment.storage, context.inputs["value"].orEmpty(), id, text)
                    try { open(pages.project(projects.confirm(projects.stage(template)))) }
                    finally { template.deleteRecursively() }
                }
                "project" -> open(pages.project(projects.project(id)))
                "project-name" -> PluginOutcome.TextInput(text("project.name"), "project-save-name/$id", initialValue = projects.project(id).name)
                "project-icon" -> PluginOutcome.TextInput(text("project.icon"), "project-save-icon/$id",
                    initialValue = (projects.project(id).spec["icon"] as? JsonObject)?.get("symbol")?.jsonPrimitive?.content.orEmpty())
                "project-save-name", "project-save-icon" -> {
                    require(context.inputs["_callerPlugin"] == null)
                    val project = projects.project(id)
                    val value = context.inputs["value"].orEmpty().trim()
                    val spec = project.spec.toMutableMap()
                    if (action == "project-save-name") spec["name"] = JsonPrimitive(value)
                    else if (value.isEmpty()) spec.remove("icon")
                    else {
                        require(value.matches(Regex("[a-z][a-z0-9_]{0,79}")))
                        spec["icon"] = buildJsonObject { put("symbol", value) }
                    }
                    projects.save(project, "plugin-spec.json", JsonObject(spec).toString())
                    open(pages.project(projects.project(id)))
                }
                "editor-author", "editor-icon-file" -> {
                    require(context.inputs["_callerPlugin"] == null)
                    val project = projects.project(id)
                    val changes = project.spec.toMutableMap()
                    if (action == "editor-author") {
                        changes["author"] = JsonPrimitive(context.inputs["author"].orEmpty().trim())
                        projects.save(project, "plugin-spec.json", JsonObject(changes).toString())
                    } else {
                        val path = requireNotNull(context.inputs["resourcePath"])
                        require(path.startsWith("imports/editor-icons/"))
                        val source = environment.storage.file(path)
                        val targetPath = "assets/project-icon-${UUID.randomUUID()}.${source.extension}"
                        val artwork = projects.file(project, targetPath)
                        try {
                            require(source.extension in setOf("png", "svg") && source.length() in 1..1_048_576L)
                            source.copyTo(artwork)
                            changes["icon"] = buildJsonObject { put("path", targetPath) }
                            projects.save(project, "plugin-spec.json", JsonObject(changes).toString())
                        } catch (error: Exception) { artwork.delete(); throw error }
                        finally { source.delete() }
                    }
                    open(pages.project(projects.project(id)))
                }
                "open" -> PluginOutcome.OpenPage("manager")
                "files" -> open(files(projects.project(id), text))
                "file-open" -> {
                    val project = projects.project(id); val path = context.inputs["path"].orEmpty()
                    val file = if (path.isEmpty()) project.root else projects.file(project, path)
                    if (file.isDirectory) { dirs[id] = path; open(files(project, text)) }
                    else {
                        require((path.startsWith("sources/") || path.startsWith("assets/") || path in setOf("plugin-spec.json", "LICENSE", "NOTICE.md")) && file.isFile && file.length() <= 256 * 1024 && file.readBytes().none { it == 0.toByte() })
                        require(file.readText(Charsets.UTF_8).length <= 64000)
                        editing[id] = path; open(editor(project, path))
                    }
                }
                "file-parent" -> {
                    val project = projects.project(id); val next = context.inputs["directory"] ?: dirs[id].orEmpty().substringBeforeLast('/', "")
                    if (next.isNotEmpty()) require(projects.file(project, next).isDirectory)
                    dirs[id] = next; open(files(project, text))
                }
                "file-write" -> { val project = projects.project(id); projects.save(project, editing.getValue(id), context.inputs["value"].orEmpty()); open(editor(project, editing.getValue(id))) }
                "file-operation" -> operation(projects.project(id), context, text)
                "file-new" -> {
                    context.inputs["directory"]?.let { dirs[id] = it }
                    PluginOutcome.Menu(text("file.new"), listOf(PageAction("file-new-folder/$id", text("file.folder"), PluginIcon.ADD),
                        PageAction("file-new-java/$id", text("file.java"), PluginIcon.TEXT), PageAction("file-new-kotlin/$id", text("file.kotlin"), PluginIcon.TEXT),
                        PageAction("file-new-text/$id", text("file.text"), PluginIcon.TEXT)))
                }
                "file-new-folder", "file-new-java", "file-new-kotlin", "file-new-text" -> {
                    creating[id] = action.substringAfterLast('-'); PluginOutcome.TextInput(text("file.name"), "file-create/$id")
                }
                "file-create" -> {
                    val project = projects.project(id); val name = context.inputs["value"].orEmpty()
                    require(name.length in 1..100 && !name.contains('/'))
                    val kind = creating.remove(id) ?: throw BuilderIssue("error.create_expired")
                    val base = dirs[id].orEmpty().ifEmpty { if (kind == "text") "assets" else "sources" }
                    require(base == "sources" || base.startsWith("sources/") || base == "assets" || base.startsWith("assets/"))
                    if (kind == "text") require(base == "assets" || base.startsWith("assets/"))
                    val suffix = if (kind == "java") ".java" else if (kind == "kotlin") ".kt" else ""
                    val path = "$base/" + name + if (suffix.isNotEmpty() && !name.endsWith(suffix)) suffix else ""
                    val target = projects.file(project, path); require(!target.exists()); requireNotNull(target.parentFile).mkdirs()
                    if (kind == "folder") { require(target.mkdir()); open(files(project, text)) }
                    else { target.writeText("", Charsets.UTF_8); editing[id] = path; open(editor(project, path)) }
                }
                "file-delete-confirm" -> {
                    require(context.inputs["_callerPlugin"] == null)
                    val (project, paths) = deletes.remove(id) ?: throw BuilderIssue("error.confirm_expired")
                    paths.forEach { projects.file(project, it).deleteRecursively() }; open(files(project, text))
                }
                "file-import" -> { context.inputs["directory"]?.let { dirs[id] = it }; PluginOutcome.ImportResource(text("file.import"), "imports/resources", "file-imported/$id", multiple = true) }
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
                    open(files(project, text))
                }
                "toolchains" -> PluginOutcome.OpenPage("toolchains")
                "choose-toolchain" -> PluginOutcome.ImportResource(text("toolchain.import"), "imports/toolchains", "toolchain-import", zip = true)
                "download-toolchain" -> {
                    require(build?.isActive != true)
                    toolchain = OfficialNativeTools.install(environment, toolchains)
                    PluginOutcome.RefreshUi
                }
                "toolchain-import" -> toolchains.stage(environment.storage.file(requireNotNull(context.inputs["resourcePath"]))).let { pendingTools[it.token] = it; open(pages.toolchain(it)) }
                "toolchain-confirm" -> {
                    require(context.inputs["_callerPlugin"] == null)
                    val pending = pendingTools.remove(id) ?: throw BuilderIssue("error.review_expired")
                    toolchains.confirm(pending); toolchain = pending; problem = ""; PluginOutcome.OpenPage("toolchains")
                }
                "build" -> begin(projects.project(id.ifEmpty { requireNotNull(context.inputs["projectId"]) }), text,
                    context.inputs["_callerPlugin"] == null)
                "cancel" -> { build?.cancel(); PluginOutcome.Message(text("build.cancel_requested")) }
                "share" -> outputs.getValue(id).let { PluginOutcome.ExportResource(text("build.share"), PluginResource(it.relativeTo(environment.storage.root).invariantSeparatorsPath), it.name, "application/octet-stream") }
                "debug" -> {
                    require(context.inputs["_callerPlugin"] == null)
                    val file = outputs.getValue(id)
                    val staging = environment.storage.directory("debug-builds").resolve(file.name)
                    file.copyTo(staging, overwrite = true)
                    PluginOutcome.InstallPackage(PluginResource(staging.relativeTo(environment.storage.root).invariantSeparatorsPath))
                }
                "source-export" -> export(projects.project(id), text)
                "delete" -> open(PluginPage("delete/$id", text("project.delete"), listOf(PageBlock.Notice(text("project.delete_notice")), PageBlock.Actions(listOf(PageAction("delete-confirm/$id", text("confirm_delete"), PluginIcon.CLOSE))))))
                "delete-confirm" -> { require(context.inputs["_callerPlugin"] == null); projects.remove(id); PluginOutcome.OpenPage("manager") }
                else -> PluginOutcome.Message(text("error.action"))
            }
            revisions.value++; result
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: BuilderIssue) { PluginOutcome.Message(text(failure.key)) }
        catch (_: Exception) { PluginOutcome.Message(text("error.operation")) }
    }
    private fun stage(project: NativeProject, text: BuilderText): PluginOutcome {
        val token = UUID.randomUUID().toString(); pendingProjects[token] = project to hashFiles(project.root)
        return open(NativeBuilderPages(text).review(project, token))
    }
    private fun begin(project: NativeProject, text: BuilderText, interactive: Boolean): PluginOutcome {
        require(build?.isActive != true) { throw BuilderIssue("error.in_progress") }
        val workspace = environment.storage.directory("tmp/build-${UUID.randomUUID()}")
        val snapshot = workspace.resolve("source"); project.root.copyRecursively(snapshot)
        val source = projects.read(snapshot)
        outputs.remove(project.id)
        update(project, text("build.preparing"), running = true, ready = false, text)
        build = scope.launch {
            try {
                val selected = toolchain?.takeIf { it.supports(source) } ?: run {
                    update(project, text("toolchain.downloading"), running = true, ready = false, text)
                    OfficialNativeTools.install(environment, toolchains).also { toolchain = it }
                }
                val loader = toolchains.loader(selected, javaClass.classLoader!!)
                val dex = NativeCompiler(loader, selected).compile(source, workspace) { phase -> update(project, text(phase), running = true, ready = false, text) }
                currentCoroutineContext().ensureActive()
                val packed = packages.pack(source, dex, environment.appBroker, interactive)
                currentCoroutineContext().ensureActive()
                outputs[project.id] = packed
                update(project, text("build.done"), running = false, ready = true, text)
            } catch (cancel: CancellationException) { update(project, text("build.cancelled"), running = false, ready = false, text) }
            catch (failure: Throwable) {
                val detail = if (failure is BuilderIssue) text(failure.key) else failure.message?.take(1000).orEmpty()
                problem = if (detail.isBlank()) text("build.failed") else text("build.failed_detail", "detail" to detail)
                update(project, problem, running = false, ready = false, text)
            } finally { workspace.deleteRecursively() }
        }
        return PluginOutcome.OpenPage("build/${project.id}")
    }
    private fun update(project: NativeProject, feedback: String, running: Boolean, ready: Boolean, text: BuilderText) {
        cache(NativeBuilderPages(text).build(project, feedback, running, ready)); revisions.value++
    }
    private fun files(project: NativeProject, text: BuilderText): PluginPage {
        val directory = dirs[project.id].orEmpty()
        val root = if (directory.isEmpty()) project.root else projects.file(project, directory)
        val items = root.listFiles().orEmpty().sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }).map {
            PluginFileItem(it.relativeTo(project.root).invariantSeparatorsPath, it.name, it.isDirectory, if (it.isFile) it.length() else 0)
        }
        return PluginPage("files/${project.id}", text("project.files"), listOf(PageBlock.Files(text("file"), items, directory, "file-open/${project.id}", "file-operation/${project.id}",
            "file-import/${project.id}", "file-new/${project.id}", parentAction = "file-parent/${project.id}")))
    }
    private fun editor(project: NativeProject, path: String) = PluginPage("edit/${project.id}", path.substringAfterLast('/'), listOf(
        PageBlock.SourceEditor("native-${project.id}-${path.hashCode()}", projects.file(project, path).readText(Charsets.UTF_8), "file-write/${project.id}",
            when { path.endsWith(".kt") -> "kotlin"; path.endsWith(".java") -> "java"; else -> path.substringAfterLast('.', "text") })))
    private fun operation(project: NativeProject, context: PluginContext, text: BuilderText): PluginOutcome {
        val paths = PluginJson.decodeFromString<List<String>>(requireNotNull(context.inputs["paths"])); require(paths.isNotEmpty())
        val sources = paths.map { path -> require(path.startsWith("sources/") || path.startsWith("assets/") || path.startsWith("dependencies/")); projects.file(project, path).also { require(it.exists()) } }
        return when (context.inputs["op"]) {
            "delete" -> { val token = UUID.randomUUID().toString(); deletes[token] = project to paths
                open(PluginPage("file-delete/$token", text("file.delete"), listOf(PageBlock.Notice(text("file.delete_notice", "count" to paths.size.toString())), PageBlock.Actions(listOf(PageAction("file-delete-confirm/$token", text("confirm_delete"), PluginIcon.CLOSE)))))) }
            "rename" -> {
                require(sources.size == 1)
                val name = context.inputs["destination"].orEmpty(); require(name.isNotBlank() && !name.contains('/'))
                val source = sources.single(); val target = BuilderFiles.relative(project.root, source.relativeTo(project.root).invariantSeparatorsPath.substringBeforeLast('/') + "/$name")
                require(!target.exists()); java.nio.file.Files.move(source.toPath(), target.toPath()); open(files(project, text))
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
                open(files(project, text))
            }
            else -> throw BuilderIssue("error.file_action")
        }
    }
    private fun export(project: NativeProject, text: BuilderText): PluginOutcome {
        val output = environment.storage.file("exports/${project.id}.kigsource").also { requireNotNull(it.parentFile).mkdirs() }
        ZipOutputStream(output.outputStream()).use { zip -> project.root.walkTopDown().filter { it.isFile }.forEach { file ->
            zip.putNextEntry(ZipEntry(file.relativeTo(project.root).invariantSeparatorsPath)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
        } }
        return PluginOutcome.ExportResource(text("project.export"), PluginResource(output.relativeTo(environment.storage.root).invariantSeparatorsPath), output.name, "application/zip")
    }
    private fun hashFiles(root: File) = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).invariantSeparatorsPath to BuilderFiles.digest(it) }
    private fun open(page: PluginPage): PluginOutcome { cache(page); return PluginOutcome.OpenPage(page.id) }
    private fun cache(page: PluginPage) = synchronized(pageCache) {
        fun bytes(value: PluginPage) = PluginJson.encodeToString(PluginPage.serializer(), value).toByteArray().size
        require(bytes(page) <= 400 * 1024) { throw BuilderIssue("error.page_too_large") }
        if (page.blocks.any { it is PageBlock.SourceEditor }) pageCache.entries.removeAll { (id, value) -> id != page.id && value.blocks.any { it is PageBlock.SourceEditor } }
        pageCache[page.id] = page; while (pageCache.size > 32 || pageCache.values.sumOf(::bytes) > 400 * 1024) pageCache.remove(pageCache.keys.first())
    }
    override suspend fun close() { scope.cancel(); withTimeoutOrNull(3000) { build?.join() } }
}
