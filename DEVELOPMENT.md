# Development notes

## Edge Light quick checks

The Edge Light Android app lives in `Android/src/lightapp`.

The Android Gradle Plugin in this repo requires a Java 11 or newer runtime and a configured
Android SDK. A plain shell using Java 8 will fail during Gradle configuration. Set either
`ANDROID_HOME` or `sdk.dir` in `Android/src/local.properties` before running Gradle.

Example for this workstation:

```sh
cd Android/src
JAVA_HOME=/home/terminus-protocol/android-studio/jbr \
ANDROID_HOME=/home/terminus-protocol/Android/Sdk \
PATH=/home/terminus-protocol/android-studio/jbr/bin:$PATH \
./gradlew :lightapp:testDebugUnitTest :lightapp:assembleDebug :lightapp:lintDebug
```

For a portable local setup, create `Android/src/local.properties` with:

```properties
sdk.dir=/path/to/Android/Sdk
```

## Build app locally

To successfully build and run the application through Android Studio, you need to configure it with your own HuggingFace Developer Application ([official doc](https://huggingface.co/docs/hub/oauth#creating-an-oauth-app)). This is required for the model download functionality to work correctly.

After you've created a developer application:

1. In [`ProjectConfig.kt`](https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/common/ProjectConfig.kt), replace the placeholders for `clientId` and `redirectUri` with the values from your HuggingFace developer application.

1. In [`app/build.gradle.kts`](https://github.com/google-ai-edge/gallery/blob/c1b50e160a66d5ea2ec2d8d8e63088b3cc0761bc/Android/src/app/build.gradle.kts#L41-L44), modify the `manifestPlaceholders["appAuthRedirectScheme"]` value to match the redirect URL you configured in your HuggingFace developer application.
