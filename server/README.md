# MiniMap Server

Paper plugin for sending pre-rendered surface maps to [MiniMap Client](https://github.com/taixue-cn/mini-map) through the Minecraft connection. Public implementation; private object storage belongs behind the provider interface.

## Compatibility and installation

- Paper **1.21.4 / Java 21** and **26.3 / Java 25**.
- Client: Fabric for Minecraft **26.3**, Xaero World Map **1.47.1**, MiniMap Client. Xaero's Minimap can use its normal World Map integration.
- The server needs BlueMap with completed low-resolution tiles, or another `MapProvider`. MiniMap Server does not generate chunks or start BlueMap renders.

Download `mini-map-server-*.jar` from Actions or Releases and put it in `plugins/`. Start once to create `plugins/MiniMapServer/config.yml`. Do **not** put the provider API SDK jar in `plugins/`.

Keep `server-id` identical across entry addresses for the same logical server. Set `worlds.<name>.id` explicitly when several backends represent the same logical world. Increase `epoch` after restoring/resetting a world; this starts a fresh client cache. Never reuse an identity for unrelated worlds.

```yaml
server-id: architecture
bluemap-maps:
  summer-palace: summer-palace-surface
worlds:
  summer-palace:
    enabled: true
    id: summer-palace
    epoch: '1'
    permission: minimap.world.summer-palace
```

`minimap.use` defaults to true. An additional world permission is optional. Requests are authorized against the **connection player's current world** on receipt, after asynchronous reads and before sending. Active sessions must still match the current provider instance, map, world UUID, cache identity/epoch and dimension. Moving an available map to another world revokes the original world's session. Changing worlds, losing permission, closing the map, or disconnecting cancels pending work. There is no world list or client-selected world endpoint. Downloaded data remains on the player's computer; permission revocation cannot recall already delivered maps.

## Providers and custom BlueMap storage

The built-in adapter selects a BlueMap map by Bukkit world save folder. With multiple BlueMap maps for one world, configure `bluemap-maps` explicitly. It reads standard low-resolution PNG samples through **BlueMap Core's storage interface**, not paths under `web/`. It extracts color, signed height and block light, then resamples the tile boundaries to canonical 64×64 block tiles. Neither SQL nor Redis nor a public HTTP endpoint is required.

BlueMap's public API does **not** guarantee this raster representation. The built-in adapter uses version-sensitive Core methods; incompatible versions/formats fail closed. A private BlueMap fork with a changed PNG format should register a custom provider that reads logical samples *before* its private encoder, or decodes its format internally. Merely using MinIO while preserving the Core interface and standard samples needs no public MiniMap change.

Kotlin is the primary implementation language. Compile your bridge against the matching `mini-map-provider-api-<version>.jar` as **compileOnly**, declare `depend: [MiniMapServer]`, then register:

```kotlin
server.servicesManager.register(
    MapProvider::class.java, myProvider, this, ServicePriority.High,
)
```

Provider plugins should declare `softdepend: [MiniMapServer]` (or a hard dependency if they require it). MiniMap Server loads before BlueMap; its reflective built-in adapter is resolved lazily, so custom BlueMap providers can depend on the public API without a circular load order.

Implement `resolve(WorldRef)` with cached metadata on the main thread. Implement `read(MapRef, tileX, tileZ)` with committed snapshots on a bounded storage worker. Samples use `index = z * 64 + x`; ARGB alpha zero means missing data; height is a signed 16-bit block Y; light is 0–15. Empty means deleted/absent. Providers are tried in Bukkit service priority order, followed by BlueMap. An authoritative provider stops fallback even when its world is disabled. Implement `available(MapRef)` from a cached policy snapshot to revoke existing sessions before the next send, and `cacheId` for stable logical identity across mounts. The same public server jar is used with custom providers; no private MiniMap fork is necessary.

## Transfer and resource limits

Successful handshake establishes a per-world session. Opening the full-screen map requests only the view. SHA-256 covers canonical uncompressed samples. Height deltas and packed light values are lossless; Deflate level 3 is used only if it saves bytes. The client verifies sizes, decompression termination and hash before replacing its cache. Unchanged tiles receive a small status reply; deleted tiles are evicted. Defaults: 128 KiB/s/player, 4 MiB/s globally, four requests in flight per player, 16 KiB fragments, bounded worker queue. Shared tile snapshots expire after two seconds; the client rechecks visible tiles every ten seconds. This is eventual consistency with BlueMap's last committed render.

No guaranteed compression ratio: flat colors compress well, detailed colors less so. Minecraft may also compress packets. A real deployment's wire savings should be measured before adding another codec.

## Build, test, release

Install JDK 25 and run `./gradlew build`. The plugin and SDK target Java 21. `./gradlew :server:fixtureJar` builds a test-only provider, excluded from releases.

```sh
python3 scripts/paper-smoke.py --version 1.21.4 --java /path/to/java21/bin/java --directory /tmp/minimap-paper-test --accept-eula --fixture
```

Use fresh directories. The test binds loopback and generates an isolated flat world. Read the Minecraft EULA before accepting. `--hold` keeps a test server running for a native client test until `<directory>/stop` exists.

For the real standard BlueMap path, `scripts/bluemap-native.py --accept-eula --bluemap-jar /path/to/bluemap-5.29-paper.jar --java /path/to/java25 --xvfb /path/to/Xvfb` creates its own marked loopback world, renders saved blocks with upstream file storage and runs the distributed Fabric/Xaero client through two entry addresses. It pins the upstream jar's release digest; optional `--resources-jar` reuses an existing Mojang 26.3 client resource jar and `--reuse-runtime` reads an existing Paper cache. Resource files remain private and are not included in evidence bundles. The fixture does not register a replacement provider or connect to PostgreSQL/MinIO. See [the current validation record](../VALIDATION.md#actual-upstream-bluemap-render--2026-10-24).

`main` pushes and pull requests run the build. Pushing a `v*` tag builds and publishes GitHub Release jars plus SHA-256 checksums. The two modules compile one shared protocol source tree.
