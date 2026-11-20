# Distribution roadmap (before the frame-rate work)

Priority: make the app safe and usable for other players first, then chase FPS. The four workstreams
below are in dependency order, with scope, source choices and risks. Identity, drivers and the add-on
manager have landed; resolution documentation remains.

## 0. Current state (what we build on)

- **Driver**: two bundled Turnip builds (`turnip-wow-scheduler-test.zip`, `turnip-V32-RP6sched.zip`)
  chosen automatically by SoC in `GpuProfile.detect`. No user selection, no online update. A generic
  `GraphicsDriverDownloader`/`WinComponentDownloader` scaffold already exists for downloads + sha256.
- **Resolution**: `RenderResolution` sets the container's `screenSize` — the Wine desktop / X server
  size the game renders into. The panel upscales it. So "Resolution" is the real in-game render
  resolution (720p/900p/1080p), the single largest GPU cost dial; it is not the phone's panel mode.
- **Add-ons**: `AddonImporter` installs local zips; `WagoAddonSource`/`AddonManager` add Wago search,
  install and update checks (see section 3).
- **Identity**: `WowMachineIdentity` generates a random identity once per install into app storage,
  writes MachineGuid / install date / computer name / volume serial / Qualcomm CPU strings into the
  prefix. Uniqueness relies on `SecureRandom` alone; a backup restored to another device would copy
  the identity, which is the fraud-check hazard to close.

## 1. Device identity: uniqueness and anti-cheat safety (S–M effort, do first) — implemented

Landed: the identity is derived from `ANDROID_ID` + 256 bits of `SecureRandom`, stored Keystore-encrypted
(`wow_identity.dat`), and verified against a device fingerprint on every load. A backup restored onto a
different phone regenerates instead of sharing; the setup screen has an **Identity** row showing the
computer name with an explanation and a reset action. Legacy plaintext identities are adopted once on
upgrade (keeps the same PC) and the old file is deleted. Remaining known window: a backup made *before*
this change restored onto a fresh device adopts the legacy identity — strictly narrower than before, and
closed on the next launch after any post-upgrade backup.

Why it was first: it is the one item that can hurt players (Battle.net fraud checks, `BLZ51914400`) if done
wrong, and it blocks distribution.

## 2. Driver adaptation, selection and online update (M effort)

Landed: a per-family catalog (`DriverCatalog`) with the two bundled Turnip builds (a8xx →
`Turnip-V32-RP6sched` recommended, a7xx → `Turnip-WoW-scheduler-test` recommended, plus the
cross-choice), a **Driver** picker on the setup screen with notes, and the choice persisted and applied
to the container (`graphicsDriverVersion` + `TU_DEBUG`) on the next launch. The online half is in place
too: the app fetches `drivers.json` (raw + CN mirrors), and the dialog downloads, sha256-checks and
unpacks new drivers into `contents/adrenotools/<name>` with progress and retry. Remaining: a Drivers-CI
that actually publishes new builds, and per-entry smoke tests before marking anything recommended.

Model on DroidDeck: a curated per-GPU-family catalog plus CI-built weekly drivers, downloaded on
demand and selectable per container.

- **Catalog**: a small versioned JSON in the app (or fetched from this repo's releases) listing, per
  Adreno family (a6xx/a7xx/a8xx): bundled stable, plus downloadable candidates with name, source
  fork + git ref, sha256, size, notes. Include the two proven forks
  (`whitebelyash/mesa-unified` gen8 + the RP6 scheduler build; `mirror/mesa` + barycentric fix for
  a7xx).
- **CI builds**: a weekly GitHub Action that builds the Turnip `.zip` bundles and publishes them to a
  `drivers` release (mirrors DroidDeck's Drivers-CI; GPL, so license notices go into
  `THIRD_PARTY_NOTICES`). Do not ship third-party binaries we cannot rebuild.
- **Download/update**: reuse `GraphicsDriverDownloader`; sha256-checked, resumable, through the same
  CN mirrors as the game components. A failed download never breaks the install: the bundled stable
  driver stays available as a one-tap rollback.
- **Selection UI**: setup screen "Driver" row → per-family list (installed / available / recommended),
  apply → container `graphicsDriverVersion` + `bcnEmulation`/`adrenotoolsTurnip` flags; an "issues"
  hint so a player who sees artifacts can fall back one step.
- **Validation**: each new catalog entry is smoke-tested on this device (V2546A / Adreno 840) before
  being marked recommended; everything else ships as "experimental".

## 3. Add-on manager: search, download, update (L effort) — implemented

Search, install and update are live behind the setup screen's **插件** row.

- **Data source**: **Wago Addons (`addons.wago.io`)**. Wago has no public search API, so
  `WagoAddonSource` reads the server-rendered Inertia payload and uses the `forever` release's
  short-lived download link. Add-ons that don't publish a Forever build are shown as unsupported.
  **CurseForge** remains a later, optional backend (API key, unreliable from CN); a GitHub-releases
  backend can be added without touching the UI if Wago becomes unreachable.
- **Search/install**: `AddonManagerDialog` → search box, results (name, author, summary, updated),
  install/reinstall, progress. Downloads go through `Net`; `AddonImporter` unpacks into
  `_forever_/Interface/AddOns` in place, so SavedVariables survive.
- **Update detection**: the manager records the Wago slug, release label and installed folders in
  `wow_addons` prefs; `.toc` title/version/interface are parsed for display. "检查更新" fetches each
  slug's latest Forever release, and single or **全部更新** reinstalls changed folders.
- **Manual installs**: folders copied in by hand still appear, marked manual, without update checks.
- **CN note**: `addons.wago.io` was reachable during implementation (no CN-specific mirror is in
  use). If it is blocked, install from a ZIP still works; add a mirror/source before relying on the
  catalogue there.

## 4. Resolution clarification and small extras (S effort)

- **Document what it does** (in-app hint + README): Resolution = the render resolution the game draws
  at inside the Wine desktop; the phone upscales it. 720p is the default because fill rate dominates
  on Adreno; with a cooler 900p/1080p are safe choices.
- **Add 1440p** for 8 Elite + cooler setups, and a one-line explanation per entry ("720p – smoother,
  1080p – sharper, needs cooling").

## Order and status

| # | Workstream | Effort | Status |
| :-- | :--- | :--- | :--- |
| 1 | Identity uniqueness + transparency | S–M | ✅ done (`wow_identity.dat` Keystore blob, device fingerprint, Identity dialog + reset) |
| 2a | Driver catalog + picker | M | ✅ done (per-family `DriverCatalog`, Driver dialog, persisted selection) |
| 2b | Driver online download | M | ✅ pipeline done (`drivers.json` fetch + sha256 + unpack + Use); ⏳ a Drivers-CI that publishes new builds |
| 3 | Add-on manager | L | ✅ search/install/update via Wago; manual ZIPs listed; no favorites/config backup yet |
| 4 | Resolution doc + 1440p | S | ⏳ pending |

Performance work (`PERFORMANCE.md`) stays parked behind these; the Max/Standard presets already ship.
