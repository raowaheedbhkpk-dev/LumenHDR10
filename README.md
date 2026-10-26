# Lumen HDR10

Android app that converts normal (SDR) video into real HDR10:
10-bit HEVC (Main10 HDR10 profile), BT.2020 color, SMPTE ST 2084 (PQ) brightness,
with HDR static metadata (mastering display, MaxCLL, MaxFALL). Output is saved to
`Movies/Lumen HDR` in your gallery.

Needs Android 13+ and a phone with an HDR10 video encoder (Pixel 7 and newer,
Galaxy S22 and newer, and most recent flagships).

## Get the APK with GitHub (no computer software needed)

1. Create a free account at github.com and make a new **empty** repository.
2. Upload every file and folder in this project (including the `.github` folder).
3. Open the **Actions** tab. The "Build APK" job starts by itself; wait about 5 minutes for the green check.
4. Open the finished run and download **LumenHDR10-apk** under Artifacts. Unzip it to get `LumenHDR10.apk`.
5. Open the APK on your phone and allow "install unknown apps" when asked.

## Or build with Android Studio

Open this folder in Android Studio, let it sync, then choose Build > Build APK(s).

## How the conversion works

Each frame is decoded, then a GPU shader:
- converts it to linear light and applies exposure, shadow lift, saturation and sharpening
- maps SDR white to 203 nits (ITU-R BT.2408) and expands highlights up to your chosen peak (400–1600 nits)
- converts BT.709 color to BT.2020 and encodes with the PQ curve

Frames are drawn to a 10-bit GPU surface tagged BT.2020 PQ, which feeds the
hardware HEVC encoder directly. Audio (AAC) is copied without re-encoding.

## Limits

- Dolby Vision is not supported: it requires Dolby's licensed encoder.
- Videos that are already HDR are rejected.
- Detail that was clipped in the original (pure white skies) can't be recovered; highlights are expanded, not invented.
- Conversion runs while the app is open; keep the screen on.
