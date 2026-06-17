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

## Decompile setup (Task 2)

- **Server jar:** `/tmp/HytaleServer.jar`, Hytale server build **0.5.5**, NOT obfuscated,
  packages under `com.hypixel.hytale.*`. Class file timestamps are `2026-06-16`.
- Jar placed at `backend-plugin/libs/HytaleServer.jar` and gitignored (licensed code, never
  committed). Verified `git check-ignore` matches and it is not staged.
- **Decompiler used:** **Vineflower 1.12.0** (`vineflower-1.12.0.jar`). The
  `releases/latest/download/vineflower.jar` asset 404s; pulled `vineflower-1.12.0.jar` from the
  release API instead. Output is clean and complete; CFR fallback was not needed.
  Vineflower was run via the `eclipse-temurin:25-jdk` container (host has no JDK 25; class files
  are Java 25 bytecode).
- Only the protocol + connection-handler classes were extracted (not the full 123 MB jar):
  `com/hypixel/hytale/protocol/*`, `.../server/core/io/handlers/*`,
  `.../server/core/io/netty/*`, `.../server/core/io/PacketHandler*`.

### backend-plugin compile result: **FAIL** (valuable finding — API drift)

`:backend-plugin:build -x test` → `BUILD FAILED`, `:compileJava` 2 errors. The Hytale
`com.hypixel.hytale.*` server API the backend plugin was written against has moved:

```
HyProxyBackendPlugin.java:101: error: no suitable method found for
    sendProxyMessage(ChannelConnection,ProxyCommunicationMessage)
  this.sendProxyMessage(playerRef.getPacketHandler().getChannel(), message);
    method ...sendProxyMessage(PlayerRef,ProxyCommunicationMessage) is not applicable
      (ChannelConnection cannot be converted to PlayerRef)
    method ...sendProxyMessage(Channel,ProxyCommunicationMessage) is not applicable
      (ChannelConnection cannot be converted to Channel)

HyProxyBackendPlugin.java:98: error: cannot find symbol
  this.sendProxyMessage(Universe.get().getPlayers().getFirst(), message);
    symbol:   method getFirst()
    location: interface Collection<PlayerRef>
```

Two concrete API changes in 0.5.5 vs the build hyproxy targeted:
1. `PlayerRef.getPacketHandler().getChannel()` now returns a `ChannelConnection`, not a
   `Channel`/`PlayerRef`. The backend's `sendProxyMessage(...)` overloads accept `PlayerRef` or
   `Channel` but not `ChannelConnection` — the channel type changed.
2. `Universe.get().getPlayers()` now returns a bare `Collection<PlayerRef>` (no longer a
   `List`/`SequencedCollection`), so `.getFirst()` no longer exists.

These are deferred to the backend-plugin fix task (do not fix here). The proxy module
(`:proxy:build`) is unaffected by these and still builds.

## Packet IDs (current vs hyproxy)

Source of truth: `com.hypixel.hytale.protocol.PacketRegistry` (server jar). IDs are assigned in
a single static-init table via `register(direction, channel, id, name, type, fixedBlockSize,
maxSize, compressed, validate, deserialize, toObject)`. There is no annotation/`getId()`-derived
scheme — the central table is authoritative; each packet class also exposes a matching
`PACKET_ID` constant and `getId()`. Direction is `ToServer` / `ToClient` / `Both`.

Handshake-relevant packets:

| Packet                         | Server id | Dir       | hyproxy id | Match? |
|--------------------------------|-----------|-----------|------------|--------|
| Connect                        | 0         | ToServer  | 0          | ✅ (id only — layout differs, see below) |
| ClientDisconnect               | 1         | ToServer  | 1          | ✅ |
| ServerDisconnect               | 2         | ToClient  | 2          | ✅ |
| Ping                           | 3         | ToClient  | — missing  | ⚠️ |
| Pong                           | 4         | ToServer  | — missing  | ⚠️ |
| AuthGrant                      | 11        | ToClient  | 11         | ✅ |
| AuthToken                      | 12        | ToServer  | 12         | ✅ |
| ServerAuthToken                | 13        | ToClient  | 13         | ✅ |
| ConnectAccept                  | 14        | ToClient  | 14         | ✅ |
| PasswordResponse               | 15        | ToServer  | — missing  | ⚠️ |
| PasswordAccepted               | 16        | ToClient  | — missing  | ⚠️ |
| PasswordRejected               | 17        | ToClient  | — missing  | ⚠️ |
| ClientReferral                 | 18        | ToClient  | 18         | ✅ |
| ServerMessage                  | 210       | ToClient  | 210        | ✅ |
| ChatMessage                    | 211       | ToServer  | 211        | ✅ |
| ServerInfo                     | 223       | ToClient  | 223        | ✅ |
| InsecurePlayerOptions          | 363       | ToServer  | — missing  | ⚠️ |
| RequestInsecurePlayerOptions   | 364       | ToClient  | — missing  | ⚠️ |

