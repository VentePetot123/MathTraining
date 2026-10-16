# Math Master

Adaptive mental-math practice app for Android (Kotlin + Jetpack Compose).

- 9 categories: addition, subtraction, multiplication, division, squares/roots,
  cubes/roots, fractions/decimals/percentages, powers of 2 and 3, factorials.
- Adaptive spaced-repetition-style engine (`Engine.kt`), stats stored locally.
- Min SDK 24, target SDK 34.

## Build
Push to `main`. GitHub Actions builds the debug APK and uploads it as the
artifact `app-debug-apk` (Actions tab -> latest run -> Artifacts).
Pushing a tag such as `v1.0` also attaches the APK to a GitHub Release.
