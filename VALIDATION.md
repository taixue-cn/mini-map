# Native surface refresh regressions — 2026-10-24

An unchanged cached response previously decoded a new snapshot on each refresh, defeating the native import identity check. A regression failed against the previous implementation and passes with the decoded cache entry retained in memory. Native samples now pass height/top-height/block-light in Xaero's actual parameter order; the previous implementation supplied light as top-height and zero as light.

Five additional tests bootstrap Minecraft registries and exercise the real Xaero 1.47.1 MapBlock/MapTile classes without launching a game or server. They check height fields, native writer ownership, unchanged import detection, preservation of locally explored pixels when server terrain is missing, and replacement of changed samples outside loaded Minecraft chunks. The importer skips loaded Minecraft chunks, updates only changed native tiles and registers visits even when samples are unchanged.

`spotlessCheck build -PreleaseVersion=0.2.0-alpha.5` passes: 13 client and 12 server/shared tests, no failures or skips. These are CPU checks; this revision has not yet been validated in a rendered client session against the user's flickering scene. The existing detailed-view size limit and lack of protocol LOD remain. No production server or storage configuration was changed.

# Visible-request scheduling regression — 2026-10-24

The released v1 client could repeatedly refresh early tiles before a larger visible set was filled: its 150 ms request interval crosses the 10-second refresh age after 67 tiles. A new deterministic 128-tile test reproduces the repeated `(0,0)` request when `(3,4)` should be next. The unchanged visible list is rebuilt on each tick, matching GUI behavior.

The client now advances a request cursor after each selected tile and resets it when the viewport changes. The regression verifies all 128 requests before the first refresh, then confirms refresh resumes; a separate pan check verifies the new center receives priority. Full Spotless/build and all JVM checks pass. Wire messages, providers, server transport, native block import and cache identity are unchanged. This fixes scheduling within the existing bounded detailed view; v1's extreme-zoom LOD limitation remains as documented in client/README.md. No production server is restarted or modified by this client change.

# Current-world binding regression — 2026-10-24

Production source `91048a88c41a89fe8d413056e66e7fdbd277c289` passes Spotless/build, 12 server/shared and five client
JVM tests, with no failures/skips. Actions [40071855538](https://github.com/taixue-cn/mini-map/actions/runs/40071855538)
passes build and Paper 1.21.4/26.3 smoke checks. The provider SDK, protocol and production client are unchanged.

The private saved-world acceptance uses two actual Paper worlds, existing PostgreSQL/MinIO and distributed
Fabric/Xaero. The retained old server jar (`6424fad6f7d2f305ea2226facbe80c00aa226ac774fbeda2d68637ff803e4339`)
keeps its original-world session after the map moves elsewhere: the provider resolves no map for that player's
world while the same map remains available. This is an actual session-withdrawal counterexample; it does not claim
that secret destination-world samples were exfiltrated.

The fixed server (`11e8b979738d84e99a437f115e8cfa4448f30299fc5cd7be62094cb1d25e60aa`) revokes that session without
delivering another target tile. Restoring the binding reuses its cache. A second migration changes saved terrain and
epoch while renderer work is pending; the player remains denied until entering the destination. The client then
verifies all 4096 canonical/cache/native GPU samples at height74/light0 with a new cache identity. All ten integrated
phases, normal process exit and ownership-checked test schema/bucket/configuration cleanup pass. Fixture observations
are excluded from release jars; the client receives no storage credentials.

Evidence is retained privately under `blue-map-source-rebinding-20261024`; private runtime source
`7dbae17b77f862bd06beef5414cde922bd0ee2e1`. This covers current-world binding, not deployed website ACLs/IAM or
production load. The earlier lifecycle checks below retain their own source revisions.

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
