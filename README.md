# JumpRope AI (Android)

Aplikasi Android native untuk menghitung lompatan skiping (**jump rope counter**) secara **live** menggunakan kamera dan **AI on-device (ML Kit Pose Detection)** tanpa koneksi internet.

## Fitur Utama

- **Live Camera + AI Jump Counter**: CameraX + ML Kit Pose Detection (`STREAM_MODE`) dengan algoritma deteksi lompatan berbasis posisi bahu & pinggul yang dinormalisasi terhadap tinggi torso (tahan terhadap variasi jarak kamera dan jitter).
- **Real-time Skeleton Overlay**: Visualisasi garis rangka tubuh (*pose skeleton*) secara live di atas preview kamera (bisa di-toggle on/off), berubah warna menjadi neon lime saat terdeteksi melompat (*airborne*).
- **Kamera Depan Default + Switch Kamera**: Otomatis terbuka di kamera depan (*front camera*) dengan tombol cepat untuk berpindah ke kamera belakang.
- **Mode Latihan Lengkap**:
  - **Bebas (Free Jump)**
  - **Target Lompatan** (50 / 100 / 200 / 300 / 500 lompatan + progress bar & auto-selesai)
  - **Target Waktu** (1 / 3 / 5 / 10 menit + countdown timer)
  - **HIIT Interval** (preset 20s/10s×4, 30s/15s×5, 45s/15s×6, 60s/20s×5 dengan pergantian otomatis fase **LOMPAT** ↔ **ISTIRAHAT**)
- **Audio & Voice Coach**:
  - Beep halus pada setiap lompatan & countdown 3-2-1
  - Suara pelatih (Text-to-Speech Bahasa Indonesia) saat mulai, milestone setiap 25 lompatan, pergantian fase HIIT, dan saat target tercapai (dengan tombol mute/unmute)
- **Live Metrics HUD**: Total Lompatan, **Durasi**, **Pace (RPM)**, **Peak RPM**, **Estimasi Kalori (kkal)** berbasis MET, **Streak** aktif & **Best Streak**.
- **Riwayat Latihan & Ringkasan Sesi**: Dialog ringkasan saat selesai latihan serta penyimpanan lokal permanen untuk riwayat sesi & statistik kumulatif (Total Lompatan, Total Sesi, Total Kalori, Best Streak sepanjang masa).

## Tech Stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- **CameraX** (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`)
- **Google ML Kit Pose Detection** (`com.google.mlkit:pose-detection`)
- **Android TextToSpeech & ToneGenerator**
- **Coroutines & StateFlow** (`AndroidViewModel`)

## Cara Build & Jalankan

1. Buka folder `JumpRopeAI` di **Android Studio** (atau gunakan CLI).
2. Jalankan unit test & build debug APK:
   ```bash
   ./gradlew testDebugUnitTest assembleDebug
   ```
3. Output APK:
   `app/build/outputs/apk/debug/app-debug.apk`
