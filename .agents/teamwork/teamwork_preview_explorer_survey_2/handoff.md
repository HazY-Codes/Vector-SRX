# Survey 2 Handoff — R3 CI Failure #45375977969, Build/Workflow & Repo Hygiene Audit

**Handoff Type:** Hard  
**Scope:** Read-only audit of Git/repo hygiene, GitHub Actions `Core` failure (`#45375977969` and subsequent runs), Gradle/CMake/signing/ZIP packaging, and branding/licensing/release-channel metadata for `HazY-Codes/Vector-SRX`.

**Core Summary:** GitHub Actions run `#45375977969` (and all subsequent `Core` runs on `HazY-Codes/Vector-SRX`) failed during `./gradlew zipAll` because `ubuntu-latest` (`ubuntu-26.04` image `20261116.202.1`) + `rm -rf $ANDROID_HOME/cmake` causes system CMake to synthesize C++23 `import std;` module scanning (`@cmake_cxx_std@synth_0.dir`) for `external/fmt` (`FMT_MODULE=ON`), invoking NDK r30 `clang-scan-deps` against host `/usr/include/c++/15/bits/std.cc` and failing with `fatal error: 'bits/stdc++.h' file not found`. Additionally, `.agents/` is not yet excluded in `.git/info/exclude`, `zygisk/update.json` will need its `versionCode` synchronized to the final `v2.2.1` commit count before tagging, and `seanmiddleditch/gha-setup-ninja@v6` emits a Node 20 deprecation warning.

---

## 1. Observation

### 1.1 Git Status, Branches, Remotes, Ignores & Submodules
- **`git status -u`**:
  - On branch `master`, up to date with `origin/master` (`e7a31c4170e2bf5780bc39d65e5669ca222851a3`).
  - Zero modified tracked files; `git diff --check` exited `0` with empty output.
  - 14 untracked files under `.agents/teamwork/` (e.g., `.agents/teamwork/ORIGINAL_REQUEST.md`, `.agents/teamwork/sentinel_1/BRIEFING.md`, `.agents/teamwork/teamwork_preview_explorer_survey_{1,2,3}/*`, `.agents/teamwork/teamwork_preview_orchestrator_r1/*`).
- **`git remote -v`**:
  - `origin` -> `https://github.com/HazY-Codes/Vector-SRX.git` (fetch/push)
  - `upstream` -> `https://github.com/JingMatrix/Vector.git` (fetch/push)
  - `vector-sr` -> `https://github.com/byemaxx/Vector-SR.git` (fetch/push)
- **`.gitignore` (lines 1–14)**: Ignores `.project`, `.settings`, `.cache`, `*.iml`, `.gradle`, `.kotlin`, `/local.properties`, `/.idea`, `.DS_Store`, `build`, `/captures`, `bin/`. Does **not** exclude `.agents/`.
- **`.git/info/exclude` (lines 1–7)**: Contains only default template comments. Does **not** exclude `.agents/`.
- **`.gitmodules` (lines 1–28)**: Declares 9 submodules:
  1. `external/lsplant` (`7a768e31ffb2852797631b5140b54f9702a78743`, `JingMatrix/LSPlant.git`)
  2. `external/dobby` (`05a09ac6807a6bb1726350e40ea4b127c1c79809`, `JingMatrix/Dobby.git`)
  3. `external/fmt` (`5da4e9a3fb15626ba92969199061e0169ebc8a00`, `fmtlib/fmt.git`, `{fmt}` `12.2.1`)
  4. `external/xz-embedded` (`ae63ae3a36ed01724674e8f3d750dc47bf125410`, `tukaani-project/xz-embedded.git`)
  5. `external/lsplt` (`a89012ef2d1dbc62b6fc205cb941f00a9c9a3d18`, `JingMatrix/LSPlt`)
  6. `services/libxposed` (`3318940876192e29cf6ab07637e899e22a87ebf0`, `JingMatrix/libxposed-service.git`)
  7. `xposed/libxposed` (`39cac0845771547c9c67a3e3ce255af110a54a0e`, `JingMatrix/libxposed-api.git`)
  8. `external/apache/commons-lang` (`7b6048b7a9d1339a12d353cb85b2222df7bf5668`, `apache/commons-lang.git`)
  9. `external/axml/manifest-editor` (`c238e28f3a1a0275c38945aa4f6b065d3f5889`, `JingMatrix/ManifestEditor.git`)
  - Locally, `git submodule status` shows `-` prefix on all 9 (uninitialized in local workspace).
