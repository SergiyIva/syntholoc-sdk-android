# Syntholoc OTA Android SDK

Public Android SDK for OTA translations. No access to the private Syntholoc project,
GitHub login, or registry token is required. MIT license. Android API 24+, Java 17,
Kotlin 2.2+. Optional Compose adapter uses AndroidX Compose 1.6.8.

Add to your application's `settings.gradle.kts` repositories:

```kotlin
maven {
    url = uri("https://raw.githubusercontent.com/SergiyIva/syntholoc-sdk-android/maven")
    content { includeGroup("com.syntholoc") }
}
```

Keep `google()` and `mavenCentral()` for Android/Kotlin dependencies. Then add:

```kotlin
implementation("com.syntholoc:ota:0.1.0")
// Optional:
implementation("com.syntholoc:ota-compose:0.1.0")
```

[Mobile integration guide (Russian)](INTEGRATION_RU.md) includes client setup,
Android resources, Compose, authentication, activation and QA.
[API reference and limitations](API.md#android).

SDK releases are tagged in this repository. Prebuilt AAR, source JAR, POM, Gradle
module metadata and checksums are stored in the public `maven` branch; old versions
are retained. Pin an explicit version in your application. SDK publication does not
publish translations: configure a distribution and channel separately in Syntholoc.

To build this standalone source checkout, install JDK 17 / Android SDK 35 and run:

```sh
./gradlew testReleaseUnitTest assembleRelease lintRelease
./gradlew connectedDebugAndroidTest # with an emulator/device attached
```

The SDK uses explicit providers and bundled fallback; it does not replace Android's
global resource loader. New translations are activated explicitly after downloading.
