# MagnifyCam

Android camera app: take a picture (or open one), magnify the part you care about, sharpen it, save it.
No ads, nothing leaves the phone. Permissions: Camera, plus Internet and Install-packages only for the in-app updater (it downloads new versions from the public GitHub release).

- Source: `app/src/main/java/com/agemaguro/magnifycam/`
  - `CameraActivity` - CameraX preview, zoom, focus, torch, shutter, gallery picker
  - `EditorActivity` - sharpen slider, enlarge chips, save to Pictures/MagnifyCam
  - `ZoomImageView` - pinch/drag/double-tap viewer with live sharpened preview
  - `Updater` - on app open, reads `version.txt` from the public release `AgeMaguro/MagnifyCam` (`magnifycam-latest`); if newer, asks to update, downloads `MagnifyCam.apk`, opens the installer
  - `ImageOps` - pure Java pixel maths (bicubic resize + unsharp mask); no Android classes, so it can be tested on a PC
- Build: `.github/workflows/apk.yml` builds the APK on every push that touches the app and publishes it as the GitHub release `magnifycam-latest` (files `MagnifyCam.apk` and `version.txt`). Old versions are archived in `past-versions/`. This repo is PUBLIC on purpose so the in-app updater can download releases without a login. Nothing private goes here.
- Install link: https://github.com/AgeMaguro/MagnifyCam/releases/download/magnifycam-latest/MagnifyCam.apk
- Same shared signing key as the Sudoku app and the earlier Claude-HQ builds, so new builds install over old ones. Version numbers here are the run number + 100.
- Handoff notes live in the private Claude-HQ repo (`Handoffs/2026-11-08_MagnifyCam-app.md`).

## Testing the image maths on a PC (no Android needed)
```
javac -d out app/src/main/java/com/agemaguro/magnifycam/ImageOps.java tests/OpsTest.java
java -cp out OpsTest
```
Prints PASS/FAIL lines and "ALL PASS". The camera and screens can only be checked by the GitHub build and on the phone.