- **`git rev-list --count refs/remotes/origin/master`**: `3131`.
- **Top tags (`git tag --list --sort=-v:refname | head -n 10`)**: `v2.2`, `v2.1`, `v2.0`, `v1.11.0`, `v1.10.2`, `v1.10.1`, `v1.10.0`, `v1.9.2`, `canary-3119`, `canary-3118`.

### 1.2 GitHub Actions Workflows & CI Run `#45375977969` Audit
- **Workflow files**:
  - `.github/workflows/core.yml` (228 lines)
  - `.github/workflows/crowdin.yml` (40 lines)
  - `.github/dependabot.yml` (27 lines — daily `gradle`, monthly `github-actions` and `gitsubmodule`)
- **Recent runs on `HazY-Codes/Vector-SRX`** (`gh run list --repo HazY-Codes/Vector-SRX --limit 15`):
  - `45375977969` (`Core`, `master`, `workflow_dispatch`): `failure` (3m11s)
  - `45376024204` (`Core`, `master`, `push`, commit `349a92b0`): `failure` (3m16s)
  - `45376024652` (`Crowdin Action`, `master`, `push`): `skipped` (`vars.ENABLE_CROWDIN != 'true'`)
  - `45377498091`, `45377515331`, `45377522798` (`Dependabot Updates`): `success` (opened PRs `#1`, `#2`, `#3`)
  - `45377654747` (`Core`, PR `#1` submodule bump): `failure`
  - `45377667557` (`Core`, PR `#2` actions bump): `failure`
  - `45378112571` (`Core`, PR `#3` maven bump): `failure`
- **Run `#45375977969` step outcomes**:
  - Passed: `Checkout` (`actions/checkout@v7`, `submodules: recursive`), `Check translations`, `Write key`, `Setup Java` (Temurin 21), `Setup Gradle` (`gradle/actions/setup-gradle@v6`), `Configure Gradle properties`, `Setup ninja` (`1.13.2`), `Setup ccache`, `Setup Android SDK`, `Remove Android's cmake` (`rm -rf $ANDROID_HOME/cmake`).
  - Failed: `Build with Gradle` (`./gradlew zipAll`).
- **Runner environment comparison (`45375977969` vs passing upstream `JingMatrix/Vector` `43788184282`)**:
  - Passing upstream run `43788184282` (2026-11-16): `Image: ubuntu-26.04`, `Version: 20261111.82.6`, NDK `30.0.16248370` (Clang 21.0.0), Ninja `1.13.2`, `{fmt}` `12.2.1` (`5da4e9a3...`). `:daemon:buildCMakeDebug[arm64-v8a]` had `127` build steps and succeeded.
  - Failing run `45375977969` (2026-11-24): `Image: ubuntu-26.04`, `Version: 20261116.202.1`, same NDK `30.0.16248370`, same Ninja `1.13.2`, same `{fmt}` `12.2.1`. `:daemon:buildCMakeDebug[arm64-v8a]` has `130` build steps (`+3` synthetic std module steps).
