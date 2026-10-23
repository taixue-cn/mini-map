# MiniMap

Optional server-provided maps for Xaero World Map. One Kotlin repository, two Gradle modules:

- `server`: Paper plugin, provider API, server transport and tests. Paper **1.21.4 / Java 21** and **26.3 / Java 25**.
- `client`: Fabric client mod, local cache, Xaero integration and tests. Minecraft **26.3 / Java 25**, Xaero World Map **1.47.1**.

`shared/src` is compiled into both modules; protocol source has one owner and no duplicated copy. Java is limited to three Sponge Mixin/accessor classes. Kotlin and Kotlin DSL use the official IntelliJ Kotlin style, enforced by Spotless/ktlint and `.editorconfig`.

## Install

Download the server and client jars from this repository's Actions or Releases. Put `mini-map-server` in Paper's `plugins`, and `mini-map-client` in the Fabric client's `mods`, together with Fabric API, **Fabric Language Kotlin 1.14.1+kotlin.2.4.20 or newer**, and supported Xaero World Map. The provider SDK is for developers; do not install it as a plugin. The Paper jar includes its Kotlin runtime.

The protocol activates after both sides handshake. An absent plugin, absent mod or unsupported Xaero installation leaves normal gameplay available. Only the player's current world can be requested; opening the full-screen map requests visible tiles. Closing the map or changing worlds drops pending responses. Downloaded maps remain in the player's local cache.

See [server setup and provider API](server/README.md) and [client behavior](client/README.md).

## Build and release

Use JDK 25:

```sh
./gradlew spotlessCheck build
./gradlew :server:fixtureJar :client:fixtureJar
```

`./gradlew spotlessApply` formats Kotlin and Kotlin DSL. `kotlin.code.style=official` configures IDE defaults. Comments describe trust boundaries, threading and version-sensitive integration.

`main` pushes and pull requests build both modules and smoke-test Paper 1.21.4 and 26.3. A `v*` tag publishes both jars, the provider SDK and SHA-256 checksums through GitHub Actions Release. Test fixture jars are excluded from releases.

Both former repository histories are retained as ancestors of this monorepo. The v1 wire format and cache hashes remain compatible with the previously released Java alpha. Build paths and provider SDK distribution now belong here.

Default branch and active development: `main` in `taixue-cn/mini-map`. The former repositories remain available for release history.