**All ids hyproxy registers still match the server.** No id renumbering. The gaps are *missing*
packets, not wrong ids:
- **Ping (3) / Pong (4):** keepalive after connect; not strictly part of the auth handshake but
  the client may send Pong; hyproxy has no decoder (will hit "unexpected packet"). Low priority.
- **InsecurePlayerOptions (363) / RequestInsecurePlayerOptions (364):** these belong to the
  DEVELOPMENT/OFFLINE (unauthenticated) flow only. The proxy runs an AUTHENTICATED flow, so it
  does NOT need these — but note the client's username+uuid now live HERE in dev mode, not in
  Connect (see Connect layout). Relevant only if we ever support offline mode.
- **Password* (15/16/17):** password-protected-server sub-flow that runs AFTER auth, before
  setup. Only used if the backend has a server password. Optional for the proxy, but the client
  CAN send PasswordResponse, so a decoder may be needed for password-protected backends.

## Connect layout

`com.hypixel.hytale.protocol.packets.connection.Connect` (id 0, ToServer, NOT compressed,
FIXED_BLOCK_SIZE=30, VARIABLE_BLOCK_START=46, MAX_SIZE=37972). Little-endian throughout.
Offset-table layout: a 1-byte null-bitfield, a fixed block, then **4** int32-LE offset slots,
then the variable block at byte 46. Each offset is relative to byte 46 (varBlockStart);
`-1`/out-of-range means absent.

Wire layout (offsets are from start of packet body):

| off | size | field            | notes |
|-----|------|------------------|-------|
| 0   | 1    | nullBits         | bit0=identityToken present, bit1=referralData present, bit2=referralSource present |
| 1   | 4    | protocolCrc      | int32 LE. **Server validates `protocolCrc == 1316766548` (0x4E7F3D14)**; mismatch → QUIC app-close ClientOutdated/ServerOutdated. |
| 5   | 4    | protocolBuildNumber | int32 LE. Server compares against `serverBuild = 100` only to pick the outdated-direction error message; the CRC check is the real gate. |
| 9   | 20   | clientVersion    | fixed 20-byte ASCII string (space/null padded), read via `readFixedAsciiString(buf, 9, 20)`. |
| 29  | 1    | clientType       | enum byte: 0=Game, 1=Editor (`ClientType.fromValue`; value ≥ 2 is invalid). |
| 30  | 4    | offset: identityToken | int32 LE, rel to byte 46. Only read if nullBits bit0 set. String, max 8192, UTF-8. |
| 34  | 4    | offset: language | int32 LE, rel to byte 46. ALWAYS present (no null bit). ASCII, max 16. |
| 38  | 4    | offset: referralData | int32 LE. Only if bit1 set. byte[], max 4096. |
| 42  | 4    | offset: referralSource | int32 LE. Only if bit2 set. `HostAddress`. |
| 46+ | var  | variable block   | varint-len-prefixed payloads at the above offsets. |

`HostAddress` = `int16-LE port` + `varString host` (UTF-8, max 256). (FIXED_BLOCK_SIZE=2.)

### Diff vs hyproxy `io/packet/impl/auth/Connect.java` — **THIS IS THE CORE BUG**

hyproxy's `Connect` is built for an OLDER protocol and is structurally wrong:

1. **hyproxy has a `UUID uuid` field and a `String username` field in Connect — the current
   Connect has NEITHER.** hyproxy reads a UUID (16 bytes) immediately after `clientType` (at
   byte 30), then reads **5** offset slots: `username, identityToken, language, referralData,
   referralSource`. The real Connect reads **4** offset slots starting at byte 30:
   `identityToken, language, referralData, referralSource`, with the variable block at byte 46.
2. Because of the extra UUID + extra username offset slot, **every field after `clientType` is
   misaligned**: hyproxy reads 16 bytes of UUID + a username offset where the server put the
   identityToken offset, the language offset, etc. The fixed block is 30 in both, but hyproxy's
   var-block start is `30 + 16(uuid) + 5*4 = 66`, while the server's is `46`. This is why a
   current client completes QUIC/TLS but the proxy fails to decode the first packet.