- **Verbatim error from `gh run view 45375977969 --repo HazY-Codes/Vector-SRX --log-failed`**:
  ```
  > Task :daemon:buildCMakeDebug[arm64-v8a] FAILED
  C/C++: ninja: Entering directory `/home/runner/work/Vector-SRX/Vector-SRX/daemon/build/Debug/2cew6b4z/arm64-v8a'
  C/C++: [1/130] Scanning /usr/include/c++/15/bits/std.compat.cc for CXX dependencies
  ...
  C/C++: [4/130] Scanning /usr/include/c++/15/bits/std.cc for CXX dependencies
  C/C++: FAILED: [code=1] external/fmt/CMakeFiles/@cmake_cxx_std@synth_0.dir/70b2301d7307.bmi.ddi 
  C/C++: "/usr/local/lib/android/sdk/ndk/30.0.16248370/toolchains/llvm/prebuilt/linux-x86_64/bin/clang-scan-deps" -format=p1689 -- /usr/local/lib/android/sdk/ndk/30.0.16248370/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++ --target=aarch64-none-linux-android27 --sysroot=/usr/local/lib/android/sdk/ndk/30.0.16248370/toolchains/llvm/prebuilt/linux-x86_64/sysroot   -g -DANDROID -fdata-sections -ffunction-sections -funwind-tables -fstack-protector-strong -no-canonical-prefixes -D_FORTIFY_SOURCE=2 -Wformat -Werror=format-security  -DVERSION_CODE=3131 -DVERSION_NAME='"2.2"' -DPHMAP_HAVE_SSE2=0 -DPHMAP_HAVE_SSSE3=0 -fno-limit-debug-info  -std=gnu++23 -Wno-reserved-module-identifier -x c++ -c -o external/fmt/CMakeFiles/@cmake_cxx_std@synth_0.dir/70b2301d7307.bmi -resource-dir "/usr/local/lib/android/sdk/ndk/30.0.16248370/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/21" -MT external/fmt/CMakeFiles/@cmake_cxx_std@synth_0.dir/70b2301d7307.bmi.ddi -MD -MF external/fmt/CMakeFiles/@cmake_cxx_std@synth_0.dir/70b2301d7307.bmi.ddi.d /usr/include/c++/15/bits/std.cc > external/fmt/CMakeFiles/@cmake_cxx_std@synth_0.dir/70b2301d7307.bmi.ddi.tmp && mv external/fmt/CMakeFiles/@cmake_cxx_std@synth_0.dir/70b2301d7307.bmi.ddi.tmp external/fmt/CMakeFiles/@cmake_cxx_std@synth_0.dir/70b2301d7307.bmi.ddi
  C/C++: Error while scanning dependencies for /usr/include/c++/15/bits/std.cc:
  C/C++: /usr/include/c++/15/bits/std.cc:30:10: fatal error: 'bits/stdc++.h' file not found
  ```
- **Same error confirmed in `:zygisk:buildCMakeDebug[arm64-v8a]` in run `#45378112571`**:
  ```
  FAILED: [code=1] native/external/fmt/CMakeFiles/@cmake_cxx_std@synth_0.dir/70b2301d7307.bmi.ddi
  ...
  /usr/include/c++/15/bits/std.cc:30:10: fatal error: 'bits/stdc++.h' file not found
  ```
- **Note on `:dex2oat`**: `:dex2oat:buildCMakeDebug[arm64-v8a]` **succeeded** in `45375977969` because `dex2oat/src/main/cpp/CMakeLists.txt` does not set `CMAKE_CXX_STANDARD 23` and does not include `external/fmt`.
- **Inspecting `external/fmt` (`5da4e9a3...`) `CMakeLists.txt`**:
  - Lines 58–73 & 81, 93: When `CMAKE_VERSION >= 3.28`, `CMAKE_CXX_STANDARD >= 20`, `CMAKE_GENERATOR == "Ninja"`, Ninja `>= 1.11`, and Clang `>= 16` with `clang-scan-deps`, `{fmt}` sets `FMT_USE_CMAKE_MODULES TRUE` and defaults `option(FMT_MODULE "Build a module library." ON)`.
  - Lines 448–452: Registers `fmt-module` with `FILE_SET fmt TYPE CXX_MODULES FILES src/fmt.cc` (an unconditional target built by default because `fmt` is not added with `EXCLUDE_FROM_ALL`).
  - Meanwhile, Vector never links `fmt` or `fmt-module`: `native/CMakeLists.txt:44` links `fmt-header-only`, and `external/CMakeLists.txt:31` configures `fmt-header-only` (`target_compile_definitions(fmt-header-only INTERFACE ...)`), while `external/CMakeLists.txt:19-24` omits `FMT_MODULE OFF`.
