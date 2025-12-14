# 字符集别名确认



你已知“IBM1388” 这是主机侧给出的 codepage 名称 / CCSID。问题是：Java / ICU4J 是否把这个名字当作一个可识别的 charset alias（即 Charset.forName("IBM1388") 能否成功）。

很多主机 EBCDIC codepage 在 Java/ICU4J 中有不同的别名（例如 Cp037、IBM037、Cp1047 等），所以需要做两件事：

1. 在目标环境（有 ICU4J 在 classpath 的 JVM）上确认 ICU4J/Java 支持哪个名字（canonical name / alias）对应于 CCSID 1388；
2. 如果不支持传入的名字，可以用一组候选名尝试匹配，或直接选择 ICU4J 支持的等效 codepage（需要你或主机侧确认实际 CCSID 对应的字节表映射）。

下面给出两段小工具代码，帮你在本地快速定位和验证：

- CharsetLister：列出当前 JVM（包含 ICU4J provider 时）的所有可用 charset 名称与别名，并查找包含 “1388” / “ibm1388” 的条目。
- CharsetTester：对一个小的样本文件尝试若干候选 charset（包括原始名与若干自动生成的别名尝试），解码并输出成 UTF-8 文件，便于你肉眼检验哪种映射正确（汉字/标点是否正确）。

把下面的两个类加入你的项目（或单独编译运行），在有 ICU4J 的 classpath 下运行即可。



## 如何使用（建议流程）

从主机上拷贝一个小样本 CSV（几个 KB，含典型汉字/符号/换行/分隔符的记录），例如 sample.bin。

在带 ICU4J 的 JVM 上运行 CharsetLister 检查是否已有名称包含 1388：

```bash
java -cp target/ebcdic-csv-transcoder-1.0.0.jar com.example.transcoder.util.CharsetLister
```

运行 CharsetTester，用候选名称去尝试解码 sample.bin 输出多个 UTF-8 文件，比较结果：

```bash
java -cp target/ebcdic-csv-transcoder-1.0.0.jar com.example.transcoder.util.CharsetTester sample.bin ./out IBM1388
```

打开 out 目录下每个 decoded\_\*.txt，找到解码正确（汉字显示正常、逗号/引号位置合适）的那个 charset 名称，记录下来（例如 "Cp1388"、"ibm-1388"、"Cp037" 等）。

更新代码建议（自动化尝试多种 alias） 我建议在 TranscoderMain.resolveCharset 中加入更强的 fallback 策略：当 Charset.forName(name) 失败时，按一系列可能的 alias 尝试（"Cp" + digits、"IBM" + digits、去掉中横线/下划线等），并且遍历 Charset.availableCharsets() 检查其 canonical name / alias 是否包含数字 "1388"。下面是一个可直接替换你项目中 resolveCharset 的改进实现，自动打印尝试过程并返回第一个可用的 Charset：



要点总结

- 你只需要把 sample 文件拿到本地并在有 ICU4J 的 JVM 上运行上面两个工具，就能很快确认 Java/ICU4J 环境中可用的 charset 名称，以及哪个名称能正确把 EBCDIC-IBM1388 解为正确字符。
- 如果你愿意，把那个 sample 发给我（注意敏感数据），我可以帮你判断（但目前我无法在你的机器上直接运行），所以最佳办法是在你的环境本地运行 CharsetTester 并按输出人工核验。
- 一旦确定了在你环境中可用的名字（例如 "Cp1388" 或 "ibm-1388"），就把那个名字传给转码程序 TranscoderMain 的 inputCharset 参数，或把 resolveCharset 的 alias map 固定好。





- 在 Java 9+ 上若遇到反射受限（IllegalAccess），可通过 JVM 启动参数放开访问，例如（如需要）： --add-opens java.base/sun.misc=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED （视 JVM 版本和实际反射路径可能需调整）

提交与测试步骤建议

1. 在本地仓库（或远程分支）替换以上三个文件（对应路径 src/main/java/com/example/transcoder/...）。
2. 本地构建与单元/编译检查：
   - mvn -U -DskipTests=false clean package
   - 若使用 Lombok，确保 IDE 已启用 Lombok 注解处理器。
3. 运行集成测试（用小文件先手工跑）：
   - 先用 TestDataGenerator 生成一个小样本（UTF-8 或你用来验证的 EBCDIC 样本）。
   - 用 TranscoderMain 将其转码为 UTF-8，确保输出正确、无异常且无过度的堆外内存增长。
4. 观察 native/堆外内存（验证 unmap 是否生效）：
   - 运行转码任务时观察进程本地内存（例如使用 `ps aux` / `top` / `jcmd <pid> VM.native_memory summary` 或 jvisualvm）。如果 unmap 生效，长期运行时 native 内存不应持续增长到文件体积级别。
5. 若在 Java9+ 报 IllegalAccessException，可按上面说明加上 --add-opens 对应模块（或运行在 Java8 下进行验证）。





