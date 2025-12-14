package com.example.transcoder;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
        UnmapUtil.unmap(mapped);
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
            // break; // 在 ChunkedMappedInputStream.read(byte[],off,len) 中移除那个早期 return/break，让它循环读取直到填满请求长度或到达 EOF——这样能减少 read 调用次数，提高性能，且仍然保持跨块正确性（因为每次读取都会先把 mapped 的 bytes 复制到目标数组，再 unmap）。 这样一次 read 可以跨多个映射块复制数据到用户缓冲区，减少系统/方法调用次数并提升吞吐。
            // continue loop to try to satisfy more bytes (possibly mapNext and continue)
        }
        return totalRead == 0 ? -1 : totalRead;
    }

    @Override
    public void close() throws IOException {
        try {
            UnmapUtil.unmap(mapped);
        } finally {
            channel.close();
        }
    }
}