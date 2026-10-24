# Kotlin monorepo validation — 2026-10-24

All live checks used new loopback server/client directories under `/tmp/minimap-kotlin`, with production worlds untouched.

| Check | Evidence/result |
| --- | --- |
| Official Kotlin style and Kotlin DSL | Spotless/ktlint checks pass; `kotlin.code.style=official` and `.editorconfig` |
| Clean build, Kotlin tests | 10 server/shared tests + 5 client tests, zero failures |
| v1 wire compatibility | Fixed bytes for all eight message types generated independently by the released Java encoder; Kotlin matches |
| Existing tile hashes | Golden canonical sample hash matches the released Java codec |
| Server distribution | JVM class version 65 (Java 21), Kotlin runtime included, fixture classes excluded |
| Client distribution | JVM class version 69 (Java 25), Fabric Language Kotlin 1.14.1+kotlin.2.4.20 tested, fixtures excluded |
| Paper 1.21.4 build 232, Java 21 | Plugin and provider fixture enable successfully |
| Paper 26.3 build 218, Java 25 | Plugin and provider fixture enable successfully |
| Paper 1.21.4 + ViaVersion 5.12.1, Fabric 26.3 | Native Xaero remote color/height/light import and texture RGB comparison pass |
| Paper 26.3, Fabric 26.3, Xaero World Map 1.47.1 | Initial remote tile, incremental color/height/light update and transparent deletion pass |
| Client addon without Xaero | Ordinary multiplayer connection, no MiniMap session, PASS |
| Xaero + addon without server plugin | Ordinary multiplayer connection, no MiniMap session, PASS |
| GitHub Actions main build | Build and both Paper smoke jobs pass, run 39989498866 |

Native reports: `client-1214/report.json`, `client-updates/report.json`, `client-no-xaero/report.json`, `client-no-server/report.json` under the temporary validation root. Tests check a tile outside vanilla's loaded chunks and native uploaded texture buffers, not merely a Java method invocation.

`server/VALIDATION.md` and `client/VALIDATION.md` retain historical Java-alpha results; the table above independently covers the Kotlin migration. Both original Git histories remain ancestors of this repository.

Limits: the table above uses a deterministic provider. The separate checks below cover the built-in upstream BlueMap adapter and actual committed MinIO-backed provider integration. Production IAM/website bindings and large-server load/compression benchmarks remain unproved. The client remains pinned to Xaero World Map 1.47.1, surface tiles and at most 1024 detailed tiles per view.

Server/provider integrations may launch a real client without the assertion fixture by setting `MINIMAP_TEST_PRESENCE_ONLY=true` for `scripts/native-client.py`. It requires a fresh game directory, sets first-start/low-distance options only there, omits the fixture mod and joins `MINIMAP_TEST_SERVER` through Minecraft's quick-play argument. The caller owns assertions and shutdown; this mode produces no MiniMap/Xaero PASS report and cannot substitute for the native transfer checks above. Ordinary fixture mode remains the default. Resource processing declares the packaged version as an input, so a reused build directory cannot silently retain an earlier plugin/mod version.

## Actual upstream BlueMap render — 2026-10-24

`scripts/bluemap-native.py` passed with the unmodified upstream BlueMap 5.29 Paper jar (SHA-256 `7bf475936c26107d476c63151c76d5b49e865eebeada12c9e7d92155de877b9f`), Paper 26.3 build 218, Fabric 26.3/Loader 0.20.1 and Xaero World Map 1.47.1. The upstream artifact digest was independently checked against its GitHub release metadata.

The separate fixture saved and flushed a sea-lantern plane outside spawn, requested a real upstream render into its fresh file storage and read its gzip/PNG tiles through the production built-in adapter. No alternative `MapProvider` was registered. All 4096 requested samples had height 70 and block light 15; the native client verified the first sample's `ffacc7be` color, numerical data and uploaded texture RGB outside vanilla loaded chunks. Connecting through `127.0.0.1` and `localhost` produced the same native server/world cache IDs and both client reports passed.

Paper 26.3's actual Bukkit save folder and BlueMap's dimension folder matched after normalization, so this test required no production adapter change. The fixture pads its saved source/mask beyond the requested tile to avoid clipping upstream render cells. It is excluded from production jars and only opts in inside a freshly marked private workspace. Website skins/metrics and the public web server are disabled. No PostgreSQL, MinIO, business world or additional storage service is used.

This verifies the standard upstream/file-storage path. The separate committed-provider record below covers the MinIO-backed renderer; neither check establishes deployed permissions.

## Actual committed-provider cache lifecycle — 2026-10-24

The separate native fixture at `d35817a66ab9725926c82d1f7e27cb38c6083648` passed an actual saved-world,
existing-PostgreSQL/MinIO provider integration on Paper 26.3 build 218 and Fabric/Xaero 1.47.1. All 4096 canonical
samples, portable-cache samples and native RGBA pixels matched outside vanilla's loaded chunks. The production
client's 42 classes and server's 1059 classes are byte-identical to the previously verified `c72f96a` distributions;
no private storage format or test controls entered the shipped mods.

Fourteen acknowledged client phases cover no requests before M, no requests after closing, known-hash UNCHANGED
without tile bodies, actual saved-block color/height/light replacement, committed tile deletion and transparent/native
cache removal, restored content, permission revocation during a delayed actual provider read, regrant, an unpublished
world, return and warm cache reuse through a second entry address. No tile arrived after its session was revoked.
Ordinary multiplayer without Xaero also passed with the new fixture Mixin installed.

The initial uniform surface encoded to 22530 canonical bytes and 147 compressed body bytes; this is a fixture
measurement, not a bandwidth promise for complex maps or the framed network total. Hashes remained exact after
decompression. The client received no storage credentials. Ownership-checked test schema/bucket and generated
configurations were removed; no storage service, database or role was created. Production IAM, deployed website
permissions and large-server load/compression benchmarks remain separate gates. Local Spotless/15 JVM tests and
Actions [40068983585](https://github.com/taixue-cn/mini-map/actions/runs/40068983585) passed, including both Paper smoke jobs.
