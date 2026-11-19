# Frame-rate plan

What is already tuned, what to try next, and how to tell whether a change actually helped. Every item
below is a single-variable A/B: change one thing, measure, keep or revert.

## Measure first

- Same place, same character, same camera: e.g. stand still in a capital city for 3 minutes, then fly a
  fixed route for 2 minutes. Record **min / average FPS** from the FPS overlay (the container sets
  `showFPS=true`), not a single glance.
- Note the phone's thermal state and battery level. This device throttles; a run after a long session is
  not comparable to a cold run. Wait 10 minutes between A/B runs.
- Keep `WINEDEBUG` off while measuring (see Tier 0). Logging shows up as stutter, not as a lower average.

## Tier 0 — already applied

| Change | Why |
| :--- | :--- |
| `WINEDEBUG=-all` unless the Wine-debug preference is on | Every `FIXME`/`ERR` costs a format + write on the render thread's process. Previously `err+all` ran in every session. |
| `MESA_SHADER_CACHE_MAX_SIZE=512MB`, cache not disabled | Recompiling shaders is the largest first-run stutter source. |
| `MESA_VK_WSI_PRESENT_MODE=mailbox` | Latest-frame presentation instead of a FIFO queue. |
| Resolution default 720p on Adreno 8xx | Fill rate is the dominant cost on this GPU; 1080p is a big step down in frame time. |

Never reintroduce `WINEDEBUG=+seh`: it slows exception dispatch enough to trip the anti-cheat's
`udf` + `cntvct_el0` timing check.

## Tier 1 — environment A/B (cheap, reversible)

Change these in the container's environment (or `containerConfig`), one at a time.

1. **`WINEESYNC=1`** (currently `0`, with `PROTON_NO_NTSYNC=1`). Synchronisation goes through the Wine
   server today. Esync is usually a clear win in CPU-bound scenes. If the client hangs, logs in slowly or
   the anti-cheat complains, revert to `0`.
2. **DXVK frame latency**: add `dxvk.maxFrameLatency = 1` to `DXVK_CONFIG`. Lower input latency; may cost
   a little throughput.
3. **DXVK compiler threads**: `dxvk.numCompilerThreads = 4` (default 0 = auto). Only helps shader-compile
   stutter, not steady-state FPS.
4. **Turnip variant**: two wheels ship (`turnip-V32-RP6sched.zip`, `turnip-wow-scheduler-test.zip`). The
   scheduler-test build targets this class of GPU; A/B them and keep the faster one for the default
   profile.
5. **`TU_DEBUG=nolrz`**: the gen8 branch appends it. Try with and without; some Turnip builds are faster
   with LRZ on, others misrender with it.
6. **Resolution 900p vs 720p**: if 900p holds the same minimum FPS, take the sharper image; if not, stay
   at 720p.

## Tier 2 — structural (needs a container smoke test)

7. **`wow64Mode=false` for the game container.** The client is native ARM64; the 32-bit half of the
   prefix exists only for compatibility. Fewer loaded modules and thunks can help startup and memory.
   Verify container creation, `wineboot`, and that the game still launches before keeping it. The
   Battle.net container is gone, so nothing else needs WoW64.
8. **Remove GL-only environment**: `mesa_glthread` and `ZINK_*` do nothing under DXVK/Vulkan. Harmless to
   drop; keeps the environment honest.
9. **Drop `VKD3D_SHADER_MODEL`**: the client is D3D11 via DXVK. Only relevant if D3D12 is ever used.

## Tier 3 — game side (no app change)

10. In-game graphics: shadows and liquid detail are the cheapest wins on Adreno; view distance next.
11. `SET gxApi "D3D11"` is forced by the launcher; keep it.
12. Optional `/console gxRestart` after changing render settings so the new pipeline is used immediately.

## Profiling (when a change is unclear)

- `DXVK_HUD=fps,frametimes,compiler` (or `DXVK_HUD=1`) gives a frame-time graph; `compiler` shows shader
  compilation spikes. Enable it only for the measurement run.
- `DXVK_LOG_LEVEL=info` plus `WINEDEBUG=err+all` for a one-off diagnostic session; turn both back off.
- `last-run.log` is written under the app's external files dir when Wine logging is enabled. It is empty
  on purpose during normal play.

## Order to try

1. `WINEESYNC=1` (biggest expected win)
2. DXVK `maxFrameLatency = 1` (latency, near-free)
3. Turnip variant + `nolrz` A/B
4. `wow64Mode=false`
5. In-game graphics sliders
