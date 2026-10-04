# D-STYLE-01 全体自有 Java 格式化计划

根源码冻结时六个 `app/src` source set 共 703 个 Java 文件：`main`、`low`、`standard`、`debug`、`androidTest`、`test`。其中两项由生成器严格管理，格式化范围为 701 个 app Java 文件，另有 `tools/java-format/JavaTokenFingerprint.java` 继续纳入既有 adopted 清单。最终显式格式化清单共 702 项。

`app/src/main/java/com/deepseekharness/app/ShellService.java` 是锁内受保护的历史 provenance 原件，明确排除。`BuiltinPluginRegistry.java` 和 `CredentialPathRules.java` 分别由 `generate-builtin-plugins.py`、`generate-credential-paths.py` 产生并做严格字节核验，也显式排除。`UiMessages.java` 生成在 `app/build/generated/uiLanguage`，从未进入源码清单。`history`、`upstream`、`vendor`、`third_party`、`third-party`、`generated` 路径同样排除；格式器逐文件复核真实路径、链接祖先和所属范围。最终 702 项清单与目标覆盖差异为零。

执行方式：实际自有文件已逐条写进 `tools/java-format-files.json`，由 `tools/format-java-tree.py` 校验清单恰好覆盖目标，再按 source set/一级包分批调用锁定的 `tools/format-java.py`。共 28 批，最大批现为 164，低于单次 256 上限。`--write` 先对全部批次做语法/token 预检与 SHA 快照，再分批写入；每批保存格式化前后 SHA-256、真实 javac token SHA-256 和 diff，并逐文件核对最终写入字节。首轮曾误纳两处生成类，历史 704 项/659 项变动与 token 证明原样保存在 `java-format-evidence.json`；两类现由各自生成器恢复规范字节并从清单排除，有效格式化范围为 702 项，其中 657 项发生格式变动。随后 DNS 的两处语义修复由兼容代理单列证明。最终检查发现 `UiLanguagePreferenceTest.java` 的注释缩进需由格式器再收敛一次，已在根代理授权下完成；`test/util` 156 文件重查零差异。生成边界修正后 `main/util` 164 文件重查零差异，其余批次的当前输入 SHA 均与此前干净检查相同。当前 702 项字节清单及两处生成原字节 SHA 见 `java-format-current-style.json`。CI 已接入整树 `--check` 门禁，两个生成器的原有严格字节检查继续执行。根代理负责后续两 flavor 编译/单测与发布范围验证。
