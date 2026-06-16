# MHG-1132 Hytale protocol RE notes

## Fork base

**Chosen base:** `SantioMC/hyproxy` @ tag `1.3` = commit `20975557532e456b113e61403ed62a2abd3fc3b6`
(this is the current HEAD of `SantioMC/hyproxy` `main`; tag `1.3` points at the same commit).

**Reasoning:**
- The deployed proxy engine (`services/repositories/hytale-proxy/server.jar`) and the lobby's
  backend plugin (`services/repositories/hytale-image/runtime/mods/hyproxy-backend-1.3.jar`) both
  ship the `ac.eva.hyproxy` package (e.g. `ac.eva.hyproxy.Main`, `ac.eva.hyproxy.HyProxy`,
  `ac.eva.hyproxy.plugin.HyProxyBackendPlugin`), matching this codebase's `group = "ac.eva"`.
- The backend jar is literally named `hyproxy-backend-1.3.jar`, and `build.gradle.kts` declares
  `version = "1.3"`. The deployed artifacts therefore correspond to the `1.3` release.
- `SantioMC/hyproxy` is the actively maintained fork (last push 2026-06-01) and its `upstream`
  remote is `xyzeva/hyproxy` (frozen 2026-04-21). We fork the live fork (Santio) so we inherit
  its more recent state while still being able to diff against xyzeva upstream if needed.

**gh fork outcome:**
- `gh repo fork SantioMC/hyproxy --org Minehut --fork-name hyproxy --clone=false` succeeded.
- Verified: `Minehut/hyproxy` exists, `isFork: true`, parent `SantioMC/hyproxy`, default branch `main`.
- `Minehut/hyproxy` did not previously exist; the actor is an active admin of the `Minehut` org.
- Cloned into `services/repositories/hyproxy`; working branch:
  `alexandresequeira/mhg-1132-update-protocol` (HEAD `2097555`).

## Packet IDs (current vs hyproxy)

## Connect layout

## Auth packets

## ClientReferral / transfer

## Handshake sequence

## Live byte captures
