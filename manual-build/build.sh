#!/usr/bin/env bash
# Manual Artlog round-2 build (no Gradle).
#
# Round 2 adds Room (kapt annotation processing) and Coil. kapt runs in
# three passes (see step 4): kotlinc generates stubs, javac -proc:only runs
# the Room processor, then a plain kotlinc compile produces the classes.
# A single kotlinc run with aptMode=stubsAndApt does NOT work under the CLI:
# it emits no classes and runs no processing (verified 2026-11-12). The
# processor jars live in processor/ and are NEVER dexed into the APK.
#
# WHY THIS EXISTS: this script was written in an environment where Gradle
# could not run (the sandbox blocks all outbound TCP from JVM processes,
# which breaks Gradle's client<->daemon socket handshake). The Gradle
# project in this repository is complete and builds with
# ./gradlew assembleDebug on a normal machine — prefer that. This script
# reproduces the same build with local-only tools (aapt2, kotlinc via the
# embeddable compiler, d8, zipalign, apksigner) and dependencies pre-fetched
# with curl (see resolve_deps.py).
#
# Usage: ./build.sh   (run from ~/workspace/artlog/manual-build)
# Output: ../app-debug.apk  (also left in build/)
set -euo pipefail

JAVA_HOME="${JAVA_HOME:-$HOME/jdk17}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
export JAVA_OPTS="-Xmx1g -Dfile.encoding=UTF-8"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/.android-sdk}}"
AAPT2="$SDK/build-tools/34.0.0/aapt2"
# NOTE: build-tools 34.0.0's d8 (8.2.2-dev) crashes with an internal NPE on
# classes whose EnclosingMethod attribute has method_index=0 (e.g.
# LiveData$1, an anonymous Runnable declared as a field initializer — valid
# per the JVM spec). Use the standalone R8 8.5.35 D8 instead, the same R8
# line AGP 8.5.x uses.
R8_JAR="${R8_JAR:-$HOME/downloads/r8-8.5.35.jar}"
ZIPALIGN="$SDK/build-tools/34.0.0/zipalign"
APKSIGNER="$SDK/build-tools/34.0.0/apksigner"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
KOTLIN_HOME="${KOTLIN_HOME:-$HOME/kotlin-2.0.20/kotlinc}"
COMPOSE_PLUGIN_CLI="${COMPOSE_PLUGIN_CLI:-$HOME/downloads/kotlin-compose-compiler-plugin-2.0.20.jar}"
# kapt (Room annotation processing): the CLI plugin jar ships with the
# Kotlin distribution; the processor classpath is processor/*.jar.
# NOTE: PROCESSOR_CP is defined below, after ROOT is set (set -u).
KAPT_PLUGIN="$KOTLIN_HOME/lib/kotlin-annotation-processing.jar"

ROOT="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$ROOT/.."
BUILD="$ROOT/build"
DEPS="$ROOT/deps"
PROCESSOR_CP="$(ls "$ROOT"/processor/*.jar 2>/dev/null | paste -sd: -)"

rm -rf "$BUILD"
mkdir -p "$BUILD/aapt2" "$BUILD/classes" "$BUILD/dex" "$BUILD/gen"

