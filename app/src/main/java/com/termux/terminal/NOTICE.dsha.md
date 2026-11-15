# Termux terminal-emulator sources

The 13 upstream Java files in this directory come from `terminal-emulator/src/main/java/com/termux/terminal`
in [termux/termux-app v0.118.0](https://github.com/termux/termux-app/tree/6e2689f55295fa444be8ac8592c527c2c5ef3253/terminal-emulator),
commit `6e2689f55295fa444be8ac8592c527c2c5ef3253`. The upstream repository's explicit
Apache-2.0 exception for terminal-emulator is reproduced in `tools/termux-jni/LICENSE.upstream.md`;
the complete license is `app/src/main/assets/licenses/Termux-terminal-Apache-2.0.txt`.
This exception does not apply to the entire Termux app.

DSHA modified `TerminalSession.java`, `TerminalEmulator.java`, and `TerminalSessionClient.java`
to use a bounded, nonblocking input queue, preserve bracketed-paste ordering, and report input
rejection or incomplete writes. `TerminalInputQueue.java` is a DSHA contribution under the
project's MIT license, not an upstream file. The remaining 10 upstream Java files are unchanged.