- **Other warnings / gaps in CI (`45375977969`)**:
  - **Node 20 deprecation annotation**: `Node.js 20 is deprecated. The following actions target Node.js 20 but are being forced to run on Node.js 24: seanmiddleditch/gha-setup-ninja@v6.` (`.github/workflows/core.yml:83`).
  - **Kotlin deprecation warnings** in `:daemon:compileDebugKotlin`:
    - `daemon/src/main/kotlin/org/matrix/vector/daemon/VectorDaemon.kt:378:58` and `383:26`: `'static fun seteuid(p0: Int): Unit' is deprecated. Deprecated in Java.`
    - `daemon/src/main/kotlin/org/matrix/vector/daemon/data/FileSystem.kt:990:58` and `995:26`: same `Os.seteuid` deprecation.
  - **Gradle deprecation warning**: `Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.`
  - **Checks not run in `core.yml`**: `core.yml` runs `check_translations.py` (`lines 36-39`) and `./gradlew zipAll` (`line 106`), which triggers `checkXResourcesIsolationRelease`, but does **not** invoke `ktfmtCheck`, Android `lint`, or host Python/shell regression suites (`tests/reattach/run_tests.py`, `tests/hotload/run_tests.py`).

### 1.3 Gradle Build, Signing & ZIP Packaging Audit
- **Tooling versions**:
  - Gradle wrapper (`gradle/wrapper/gradle-wrapper.properties:3`): `gradle-9.8.2-bin.zip`.
  - Version catalog (`gradle/libs.versions.toml:1-28`): AGP `9.5.0`, Kotlin `2.4.21`, ktfmt `0.27.0`, Compose BOM `2026.09.00`, Material3 `1.5.0-rc01`, Nav3 `1.2.1`.
  - Root `build.gradle.kts:226-232`: `compileSdk = 37`, `targetSdk = 37`, `minSdk = 27`, `buildToolsVersion = "37.0.0"`, `ndkVersion = "30.0.16248370"`, Java `21`.
  - `build.gradle.kts:269,275,303-310`: CMake `3.29.8+`, 4 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`), `-Wl,-z,max-page-size=16384` (16 KB page alignment).
  - `gradle.properties:18`: `org.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8`.
- **Signing & `SignInfo`**:
  - `.github/workflows/core.yml:41-65`: On `master` non-PR or tag pushes, writes `androidStorePassword`, `androidKeyAlias`, `androidKeyPassword`, `androidStoreFile=key.jks` to `gradle.properties` and decodes `secrets.KEY_STORE` to `key.jks`. Fails explicitly if `IS_UPSTREAM == "true"` (`HazY-Codes`) and `KEY_STORE` is empty.
  - `manager/build.gradle.kts:40-41,117-137`: Creates signingConfig `apksign` when `androidStoreFile` exists and applies it to all build types (`debug` + `release`), else falls back to `debug`.
  - `daemon/build.gradle.kts:68-138`: `GenerateSignInfoTask` (`generate<Variant>SignInfo`) reads `:manager`'s active `signingConfig` and emits `org/matrix/vector/daemon/utils/SignInfo.kt` with `CERTIFICATE = byteArrayOf(...)`.
- **ZIP Packaging (`zygisk/build.gradle.kts:91-202,392-476`)**:
  - `zipAll` depends on `zipDebug` and `zipRelease`; `zipRelease` also depends on `checkXResourcesIsolationRelease` (`line 474`).
  - Output archives: `zygisk/release/Vector-SRX-v${versionNameProvider.get()}-${versionCodeProvider.get()}-$variantCapped.zip`.
  - Payload staged into `zygisk/build/module/${variant.name}`:
    - `README.md` (from root)
    - `module.prop` (expanded `versionName`, `versionCode`, `versionHash`)
    - `action.sh` (token-replaced `MANAGER_PACKAGE_NAME`, `INJECTED_PACKAGE_NAME`)
    - `daemon` (token-replaced `DEBUG=true|false`)
    - `customize.sh`, `emulated-soft-reboot.sh`, `post-fs-data.sh`, `service.sh`, `uninstall.sh`, `sepolicy.rule`, `system.prop`, `cli`, `META-INF/com/google/android/{update-binary,updater-script}`
    - `manager.apk` (renamed from `:manager:package<Variant>`)
    - `daemon.apk` (renamed from `:daemon:package<Variant>`)
    - `lib/<abi>/libzygisk.so` across 4 ABIs (stripped by AGP; `.dynsym` exports restricted to `zygisk_module_entry` and `zygisk_companion_entry` via `zygisk/src/main/cpp/CMakeLists.txt:15-25`)
    - `bin/<abi>/dex2oat` and `bin/<abi>/liboat_hook.so` across 4 ABIs (from `:dex2oat:externalNativeBuild<Variant>`)
    - `framework/vector.dex` (from `minifyReleaseWithR8` for Release, `mergeDexDebug` for Debug)
    - `<file>.sha256` sidecar generated for every non-directory file (`lines 183-190`), verified during install by `zygisk/module/customize.sh:40-53`.
  - **Minor packaging observation**: `dex2oat/src/main/cpp/CMakeLists.txt:15-24` strips `dex2oat` and saves `dex2oat/build/symbols/${ANDROID_ABI}/dex2oat.debug` in Release, whereas `oat_hook` (`liboat_hook.so`) has no `POST_BUILD` strip/symbol command (`core.yml:152` uploads `dex2oat/build/symbols`).
  - **R8 & mappings**: `core.yml:136-153` uploads `{zygisk,manager,daemon}/build/outputs/mapping` and `{zygisk,daemon,dex2oat}/build/symbols`.
  - **`check_translations.py`**: Run locally:
    - `python3 manager/tools/check_translations.py manager/src/main/res` -> `problems: 0`, exit `0`.
    - `python3 manager/tools/check_translations.py daemon/src/main/res` -> `problems: 0`, exit `0`.

### 1.4 Branding, Licensing, Release Channel & Keystore Audit
- **`LICENSE` (lines 1–675)**: Unmodified GNU GPL v3.0.
- **`NOTICE.md` (lines 1–32)**: Complete GPL-3.0 Section 5 modification notice and provenance table covering `JingMatrix/Vector` (`6f6b976e` + `#579`), `byemaxx/Vector-SR` (`45d20109` / `7065a816`), `JingMatrix/Vector` PRs `#1008`, `#1012`, `#1013`, `#1017`, `#1018`, `#1103`, `#1104`, and `peppicry/Vector-SR-S24U-Hotfix`.
- **`README.md` (lines 1–74)**: Branded **Vector-SRX**, badges/links point to `HazY-Codes/Vector-SRX`, retains `id=zygisk_vector` compatibility note, credits all upstreams, links `./LICENSE` and `./NOTICE.md`. Minor typo at line 55: `notification,root manager` (missing space after comma).
- **`zygisk/module/module.prop` (lines 1–8)**:
  - `id=zygisk_vector`
  - `name=Vector-SRX`
  - `version=${versionName} (${versionCode}-${versionHash})`
  - `versionCode=${versionCode}`
  - `author=JingMatrix, byemaxx, HazY-Codes`
  - `updateJson=https://raw.githubusercontent.com/HazY-Codes/Vector-SRX/master/zygisk/update.json`
