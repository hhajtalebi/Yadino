#!/usr/bin/env python3
"""
Yadino hermetic build (no Gradle, no Maven access required).

It mirrors what the Gradle build does, using the toolchain prepared by
fetch-toolchain.sh and build-deps.sh:

  jvm    compile :core:calendar and :core:domain
  test   compile + run the JVM unit tests (JUnit 4)
  apk    build the Android app: aapt2 -> kapt (Room) -> kotlinc (Compose) -> javac
         -> R8/D8 -> zip -> zipalign -> apksigner
  all    jvm + test + apk (debug and release)

The official build is Gradle (see docs/BUILD.md); this script exists so the project can
be compiled and verified in restricted environments.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
TC = Path(os.environ.get("YADINO_TC", str(Path.home() / ".cache" / "yadino-toolchain")))
OUT = ROOT / "build" / "offline"
JAVA_HOME = TC / "jdk17" / "linux-x86"
KOTLIN_HOME = TC / "kotlinc"
ANDROID_JAR = TC / "sdk" / "35" / "public" / "android.jar"
AAPT2 = TC / "sdk" / "tools" / "linux" / "bin" / "aapt2"
ZIPALIGN = TC / "sdk" / "tools" / "linux" / "bin" / "zipalign"
APKSIGNER = TC / "sdk" / "tools" / "linux" / "lib" / "apksigner.jar"
R8 = TC / "r8" / "r8.jar"
M2_ANDROIDX = TC / "sdk" / "current" / "androidx" / "m2repository"
M2_TOOLS = TC / "m2tools" / "common" / "m2" / "repository"
DEPS = TC / "out" / "deps"
STDLIB = KOTLIN_HOME / "lib" / "kotlin-stdlib.jar"

APP_ID = "ir.yadino.app"
MIN_SDK = 26
TARGET_SDK = 35
VERSION_CODE = 6
VERSION_NAME = "1.4.0"

ENV = dict(os.environ)
ENV["JAVA_HOME"] = str(JAVA_HOME)
ENV["PATH"] = f"{JAVA_HOME}/bin:{KOTLIN_HOME}/bin:" + ENV.get("PATH", "")
ENV["LD_LIBRARY_PATH"] = str(TC / "sdk" / "tools" / "linux" / "lib64")
ENV.setdefault("JAVA_OPTS", "-Xmx2560m -XX:+UseParallelGC -Xss4m")

# AndroidX / Kotlin dependencies of the app (top level; transitive deps are resolved).
APP_DEPENDENCIES = [
    "androidx.core:core-ktx",
    "androidx.activity:activity-compose",
    "androidx.lifecycle:lifecycle-runtime-compose",
    "androidx.lifecycle:lifecycle-viewmodel-compose",
    "androidx.compose.ui:ui",
    "androidx.compose.foundation:foundation",
    "androidx.compose.material3:material3",
    "androidx.compose.material:material-icons-extended",
    "androidx.room:room-runtime",
    "androidx.room:room-ktx",
    "androidx.work:work-runtime-ktx",
    "androidx.datastore:datastore-preferences",
    "androidx.browser:browser",
]

# Maven coordinates that are provided by jars built from source / other prebuilts.
EXTERNAL = {
    "org.jetbrains.kotlin:kotlin-stdlib": [STDLIB],
    "org.jetbrains.kotlin:kotlin-stdlib-common": [],
    "org.jetbrains.kotlin:kotlin-stdlib-jdk7": [],
    "org.jetbrains.kotlin:kotlin-stdlib-jdk8": [],
    "org.jetbrains.kotlinx:kotlinx-coroutines-core": [DEPS / "kotlinx-coroutines-core.jar"],
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm": [DEPS / "kotlinx-coroutines-core.jar"],
    "org.jetbrains.kotlinx:kotlinx-coroutines-android": [DEPS / "kotlinx-coroutines-android.jar"],
    "com.squareup.okio:okio-jvm": [DEPS / "okio.jar"],
    "com.squareup.okio:okio": [DEPS / "okio.jar"],
    "com.google.guava:listenablefuture": [M2_TOOLS / "com/google/guava/guava/listenablefuture/1.0/listenablefuture-1.0.jar"],
    # compile-time only annotations
    "org.jspecify:jspecify": [],
    # only needed by serialization based APIs that Yadino does not use
    "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm": [],
    "org.jetbrains.kotlinx:kotlinx-serialization-core": [],
}
COMPILE_ONLY = [DEPS / "jspecify.jar"]

ROOM_PROCESSOR_CP = [
    "androidx/room/room-compiler/2.7.0-beta01/room-compiler-2.7.0-beta01.jar",
    "androidx/room/room-compiler-processing/2.7.0-beta01/room-compiler-processing-2.7.0-beta01.jar",
    "androidx/room/room-common-jvm/2.7.0-beta01/room-common-jvm-2.7.0-beta01.jar",
    "androidx/room/room-migration-jvm/2.7.0-beta01/room-migration-jvm-2.7.0-beta01.jar",
    "androidx/room/room-external-antlr/2.7.0-beta01/room-external-antlr-2.7.0-beta01.jar",
    "androidx/annotation/annotation-jvm/1.9.0-rc01/annotation-jvm-1.9.0-rc01.jar",
    "androidx/collection/collection-jvm/1.5.0-beta03/collection-jvm-1.5.0-beta03.jar",
]


# ----------------------------------------------------------------------------- utils
def log(msg):
    print(f"\033[1;36m▶ {msg}\033[0m", flush=True)


def run(cmd, cwd=None, quiet=False, check=True, env=None):
    cmd = [str(c) for c in cmd]
    if not quiet:
        shown = " ".join(cmd)
        print("  $ " + (shown if len(shown) < 400 else shown[:400] + " …"), flush=True)
    res = subprocess.run(cmd, cwd=cwd, env=env or ENV, text=True, capture_output=True, stdin=subprocess.DEVNULL)
    out = (res.stdout or "") + (res.stderr or "")
    if res.returncode != 0 and check:
        print(out)
        raise SystemExit(f"command failed ({res.returncode}): {cmd[0]}")
    return out


def sources(*dirs, ext=(".kt", ".java")):
    files = []
    for d in dirs:
        d = Path(d)
        if d.exists():
            files += sorted(p for p in d.rglob("*") if p.suffix in ext and p.is_file())
    return files


def digest(paths, extra=""):
    h = hashlib.sha256(extra.encode())
    for p in sorted(set(map(str, paths))):
        pp = Path(p)
        h.update(p.encode())
        if pp.is_file():
            h.update(pp.read_bytes())
    return h.hexdigest()


def cached(name, inputs, extra, fn):
    """Runs fn() only when the digest of inputs changed."""
    stamp = OUT / "stamps" / f"{name}.sha"
    d = digest(inputs, extra)
    if stamp.exists() and stamp.read_text() == d:
        print(f"  ✓ {name} (up to date)")
        return False
    fn()
    stamp.parent.mkdir(parents=True, exist_ok=True)
    stamp.write_text(d)
    return True


EMBEDDABLE_CP = [
    M2_TOOLS / "org/jetbrains/kotlin/kotlin-compiler-embeddable/2.0.21/kotlin-compiler-embeddable-2.0.21.jar",
    M2_TOOLS / "org/jetbrains/kotlin/kotlin-daemon-embeddable/2.0.21/kotlin-daemon-embeddable-2.0.21.jar",
    KOTLIN_HOME / "lib" / "kotlin-stdlib.jar", KOTLIN_HOME / "lib" / "kotlin-reflect.jar",
    KOTLIN_HOME / "lib" / "kotlin-script-runtime.jar", KOTLIN_HOME / "lib" / "trove4j.jar",
    KOTLIN_HOME / "lib" / "kotlinx-coroutines-core-jvm.jar", KOTLIN_HOME / "lib" / "annotations-13.0.jar",
]


def kotlinc(module, srcs, classpath, out_dir, extra=(), embeddable=False):
    out_dir = Path(out_dir)
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)
    if not srcs:
        # kotlinc without sources would start the interactive REPL
        print(f"  kotlinc {module}: no sources")
        return
    argfile = OUT / "args" / f"{module}.kotlinc.args"
    argfile.parent.mkdir(parents=True, exist_ok=True)
    args = ["-jvm-target", "17", "-no-stdlib", "-no-reflect", "-module-name", module,
            "-Xjvm-default=all",
            "-cp", os.pathsep.join(map(str, [STDLIB] + list(classpath))), "-d", str(out_dir)]
    args += list(extra)
    args += [str(s) for s in srcs]
    argfile.write_text("\n".join(f'"{a}"' if " " in a else a for a in args))
    t = time.time()
    if embeddable:
        # The Compose compiler plugin shipped with AOSP's kotlinc targets the
        # *embeddable* compiler (relocated IntelliJ packages).
        cmd = [JAVA_HOME / "bin" / "java", "-Xmx2800m", "-Xss8m", "-XX:+UseParallelGC",
               "-cp", os.pathsep.join(map(str, EMBEDDABLE_CP)),
               "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", f"@{argfile}"]
    else:
        cmd = [KOTLIN_HOME / "bin" / "kotlinc", f"@{argfile}"]
    out = run(cmd, quiet=True, check=False)
    warnings = [l for l in out.splitlines() if "warning:" in l]
    errors = [l for l in out.splitlines() if "error:" in l or "exception:" in l.lower()]
    (OUT / "logs").mkdir(parents=True, exist_ok=True)
    (OUT / "logs" / f"{module}.kotlinc.log").write_text(out)
    if errors or not any(out_dir.rglob("*.class")):
        print(out[-20000:])
        raise SystemExit(f"kotlinc failed for {module}")
    print(f"  kotlinc {module}: {len(srcs)} files, {len(warnings)} warnings, {time.time() - t:.0f}s")
    (OUT / "logs").mkdir(parents=True, exist_ok=True)
    (OUT / "logs" / f"{module}.kotlinc.log").write_text(out)


def javac(module, srcs, classpath, out_dir, extra=()):
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    if not srcs:
        return
    argfile = OUT / "args" / f"{module}.javac.args"
    argfile.parent.mkdir(parents=True, exist_ok=True)
    args = ["-encoding", "UTF-8", "--release", "17", "-nowarn", "-Xlint:none", "-XDsuppressNotes",
            "-cp", os.pathsep.join(map(str, classpath)), "-d", str(out_dir)] + list(extra) + [str(s) for s in srcs]
    argfile.write_text("\n".join(args))
    run([JAVA_HOME / "bin" / "javac", f"@{argfile}"], quiet=True)


def jar(out_jar, classes_dir):
    out_jar = Path(out_jar)
    out_jar.parent.mkdir(parents=True, exist_ok=True)
    if out_jar.exists():
        out_jar.unlink()
    with zipfile.ZipFile(out_jar, "w", zipfile.ZIP_DEFLATED) as z:
        for p in sorted(Path(classes_dir).rglob("*")):
            if p.is_file():
                z.write(p, p.relative_to(classes_dir).as_posix())


# ----------------------------------------------------------------------------- JVM modules
CAL_MAIN = ROOT / "core/calendar/src/main/kotlin"
DOM_MAIN = ROOT / "core/domain/src/main/kotlin"
CAL_JAR = OUT / "jars" / "calendar.jar"
DOM_JAR = OUT / "jars" / "domain.jar"


def build_jvm():
    log("JVM modules")
    cal_src = sources(CAL_MAIN)
    cached("calendar", cal_src, "v1", lambda: (
        kotlinc("calendar", cal_src, [], OUT / "classes/calendar"),
        jar(CAL_JAR, OUT / "classes/calendar")))
    dom_src = sources(DOM_MAIN)
    cached("domain", dom_src + [CAL_JAR], "v1", lambda: (
        kotlinc("domain", dom_src, [CAL_JAR], OUT / "classes/domain"),
        jar(DOM_JAR, OUT / "classes/domain")))


def build_tests():
    log("JVM unit tests")
    junit = [DEPS / "junit.jar", DEPS / "hamcrest-core.jar"]
    results = {}
    sqlite_jdbc = M2_TOOLS / "org/xerial/sqlite-jdbc/3.28.0/sqlite-jdbc-3.28.0.jar"
    modules = [
        ("calendar", ROOT / "core/calendar/src/test", [CAL_JAR], ROOT),
        ("domain", ROOT / "core/domain/src/test", [CAL_JAR, DOM_JAR, DEPS / "kotlinx-coroutines-core.jar"], ROOT),
        # JVM tests of the app module (no Android framework needed); run from app/ like Gradle does
        ("app", APP / "src/test", [CAL_JAR, DOM_JAR, sqlite_jdbc, DEPS / "kotlinx-coroutines-core.jar",
                                    OUT / "app" / "debug" / "app-classes.jar", OUT / "app" / "debug" / "r.jar", ANDROID_JAR], APP),
    ]
    for name, test_root, cp, workdir in modules:
        srcs = sources(test_root / "kotlin")
        if not srcs:
            continue
        classes = OUT / "test-classes" / name
        friends = ",".join(str(p) for p in cp if p.suffix == ".jar" and "out/deps" not in str(p) and p != ANDROID_JAR)
        cached(f"{name}-tests", srcs + cp, "v2", lambda: kotlinc(f"{name}-test", srcs, cp + junit, classes, [f"-Xfriend-paths={friends}"]))
        res_dir = test_root / "resources"
        test_classes = sorted(
            p.relative_to(classes).with_suffix("").as_posix().replace("/", ".")
            for p in classes.rglob("*Test.class") if "$" not in p.name)
        cp_all = [classes] + ([res_dir] if res_dir.exists() else []) + cp + junit + [STDLIB]
        t = time.time()
        out = run([JAVA_HOME / "bin" / "java", "-cp", os.pathsep.join(map(str, cp_all)),
                   "org.junit.runner.JUnitCore"] + test_classes, quiet=True, check=False, cwd=workdir)
        ok = re.search(r"OK \((\d+) tests?\)", out)
        fail = re.search(r"Tests run: (\d+),\s+Failures: (\d+)", out)
        (OUT / "logs").mkdir(parents=True, exist_ok=True)
        (OUT / "logs" / f"{name}-tests.log").write_text(out)
        if ok:
            results[name] = {"tests": int(ok.group(1)), "failures": 0, "classes": test_classes,
                             "seconds": round(time.time() - t, 1)}
            print(f"  ✅ {name}: {ok.group(1)} tests passed ({len(test_classes)} classes)")
        else:
            print(out[-15000:])
            results[name] = {"tests": int(fail.group(1)) if fail else -1,
                             "failures": int(fail.group(2)) if fail else -1, "classes": test_classes}
            print(f"  ❌ {name}: failures")
    (OUT / "test-results.json").write_text(json.dumps(results, indent=1, ensure_ascii=False))
    if any(r["failures"] != 0 for r in results.values()):
        raise SystemExit("unit tests failed")
    return results


# ----------------------------------------------------------------------------- dependency resolution
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def _find_artifact(group, artifact):
    for repo in (M2_ANDROIDX, M2_TOOLS):
        for cand in (artifact, artifact + "-android", artifact + "-jvm"):
            d = repo.joinpath(*group.split("."), cand)
            if d.is_dir():
                versions = sorted(v.name for v in d.iterdir() if v.is_dir())
                if versions:
                    return cand, versions[-1], d / versions[-1]
    return None, None, None


def resolve_dependencies(roots, allow_missing=False):
    seen, order, missing = {}, [], set()
    queue = list(roots)
    while queue:
        coord = queue.pop(0)
        if coord in seen or coord in missing:
            continue
        if coord in EXTERNAL:
            seen[coord] = ("external", EXTERNAL[coord])
            order.append(coord)
            continue
        g, a = coord.split(":")[:2]
        real, ver, base = _find_artifact(g, a)
        if not real:
            missing.add(coord)
            continue
        pom = base / f"{real}-{ver}.pom"
        deps = []
        if pom.exists():
            tree = ET.parse(pom).getroot()
            for dep in tree.findall("m:dependencies/m:dependency", NS):
                scope = dep.findtext("m:scope", namespaces=NS) or "compile"
                if scope in ("test", "provided", "system", "import"):
                    continue
                if dep.findtext("m:optional", namespaces=NS) == "true":
                    continue
                deps.append(dep.findtext("m:groupId", namespaces=NS) + ":" + dep.findtext("m:artifactId", namespaces=NS))
        art = next((base / f"{real}-{ver}.{e}" for e in ("aar", "jar") if (base / f"{real}-{ver}.{e}").exists()), None)
        seen[coord] = ("maven", art)
        order.append(coord)
        queue += deps
    if missing and not allow_missing:
        raise SystemExit("unresolved dependencies: " + ", ".join(sorted(missing)))
    return order, seen


class Library:
    def __init__(self, coord, path):
        self.coord = coord
        self.path = Path(path)
        self.is_aar = self.path.suffix == ".aar"
        self.dir = OUT / "aars" / self.path.stem
        self.jars = []
        self.res = None
        self.manifest = None
        self.package = None
        self.proguard = None
        self.assets = None

    def extract(self):
        if not self.is_aar:
            self.jars = [self.path]
            return
        if not (self.dir / ".done").exists():
            if self.dir.exists():
                shutil.rmtree(self.dir)
            self.dir.mkdir(parents=True)
            with zipfile.ZipFile(self.path) as z:
                z.extractall(self.dir)
            (self.dir / ".done").write_text("ok")
        if (self.dir / "classes.jar").exists():
            self.jars.append(self.dir / "classes.jar")
        self.jars += sorted((self.dir / "libs").glob("*.jar")) if (self.dir / "libs").exists() else []
        res = self.dir / "res"
        if res.exists() and any(res.rglob("*.*")):
            self.res = res
        self.manifest = self.dir / "AndroidManifest.xml"
        if self.manifest.exists():
            self.package = ET.parse(self.manifest).getroot().get("package")
        if (self.dir / "proguard.txt").exists():
            self.proguard = self.dir / "proguard.txt"
        if (self.dir / "assets").exists():
            self.assets = self.dir / "assets"


def libraries():
    order, seen = resolve_dependencies(APP_DEPENDENCIES)
    libs = []
    paths = set()
    for coord in order:
        kind, val = seen[coord]
        items = val if kind == "external" else [val]
        for p in items:
            if p is None or str(p) in paths:
                continue
            paths.add(str(p))
            lib = Library(coord, p)
            lib.extract()
            libs.append(lib)
    return libs


# ----------------------------------------------------------------------------- manifest merging
ANDROID = "http://schemas.android.com/apk/res/android"
TOOLS = "http://schemas.android.com/tools"
ET.register_namespace("android", ANDROID)
ET.register_namespace("tools", TOOLS)
A_NAME = f"{{{ANDROID}}}name"
T_NODE = f"{{{TOOLS}}}node"


def _qualify(name, package):
    if name and name.startswith("."):
        return package + name
    if name and "." not in name and package:
        return package + "." + name
    return name


def merge_manifests(app_manifest, libs, placeholders, out_path):
    """A small manifest merger covering what AndroidX libraries need:
    permissions, features, queries and application components (with merging of
    duplicate components such as the androidx.startup provider), honouring
    tools:node="remove" declared in the app manifest."""
    text = Path(app_manifest).read_text()
    for k, v in placeholders.items():
        text = text.replace("${" + k + "}", v)
    root = ET.fromstring(text)
    root.set("package", APP_ID)
    app = root.find("application")
    removed = set()
    # collect removals declared by the app (any depth)
    for el in root.iter():
        if el.get(T_NODE) == "remove" and el.get(A_NAME):
            removed.add(el.get(A_NAME))

    def key(el):
        return (el.tag, el.get(A_NAME))

    def existing_children(parent):
        return {key(c): c for c in parent if c.get(A_NAME)}

    for lib in libs:
        if not lib.manifest or not lib.manifest.exists():
            continue
        ltext = lib.manifest.read_text()
        ltext = ltext.replace("${applicationId}", APP_ID)
        lroot = ET.fromstring(ltext)
        lpkg = lroot.get("package")
        top = existing_children(root)
        for child in lroot:
            if child.tag in ("uses-permission", "uses-permission-sdk-23", "uses-feature", "permission"):
                name = child.get(A_NAME)
                if name in removed or (child.tag, name) in top:
                    continue
                root.insert(list(root).index(app), child)
                top[(child.tag, name)] = child
            elif child.tag == "queries":
                q = root.find("queries")
                if q is None:
                    root.insert(list(root).index(app), child)
                else:
                    for c in child:
                        q.append(c)
            elif child.tag == "application":
                comps = existing_children(app)
                for comp in child:
                    name = _qualify(comp.get(A_NAME), lpkg)
                    if name:
                        comp.set(A_NAME, name)
                    if name in removed:
                        continue
                    k = (comp.tag, name)
                    if k in comps:
                        target = comps[k]
                        if target.get(T_NODE) == "remove":
                            continue
                        sub = existing_children(target)
                        for sc in comp:
                            sname = sc.get(A_NAME)
                            if sname in removed:
                                continue
                            if sname and (sc.tag, sname) in sub:
                                continue
                            target.append(sc)
                        for attr, val in comp.attrib.items():
                            if attr not in target.attrib:
                                target.set(attr, val)
                    else:
                        # drop children explicitly removed by the app
                        for sc in list(comp):
                            if sc.get(A_NAME) in removed:
                                comp.remove(sc)
                        app.append(comp)
                        comps[k] = comp
    # strip tools:* attributes and removed nodes
    def strip(parent):
        for c in list(parent):
            if c.get(T_NODE) == "remove":
                parent.remove(c)
                continue
            for attr in list(c.attrib):
                if attr.startswith(f"{{{TOOLS}}}"):
                    del c.attrib[attr]
            strip(c)
    strip(root)
    for attr in list(root.attrib):
        if attr.startswith(f"{{{TOOLS}}}"):
            del root.attrib[attr]
    Path(out_path).parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(root).write(out_path, encoding="utf-8", xml_declaration=True)


# ----------------------------------------------------------------------------- Android app
APP = ROOT / "app"
APP_MAIN = APP / "src" / "main"


def read_local_property(key, default=""):
    lp = ROOT / "local.properties"
    if lp.exists():
        for line in lp.read_text().splitlines():
            if line.strip().startswith(key + "="):
                return line.split("=", 1)[1].strip()
    return os.environ.get(key.upper().replace(".", "_"), default)


def drive_config():
    client_id = read_local_property("yadino.drive.clientId", "")
    if client_id.endswith(".apps.googleusercontent.com"):
        scheme = "com.googleusercontent.apps." + client_id[: -len(".apps.googleusercontent.com")]
    else:
        scheme = APP_ID + ".oauth"
    return client_id, scheme


def write_build_config(gen_dir, debug):
    client_id, scheme = drive_config()
    pkg_dir = Path(gen_dir) / APP_ID.replace(".", "/")
    pkg_dir.mkdir(parents=True, exist_ok=True)
    (pkg_dir / "BuildConfig.java").write_text(f"""/** Automatically generated file. DO NOT MODIFY */