echo "== 1. Unpack AARs =="
rm -rf "$BUILD/unpacked"
mkdir -p "$BUILD/unpacked"
CLASS_JARS=()
PLAIN_JARS=()
LIB_PACKAGES=()
for aar in "$DEPS"/*.aar; do
    name="$(basename "$aar" .aar)"
    dest="$BUILD/unpacked/$name"
    mkdir -p "$dest"
    unzip -q -o "$aar" -d "$dest"
    [ -f "$dest/classes.jar" ] && CLASS_JARS+=("$dest/classes.jar")
    # package name from the AAR manifest (for R.java generation)
    pkg="$(python3 -c "
import re,sys
data = open('$dest/AndroidManifest.xml','rb').read()
m = re.search(rb'package=\"([^\"]+)\"', data)
print(m.group(1).decode() if m else '')
" 2>/dev/null || true)"
    # AAR manifests are binary XML; fall back to aapt2 dump if needed
    if [ -z "$pkg" ] && [ -f "$dest/AndroidManifest.xml" ]; then
        pkg="$("$AAPT2" dump xmltree "$aar" AndroidManifest.xml 2>/dev/null | grep -m1 -o 'package="[^"]*"' | cut -d'"' -f2 || true)"
    fi
    [ -n "$pkg" ] && LIB_PACKAGES+=("$pkg")
done
for jar in "$DEPS"/*.jar; do
    PLAIN_JARS+=("$jar")
done
echo "   ${#CLASS_JARS[@]} AAR classes.jars, ${#PLAIN_JARS[@]} plain jars"
echo "   library packages: ${LIB_PACKAGES[*]}"

echo "== 2. aapt2 compile resources =="
RES_LIST="$BUILD/aapt2/res.list"
: > "$RES_LIST"
compile_res() {
    local resdir="$1" out="$2"
    [ -d "$resdir" ] || return 0
    "$AAPT2" compile --dir "$resdir" -o "$out"
    echo "$out" >> "$RES_LIST"
}
compile_res "$PROJECT/app/src/main/res" "$BUILD/aapt2/app.zip"
for d in "$BUILD/unpacked"/*/; do
    compile_res "${d}res" "$BUILD/aapt2/$(basename "$d").zip"
done
echo "   compiled $(wc -l < "$RES_LIST") resource zips"

echo "== 3. aapt2 link (generates R.java) =="
# 2026-11-12 root cause (confirmed by the user's real crash log, v5):
# --extra-packages must be passed ONCE PER PACKAGE. The old code joined
# all library packages into ONE comma-separated argument, and aapt2
# treated the whole string as a single package name: it generated ONE
# bogus R.java under a directory literally named with commas
# (package "com.google.accompanist.drawablepainter,androidx.activity,...")
# and no per-library R.java at all. (aapt2's help says "Generate the
# same R.java but with different package names" — the flag is repeatable,
# one package per occurrence.)
EXTRA_ARGS=()
for pkg in "${LIB_PACKAGES[@]}"; do EXTRA_ARGS+=(--extra-packages "$pkg"); done
R_ARGS=()
while IFS= read -r f; do R_ARGS+=(-R "$f"); done < "$RES_LIST"
"$AAPT2" link -o "$BUILD/app-unsigned.apk" \
    --manifest "$PROJECT/app/src/main/AndroidManifest.xml" \
    -I "$ANDROID_JAR" \
    --auto-add-overlay \
    --java "$BUILD/gen" \
    "${EXTRA_ARGS[@]}" \
    --min-sdk-version 26 \
    --target-sdk-version 34 \
    --version-code 28 \
    --version-name 1.0.0 \
    "${R_ARGS[@]}"
# NOTE: --version-code / --version-name here are the values the APK
# actually ships with — aapt2 link does NOT read build.gradle.kts.
# Keep them in sync with app/build.gradle.kts by hand (v28: version
# 1.0.0, database v7; first public release, versionName aligned with
# the v1.0.0 release tag).
echo "   linked APK: $(du -h "$BUILD/app-unsigned.apk" | cut -f1)"

