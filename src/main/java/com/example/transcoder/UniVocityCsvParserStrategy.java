package com.example.transcoder;

import com.univocity.parsers.common.ParsingContext;
import com.univocity.parsers.common.processor.RowProcessor;
import com.univocity.parsers.csv.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.Reader;
import java.io.Writer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * uniVocity parser implementation.
 */
@Slf4j
public class UniVocityCsvParserStrategy implements CsvParserStrategy {

    @Data
    public static class Config {
        private char delimiter = ',';
        private char quoteChar = '"';
        private boolean skipEmptyLines = true;
        private boolean headers = false;
        private int maxLoggedErrors = 1000;
    }

    private final Config cfg;

    public UniVocityCsvParserStrategy(Config cfg) {
        this.cfg = cfg;
    }

    @Override
    public long parseAndWrite(Reader inputReader, Writer outputWriter) throws Exception {
        CsvParserSettings settings = new CsvParserSettings();
        settings.getFormat().setDelimiter(cfg.delimiter);
        settings.getFormat().setQuote(cfg.quoteChar);
        settings.setIgnoreLeadingWhitespaces(true);
        settings.setIgnoreTrailingWhitespaces(true);
        settings.setSkipEmptyLines(cfg.skipEmptyLines);
        settings.setNullValue("");
        settings.setMaxCharsPerColumn(10_000_000); // safety
        final AtomicLong counter = new AtomicLong(0);
        final AtomicLong errorCount = new AtomicLong(0);

        settings.setProcessor(new RowProcessor() {
            @Override
            public void processStarted(ParsingContext context) {
            }

            @Override
            public void rowProcessed(String[] row, ParsingContext context) {
                try {
                    // write row as CSV using simple escaping (we keep same delimiter and quote)
                    writeRow(outputWriter, row);
                    counter.incrementAndGet();
                    if (counter.get() % 100_000 == 0) {
                        log.info("uniVocity parsed {} rows", counter.get());
                        outputWriter.flush();
                    }
                } catch (Exception e) {
                    long err = errorCount.incrementAndGet();
                    if (err <= cfg.maxLoggedErrors) {
                        log.warn("Error writing row {}: {}", counter.get(), e.getMessage());
                    }
                }
            }

            @Override
            public void processEnded(ParsingContext context) {
            }
        });

        CsvParser parser = new CsvParser(settings);
        try {
            parser.parse(inputReader);
        } catch (Exception ex) {
            // uniVocity will throw on unrecoverable errors; we log and continue if possible
            log.error("uniVocity parser threw exception: {}", ex.getMessage(), ex);
            throw ex;
        } finally {
            outputWriter.flush();
        }
        return counter.get();
    }

    private void writeRow(Writer writer, String[] row) throws Exception {
        for (int i = 0; i < row.length; i++) {
            if (i > 0) writer.write(cfg.delimiter);
            String field = row[i];
            if (field == null) field = "";
            boolean needsQuote = field.indexOf(cfg.delimiter) >= 0 || field.indexOf('\n') >= 0 || field.indexOf(cfg.quoteChar) >= 0;
            if (needsQuote) {
                writer.write(cfg.quoteChar);
                writer.write(field.replace(String.valueOf(cfg.quoteChar), String.valueOf(cfg.quoteChar) + cfg.quoteChar));
                writer.write(cfg.quoteChar);
            } else {
                writer.write(field);
            }
        }
        writer.write('\n');
    }
}