package {APP_ID};

public final class BuildConfig {{
  public static final boolean DEBUG = {str(debug).lower()};
  public static final String APPLICATION_ID = "{APP_ID}";
  public static final String BUILD_TYPE = "{'debug' if debug else 'release'}";
  public static final int VERSION_CODE = {VERSION_CODE};
  public static final String VERSION_NAME = "{VERSION_NAME}";
  public static final String DRIVE_CLIENT_ID = "{client_id}";
  public static final String DRIVE_REDIRECT_SCHEME = "{scheme}";
  public static final String BUILD_TOOL = "offline-build";
}}
""")


def aapt2_compile_dir(res_dir, out_zip):
    out_zip = Path(out_zip)
    out_zip.parent.mkdir(parents=True, exist_ok=True)
    run([AAPT2, "compile", "--dir", res_dir, "-o", out_zip], quiet=True)


def build_apk(variant="debug"):
    debug = variant == "debug"
    log(f"Android app ({variant})")
    for p in (CAL_JAR, DOM_JAR):
        if not p.exists():
            build_jvm()
    w = OUT / "app" / variant
    w.mkdir(parents=True, exist_ok=True)
    libs = libraries()
    aars = [l for l in libs if l.is_aar]
    lib_jars = [j for l in libs for j in l.jars]
    print(f"  {len(libs)} libraries ({len(aars)} AARs)")

    # 1. resources --------------------------------------------------------------
    flats = []
    for l in aars:
        if l.res:
            z = OUT / "res-flat" / f"{l.path.stem}.zip"
            if not z.exists():
                aapt2_compile_dir(l.res, z)
            flats.append(z)
    app_res = APP_MAIN / "res"
    app_flat = w / "app-res.zip"
    cached(f"app-res-{variant}", sources(app_res, ext=(".xml", ".png", ".ttf", ".webp", ".otf", ".json")),
           "v1", lambda: aapt2_compile_dir(app_res, app_flat))

    client_id, scheme = drive_config()
    merged_manifest = w / "AndroidManifest.xml"
    merge_manifests(APP_MAIN / "AndroidManifest.xml", aars,
                    {"applicationId": APP_ID, "driveRedirectScheme": scheme}, merged_manifest)

    gen = w / "gen"
    if gen.exists():
        shutil.rmtree(gen)
    gen.mkdir(parents=True)
    res_apk = w / "resources.ap_"
    packages = sorted({l.package for l in aars if l.package and l.res})
    link = [AAPT2, "link", "-o", res_apk, "-I", ANDROID_JAR, "--manifest", merged_manifest,
            "--java", gen, "--proguard", w / "aapt-rules.pro",
            "--min-sdk-version", MIN_SDK, "--target-sdk-version", TARGET_SDK,
            "--version-code", VERSION_CODE, "--version-name", VERSION_NAME,
            "--auto-add-overlay", "--no-version-vectors",
            "--extra-packages", ":".join(packages)]
    if (APP_MAIN / "assets").exists():
        link += ["-A", APP_MAIN / "assets"]
    for f in flats:
        link += ["-R", f]
    link += ["-R", app_flat]
    run(link, quiet=True)
    write_build_config(gen, debug)
    print(f"  resources linked ({len(flats)} library resource sets, {len(packages)} R packages)")

    # 2. compile R + BuildConfig (javac) ------------------------------------------
    r_classes = w / "classes-r"
    gen_java = sources(gen, ext=(".java",))
    cached(f"r-{variant}", gen_java, "v1", lambda: (
        shutil.rmtree(r_classes, ignore_errors=True), javac("r", gen_java, [ANDROID_JAR], r_classes)))
    r_jar = w / "r.jar"
    jar(r_jar, r_classes)

    compile_cp = [ANDROID_JAR, r_jar, CAL_JAR, DOM_JAR] + lib_jars + COMPILE_ONLY
    kt_src = sources(APP_MAIN / "java", APP_MAIN / "kotlin", ext=(".kt",))
    java_src = sources(APP_MAIN / "java", ext=(".java",))

    # 3. kapt (Room) ----------------------------------------------------------------
    kapt_dir = OUT / "app" / "kapt"
    kapt_gen = kapt_dir / "sources"
    room_cp = [M2_ANDROIDX / p for p in ROOM_PROCESSOR_CP] + [
        M2_TOOLS / "com/google/guava/guava/32.1.1-jre/guava-32.1.1-jre.jar",
        DEPS / "failureaccess.jar",
        M2_TOOLS / "commons-codec/commons-codec/1.10/commons-codec-1.10.jar",
        M2_TOOLS / "org/xerial/sqlite-jdbc/3.28.0/sqlite-jdbc-3.28.0.jar",
        DEPS / "javapoet.jar", DEPS / "kotlinpoet.jar", DEPS / "kotlinpoet-javapoet.jar",
        DEPS / "auto-common.jar", DEPS / "auto-value-annotations.jar",
        DEPS / "symbol-processing-api.jar", DEPS / "kotlin-metadata-jvm.jar", DEPS / "jspecify.jar",
        DEPS / "kotlinx-serialization-core.jar", DEPS / "kotlinx-serialization-json.jar",
        STDLIB, KOTLIN_HOME / "lib" / "kotlin-reflect.jar", KOTLIN_HOME / "lib" / "annotations-13.0.jar",
    ]
    room_inputs = [s for s in kt_src if "/data/local/" in s.as_posix()]
    schema_dir = APP / "schemas"

    def run_kapt():
        shutil.rmtree(kapt_dir, ignore_errors=True)
        for sub in ("sources", "classes", "stubs", "incremental"):
            (kapt_dir / sub).mkdir(parents=True)
        argfile = OUT / "args" / "kapt.args"
        argfile.parent.mkdir(parents=True, exist_ok=True)
        args = [
            "-Kapt-mode=stubsAndApt",
            f"-Kapt-sources={kapt_dir / 'sources'}",
            f"-Kapt-classes={kapt_dir / 'classes'}",
            f"-Kapt-stubs={kapt_dir / 'stubs'}",
        ] + [f"-Kapt-classpath={p}" for p in room_cp] + [
            "-Kapt-processors=androidx.room.RoomProcessor",
            # the kapt CLI accepts a single -Kapt-option only
            f"-Kapt-option:room.schemaLocation={schema_dir}",
            "-Kapt-correct-error-types=true",
            "-Kapt-map-diagnostic-locations=true",
            "-jvm-target", "17", "-no-stdlib", "-no-reflect", "-Xjvm-default=all",
            "-cp", os.pathsep.join(map(str, [STDLIB] + compile_cp)),
        ] + [str(s) for s in room_inputs]
        argfile.write_text("\n".join(args))
        t = time.time()
        out = run([KOTLIN_HOME / "bin" / "kapt", f"@{argfile}"], quiet=True, check=False)
        (OUT / "logs").mkdir(parents=True, exist_ok=True)
        (OUT / "logs" / "kapt.log").write_text(out)
        if "error:" in out or "Exception" in out or not any((kapt_dir / "sources").rglob("*.java")):
            print(out[-20000:])
            raise SystemExit("kapt failed")
        print(f"  kapt: {len(list((kapt_dir / 'sources').rglob('*.java')))} generated files, {time.time() - t:.0f}s")

    cached("kapt", room_inputs + [CAL_JAR, DOM_JAR], "v3", run_kapt)
    kapt_java = sources(kapt_gen, ext=(".java",))

    # 4. kotlinc (with Compose compiler) -----------------------------------------------
    kt_classes = OUT / "app" / "classes-kotlin"
    compose_plugin = KOTLIN_HOME / "lib" / "compose-compiler.jar"
    kt_inputs = kt_src + java_src + kapt_java + [CAL_JAR, DOM_JAR, r_jar]
    kt_extra = [f"-Xplugin={compose_plugin}",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-opt-in=kotlinx.coroutines.FlowPreview",
                "-Xjava-source-roots=" + ",".join(map(str, [p for p in (APP_MAIN / "java", kapt_gen) if p.exists()]))]
    cached("app-kotlin", kt_inputs, "v4" + " ".join(kt_extra), lambda: kotlinc(
        "app", kt_src + java_src + kapt_java, compile_cp, kt_classes, kt_extra, embeddable=True))

    # 5. javac (Room generated code, hand written Java) -----------------------------------
    j_classes = OUT / "app" / "classes-java"
    j_src = java_src + kapt_java
    cached("app-java", j_src + [kt_classes / "META-INF" / "app.kotlin_module"], "v1", lambda: (
        shutil.rmtree(j_classes, ignore_errors=True),
        javac("app-java", j_src, compile_cp + [kt_classes], j_classes, ["-proc:none"])))

    app_jar = w / "app-classes.jar"
    merged = w / "app-classes-merged"
    shutil.rmtree(merged, ignore_errors=True)
    merged.mkdir(parents=True)
    for src_dir in (kt_classes, j_classes, kapt_dir / "classes"):
        if src_dir.exists():
            shutil.copytree(src_dir, merged, dirs_exist_ok=True)
    jar(app_jar, merged)

    # 6. dex ---------------------------------------------------------------------------
    dex_dir = w / "dex"
    shutil.rmtree(dex_dir, ignore_errors=True)
    dex_dir.mkdir(parents=True)
    program = [app_jar, r_jar, CAL_JAR, DOM_JAR] + lib_jars
    t = time.time()
    rules = [w / "aapt-rules.pro", APP / "proguard-rules.pro", HERE / "r8-extra.pro"]
    rules += [l.proguard for l in libs if l.proguard]
    # consumer rules shipped inside jars (META-INF/proguard, META-INF/com.android.tools)
    jar_rules_dir = w / "jar-rules"
    shutil.rmtree(jar_rules_dir, ignore_errors=True)
    jar_rules_dir.mkdir()
    for j in lib_jars:
        with zipfile.ZipFile(j) as z:
            for n in z.namelist():
                if (n.startswith("META-INF/proguard/") or n.startswith("META-INF/com.android.tools/r8/")) and n.endswith(".pro"):
                    target = jar_rules_dir / (hashlib.md5((str(j) + n).encode()).hexdigest()[:8] + "-" + Path(n).name)
                    target.write_bytes(z.read(n))
                    rules.append(target)
    cmd = [JAVA_HOME / "bin" / "java", "-Xmx3g", "-cp", R8, "com.android.tools.r8.R8",
           "--release" if not debug else "--debug", "--min-api", MIN_SDK, "--lib", ANDROID_JAR,
           "--output", dex_dir, "--pg-map-output", w / "mapping.txt"]
    for r in rules:
        if r and Path(r).exists():
            cmd += ["--pg-conf", r]
    if debug:
        dbg_rules = w / "debug-rules.pro"
        dbg_rules.write_text("-dontobfuscate\n-dontoptimize\n")
        cmd += ["--pg-conf", dbg_rules]
    cmd += program
    out = run(cmd, quiet=True, check=False)
    (OUT / "logs").mkdir(parents=True, exist_ok=True)
    (OUT / "logs" / f"r8-{variant}.log").write_text(out)
    if not list(dex_dir.glob("classes*.dex")):
        print(out[-20000:])
        raise SystemExit("R8 failed")
    print(f"  R8 ({variant}): {len(list(dex_dir.glob('classes*.dex')))} dex files, {time.time() - t:.0f}s")

    # 7. package --------------------------------------------------------------------------
    unsigned = w / "unsigned.apk"
    shutil.copy(res_apk, unsigned)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as z:
        names = set(z.namelist())
        for dex in sorted(dex_dir.glob("classes*.dex")):
            z.write(dex, dex.name)
        # java resources from jars (service loaders, kotlin module metadata is not needed)
        for j in [app_jar] + lib_jars:
            with zipfile.ZipFile(j) as jz:
                for n in jz.namelist():
                    if n.endswith("/") or n.endswith(".class") or n in names:
                        continue
                    if n.startswith("META-INF/services/") or (n.startswith("META-INF/") and n.endswith(".version")):
                        z.writestr(n, jz.read(n))
                        names.add(n)
        for l in aars:
            if l.assets:
                for a in l.assets.rglob("*"):
                    if a.is_file():
                        n = "assets/" + a.relative_to(l.assets).as_posix()
                        if n not in names:
                            z.write(a, n)
                            names.add(n)
    aligned = w / "aligned.apk"
    run([ZIPALIGN, "-p", "-f", "4", unsigned, aligned], quiet=True)
    keystore, ks_pass, alias, key_pass = signing_config(debug)
    final = OUT / f"yadino-{VERSION_NAME}-{variant}.apk"
    # apksigner works without the (optional) Conscrypt native provider
    signer_env = {k: v for k, v in ENV.items() if k != "LD_LIBRARY_PATH"}
    run([JAVA_HOME / "bin" / "java", "-jar", APKSIGNER, "sign", "--ks", keystore, "--ks-pass", f"pass:{ks_pass}",
         "--ks-key-alias", alias, "--key-pass", f"pass:{key_pass}", "--min-sdk-version", MIN_SDK,
         "--out", final, aligned], quiet=True, env=signer_env)
    verify = run([JAVA_HOME / "bin" / "java", "-jar", APKSIGNER, "verify", "--verbose", "--print-certs", final],
                 quiet=True, env=signer_env)
    print("  " + "\n  ".join(l for l in verify.splitlines() if l.startswith("Verified using") or "certificate SHA-256" in l))
    badging = run([AAPT2, "dump", "badging", final], quiet=True)
    (OUT / "logs" / f"badging-{variant}.txt").write_text(badging)
    print(f"  ✅ {final.relative_to(ROOT)} ({final.stat().st_size / 1024 / 1024:.1f} MB)")
    return final


def compile_android_tests():
    """Compiles the instrumented tests (app/src/androidTest) against the app and AndroidX
    Test. They need a device or emulator to run, which this environment does not have."""
    log("Instrumented tests (compile only)")
    w = OUT / "app" / "debug"
    if not (w / "app-classes.jar").exists():
        build_apk("debug")
    misc = TC / "m2misc" / "common" / "androidx-test" / "androidx" / "test"
    test_aars = [misc / "runner/1.6.0/runner-1.6.0.aar", misc / "core/1.6.0/core-1.6.0.aar",
                 misc / "monitor/1.7.0/monitor-1.7.0.aar", misc / "ext/junit/1.2.0/junit-1.2.0.aar"]
    # test-only transitive deps (espresso, …) are provided by the test APK at runtime
    order, seen = resolve_dependencies(APP_DEPENDENCIES + ["androidx.room:room-testing", "androidx.compose.ui:ui-test-junit4"], allow_missing=True)
    jars = []
    for coord in order:
        kind, val = seen[coord]
        for p in (val if kind == "external" else [val]):
            if p is None:
                continue
            lib = Library(coord, p)
            lib.extract()
            jars += lib.jars
    for a in test_aars:
        lib = Library(a.stem, a)
        lib.extract()
        jars += lib.jars
    cp = [ANDROID_JAR, w / "app-classes.jar", w / "r.jar", CAL_JAR, DOM_JAR, DEPS / "junit.jar", DEPS / "hamcrest-core.jar",
          DEPS / "kotlinx-coroutines-test.jar"] + list(dict.fromkeys(jars)) + COMPILE_ONLY
    srcs = sources(APP / "src" / "androidTest", ext=(".kt",))
    kotlinc("app-android-test", srcs, cp, OUT / "android-test-classes",
            [f"-Xplugin={KOTLIN_HOME / 'lib' / 'compose-compiler.jar'}", f"-Xfriend-paths={w / 'app-classes.jar'}"], embeddable=True)
    n = len(list((OUT / "android-test-classes").rglob("*.class")))
    print(f"  ✅ {len(srcs)} instrumented test files compiled ({n} classes) — not executed (no device)")


def run_lint():
    """Runs Android Lint (cmdline-tools) on the app module using an XML project
    description, the same mechanism AOSP's build uses. Reports: build/offline/lint/."""
    log("Android Lint")
    w = OUT / "app" / "debug"
    if not (w / "app-classes.jar").exists():
        build_apk("debug")
    tools = TC / "cmdline-tools" / "tools"
    lint_out = OUT / "lint"
    shutil.rmtree(lint_out, ignore_errors=True)
    lint_out.mkdir(parents=True)
    # minimal SDK layout expected by lint (API database for NewApi etc.)
    sdk = OUT / "lint-sdk"
    plat = sdk / "platforms" / "android-35"
    (plat / "data").mkdir(parents=True, exist_ok=True)
    for src, dst in ((ANDROID_JAR, plat / "android.jar"),
                     (TC / "sdk/35/public/data/api-versions.xml", plat / "data/api-versions.xml"),
                     (TC / "sdk/35/public/data/annotations.zip", plat / "data/annotations.zip")):
        if not dst.exists():
            shutil.copy(src, dst)
    (plat / "source.properties").write_text("Pkg.Desc=Android SDK Platform 35\nPkg.Revision=1\nAndroidVersion.ApiLevel=35\nPlatform.Version=15\n")
    (plat / "build.prop").write_text("ro.build.version.sdk=35\nro.build.version.release=15\n")
    # source manifest with placeholders resolved
    _, scheme = drive_config()
    manifest = lint_out / "AndroidManifest.xml"
    text = (APP_MAIN / "AndroidManifest.xml").read_text().replace("${applicationId}", APP_ID).replace("${driveRedirectScheme}", scheme)
    root = ET.fromstring(text)
    root.set("package", APP_ID)
    root.set(f"{{{ANDROID}}}versionCode", str(VERSION_CODE))
    root.set(f"{{{ANDROID}}}versionName", VERSION_NAME)
    uses_sdk = ET.Element("uses-sdk")
    root.insert(0, uses_sdk)
    uses_sdk.set(f"{{{ANDROID}}}minSdkVersion", str(MIN_SDK))
    uses_sdk.set(f"{{{ANDROID}}}targetSdkVersion", str(TARGET_SDK))
    ET.ElementTree(root).write(manifest, encoding="utf-8", xml_declaration=True)
    libs = libraries()
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<project>", f'<root dir="{ROOT}"/>',
             '<module name="app" android="true" library="false" compile-sdk-version="35" desugar="full" test="false">',
             f'<manifest file="{manifest}"/>', f'<resource file="{APP_MAIN / "res"}"/>',
             f'<classes file="{w / "app-classes.jar"}"/>']
    for src in sources(APP_MAIN / "java", ext=(".kt", ".java")):
        lines.append(f'<src file="{src}"/>')
    for jar_path in [w / "r.jar", CAL_JAR, DOM_JAR] + [j for l in libs if not l.is_aar for j in l.jars]:
        lines.append(f'<classpath file="{jar_path}"/>')
    for l in libs:
        if l.is_aar:
            lines.append(f'<aar file="{l.path}" extracted="{l.dir}"/>')
    lines += ["</module>", "</project>"]
    project = lint_out / "project.xml"
    project.write_text("\n".join(lines))
    cmd = [JAVA_HOME / "bin" / "java", "-Xmx3g", f"-Dcom.android.tools.lint.bindir={tools / 'bin'}",
           "-cp", tools / "lib" / "lint-classpath.jar", "com.android.tools.lint.Main",
           "--project", project, "--sdk-home", sdk, "--offline", "--quiet",
           "--config", APP / "lint.xml",
           "--text", lint_out / "lint.txt", "--xml", lint_out / "lint.xml", "--html", lint_out / "lint.html"]
    t = time.time()
    out = run(cmd, quiet=True, check=False)
    (lint_out / "console.txt").write_text(out)
    summary = (lint_out / "lint.txt").read_text() if (lint_out / "lint.txt").exists() else out
    tail = [l for l in summary.splitlines() if l.strip()][-1:] or ["(no output)"]
    print(f"  lint finished in {time.time() - t:.0f}s: {tail[0]}")
    return summary