echo "== 4a. kotlinc + kapt (stubs only) =="
CP="$ANDROID_JAR"
for j in "${CLASS_JARS[@]}" "${PLAIN_JARS[@]}"; do CP="$CP:$j"; done
CP="$CP:$KOTLIN_HOME/lib/kotlin-stdlib.jar"
SOURCES="$(find "$PROJECT/app/src/main/java" -name '*.kt' | sort)"
RJAVA="$(find "$BUILD/gen" -name '*.java' | sort)"
echo "   kotlin sources: $(echo "$SOURCES" | wc -l), generated R.java: $(echo "$RJAVA" | wc -l)"
echo "   kapt apclasspath: $(echo "$PROCESSOR_CP" | tr ':' '\n' | wc -l) processor jars"
mkdir -p "$BUILD/kapt" "$BUILD/kapt-stubs" "$BUILD/kapt-classes"
# NOTE: the Compose compiler plugin must be the NON-embeddable artifact
# (kotlin-compose-compiler-plugin, not -embeddable): the embeddable one is
# built against the compiler's relocated IntelliJ API and fails under the
# kotlinc CLI with AbstractMethodError on ComponentRegistrar.
#
# kapt under the kotlinc CLI does NOT work the way Gradle drives it:
# a single kotlinc run with aptMode=stubsAndApt generates the Java stubs
# but emits NO .class files and runs NO annotation processing (verified
# 2026-11-12: 67 stubs, 0 classes, 0 generated sources, exit 0 — and the
# resulting APK silently contained no app classes at all). Gradle's kapt
# task runs three passes, and this script does the same:
#   4a. kotlinc + kapt, aptMode=stubs  -> Java stubs in $BUILD/kapt-stubs
#   4b. javac -proc:only on the stubs  -> Room's *_Impl in $BUILD/kapt
#   4c. plain kotlinc (no kapt)        -> real .class files
#   4d. javac on the generated sources -> _Impl classes
# The Room database sets exportSchema=false, so no schema location
# argument is needed anywhere.
#
# 2026-11-12 LAUNCH-CRASH ROOT CAUSE (confirmed from the user's real
# crash log, not a guess): the v1–v5 APKs contained ZERO library R
# classes. Two compounding gaps in this script: (1) step 3 passed
# --extra-packages ONCE with all library packages comma-joined, and
# aapt2 treated the whole string as ONE package name, generating a
# single bogus R.java instead of one per library; (2) kotlinc never
# emits .class files for .java sources, so even the R.java files that
# did exist were never compiled — only kapt sources went through javac.
# The first runtime touch of any library R class,
# PoolingContainer.<clinit> reading androidx.customview.poolingcontainer
# R$id at the first ComposeView creation, threw NoClassDefFoundError
# before the first frame. Fixed in step 3 (one --extra-packages flag
# per package) and step 4e (javac compiles all generated R.java).
# shellcheck disable=SC2086
JAVA_OPTS="$JAVA_OPTS" "$KOTLIN_HOME/bin/kotlinc" \
    -jvm-target 17 \
    -Xplugin="$COMPOSE_PLUGIN_CLI" \
    -Xplugin="$KAPT_PLUGIN" \
    -P "plugin:org.jetbrains.kotlin.kapt3:apclasspath=$PROCESSOR_CP" \
    -P "plugin:org.jetbrains.kotlin.kapt3:aptMode=stubs" \
    -P "plugin:org.jetbrains.kotlin.kapt3:sources=$BUILD/kapt" \
    -P "plugin:org.jetbrains.kotlin.kapt3:stubs=$BUILD/kapt-stubs" \
    -P "plugin:org.jetbrains.kotlin.kapt3:classes=$BUILD/kapt-classes" \
    -P "plugin:org.jetbrains.kotlin.kapt3:includeCompileClasspath=true" \
    -cp "$CP" \
    -d "$BUILD/kapt-classes" \
    $SOURCES $RJAVA
echo "   kapt stubs: $(find "$BUILD/kapt-stubs" -name '*.java' | wc -l)"

