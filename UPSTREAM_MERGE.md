# Tracking upstream (`jaredgei/wow-forever-android`)

This fork does **not** share git history with upstream. Our repository is an independent history whose
root commit is `fe79631`, and upstream objects are absent, so `git merge` is impossible: an upstream
update is a **curated semantic port**, one file at a time.

## The rule

Upstream ships a browser-login implementation that fails on this device. Do **not** import these files
wholesale; our versions are the working ones:

| File | Keep ours because |
| :--- | :--- |
| `tools/wow-session-bridge/build.sh` | We build with the **msvcrt** llvm-mingw variant. The ARM64EC prefix ships `msvcrt.dll` but no `api-ms-win-crt-*.dll`, so upstream's UCRT build dies with `0xC0000135` before `wmain`. |
| `tools/wow-session-bridge/{bridge.c,device_id.c,device_id.h}` | Device JSON is assembled with explicit appends; Wine's msvcrt `vsnprintf` returns 0 bytes on ARM64, so upstream's printf-based JSON emits only `" }\n"`. |
| `.../auth/BattleNetBrowserLogin.kt` (launch command) | Paths are passed **unquoted**; Wine's `CreateProcessW(NULL, cmdline)` does not strip quotes from the first token, so a quoted helper path is never found. |
| `.../auth/BrowserAuthUrl.kt`, `BgsClient.kt` | CN/NetEase hosts and the multi-parameter `ST=` parse. Upstream only allows `battle.net`. |
| `.../wow/WowMachineIdentity.kt` | Writes the Qualcomm CPU vendor/name the client hashes; without it the client stalls. |
| `.../xserver/XServerScreen.kt` | `WINEDEBUG=err+all` (never `+seh`), our launch-command shape and `last-run.log` capture. |
| `.../wow/WoWForeverScreen.kt`, `PluviaMain.kt` | Browser-login gating replaced the cached-token path; `SharedCache` paths; `guestExited()` hook. |
| `tools/fetch-components.sh`, `tools/fetch-components-cn.sh`, `.github/workflows/build.yml` | CN mirrors, component fetching, auto versioning, pinned debug keystore. |

## How to port an upstream release

1. Fetch the tag tree (tarballs work through the mirrors; `git fetch` of a fresh unrelated history is
   slow and unnecessary):
   `curl -L -o up.tgz https://gh-proxy.com/https://codeload.github.com/jaredgei/wow-forever-android/tar.gz/refs/tags/<tag>`
2. `diff -rq <tag tree> .` and rank differing files by `diff -u | wc -l`.
3. Classify every file: **take**, **skip**, or **ours-is-superset**. Check the direction of each diff —
   most large diffs are our fixes, not theirs.
4. Apply "take" items with the edit tool as small hunks, then verify:
   - C: `clang --target=aarch64-w64-windows-gnu … -Wall -Wextra -Werror` for the helper, and
     `clang -std=c11 -O2 -Wall -Wextra -Werror -fPIC -c app/src/main/cpp/gamenative_dns_v4mapped.c`.
   - Java/Kotlin: no local Android SDK — push and let CI compile.
5. Bump the base version and the CI version template together.

## 2.4.5 port (this round)

Taken:

- **Version** `2.4.5`, `versionCode 248`; CI builds are `2.4.5-ci.<run>` with `versionCode = 248 + run`.
- **DNS shim** (`gamenative_dns_v4mapped.c`): `gethostbyname`/`gethostbyname_r` return the configured
  hostname as `h_name` without disturbing libc's buffers.
- **Key repeat** (`Keyboard.java`, `InputDeviceManager.java`, `XServer.java`): `autoRepeat` flag so
  hardware repeats don't double-fire, and a new key press cancels the previous repeat.
- **Proton 32-bit trim** (`tools/trim_proton_i386.py` + CI step): removes the unused i386 half at build
  time. Measured: 1012 files from `lib/wine/i386-windows/`, 49 32-bit PEs from `prefixPack.txz`,
  **126 MB → 65 MB**, ~93 s.
- **ContainerManager guard**: tolerates an empty or missing `i386-windows` directory after the trim.

Deliberately skipped:

- **Foreground service** (`GameSessionService`, `FOREGROUND_SERVICE*` permissions, `PluviaMain` hooks).
  The container already runs with `SUSPEND_POLICY_NEVER`, which keeps the session alive across app
  switches on this device; adding an Android 14 `specialUse` FGS would add permissions and a persistent
  notification for no measured gain. Revisit only if backgrounding is reported to drop the connection.
- **`BattleNetSignIn` / `login.txt`**: the cached-password path we removed; browser login supersedes it.
- **Upstream's helper diagnostics** (`Logs\BrowserLogin.log`, Wine exit status in the dialog): ours
  writes a private stage log and shows it in the failure dialog.
- **Helper test harness** (`test.sh`, `test_protocol.c`, `test_child.c`, `test_integration.py`) and the
  license files added alongside it: optional, not wired into CI.

## Known risk of the trim

Removing Proton's 32-bit Windows files means an ARM64EC prefix can no longer run x86 (32-bit) programs.
The WoW client is ARM64 and the Battle.net client is x86-64, so neither needs them. If container creation
or a 32-bit tool ever breaks after a Proton change, delete the trim step in
`.github/workflows/build.yml` and re-run — nothing else depends on it.
