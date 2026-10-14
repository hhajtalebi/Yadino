#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Yadino – hermetic toolchain fetcher
#
# Downloads everything needed to compile, test and package Yadino WITHOUT
# access to Google Maven / Maven Central / services.gradle.org.  All inputs are
# fetched from public GitHub mirrors of AOSP prebuilts and from upstream source
# repositories (pinned to exact tags), using git partial clones so only the
# required files are transferred.
#
# Normal development should use Android Studio / Gradle (see docs/BUILD.md).
# This script exists for restricted CI / sandbox environments.
# -----------------------------------------------------------------------------
set -euo pipefail
TC="${YADINO_TC:-$HOME/.cache/yadino-toolchain}"
mkdir -p "$TC"
cd "$TC"

# sparse partial clone: fetch <dir> <owner/repo> <ref> <patterns...>
fetch() {
  local name=$1 repo=$2 ref=$3; shift 3
  if [ -f "$name/.fetched" ]; then echo "✓ $name"; return; fi
  rm -rf "$name"
  echo "↓ $name ($repo@$ref)"
  git clone -q --depth 1 --filter=blob:none --no-checkout --branch "$ref" "https://github.com/$repo" "$name"
  ( cd "$name" && git sparse-checkout init --no-cone >/dev/null \
      && printf '%s\n' "$@" > .git/info/sparse-checkout && git checkout -q "$ref" )
  touch "$name/.fetched"
}

# ---- AOSP prebuilts (GitHub mirror of android.googlesource.com) -------------
fetch jdk17   msft-mirror-aosp/platform.prebuilts.jdk.jdk17 main \
  '/linux-x86/' '!/linux-x86/jmods/' '!/linux-x86/lib/src.zip'
fetch kotlinc msft-mirror-aosp/platform.external.kotlinc main \
  '/bin/' '/lib/' '/build.txt' '!*-sources.jar' '!*.klib'
fetch r8      msft-mirror-aosp/platform.prebuilts.r8 main '/r8.jar'
M=/current/androidx/m2repository/androidx
fetch sdk     msft-mirror-aosp/platform.prebuilts.sdk main \
  '/35/public/android.jar' '/35/public/framework.aidl' \
  '/tools/linux/bin/aapt2' '/tools/linux/bin/zipalign' '/tools/linux/bin/source.properties' \
  '/tools/linux/lib/' '/tools/linux/lib64/' '/tools/core-lambda-stubs.jar' \
  $M/activity/ $M/annotation/ $M/arch/ $M/autofill/ $M/collection/ $M/compose/ \
  $M/concurrent/ $M/core/ $M/customview/ $M/datastore/ $M/emoji2/ $M/graphics/ \
  $M/interpolator/ $M/lifecycle/ $M/profileinstaller/ $M/room/ $M/savedstate/ \
  $M/sqlite/ $M/startup/ $M/tracing/ $M/versionedparcelable/ $M/work/ $M/browser/ \
  '!*-sources.jar' '!*.md5' '!*.sha1' '!*.sha256' '!*.sha512'
P=/common/m2/repository
fetch m2tools msft-mirror-aosp/platform.prebuilts.tools main \
  $P/com/google/guava/guava/32.1.1-jre/guava-32.1.1-jre.jar \
  $P/com/google/guava/guava/listenablefuture/1.0/ \
  $P/commons-codec/commons-codec/1.10/commons-codec-1.10.jar \
  $P/org/xerial/sqlite-jdbc/3.28.0/sqlite-jdbc-3.28.0.jar

# ---- Upstream sources (pinned tags) ----------------------------------------
fetch src-coroutines Kotlin/kotlinx.coroutines 1.9.0 \
  '/kotlinx-coroutines-core/common/src/' '/kotlinx-coroutines-core/concurrent/src/' \
  '/kotlinx-coroutines-core/jvm/src/' '/kotlinx-coroutines-core/jvm/resources/' \
  '/ui/kotlinx-coroutines-android/src/' '/ui/kotlinx-coroutines-android/resources/' \
  '/kotlinx-coroutines-test/common/src/' '/kotlinx-coroutines-test/jvm/src/' '/kotlinx-coroutines-test/jvm/resources/'
fetch src-atomicfu Kotlin/kotlinx.atomicfu 0.23.1 '/atomicfu/src/commonMain/' '/atomicfu/src/jvmMain/'
fetch src-okio square/okio parent-3.9.0 \
  '/okio/src/commonMain/' '/okio/src/jvmMain/' '/okio/src/zlibMain/' '/okio/src/systemFileSystemMain/'

fetch src-kotlinpoet square/kotlinpoet 1.17.0 '/kotlinpoet/src/commonMain/' '/kotlinpoet/src/jvmMain/' '/interop/javapoet/src/main/'
fetch src-ksp google/ksp 2.0.10-1.0.24 '/api/src/main/'
fetch src-javapoet square/javapoet javapoet-1.13.0 '/src/main/java/'
fetch src-auto google/auto auto-common-1.2.1 '/common/src/main/java/' '/value/src/main/java/com/google/auto/value/*.java'
fetch src-guava google/guava v32.1.1 '/futures/failureaccess/src/'
fetch src-junit msft-mirror-aosp/platform.external.junit main '/src/main/java/'
# AOSP's copy ships the generated CoreMatchers.java
fetch src-hamcrest msft-mirror-aosp/platform.external.hamcrest main '/hamcrest-core/src/main/java/'
fetch src-jspecify jspecify/jspecify v1.0.0 '/src/main/java/'
fetch src-kotlin-metadata JetBrains/kotlin v2.0.21 '/libraries/kotlinx-metadata/src/' '/libraries/kotlinx-metadata/jvm/src/'

echo "Toolchain ready in $TC"
