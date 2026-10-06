package org.ohmyloader.techmod

import org.ohmyloader.api.Mod
import org.ohmyloader.api.ModContext
import org.ohmyloader.api.OMLModInitializer
import org.ohmyloader.api.command.OMLArgumentType
import org.ohmyloader.api.command.OMLCommandProvider
import org.ohmyloader.api.command.OMLCommandRegistry
import org.ohmyloader.api.content.ContentRegistry
import org.ohmyloader.api.content.OMLContentProvider

/**
 * The reference technology mod: the sample that has to stay entirely inside `oml-api`.
 *
 * Two rules when editing this file: never touch the `platform` escape hatch, and record every API
 * gap met here in README-TECHMOD.md's gap list instead of working around it inside the mod. Parts
 * deliberately beyond what [ContentRegistry] can express carry a `Missing:` comment at their
 * declaration site — that is the gap list's code anchor.
 */
@Mod(
    id = "techmod",
    name = "Tech Mod",
    version = "0.1.0",
    dependencies = ["oml_testmod@>=0.1.0"],
)
class TechMod : OMLModInitializer, OMLContentProvider, OMLCommandProvider {

    companion object {
        // The material chain's ids, in one place; the handles come from materialization.
        val ORE_BLOCKS = listOf("copper_ore", "tin_ore")
        val RAW_ITEMS = listOf("raw_copper", "raw_tin")
        val INGOT_ITEMS = listOf("copper_ingot", "tin_ingot")
        val DUST_ITEMS = listOf("copper_dust", "tin_dust")
        val PLATE_ITEMS = listOf("copper_plate", "tin_plate")

        /** One press cycle, in game ticks (3 seconds). */
        const val PRESS_TICKS_PER_CYCLE = 60
    }

    override fun declareContent(registry: ContentRegistry) {
        // Ore: stone-tier hardness, and it drops nothing without the right tool
        for (ore in ORE_BLOCKS) {
            registry.declareBlock(ore) {
                destroyTime = 3.0f
                explosionResistance = 3.0f
                requiresCorrectToolForDrops = true
                generateAsOre {
                    veinSize = 8
                    perChunk = 6
                    minY = 16
                    maxY = 64
                }
            }
        }
        // Mining an ore yields its raw form — a loot-table override, answered through the injected pack
        registry.declareBlockDrop("copper_ore", "raw_copper")
        registry.declareBlockDrop("tin_ore", "raw_tin")

        // Raw: what mining produces
        for (raw in RAW_ITEMS) {
            registry.declareItem(raw)
        }

        // Ingot: raw smelted in a furnace — a recipe JSON through the injected pack
        for ([raw, ingot] in RAW_ITEMS.zip(INGOT_ITEMS)) {
            registry.declareItem(ingot)
            registry.declareSmelting(input = raw, result = ingot, experience = 0.7)
        }

        // Dust: raw crushed. Missing: manual recipe / crushing machine — only the machine produces it
        for (dust in DUST_ITEMS) {
            registry.declareItem(dust)
        }

        // Plate: ingot pressed. Missing: pressing recipe — the machine exists, taking its input does not
        for (plate in PLATE_ITEMS) {
            registry.declareItem(plate)
        }

        // The press exercises the BlockEntity API: its tick belongs to the block rather than to a
        // global SERVER_TICK handler, and its progress persists in OMLBlockData with the world.
        registry.declareBlock("press") {
            destroyTime = 3.5f
            explosionResistance = 6.0f
            requiresCorrectToolForDrops = true
            blockEntity {
                tick { event ->
                    val progress = event.data.getInt("progress") + 1
                    if (progress >= PRESS_TICKS_PER_CYCLE) {
                        event.data.putInt("progress", 0)
                        println("[techmod] press at ${event.x}/${event.y}/${event.z}: cycle complete")
                    } else {
                        event.data.putInt("progress", progress)
                    }
                }
            }
        }
        registry.declareShapedCrafting(
            result = "press",
            pattern = listOf("III", "I I", "III"),
            key = mapOf('I' to "minecraft:iron_ingot"),
        )
    }

    override fun declareCommands(commands: OMLCommandRegistry) {
        // Root command and a typed argument — the two shapes the command API exposes.
        commands.register("techmod_ping") {
            executes { source ->
                val prefix = context.config.getString("ping_prefix")
                println("[techmod] $prefix (executed by ${source.name})")
            }
            argument("loud", OMLArgumentType.BOOLEAN) {
                executes { source ->
                    val prefix = context.config.getString("ping_prefix")
                    val loud = source.getBoolean("loud") || context.config.getBoolean("loud_by_default")
                    val message = if (loud) prefix.uppercase() + "!!" else prefix
                    println("[techmod] $message (executed by ${source.name})")
                }
            }
        }
    }

    private lateinit var context: ModContext

    override fun onInitialize(context: ModContext) {
        this.context = context
        // Declared entries: config/techmod.toml is generated on the first read, the command reads them.
        context.config.define("ping_prefix", "pong", "Prefix of the /techmod_ping reply")
        context.config.define("loud_by_default", false, "Whether /techmod_ping shouts by default")
        println(
            "[techmod] init: id=${context.id} version=${context.version}, " +
                "${ORE_BLOCKS.size} ores + ${RAW_ITEMS.size + INGOT_ITEMS.size + DUST_ITEMS.size + PLATE_ITEMS.size} items declared",
        )

        println(
            "[techmod] machine: press block entity declared — tick is bound to the block, " +
                "one cycle = $PRESS_TICKS_PER_CYCLE server ticks",
        )
    }
}
