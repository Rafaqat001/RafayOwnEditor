# Test report - honest status

**The APK has NOT been built or run yet.** The code was written without an Android SDK or internet in the build environment, so nothing here was compiled or executed on a phone. Expect to fix a few compile errors on first build.

| # | Feature | Status |
|---|---------|--------|
| 1 | Opening clip detection | Implemented (scene-cut heuristic). NOT tested on real videos. Fails if the opening has hard cuts inside it, or no clean cut. Then it logs a warning, assumes 5 s, and you set "Opening length override". |
| 2 | Keep exactly last 1 s | Graph logic covered by a unit test (FilterGraphBuilderTest, not yet run). Exact frame accuracy depends on ffmpeg trim; verify on device. |
| 3 | Contrast +10 | Implemented via lutyuv (factor 1.10). Unit-tested string only. Check look on device. |
| 4 | Saturation -5 | Implemented via lutyuv chroma (factor 0.95). Same as above. |
| 5 | Upper player detection | Implemented with bundled face model. NOT tested. Player footage with no visible face (hands, back of head) is missed -> use manual intervals or ALL. |
| 6 | Selective mirroring | Only the UPPER section is flipped, only during detected intervals. LIMITATION: stickers/text/captions that sit on top of the player footage at that moment ARE flipped with it (a single-pass filter cannot separate overlays that are burned into the video). Gameplay is never flipped. |
| 7 | 1.04x speed | Implemented (setpts/1.04 + atempo 1.04, original fps kept). Unit-tested string only. |
| 8 | Audio sync | Designed with trim/atrim/concat on the same timestamps. Must be verified on real videos, especially variable-frame-rate phone clips. |
| 9 | MP4 export | Implemented (libx264 CRF18, fallback hardware H.264). Needs on-device test. |
| 10 | Offline | No INTERNET permission, no cloud calls, model bundled. Library download needed only at build time. |

Other limits: OpenCV and TensorFlow Lite were not used; plain image maths + ML Kit's bundled (offline) face detector do the job with less RAM. Processing is a single FFmpeg pass; keep the screen on and the app in the foreground. Pitch shift does not guarantee any copyright outcome.

## Manual test checklist
Run with 5 clips (opening of 3, 4, 5, 6, 7 s): read the log for "Opening ends at", confirm only 1 s of the opening remains; compare colors; confirm sticker-only moments are not flipped; play full result for audio sync; turn on airplane mode and repeat.
