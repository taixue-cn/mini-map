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

Limits: the live provider is deterministic test data. Full BlueMap rendering, MinIO/private provider integration and large-server load/compression benchmarks are not proved by these checks. The client remains pinned to Xaero World Map 1.47.1, surface tiles and at most 1024 detailed tiles per view.

Server/provider integrations may launch a real client without the assertion fixture by setting `MINIMAP_TEST_PRESENCE_ONLY=true` for `scripts/native-client.py`. It requires a fresh game directory, sets first-start/low-distance options only there, omits the fixture mod and joins `MINIMAP_TEST_SERVER` through Minecraft's quick-play argument. The caller owns assertions and shutdown; this mode produces no MiniMap/Xaero PASS report and cannot substitute for the native transfer checks above. Ordinary fixture mode remains the default. Resource processing declares the packaged version as an input, so a reused build directory cannot silently retain an earlier plugin/mod version.
