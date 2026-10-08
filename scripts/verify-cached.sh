#!/usr/bin/env bash
# A source compilation + JVM-test fallback for environments that prohibit Gradle's sockets.
# This is NOT an APK build or a substitute for Android device testing.
set -euo pipefail
cd "$(dirname "$0")/.."
cache="${VERIFY_MAVEN_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}"
sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
out="$PWD/.build-cache/verify"
mkdir -p "$out/aars" "$out/resources" "$out/generated" "$out/classes"
artifact() {
    rg --files "$cache/$1" -g '*.jar' | rg -v '(sources|javadoc|metadata|common|all)\.jar$' | sort -V | tail -1
}
compiler="$(artifact org.jetbrains.kotlin/kotlin-compiler-embeddable)"
stdlib="$(artifact org.jetbrains.kotlin/kotlin-stdlib)"
coroutines="$(artifact org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm)"
annotations="$(artifact org.jetbrains/annotations)"
compiler_cp="$compiler:$stdlib:$coroutines:$annotations:$(artifact org.jetbrains.kotlin/kotlin-reflect)"
compose_plugin="$(artifact org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable)"
classpath="$stdlib:$coroutines:$annotations:$sdk/platforms/android-36/android.jar"
for module in org.jetbrains.kotlinx/kotlinx-serialization-core-jvm org.jetbrains.kotlinx/kotlinx-serialization-json-jvm org.jetbrains.kotlin/kotlin-test org.jetbrains.kotlin/kotlin-test-junit junit/junit org.hamcrest/hamcrest-core; do
    classpath="$classpath:$(artifact "$module")"
done
# Choose the newest locally cached version of each Android library.
declare -A aars
while IFS= read -r file; do
    relative="${file#"$cache/"}"
    group="${relative%%/*}"; relative="${relative#*/}"; module="${relative%%/*}"
    aars["$group-$module"]="$file"
done < <(rg --files "$cache" -g '*.aar' | sort -V)
for key in "${!aars[@]}"; do
    mkdir -p "$out/aars/$key"
    if ! unzip -Z1 "${aars[$key]}" | rg -q '^classes.jar$'; then continue; fi
    unzip -qo "${aars[$key]}" classes.jar -d "$out/aars/$key"
    classpath="$classpath:$out/aars/$key/classes.jar"
done
while IFS= read -r file; do classpath="$classpath:$file"; done < <(
    rg --files "$cache" -g '*.jar' | rg '/androidx\.' | rg -v '(sources|javadoc|metadata)\.jar$'
)
aapt="$sdk/build-tools/36.0.0/aapt2"
"$aapt" compile --dir androidApp/src/main/res -o "$out/resources.zip"
sed -e 's/<manifest /<manifest package="org.intentional.app" /' -e 's/${applicationId}/org.intentional.app/g' androidApp/src/main/AndroidManifest.xml > "$out/AndroidManifest.xml"
"$aapt" link -I "$sdk/platforms/android-36/android.jar" --manifest "$out/AndroidManifest.xml" --java "$out/generated" -o "$out/resources.apk" "$out/resources.zip"
javac -d "$out/classes" "$out/generated/org/intentional/app/R.java"
classpath="$classpath:$out/classes"
mapfile -t sources < <(rg --files shared/src/commonMain androidApp/src/main/kotlin shared/src/commonTest -g '*.kt')
java -Xmx2g -cp "$compiler_cp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 -classpath "$classpath" \
    -Xplugin="$compose_plugin" -d "$out/classes" "${sources[@]}"
java -cp "$out/classes:$classpath" org.junit.runner.JUnitCore org.intentional.shared.SessionEngineTest org.intentional.shared.StudyCsvTest org.intentional.shared.ForegroundTimerTest org.intentional.shared.ForegroundWindowTest org.intentional.shared.StudyUploadPolicyTest org.intentional.shared.StudySyncPolicyTest
printf '\nAll Kotlin sources compiled; session tests passed. No APK was built.\n'
