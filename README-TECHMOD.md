# techmod: API Sample Mod and Gap List

This repository contains three mods in one jar, with three `@Mod` entry points:

- `oml_testmod` (`oml.testmod`): the end-to-end verification mod; it covers loader capabilities one by one in
  the development loop.
- `techmod` (`oml.techmod`): the API sample mod. It deliberately uses APIs the way a real mod would, with a
  material chain, to expose gaps in `oml-api`. This file tracks those gaps.
- `oml_playmod` (`oml.playmod`): the playable mod; see the E2E section in the repository README.

## Sample rules

1. Use only `oml-api`; do not use the `platform` escape hatch.
2. When an API gap appears, leave a `Missing:` comment at the declaration as a code anchor and add the gap to
   this file. Do not work around it in the mod; a workaround defeats the sample.

## M1 scope (implemented)

Two material chains, copper and tin:

| Content | Declaration | Notes |
|---|---|---|
| `copper_ore` / `tin_ore` | `declareBlock` | Stone-tier hardness; drops require the correct tool |
| `raw_copper` / `raw_tin` | `declareItem` | Ore mining output |
| `copper_ingot` / `tin_ingot` | `declareItem` + `declareSmelting` | Smelting recipes use the data pack path |
| `*_dust` / `*_plate` | `declareItem` | Intermediate items produced by the press |

Ore drops use `declareBlockDrop` and the data pack loot table.

## Gap list and remaining limitations

> Zero escape-hatch audit (2026-10-24, M5): both reference mods now access versioned game objects only through
> typed `api26_3` accessors (`OMLCommandSource.player`; `serverLevel`, `stepper`, and `hitBy` on behavior
> payloads). Neither mod uses `platform`. Normal type narrowing on a typed payload (`LivingEntity` to
> `ServerPlayer`) is not an escape hatch.

No `Missing:` anchors remain as of 2026-10-24. The press now covers raw ore → dust and ingot → plate
through the declarative container API; the previous manual/machine-recipe gap is closed.

| Gap | Impact | Code anchor |
|---|---|---|
| Worldgen API (resolved, M2) | `generateAsOre` landed (M2 / T-2.3); copper and tin generate naturally. | `generateAsOre` in `TechMod.kt` |
| BlockEntity / tick logic (resolved, M2) | `blockEntity { tick { } }` landed (M2 / T-2.2); press progress persists. | `blockEntity` in `TechMod.kt` |
| **Custom block behavior** (remaining limitation) | Declarative behavior hooks (`onStepOn` / `onHit`) landed (M2 / T-2.1). A fully custom block class still uses the `platform` escape hatch. | `ContentRegistry` class documentation |

## Run

The three mods share one jar. Use the development loop in the repository README:

```bash
# Publish once in OhMyLoader/ and OhMyLoaderGradle/
(cd ../OhMyLoader && ./gradlew publishToMavenLocal)
(cd ../OhMyLoaderGradle && ./gradlew publishToMavenLocal)
# Then in this repository:
./gradlew runClient     # or runServer
```

In game, use commands such as `/give @s techmod:copper_ingot` to obtain items; `/setblock` places ore blocks
for texture and drop checks.
