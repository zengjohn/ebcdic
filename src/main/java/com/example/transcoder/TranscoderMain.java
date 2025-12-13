package com.example.transcoder;

import com.example.transcoder.UniVocityCsvParserStrategy.Config;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Main orchestrator for transcoding CSV files using memory-mapped IO and ICU4J (via Charset).
 *
 * Example usage:
 * java -jar ebcdic-csv-transcoder.jar input.csv IBM1388 output.csv utf-8 univocity
 */
@Slf4j
public class TranscoderMain {

    @Data
    public static class Options {
        private File inputFile;
        private String inputCharset;
        private File outputFile;
        private String outputCharset = "UTF-8";
        private long chunkSize = 1L * 1024 * 1024 * 1024; // 1GB default
        private String parser = "univocity"; // or "commons"
        private char delimiter = ',';
        private char quoteChar = '"';
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("Usage: java -jar ebcdic-csv-transcoder.jar <inputFile> <inputCharset> <outputFile> <outputCharset> [parser=univocity|commons] [chunkSizeBytes] [delimiter] [quoteChar]");
            System.out.println("Example: java -jar ... input.csv IBM1388 output.csv UTF-8 univocity 1073741824 , \"");
            return;
        }
        Options options = new Options();
        options.setInputFile(new File(args[0]));
        options.setInputCharset(args[1]);
        options.setOutputFile(new File(args[2]));
        options.setOutputCharset(args[3]);
        if (args.length > 4) options.setParser(args[4]);
        if (args.length > 5) options.setChunkSize(Long.parseLong(args[5]));
        if (args.length > 6) options.setDelimiter(args[6].charAt(0));
        if (args.length > 7) options.setQuoteChar(args[7].charAt(0));

        log.info("Options: {}", options);

        Charset inCharset = resolveCharset(options.getInputCharset());
        Charset outCharset = resolveCharset(options.getOutputCharset());

        // Create InputStream backed by MappedByteBuffer chunks
        try (ChunkedMappedInputStream in = new ChunkedMappedInputStream(options.getInputFile(), options.getChunkSize());
             Reader reader = new InputStreamReader(in, inCharset);
             ChunkedMappedOutputStream outStream = new ChunkedMappedOutputStream(options.getOutputFile(), options.getChunkSize());
             Writer writer = new OutputStreamWriter(outStream, outCharset)) {

            CsvParserStrategy parserStrategy;
            if ("commons".equalsIgnoreCase(options.getParser())) {
                CommonsCsvParserStrategy.Config ccfg = new CommonsCsvParserStrategy.Config();
                ccfg.setDelimiter(options.getDelimiter());
                ccfg.setQuoteChar(options.getQuoteChar());
                parserStrategy = new CommonsCsvParserStrategy(ccfg);
            } else {
                UniVocityCsvParserStrategy.Config ucfg = new UniVocityCsvParserStrategy.Config();
                ucfg.setDelimiter(options.getDelimiter());
                ucfg.setQuoteChar(options.getQuoteChar());
                parserStrategy = new UniVocityCsvParserStrategy(ucfg);
            }

            long start = System.currentTimeMillis();
            long records = parserStrategy.parseAndWrite(reader, writer);
            writer.flush();
            long end = System.currentTimeMillis();
            log.info("Completed. Records: {}, Time(s): {}, RPS: {}", records, (end - start)/1000.0, records / Math.max(1, (end - start)/1000));
        } catch (Throwable t) {
            log.error("Transcoding failed: {}", t.getMessage(), t);
            // Attempt cleanup
            // do not delete output by default; depending on your preference, you can delete partial output files here.
            throw t;
        }
    }

    /**
     * Resolve charset via ICU4J (if available) or fallback to standard Charset.
     * Also contains a small alias map for some EBCDIC names.
     */
    private static Charset resolveCharset(String name) {
        if (name == null || name.isEmpty()) return Charset.defaultCharset();
        String n = name.trim();
        try {
            return Charset.forName(n);
        } catch (Exception ignored) {}

        String digits = n.replaceAll("\\D+", "");
        List<String> candidates = new ArrayList<>();
        if (!digits.isEmpty()) {
            candidates.add("Cp" + digits);
            candidates.add("cp" + digits);
            candidates.add("IBM" + digits);
            candidates.add("ibm-" + digits);
            candidates.add("ibm" + digits);
        }
        // common fallbacks
        candidates.add("Cp037");
        candidates.add("Cp1047");
        candidates.add("Cp1147");

        // Scan available charsets to find ones that contain digits or original name
        Charset.availableCharsets().forEach((k, v) -> {
            String lower = k.toLowerCase();
            if (lower.contains(n.toLowerCase()) || (!digits.isEmpty() && lower.contains(digits))) {
                candidates.add(k);
            }
        });

        for (String c : candidates) {
            try {
                Charset cs = Charset.forName(c);
                System.out.println("Resolved charset '" + name + "' -> '" + c + "'");
                return cs;
            } catch (Exception ex) {
                // continue
            }
        }

        System.out.println("Failed to resolve charset '" + name + "', falling back to UTF-8");
        return Charset.forName("UTF-8");
    }

}