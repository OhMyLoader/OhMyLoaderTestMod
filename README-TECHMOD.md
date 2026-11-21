# techmod: API Sample Mod and Gap List

`techmod` (`oml.techmod`) is the API sample mod in the test-mod jar. It deliberately uses
`oml-api-core` the way a real mod would, with a material chain, to expose gaps in the API. This
file tracks those gaps.

Sample rules:

1. Use only `oml-api-core`; do not use the `platform` escape hatch.
2. When an API gap appears, leave a `Missing:` comment at the declaration as a code anchor and add
   the gap to this file. Do not work around it in the mod; a workaround defeats the sample.

## What it declares

Two material chains, copper and tin:

| Content | Declaration | Notes |
|---|---|---|
| `copper_ore` / `tin_ore` | `declareBlock` | Stone-tier hardness; drops require the correct tool |
| `raw_copper` / `raw_tin` | `declareItem` | Ore mining output |
| `copper_ingot` / `tin_ingot` | `declareItem` + `declareSmelting` | Smelting recipes use the data pack path |
| `*_dust` / `*_plate` | `declareItem` | Intermediate items produced by the press |

Ore drops use `declareBlockDrop` and the data pack loot table; the press is a declarative block
entity whose progress persists.

## Gap list

| Gap | Impact | Code anchor |
|---|---|---|
| **Custom block behavior** | A fully custom block class still uses the `platform` escape hatch; declarative behavior hooks (`onStepOn` / `onHit`) cover the rest. | `ContentRegistry` class documentation |

Run with the development loop in the repository README. In game, `/give @s techmod:copper_ingot`
obtains items; `/setblock` places ore blocks for texture and drop checks.
