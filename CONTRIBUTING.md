# Contributing to Artlog

Thank you for considering a contribution. Artlog is a personal project by
飞舞网络卡死了, shared for free under the Artlog Non-Commercial License
(see [LICENSE](LICENSE)).

## How to contribute

1. **Issues**: report bugs or suggest features through GitHub Issues.
   Please include the app version, your device model and Android version,
   and steps to reproduce the problem. Screenshots help.
2. **Pull Requests**: fork the repository, create a branch, and open a
   pull request against `main`. Keep each pull request focused on one
   change, and describe what it does and why.
3. **Discuss first for large changes**: if you plan a large feature or a
   redesign, please open an Issue to discuss it before writing code.

## Rules

- All contributions must comply with the Artlog Non-Commercial License.
  By submitting a pull request, you agree that your contribution is
  licensed under the same license.
- Do not introduce network requests, accounts, analytics, or tracking.
  Artlog is local-first and fully offline by design.
- Do not add fake data or placeholder content. Statistics and displayed
  information must come from the user's real records.
- Do not add copyrighted third-party resources (fonts, images, music)
  without a clear license that permits redistribution.
- Keep the dark, minimal visual style. Do not change the app's core
  colours or layout language without discussion.
- Add or update tests for logic changes where the project already has
  JVM tests (`manual-build/jvm-test/`).

## Building

```bash
./gradlew assembleDebug
```

Requires JDK 17 and Android SDK (platform android-34, build-tools
34.0.0). See [README.md](README.md) for details. An alternative manual
build toolchain is documented in `manual-build/`.

## Decisions

The author retains the final say on merging code, publishing releases,
and granting commercial licenses.
