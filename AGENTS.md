# AGENTS.md — OhMyLoaderTestMod

Read [CONTRIBUTING.md](CONTRIBUTING.md) first. It defines the build order, E2E gates, verdict evidence,
style, commit and pull-request rules, security, and licensing. This file adds only agent-specific
constraints.

- Work only in this repository. Consume the loader and plugin through Maven coordinates; do not add
  sibling source paths, shared catalogs, `includeBuild`, or source mounts.
- Publish the sibling loader and plugin to mavenLocal before building. Keep the version pins aligned.
- Run `./gradlew build` for every probe or verdict change. Run the relevant gate:
  `./scripts/e2e-server.sh`, `./scripts/e2e-client.sh`, or `./scripts/e2e-multiplayer.sh`.
- Keep E2E probes inert outside E2E mode. They mutate the world and player; a normal run must not.
- Do not weaken verdicts, self-tests, timeouts, grace windows, or the real-run evidence requirement.
- Keep the client and server expectation sets separate; a remote client cannot satisfy
  integrated-server-only checks.
- Attach both `e2e-context` and `e2e-result` files when reporting a failure. A green job without a verdict
  is not evidence.
- Do not commit `build/`, `run/`, screenshots, or generated evidence.
- Commit in this repository. Do not push, tag, or release unless explicitly asked. This repository has no
  release artifact.
