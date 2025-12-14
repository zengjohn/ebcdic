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