确认：我在你的仓库 zengjohn/ebcdic 看到了你已 push 的文件（ChunkedMappedOutputStream.java 已更新并在 fix/unmap 分支）。我检查了当前实现，并针对你关心的“行被分到两个或多个 MappedByteBuffer 块（跨块）”的问题，给出结论与改进建议、以及验证方法。

当前实现对跨块问题的分析与结论

- 输入端（ChunkedMappedInputStream）：
  - 实现把文件按块顺序映射并通过 InputStream 接口按字节顺序返回数据。映射是连续的（mapNext 按文件偏移顺序映射下一个块）。
  - Charset 解码器是在 InputStreamReader（或其它 Reader）层处理字节到字符的转换。InputStreamReader 使用 CharsetDecoder，它是有状态的并能正确处理不完整的多字节序列（例如 UTF‑8 或 GBK）跨多次读取的场景。
  - 由于我们的 InputStream 在线性字节流上返回数据（即使块边界处 byte 被分割，解码器会在后续读请求中接收剩余字节并完成解码），所以多字节字符跨块一般能被正确处理，不会产生损坏字符。
  - 关键安全点：在切换映射（unmap 旧映射）之前，我们保证已把该映射中的字节复制出（read 方法把字节复制到调用者提供的 byte[]）。因此不会在尚未复制的数据上就释放映射，避免解码器访问到已释放的内存。
- 解析层（uniVocity / Commons CSV）：
  - 解析器在字符流（Reader）层工作，按字符逐步解析 CSV。只要字符解码正确（上面保证），解析器能处理字段内换行或一行跨块的情况——因为 parser 是基于连续字符流的，会一直等待直到完整记录被读取和解析完成。
  - 因此单行被文件块分割（byte-level split）不是问题，重点是保证字节到字符解码在块边界处能无缝进行。

需要注意的风险和细节

1. 仍需保证 InputStream 的 read 实现把字节“按需”提供给 CharsetDecoder，不要在映射尚有未返回字节时就 unmap。当前实现已避免在有 remaining 时立即 unmap，只有在 mapped 没有 remaining 并 mapNext 被调用时才 unmap，上述逻辑是安全的。
2. 当前 read(byte[],off,len) 实现有一句 break（注释写“如果满足至少1字节就返回以便 decoder 处理”）。这会使每次调用最多只返回一段已映射区域中的数据（或更少），这对正确性没问题，但会降低吞吐（更多次系统/方法调用）。可以考虑去掉该 break，让一次 read 调用尽量填满用户缓冲区（在内部跨映射继续读），以提高性能。
3. unmap 是 best-effort（反射/Unsafe 等方式）。在某些 JVM 或安全限制下可能失败，但不会造成立即错误，堆外内存最终仍会由 GC 回收。建议在跑批量超大文件时监控进程 native 内存使用，确认无持续泄漏。



测试/验证跨块解析的建议

- 使用 TestDataGenerator 生成多种测试文件：

  - 小 chunk（例如传递给 TranscoderMain 的 chunkSize = 1MB）但写入超长行（例如每行 2MB 或更多），以模拟“单行跨多个映射”的情况。
  - 并用不同编码（UTF-8、GBK、EBCDIC 映射）测试，确保字符没有损坏并且 CSV 中的字段（包含换行、逗号、引号）被正确解析。

- 示例生成命令（在你的项目中）：

  - 先生成大单行文件（在 TestDataGenerator 中可以添加一种模式生成单行超过 chunkSize 的行），或者写一个小脚本快速生成：

    ```
    # 生成一个包含 2MB 行的文件（UTF-8）
    head -c $((2*1024*1024)) < /dev/urandom | base64 | sed 's/.*/&,end/' > bigline.csv
    ```

  - 用 TranscoderMain 指定小 chunkSize 来运行：

    ```
    java -jar target/ebcdic-...jar bigline.csv UTF-8 out.csv UTF-8 univocity 1048576 , \"
    ```

  - 检查 out.csv 是否只有一行且完整无破损。

总结（要点）

- 现在的实现在设计上已经考虑到跨块的正确性（按顺序提供字节流，利用 CharsetDecoder 的状态性处理不完整多字节字符）。CSV 解析器在字符层工作，因此单行跨块在逻辑上是可处理的。
- 为了更高的性能与更健壮的跨块处理，我建议：
  1. 去掉 read(byte[]) 中的早期 break，让一次 read 尽可能填满用户缓冲（我给出了修改示例）；
  2. 在 Reader 层使用 BufferedReader（64KB+）；
  3. 做充分的压力测试（用较小 chunkSize 模拟跨块并生成非常长的行）。
- 我可以：
  - 直接把上面的 read(byte[]) 修复补丁提交到你的 fix/unmap 分支（或新分支），并创建 PR；或者
  - 给出完整修改过的文件并说明如何在本地替换测试。
