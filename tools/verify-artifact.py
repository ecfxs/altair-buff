#!/usr/bin/env python3
"""只读核对 APK 的包名、版本和既有签名，输出可复核的产物摘要；不签名、不安装。"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    version = dict(line.split("=", 1) for line in
                   (ROOT / "version.properties").read_text().splitlines()
                   if "=" in line and not line.startswith("#"))
    signer = ET.parse(ROOT / "probe/src/main/res/values/strings.xml").find("./string[@name='release_signer_sha256']")
    if signer is None or signer.text != version["releaseSignerSha256"]:
        raise SystemExit("FAIL: 应用签名信任锚与发布清单不一致")
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT", ROOT / ".toolchain/android-sdk"))
    java = Path(os.environ.get("JAVA_HOME", ROOT / ".toolchain/jdk-17/Contents/Home")) / "bin/java"
    build_tools = sdk / "build-tools/33.0.1"
    metadata = subprocess.check_output([str(build_tools / "aapt2"), "dump", "badging", str(args.apk)], text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", metadata)
    if package is None:
        raise SystemExit("FAIL: 无法读取 APK 包名与版本")
    expected = ("com.altair.probe", version["versionCode"], version["versionName"])
    if package.groups() != expected:
        raise SystemExit(f"FAIL: APK {package.groups()} 与源码版本 {expected} 不一致")
    certificates = subprocess.check_output([str(java), "-jar", str(build_tools / "lib/apksigner.jar"),
                                           "verify", "--print-certs", str(args.apk)], text=True)
    signers = re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]+)", certificates)
    if [s.lower() for s in signers] != [version["releaseSignerSha256"].lower()]:
        raise SystemExit("FAIL: APK 当前签名与既有正式签名不一致")
    digest = hashlib.sha256()
    with args.apk.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    print(json.dumps({"package": expected[0], "versionCode": expected[1], "versionName": expected[2],
                      "signerSha256": signers[0].lower(), "apkSha256": digest.hexdigest()}, ensure_ascii=False))


if __name__ == "__main__":
    main()
