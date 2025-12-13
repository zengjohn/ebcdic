package com.example.transcoder;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;

import java.io.Reader;
import java.io.Writer;

/**
 * Apache Commons CSV implementation (fallback/alternative).
 */
@Slf4j
public class CommonsCsvParserStrategy implements CsvParserStrategy {

    @Data
    public static class Config {
        private char delimiter = ',';
        private char quoteChar = '"';
        private boolean skipHeader = false;
    }

    private final Config cfg;

    public CommonsCsvParserStrategy(Config cfg) {
        this.cfg = cfg;
    }

    @Override
    public long parseAndWrite(Reader inputReader, Writer outputWriter) throws Exception {
        CSVFormat format = CSVFormat.DEFAULT
                .withDelimiter(cfg.delimiter)
                .withQuote(cfg.quoteChar)
                .withRecordSeparator('\n')
                .withIgnoreSurroundingSpaces();

        CSVParser parser = format.parse(inputReader);
        CSVPrinter printer = new CSVPrinter(outputWriter, format);

        long count = 0;
        for (org.apache.commons.csv.CSVRecord rec : parser) {
            printer.printRecord(rec);
            count++;
            if ((count % 100_000) == 0) {
                log.info("commons-csv parsed {} rows", count);
                printer.flush();
            }
        }
        printer.flush();
        return count;
    }
}