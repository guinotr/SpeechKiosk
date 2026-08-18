# SpeechKiosk

[Version française](README.md)

SpeechKiosk turns an Android tablet into a dedicated live-caption display for deaf and hard-of-hearing users. It targets Android 9 (API 28) and newer.

## Public cloud build

The default public build contains no API key and no offline model. Build it with:

```powershell
.\gradlew.bat testCloudDebugUnitTest assembleCloudDebug
```

Install the APK, long-press the status line, enter the administrator PIN (`2468` by default), then enter your own OpenAI API key and select the transcription language.

## Optional offline build

The French sherpa-onnx model is deliberately excluded from Git because one model file exceeds GitHub's regular file-size limit. On Windows:

```powershell
.\download-offline-model.ps1
.\gradlew.bat testHybridDebugUnitTest assembleHybridDebug
```

Downloaded model files remain ignored by Git. The app disables local mode when they are unavailable.

## Dedicated-device kiosk

Provisioning Device Owner requires a freshly reset tablet with no Google account configured:

```powershell
adb install -r app-cloud-debug.apk
adb shell dpm set-device-owner fr.mamieturbo/.kiosk.MamieTurboDeviceAdminReceiver
adb shell pm grant fr.mamieturbo android.permission.RECORD_AUDIO
adb shell am start -n fr.mamieturbo/.ui.MainActivity
```

The historical package name is retained so existing MamieTurbo tablets can be upgraded without provisioning Device Owner again.

## Privacy and security

Audio and transcripts are not stored by the app. Cloud mode sends speech segments to OpenAI for transcription. The API key is stored in the app's private local preferences and is never compiled into the APK. For deployment to devices you do not control, use a backend and short-lived credentials instead of a permanent API key.

Licensed under the MIT License. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for optional offline dependencies.
