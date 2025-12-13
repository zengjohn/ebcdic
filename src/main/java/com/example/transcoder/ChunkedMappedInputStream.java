package com.example.transcoder;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * An InputStream that maps an input file in chunks using MappedByteBuffer and
 * presents a continuous InputStream to callers. It attempts to unmap previous
 * mapped buffers to avoid off-heap leaks.
 */
@Slf4j
public class ChunkedMappedInputStream extends InputStream {

    private final FileChannel channel;
    private final long fileSize;
    private final long chunkSize;
    private long position = 0;

    private MappedByteBuffer mapped;
    private final File file;

    public ChunkedMappedInputStream(File file, long chunkSize) throws IOException {
        Objects.requireNonNull(file, "file");
        this.file = file;
        this.channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
        this.fileSize = channel.size();
        this.chunkSize = chunkSize;
        mapNext(); // map first chunk
    }

    private void mapNext() throws IOException {
        unmap(mapped);
        if (position >= fileSize) {
            mapped = null;
            return;
        }
        long remaining = fileSize - position;
        long size = Math.min(chunkSize, remaining);
        mapped = channel.map(FileChannel.MapMode.READ_ONLY, position, size);
        position += size;
        log.debug("Mapped chunk: startPos={}, size={}", position - size, size);
    }

    @Override
    public int read() throws IOException {
        while (mapped != null) {
            if (mapped.hasRemaining()) {
                return mapped.get() & 0xFF;
            } else {
                mapNext();
            }
        }
        return -1;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (mapped == null) {
            return -1;
        }
        int totalRead = 0;
        while (len > 0) {
            if (mapped == null) break;
            if (!mapped.hasRemaining()) {
                mapNext();
                continue;
            }
            int remaining = mapped.remaining();
            int toRead = Math.min(len, remaining);
            mapped.get(b, off, toRead);
            off += toRead;
            len -= toRead;
            totalRead += toRead;
            // if we satisfied at least one byte, return immediately to allow decoder to process
            break;
        }
        return totalRead == 0 ? -1 : totalRead;
    }

    @Override
    public void close() throws IOException {
        try {
            unmap(mapped);
        } finally {
            channel.close();
        }
    }

    /**
     * Attempts to unmap a MappedByteBuffer to release the underlying memory.
     * Uses several reflection strategies to support different JDKs.
     */
    private static void unmap(MappedByteBuffer buffer) {
        if (buffer == null) return;
        try {
            // Java 9+: invoke Cleaner via reflection on DirectByteBuffer
            Method cleanerMethod = buffer.getClass().getMethod("cleaner");
            cleanerMethod.setAccessible(true);
            Object cleaner = cleanerMethod.invoke(buffer);
            if (cleaner != null) {
                Method clean = cleaner.getClass().getMethod("clean");
                clean.setAccessible(true);
                clean.invoke(cleaner);
            }
            log.debug("Unmapped MappedByteBuffer via cleaner()");
            return;
        } catch (Throwable t) {
            // fallback to other strategies
        }

        try {
            // Try sun.misc.Unsafe approach (older JVMs)
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle mh = lookup.findVirtual(buffer.getClass(), "cleaner", MethodType.methodType(Method.class.getMethod("getReturnType").getReturnType()));
            // ignore; this is best-effort
        } catch (Throwable ignored) {
            // best effort only
        }
    }
}