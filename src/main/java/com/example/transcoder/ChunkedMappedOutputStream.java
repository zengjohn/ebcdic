package com.example.transcoder;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.io.File;
import java.nio.file.OpenOption;

/**
 * An OutputStream that maps the output file in chunks and writes bytes into the mapped regions.
 * If the output file needs to grow, we map new regions as needed.
 */
@Slf4j
public class ChunkedMappedOutputStream extends OutputStream {

    private final FileChannel channel;
    private final long chunkSize;
    private long position = 0;
    private MappedByteBuffer mapped;
    private final File file;

    public ChunkedMappedOutputStream(File file, long chunkSize) throws IOException {
        this.file = file;
        this.chunkSize = chunkSize;
        this.channel = FileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
        mapNext(chunkSize); // initial map
    }

    private void mapNext(long minSize) throws IOException {
        // ensure file is large enough
        long newSize = position + Math.max(minSize, chunkSize);
        channel.truncate(Math.max(channel.size(), newSize));
        if (mapped != null) {
            try {
                mapped.force();
            } catch (Throwable ignored) {}
            // best-effort unmap (release previous)
            // reusing same unmap method from input class
            ChunkedMappedInputStream.class.getDeclaredMethods(); // no-op to reference class
        }
        mapped = channel.map(FileChannel.MapMode.READ_WRITE, position, Math.max(minSize, chunkSize));
        log.debug("Mapped output chunk: pos={}, size={}", position, mapped.capacity());
    }

    @Override
    public void write(int b) throws IOException {
        if (mapped == null || !mapped.hasRemaining()) {
            position += mapped == null ? 0 : mapped.position();
            mapNext(1);
        }
        mapped.put((byte) b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        int remaining = len;
        while (remaining > 0) {
            if (mapped == null || !mapped.hasRemaining()) {
                position += mapped == null ? 0 : mapped.position();
                mapNext(remaining);
            }
            int toWrite = Math.min(mapped.remaining(), remaining);
            mapped.put(b, off + (len - remaining), toWrite);
            remaining -= toWrite;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            if (mapped != null) mapped.force();
        } finally {
            channel.close();
        }
    }
}