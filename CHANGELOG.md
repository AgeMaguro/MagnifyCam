# MagnifyCam changelog

Each build is numbered by the GitHub run (the version shown on the phone is 1.0.<run>).

## Next build
- Camera: under the lens buttons the text now shows the sensor's real pixel count next to what the phone lets this app use (for example "Sensor 200 MP · this app can take up to 12 MP"). Many phones, Samsung Ultras included, only give other apps a binned ~12 MP picture from a 200 MP sensor. Also checks the phone's high-resolution size lists when working out the largest photo.
- Tip: to use the full 200 MP, shoot in the phone's own camera app and open the picture with "Gallery" here.

## 1.0.5
- Camera: asks the phone for its largest photo size ("Detail: Max" button; "Standard" = the phone's normal size). Lens buttons (0.6x / 1x / 3x / 5x, whatever the phone lists) so you can use the real telephoto lenses instead of digital zoom; shows the biggest photo each lens reports. Zoom number is the total magnification.
- Editor: shows the original photo size. Photos bigger than 4096 px are no longer shrunk: the part on screen is read from the real pixels for the preview and the saved file. "Enlarge 1x" now means real photo pixels.
- In-app updater: checks the public release when the app opens and offers to install a newer build (needs the public repo AgeMaguro/MagnifyCam; see the handoff note).

## 1.0.2 (first working build)
- Build fix only: pinned the Kotlin runtime version (build 1 failed on a duplicate-class clash).

## First version (build 1, never published)
- Camera screen: live preview, pinch or slider zoom (uses the phone's real zoom lens), tap to focus, torch button, shutter.
- Gallery button: open any picture already on the phone.
- Editor: pinch / drag / double-tap to magnify the picture; Sharpen slider (0-100) with a live preview of the part you are looking at; hold "original" to compare.
- Enlarge 1x-4x: Save writes what is on screen, enlarged with a smooth bicubic resize, then sharpened (brightness only, so no colour fringes) as a JPEG in Pictures/MagnifyCam. Saved size is capped at about 12.5 megapixels to protect phone memory.
