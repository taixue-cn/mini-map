# Validation (2026-10-23 UTC)

All tests use fresh loopback servers and fresh client directories, never production worlds.

| Check | Result |
| --- | --- |
| Paper 1.21.4 build 232, Java 21: production plugin + SPI fixture enable | PASS |
| Paper 26.3 build 218, Java 25: production plugin + SPI fixture enable | PASS |
| Fabric 26.3, Loader 0.20.1, Xaero World Map 1.47.1: remote tile import | PASS |
| Native Xaero texture upload and RGB comparison outside vanilla loaded chunks | PASS |
| Native color/height/light incremental replacement followed by transparent deletion | PASS |
| Addon installed without Xaero: normal multiplayer, sync inactive | PASS |
| Xaero + addon against server without MiniMap Server: normal multiplayer | PASS |
| Canonical codec, corrupt/bounded frames, fragmentation, token bucket | PASS |
| Conditional cache requests, corruption recovery, eviction, old world/closed-map responses | PASS |
| Standard BlueMap lowres PNG: signed heights, light, negative coordinates, 500-pixel boundaries | PASS |

The live network tests use a deterministic `MapProvider` fixture. BlueMap 5.29's relevant Core signatures and PNG layout were checked against its source/distribution; a full production BlueMap render and a private MinIO implementation were **not** exercised. The private storage integration needs a separate provider and deployment test.

The client is pinned to Xaero World Map 1.47.1, supports surface tiles only, and requests at most 1024 detailed tiles per view. No large-server load/latency benchmark or compression-ratio guarantee is claimed.
