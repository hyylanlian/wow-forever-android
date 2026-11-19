# Frame-rate plan

What is tuned today, the levers that remain, in the order they should be tried, and why each one
matters on this stack (Snapdragon 8 Elite / Adreno 830, Turnip + DXVK on ARM64EC Wine).

## The two different problems

- **Throughput** (average FPS): limited by Adreno fill rate, memory bandwidth and thermal throttle.
- **Stutter** (minimum FPS): limited by shader-compilation pauses, synchronisation round-trips and
  thermal spikes.

A change that helps one often does nothing for the other. First measure which one dominates.

## Step 0 — measure and classify (one session)

Enable the HUD for one session:

```
DXVK_HUD=fps,frametimes,compiler
```

- `compiler` lines spiking while moving to new areas → shader-compile stutter → warm the cache first
  (play through each zone once); `dxvk.numCompilerThreads` only matters here.
- smooth FPS that drops over 10+ minutes → thermal throttle → lower the quality settings, not the app.
- constant low average at 720p with few spikes → fill/bandwidth-bound → the LRZ and render-scale
  experiments below.

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

Never set `WINEDEBUG=+seh`: slow exception dispatch trips the anti-cheat's `udf` + `cntvct_el0` check.

## The remaining levers, in priority order

| # | Experiment | Mechanism (why it can win) | Risk | Revert |
| :-- | :--- | :--- | :--- | :--- |
| 1 | **`WINEESYNC=1`** | Sync is the big one. `PROTON_NO_NTSYNC=1` kills the new ntsync driver and `WINEESYNC=0` kills classic esync, so every wait/signal is a wineserver round-trip (syscall + IPC) on 8 cores. Esync is the old userspace eventfd mechanism — **not** the ntsync driver that hung the socket earlier — and it is the standard Proton fast path. Expected: better CPU-bound throughput and less stutter. | Login/anti-cheat behaviour unknown; if the client stalls or the socket handoff misbehaves, undo. | 1-line env |
| 2 | **LRZ: drop `nolrz`** | Adreno's low-resolution-Z rejects hidden pixels before shading. WoW is overdraw-heavy (foliage, alpha). `nolrz` disables LRZ for correctness; if this Turnip build handles it, early-Z rejection can lift minimum FPS in dense scenes by a visible margin. | LRZ bugs on Turnip are classic: flickering geometry or missing particles on some surfaces. Test visually, not just FPS. | 1-line env |
| 3 | **Turnip variant A/B** | `Turnip-V32-RP6sched` (current, tuned for gen8 by arusiasotto) vs `Turnip-WoW-scheduler-test` (the Adreno 740 default, `noconform`). Different scheduler/UBWC handling can be worth several frames. Expect RP6sched to stay, but measure once. | Rendering regressions | switch the driver asset |
| 4 | **`dxvk.cachedDynamicResources = c`** (DXVK_CONFIG) | Constant-buffer uploads are a known stall on Adreno; caching them avoids repeated map/copy. The Battle.net container used the `vic` variant of this trick for CEF. | None known | 1-line env |
| 5 | **`dxvk.maxFrameLatency = 1`** | Lower input latency, tiny throughput cost. Quality-of-play, not FPS. | Minor | 1-line env |
| 6 | **In-game render scale + sliders** | Render scale 0.8 with the game's own sharpening (or FSR if offered) keeps UI sharp while cutting shading cost; shadows and liquid detail are the cheapest wins on Adreno, view distance next. | Visual | in-game |
| 7 | **`wow64Mode=false` for the game container** | The client is native ARM64 and the 32-bit Proton half was already trimmed from the APK, so the WoW64 layer is vestigial — fewer loader modules and thunks. | The ARM64EC build may be "wow64-only": Winlator might need this flag to pick the right loader. Smoke-test container creation + boot; if it fails, revert immediately. | 1 field |
| 8 | **Later: newer DXVK aarch64** | 2.5.x carries Adreno-specific dynamic-state and barrier fixes. Needs a new component build (not available in-repo). | New surface | new component |

## Explicitly not worth it

- `mesa_glthread`, `ZINK_*`: OpenGL-only; the game is D3D11→Vulkan. Removing them is cosmetic.
- `VKD3D_SHADER_MODEL`: D3D12-only.
- `PULSE_LATENCY_MSEC=144`: audio buffering, no FPS effect.
- 900p/1080p: fill-rate-bound; chasing resolution is the wrong direction on this GPU.

## Thermal

Adreno 830 throttles hard under sustained load; the highest sustained average usually comes from
720p + low shadows + the device's performance/cooling mode, not from GPU-side tweaks. If a "boost" is
only visible in the first three minutes, the change did nothing for sustained play.