- **`zygisk/update.json` (lines 1–7)**:
  - Currently `"version": "v2.2.1"`, `"versionCode": 3131`, `"zipUrl": "https://github.com/HazY-Codes/Vector-SRX/releases/download/v2.2.1/Vector-SRX-v2.2.1-3131-Release.zip"`, `"changelog": "https://raw.githubusercontent.com/HazY-Codes/Vector-SRX/master/zygisk/changelog.md"`.
- **`zygisk/changelog.md` (lines 1–14)**: Concise, accurate changelog with no duplicate H1 title (matches `core.yml:161`).
- **`manager/src/main/kotlin/org/matrix/vector/manager/data/github/GitHubRepository.kt` (lines 926–958, 823–832)**:
  - `OWNER = "HazY-Codes"`, `REPO = "HazY-Codes/Vector-SRX"`, `ZIP_PREFIX = "Vector-SRX-"`.
  - `GhRelease.versionCode()` filters assets starting with `Vector-SRX-` and parses the trailing numeric build code (`\d{3,}`), matching `Vector-SRX-v2.2.1-<code >-Release.zip`.
- **Intentional/residual `Vector` identifiers (verified)**:
  - `settings.gradle.kts:19`: `rootProject.name = "Vector"` -> `BuildConfig.FRAMEWORK_NAME` is `"Vector"` (`daemon/build.gradle.kts:36`, `xposed/build.gradle.kts:14,30`).
  - `daemon/src/main/res/values/strings.xml:11-12`: `"Vector status"` / `"Vector loaded"`.
  - `.github/ISSUE_TEMPLATE/config.yml:4`: `Upstream Vector Discussions` linking to `JingMatrix/Vector/discussions` with explicit warning not to report Vector-SRX bugs upstream.