echo "== 4b. javac -proc:only (run Room on the stubs) =="
# Only the data-package stubs are needed: Room only looks at @Database /
# @Entity / @Dao elements, and the other stubs contain kapt artefacts that
# fail plain attribution. -proc:only runs the processors without compiling,
# so those stub quirks are harmless. -encoding UTF-8 is required: the
# stubs embed KDoc comments with non-ASCII characters and this VM's JVM
# default charset is US-ASCII.
# 2026-11-13 P1 build fix (test/delivery round): P1 moved MusicRepeatMode
# from ui.music to playback/PlaybackModeLogic.kt, and AppearanceState /
# AppearanceSettings now reference com.artlog.app.playback.MusicRepeatMode.
# javac -proc:only on the data stubs alone then fails with "package
# com.artlog.app.playback does not exist" (6 errors, verified in the
# first v20 build attempt). Passing the enum's kapt stub as well resolves
# the type; Room only processes @Database/@Entity/@Dao elements, so the
# extra stub is inert for annotation processing. This is a build-script
# fix only — no product code changed.
# shellcheck disable=SC2086
"$JAVA_HOME/bin/javac" -proc:only -encoding UTF-8 -nowarn \
    -cp "$CP" \
    -processorpath "$PROCESSOR_CP" \
    -s "$BUILD/kapt" \
    $(find "$BUILD/kapt-stubs/com/artlog/app/data" -name '*.java' | sort) \
    "$BUILD/kapt-stubs/com/artlog/app/playback/MusicRepeatMode.java"
echo "   kapt generated java: $(find "$BUILD/kapt" -name '*.java' | wc -l)"
if [ ! -f "$BUILD/kapt/com/artlog/app/data/ArtworkDatabase_Impl.java" ]; then
    echo "ERROR: Room did not generate ArtworkDatabase_Impl.java" >&2
    exit 1
fi

echo "== 4c. kotlinc (plain compile, no kapt) =="
# shellcheck disable=SC2086
JAVA_OPTS="$JAVA_OPTS" "$KOTLIN_HOME/bin/kotlinc" \
    -jvm-target 17 \
    -Xplugin="$COMPOSE_PLUGIN_CLI" \
    -cp "$CP" \
    -d "$BUILD/classes" \
    $SOURCES $RJAVA
echo "   compiled classes: $(find "$BUILD/classes" -name '*.class' | wc -l)"
APP_CLASSES="$(find "$BUILD/classes/com/artlog/app" -name '*.class' | wc -l)"
echo "   app classes: $APP_CLASSES"
if [ "$APP_CLASSES" -eq 0 ]; then
    echo "ERROR: no com.artlog.app classes were compiled — refusing to ship an APK without MainActivity" >&2
    exit 1
fi

echo "== 4d. javac (kapt-generated sources) =="
GEN_JAVA="$(find "$BUILD/kapt" -name '*.java' 2>/dev/null | sort)"
if [ -n "$GEN_JAVA" ]; then
    echo "$GEN_JAVA"
    # shellcheck disable=SC2086
    "$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -nowarn \
        -cp "$CP:$BUILD/classes" \
        -d "$BUILD/classes" \
        $GEN_JAVA
    echo "   classes after javac: $(find "$BUILD/classes" -name '*.class' | wc -l)"
else
    echo "   (no kapt-generated sources found)"
    exit 1
fi
echo "== 4e. javac (generated R.java — library + app R classes) =="
# 2026-11-12 root cause of the launch crash, part 2 (confirmed by the
# user's real crash log, v5): kotlinc does NOT emit .class files for
# .java sources — it only reads them for symbol resolution. The R.java
# files aapt2 generates were therefore NEVER compiled by any step of
# this script (only kapt-generated sources went through javac in 4d),
# and the APK's dex contained ZERO R classes. The first runtime touch
# of any library R class — PoolingContainer.<clinit> reading R$id when
# the first ComposeView is created in MainActivity.setContent — threw
# NoClassDefFoundError before the first frame. Gradle compiles these in
# its own compileDebugJavaWithJavac task; here javac must do it.
# shellcheck disable=SC2086
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -nowarn \
    -cp "$CP" \
    -d "$BUILD/classes" \
    $(find "$BUILD/gen" -name '*.java' | sort)
