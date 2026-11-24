"""Rebuild the unsigned APK from the extracted corresponding-source archive."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
from zipfile import ZipFile, ZIP_DEFLATED

parser = argparse.ArgumentParser()
parser.add_argument("--sdk", required=True, type=Path)
parser.add_argument("--jdk", required=True, type=Path)
parser.add_argument("--microg", type=Path, help="Optional original microG Services APK")
parser.add_argument("--privacy-deps", type=Path, help="Optional directory containing verified bytehook/shadowhook .so files")
parser.add_argument("--offline", action="store_true", help="Use cached Gradle dependencies")
args = parser.parse_args()
project = Path(__file__).resolve().parent
sdk, jdk = args.sdk.resolve(), args.jdk.resolve()
os.environ.update(JAVA_HOME=str(jdk), ANDROID_HOME=str(sdk), PATH=str(jdk / "bin") + os.pathsep + os.environ["PATH"])
os.environ.pop("ANDROID_SDK_ROOT", None)
work = project / "build/candidate-inputs"
work.mkdir(parents=True, exist_ok=True)
os.environ.setdefault("ANDROID_USER_HOME", str(work / "android-home"))
exe = ".exe" if os.name == "nt" else ""
bat = ".bat" if os.name == "nt" else ""

PRIVACY_LIBS = {
    "libbytehook.so": "52037a6086f5b6c4ab588393b917d92a9b49beedec9813fd08f92666d9bf0433",
    "libshadowhook.so": "fc84287eac46e3bade2f7e07e3c9efdca005822e21191d6db5b8cb222d53e8a0",
    "libshadowhook_nothing.so": "f2cc6a6f94d7361be915daafff1d25b7b3af34972e7308ac540982cfd608bd32",
}
PRIVACY_AARS = (
    ("bytehook", "com/bytedance", "1.1.2", "b3c85fec26e9f41fd3608851ac3fe412573ef4ce6ef83911b92d610d3bab7215",
     ("libbytehook.so",)),
    ("shadowhook", "com/bytedance/android", "2.0.1", "2cad01ff4d59542958775e19f281981a7e84b39670b5fae75905b8511e10c7cd",
     ("libshadowhook.so", "libshadowhook_nothing.so")),
)


def run(*command, env=None):
    subprocess.run([str(p) for p in command], cwd=project, check=True, env=env)


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def llvm_prebuilt(ndk: Path) -> Path:
    prebuilt = ndk / "toolchains/llvm/prebuilt"
    for candidate in ("windows-x86_64", "linux-x86_64", "darwin-x86_64", "darwin-arm64"):
        p = prebuilt / candidate
        if p.is_dir():
            return p
    raise FileNotFoundError("NDK LLVM prebuilt not found")


def clang_resource_dir(llvm: Path) -> Path:
    clang_dir = llvm / "lib/clang"
    versions = sorted(p for p in clang_dir.iterdir() if p.is_dir())
    return versions[-1]


def clang_builtins(llvm: Path) -> Path:
    res = clang_resource_dir(llvm)
    return res / "lib/linux/libclang_rt.builtins-aarch64-android.a"


ndk = sdk / "ndk/27.0.12077973"
llvm = llvm_prebuilt(ndk)
res_dir = clang_resource_dir(llvm)
builtins = clang_builtins(llvm)
tools = sdk / "build-tools/36.1.0"
android_jar = sdk / "platforms/android-36/android.jar"

# 1. Build Dobby static library
cmake = sdk / "cmake/3.31.6/bin"
run(cmake / f"cmake{exe}", "-S", project / "dobby", "-B", work / "native", "-G", "Ninja",
    "-DCMAKE_MAKE_PROGRAM=" + (cmake / f"ninja{exe}").as_posix(),
    "-DCMAKE_TOOLCHAIN_FILE=" + (ndk / "build/cmake/android.toolchain.cmake").as_posix(),
    "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-28", "-DCMAKE_BUILD_TYPE=Release",
    "-DDOBBY_BUILD_TEST=OFF", "-DDOBBY_BUILD_EXAMPLE=OFF")
run(cmake / f"cmake{exe}", "--build", work / "native", "--target", "dobby_static")
native = project / "Bcore/src/main/cpp/Dobby/arm64-v8a/libdobby.a"
native.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(work / "native/libdobby.a", native)

# 2. Build empty.jar
classes = work / "classes"
shutil.rmtree(classes, ignore_errors=True)
classes.mkdir(parents=True, exist_ok=True)
run(jdk / f"bin/javac{exe}", "--release", "8", "-d", classes, project / "Empty.java")
run(jdk / f"bin/jar{exe}", "cf", work / "empty-classes.jar", "-C", classes, ".")
run(tools / f"d8{bat}", "--min-api", "28", "--output", work, work / "empty-classes.jar")
assets = project / "Bcore/src/main/assets"
assets.mkdir(parents=True, exist_ok=True)
with ZipFile(assets / "empty.jar", "w", ZIP_DEFLATED) as z:
    z.write(work / "classes.dex", "classes.dex")

# 3. Build libcage_privacy.so + stage pinned bytehook/shadowhook arm64 libraries
priv_tmp = work / "privacy-tmp"
priv_tmp.mkdir(exist_ok=True)
cc_env = dict(os.environ, TEMP=str(priv_tmp), TMP=str(priv_tmp))
priv_src = project / "privacy-native-src"
priv_obj, priv_so = work / "privacy.o", work / "libcage_privacy.so"
run(llvm / f"bin/clang++{exe}", "-fintegrated-cc1", "--target=aarch64-linux-android28",
    "--sysroot=" + str(llvm / "sysroot"), "-resource-dir=" + str(res_dir),
    "-std=c++17", "-O2", "-fPIC", "-fno-exceptions", "-fno-rtti", "-fvisibility=hidden",
    "-mno-outline-atomics", "-Wall", "-Wextra", "-Werror", "-Wno-deprecated-declarations",
    "-I" + str(priv_src), "-c", priv_src / "privacy.cpp", "-o", priv_obj, env=cc_env)
run(llvm / f"bin/ld.lld{exe}", "-shared", "-m", "aarch64linux", "--hash-style=gnu", "--eh-frame-hdr",
    "--build-id=sha1", "-z", "defs", "-z", "now", "-z", "relro", "-z", "noexecstack",
    "-z", "max-page-size=16384", "-soname", priv_so.name,
    "-L" + str(llvm / "sysroot/usr/lib/aarch64-linux-android/28"),
    "-L" + str(llvm / "sysroot/usr/lib/aarch64-linux-android"), priv_obj,
    builtins, "-lc", "-ldl", "-llog", "-o", priv_so, env=cc_env)
jni_dir = project / "Bcore/src/main/jniLibs/arm64-v8a"
jni_dir.mkdir(parents=True, exist_ok=True)
shutil.copy2(priv_so, jni_dir / "libcage_privacy.so")

deps_ready = True
if args.privacy_deps and args.privacy_deps.is_dir():
    for lib_name, expected_sha in PRIVACY_LIBS.items():
        candidate_lib = args.privacy_deps / lib_name
        if candidate_lib.is_file():
            if sha256(candidate_lib) != expected_sha:
                raise ValueError("Privacy library checksum mismatch: " + lib_name)
            raw = candidate_lib.read_bytes()
            if raw[:6] != b"\x7fELF\x02\x01" or int.from_bytes(raw[18:20], "little") != 183:
                raise ValueError("Dependency architecture mismatch: " + lib_name)
            shutil.copy2(candidate_lib, jni_dir / lib_name)
        else:
            deps_ready = False
else:
    deps_ready = False

if not deps_ready:
    for name, group, version, digest, libs in PRIVACY_AARS:
        aar = work / f"{name}-{version}.aar"
        if not aar.exists():
            url = f"https://repo.maven.apache.org/maven2/{group}/{name}/{version}/{aar.name}"
            urllib.request.urlretrieve(url, aar)
        if sha256(aar) != digest:
            raise ValueError("Privacy dependency checksum mismatch: " + name)
        with ZipFile(aar) as archive:
            for lib_name in libs:
                raw = archive.read("jni/arm64-v8a/" + lib_name)
                if raw[:6] != b"\x7fELF\x02\x01" or int.from_bytes(raw[18:20], "little") != 183:
                    raise ValueError("Dependency architecture mismatch: " + lib_name)
                (jni_dir / lib_name).write_bytes(raw)

notices_dst = project / "app/src/main/assets/cage-privacy/THIRD-PARTY-NOTICES.txt"
notices_dst.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(priv_src / "third_party/NOTICE.txt", notices_dst)

# 4. Build libcage_child_properties.so (arm64-v8a LD_PRELOAD child library)
child_src = project / "privacy-child-src"
child_obj, child_so = work / "property_preload.o", work / "libcage_child_properties.so"
run(llvm / f"bin/clang{exe}", "-fintegrated-cc1", "--target=aarch64-linux-android28",
    "--sysroot=" + str(llvm / "sysroot"), "-resource-dir=" + str(res_dir),
    "-O2", "-fPIC", "-fvisibility=hidden", "-Wall", "-Wextra", "-Werror",
    "-I" + str(child_src), "-c", child_src / "property_preload.c", "-o", child_obj, env=cc_env)
run(llvm / f"bin/ld.lld{exe}", "-shared", "-m", "aarch64linux", "--hash-style=gnu", "--eh-frame-hdr",
    "-z", "defs", "-z", "now", "-z", "relro", "-z", "noexecstack",
    "-z", "max-page-size=16384", "-soname", child_so.name,
    "-L" + str(llvm / "sysroot/usr/lib/aarch64-linux-android/28"),
    "-L" + str(llvm / "sysroot/usr/lib/aarch64-linux-android"), child_obj,
    builtins, "-lc", "-ldl", "-o", child_so, env=cc_env)
child_dst = project / "app/src/main/assets/cage-child/arm64-v8a/libcage_child_properties.so"
child_dst.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(child_so, child_dst)

# 5. Build preflight.apk (in-space probe with libcage_privacy_probe.so + libcage_socket_mark.so)
probe_work = work / "probe"
for sub in ("classes", "dex"):
    sub_dir = probe_work / sub
    shutil.rmtree(sub_dir, ignore_errors=True)
    sub_dir.mkdir(parents=True, exist_ok=True)
probe_src = project / "probe-src"
probe_native_obj, probe_native_so = probe_work / "native_probe.o", probe_work / "libcage_privacy_probe.so"
run(llvm / f"bin/clang++{exe}", "-fintegrated-cc1", "--target=aarch64-linux-android28",
    "--sysroot=" + str(llvm / "sysroot"), "-resource-dir=" + str(res_dir),
    "-std=c++17", "-O2", "-fPIC", "-fno-exceptions", "-fno-rtti", "-fvisibility=hidden",
    "-mno-outline-atomics", "-Wall", "-Wextra", "-Werror",
    "-c", probe_src / "native_probe.cpp", "-o", probe_native_obj, env=cc_env)
run(llvm / f"bin/ld.lld{exe}", "-shared", "-m", "aarch64linux", "--hash-style=gnu", "--eh-frame-hdr",
    "-z", "defs", "-z", "now", "-z", "relro", "-z", "noexecstack",
    "-z", "max-page-size=16384", "-soname", probe_native_so.name,
    "-L" + str(llvm / "sysroot/usr/lib/aarch64-linux-android/28"),
    "-L" + str(llvm / "sysroot/usr/lib/aarch64-linux-android"), probe_native_obj,
    builtins, "-lc", "-o", probe_native_so, env=cc_env)

mark_obj, mark_so = probe_work / "socket_mark.o", probe_work / "libcage_socket_mark.so"
run(llvm / f"bin/clang{exe}", "-fintegrated-cc1", "--target=aarch64-linux-android28",
    "--sysroot=" + str(llvm / "sysroot"), "-resource-dir=" + str(res_dir),
    "-fPIC", "-O2", "-c", probe_src / "socket_mark.c", "-o", mark_obj, env=cc_env)
run(llvm / f"bin/ld.lld{exe}", "-shared", "-m", "aarch64linux", "-z", "max-page-size=16384",
    "-L" + str(llvm / "sysroot/usr/lib/aarch64-linux-android/28"), mark_obj,
    "-lc", "-o", mark_so, env=cc_env)

preflight_java = sorted((project / "app/src/main/java/io/github/flysky701/opencage/preflight").glob("*.java"))
run(jdk / f"bin/javac{exe}", "--release", "8", "-encoding", "UTF-8", "-cp", android_jar,
    "-d", probe_work / "classes", probe_src / "ProbeActivity.java", *preflight_java)
probe_jar = probe_work / "classes.jar"
with ZipFile(probe_jar, "w", ZIP_DEFLATED) as z:
    for item in sorted((probe_work / "classes").rglob("*.class")):
        z.write(item, item.relative_to(probe_work / "classes").as_posix())
run(tools / f"d8{bat}", "--min-api", "26", "--lib", android_jar, "--output", probe_work / "dex", probe_jar)

probe_base = probe_work / "base.apk"
run(tools / f"aapt2{exe}", "link", "-I", android_jar, "--manifest", probe_src / "AndroidManifest.xml", "-o", probe_base)
with ZipFile(probe_base, "a") as z:
    z.write(probe_work / "dex/classes.dex", "classes.dex", compress_type=ZIP_DEFLATED)
    z.write(probe_native_so, "lib/arm64-v8a/" + probe_native_so.name, compress_type=ZIP_DEFLATED)
    z.write(mark_so, "lib/arm64-v8a/" + mark_so.name, compress_type=ZIP_DEFLATED)
probe_aligned = probe_work / "aligned.apk"
run(tools / f"zipalign{exe}", "-p", "-f", "4", probe_base, probe_aligned)
probe_ks = probe_work / "probe.p12"
if not probe_ks.exists():
    run(jdk / f"bin/keytool{exe}", "-genkeypair", "-keystore", probe_ks, "-storetype", "PKCS12",
        "-storepass", "cageprobe", "-alias", "probe", "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "3650", "-dname", "CN=CagePreflight")
probe_signed = probe_work / "preflight.apk"
run(tools / f"apksigner{bat}", "sign", "--ks", probe_ks, "--ks-pass", "pass:cageprobe",
    "--v4-signing-enabled", "false", "--out", probe_signed, probe_aligned)
probe_dst = project / "app/src/main/assets/cage-android/preflight.apk"
probe_dst.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(probe_signed, probe_dst)
probe_sha = sha256(probe_dst)
(project / "app/src/main/java/io/github/flysky701/opencage/BuildInfo.java").write_text(
    f'package io.github.flysky701.opencage;\nfinal class BuildInfo {{\n    static final String PROBE_SHA = "{probe_sha}";\n}}\n',
    encoding="utf-8", newline="\n",
)

# 6. Copy pinned microG Services APK
spec = next(item for item in json.loads((project / "bundle.lock.json").read_text())["apps"]
            if item["package"] == "com.google.android.gms")
original = args.microg or work / spec["name"]
if not original.exists():
    if args.microg:
        raise FileNotFoundError(original)
    urllib.request.urlretrieve(spec["url"], original)
if sha256(original) != spec["sha256"]:
    raise ValueError("microG Services checksum mismatch")
bundle_dir = project / "app/src/main/assets/bundle"
bundle_dir.mkdir(parents=True, exist_ok=True)
for stale_apk in bundle_dir.glob("*.apk"):
    stale_apk.unlink()
shutil.copy2(original, bundle_dir / spec["name"])

# 7. Assemble Release APK
wrapper = [project / "gradlew.bat"] if os.name == "nt" else ["bash", project / "gradlew"]
gradle_options = ["--offline"] if args.offline else []
if os.name == "nt":
    gradle_options.append(f"-Pandroid.aapt2FromMavenOverride={tools / 'aapt2.exe'}")
run(*wrapper, "--max-workers=2", *gradle_options, ":app:assembleRelease")
