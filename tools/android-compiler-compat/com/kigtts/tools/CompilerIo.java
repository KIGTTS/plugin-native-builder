package com.kigtts.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Java stream operations missing from older Android releases. */
public final class CompilerIo {
    private CompilerIo() {}

    public static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transferTo(input, output);
        return output.toByteArray();
    }

    public static byte[] readNBytes(InputStream input, int length) throws IOException {
        if (length < 0) throw new IllegalArgumentException("Negative read length");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[Math.min(length, 8192)];
        while (length > 0) {
            int count = input.read(buffer, 0, Math.min(length, buffer.length));
            if (count < 0) break;
            output.write(buffer, 0, count);
            length -= count;
        }
        return output.toByteArray();
    }

    public static int readNBytes(InputStream input, byte[] buffer, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > buffer.length - length) {
            throw new IndexOutOfBoundsException();
        }
        int total = 0;
        while (total < length) {
            int count = input.read(buffer, offset + total, length - total);
            if (count < 0) break;
            total += count;
        }
        return total;
    }

    public static long transferTo(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            output.write(buffer, 0, count);
            total += count;
        }
        return total;
    }
}
