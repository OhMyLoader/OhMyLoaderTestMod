package oml.techmod

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import org.ohmyloader.api.Mod
import org.ohmyloader.api.ModContext
import org.ohmyloader.api.OMLModInitializer
import org.ohmyloader.api.command.OMLArgumentType
import org.ohmyloader.api.command.OMLCommandProvider
import org.ohmyloader.api.command.OMLCommandRegistry
import org.ohmyloader.api.content.ContentRegistry
import org.ohmyloader.api.content.OMLContentProvider
import org.ohmyloader.api.content.OMLDataItemStack
import org.ohmyloader.api26_3.blockDataAt
import org.ohmyloader.api26_3.giveItem
import org.ohmyloader.api26_3.itemIn
import org.ohmyloader.api26_3.itemId
import org.ohmyloader.api26_3.remove
import org.ohmyloader.api26_3.sendSystemMessage

/**
 * The reference technology mod: the sample that has to stay inside the official API surface — the
 * version-independent `oml-api` plus the version-facing `oml-api-26_3` extensions — never the
 * `platform` escape hatch.
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
                hardness = 3.0f
                blastResistance = 3.0f
                dropsRequireCorrectTool = true
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

        // Plate: ingot pressed — the press machine's production path runs on OMLBlockData item stacks
        for (plate in PLATE_ITEMS) {
            registry.declareItem(plate)
        }

        // A second pack joining one tag: the game reads a single file per tag, so this member has to reach the
        // group `oml_testmod` opened, which is what WorldProbe measures.
        registry.declareDamageType(id = "crush", messageId = "techmod.crushes")
        registry.declareTag("minecraft:damage_type", "oml_testmod:melting_causes") { element("crush") }

        // The press exercises the BlockEntity API: its tick belongs to the block rather than to a
        // global SERVER_TICK handler, and its progress, input and output persist in OMLBlockData with
        // the world. The production path is the machine's own: hit the press holding an ingot to load
        // it, the tick presses, hit it again with an empty hand to take the plate.
        registry.declareBlock("press") {
            hardness = 3.5f
            blastResistance = 6.0f
            dropsRequireCorrectTool = true
            blockEntity {
                tick { event ->
                    val input = event.data.getItemStack("input") ?: return@tick
                    val progress = event.data.getInt("progress") + 1
                    if (progress >= PRESS_TICKS_PER_CYCLE) {
                        val plate = input.itemId.replace("_ingot", "_plate")
                        val existing = event.data.getItemStack("output")
                        event.data.putItemStack(
                            "output",
                            if (existing?.itemId == plate) OMLDataItemStack(plate, existing.count + 1)
                            else OMLDataItemStack(plate, 1),
                        )
                        event.data.remove("input")
                        event.data.putInt("progress", 0)
                        println("[techmod] press at ${event.x}/${event.y}/${event.z}: pressed a $plate")
                    } else {
                        event.data.putInt("progress", progress)
                    }
                }
            }
            onHit { event ->
                if (event.isClient) return@onHit
                val player = event.player as? Player ?: return@onHit
                val level = event.level as? Level ?: return@onHit
                val data = level.blockDataAt(BlockPos(event.x, event.y, event.z)) ?: return@onHit
                val output = data.getItemStack("output")
                if (output != null) {
                    player.giveItem(output.itemId, output.count)
                    data.remove("output")
                    player.sendSystemMessage("[techmod] press yields ${output.count}x ${output.itemId}")
                    return@onHit
                }
                if (data.getItemStack("input") != null) {
                    player.sendSystemMessage("[techmod] press is already pressing")
                    return@onHit
                }
                val held = player.itemIn("mainhand")?.itemId ?: return@onHit
                val ingot = INGOT_ITEMS.firstOrNull { "techmod:$it" == held } ?: return@onHit
                player.inventory.remove(held, 1)
                data.putItemStack("input", OMLDataItemStack(held, 1))
                data.putInt("progress", 0)
                player.sendSystemMessage("[techmod] press loaded with $ingot")
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
            argument("loud", OMLArgumentType.BOOLEAN) { loudArg ->
                executes { source ->
                    val prefix = context.config.getString("ping_prefix")
                    val loud = source[loudArg] || context.config.getBoolean("loud_by_default")
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
