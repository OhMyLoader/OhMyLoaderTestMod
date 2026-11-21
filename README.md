# OhMyLoader Test Mod

The end-to-end verification mod for OhMyLoader: an external mod project that consumes the loader
through the Gradle plugin and exercises the full consumption path. If this project builds and runs,
an external mod project can too.

The jar carries three mods:

- `oml_testmod` — the E2E probes. Inert in a normal run; they run only in E2E mode (`-Doml.e2e=1`).
- `techmod` — an API sample in the shape of a real mod, exposing gaps in `oml-api-core`
  ([README-TECHMOD.md](README-TECHMOD.md)).
- `oml_playmod` — a playable mod that uses the API the way a mod author would; no assertions.

```bash
# After publishing the loader and the plugin to the local Maven repository:
./gradlew build
./gradlew runClient
./gradlew runServer
# The E2E gates (CI runs these on Linux):
./scripts/e2e-server.sh
DISPLAY=:0 ./scripts/e2e-client.sh
./scripts/e2e-multiplayer.sh
```

## License

Licensed under AGPL-3.0.