R_CLASSES="$(find "$BUILD/classes" -name 'R*.class' | wc -l)"
echo "   R classes compiled: $R_CLASSES"
if [ "$R_CLASSES" -eq 0 ]; then
    echo "ERROR: no R classes were compiled — refusing to ship an APK whose dex has no R classes" >&2
    exit 1
fi
if [ ! -f "$BUILD/classes/androidx/customview/poolingcontainer/R\$id.class" ]; then
    echo "ERROR: poolingcontainer R\$id class missing after javac — the launch crash would return" >&2
    exit 1
fi
echo "== 5. d8 dex =="
# d8 does not accept a directory of .class files; jar them first.
jar cf "$BUILD/classes.jar" -C "$BUILD/classes" .
DEX_INPUTS=("$BUILD/classes.jar")
# PLAIN_JARS already includes kotlin-stdlib-2.0.20.jar; do NOT also add
# kotlinc/lib/kotlin-stdlib.jar (same classes -> "defined multiple times").
for j in "${CLASS_JARS[@]}" "${PLAIN_JARS[@]}"; do DEX_INPUTS+=("$j"); done
# --min-api 26: with min-api >= 21 D8 splits into multiple dex files
# automatically when the method count exceeds the 64k single-dex limit
# (the full Compose closure is ~111k methods). This matches the Gradle
# build, which enables native multidex by default for minSdk >= 21.
java $JAVA_OPTS -cp "$R8_JAR" com.android.tools.r8.D8 --min-api 26 --output "$BUILD/dex" --lib "$ANDROID_JAR" "${DEX_INPUTS[@]}"
ls -la "$BUILD/dex"