3. **username/uuid moved.** In the current protocol the client's username + uuid are no longer in
   Connect at all. In AUTHENTICATED mode they come from the validated JWT identity/access-token
   claims (see Handshake). In DEVELOPMENT/OFFLINE mode they arrive later in
   `InsecurePlayerOptions` (id 363: uuid@1, username offset@17, optional skin).
4. **Fix direction:** rewrite hyproxy `Connect` to: nullBits, protocolCrc, protocolBuildNumber,
   clientVersion(20 ASCII), clientType(byte), then 4 LE offset slots (identityToken, language,
   referralData, referralSource), var block at 46. Drop `uuid`/`username` from the packet; obtain
   them from JWT claims in the auth handler (the auth handler ALREADY derives uuid/username from
   claims — `InboundInitialPacketHandler` currently mis-sources them from `connect.getUuid()` /
   `connect.getUsername()`, which must change). Also validate `protocolCrc == 0x4E7F3D14`.
   hyproxy currently also reads `language` with max 128; server max is 16 (cosmetic).

## Auth packets

All four auth packets use the same offset-table scheme (nullBits + N int32-LE slots + var block).
Current server layouts:

- **AuthGrant** (id 11, ToClient, fixed=1, varStart=9, max=49171): nullBits(bit0=authorizationGrant,
  bit1=serverIdentityToken); slot@1 → `authorizationGrant` (String, max 4096); slot@5 →
  `serverIdentityToken` (String, max 8192). Both UTF-8, both nullable.
  → hyproxy `AuthGrant(grant, serverIdentityToken)` — **matches.**
- **AuthToken** (id 12, ToServer, fixed=1, varStart=9, max=49171): nullBits(bit0=accessToken,
  bit1=serverAuthorizationGrant); slot@1 → `accessToken` (String, max 8192); slot@5 →
  `serverAuthorizationGrant` (String, max 4096). → hyproxy reads `accessToken` +
  `serverAuthorizationGrant` — **matches.**
- **ServerAuthToken** (id 13, ToClient, fixed=1, varStart=9, max=32851): nullBits(bit0=serverAccessToken,
  bit1=passwordChallenge); slot@1 → `serverAccessToken` (String, max 8192); slot@5 →
  `passwordChallenge` (**byte[], max 64**). → hyproxy constructs `ServerAuthToken(token, null)`.
  **Field set matches**, but note field2 is the **passwordChallenge byte[]**, not another token —
  hyproxy sends `null` for it (fine; only set when the backend requires a password).
- **ConnectAccept** (id 14, ToClient, fixed=1, varStart=1, max=70): nullBits(bit0=passwordChallenge);
  inline (NOT offset-table) optional `passwordChallenge` byte[] (varint-len, max 64) right after
  the null byte. → hyproxy `ConnectAccept` deserialize/serialize — **matches exactly.**
  (ConnectAccept is part of the DEV flow, sent after InsecurePlayerOptions; not used by the
  authenticated proxy path.)

**Password\* packets (not yet in hyproxy):**
- PasswordResponse (id 15, ToServer): client sends a `hash` byte[] (SHA-256 of challenge+password).
- PasswordAccepted (id 16, ToClient): empty (fixed=0).
- PasswordRejected (id 17, ToClient): new `passwordChallenge` byte[] + `attemptsRemaining` int.

Net: no auth-packet field renames/removals on the authenticated path. AuthGrant/AuthToken/
ServerAuthToken are wire-compatible. Only Connect is broken.

## ClientReferral / transfer

`com.hypixel.hytale.protocol.packets.auth.ClientReferral` (id 18, **ToClient only** — server→client,
fixed=1, varStart=9, max=5141). Offset-table: nullBits(bit0=hostTo, bit1=data) + 2 int32-LE slots:

| off | field   | type | notes |
|-----|---------|------|-------|
| 0   | nullBits | byte | bit0=hostTo present, bit1=data present |
| 1   | offset: hostTo | int32 LE rel to byte 9 | `HostAddress` = int16-LE port + varString host(UTF-8 max 256) |
| 5   | offset: data   | int32 LE rel to byte 9 | byte[], max 4096 — the signed referral blob |
| 9+  | var block |

**Transfer/redirect encoding:** the server tells the client to reconnect elsewhere by sending
ClientReferral with `hostTo` (target host:port) and an opaque signed `data` blob (≤4096 bytes).
The client then opens a fresh connection to `hostTo` and replays that blob back in the **`Connect`
packet's `referralData` field** (with `referralSource` = the address it was referred from). The
new server's `InitialPacketHandler.handle(Connect)` validates: referralData present ⇒
referralSource must be non-null with a non-empty host, and referralData length ≤ 4096, else it
rejects. So the round-trip is: `ServerB→client ClientReferral{hostTo, data}` then
`client→ServerA(hostTo) Connect{referralData=data, referralSource=...}`.

