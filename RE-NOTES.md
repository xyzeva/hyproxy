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

## Build environment

The local host has no JDK (only JRE 21, no `javac`); the proxy needs JDK 25. Build inside a
container instead of changing the host. **Recipe used for all `./gradlew` builds in this ticket:**

```bash
cd services/repositories/hyproxy
docker run --rm -v "$PWD":/work -w /work \
  -v hyproxy-gradle-cache:/root/.gradle \
  eclipse-temurin:25-jdk bash -lc 'sh ./gradlew --no-daemon <task>'
```

(`gradlew` may have lost its +x bit in git; invoke it with `sh ./gradlew`. Build outputs under
`build/` end up root-owned because the container runs as root — they are gitignored, so this is
cosmetic; `chown` if a host tool needs to read them.)

**Baseline build verified:** `:proxy:build -x test` → BUILD SUCCESSFUL. The shadow jar
`proxy/build/libs/hyproxy-1.3.jar` is 61,198,355 B vs the deployed
`hytale-proxy/server.jar` 61,198,368 B — a ~13-byte (manifest/timestamp) difference, confirming
the fork base reproduces the deployed engine.

## Packet IDs (current vs hyproxy)

## Connect layout

## Auth packets

## ClientReferral / transfer

## Handshake sequence

## Live byte captures
