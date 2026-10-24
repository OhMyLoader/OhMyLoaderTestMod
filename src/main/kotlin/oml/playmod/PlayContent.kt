package oml.playmod

import net.minecraft.core.BlockPos
import org.ohmyloader.api.content.ContentRegistry

/**
 * The whole of what the kit puts into the world, declared once at initialization. Mining, crafting, a machine
 * that keeps score, and a dimension to travel to — the gameplay loop a player actually walks through, on the
 * content track rather than through the game's registration classes.
 */
object PlayContent {

    const val ORE = "lite_ore"
    const val BLOCK = "lite_block"
    const val PAD = "launch_pad"
    const val WAYSTONE = "waystone"
    const val RAW = "raw_lite"
    const val INGOT = "lite_ingot"
    const val SWORD = "lite_sword"
    const val PEARL = "recall_pearl"
    const val KEY = "gallery_key"
    const val DAMAGE_TYPE = "shock"
    const val CLOCK = "gallery_clock"
    const val TIMELINE = "gallery_day"
    const val MARKER_CHIME = "chime"
    const val MARKER_DIM = "dim"
    const val TYPE = "gallery_type"
    const val DIMENSION = "gallery"

    /** The namespace these declarations land in, spelled the way the game keys them. */
    fun id(name: String): String = "oml_playmod:$name"

    /** The gallery's standing pad and waystone, placed once by `/kit gallery build`. */
    const val GALLERY_FLOOR = 4
    val GALLERY_CENTER = BlockPos(0, GALLERY_FLOOR + 1, 0)

    fun declare(registry: ContentRegistry) {
        // --- the mineable chain: ore in the ground, dropped as raw, smelted into an ingot -----------
        registry.declareBlock(ORE) {
            hardness = 2.2f
            blastResistance = 9.0f
            dropsRequireCorrectTool = true
            generateAsOre {
                veinSize = 6
                perChunk = 3
                minY = 8
                maxY = 32
                // Explicit biomes rather than the "every biome" default: an empty list merges the feature into
                // `minecraft:plains` too, which is the biome the kit's own flat dimensions run on — and the
                // verification probes read those dimensions as empty ground. Measured: with the default, a cow
                // teleported into a declared dimension stopped being findable there.
                biomes += listOf("minecraft:forest", "minecraft:desert")
            }
        }
        registry.declareBlockDrop(ORE, RAW, 1, 3)
        registry.declareItem(RAW)
        registry.declareItem(INGOT)
        registry.declareSmelting(input = RAW, result = INGOT, experience = 0.9, cookingTime = 160)
        registry.declareShapelessCrafting(result = RAW, ingredients = listOf(INGOT, INGOT, INGOT))

        // --- the building block: a machine that counts who stepped on it ---------------------------
        registry.declareBlock(BLOCK) {
            hardness = 1.6f
            blastResistance = 12.0f
            dropsRequireCorrectTool = true
            onStepOn { event ->
                if (!event.isClient) PlayMachines.touched(event.level, event.x, event.y, event.z, event.entity)
            }
            onHit { event ->
                if (!event.isClient) PlayMachines.punched(event.level, event.x, event.y, event.z, event.player)
            }
            blockEntity {
                tick { event -> PlayMachines.tickLiteBlock(event) }
            }
        }
        registry.declareShapedCrafting(
            result = BLOCK,
            pattern = listOf("ii", "ii"),
            key = mapOf('i' to INGOT),
        )

        // --- the pad: charge it, step on it, get launched ------------------------------------------
        registry.declareBlock(PAD) {
            hardness = 0.8f
            blastResistance = 4.0f
            onStepOn { event ->
                if (!event.isClient) PlayMachines.launch(event.level, event.x, event.y, event.z, event.entity)
            }
            blockEntity {
                tick { event -> PlayMachines.rearmPad(event) }
            }
        }
        registry.declareBlockDrop(PAD, INGOT)
        registry.declareShapedCrafting(
            result = PAD,
            pattern = listOf("i i", "isi"),
            key = mapOf('i' to INGOT, 's' to "minecraft:slime_block"),
        )

        // --- the waystone: sets the pearl's target, and the player's respawn anchor ----------------
        registry.declareBlock(WAYSTONE) {
            hardness = 3.0f
            blastResistance = 30.0f
            dropsRequireCorrectTool = true
            blockEntity {
                tick { event -> PlayMachines.waystoneHeartbeat(event) }
            }
        }
        registry.declareBlockDrop(WAYSTONE, INGOT, 2, 4)
        registry.declareShapedCrafting(
            result = WAYSTONE,
            pattern = listOf(" i ", "igi", " i "),
            key = mapOf('i' to INGOT, 'g' to "minecraft:glass"),
        )

        // --- the tools: a sword with its own mining rules, a pearl, a gallery key ------------------
        registry.declareItem(SWORD) {
            maxDurability = 320
            attackDamage = 6.5
            attackSpeed = -2.6
            miningSpeed = 7.0f
            minesAndDrops(id(ORE), 5.5f)
            minesAndDrops("minecraft:stone", 3.0f)
            deniesDrops("minecraft:bedrock")
            overrideSpeed("minecraft:dirt", 4.0f)
        }
        registry.declareShapedCrafting(
            result = SWORD,
            pattern = listOf("i", "i", "s"),
            key = mapOf('i' to INGOT, 's' to "minecraft:stick"),
        )
        registry.declareItem(PEARL)
        registry.declareShapelessCrafting(result = PEARL, ingredients = listOf(INGOT, "minecraft:ender_pearl"))
        registry.declareItem(KEY)
        registry.declareShapelessCrafting(result = KEY, ingredients = listOf(INGOT, "minecraft:compass"))

        // --- the data track the reads answer for: a damage type, its clock, its dimension ---------
        registry.declareDamageType(
            id = DAMAGE_TYPE,
            messageId = "oml_playmod.shocks",
            effects = "thorns",
            exhaustion = 0.1f,
        )
        registry.declareClock(CLOCK)
        registry.declareTimeline(TIMELINE) {
            clock = CLOCK
            periodTicks = 6000
            marker(MARKER_CHIME, 0, inCommands = true)
            marker(MARKER_DIM, 3000)
        }
        registry.declareDimensionType(TYPE) {
            skyLight = false
            ceiling = true
            minY = 0
            height = 64
            logicalHeight = 64
            ambientLight = 0.55f
            skybox = "none"
            fixedTime = true
            clock = CLOCK
            timelines += TIMELINE
        }
        registry.declareDimension(DIMENSION) {
            type = TYPE
            generator = "flat"
            layer("minecraft:bedrock", 1)
            layer("minecraft:smooth_stone", GALLERY_FLOOR)
            features = false
            lakes = false
        }

        // --- the groups: the game's own behaviour reads these --------------------------------------
        registry.declareTag("minecraft:block", "minecraft:mineable/pickaxe") {
            element(ORE)
            element(BLOCK)
            element(PAD)
            element(WAYSTONE)
        }
        registry.declareTag("minecraft:damage_type", "minecraft:is_lightning") { element(DAMAGE_TYPE) }
        registry.declareTag("minecraft:item", "lite_kit") {
            element(SWORD)
            element(PEARL)
            element(KEY)
        }
    }
}
