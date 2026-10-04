# Java formatter

`tools/java-format.lock.json` pins Google Java Format **1.22.0** and its exact JAR bytes.
Both the [official release](https://github.com/google/google-java-format/releases/tag/v1.22.0)
and the official Maven artifact were downloaded and compared; Maven's published SHA-1
matches those bytes. The lock records the calculated SHA-256, rather than claiming
that Maven published a SHA-256 sidecar. The command and token guard require **JDK 17**.
The JAR and compiled guard live only in ignored `app/build/java-format`.

Choose a package batch explicitly after its owner freezes the source:

```sh
python3 tools/format-java.py --check --files app/src/main/java/com/deepseekharness/app/runtime/RuntimeTrial.java
python3 tools/format-java.py --write --files app/src/main/java/com/deepseekharness/app/runtime/RuntimeTrial.java --report app/build/java-format/runtime-trial.json
python3 tools/format-java.py --check --files app/src/main/java/com/deepseekharness/app/runtime/RuntimeTrial.java
```

`--files changed` selects staged, unstaged and untracked Java files using Git; it
does not traverse the whole source tree. The entire selection must pass ownership,
path, syntax and token checks before any write. `--files` and either `--check` or
`--write` are required. There is no automatic whole-repository write. Paths outside
the project, links, generated files, history, third-party source trees and the
decompiled `ShellService.java` original are refused. Import sorting, unused import
removal, string reflow and Javadoc reflow are disabled. The actual JDK lexer checks
that every non-comment token, including literal spelling, remains identical;
unsupported literal changes fail instead of being waived.

`tools/java-format-files.json` is the explicit adoption list. Add a file only after
formatting it and reviewing the separate formatting diff. Source CI runs:

```sh
python3 tools/format-java.py --check --files adopted
python3 tools/test-java-format.py
```

CI must fail when an adopted file needs formatting. Other files have not yet
adopted this formatter; the list does not suppress or ignore violations in adopted
files. A passing small list does not claim the whole Java tree was formatted.
Use `JAVA_HOME`, `DSHA_JAVA17` (the executable path), or `--java` to select JDK 17.
`--offline` refuses a missing cache, and every cached JAR is checked before running.
Exit codes are **0** for success, **1** for formatting needed, **2** for an invalid
selection, source, toolchain, checksum, or token change. `--report` records per-file
before/after SHA-256, the matching token digest and an exact unified diff beneath
`app/build/java-format`; copy the reviewed report into an audit record as needed.