def link_check():
    """Shrinks the debug inputs to class files with R8 and verifies every referenced
    method/field exists at runtime (catches binary incompatibilities between libraries)."""
    log("Binary compatibility (link) check")
    w = OUT / "app" / "debug"
    lc_dir = OUT / "linkcheck"
    lc_dir.mkdir(parents=True, exist_ok=True)
    checker = lc_dir / "classes"
    src = HERE / "linkcheck" / "LinkCheck.kt"
    cached("linkcheck-tool", [src], "v1", lambda: kotlinc("linkcheck", [src], [KOTLIN_HOME / "lib" / "kotlin-compiler.jar"], checker))
    libs = libraries()
    lib_jars = [j for l in libs for j in l.jars]
    rules = [w / "aapt-rules.pro", APP / "proguard-rules.pro", HERE / "r8-extra.pro", w / "debug-rules.pro"]
    rules += [l.proguard for l in libs if l.proguard] + sorted((w / "jar-rules").glob("*.pro"))
    out_jar = lc_dir / "shrunk-classes.jar"
    if out_jar.exists():
        out_jar.unlink()
    lc_rules = lc_dir / "linkcheck.pro"
    # class-file output keeps invokedynamic (desugared only when dexing)
    lc_rules.write_text("-dontwarn java.lang.invoke.**\n")
    rules.append(lc_rules)
    cmd = [JAVA_HOME / "bin" / "java", "-Xmx3g", "-cp", R8, "com.android.tools.r8.R8", "--classfile", "--debug",
           "--lib", ANDROID_JAR, "--output", out_jar]
    for r in rules:
        if r and Path(r).exists():
            cmd += ["--pg-conf", r]
    cmd += [w / "app-classes.jar", w / "r.jar", CAL_JAR, DOM_JAR] + lib_jars
    run(cmd, quiet=True)
    out = run([JAVA_HOME / "bin" / "java", "-cp", os.pathsep.join(map(str, [checker, STDLIB, KOTLIN_HOME / "lib" / "kotlin-compiler.jar"])),
               "LinkCheckKt", out_jar, ANDROID_JAR], quiet=True)
    (OUT / "logs" / "linkcheck.txt").write_text(out)
    print("  " + "\n  ".join(out.splitlines()[:40]))
    return out