echo "== 5b. dex gate (fail fast on an empty dex) =="
# 2026-11-12 lesson: a build once sailed through D8, packaging and
# signing with ZERO app classes and still printed BUILD OK. That APK
# would crash instantly on launch (no MainActivity in the dex). Never
# again: verify the dex actually contains the startup classes BEFORE
# anything is signed or copied to the project root.
DEXDUMP="$SDK/build-tools/34.0.0/dexdump"
# NOTE: dexdump prints ~170 MB of text for this app. Do NOT capture it in
# a shell variable and pipe it to `grep -q`: grep quits on the first match,
# printf then dies with SIGPIPE, and with `set -o pipefail` that makes the
# pipeline fail even though the class IS present (false negative that
# blocked the 2026-11-12 build). Stream dexdump to a file once and grep
# the file instead — no pipe, no SIGPIPE, no giant variable.
"$DEXDUMP" "$BUILD"/dex/classes*.dex > "$BUILD/dexdump.txt" 2>/dev/null
for REQUIRED_CLASS in \
    "Lcom/artlog/app/MainActivity;" \
    "Lcom/artlog/app/ArtlogApplication;" \
    "Lcom/artlog/app/data/ArtworkDatabase_Impl;" \
    "Lcom/artlog/app/data/ArchiveDao_Impl;" \
    "Lcom/artlog/app/data/Tag;" \
    "Lcom/artlog/app/data/JournalEntry;" \
    "Lcom/artlog/app/data/ArtworkTagCrossRef;" \
    "Lcom/artlog/app/data/BackupManager;" \
    "Lcom/artlog/app/data/InspirationRepository;" \
    "Lcom/artlog/app/data/InspirationWork;" \
    "Lcom/artlog/app/data/InspirationContentType;" \
    "Lcom/artlog/app/data/InspirationLicenseStatus;" \
    "Lcom/artlog/app/data/InspirationSource;" \
    "Lcom/artlog/app/data/InspirationFavorite;" \
    "Lcom/artlog/app/data/WebsiteFavorite;" \
    "Lcom/artlog/app/data/WebsiteFavoriteDao;" \
    "Lcom/artlog/app/data/WebsiteFavoriteDao_Impl;" \
    "Lcom/artlog/app/data/WebsiteFavoriteRepository;" \
    "Lcom/artlog/app/data/VisualDirectory;" \
    "Lcom/artlog/app/data/InspirationDao_Impl;" \
    "Lcom/artlog/app/data/ImageDisplayGateKt;" \
    "Lcom/artlog/app/data/ImageRetryInterceptor;" \
    "Lcom/artlog/app/ui/directory/VisualInspirationScreenKt;" \
    "Lcom/artlog/app/ui/directory/LegacyFavoritesScreenKt;" \
    "Lcom/artlog/app/ui/directory/DirectoryUiLogicKt;" \
    "Lcom/artlog/app/ui/directory/DirectorySiteItem;" \
    "Lcom/artlog/app/ui/directory/VisualInspirationViewModel;" \
    "Lcom/artlog/app/ui/directory/LegacyFavoritesViewModel;" \
    "Lcom/artlog/app/ui/screens/BackupViewModel;" \
    "Lcom/artlog/app/ui/screens/SettingsScreenKt;" \
    "Lcom/artlog/app/ui/journal/JournalScreenKt;" \
    "Lcom/artlog/app/ui/statistics/StatisticsScreenKt;" \
    "Lcom/artlog/app/ui/tags/TagManagementScreenKt;" \
    "Lcom/artlog/app/CrashReporter;" \
    "Lkotlinx/coroutines/android/AndroidDispatcherFactory;" \
    "Landroidx/customview/poolingcontainer/R\$id;" \
    "Lcom/artlog/app/data/MusicTrack;" \
    "Lcom/artlog/app/data/MusicTrackDao_Impl;" \
    "Lcom/artlog/app/data/MusicTrackRepository;" \
    "Lcom/artlog/app/data/MusicStorage;" \
    "Lcom/artlog/app/data/LyricsParser;" \
    "Lcom/artlog/app/data/MusicImport;" \
    "Lcom/artlog/app/playback/PlaybackService;" \
    "Lcom/artlog/app/ui/music/MusicScreenKt;" \
    "Lcom/artlog/app/ui/music/MusicViewModel;" \
    "Landroidx/media3/exoplayer/ExoPlayer;" \
    "Landroidx/media3/session/MediaSessionService;" \
    "Landroidx/media3/session/MediaSession;" \
    "Landroidx/media3/session/MediaController;" \
    "Landroidx/media3/common/MediaItem;" \
    "Lcom/artlog/app/R;" ; do
    if ! grep -qF "$REQUIRED_CLASS" "$BUILD/dexdump.txt"; then
        echo "ERROR: dex is missing required class $REQUIRED_CLASS — refusing to sign" >&2
        exit 1
    fi
    echo "   dex contains $REQUIRED_CLASS"
done

# Stage 4: the old Feed must leave no class behind in the dex. Any
# of these present means a deleted file (or a reference to it) is
# still compiled in — fail the build the same way a missing
# required class does.
for FORBIDDEN_CLASS in \
    "Lcom/artlog/app/data/OpenverseSource;" \
    "Lcom/artlog/app/data/CommonsSource;" \
    "Lcom/artlog/app/data/MetSource;" \
    "Lcom/artlog/app/data/AicSource;" \
    "Lcom/artlog/app/data/InspirationSourcesKt;" \
    "Lcom/artlog/app/data/OpenverseCircuitBreaker;" \
    "Lcom/artlog/app/data/FetchFailureKind;" \
    "Lcom/artlog/app/data/CircuitBreakerKt;" \
    "Lcom/artlog/app/data/RelevanceRulesKt;" \
    "Lcom/artlog/app/data/LinkDiscoverySource;" \
    "Lcom/artlog/app/data/InspirationCacheItem;" \
    "Lcom/artlog/app/data/InspirationMixConfig;" \
    "Lcom/artlog/app/ui/inspiration/InspirationScreenKt;" \
    "Lcom/artlog/app/ui/inspiration/InspirationViewModel;" ; do
    if grep -qF "$FORBIDDEN_CLASS" "$BUILD/dexdump.txt"; then
        echo "ERROR: dex still contains deleted Feed class $FORBIDDEN_CLASS — refusing to sign" >&2
        exit 1
    fi
    echo "   dex clean of $FORBIDDEN_CLASS"
