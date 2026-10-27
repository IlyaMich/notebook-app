# Notebook App — FND-08A Android Device Matrix (Draft V0.1)

**Date:** 2026-10-27  
**Status:** In progress — device variants and installed Android versions not yet verified  
**Repository destination:** `docs/development/notebook_android_device_matrix_fnd08a_v0_1.md`  
**Depends on:** Android-first execution plan and the V1 PRD; FND-04 emulator checks verified

## 1. Confirmed environment

- Windows 11 Home, 64-bit; AMD Ryzen 9 9955HX; 31.2 GB RAM; NVIDIA GeForce RTX 5070 Ti Laptop GPU; >500 GB disk free.
- Android Studio Rabbit 1 (2026.2.1), installed and launching.
- Android SDK in `C:\Users\ilmih\AppData\Local\Android\Sdk`.
- Installed SDK Platform: Android 17 / API 37; Build-Tools 37, Platform-Tools 37.0.1, Command-line Tools 23; Emulator 37.2.12.0.
- Windows Hypervisor Platform usable (`emulator -accel-check`: `accel: 0`, `WHPX(10.0.26200) is installed and usable`).
- Running emulator appeared as `emulator-5554`, state `device`, `sys.boot_completed=1`, `ro.build.version.sdk=37`.
- Device Manager hardware profile of emulator was not independently captured; confirm tablet profile before treating screen size as verified.

## 2. Physical device inventory

| ID | User-reported device | Model identifier | Installed Android/API | Stylus | Connection status | Test priorities |
|---|---|---|---|---|---|---|
| AVD-01 | Android tablet emulator | `sdk_gphone64_x86_64` image; hardware profile to confirm | Android 17 / API 37 | Emulated input only | Connected, booted | Layout, navigation, installation, smoke tests |
| AND-01 | "Samsung galaxy s7 lite" | **Unconfirmed**: Samsung officially offered Galaxy Tab S7 FE; verify model, don't silently rename | Unknown | User reports yes | Not tested over ADB | Large-screen layout, S Pen, pressure and palm behaviors |
| AND-02 | "Samsung galaxy s6 lite" | **Unconfirmed revision**: 2020 / 2022 / 2024 variants exist | Unknown | User reports yes | Not tested over ADB | Stylus input, older device performance, memory behavior |
| AND-03 | Android phone | Not yet available/identified | Unknown | TBD; finger draw is required in V1 | Not applicable | Compact layout and finger-writing mode |

## 3. Android API baseline — candidate, not final approval

- `minSdk = 26` (Android 8.0): **provisional implementation baseline**, pending measured test device versions, required dependency manifests and market coverage review. All identified plausible Samsung tablet families started on newer versions than API 26. Jetpack Compose supports API 21+.
- `compileSdk = 37`: SDK Platform API 37 installed; for AGP builds verify AGP compatibility, preferably 9.1.1 or later.
- `targetSdk >= 36`: Google Play's August 31, 2026 requirement for new ordinary Android app submissions. Choose actual target after generated project inspection and review behavior changes; revisit before store submission.
- The version of Android on an emulator is **not** the minimum Android version the app can support.
- Production ink engine selection remains deferred to M2. Evaluate library support for the selected minSdk during that milestone.

## 4. Verify models and versions without guessing

On each physical Samsung tablet, go to **Settings → About tablet** and record **Model number**. Then use **Software information → Android version** and **One UI version**. Model numbers generally start `SM-`.

Once USB debugging is enabled with consent and ADB is authorized, capture per-device output using its serial (`adb -s SERIAL shell getprop ro.product.model`, `adb -s SERIAL shell getprop ro.product.device`, `adb -s SERIAL shell getprop ro.build.version.release`, `adb -s SERIAL shell getprop ro.build.version.sdk`). Keep serials out of committed reports.

## 5. Planned validation coverage

1. AVD: debug build installs, opens, navigates Library → Notebook → Note Editor → Back and rotates correctly.
2. Both tablets: debug build installs and launches; test portrait/landscape, split-screen and responsive navigation.
3. M2 only: test raw pointer tool types, pressure, tilt where available, palm cancellation, handwriting latency, and S Pen behavior. Do not assume emulator simulates these.
4. Android phone: obtain one real device later, or use a phone AVD for initial layouts; verify finger-drawing mode on real hardware before V1.
5. Add long-document, storage durability and backup/restore device tests at subsequent milestones.

## 6. Open decisions / exit evidence

- [ ] AND-01 exact marketed model and model number confirmed.
- [ ] AND-02 model number/revision confirmed.
- [ ] Both tablets' installed Android versions and API levels recorded.
- [ ] At least one physical Android device authorized and visible in ADB (FND-04 physical-debugging portion).
- [ ] Minimum SDK and target SDK reviewed against actual devices, required libraries and release requirements.
- [ ] Phone-sized configuration included in test plan.
- [ ] Android build runs on AVD and one physical tablet after FND-06.

## References

- Android Studio Compose project creation: https://developer.android.com/studio/projects/create-project
- Android Studio/AGP and API support: https://developer.android.com/studio/releases
- Play target SDK policy: https://developer.android.com/google/play/requirements/target-sdk
- Samsung Tab S7 FE: https://www.samsungmobilepress.com/articles/introducing-the-newest-members-of-the-samsung-galaxy-tab-portfolio-galaxy-tab-s7-fe-and-galaxy-tab-a7-lite
- Samsung Tab S6 Lite (2020): https://www.samsungmobilepress.com/articles/create-learn-and-relax-with-the-stylish-galaxy-tab-s6-lite
- Samsung Tab S6 Lite (2024): https://news.samsung.com/global/samsung-galaxy-tab-s6-lite-2024-style-and-function-in-a-compact-package
