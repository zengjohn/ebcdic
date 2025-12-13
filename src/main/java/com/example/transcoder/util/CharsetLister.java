package com.example.transcoder.util;

import java.nio.charset.Charset;
import java.util.Map;

public class CharsetLister {
    public static void main(String[] args) {
        System.out.println("Available charsets count: " + Charset.availableCharsets().size());
        for (Map.Entry<String, Charset> e : Charset.availableCharsets().entrySet()) {
            System.out.println("Canonical: " + e.getKey());
            // print aliases if any via charset().aliases() (Java 17+ has aliases() method);
            // but Charset doesn't expose aliases() directly; we can print known name only.
        }

        // quick search for "1388" or "ibm"
        System.out.println("\n-- Search for '1388' or 'ibm1388' -- ");
        Charset.availableCharsets().forEach((k, v) -> {
            String lower = k.toLowerCase();
            if (lower.contains("1388") || lower.contains("ibm1388") || lower.contains("ibm-1388") || lower.contains("cp1388")) {
                System.out.println("Match: " + k);
            }
        });
    }
}