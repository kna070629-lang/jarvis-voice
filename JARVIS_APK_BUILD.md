# Android debug APK build and installation

## Current environment result

The existing Android project is preserved under `android/`. Its configured package is `dev.jarvis.companion`, `minSdk` 26, `targetSdk` 34, `compileSdk` 35, AGP 8.11.1, and Gradle wrapper 8.13. The Gradle wrapper is executable and pins the Gradle distribution SHA-256. The existing workflow `.github/workflows/android-debug.yml` sets up Temurin JDK 17, Android platform 35/build tools 35.0.0, runs `:app:testDebugUnitTest` and `:app:assembleDebug`, then uploads the debug APK artifact.

The current Termux environment has Java 21 but no `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `sdkmanager`, `adb`, system Gradle, or `kotlinc`. I ran the wrapper once:

```text
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
FAILURE: SDK location not found. Define ANDROID_HOME or sdk.dir in android/local.properties.
BUILD FAILED in 41s
```

No `local.properties` was created. No APK was built, verified, installed, or launched in this session. The workflow has not been run against these current uncommitted changes.

## Recommended build: existing GitHub Actions workflow

GitHub Actions is the simplest reliable route here because the local Android SDK is absent and the repository already has a reproducible workflow. Do not upload credentials; this debug build uses the standard debug signing configuration. The workflow has `workflow_dispatch`, `push`, and `pull_request` triggers, read-only repository permissions, and a 14-day artifact retention period.

These local changes must first be present on a GitHub branch. They are currently uncommitted on local branch `master`; this handoff does not commit or push them. After you have reviewed and published the intended branch using your normal approval process:

1. Open the repository on GitHub and choose **Actions → Android debug APK → Run workflow**.
2. Select the branch containing the reviewed changes and run the workflow.
3. Wait for both the unit-test and assemble steps to pass.
4. Open the successful run and download artifact **jarvis-android-debug-apk**. The artifact contains `app-debug.apk`.

With GitHub CLI already authenticated, the equivalent commands are:

```bash
gh workflow run android-debug.yml --ref <reviewed-branch>
gh run list --workflow android-debug.yml --limit 5
gh run watch <run-id> --exit-status
gh run download <run-id> --name jarvis-android-debug-apk --dir ./jarvis-apk-artifact
```

Run the first command only after the intended code is present on that remote branch. No push command is provided because publication has not been authorized for this migration.

## Optional local SDK build

Use a trusted Android SDK installation (Android Studio or official command-line tools) on a Linux/macOS workstation. Install platform 35 and build-tools 35.0.0, use JDK 17, then from the repository:

```bash
cd ~/jarvis-workspace/android
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

The expected output is `android/app/build/outputs/apk/debug/app-debug.apk`. Verify it before installing:

```bash
apkanalyzer manifest application-id app/build/outputs/apk/debug/app-debug.apk
apksigner verify --verbose app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

Expected application ID: `dev.jarvis.companion`. The build task has not succeeded in this environment, so no APK checksum or artifact path is reported as a built result.

## Manual Vivo installation after a successful build

Choose one method:

- Copy the downloaded APK to the phone, open it in Android Files/Downloads, review Android's installer prompt, and tap **Install**. If Android asks, allow installation for the file-opening app only; do not enable a broader setting unless you choose to.
- With USB debugging already enabled and the phone connected, run `adb devices` and confirm the Vivo device is listed, then run `adb install -r app-debug.apk`. The `-r` updates the same package without uninstalling it.

Launch **JARVIS Companion** manually. Grant microphone access only when prompted by the microphone action. Notification and Accessibility access remain optional and must be enabled by the user from **Controls** if desired. No app installation or launch was performed automatically here.

After installing, start the backend with `./jarvis-mobile.sh`, pair it through **Controls**, then test recognition, bridge response, and audible TTS on the phone. Those Android UI changes have not been rebuilt or device-tested in this session.
