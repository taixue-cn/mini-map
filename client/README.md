# MiniMap Client

Optional Fabric companion to [MiniMap Server](https://github.com/taixue-cn/mini-map). Downloads pre-rendered maps through Minecraft plugin messages and imports their color, height and light into Xaero World Map's native surface tiles.

## Install

Minecraft **26.3**, Java **25**, Fabric Loader **0.20.1+**, Fabric API and Fabric Language Kotlin 1.14.1+kotlin.2.4.20 or newer, **Xaero World Map 1.47.1** and its XaeroLib dependency. Put `mini-map-client-*.jar` in `mods/`. Xaero's Minimap is optional and uses the normal World Map integration.

Without a compatible Xaero World Map, the addon stays inactive. Without a server handshake, Xaero keeps its normal behavior. No public web map URL, CDN access or account login is used.

After entering a supported world, press **M**. The full-screen map downloads detailed tiles near the visible center. Panning changes the requested area; closing stops requests. A ten-second recheck compares hashes and updates changed/deleted tiles. The provider must already have rendered that area; this mod does not generate missing server maps.

Chunks currently loaded by Minecraft keep Xaero's native exploration. Server samples fill unloaded chunks, and missing server tiles clear only imported pixels. Unchanged responses reuse the decoded snapshot without rebuilding native textures.

## Cache identity and data

The server supplies a stable server ID, world ID and epoch. The addon hashes length-delimited IDs into safe paths, so different entry addresses reuse the same cache and equally named dimensions/worlds remain separate. `mini-map-cache/` stores verified, compressed canonical tiles, capped at approximately **512 MiB** with periodic eviction. Xaero's native cache uses a server-derived root and world/epoch sub-map after handshake; existing address-based caches are not migrated or deleted.

Colors remain exact with this public protocol. Heights and block light use separate lossless planes. No lossy image processing is applied to metadata. A private provider may choose its own color image pipeline before returning samples.

World/session changes reject old responses. The server grants access only while the player is in the world and has permission; downloaded files remain readable locally afterward. Already delivered data cannot be revoked.

## Current limits

- Xaero integration is deliberately pinned to **1.47.1**; unsupported versions disable synchronization before applying mixins.
- Surface maps only. Cave layers keep normal Xaero behavior.
- Detailed requests cover at most **32×32 tiles** of 64×64 blocks around the view center. There is no protocol LOD yet; at extreme zoom-out, pan to fetch other areas.
- Portable samples are restored into native tiles while the map is open. Native texture caches are rebuildable; placeholder block states are not saved as authoritative terrain. Downloaded pixels are not Minecraft blocks.
- Data freshness follows BlueMap's committed render plus the polling interval. This is not a live block-update stream.

## Development

Install JDK 25 and run `./gradlew build`. The distributable jar is in `client/build/libs/`. The server and client compile the same protocol source under `shared/src`; there is no copied protocol snapshot or separate Fabric module. `./gradlew :client:fixtureJar` builds a separate test addon, excluded from releases.

`scripts/native-client.py` launches the **distributed** Fabric jars against an isolated server created by MiniMap Server's smoke script. It uses the local Gradle Minecraft/assets cache, a display or Xvfb, and these environment variables:

```sh
MINIMAP_TEST_SERVER=127.0.0.1:25565
MINIMAP_TEST_LIBRARIES=/tmp/minimap-test-libraries
MINIMAP_TEST_DIRECTORY=/tmp/minimap-native-client
MINIMAP_TEST_REPORT=/tmp/minimap-native-client/report.json
MINIMAP_TEST_JAVA=/path/to/java25/bin/java
```

`MINIMAP_TEST_MODE=fallback` checks normal multiplayer without handshake; `MINIMAP_TEST_OMIT_XAERO=1` also omits Xaero dependencies. Use a fresh client directory per test.

`MINIMAP_TEST_MODE=lifecycle` selects the file-driven native fixture. `MINIMAP_TEST_SCENARIO_DIR` must contain an
`.mini-map-lifecycle` ownership marker. The external test harness atomically writes `client-command.json` with an increasing
`sequence` and one of `idle`, `tile`, `missing`, `revoked`, `world` or `finish`. It waits for `client-observed.json` before changing
server data or permissions, then for the matching `client-ack.json`. Assertions use tile `(10,10)` outside vanilla's loaded
chunks, all 4096 native texture pixels and canonical/local-cache digests. The separate fixture Mixin observes actual protocol
messages without replacing payloads or networking. These controls are excluded from release jars.

`main` pushes and pull requests run builds/tests. Pushing a `v*` tag publishes the jar and SHA-256 checksums through GitHub Actions. Xaero jars are external dependencies and are not redistributed in this project.
