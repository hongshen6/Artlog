#!/usr/bin/env bash
# Run one JVM test from manual-build/jvm-test/ against the real
# data-layer sources, the way stage-1 prep did it (one-off kotlinc
# command, saved here so it is reproducible).
#
# Usage (from ~/workspace/artlog):
#   manual-build/jvm-test/run.sh TestGate
#   manual-build/jvm-test/run.sh TestWebsiteFavorites
#   manual-build/jvm-test/run.sh TestDirectoryLogic
#   manual-build/jvm-test/run.sh TestOverrides
#   manual-build/jvm-test/run.sh TestCategories
#   manual-build/jvm-test/run.sh TestDirectoryManagement
#   manual-build/jvm-test/run.sh TestAppearance
#   manual-build/jvm-test/run.sh TestMusic
#   manual-build/jvm-test/run.sh TestLyrics
#   manual-build/jvm-test/run.sh TestLyricsP0
#   manual-build/jvm-test/run.sh TestExternalLyrics
#   manual-build/jvm-test/run.sh TestPlaybackMode
#   manual-build/jvm-test/run.sh TestHomeTitle
#   manual-build/jvm-test/run.sh TestDetailNavigation
#   manual-build/jvm-test/run.sh TestWaterfallLyrics
#   manual-build/jvm-test/run.sh TestTextSettings
#   manual-build/jvm-test/run.sh TestAuthorInfo
#   manual-build/jvm-test/run.sh TestAlbumArtwork
#   manual-build/jvm-test/run.sh all   # every test above, in order
# (Stage 4: Test51 / Test512 were deleted with the Feed code they
# covered; they must not be re-added. The probe-module test was
# deleted with its module in the next-iteration stage 2; it must
# not be re-added either.)
#
# Output: manual-build/build/jvm-test-out/<test>/compile.log and
# run.log; compiled classes under <test>/classes/. Nothing is
# written into the app source tree.
set -u
cd "$(dirname "$0")/../.." || exit 1
export PATH="/home/hatch/jdk17/bin:$PATH"

KOTLIN_HOME=/home/hatch/kotlin-2.0.20/kotlinc
ANDROID_JAR=/home/hatch/.android-sdk/platforms/android-34/android.jar
DEPS=manual-build/deps
OUT_BASE=manual-build/build/jvm-test-out
SRC=app/src/main/java/com/artlog/app/data

# The full build wipes manual-build/build/, which also removes the
# extracted coil-base / room-runtime classes.jars below. Re-extract
# them from the AARs when missing so this script stays runnable
# after a build.
for aar in coil-base-2.7.0 room-runtime-2.6.1; do
    dir="$OUT_BASE/${aar%-2.*}"
    if [ ! -f "$dir/classes.jar" ]; then
        mkdir -p "$dir"
        unzip -o -q "manual-build/deps/$aar.aar" classes.jar -d "$dir"
    fi
done

CP="$ANDROID_JAR"
CP="$CP:$OUT_BASE/coil-base/classes.jar"
CP="$CP:$OUT_BASE/room-runtime/classes.jar"
CP="$CP:$DEPS/room-common-2.6.1.jar"
CP="$CP:$DEPS/kotlinx-coroutines-core-jvm-1.7.3.jar"
CP="$CP:$DEPS/okhttp-4.12.0.jar"
CP="$CP:$DEPS/okio-jvm-3.9.0.jar"
CP="$CP:$DEPS/annotation-jvm-1.8.0.jar"
CP="$CP:$DEPS/annotations-13.0.jar"
RUN_CP="$CP:$KOTLIN_HOME/lib/kotlin-stdlib.jar"

# Sources for the gate test (TestGate): the slimmed model, the gate
# itself and the image-loading config. Stage 4 removed the Feed
# sources (InspirationSources / CircuitBreaker / RelevanceRules /
# LinkDiscovery / InspirationRepository feed part) — TestGate no
# longer compiles them.
GATE_SOURCES=(
    "$SRC/Inspiration.kt" "$SRC/ImageDisplayGate.kt" "$SRC/CoilConfig.kt"
)

