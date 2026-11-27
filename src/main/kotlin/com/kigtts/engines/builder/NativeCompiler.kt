package com.kigtts.engines.builder

import kotlinx.coroutines.*
import java.io.*
import java.lang.reflect.InvocationTargetException
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** No compiler classes link into the app or extension. A confirmed toolchain provides them. */
class NativeCompiler(private val loader: ClassLoader, private val toolchain: BuilderToolchain) {
    suspend fun compile(project: NativeProject, output: File, status: (String) -> Unit): File = withContext(Dispatchers.IO) {
        val sources = project.root.resolve("sources").walkTopDown().filter { it.isFile && it.extension in setOf("java", "kt") }.toList()
        require(sources.size in 1..64 && sources.sumOf { it.length() } <= 1024 * 1024) { throw BuilderIssue("error.sources") }
        val classes = output.resolve("classes").also { it.mkdirs() }
        val dependencies = project.root.resolve("dependencies").listFiles().orEmpty().filter { it.isFile && it.extension == "jar" }
        val libraries = toolchain.classpath.map { BuilderFiles.relative(toolchain.directory, it) }
        val classpath = (libraries + dependencies + classes).joinToString(File.pathSeparator) { it.absolutePath }
        val diagnostic = StringWriter()
        fun checked(block: () -> Unit) {
            try { block() } catch (failure: InvocationTargetException) { throw IllegalStateException(failure.targetException.message ?: "Incompatible build tools", failure.targetException) }
        }
        val kotlin = sources.filter { it.extension == "kt" }
        if (kotlin.isNotEmpty()) {
            require("kotlin" in toolchain.languages) { throw BuilderIssue("error.kotlin_missing") }
            status("build.kotlin"); coroutineContext.ensureActive()
            val bytes = ByteArrayOutputStream()
            checked {
                val type = loader.loadClass(toolchain.kotlinClass)
                val instance = type.getDeclaredConstructor().newInstance()
                val args = (listOf("-no-jdk", "-no-stdlib", "-no-reflect", "-jvm-target", "1.8", "-classpath", classpath, "-d", classes.absolutePath) + sources.map { it.absolutePath }).toTypedArray()
                val method = type.methods.firstOrNull { it.name == "exec" && it.parameterTypes.contentEquals(arrayOf(PrintStream::class.java, Array<String>::class.java)) }
                    ?: throw BuilderIssue("error.kotlin_cli")
                val thread = Thread.currentThread()
                val originalLoader = thread.contextClassLoader
                val result = try {
                    thread.contextClassLoader = loader
                    PrintStream(bytes, true, "UTF-8").use { method.invoke(instance, it, args) }
                } finally {
                    thread.contextClassLoader = originalLoader
                }
                diagnostic.write(bytes.toString("UTF-8")); require(result?.toString() == "OK") { diagnostic.toString().takeLast(16000) }
            }
        }
        val java = sources.filter { it.extension == "java" }
        if (java.isNotEmpty()) {
            require("java" in toolchain.languages) { throw BuilderIssue("error.java_missing") }
            status("build.java"); coroutineContext.ensureActive()
            checked {
                val type = loader.loadClass(toolchain.javaClass)
                val method = type.methods.firstOrNull { it.name == "compile" && it.parameterTypes.size == 4 && it.parameterTypes[0] == Array<String>::class.java }
                    ?: throw BuilderIssue("error.java_cli")
                val platform = libraries.firstOrNull { it.name == "android.jar" } ?: throw BuilderIssue("error.android_jar")
                val bootClasspath = (listOf(platform) + libraries.filter { it.name == "android-java-linkage.jar" })
                    .joinToString(File.pathSeparator) { it.absolutePath }
                val args = (listOf("-source", "1.8", "-target", "1.8", "-proc:none", "-encoding", "UTF-8", "-bootclasspath", bootClasspath,
                    "-classpath", classpath, "-d", classes.absolutePath) + java.map { it.absolutePath }).toTypedArray()
                require(method.invoke(null, args, PrintWriter(diagnostic), PrintWriter(diagnostic), null) == true) { diagnostic.toString().takeLast(16000) }
            }
        }
        coroutineContext.ensureActive()
        require(classes.resolve(project.entryClass.replace('.', '/') + ".class").isFile) { throw BuilderIssue("error.entry_class") }
        val jar = output.resolve("program.jar")
        JarOutputStream(jar.outputStream()).use { archive -> classes.walkTopDown().filter { it.isFile }.forEach { file ->
            archive.putNextEntry(JarEntry(file.relativeTo(classes).invariantSeparatorsPath)); file.inputStream().use { it.copyTo(archive) }; archive.closeEntry()
        } }
        status("build.dex")
        val dex = output.resolve("dex").also { it.mkdirs() }
        checked {
            val d8 = loader.loadClass(toolchain.d8Class)
            val command = loader.loadClass("com.android.tools.r8.D8Command")
            val origin = loader.loadClass("com.android.tools.r8.origin.Origin")
            val args = mutableListOf("--release", "--min-api", project.minSdk.toString(), "--output", dex.absolutePath)
            libraries.forEach { args += listOf("--lib", it.absolutePath) }
            args += jar.absolutePath; dependencies.forEach { args += it.absolutePath }
            val builder = command.getMethod("parse", Array<String>::class.java, origin).invoke(null, args.toTypedArray(), origin.getMethod("unknown").invoke(null))
            val built = builder.javaClass.getMethod("build").invoke(builder)
            d8.getMethod("run", command).invoke(null, built)
        }
        coroutineContext.ensureActive()
        require(dex.resolve("classes.dex").isFile && dex.resolve("classes.dex").length() > 32) { throw BuilderIssue("error.dex") }
        output.resolve("build.log").writeText(diagnostic.toString().takeLast(16000), Charsets.UTF_8)
        dex
    }
}