def signing_config(debug):
    if debug:
        ks = HERE / "debug.keystore"
        if not ks.exists():
            run([JAVA_HOME / "bin" / "keytool", "-genkeypair", "-v", "-keystore", ks, "-storepass", "android",
                 "-alias", "androiddebugkey", "-keypass", "android", "-keyalg", "RSA", "-keysize", "2048",
                 "-validity", "10000", "-dname", "CN=Android Debug,O=Android,C=US"], quiet=True)
        return ks, "android", "androiddebugkey", "android"
    ks = Path(os.environ.get("YADINO_KEYSTORE", str(Path.home() / ".android" / "yadino-release.jks")))
    ks_pass = os.environ.get("YADINO_KEYSTORE_PASSWORD")
    alias = os.environ.get("YADINO_KEY_ALIAS", "yadino")
    key_pass = os.environ.get("YADINO_KEY_PASSWORD", ks_pass or "")
    if not ks.exists() or not ks_pass:
        raise SystemExit("release signing requires YADINO_KEYSTORE and YADINO_KEYSTORE_PASSWORD")
    return ks, ks_pass, alias, key_pass


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("target", choices=["jvm", "test", "apk", "release", "all", "deps", "linkcheck", "androidtest", "lint"], nargs="?", default="all")
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    if args.target == "deps":
        for l in libraries():
            print(l.coord, l.path)
        return
    if args.target in ("jvm", "test", "all"):
        build_jvm()
    if args.target in ("test", "all"):
        build_tests()
    if args.target in ("apk", "all"):
        build_apk("debug")
    if args.target in ("release",):
        build_apk("release")
    if args.target in ("linkcheck",):
        link_check()
    if args.target in ("androidtest", "all"):
        compile_android_tests()
    if args.target in ("lint",):
        run_lint()


if __name__ == "__main__":
    main()