→ hyproxy `impl/ClientReferral` is server→client, serialize-only (`deserialize` throws
UnsupportedOperationException), nullBits + 2 LE offset slots [hostTo, data] + var block. **Matches
the current layout exactly.** The signed-blob construction/validation lives in hyproxy's
`SecretMessageUtil` (referral data is validated in `InboundInitialPacketHandler` against the proxy
secret + uuid) — unchanged by this protocol update. No layout change needed for ClientReferral.

## Handshake sequence

Reconstructed from the server's connection handlers (`InitialPacketHandler`, `HandshakeHandler` /
`AuthenticationPacketHandler`, `PasswordPacketHandler`, `SetupPacketHandler`).

**Who speaks first: the CLIENT.** After QUIC/TLS is established, the server installs an
`InitialPacketHandler` and *waits*. It does NOT send ServerInfo / ConnectAccept /
RequestInsecurePlayerOptions first. The client must send `Connect` (id 0) as the first application
packet. (`InitialPacketHandler.disconnect` even silently drops the connection if it closes before
any Connect arrives.) **hyproxy's assumption that the client speaks first with Connect is CORRECT.**

`InitialPacketHandler` accepts only ids {0 Connect, 1 ClientDisconnect, 363 InsecurePlayerOptions};
anything else → "unexpectedPacket" disconnect.

### Authenticated flow (what the proxy runs)

```
QUIC/TLS established
  client → server : Connect (id 0)          [client speaks first]
    server validates protocolCrc == 0x4E7F3D14 (1316766548)
    server: AuthMode == AUTHENTICATED && identityToken present
            → hand off to AuthenticationPacketHandler (extends HandshakeHandler)
    server validates identityToken JWT (subject/username/scope: hytale:client|hytale:editor)
  server → client : AuthGrant (id 11)        {authorizationGrant, serverIdentityToken}
  client → server : AuthToken (id 12)        {accessToken, serverAuthorizationGrant}
    server validates access-token JWT (uuid+username must match identity claims);
    requires serverAuthorizationGrant (mutual auth) else disconnect;
    server exchanges the grant for a serverAccessToken via the session service
  server → client : ServerAuthToken (id 13)  {serverAccessToken, optional passwordChallenge}
    → hand off to PasswordPacketHandler
  [if passwordChallenge present:]
    client → server : PasswordResponse (id 15){hash}
    server → client : PasswordAccepted (16)  | PasswordRejected (17){newChallenge, attemptsLeft}
  [else / on accept:] → hand off to SetupPacketHandler  (world setup begins)
```

This is **exactly the flow hyproxy implements** (`InboundInitialPacketHandler` →
`InboundAuthPacketHandler`: Connect → AuthGrant → AuthToken → ServerAuthToken → forwarding).
**The handshake ORDERING does not need to change.** What is broken is purely the **Connect packet
decode** (see Connect layout) and where the proxy sources uuid/username:

- hyproxy `InboundInitialPacketHandler.handle(Connect)` reads `connect.getUuid()` /
  `connect.getUsername()` — these fields don't exist in the current Connect. After fixing
  Connect, the proxy must take uuid/username from the JWT identity-token claims (it already
  validates them in `InboundAuthPacketHandler.activated()`), NOT from the Connect packet.
- The proxy never enters the dev/offline branch (no RequestInsecurePlayerOptions /
  InsecurePlayerOptions / ConnectAccept on the authenticated path), so those packets are not
  required for the core join, though decoders for Ping/Pong and Password* may be added for
  robustness/password-protected backends.

### Development / offline flow (NOT used by the proxy, for reference)

```
  client → server : Connect (id 0)           (no identityToken, or AuthMode != AUTHENTICATED)
  server → client : RequestInsecurePlayerOptions (id 364)
  client → server : InsecurePlayerOptions (id 363){uuid, username, optional skin}
  server → client : ConnectAccept (id 14){optional passwordChallenge}
    → PasswordPacketHandler (same Password* sub-flow) → SetupPacketHandler
```
This is where uuid + username live when there is no auth (they were REMOVED from Connect and put
into InsecurePlayerOptions). Confirms the Connect-layout change is intentional, not a decode
artifact.

## Live byte captures

(Deferred to Task 4 — raw-byte logging + live handshake capture. Static RE above is sufficient to
drive the Connect-layout fix.)