done

echo "== 5c. migration check (round 4: v1 -> v2, round 5: v2 -> v3, round 5.1: v3 -> v4, Artlog 2.0 minimal v5: v4 -> v5, next iteration minimal v6: v5 -> v6, final round v6 -> v7, build level) =="
# verify_migration.py parses MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
# MIGRATION_4_5 and MIGRATION_5_6 out of ArtworkDatabase.kt and diffs them against the
# kapt-generated ArtworkDatabase_Impl.java, runs each migration on a test
# database with sample rows, and verifies counts and associations survive.
# Any failure fails the whole build: a wrong migration would crash Room at
# open time on the user's phone.
python3 "$PROJECT/manual-build/verify_migration.py" \
    --impl "$BUILD/kapt/com/artlog/app/data/ArtworkDatabase_Impl.java" \
    --source "$PROJECT/app/src/main/java/com/artlog/app/data/ArtworkDatabase.kt" \
    || { echo "ERROR: migration check failed — refusing to sign" >&2; exit 1; }

echo "== 6. Package, align, sign =="
cp "$BUILD/app-unsigned.apk" "$BUILD/app-debug.apk"
# Native libraries from AARs (e.g. androidx.graphics.path) are packaged
# by Gradle under lib/<abi>/; the manual build must do the same, or the
# libraries silently fall back (or fail) at runtime.
python3 - "$BUILD/dex" "$BUILD/app-debug.apk" "$BUILD/unpacked" <<'EOF'
import glob, os, sys, zipfile
dex_dir, apk, unpacked = sys.argv[1], sys.argv[2], sys.argv[3]
# NOTE: some .so files extracted from AARs carry pre-1980 zip timestamps,
# which Python's zipfile rejects ("ZIP does not support timestamps before
# 1980"). Write every entry with a fixed 1980-01-01 timestamp.
def add(z, path, arcname):
    zi = zipfile.ZipInfo(arcname, date_time=(1980, 1, 1, 0, 0, 0))
    zi.compress_type = zipfile.ZIP_DEFLATED
    with open(path, "rb") as f:
        z.writestr(zi, f.read())
    print("added", arcname)
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    for dex in sorted(glob.glob(os.path.join(dex_dir, "classes*.dex"))):
        add(z, dex, os.path.basename(dex))
    for so in sorted(glob.glob(os.path.join(unpacked, "*", "jni", "*", "*.so"))):
        parts = so.split(os.sep)
        abi = parts[-2]
        arcname = "lib/%s/%s" % (abi, os.path.basename(so))
        add(z, so, arcname)
EOF
"$ZIPALIGN" -f 4 "$BUILD/app-debug.apk" "$BUILD/app-debug-aligned.apk"
mv "$BUILD/app-debug-aligned.apk" "$BUILD/app-debug.apk"

KEYSTORE="$ROOT/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
    echo "   generated debug keystore"
fi
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android \
    --key-pass pass:android --out "$BUILD/app-debug-signed.apk" "$BUILD/app-debug.apk"
mv "$BUILD/app-debug-signed.apk" "$BUILD/app-debug.apk"

echo "== 7. Verify =="
"$APKSIGNER" verify --verbose "$BUILD/app-debug.apk"
# head closes the pipe early; without `|| true`, pipefail would kill the
# script with SIGPIPE (141) before the final copy below.
"$AAPT2" dump badging "$BUILD/app-debug.apk" | head -6 || true
cp "$BUILD/app-debug.apk" "$PROJECT/app-debug.apk"
echo "APK: $PROJECT/app-debug.apk  ($(du -h "$PROJECT/app-debug.apk" | cut -f1), $(stat -c%s "$PROJECT/app-debug.apk") bytes)"
echo "BUILD OK"
