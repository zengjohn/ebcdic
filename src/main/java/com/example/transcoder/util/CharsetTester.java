package com.example.transcoder.util;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class CharsetTester {
    // usage: java CharsetTester sample.bin outdir IBM1388
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: CharsetTester <sampleFile> <outDir> <charsetName>");
            System.exit(1);
        }
        File sample = new File(args[0]);
        File outDir = new File(args[1]);
        String name = args[2];

        if (!outDir.exists()) outDir.mkdirs();

        byte[] data = Files.readAllBytes(sample.toPath());
        List<String> candidates = buildCandidates(name);

        for (String c : candidates) {
            try {
                Charset cs = Charset.forName(c);
                String decoded = new String(data, cs);
                File out = new File(outDir, "decoded_" + c.replaceAll("[^0-9A-Za-z-]", "_") + ".txt");
                try (Writer w = new OutputStreamWriter(new FileOutputStream(out), Charset.forName("UTF-8"))) {
                    w.write("=== charset=" + c + " ===\n");
                    w.write(decoded);
                }
                System.out.println("Tried: " + c + " -> wrote to " + out.getAbsolutePath());
            } catch (Exception ex) {
                System.out.println("Charset.forName failed for '" + c + "': " + ex.getMessage());
            }
        }
    }

    private static List<String> buildCandidates(String original) {
        List<String> list = new ArrayList<>();
        String lower = original.toLowerCase().replaceAll("[^0-9a-z]", "");
        list.add(original);
        // common patterns
        list.add("Cp" + lower.replaceAll("[^0-9]", ""));
        list.add("cp" + lower.replaceAll("[^0-9]", ""));
        list.add("IBM" + lower.replaceAll("[^0-9]", ""));
        list.add("ibm-" + lower.replaceAll("[^0-9]", ""));
        list.add("ibm" + lower.replaceAll("[^0-9]", ""));
        list.add("EBCDIC-" + lower.replaceAll("[^0-9]", ""));
        // some other typical cp names
        list.add("Cp037");
        list.add("Cp1047");
        // dedupe
        List<String> out = new ArrayList<>();
        for (String s : list) if (!out.contains(s)) out.add(s);
        return out;
    }
}