- **Release keystore & GitHub Secrets**:
  - Local backup at `/data/user/0/com.agy/files/.config/vector-srx/`: `release.jks` (`0600`, `4.2K`) and `credentials.json` (`0600`, `231B`, all three credential fields non-empty).
  - Verified with `/data/user/0/com.agy/files/usr/lib/jvm/java-25-openjdk/bin/keytool -list -v`:
    - `Alias name: vector-srx` (`PrivateKeyEntry`)
    - `Owner: CN=Vector-SRX, OU=Android, O=HazY-Codes, C=US`
    - `Issuer: CN=Vector-SRX, OU=Android, O=HazY-Codes, C=US`
    - `Valid from: Tue Nov 24 10:29:48 GMT 2026 until: Sat Apr 11 10:29:48 GMT 2054`
    - `SHA256: 56:03:F4:D1:0C:D4:B9:FA:C2:98:C9:63:21:9B:A2:75:40:E5:0B:4C:1D:2E:99:3F:18:84:A5:87:90:9D:14:55`
  - `gh secret list --repo HazY-Codes/Vector-SRX`: `ALIAS`, `KEY_PASSWORD`, `KEY_STORE`, `KEY_STORE_PASSWORD` all configured.
  - `gh release list --repo HazY-Codes/Vector-SRX`: `no releases found` (expected prior to green CI).

---

## 2. Logic Chain