run_one() {
    local test="$1"
    local out="$OUT_BASE/$(echo "$test" | tr '[:upper:]' '[:lower:]')"
    mkdir -p "$out/classes"
    local sources=()
    case "$test" in
        TestGate)
            sources=("${GATE_SOURCES[@]}" "manual-build/jvm-test/$test.kt")
            ;;
        TestWebsiteFavorites)
            sources=(
                "$SRC/WebsiteFavorite.kt" "$SRC/WebsiteFavoriteDao.kt"
                "$SRC/WebsiteFavoriteRepository.kt" "$SRC/VisualDirectory.kt"
                "$SRC/ImageDisplayGate.kt" "$SRC/Inspiration.kt"
                "manual-build/jvm-test/$test.kt"
            )
            ;;
        TestDirectoryLogic)
            sources=(
                "$SRC/WebsiteFavorite.kt" "$SRC/WebsiteFavoriteDao.kt"
                "$SRC/WebsiteFavoriteRepository.kt" "$SRC/VisualDirectory.kt"
                "$SRC/WebsiteOverride.kt" "$SRC/WebsiteOverrideDao.kt"
                "$SRC/WebsiteOverrideRepository.kt"
                "$SRC/DirectoryCategory.kt" "$SRC/DirectoryCategoryDao.kt"
                "$SRC/DirectoryCategoryRepository.kt"
                "$SRC/ImageDisplayGate.kt" "$SRC/Inspiration.kt"
                "app/src/main/java/com/artlog/app/ui/directory/DirectoryUiLogic.kt"
                "manual-build/jvm-test/$test.kt"
            )
            ;;
        TestOverrides)
            sources=(
                "$SRC/WebsiteOverride.kt" "$SRC/WebsiteOverrideDao.kt"
                "$SRC/WebsiteOverrideRepository.kt" "$SRC/VisualDirectory.kt"
                "$SRC/WebsiteFavorite.kt" "$SRC/WebsiteFavoriteDao.kt"
                "$SRC/WebsiteFavoriteRepository.kt"
                "$SRC/DirectoryCategory.kt" "$SRC/DirectoryCategoryDao.kt"
                "$SRC/DirectoryCategoryRepository.kt"
                "$SRC/ImageDisplayGate.kt" "$SRC/Inspiration.kt"
                "app/src/main/java/com/artlog/app/ui/directory/DirectoryUiLogic.kt"
                "manual-build/jvm-test/$test.kt"
            )
            ;;
        TestCategories)
            sources=(
                "$SRC/DirectoryCategory.kt" "$SRC/DirectoryCategoryDao.kt"
                "$SRC/DirectoryCategoryRepository.kt"
                "$SRC/WebsiteFavorite.kt" "$SRC/WebsiteFavoriteDao.kt"
                "$SRC/WebsiteFavoriteRepository.kt" "$SRC/VisualDirectory.kt"
                "$SRC/ImageDisplayGate.kt" "$SRC/Inspiration.kt"
                "manual-build/jvm-test/$test.kt"
            )
            ;;
        TestDirectoryManagement)
            sources=(
                "$SRC/WebsiteFavorite.kt" "$SRC/WebsiteFavoriteDao.kt"
                "$SRC/WebsiteFavoriteRepository.kt" "$SRC/VisualDirectory.kt"
                "$SRC/WebsiteOverride.kt" "$SRC/WebsiteOverrideDao.kt"
                "$SRC/WebsiteOverrideRepository.kt"
                "$SRC/DirectoryCategory.kt" "$SRC/DirectoryCategoryDao.kt"
                "$SRC/DirectoryCategoryRepository.kt"
                "$SRC/ImageDisplayGate.kt" "$SRC/Inspiration.kt"
                "app/src/main/java/com/artlog/app/ui/directory/DirectoryUiLogic.kt"
                "manual-build/jvm-test/$test.kt"
            )
            ;;
        TestAppearance)
            sources=(
                "$SRC/AppearanceSettings.kt" "$SRC/FloatingLyricsLogic.kt"
                "$SRC/LyricsParser.kt"
                "app/src/main/java/com/artlog/app/playback/PlaybackModeLogic.kt"
                "manual-build/jvm-test/$test.kt"
            )
            ;;
        TestMusic)
            sources=(
                "$SRC/MusicTrack.kt" "$SRC/MusicTrackDao.kt"
                "$SRC/MusicTrackRepository.kt" "$SRC/MusicStorage.kt"
                "$SRC/AlbumCover.kt" "$SRC/LyricsParser.kt" "$SRC/MusicImport.kt"
                "manual-build/jvm-test/$test.kt"
            )
            ;;
        TestLyrics)
            sources=(
                "$SRC/LyricsParser.kt"
                "manual-build/jvm-test/$test.kt"
            )
            ;;
        TestLyricsP0)
            sources=(
                "$SRC/LyricsParser.kt" "$SRC/FloatingLyricsLogic.kt"
                "$SRC/AppearanceSettings.kt"
                "app/src/main/java/com/artlog/app/playback/AudioFocusPolicy.kt"
                "app/src/main/java/com/artlog/app/playback/PlaybackModeLogic.kt"
                "manual-build/jvm-test/$test.kt"
            )
            ;;
        TestExternalLyrics)
            sources=(
                "$SRC/LyricsParser.kt" "$SRC/FloatingLyricsLogic.kt"
                "$SRC/AppearanceSettings.kt"
                "app/src/main/java/com/artlog/app/playback/PlaybackModeLogic.kt"
                "manual-build/jvm-test/$test.kt"
            )
            ;;
        TestPlaybackMode)
            sources=(
                "app/src/main/java/com/artlog/app/playback/PlaybackModeLogic.kt"
                "manual-build/jvm-test/$test.kt"
            )
            ;;
        TestHomeTitle)
            sources=(
                "$SRC/AppearanceSettings.kt" "$SRC/FloatingLyricsLogic.kt"
                "$SRC/LyricsParser.kt"
                "app/src/main/java/com/artlog/app/playback/PlaybackModeLogic.kt"
                "manual-build/jvm-test/$test.kt"
            )
            ;;
        TestDetailNavigation)
            sources=(
                "app/src/main/java/com/artlog/app/ui/detail/DetailNavigationLogic.kt"
                "manual-build/jvm-test/$test.kt"
            )
            ;;
        TestWaterfallLyrics)
            sources=(
                "$SRC/LyricsWaterfallLogic.kt" "$SRC/FloatingLyricsLogic.kt"
                "$SRC/LyricsParser.kt"
                "manual-build/jvm-test/$test.kt"
            )
            ;;
        TestTextSettings)
            sources=(
                "$SRC/AppearanceSettings.kt" "$SRC/FloatingLyricsLogic.kt"
                "$SRC/LyricsParser.kt"
                "app/src/main/java/com/artlog/app/playback/PlaybackModeLogic.kt"
                "manual-build/jvm-test/$test.kt"
            )
            ;;
        TestAuthorInfo)
            sources=(
                "$SRC/AuthorInfo.kt"
                "manual-build/jvm-test/$test.kt"
            )
            ;;
        TestAlbumArtwork)
            sources=(
                "$SRC/AlbumCover.kt" "$SRC/MusicStorage.kt"
                "$SRC/MusicTrack.kt" "$SRC/MusicTrackDao.kt"
                "$SRC/MusicTrackRepository.kt"
                "manual-build/jvm-test/$test.kt"
            )
            ;;
        *)
            echo "Unknown test: $test" >&2
            return 1
            ;;
    esac
    echo "== $test: kotlinc =="
    "$KOTLIN_HOME/bin/kotlinc" "${sources[@]}" -cp "$CP" -d "$out/classes" \
        > "$out/compile.log" 2>&1
    echo "KOTLINC_EXIT=$?" >> "$out/compile.log"
    if ! grep -q "KOTLINC_EXIT=0" "$out/compile.log"; then
        echo "$test: COMPILE FAILED (see $out/compile.log)" >&2
        return 1
    fi
    echo "== $test: run =="
    java -cp "$out/classes:$RUN_CP" "com.artlog.app.data.${test}Kt" \
        > "$out/run.log" 2>&1
    local rc=$?
    tail -n 1 "$out/run.log"
    return $rc
}

if [ "${1:-}" = "all" ]; then
    overall=0
    for t in TestGate TestWebsiteFavorites TestDirectoryLogic TestOverrides TestCategories TestDirectoryManagement TestAppearance TestMusic TestLyrics TestLyricsP0 TestExternalLyrics TestPlaybackMode TestHomeTitle TestDetailNavigation TestWaterfallLyrics TestTextSettings TestAuthorInfo TestAlbumArtwork; do
        run_one "$t" || overall=1
    done
    exit $overall
elif [ $# -eq 1 ]; then
    run_one "$1"
    exit $?
else
    echo "Usage: $0 <TestGate|TestWebsiteFavorites|TestDirectoryLogic|TestOverrides|TestCategories|TestDirectoryManagement|TestAppearance|TestMusic|TestLyrics|TestLyricsP0|TestExternalLyrics|TestPlaybackMode|TestWaterfallLyrics|all>" >&2
    exit 2
fi
