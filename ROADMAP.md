# Distribution roadmap (before the frame-rate work)

Priority: make the app safe and usable for other players first, then chase FPS. The four workstreams
below are planned in dependency order, with scope, source choices and risks. Nothing here is
implemented yet; it is the plan to review.

## 0. Current state (what we build on)

- **Driver**: two bundled Turnip builds (`turnip-wow-scheduler-test.zip`, `turnip-V32-RP6sched.zip`)
  chosen automatically by SoC in `GpuProfile.detect`. No user selection, no online update. A generic
  `GraphicsDriverDownloader`/`WinComponentDownloader` scaffold already exists for downloads + sha256.
- **Resolution**: `RenderResolution` sets the container's `screenSize` — the Wine desktop / X server
  size the game renders into. The panel upscales it. So "Resolution" is the real in-game render
  resolution (720p/900p/1080p), the single largest GPU cost dial; it is not the phone's panel mode.
- **Add-ons**: `AddonImporter` installs from a local zip only (no catalog, no update check).
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
to the container (`graphicsDriverVersion` + `TU_DEBUG`) on the next launch. Remaining: the online part.

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

## 3. Add-on manager: search, download, update (L effort)

Reuse `AddonImporter` for installs; add a catalog and an update check.

- **Data source**: start with **Wago (wago.tools)** — no API key, per-addon metadata and file URLs;
  fall back to **GitHub releases** for add-ons that publish there. **CurseForge** is the community
  default but needs an API key and is unreliable from CN, so it is a later, optional backend. Design
  the fetcher behind an interface (`AddonSource`) so backends are swappable.
- **Search/browse UI**: a dedicated screen or dialog: search box, results (name, author, summary,
  version, game flavor mainline/classic), install button. Downloads go through the existing
  mirror/fallback network stack; unpack via `AddonImporter` into `_forever_/Interface/AddOns`.
- **Update detection**: parse each installed add-on's `.toc` (`## Version:`, `## Interface:`) and
  compare with the catalog's latest; "Update all" replaces folders in place. Skip-version and
  per-addon pinning for details.
- **Phases**: (a) search + install, (b) update check + batch update, (c) favorites and config backup.
- **CN note**: wago.tools reachability from CN must be verified first; if blocked, the GitHub-releases
  backend becomes the primary.

## 4. Resolution clarification and small extras (S effort)

- **Document what it does** (in-app hint + README): Resolution = the render resolution the game draws
  at inside the Wine desktop; the phone upscales it. 720p is the default because fill rate dominates
  on Adreno; with a cooler 900p/1080p are safe choices.
- **Add 1440p** for 8 Elite + cooler setups, and a one-line explanation per entry ("720p – smoother,
  1080p – sharper, needs cooling").

## Order and effort

| # | Workstream | Effort | Risk | Start |
| :-- | :--- | :--- | :--- | :--- |
| 1 | Identity uniqueness + transparency | S–M | player accounts (ban risk if wrong) | now |
| 2 | Driver catalog + download + picker | M | GPU regressions (mitigated by rollback) | after 1 |
| 3 | Add-on manager | L | external APIs/CN network | after 2 (phased) |
| 4 | Resolution doc + 1440p | S | none | anytime |

The frame-rate experiments (`PERFORMANCE.md`) stay parked until these ship; the Max/Standard presets
already delivered remain.
