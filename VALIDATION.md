# Kotlin monorepo validation

The former server and client validation reports are retained in `server/VALIDATION.md` and `client/VALIDATION.md` as historical evidence for the Java alpha. They do not prove the Kotlin migration; migration checks are recorded below as they finish.

The migration must preserve v1 bytes and cache identity, run the Kotlin protocol/cache/session/provider tests, build fixture jars, boot Paper 1.21.4 and 26.3, and exercise native Xaero color/height/light updates and deletion. The no-Xaero and no-server paths must remain ordinary multiplayer connections.

BlueMap/MinIO integration belongs to the separate private `blue-map` work and is not covered by a deterministic MapProvider fixture.
