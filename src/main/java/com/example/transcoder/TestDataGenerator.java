package com.example.transcoder;

import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.Random;

/**
 * Test data generator to create large CSV files for performance testing.
 *
 * Usage example:
 *   java -cp ... com.example.transcoder.TestDataGenerator output.csv 1024 1000000 UTF-8
 *
 * Generates 1,000,000 rows of sample data; each row has a mix of ascii and CJK text.
 */
@Slf4j
public class TestDataGenerator {
    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("Usage: TestDataGenerator <outputFile> <rowSizeApproxBytes> <numRows> <encoding>");
            System.out.println("Example: TestDataGenerator /tmp/test.csv 200 500000 UTF-8");
            return;
        }
        File out = new File(args[0]);
        int rowSize = Integer.parseInt(args[1]);
        long numRows = Long.parseLong(args[2]);
        String encoding = args[3];

        Charset cs = Charset.forName(encoding);

        try (FileOutputStream fos = new FileOutputStream(out);
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {
            Random rnd = new Random(12345);
            String[] sample = new String[]{
                    "alpha", "beta", "gamma", "δelta", "数据", "测试", "漢字", "行内\n换行", "含,逗号", "\"quote\""
            };

            for (long i = 0; i < numRows; i++) {
                // construct a row with multiple columns
                StringBuilder sb = new StringBuilder();
                sb.append(i).append(',');
                int target = rowSize - 20;
                while (sb.length() < target) {
                    sb.append(sample[(int) (rnd.nextInt(sample.length))]).append(',');
                }
                sb.append("end").append('\n');
                byte[] bytes = sb.toString().getBytes(cs);
                bos.write(bytes);
                if ((i & 0xFFFF) == 0) {
                    log.info("Generated {} rows", i);
                    bos.flush();
                }
            }
            bos.flush();
        }
        log.info("Wrote test file {} rows={}, encoding={}", out.getAbsolutePath(), numRows, encoding);
    }
}