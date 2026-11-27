import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Build-time adaptation for compilers distributed in the Android toolchain. */
class AndroidCompilerPatcher {
    private static final String PERFORMANCE_MANAGER = "org/jetbrains/kotlin/util/PerformanceManager";
    private static final String LOW_MEMORY_INIT =
        "org/jetbrains/kotlin/com/intellij/openapi/util/LowMemoryWatcherManager$2";
    private static final String JAVA_SHIM = "org/jetbrains/kotlin/com/intellij/util/DefaultJava11Shim";
    private static final String CONCURRENT_LONG_MAP =
        "()Lorg/jetbrains/kotlin/com/intellij/util/containers/ConcurrentLongObjectMap;";
    private static final Set<String> STREAM_METHODS = Set.of(
        "readAllBytes()[B", "readNBytes(I)[B", "readNBytes([BII)I", "transferTo(Ljava/io/OutputStream;)J");

    public static void main(String[] args) throws Exception {
        try (JarFile source = new JarFile(args[0]);
             JarOutputStream output = new JarOutputStream(java.nio.file.Files.newOutputStream(Path.of(args[1])))) {
            for (Enumeration<JarEntry> entries = source.entries(); entries.hasMoreElements();) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.matches("META-INF/[^/]+\\.(SF|RSA|DSA)")) continue;
                output.putNextEntry(new JarEntry(name));
                try (InputStream input = source.getInputStream(entry)) {
                    output.write(name.endsWith(".class") ? adapt(input.readAllBytes()) : input.readAllBytes());
                }
                output.closeEntry();
            }
        }
    }

    private static byte[] adapt(byte[] code) {
        ClassReader reader = new ClassReader(code);
        ClassWriter writer = new ClassWriter(reader, 0);
        String className = reader.getClassName();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, desc, signature, exceptions);
                if (replaceManagementBeanCallers(className, name, desc, method)) return null;
                return new MethodVisitor(Opcodes.ASM9, method) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean itf) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && owner.startsWith("java/") && owner.endsWith("InputStream")
                            && STREAM_METHODS.contains(method + descriptor)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/kigtts/tools/CompilerIo", method,
                                "(Ljava/io/InputStream;" + descriptor.substring(1), false);
                        } else {
                            super.visitMethodInsn(opcode, owner, method, descriptor, itf);
                        }
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static boolean replaceManagementBeanCallers(String owner, String name, String descriptor, MethodVisitor method) {
        if (owner.equals(PERFORMANCE_MANAGER) && name.equals("initializeCurrentThread") && descriptor.equals("()V")) {
            method.visitCode();
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
            method.visitFieldInsn(Opcodes.PUTFIELD, owner, "thread", "Ljava/lang/Thread;");
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(2, 1);
        } else if (owner.equals(PERFORMANCE_MANAGER) && name.equals("currentTime")
                && descriptor.equals("()Lorg/jetbrains/kotlin/util/Time;")) {
            String time = Type.getReturnType(descriptor).getInternalName();
            method.visitCode();
            method.visitTypeInsn(Opcodes.NEW, time);
            method.visitInsn(Opcodes.DUP);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "android/os/Debug", "threadCpuTimeNanos", "()J", false);
            method.visitInsn(Opcodes.DUP2);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, time, "<init>", "(JJJ)V", false);
            method.visitInsn(Opcodes.ARETURN);
            method.visitMaxs(8, 1);
        } else if (owner.equals(JAVA_SHIM) && name.equals("createConcurrentLongObjectMap") && descriptor.equals(CONCURRENT_LONG_MAP)) {
            method.visitCode();
            final String adapter = "com/kigtts/tools/CompilerLongMap";
            method.visitTypeInsn(Opcodes.NEW, adapter);
            method.visitInsn(Opcodes.DUP);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, adapter, "<init>", "()V", false);
            method.visitInsn(Opcodes.ARETURN);
            method.visitMaxs(2, 1);
        } else if (descriptor.equals("()V") && ((owner.equals(PERFORMANCE_MANAGER) && name.equals("enableExtendedStats"))
                || (owner.equals(LOW_MEMORY_INIT) && name.equals("run")))) {
            // Android has no JVM management beans. Compilation and phase timing remain available.
            method.visitCode();
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 1);
        } else {
            return false;
        }
        method.visitEnd();
        return true;
    }
}
