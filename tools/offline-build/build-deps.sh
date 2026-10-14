#!/usr/bin/env bash
# Compiles third-party libraries that are not available as prebuilts
# (kotlinx.coroutines, okio, kotlinpoet, …) from pinned upstream sources.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/env.sh"
mkdir -p "$DEPS"
WORK="$TC/out/work"; mkdir -p "$WORK"

kt() { # kt <name> <classpath> <kotlinc args...>   -> $DEPS/<name>.jar
  local name=$1 cp=$2; shift 2
  [ -f "$DEPS/$name.jar" ] && { echo "✓ $name"; return; }
  echo "⚙ kotlinc $name"
  local out="$WORK/$name"; rm -rf "$out"; mkdir -p "$out"
  kotlinc -nowarn -jvm-target 1.8 -no-reflect -no-stdlib -module-name "$name" -cp "$KOTLIN_STDLIB${cp:+:$cp}" -d "$out" "$@"
  (cd "$out" && jar cf "$DEPS/$name.jar" .)
}
jv() { # jv <name> <classpath> <srcdir...>
  local name=$1 cp=$2; shift 2
  [ -f "$DEPS/$name.jar" ] && { echo "✓ $name"; return; }
  echo "⚙ javac $name"
  local out="$WORK/$name"; rm -rf "$out"; mkdir -p "$out"
  find "$@" -name "*.java" ! -name module-info.java > "$WORK/$name.srcs"
  if ! javac -nowarn -XDsuppressNotes -Xlint:none -encoding UTF-8 --release "${JV_RELEASE:-8}" -proc:none ${cp:+-cp "$cp"} -d "$out" @"$WORK/$name.srcs" > "$WORK/$name.log" 2>&1; then
    cat "$WORK/$name.log" | head -60; echo "javac failed for $name"; exit 1
  fi
  (cd "$out" && jar cf "$DEPS/$name.jar" .)
}
common_list() { find "$@" -name "*.kt" | paste -sd, -; }
# mpp_args <argfile> "<frag>=<dir1> <dir2>" ... (first fragment is the root, each refines the previous)
# Produces K2 multiplatform fragment arguments in an argfile (paths can be long).
mpp_args() {
  local argfile=$1; shift
  local frags=() refines=() srcs=() prev="" spec name dirs f
  : > "$argfile.tmp"
  for spec in "$@"; do
    name=${spec%%=*}; dirs=${spec#*=}
    frags+=("$name")
    [ -n "$prev" ] && refines+=("$name:$prev")
    prev=$name
    for f in $(find $dirs -name "*.kt" ! -name LockFreedomTestEnvironment.kt ! -path "*/test_/*" 2>/dev/null); do
      echo "$f" >> "$argfile.tmp"; srcs+=("$name:$f")
    done
  done
  {
    echo "-Xmulti-platform"
    echo "-Xexpect-actual-classes"
    echo "-Xfragments=$(IFS=,; echo "${frags[*]}")"
    [ ${#refines[@]} -gt 0 ] && echo "-Xfragment-refines=$(IFS=,; echo "${refines[*]}")"
    echo "-Xfragment-sources=$(IFS=,; echo "${srcs[*]}")"
    cat "$argfile.tmp"
  } > "$argfile"
  rm -f "$argfile.tmp"
}

# --- compile-only annotation stubs ------------------------------------------------
jv build-stubs "" "$HERE/stubs"

# --- kotlinx.atomicfu --------------------------------------------------------
A="$TC/src-atomicfu/atomicfu/src"
mpp_args "$WORK/atomicfu.args" "common=$A/commonMain" "jvm=$A/jvmMain"
kt atomicfu "" @"$WORK/atomicfu.args"

# --- kotlinx.coroutines ------------------------------------------------------
C="$TC/src-coroutines"
COPT=(-opt-in=kotlin.RequiresOptIn
      -opt-in=kotlin.experimental.ExperimentalTypeInference -opt-in=kotlin.ExperimentalMultiplatform
      -opt-in=kotlinx.coroutines.DelicateCoroutinesApi -opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi
      -opt-in=kotlinx.coroutines.ObsoleteCoroutinesApi -opt-in=kotlinx.coroutines.InternalCoroutinesApi
      -opt-in=kotlinx.coroutines.FlowPreview)
if [ ! -f "$DEPS/kotlinx-coroutines-core.jar" ]; then
  mpp_args "$WORK/coroutines.args" "common=$C/kotlinx-coroutines-core/common/src" \
     "concurrent=$C/kotlinx-coroutines-core/concurrent/src" "jvm=$C/kotlinx-coroutines-core/jvm/src"
  kt kotlinx-coroutines-core "$DEPS/atomicfu.jar:$DEPS/build-stubs.jar" "${COPT[@]}" @"$WORK/coroutines.args"
  # merge atomicfu (static lib, like AOSP) + resources
  (cd "$WORK/kotlinx-coroutines-core" && unzip -q -o "$DEPS/atomicfu.jar" -x 'META-INF/MANIFEST.MF' \
     && cp -r $C/kotlinx-coroutines-core/jvm/resources/META-INF . && rm -f META-INF/*.bin \
     && find . -name "DebugProbesKt.bin" -delete; jar cf "$DEPS/kotlinx-coroutines-core.jar" .)
fi
ANNOT=$(ls $M2_ANDROIDX/androidx/annotation/annotation-jvm/*/annotation-jvm-*.jar | head -1)
kt kotlinx-coroutines-android "$DEPS/kotlinx-coroutines-core.jar:$ANDROID_JAR:$ANNOT" \
   -opt-in=kotlinx.coroutines.InternalCoroutinesApi -opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi \
   $(find $C/ui/kotlinx-coroutines-android/src -name "*.kt")
if ! unzip -l "$DEPS/kotlinx-coroutines-android.jar" | grep -q "META-INF/services"; then
  (cd "$WORK/kotlinx-coroutines-android" && cp -r $C/ui/kotlinx-coroutines-android/resources/META-INF . && jar cf "$DEPS/kotlinx-coroutines-android.jar" .)
fi
mpp_args "$WORK/coroutines-test.args" "common=$C/kotlinx-coroutines-test/common/src" "jvm=$C/kotlinx-coroutines-test/jvm/src"
kt kotlinx-coroutines-test "$DEPS/kotlinx-coroutines-core.jar" \
   -opt-in=kotlin.ExperimentalMultiplatform -opt-in=kotlinx.coroutines.InternalCoroutinesApi \
   -opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi @"$WORK/coroutines-test.args"

# --- okio ----------------------------------------------------------------------
O="$TC/src-okio/okio/src"
# okio's common sources use @JvmField on expect members, which K2 fragment
# compilation rejects; compile like AOSP does (K1 + -Xcommon-sources).
find $O/commonMain $O/zlibMain $O/systemFileSystemMain $O/jvmMain -name "*.kt" > "$WORK/okio.args"
kt okio "" -language-version 1.9 -api-version 1.9 -Xmulti-platform -Xexpect-actual-classes -opt-in=kotlin.RequiresOptIn \
   -Xcommon-sources="$(common_list $O/commonMain $O/zlibMain $O/systemFileSystemMain)" @"$WORK/okio.args"

# --- Java libraries ------------------------------------------------------------
GUAVA="$M2_TOOLS/com/google/guava/guava/32.1.1-jre/guava-32.1.1-jre.jar"
jv failureaccess "" "$TC/src-guava/futures/failureaccess/src"
jv javapoet "" "$TC/src-javapoet/src/main/java"
jv hamcrest-core "" "$TC/src-hamcrest/hamcrest-core/src/main/java"
jv junit "$DEPS/hamcrest-core.jar" "$TC/src-junit/src/main/java"
JV_RELEASE=11 jv jspecify "" "$TC/src-jspecify/src/main/java"
jv auto-value-annotations "" "$TC/src-auto/value/src/main/java/com/google/auto/value"
jv auto-common "$GUAVA:$DEPS/javapoet.jar:$DEPS/auto-value-annotations.jar:$DEPS/build-stubs.jar" "$TC/src-auto/common/src/main/java"

# --- kotlinpoet / ksp api ---------------------------------------------------------
KREFLECT="$KOTLIN_HOME/lib/kotlin-reflect.jar"
KP="$TC/src-kotlinpoet"
if [ -d "$KP/kotlinpoet/src/jvmMain" ]; then
  mpp_args "$WORK/kotlinpoet.args" "common=$KP/kotlinpoet/src/commonMain" "jvm=$KP/kotlinpoet/src/jvmMain"
else
  find "$KP/kotlinpoet/src/commonMain" -name "*.kt" > "$WORK/kotlinpoet.args"
fi
kt kotlinpoet "$KREFLECT" -Xjvm-default=all @"$WORK/kotlinpoet.args"
kt kotlinpoet-javapoet "$DEPS/kotlinpoet.jar:$DEPS/javapoet.jar" -Xjvm-default=all \
   $(find $KP/interop/javapoet/src/main -name "*.kt")
kt symbol-processing-api "" -Xjvm-default=all-compatibility $(find "$TC/src-ksp/api/src/main" -name "*.kt")

# --- kotlin-metadata-jvm (from JetBrains/kotlin v2.0.21) ---------------------------
if [ ! -f "$DEPS/kotlin-metadata-jvm.jar" ]; then
  KM="$TC/src-kotlin-metadata/libraries/kotlinx-metadata"
  kt kotlin-metadata-jvm-raw "$KOTLIN_HOME/lib/kotlin-compiler.jar" -Xallow-kotlin-package \
     -Xjvm-default=all -opt-in=kotlin.metadata.ExperimentalContextReceivers \
     $(find $KM/src $KM/jvm/src -name "*.kt")
  # bundle the (unrelocated) metadata protobuf classes it depends on
  D="$WORK/kotlin-metadata-jvm-bundle"; rm -rf "$D"; mkdir -p "$D"
  (cd "$D" && unzip -q -o "$DEPS/kotlin-metadata-jvm-raw.jar" \
     && unzip -q -o "$KOTLIN_HOME/lib/kotlin-compiler.jar" 'org/jetbrains/kotlin/metadata/*' 'org/jetbrains/kotlin/protobuf/*' \
     && jar cf "$DEPS/kotlin-metadata-jvm.jar" .)
fi
echo "deps ready:"; ls -la "$DEPS"