1. **Why CI `Build with Gradle` fails on run `#45375977969` (and PRs `#1–#3`)**:
   - `core.yml:18` runs on `ubuntu-latest` (now `ubuntu-26.04`) and `core.yml:102` deletes `$ANDROID_HOME/cmake`, so AGP invokes the runner's system CMake.
   - Between `ubuntu-26.04` image `20261111.82.6` (where upstream `43788184282` passed with 127 Ninja steps) and `20261116.202.1` (where `45375977969` failed with 130 Ninja steps), the runner's system CMake began enabling `import std;` synthetic module targets (`@cmake_cxx_std@synth_0.dir`) for C++23 module targets.
   - `daemon/src/main/jni/CMakeLists.txt:4`, `external/CMakeLists.txt:5`, `native/CMakeLists.txt:5`, and `zygisk/src/main/cpp/CMakeLists.txt:5` all set `CMAKE_CXX_STANDARD 23`, and `external/CMakeLists.txt:24` calls `add_subdirectory(fmt)` without disabling `FMT_MODULE`.
   - `{fmt}` `12.2.1` (`external/fmt/CMakeLists.txt:58-93,448-452`) auto-enables `FMT_MODULE=ON` under Ninja + Clang + C++20/23 and defines `fmt-module`.
   - CMake generates `@cmake_cxx_std@synth_0.dir` under `external/fmt/CMakeFiles/` using the host GCC 15 module descriptor (`/usr/include/c++/15/bits/std.cc`) rather than NDK `libc++`, causing NDK r30 `clang-scan-deps` (`--sysroot=.../ndk/30.0.16248370/...`) to abort because `'bits/stdc++.h'` does not exist in the Android NDK sysroot.
   - Both `:daemon` (via `daemon/src/main/jni/CMakeLists.txt:6`) and `:zygisk` (via `zygisk/src/main/cpp/CMakeLists.txt:8` -> `native/CMakeLists.txt:7`) include `external/CMakeLists.txt`, so both fail on the exact same `fmt` synthetic module step.
2. **Why `zygisk/update.json` will drift at `v2.2.1` unless updated last**:
   - `GitCommitCountValueSource` (`build.gradle.kts:24-42`) sets `versionCode` to `git rev-list --count refs/remotes/origin/master` (currently `3131`).
   - Any commit pushed to `master` (R1/R2/R3 fixes) will increment `versionCode` above `3131`, changing the produced filename `Vector-SRX-v2.2.1-<N>-Release.zip` while `zygisk/update.json` still says `3131`.
3. **Why `.agents/` poses a repo-hygiene risk**:
   - Neither `.gitignore` nor `.git/info/exclude` lists `.agents/`, so `git status` shows all teamwork metadata as untracked, risking accidental inclusion in commits if `git add -A` or `git add .` is run.

---

## 3. Caveats

- All 9 git submodules are uninitialized locally, and the local host does not have Android SDK/NDK installed, so `./gradlew zipAll` and `./gradlew ktfmtCheck` were not executed locally (CI logs and `check_translations.py` / `git diff --check` / `keytool` were run directly).
- The exact system CMake version string on `ubuntu-26.04` `20261116.202.1` is not printed in the Gradle verbose output of `45375977969` (and `read_url_content` to `raw.githubusercontent.com` for `Ubuntu2604-Readme.md` was denied by policy); however, the 127 -> 130 step change, `@cmake_cxx_std@synth_0.dir`, and `/usr/include/c++/15/bits/std.cc` command line in the verbatim log fully establish the mechanism.
- Open Dependabot PRs `#1`, `#2`, `#3` on `HazY-Codes/Vector-SRX` were inspected at summary/log level only; they fail on the same CMake `std.cc` error.

---

## 4. Conclusion & Concrete Remediation Plan

1. **Fix CMake C++23 `import std;` / `{fmt}` module build failure (R3 blocker)**:
   - In `external/CMakeLists.txt` (before line 24 `add_subdirectory(fmt)`): explicitly disable `FMT_MODULE` (`set(FMT_MODULE OFF CACHE BOOL "" FORCE)` or `option(FMT_MODULE "" OFF)` + `add_subdirectory(fmt EXCLUDE_FROM_ALL)`), since Vector only consumes `fmt-header-only`.
   - In `build.gradle.kts:303-310` (`args` passed to `externalNativeBuild.cmake.arguments`): add `"-DCMAKE_CXX_MODULE_STD=OFF"` and `"-DFMT_MODULE=OFF"` so CMake 3.30+/4.x never synthesizes host `std.cc` module scanning for Android NDK cross-compiles while preserving `lsplant` and `dex_builder` C++23 module compilation.
   - In `.github/workflows/core.yml`: consider pinning `runs-on: ubuntu-24.04` (or keeping `ubuntu-latest` once the CMake flags above are verified green, as the flags fix both `ubuntu-24.04` and `ubuntu-26.04`), and evaluate whether `rm -rf $ANDROID_HOME/cmake` (`line 102`) is still desirable vs installing/pinning a known CMake version.
