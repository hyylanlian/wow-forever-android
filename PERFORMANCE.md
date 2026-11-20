# Frame-rate plan

What is tuned today, the two built-in presets, and the experiments that remain, with the mechanism
behind each one on this stack (Snapdragon 8 Elite / Adreno 840, Turnip + DXVK on ARM64EC Wine).

## The two different problems

- **Throughput** (average FPS): limited by Adreno fill rate, memory bandwidth and thermal throttle.
- **Stutter** (minimum FPS): limited by shader-compilation pauses, synchronisation round-trips and
  thermal spikes.

A change that helps one often does nothing for the other. First measure which one dominates.

## The presets (setup screen → Performance)

| Preset | Intended for | Differences from the validated baseline |
| :--- | :--- | :--- |
| **Standard** | daily play, passive cooling | the validated defaults |
| **Max (cooler)** | external cooler | `DXVK_CONFIG=dxvk.cachedDynamicResources=c,dxvk.maxFrameLatency=1` |

`WINEESYNC=0` and `PROTON_NO_NTSYNC=1` stay in both. Esync was tried in Max first and **hangs container
startup on this Proton build** (prefix init never finished, standard took ~10 s), so it is off. The ntsync
driver is off because it hung the loopback socket. Resolution is independent of the preset: with a
cooler, 900p/1080p no longer costs sustained FPS the way it does passively cooled.

## Step 0 — measure and classify (one session)

Enable the HUD for one session:

```
DXVK_HUD=fps,frametimes,compiler
```

- `compiler` lines spiking while moving to new areas → shader-compile stutter → warm the cache first
  (play through each zone once).
- smooth FPS that drops over 10+ minutes → thermal throttle → lower quality settings or switch to the
  Max preset on a cooler.
- constant low average at 720p with few spikes → fill/bandwidth-bound → LRZ and render-scale below.

Same protocol for every A/B: same place, 3 min static + 2 min flight, cold device, 10 min between runs,
record min/average FPS. Change exactly one variable per run.

## Already in place

| Setting | Why |
| :--- | :--- |
| `WINEDEBUG=-all` by default | Formatting + writing every `FIXME`/`ERR` costs CPU on the render path; the debug preference restores `err+all`. |
| `MESA_SHADER_CACHE_MAX_SIZE=512MB` | Shader recompiles are the largest first-run stutter source. |
| `MESA_VK_WSI_PRESENT_MODE=mailbox` | Present the newest frame; no FIFO queue lag. |
| 720p default on Adreno 8xx | Fill rate dominates; 1080p nearly doubles frame cost. |
| `nolrz` in `TU_DEBUG` | Matches the config this game was validated with; see the LRZ experiment. |
| Max preset: DXVK dynamic-resource cache + frame latency | Cached dynamic resources cut map/copy stalls; a shorter frame queue lowers latency. |

Never set `WINEDEBUG=+seh`: slow exception dispatch trips the anti-cheat's `udf` + `cntvct_el0` check.

## The remaining experiments, in priority order

| # | Experiment | Mechanism (why it can win) | Risk | Revert |
| :-- | :--- | :--- | :--- | :--- |
| 1 | **Confirm Max beats Standard** | Validates the DXVK deltas with real numbers. If Max doesn't win, revert them one at a time. | none (toggle) | toggle |
| 1b | ~~`WINEESYNC=1`~~ | **Tested: hangs container startup on this Proton build.** Not available until the wine build changes. | blocked | n/a |
| 2 | **LRZ: drop `nolrz`** | Adreno's low-resolution-Z rejects hidden pixels before shading. WoW is overdraw-heavy (foliage, alpha). If this Turnip build handles LRZ, dense-scene minimum FPS rises visibly. | LRZ bugs on Turnip are classic: flickering geometry or missing particles. Test visually, not just FPS. | 1-line env |
| 3 | **Turnip variant A/B** | `Turnip-V32-RP6sched` (current, tuned for gen8 by arusiasotto) vs `Turnip-WoW-scheduler-test` (the Adreno 740 default). Different scheduler/UBWC handling can be worth several frames. | Rendering regressions | switch the driver asset |
| 4 | **In-game render scale + sliders** | Render scale 0.8 with the game's own sharpening (or FSR if offered) keeps the UI sharp while cutting shading cost; shadows and liquid detail are the cheapest wins on Adreno, view distance next. | Visual | in-game |
| 5 | **`wow64Mode=false` for the game container** | The client is native ARM64 and the 32-bit Proton half was already trimmed, so the WoW64 layer is vestigial. | The ARM64EC build may be "wow64-only": Winlator might need this flag to pick the right loader. Smoke-test boot; revert on failure. | 1 field |
| 6 | **Later: newer DXVK aarch64** | 2.5.x carries Adreno-specific dynamic-state and barrier fixes. Needs a new component build (not available in-repo). | New surface | new component |

## Explicitly not worth it

- `mesa_glthread`, `ZINK_*`: OpenGL-only; the game is D3D11→Vulkan. Removing them is cosmetic.
- `VKD3D_SHADER_MODEL`: D3D12-only.
- `PULSE_LATENCY_MSEC=144`: audio buffering, no FPS effect.
- `dxvk.numCompilerThreads`: only first-run shader compile, not steady-state FPS.

## Thermal

Without a cooler, Adreno throttles hard under sustained load; the highest sustained average usually
comes from 720p + low shadows + the device's performance mode, not from GPU-side tweaks. With a cooler,
the Max preset plus a higher resolution is the intended combination. If a "boost" is only visible in the
first three minutes, it did nothing for sustained play.
