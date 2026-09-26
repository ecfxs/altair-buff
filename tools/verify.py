#!/usr/bin/env python3
"""使用已安装的 JDK/SDK 和缓存 Kotlin 编译器，离线验证；不启动守护进程或安装 APK。"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
TC = ROOT / ".toolchain"
JAVA = Path(os.environ.get("JAVA_HOME", TC / "jdk-17/Contents/Home")) / "bin"
SDK = Path(os.environ.get("ANDROID_SDK_ROOT", TC / "android-sdk"))
CACHE = TC / "gradle-home/caches/modules-2/files-2.1"
SRC = ROOT / "probe/src/main/java/com/altair/probe"


def dependency(group, artifact, version):
    matches = list((CACHE / group / artifact / version).glob("*/*.jar"))
    if len(matches) != 1:
        raise SystemExit(f"缺少离线依赖：{group}:{artifact}:{version}")
    return str(matches[0])


def run(args):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True)


def main():
    if not (ROOT / "gradlew").is_file():
        raise SystemExit("缺少 gradlew 启动脚本")
    wrapper = ROOT / "gradle/wrapper/gradle-wrapper.properties"
    if not wrapper.is_file():
        raise SystemExit("缺少 Gradle Wrapper 配置")
    properties = dict(
        line.split("=", 1)
        for line in wrapper.read_text(encoding="utf-8").splitlines()
        if "=" in line and not line.lstrip().startswith("#")
    )
    if "gradle-8.2-bin.zip" not in properties.get("distributionUrl", ""):
        raise SystemExit("Gradle Wrapper 必须固定在 8.2-bin")

    stdlib = dependency("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.22")
    compiler = [dependency("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "1.9.22"),
                stdlib, dependency("org.jetbrains.kotlin", "kotlin-script-runtime", "1.9.22"),
                dependency("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
                dependency("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
                dependency("org.jetbrains", "annotations", "13.0")]
    android = SDK / "platforms/android-33/android.jar"
    with tempfile.TemporaryDirectory(prefix="altair-verify-") as temp:
        classes = Path(temp) / "classes"
        classes.mkdir()
        run([JAVA / "javac", "-encoding", "UTF-8", "-source", "17", "-target", "17",
             "-cp", android, "-d", classes, *sorted(SRC.glob("*.java"))])
        aapt = SDK / "build-tools/33.0.1/aapt2"
        resources = Path(temp) / "resources.zip"
        run([aapt, "compile", "--dir", ROOT / "probe/src/main/res", "-o", resources])
        import xml.etree.ElementTree as ET
        version = dict(line.split("=", 1) for line in (ROOT / "version.properties").read_text().splitlines()
                       if "=" in line and not line.startswith("#"))
        strings = ET.parse(ROOT / "probe/src/main/res/values/strings.xml")
        signer = strings.find("./string[@name='release_signer_sha256']")
        if signer is None or signer.text != version.get("releaseSignerSha256"):
            raise SystemExit("应用签名信任锚与发布清单不一致")
        manifest = ET.parse(ROOT / "probe/src/main/AndroidManifest.xml")
        manifest_root = manifest.getroot()
        android_ns = "{http://schemas.android.com/apk/res/android}"
        application = manifest_root.find("application")
        if application is None or application.get(android_ns + "usesCleartextTraffic") != "false":
            raise SystemExit("AndroidManifest 必须禁止明文 HTTP")
        network = ET.parse(ROOT / "probe/src/main/res/xml/network_security_config.xml").getroot()
        base = network.find("base-config")
        if base is None or base.get("cleartextTrafficPermitted") != "false":
            raise SystemExit("network_security_config 必须禁止全局明文 HTTP")
        manifest_root.set("package", "com.altair.probe")
        manifest_file = Path(temp) / "AndroidManifest.xml"
        manifest.write(manifest_file, encoding="utf-8", xml_declaration=True)
        generated = Path(temp) / "generated"
        generated.mkdir()
        run([aapt, "link", "--manifest", manifest_file, "-I", android, "--java", generated,
             "--min-sdk-version", "26", "--target-sdk-version", "30",
             "-o", Path(temp) / "resources.ap_", resources])
        run([JAVA / "javac", "-encoding", "UTF-8", "-source", "17", "-target", "17",
             "-cp", os.pathsep.join([str(android), str(classes)]), "-d", classes,
             *sorted(generated.rglob("*.java"))])

        kotlin_classes = Path(temp) / "kotlin-classes"
        kotlin_classes.mkdir()
        cp = os.pathsep.join([str(android), str(classes), stdlib])
        run([JAVA / "java", "-cp", os.pathsep.join(compiler),
             "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
             "-jvm-target", "17", "-classpath", cp, "-d", kotlin_classes, *sorted(SRC.glob("*.kt"))])
        print("PASS: 全部 Java / Kotlin 源码编译（Android API 33）", flush=True)
        production_classes = sorted(classes.rglob("*.class")) + sorted(kotlin_classes.rglob("*.class"))
        tests = sorted((ROOT / "probe/src/test/java/com/altair/probe").glob("*.java"))
        test_cp = os.pathsep.join([str(classes), str(kotlin_classes), stdlib])
        run([JAVA / "javac", "-encoding", "UTF-8", "-cp", test_cp, "-d", classes, *tests])
        run([JAVA / "java", "-ea", "-cp", test_cp,
             "com.altair.probe.AutomationRegression"])
        # Android 的 D8 同时检查实际 dex 转换，覆盖 app_process 所需的 Java helper。
        dex = Path(temp) / "dex"
        dex.mkdir()
        run([JAVA / "java", "-cp", SDK / "build-tools/33.0.1/lib/d8.jar",
             "com.android.tools.r8.D8", "--min-api", "26", "--lib", android,
             "--output", dex, *production_classes, stdlib])
        print("PASS: DEX 转换（minSdk 26）；未安装或签名 APK", flush=True)
        print("PASS: Android 资源、清单与 HTTPS-only 策略链接", flush=True)


if __name__ == "__main__":
    main()
