package com.example.transcoder;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.MappedByteBuffer;

/**
 * Utility to unmap MappedByteBuffer (best-effort).
 * Uses multiple fallback strategies to maximize compatibility across JVMs.
 */
@Slf4j
public final class UnmapUtil {

    private UnmapUtil() {}

    public static void unmap(MappedByteBuffer buffer) {
        if (buffer == null) return;
        try {
            // Try Java 9+ Unsafe.invokeCleaner
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object theUnsafe = f.get(null);
            Method invokeCleaner = unsafeClass.getMethod("invokeCleaner", java.nio.ByteBuffer.class);
            invokeCleaner.invoke(theUnsafe, buffer);
            log.debug("Unmapped MappedByteBuffer using sun.misc.Unsafe.invokeCleaner");
            return;
        } catch (Throwable t) {
            // ignore and fall through
        }

        try {
            // Try invoking Cleaner via DirectByteBuffer.cleaner() -> Cleaner.clean()
            Method cleanerMethod = buffer.getClass().getMethod("cleaner");
            cleanerMethod.setAccessible(true);
            Object cleaner = cleanerMethod.invoke(buffer);
            if (cleaner != null) {
                Method clean = cleaner.getClass().getMethod("clean");
                clean.setAccessible(true);
                clean.invoke(cleaner);
                log.debug("Unmapped MappedByteBuffer via buffer.cleaner().clean()");
                return;
            }
        } catch (Throwable t) {
            // ignore and fall through
        }

        // If we reach here, best-effort failed
        log.warn("Unable to explicitly unmap MappedByteBuffer (best-effort attempts failed). It will be reclaimed by GC eventually.");
    }
}