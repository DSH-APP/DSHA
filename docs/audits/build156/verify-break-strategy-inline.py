#!/usr/bin/env python3
"""Audit-only SDK/JDK proof for the two build156 Overlay break-strategy fixes."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[3]
AUDIT = Path(__file__).resolve().parent
OUT = AUDIT / "overlay-break-strategy-proof"
SDK = Path("F:/DSHA/_toolchains/android-sdk/platforms/android-37.0")
JDK = Path("F:/DSHA/_toolchains/jdk-17/bin")
D8 = Path("F:/DSHA/_toolchains/android-sdk/build-tools/36.0.0/lib/d8.jar")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def run(args, target):
    result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", timeout=45)
    text = result.stdout + result.stderr
    (OUT / target).write_text(text, encoding="utf-8")
    require(result.returncode == 0, target + " failed: " + text)
    commands.append({"argv": [str(arg) for arg in args], "exitCode": result.returncode,
                     "log": (OUT / target).relative_to(ROOT).as_posix(),
                     "sha256": sha(OUT / target)})
    return text


OUT.mkdir(exist_ok=True)
commands = []
source = ROOT / "app/src/main/java/com/deepseekharness/app/OverlayController.java"
before = AUDIT / "f7328-lint-failure/OverlayController.before.java"
old = b"android.text.Layout.BREAK_STRATEGY_SIMPLE"
new = b"android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE"
require(before.read_bytes().count(old) == 2, "Expected exactly two original Layout symbols")
require(source.read_bytes() == before.read_bytes().replace(old, new),
        "Overlay source has changes beyond the two authorized symbols")

jar = SDK / "android.jar"
versions = SDK / "data/api-versions.xml"
annotations = SDK / "data/annotations.zip"
version = run([JDK / "java.exe", "-version"], "java-version.log")
require(re.search(r'version "17[.]', version) is not None, "JDK17 required")
constant = run([JDK / "javap.exe", "-J-Duser.language=en", "-J-Duser.country=US",
                "-J-Dfile.encoding=UTF-8", "-classpath", jar, "-verbose",
                "android.graphics.text.LineBreaker"], "sdk-linebreaker-javap.log")
require(re.search(r'public static final int BREAK_STRATEGY_SIMPLE;.*?ConstantValue: int 0',
                  constant, re.S) is not None, "SDK ConstantValue is not int zero")

root = ET.parse(versions).getroot()
api = {}
for cls in root.findall("class"):
    name = cls.get("name")
    if name in {"android/graphics/text/LineBreaker", "android/text/StaticLayout$Builder",
                "android/widget/TextView"}:
        api[name] = {"class": cls.attrib, "members": [member.attrib for member in cls
                     if member.get("name", "").startswith(("setBreakStrategy", "BREAK_STRATEGY_SIMPLE"))]}
require(api["android/graphics/text/LineBreaker"]["class"]["since"] == "29",
        "Unexpected LineBreaker API metadata")
require(api["android/text/StaticLayout$Builder"]["class"]["since"] == "23",
        "Unexpected StaticLayout.Builder API metadata")
require(api["android/text/StaticLayout$Builder"]["members"][0].get("since", "23") == "23",
        "Unexpected StaticLayout.Builder.setBreakStrategy API metadata")
require(api["android/widget/TextView"]["members"][0]["since"] == "23",
        "Unexpected TextView.setBreakStrategy API metadata")
annotation_items = []
with zipfile.ZipFile(annotations) as archive:
    for name in ["android/text/annotations.xml", "android/widget/annotations.xml"]:
        for item in ET.fromstring(archive.read(name)).findall("item"):
            if item.get("name", "") in {
                "android.text.StaticLayout.Builder android.text.StaticLayout.Builder setBreakStrategy(int) 0",
                "android.widget.TextView void setBreakStrategy(int) 0"}:
                value = ET.tostring(item, encoding="unicode")
                require("android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE" in value,
                        "Actual SDK annotation does not accept the chosen symbol")
                annotation_items.append({"member": item.get("name"), "xml": value})
require(len(annotation_items) == 2, "Expected actual annotation for both call sites")

probe = """public final class BreakStrategyInlineProbe {
  public static android.text.StaticLayout.Builder layout(android.text.StaticLayout.Builder builder) {
    return builder.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE);
  }
  public static void text(android.widget.TextView view) {
    view.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE);
  }
}
"""
with tempfile.TemporaryDirectory(prefix="dsha-build156-constant-") as directory:
    temp = Path(directory)
    fixture = temp / "BreakStrategyInlineProbe.java"
    fixture.write_text(probe, encoding="utf-8")
    run([JDK / "javac.exe", "-J-Duser.language=en", "-J-Duser.country=US",
         "-J-Dfile.encoding=UTF-8", "--release", "17", "-encoding", "UTF-8",
         "-classpath", jar, "-d", temp, fixture], "javac.log")
    bytecode = run([JDK / "javap.exe", "-J-Duser.language=en", "-J-Duser.country=US",
                    "-J-Dfile.encoding=UTF-8", "-classpath", temp, "-c", "-verbose",
                    "BreakStrategyInlineProbe"], "probe-javap.log")
    compiled = temp / "BreakStrategyInlineProbe.class"
    payload = compiled.read_bytes()
    # javac leaves an unused owner Class entry in the constant pool. It is not
    # an executable field/class access; report it honestly and verify DEX too.
    instructions = re.findall(r'^\s*\d+:.*$', bytecode, re.M)
    require(not any("LineBreaker" in instruction for instruction in instructions),
            "Executable API29 class reference remains")
    require(bytecode.count("iconst_0") == 2, "Both method arguments must compile to iconst_0")
    require("getstatic" not in bytecode, "Constant field access remains at runtime")
    (OUT / "BreakStrategyInlineProbe.class").write_bytes(payload)
    dex_dir = temp / "dex"
    dex_dir.mkdir()
    run([JDK / "java.exe", "-Duser.language=en", "-Duser.country=US", "-Dfile.encoding=UTF-8",
         "-classpath", D8, "com.android.tools.r8.D8", "--min-api", "23", "--lib", jar,
         "--output", dex_dir, compiled], "d8-min-api23.log")
    dex = (dex_dir / "classes.dex").read_bytes()
    require(b"LineBreaker" not in dex, "API29 LineBreaker reference remains in API23 DEX")
    (OUT / "classes.dex").write_bytes(dex)
(OUT / "BreakStrategyInlineProbe.java").write_text(probe, encoding="utf-8")

format_source = ROOT / "app/build/java-format/ui-overlay-break-strategy156.json"
format_data = json.loads(format_source.read_text(encoding="utf-8"))
(OUT / "format-receipt.json").write_bytes(format_source.read_bytes())
receipt = {
    "schema": 1, "status": "PASS_SDK37_CONSTANT_INLINE",
    "source": source.relative_to(ROOT).as_posix(), "sourceSha256": sha(source),
    "sourceBeforeSha256": sha(before), "authorizedChangeCount": 2,
    "symbol": new.decode(), "constantValue": 0,
    "sdk": {"androidJar": str(jar), "androidJarSha256": sha(jar),
            "apiVersionsSha256": sha(versions), "annotationsSha256": sha(annotations),
            "apiMetadata": api, "actualIntDefAnnotations": annotation_items},
    "commands": commands,
    "formatCommand": ["python", "-B", "tools/format-java.py", "--check", "--files",
                      source.relative_to(ROOT).as_posix(), "--java", str(JDK / "java.exe"),
                      "--offline", "--report", format_source.relative_to(ROOT).as_posix()],
    "formatExitCode": 0, "formatReceiptSha256": sha(format_source),
    "formatReceipt": format_data,
    "bytecode": {"sha256": sha(OUT / "BreakStrategyInlineProbe.class"), "iconst_0": 2,
                 "getstatic": 0, "runtimeInstructionsReferencingLineBreaker": 0,
                 "unusedLineBreakerConstantPoolClassEntry": True},
    "dex": {"tool": str(D8), "toolSha256": sha(D8), "minApi": 23,
            "sha256": sha(OUT / "classes.dex"), "lineBreakerReferences": 0},
    "conclusion": "JDK17 compiled both actual SDK37 APIs with literal zero, without executable API29 LineBreaker field/class access. javac retains an unused owner Class entry, which SDK D8 removes: the minAPI23 DEX has no LineBreaker name/reference at all. Both target APIs exist from API23. The production change consists of the same two constant expressions only.",
    "missingEvidence": ["Actual Low release DEX/APK and fresh two-flavor Release Lint are deferred to the root integrated build.",
                        "No API23 physical-device rendering or interaction was performed by this proof."]}
(OUT / "receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("PASS: two exact source symbol replacements, SDK37 ConstantValue=0/IntDef, API23 targets, JDK17 iconst_0 x2/no executable API29 access, D8 minAPI23 DEX with no LineBreaker reference, pinned formatter/token receipt")
