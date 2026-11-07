#!/usr/bin/env python3
"""打造 APK（不需要 Gradle，只用 Android SDK build-tools + JDK 17）。

    python build_apk.py

依赖（用环境变量指定，或放在 android/toolchain/ 下）：
    ANDROID_SDK   Android SDK 目录（里面要有 build-tools/34.0.0 和 platforms/android-35）
    JAVA_HOME     JDK 17 目录（javac / keytool）

产物：android/HualiElec.apk
"""

from __future__ import annotations

import glob
import os
import shutil
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.join(HERE, "app")
BUILD = os.path.join(HERE, "build")
OUT_APK = os.path.join(HERE, "HualiElec.apk")
KEYSTORE = os.path.join(HERE, "debug.keystore")

BUILD_TOOLS_VERSION = "34.0.0"
PLATFORM = "android-35"


def _first_existing(*candidates: str) -> str:
    for c in candidates:
        if c and os.path.isdir(c):
            return c
    return ""


def _major_version(java_home: str) -> int:
    """读取 JDK 的 release 文件拿主版本号（拿不到就当作 0）。"""
    try:
        with open(os.path.join(java_home, "release"), encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if line.startswith("JAVA_VERSION="):
                    ver = line.split("=", 1)[1].strip().strip('"')
                    return int(ver.split(".")[0])
    except Exception:  # noqa: BLE001
        pass
    return 0


def _pick_jdk(*candidates: str) -> str:
    """优先选 17+ 的 JDK（d8/apksigner 需要 11+，build-tools 34 建议 17）。"""
    fallback = ""
    for c in candidates:
        if c and os.path.isdir(c):
            major = _major_version(c)
            if major >= 17:
                return c
            if not fallback:
                fallback = c
    return fallback


SDK = _first_existing(
    os.environ.get("ANDROID_SDK", ""),
    os.path.join(HERE, "toolchain", "sdk"),
    r"C:\Users\luobi\android\sdk",
    os.path.expanduser("~/Android/Sdk"),
)
JAVA_HOME = _pick_jdk(
    os.environ.get("JAVA_HOME", ""),
    os.path.join(HERE, "toolchain", "jdk17"),
    r"C:\Users\luobi\android\jdk17",
    os.path.expanduser("~/jdk17"),
)


def run(cmd: list[str]) -> None:
    print("$", " ".join(cmd))
    env = dict(os.environ)
    if JAVA_HOME:
        env["JAVA_HOME"] = JAVA_HOME
        env["PATH"] = os.path.join(JAVA_HOME, "bin") + os.pathsep + env.get("PATH", "")
    res = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                         errors="replace", env=env)
    if res.stdout.strip():
        print(res.stdout.strip())
    if res.returncode != 0:
        print("STDERR:", res.stderr.strip()[:4000])
        raise SystemExit(f"命令失败: {cmd[0]}")


def main() -> None:
    if not SDK:
        raise SystemExit("找不到 Android SDK，请设置环境变量 ANDROID_SDK")
    if not JAVA_HOME or _major_version(JAVA_HOME) < 17:
        raise SystemExit(f"需要 JDK 17+（当前: {JAVA_HOME or '未找到'}），请设置环境变量 JAVA_HOME")

    bt = os.path.join(SDK, "build-tools", BUILD_TOOLS_VERSION)
    android_jar = os.path.join(SDK, "platforms", PLATFORM, "android.jar")
    for path in (bt, android_jar):
        if not os.path.exists(path):
            raise SystemExit(f"缺少 {path}（需要 build-tools;{BUILD_TOOLS_VERSION} + platforms;{PLATFORM}）")

    shutil.rmtree(BUILD, ignore_errors=True)
    gen = os.path.join(BUILD, "gen")
    classes = os.path.join(BUILD, "classes")
    dex = os.path.join(BUILD, "dex")
    for d in (gen, classes, dex):
        os.makedirs(d, exist_ok=True)

    # 1) 资源
    res_zip = os.path.join(BUILD, "res.zip")
    run([os.path.join(bt, "aapt2.exe"), "compile", "--dir", os.path.join(PROJ, "res"),
         "-o", res_zip])

    # 2) 链接 + R.java
    unsigned = os.path.join(BUILD, "app-unsigned.apk")
    run([
        os.path.join(bt, "aapt2.exe"), "link",
        "-o", unsigned,
        "-I", android_jar,
        "--manifest", os.path.join(PROJ, "AndroidManifest.xml"),
        "-R", res_zip,
        "--java", gen,
        "--min-sdk-version", "21",
        "--target-sdk-version", "35",
        "--auto-add-overlay",
    ])

    # 3) javac
    sources = glob.glob(os.path.join(PROJ, "src", "**", "*.java"), recursive=True)
    sources += glob.glob(os.path.join(gen, "**", "R.java"), recursive=True)
    run([
        os.path.join(JAVA_HOME, "bin", "javac.exe"), "-encoding", "UTF-8",
        "-source", "8", "-target", "8",
        "-bootclasspath", android_jar, "-classpath", android_jar,
        "-d", classes, *sources,
    ])

    # 4) dex
    class_files = glob.glob(os.path.join(classes, "**", "*.class"), recursive=True)
    run([os.path.join(bt, "d8.bat"), "--lib", android_jar, "--min-api", "21",
         "--output", dex, *class_files])

    # 5) 打进 APK
    apk_with_dex = os.path.join(BUILD, "app-with-dex.apk")
    with zipfile.ZipFile(unsigned) as src, zipfile.ZipFile(apk_with_dex, "w", zipfile.ZIP_DEFLATED) as dst:
        for item in src.infolist():
            dst.writestr(item, src.read(item.filename))
        dst.write(os.path.join(dex, "classes.dex"), "classes.dex")

    # 6) 对齐 + 签名（debug key，首次自动生成）
    aligned = os.path.join(BUILD, "app-aligned.apk")
    run([os.path.join(bt, "zipalign.exe"), "-f", "4", apk_with_dex, aligned])

    if not os.path.exists(KEYSTORE):
        run([
            os.path.join(JAVA_HOME, "bin", "keytool.exe"), "-genkeypair", "-v",
            "-keystore", KEYSTORE, "-alias", "androiddebugkey",
            "-storepass", "android", "-keypass", "android",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
            "-dname", "CN=Android Debug,O=Android,C=US",
        ])
    run([
        os.path.join(bt, "apksigner.bat"), "sign",
        "--ks", KEYSTORE, "--ks-pass", "pass:android", "--key-pass", "pass:android",
        "--ks-key-alias", "androiddebugkey",
        "--out", OUT_APK, aligned,
    ])
    print(f"\nAPK: {OUT_APK}  ({os.path.getsize(OUT_APK) / 1024:.1f} KB)")


if __name__ == "__main__":
    main()
