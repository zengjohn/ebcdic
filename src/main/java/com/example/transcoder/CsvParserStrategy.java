package com.example.transcoder;

import java.io.Reader;
import java.io.Writer;

/**
 * CSV parser strategy interface to allow switching between uniVocity and Commons CSV.
 * Implementations should read from the Reader and write normalized output to the Writer.
 * Returns number of records processed.
 */
public interface CsvParserStrategy {
    long parseAndWrite(Reader inputReader, Writer outputWriter) throws Exception;
}