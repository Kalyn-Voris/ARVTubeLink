# Third-Party Notices

ARVTubeLink bundles or links against the following third-party components.
The same list is shown in-app under **设置 → 关于 → 使用项目**.

---

## Sakura2D Purism Core

- **Version**: 1.1.0 (`PSM_TRUE_VERSION` 0x01010000), bundled as `app/src/main/cpp/PurismCoreBundle.h`
- **License**: MIT License
- **Copyright**: © 2025, 2026 Sakura Motion Project — <https://sakura2d.org/>
- **Provenance (verbatim)**: The bundled file is **byte-for-byte identical** to the
  upstream v1.1.0 release `PurismCoreBundle.h`; no local modification is applied.
  The repository stores it with LF endings, and `core.autocrlf` only rewrites the
  working-tree copy to CRLF on Windows (Git normalizes it back on commit).
  - Upstream SHA-256 (LF): `56387c85c5978da63e364492bb50ecd4220f9998e27a376691d15c1de51c3913`
  - Bundled SHA-256 (LF, after `tr -d '\r'`): `56387c85c5978da63e364492bb50ecd4220f9998e27a376691d15c1de51c3913`
  - Bundled SHA-256 (working-tree CRLF): `6cc8a5e9f2e80a2274908454b6652bbb8ca82a4ff7d77b779b53e76d1ea783e4`
- **Purpose**: Native rendering core for Live2D models. Upstream declares it to
  be a clean-room reimplementation of the Live2D Cubism Core API in plain C99,
  and further states that it contains no code from Live2D Inc. ARVTubeLink
  bundles the component unmodified and makes no independent claim of its own
  about its provenance; the licence text is reproduced verbatim in
  `app/src/main/cpp/LICENSE-PURISM-CORE`.

```
MIT License

Copyright (c) 2025, 2026 Sakura Motion Project

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

"Sakura Motion", "Sakura2D" and "Purism Core" are trademarks of Sakura Motion
Project. "Live2D" and "Cubism" are trademarks of Live2D Inc.; all references
thereto are purely nominative.

---

## stb_image

- **Version**: v2.30, bundled as `app/src/main/cpp/stb_image.h`
- **License**: Public Domain (Unlicense) **or** MIT — dual-licensed by the author
- **Copyright**: © 2017 Sean Barrett
- **Purpose**: Single-header PNG/JPEG decoder used to load Live2D model textures
  in the native rendering pipeline.

```
ALTERNATIVE A - MIT License
Copyright (c) 2017 Sean Barrett

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

ALTERNATIVE B - Public Domain (www.unlicense.org)
This is free and unencumbered software released into the public domain.
For more information, please refer to <http://unlicense.org/>
```

---

## Other dependencies

AndroidX / Jetpack Compose, Kotlin Coroutines & Standard Library, OkHttp & Okio,
Gson and AndroidX WorkManager are licensed under the Apache License 2.0. The
full text is available in-app and at <http://www.apache.org/licenses/LICENSE-2.0>.

## Proprietary SDKs (not open source)

- **HUAWEI AR Engine SDK** (`com.huawei.hms:arenginesdk`) — proprietary, governed
  by the HUAWEI AR Engine Service Agreement. Not covered by any open source
  license listed here; ARVTubeLink is not affiliated with or endorsed by Huawei.
  **Binary distribution**: the AR Engine runtime libraries and the bundled
  configuration assets (`arengine_conf_new.jar`, `arengine_conf_old.jar`) are
  redistributed inside the APK as part of the SDK's normal aggregation flow. The
  SDK may contact Huawei servers at runtime to validate its licence and to
  process device information under Huawei's own privacy policy; refer to the
  HUAWEI privacy statement for details.
- **VTube Studio** — used only through its publicly documented local WebSocket
  API. No VTube Studio code is included, linked against, or redistributed.

## Model assets

**ARVTubeLink ships no Live2D models, textures, or other model assets.** Live2D
models are imported by the user from `.zip` packages and stored locally on the
device. Copyright and legality of any imported model, texture, or related asset
are the user's sole responsibility; the project does not provide, store, or
distribute model assets.

Every import requires the user to confirm, before extraction, that they hold a
valid licence for the content. The app records evidence of that confirmation
locally: the SHA-256 of the selected archive, a UNIX timestamp, and the version
of the user agreement in force at the time. This record stays on the device in
`SharedPreferences` (`import_consent`) and is also written to the runtime log.

## Trademarks

Live2D and Cubism are trademarks of Live2D Inc. This project is not affiliated
with or endorsed by Live2D Inc.
