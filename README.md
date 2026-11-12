# Noise Watch Android

Android port of [noise-watch-desktop](https://github.com/learner20230724/noise-watch-desktop) (v1.0.3), built with **Kotlin + Jetpack Compose + Material 3 + Foreground Service**.

## Cloud Build via GitHub Actions (Zero Local Setup)

This project includes `.github/workflows/build-apk.yml` so you can compile the `.apk` directly in the cloud without installing Android Studio:

1. Unzip `NoiseWatchAndroid.zip`.
2. Create a new repository on GitHub (can be **Private**) and push or upload all files inside the `NoiseWatchAndroid` folder to the root of your repository (make sure the `.github/workflows/build-apk.yml` file is included).
3. Go to the **Actions** tab of your GitHub repository — the **Build Android APK** workflow will run automatically (or click **Run workflow**).
4. Once finished (~2–3 minutes), open the completed workflow run and download **`NoiseWatch-Debug-APK`** under the **Artifacts** section to get `app-debug.apk`.

## Features

- **Input Device Selection & Refresh**: Enumerate available microphones (`AudioManager.GET_DEVICES_INPUTS`: built-in, wired headset, Bluetooth, USB) and refresh on demand.
- **Multi-Band FFT Analysis (dBFS)**: 2048-point Cooley-Tukey FFT with Hann windowing across **Low (20–250 Hz)**, **Mid (250–2000 Hz)**, and **High (2000–8000 Hz)** bands; multi-select uses the maximum dBFS among selected bands.
- **Threshold Rule Engine**: Configurable **Threshold (dBFS)**, **Impact Count**, **Time Window (s)**, and **Cooldown (s)**.
- **Background Foreground Service + WakeLock**: Continues monitoring with a persistent notification and `PARTIAL_WAKE_LOCK` even when the screen is off.
- **Local Alert Playback & Stop Control**: Select any local audio file via Android Storage Access Framework (SAF) or fall back to the built-in synthesized chime, with one-tap **Stop Alert Sound**.
- **Settings Persistence**: Automatically saves and restores all settings via `SharedPreferences`.
- **Local Data Output**:
  - Real-time JSON snapshot: `<app-external-files>/events/latest-events.json`
  - Periodic CSV export (every 5 minutes + manual export/share): `<app-external-files>/exports/latest-events.csv`
- **Bilingual UI**: Switch between **中文** and **English** at any time.
- **100% Local**: No network permissions (`INTERNET` is not requested).
