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

/** Build-time adaptation for ECJ and D8. Does not run in the phone app. */
class AndroidCompilerPatcher {
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
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, signature, exceptions)) {
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
}