2. **Harden CI workflow (`.github/workflows/core.yml`)**:
   - Add host regression / static checks (`ktfmtCheck`, `tests/reattach/run_tests.py`, `tests/hotload/run_tests.py`, and new R2 tests) to `core.yml` prior to `./gradlew zipAll`.
   - Note Node.js 20 deprecation on `seanmiddleditch/gha-setup-ninja@v6` (optional: install `ninja-build` via `apt` or set `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24=true` / wait for upstream action update; `ubuntu-26.04` already enforces Node 24).
   - Address `Os.seteuid` deprecation warnings in `VectorDaemon.kt:378,383` and `FileSystem.kt:990,995` if desired (non-blocking).
3. **Repo & Metadata Hygiene**:
   - Add `.agents/` to `.git/info/exclude` (never commit `.agents/`).
   - Fix minor typo in `README.md:55` (`notification,root manager` -> `notification, root manager`).
   - Confirm whether `BuildConfig.FRAMEWORK_NAME` (`settings.gradle.kts:19` `rootProject.name = "Vector"`) and `daemon/src/main/res/values/strings.xml:11-12` (`Vector status` / `Vector loaded`) should stay `"Vector"` for compatibility or be updated to `"Vector-SRX"`.
4. **Pre-Tag `v2.2.1` Release Synchronization**:
   - Immediately before creating and pushing tag `v2.2.1` on `master`, calculate the exact post-commit `versionCode` (`(git rev-list --count HEAD) + 1` for the final commit) and update `zygisk/update.json` (`versionCode` and `zipUrl`) so `update.json` matches `Vector-SRX-v2.2.1-<versionCode>-Release.zip` attached by `Publish release` in `core.yml:164-169`.

---

## 5. Verification Method

- **Repo hygiene**:
  - `GIT_CONFIG_NOSYSTEM=1 git status -u` -> confirm `.agents/` no longer appears as untracked after updating `.git/info/exclude`.
  - `GIT_CONFIG_NOSYSTEM=1 git diff --check` -> exit `0`, empty output.
- **Translation & static checks**:
  - `python3 manager/tools/check_translations.py manager/src/main/res` -> `problems: 0`, exit `0`.
  - `python3 manager/tools/check_translations.py daemon/src/main/res` -> `problems: 0`, exit `0`.
- **Keystore verification**:
  - Run `/data/user/0/com.agy/files/usr/lib/jvm/java-25-openjdk/bin/keytool -list -v -keystore /data/user/0/com.agy/files/.config/vector-srx/release.jks` -> confirm `CN=Vector-SRX` SHA256 `56:03:F4:...`, matching `gh secret list --repo HazY-Codes/Vector-SRX`.
- **CI & Artifact verification (post-fix)**:
  - Monitor `gh run watch --repo HazY-Codes/Vector-SRX` for `Core` workflow -> `conclusion: success` across all 4 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`), with zero `@cmake_cxx_std@synth_0` errors.
  - Download produced `Vector-SRX-v*-Release.zip` and verify archive layout, `.sha256` checksums (`sha256sum -c`), `module.prop`, `libzygisk.so` `.dynsym` exports (`nm -D`), and `apksigner verify` against `CN=Vector-SRX`.
  - **Invalidation condition**: Any subsequent commit to `master` after updating `zygisk/update.json` invalidates `update.json`'s `versionCode` and `zipUrl` and requires re-syncing `zygisk/update.json` prior to tagging `v2.2.1`.
