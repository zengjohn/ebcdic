package com.example.transcoder;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

/**
 * OutputStream backed by chunked memory-mapped file regions.
 * This version correctly unmaps previous mapped region using UnmapUtil and
 * tracks absolute positions properly.
 */
@Slf4j
public class ChunkedMappedOutputStream extends OutputStream {

    private final FileChannel channel;
    private final long chunkSize;

    // absolute file position where the current mapping starts
    private long mappingStart = 0L;

    // current mapped buffer
    private MappedByteBuffer mapped;

    private final File file;

    public ChunkedMappedOutputStream(File file, long chunkSize) throws IOException {
        this.file = file;
        this.chunkSize = chunkSize;
        this.channel = FileChannel.open(file.toPath(),
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE);
        // ensure file has at least chunkSize capacity for first mapping
        mapNext(Math.max(1, chunkSize));
    }

    private void mapNext(long minSize) throws IOException {
        // Unmap previous mapping if exists
        if (mapped != null) {
            try {
                // advance mappingStart by current buffer's position (bytes already written in that mapping)
                mappingStart += mapped.position();
            } catch (Throwable ignored) {}
            try {
                UnmapUtil.unmap(mapped);
            } catch (Throwable t) {
                log.warn("Unmap failed: {}", t.getMessage());
            }
            mapped = null;
        }

        long mapSize = Math.max(minSize, chunkSize);
        long requiredSize = mappingStart + mapSize;
        // ensure file size is at least requiredSize
        long currentSize = channel.size();
        if (currentSize < requiredSize) {
            channel.truncate(requiredSize);
        }
        mapped = channel.map(FileChannel.MapMode.READ_WRITE, mappingStart, mapSize);
        log.debug("Mapped output chunk: start={}, size={}", mappingStart, mapped.capacity());
    }

    @Override
    public synchronized void write(int b) throws IOException {
        if (mapped == null || !mapped.hasRemaining()) {
            mapNext(1);
        }
        mapped.put((byte) b);
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) throws IOException {
        int remaining = len;
        int srcPos = off;
        while (remaining > 0) {
            if (mapped == null || !mapped.hasRemaining()) {
                mapNext(remaining);
            }
            int toWrite = Math.min(mapped.remaining(), remaining);
            mapped.put(b, srcPos, toWrite);
            srcPos += toWrite;
            remaining -= toWrite;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            if (mapped != null) {
                try {
                    mapped.force();
                } catch (Throwable ignored) {}
                // advance mappingStart by position so file length is correct
                try {
                    mappingStart += mapped.position();
                } catch (Throwable ignored) {}
                UnmapUtil.unmap(mapped);
                mapped = null;
            }
            // truncate file to actual written length
            long finalSize = mappingStart;
            if (channel.size() > finalSize) {
                channel.truncate(finalSize);
            }
        } finally {
            channel.close();
        }
    }
}