package oml.testmod

import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.core.Direction
import net.minecraft.tags.TagKey
import org.ohmyloader.api.content.OMLBlockDataCarrier
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.InteractionResult
import net.minecraft.world.MenuProvider
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.server.permissions.LevelBasedPermissionSet
import net.minecraft.server.permissions.PermissionSet
import net.minecraft.server.permissions.Permissions
import net.minecraft.world.phys.Vec3
import java.util.Optional
import net.minecraft.world.Container
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import org.ohmyloader.api.tick.OMLScheduler
import org.ohmyloader.api.tick.OMLSide
import org.ohmyloader.api.tick.OMLTask
import org.ohmyloader.api26_3.*
import org.ohmyloader.api26_3.event.BlockUsedEvent
import org.ohmyloader.api26_3.event.Events
import org.ohmyloader.api26_3.event.ItemUsedEvent

/**
 * Probes the version-facing surface (`OMLGame`, the level / player / inventory reads and writes, the tick
 * scheduler) and the lifecycle and interaction events, on the game's own 26.3 types.
 *
 * Reading proves the mod is handed the objects the game holds; driving game methods proves the hooks fire. An
 * unattended run has no hand, so the probe calls `destroyBlock` / `attack` / `interactOn` / `hurtServer` /
 * `useItemOn` / `useItem` itself with its own cancelling handler armed: cancellation keeps the driven call from
 * changing the world, and the game's return value and the entity's health are what the assertions read.
 */
object WorldProbe {


    private const val AIR = "minecraft:air"
    private const val STONE = "minecraft:stone"
    private const val DIRT = "minecraft:dirt"
    private const val GLOWSTONE = "minecraft:glowstone"
    private const val COW = "minecraft:cow"

    /** A entity with no movement AI: nothing recomputes the motion written into it. */
    private const val SNOWBALL = "minecraft:snowball"
    private const val CHEST = "minecraft:chest"
    private const val POOF = "minecraft:poof"

    /** A particle whose type needs arguments built per spawn, so a bare id must be refused. */
    private const val DUST = "minecraft:dust"
    private const val HURT_AMOUNT = 2f

    /** What the damage-rewrite handler writes over vanilla's number; the applied damage must scale with it. */
    private const val REWRITTEN_AMOUNT = 4f

    /** The game's own damage cooldown: an application inside this window is reduced by the previous one. */
    private const val DAMAGE_COOLDOWN_TICKS = 20

    /** A neutral mob the game treats as fire-immune: lighting it has to be refused. */
    private const val STRIDER = "minecraft:strider"
    private const val FIRE_TICKS = 60

    /** Attribute ids the probes read and write. */
    private const val MAX_HEALTH_ATTRIBUTE = "minecraft:max_health"
    private const val ATTACK_DAMAGE_ATTRIBUTE = "minecraft:attack_damage"

    /** A maximum the game itself accepts (its own clamp for this attribute reaches far past it). */
    private const val RAISED_MAX_HEALTH = 30.0

    /** The modifier the attribute probe adds and takes off again. */
    private const val MODIFIER_ID = "oml_testmod:probe"
    private const val MODIFIER_AMOUNT = 4.0

    /** Item ids the equipment probes wear and take off: boots fill a feet slot, a chestplate a player's. */
    private const val LEATHER_BOOTS = "minecraft:leather_boots"
    private const val IRON_CHESTPLATE = "minecraft:iron_chestplate"

    /** An item the game equips in no armour slot, driven as the refusal. */
    private const val DIAMOND = "minecraft:diamond"

    /** A slot name the game does not have. */
    private const val NO_SUCH_SLOT = "saddlebow"

    /** A registered damage type the hurt probe deals through the game's own path. */
    private const val CACTUS_DAMAGE = "minecraft:cactus"

    /** A block whose slots refuse some items by its own rule: a furnace's output and fuel slots. */
    private const val FURNACE = "minecraft:furnace"

    /**
     * The menu id the game gives a single chest: its own `generic_9x3` layout, not the block's id — which is
     * why the read reports the registry name the game holds instead of anything OML could derive.
     */
    private const val CHEST_MENU = "minecraft:generic_9x3"
    private const val CHEST_SLOTS = 27
    private const val FURNACE_SLOTS = 3

    /** How far along x the probes look for a column with ground under it and air above. */
    private const val POCKET_SEARCH_RANGE = 12

    /** The furnace's own refusal rules, read from its block entity: fuel slot and output slot. */
    private const val FURNACE_INPUT = 0
    private const val FURNACE_FUEL = 1
    private const val FURNACE_OUTPUT = 2

    /** The damage type [OMLTestMod] declares; the game must have loaded it from OML's content pack. */
    private const val MOD_DAMAGE_TYPE = "oml_testmod:test_melt"
    private const val MOD_DAMAGE_MESSAGE = "oml_testmod.melts"

    /** The groups the tag probe reads back: one joined from vanilla, one the pack opened, one it replaced. */
    private const val FIRE_TAG = "minecraft:is_fire"
    private const val FREEZING_TAG = "minecraft:is_freezing"
    private const val MOD_TAG = "oml_testmod:melting_causes"
    private const val TECH_DAMAGE_TYPE = "techmod:crush"
    private const val OVERWORLD = "minecraft:overworld"

    /** A dimension whose type names no clock: the game has no time to read or write there. */
    private const val NETHER = "minecraft:the_nether"

    /** The clock the overworld runs on, and the vanilla marker the clock probes move it onto. */
    private const val OVERWORLD_CLOCK = "minecraft:overworld"
    private const val NOON_MARKER = "minecraft:noon"
    private const val ABSENT_CLOCK = "oml_testmod:absent_clock"
    private const val ABSENT_MARKER = "oml_testmod:absent_marker"

    /**
     * The clock and timeline [OMLTestMod] declares. The game must have loaded both out of OML's content pack,
     * run an instance for the clock, and moved it onto the mod's own marker.
     */
    private const val MOD_CLOCK = "oml_testmod:test_clock"
    private const val MOD_TIMELINE = "oml_testmod:test_timeline"
    private const val MOD_MARKER_LOW = "oml_testmod:low_tide"
    private const val MOD_MARKER_HIGH = "oml_testmod:high_tide"
    private const val MOD_MARKER_HIGH_TICKS = 12000

    /** The second declared dimension, whose type names no clock — the pair the teleport probe moves between. */
    private const val MOD_DIMENSION_B = "oml_testmod:test_dim_b"
    private const val ABSENT_DIMENSION_B = "oml_testmod:no_such_dim"
    /** Where the anchor probes point: a column of the mod's own dimension, with a bedrock floor under it. */
    private val ANCHOR_POSITION = BlockPos(72, 41, 72)
    private const val ANCHOR_YAW = 90f

    /** How long the press ticker is given to visit a block entity the probe placed on this tick. */
    private const val PROGRESS_TICK_WAIT_LIMIT = 20

    /** A border this tight excludes [OUTSIDE_BORDER_POSITION] and ANCHOR_POSITION's column alike. */
    private const val TIGHT_BORDER_SIZE = 50.0
    private val OUTSIDE_BORDER_POSITION = BlockPos(200, 41, 200)

    /** Its own column, so the ticket probes below never release a chunk another probe is holding. */
    private val TELEPORT_POSITION = BlockPos(72, 40, 72)
    private const val TELEPORT_X = 72.5
    private const val TELEPORT_Y = 41.0
    private const val TELEPORT_Z = 72.5

    /** A block with a block entity, declared by the techmod in the same run: `techmod:press`. */
    private const val PRESS_BLOCK = "techmod:press"
    private val PRESS_POSITION = BlockPos(120, 40, 120)

    /** Its own column, stood in two declared dimensions at once so the same coordinates carry two stores. */
    private val PRESS_DATA_POSITION = BlockPos(168, 40, 168)

    /** A node nothing in vanilla grants; the gated command and the grant probe both address it. */
    private const val MOD_NODE = "oml_testmod:build"

    /** A node a **different** pack grants to the same player, so the override table is never solely ours. */
    private const val OTHER_PACK_NODE = "oml_playmod:keeper"
    private const val CHAT_NODE = "minecraft:chat/send_messages"


    /**
     * The dimension type and dimension this mod declares. 26.3 builds a level for every `dimension` entry when
     * the server starts, so the acceptance point is the running server answering for a dimension vanilla never
     * had — and obeying the flags the declaration wrote.
     */
    private const val MOD_DIMENSION_TYPE = "oml_testmod:test_deep"
    private const val MOD_DIMENSION = "oml_testmod:test_dim"
    private const val ABSENT_DIMENSION = "oml_testmod:absent_dim"
    private const val MOD_DIMENSION_HEIGHT = 128
    private const val MOD_DIMENSION_MIN_Y = 0

    /**
     * Where the chunk probe works: a position inside one chunk of the mod's own dimension, which no player
     * ever visits. The flat generator puts one bedrock layer at y=0, so the column above it is air to build in.
     */
    private val CHUNK_PROBE_POSITION = BlockPos(8, 64, 8)
    private const val CHUNK_PROBE_BLOCK = "minecraft:stone"
    private const val CHUNK_TICK_WAIT_LIMIT = 60

    /** How long the destination's id table may take to answer for a teleported entity. */
    private const val ENTITY_ARRIVAL_WAIT_TICKS = 20


    /** Outside the game's own `/time rate` window, so OML has to refuse it rather than store it. */
    private const val FAST_RATE = 2f
    private const val IDLE_RATE = 0f
    private const val IMPOSSIBLE_RATE = 100000f

    /** Ticks the clock probes watch the game count at a given rate, and the most they may wait for it. */
    private const val CLOCK_RATE_WINDOW = 5
    private const val CLOCK_PAUSE_WINDOW = 3
    private const val CLOCK_WINDOW_LIMIT = 100

    /** How far ahead of the client's copy the sync probe writes, and how near it has to land. */
    private const val CLOCK_SYNC_JUMP = 5000L
    private const val CLOCK_SYNC_TOLERANCE = 5L
    private const val CLOCK_SYNC_WAIT_TICKS = 60

    /** A rule that exists in every vanilla dimension, so the flip-and-restore has a guaranteed subject. */
    private const val KEEP_INVENTORY_RULE = "minecraft:keep_inventory"

    /** A dust colour in the game's own packed form: 0xRRGGBB. */
    private const val DUST_COLOR = 0xFF5500

    /** X motion written to the launched snowball; the game has to integrate it into the position. */
    private const val MOTION_X = 0.6

    /** Ticks the motion probe lets the game run with the vector before reading the drift. */
    private const val MOTION_WINDOW_TICKS = 4

    /** How often the motion probe may re-drive its launch before that counts as a defect. */
    private const val MOTION_MAX_ATTEMPTS = 5

    /** Where the motion probe starts looking for open air above the player, and how far it climbs. */
    private const val SHOT_LIFT = 6.0
    private const val SHOT_CLIMB = 24

    /** Blocks along +x that have to read as air for a launch to be considered clear. */
    private const val SHOT_RUN = 8

    /**
     * How long a driven damage may stay refused before that is a defect: long enough to outlast the game's
     * own damage cooldown and a just-joined player's invulnerability, short enough that a refused action
     * reports instead of waiting out the run.
     */
    private const val REFUSED_DAMAGE_WAIT_TICKS = 40

    /** A probe whose precondition has not been met for this many ticks has a defect, not a slow world. */
    private const val GIVE_UP_TICKS = 600

    /**
     * How long one weather phase may take: the game moves its live rain level by 0.01 per weather tick and
     * reads rain above 0.2, thunder above 0.9, so a write shows up twenty-odd ticks later, thunder about a
     * hundred, and walking back down through the rain threshold takes eighty.
     */
    private const val WEATHER_RAMP_WAIT_TICKS = 250

    /** How long the storm phase is asked to hold, comfortably past the wait it has to survive. */
    private const val WEATHER_HOLD_TICKS = 600

    /** The weather states the probe drives in order: a known-clear start, the storm, then clear again. */
    private val WEATHER_DRIVE = listOf(false to false, true to true, false to false)

    /** A dedicated server never boots a client, so the game's own client instance is the side test. */
    private val dedicated: Boolean = OMLGame.client == null

    /** Unique per run: a chat line from an earlier run must not satisfy the delivery check. */
    private val broadcastMarker = "oml-e2e-broadcast-${System.nanoTime()}"
    private val systemMarker = "oml-e2e-system-${System.nanoTime()}"

    // What the cancelling handlers observed; the driving step reads them back and reports once.
    private var breakObservation: Pair<BlockPos, String>? = null
    private var attackObservation: String? = null
    private var interactObservation: InteractionHand? = null
    private var hurtObservation: Triple<Float, String, Boolean>? = null
    private var blockUseObservation: BlockUsedEvent? = null
    private var itemUseObservation: ItemUsedEvent? = null

    /** The number the rewrite handler saw on the way in, which must be vanilla's, not the written one. */
    private var rewriteObservation: Float? = null

    /** What the hook saw of the damage this probe **dealt**: amount, damage type, and whether an attacker
     *  was credited — the same three things a mob's own attack arrives with. */
    private var hurtActionObservation: Triple<Float, String, Boolean>? = null

    /** The game's own tag reads on the source it built: `is_fire`, `is_projectile`. */
    private var sourceTagObservation: Pair<Boolean, Boolean>? = null

    /** LEVEL_TICK counts and first-seen dimensions, kept per side: the same run ticks two levels. */
    private var serverLevelTicks = 0
    private var clientLevelTicks = 0
    private var firstServerLevel: String? = null
    private var firstClientLevel: String? = null

    /** Where [driveHurtRewrite] got to: 0 control damage pending, 1 waiting out the game's cooldown, 2 reported. */
    private var rewritePhase = 0

    /** What the game's own arithmetic made of the control amount, the baseline the rewritten one compares to. */
    private var controlDelta = -1f

    /** Server tick the control damage landed on, the start of the cooldown that has to expire after it. */
    private var controlTick = -1

    /** The cow the spawn probe created: the steps sharing it read and clean up the same living entity. */
    private var spawned: LivingEntity? = null

    /** The projectile the motion probe writes into, discarded once the measurement reported. */
    private var projectile: Entity? = null

    /** X position the shot had when its motion was written, the baseline the game's integration is measured from. */
    private var motionStart = Double.NaN

    /** Server tick the motion was written on; negative means the write has not happened yet. */
    private var motionTick = -1

    /** How many launches the game removed before their window closed. */
    private var motionAttempts = 0

    /** Ticks the control damage has been refused for, so a refusal becomes a verdict instead of silence. */
    private var controlWaits = 0

    /** The player [Events.PLAYER_RESPAWNED] handed out while the respawn drive was armed. */
    private var respawnObservation: ServerPlayer? = null

    /** Armed only around the driven respawn, so a player respawning on their own cannot satisfy the check. */
    @Volatile
    private var driveRespawnWindow = false

    /** The instance the game replaced at the driven respawn, kept to address the player through it. */
    private var replacedHandle: ServerPlayer? = null

    /** Which [WEATHER_DRIVE] state [probeWeather] has to reach next. */
    private var weatherPhase = 0

    /** The current weather phase's write is done and only the game's ramped read is pending. */
    private var weatherWritten = false

    /** Ticks spent waiting for the live weather read to catch up with the write. */
    private var weatherWait = 0

    /** Clock ticks the rate probe had counted before it changed the speed, to restore afterwards. */
    private var clockBaseline = 0L

    /** Server tick the current clock-rate phase started on. */
    private var clockPhaseTick = -1

    /** Clock ticks counted at the start of the current clock-rate phase. */
    private var clockPhaseStart = 0L

    /** 0 = fast rate running, 1 = paused, 2 = resumed, 3 = reported. */
    private var clockRatePhase = 0

    /** The clock position the client-sync probe wrote, and what it held before the probe. */
    private var clockSyncTarget = 0L
    private var clockSyncPrevious = 0L

    /** Server tick the client-sync probe wrote on, the start of the window it may take to arrive. */
    private var clockSyncTick = -1

    /** 0 = the write is pending, 1 = waiting for the client's copy to follow, 2 = reported. */
    private var clockSyncPhase = 0

    // Gates: a handler cancels only the call this probe made itself, never a real player action.
    @Volatile
    private var breakTarget: BlockPos? = null

    @Volatile
    private var drivenUuid: java.util.UUID? = null

    @Volatile
    private var driveAttack = false

    @Volatile
    private var driveInteract = false

    @Volatile
    private var driveHurt = false

    @Volatile
    private var driveHurtRewrite = false

    /** Armed only while the probe's own dealt damage runs: the handler records it and lets it through. */
    @Volatile
    private var driveHurtAction = false

    @Volatile
    private var driveBlockUse = false

    @Volatile
    private var driveItemUse = false

    /** How many times the node-gated command's handler actually ran. */
    var gatedRuns = 0

    /**
     * `"dimensionId@cx,cz"` for every chunk the game has said is ticking and has not since dropped. A forced
     * chunk is only *writable* once it reaches that state, which is why the probes below wait on it instead of
     * on `isLoaded`.
     */
    private val tickingChunks = HashSet<String>()

    private fun tickingKey(dimensionId: String, chunk: ChunkPos): String = "$dimensionId@${chunk.x()},${chunk.z()}"

    private val reported = HashSet<String>()
    private val waited = HashMap<String, Int>()

    @Volatile
    private var chunkTickingSeen = false

    @Volatile
    private var chunkUnloadedSeen = false

    /** Registers the handlers. Called on both sides; only the current side's hooks ever dispatch. */
    fun register() {
        // The chunk pair observes the game's own promotion and drop of one chunk of the mod's dimension.
        // Both handlers filter on the probe's target, so whatever else the game loads stays out of it.
        Events.CHUNK_STARTED_TICKING.register { event ->
            tickingChunks += tickingKey(event.level.dimensionId, event.chunk.pos)
            if (event.level.dimensionId == MOD_DIMENSION && event.chunk.pos == ChunkPos.containing(CHUNK_PROBE_POSITION)) {
                chunkTickingSeen = true
            }
        }
        Events.CHUNK_UNLOADED.register { event ->
            tickingChunks -= tickingKey(event.level.dimensionId, event.chunk.pos)
            if (event.level.dimensionId == MOD_DIMENSION && event.chunk.pos == ChunkPos.containing(CHUNK_PROBE_POSITION)) {
                chunkUnloadedSeen = true
            }
        }
        Events.SERVER_STARTED.register { event ->
            val dimensions = event.server.dimensionIds
            E2E.check(
                "event.server_started",
                "minecraft:overworld" in dimensions && event.server.tickCount >= 0,
                "dimensions=$dimensions tick=${event.server.tickCount} dedicated=${event.server.isDedicatedServer()}",
            )
        }
        // Record-only, and it cannot be an expectation: it fires during teardown, which the harness only
        // reaches after the READY line an expectation would be waiting for.
        Events.SERVER_STOPPING.register { event ->
            E2E.check(
                "event.server_stopping",
                event.server.tickCount > 0,
                "handle still reads at tick ${event.server.tickCount} with " +
                    "${event.server.onlinePlayers.size} player(s)",
            )
        }
        Events.PLAYER_JOINED.register { event ->
            val player = event.player
            E2E.check(
                "event.player_joined",
                player.scoreboardName.isNotBlank() && player.gameModeName != null &&
                    player.inventory.containerSize >= 36 && player.dimensionId.isNotBlank(),
                "player=${player.scoreboardName} mode=${player.gameModeName} " +
                    "dimension=${player.dimensionId} slots=${player.inventory.containerSize}",
            )
        }
        // Record-only, for the same teardown-ordering reason as SERVER_STOPPING.
        Events.PLAYER_LEFT.register { event ->
            E2E.check(
                "event.player_left",
                event.player.scoreboardName.isNotBlank(),
                "last-known position ${event.player.blockPosition()}",
            )
        }

        Events.BLOCK_BREAK.register { event ->
            if (breakTarget == event.position) {
                breakObservation = event.position to event.state.blockId
                event.cancel()
            }
        }
        Events.ENTITY_ATTACK.register { event ->
            if (driveAttack && event.player.uuid == drivenUuid) {
                attackObservation = event.target.typeId
                event.cancel()
            }
        }
        Events.ENTITY_INTERACT.register { event ->
            if (driveInteract && event.player.uuid == drivenUuid) {
                interactObservation = event.hand
                event.cancel()
            }
        }
        Events.LIVING_HURT.register { event ->
            if (driveHurt && event.entity.uuid == drivenUuid) {
                hurtObservation = Triple(event.amount, event.source.type().msgId(), event.source.entity != null)
                event.cancel()
            }
            // Not cancelling: the point is that the game applies the written number, so this handler has
            // to let the damage through.
            if (driveHurtRewrite && event.entity.uuid == drivenUuid) {
                rewriteObservation = event.amount
                event.amount = REWRITTEN_AMOUNT
            }
            // Neither cancelling nor rewriting: this is the damage the probe *dealt*, and the point is
            // that the game runs it through the same hook a mob's attack uses.
            if (driveHurtAction && event.entity.uuid == drivenUuid) {
                hurtActionObservation = Triple(event.amount, event.source.type().msgId(), event.source.entity != null)
                sourceTagObservation = event.source.`is`(DamageTypeTags.IS_FIRE) to
                    event.source.`is`(DamageTypeTags.IS_PROJECTILE)
            }
        }
        Events.BLOCK_USED.register { event ->
            if (driveBlockUse && event.player.uuid == drivenUuid) {
                blockUseObservation = event
                event.cancel()
            }
        }
        Events.ITEM_USED.register { event ->
            if (driveItemUse && event.player.uuid == drivenUuid) {
                itemUseObservation = event
                event.cancel()
            }
        }
        // Tripwire, never driven: nothing here kills anything. When a natural death passes through the
        // hook, the constraint worth asserting is that the death routine starts at or below zero health.
        Events.LIVING_DIED.register { event ->
            E2E.check(
                "event.living_died",
                event.entity.health <= 0f,
                "${event.entity.typeId} entered the death routine at health ${event.entity.health} " +
                    "(${event.source.type().msgId()})",
            )
        }
        Events.PLAYER_RESPAWNED.register { event ->
            if (driveRespawnWindow) respawnObservation = event.player
        }
        // Fires up to twice per tick per dimension in singleplayer, so it only counts and remembers the
        // first dimension each side reported; the assertions live in the step below.
        Events.LEVEL_TICK.register { event ->
            val level = event.level
            if (level.isClientSide) {
                clientLevelTicks++
                if (firstClientLevel == null) firstClientLevel = level.dimensionId
            } else {
                serverLevelTicks++
                if (firstServerLevel == null) firstServerLevel = level.dimensionId
            }
        }
    }

    /** Client-side pass. */
    fun tickClient() = guard {
        probeClientHandle()
        tickScheduler(clientRun)
        step("api.world_block_read") { probeClientWorldRead() }
    }

    /** Server-side pass, dedicated or integrated. */
    fun tickServer() = guard {
        probeServerHandle()
        tickScheduler(serverRun)
        step("api.server_level_lookup") { probeLevelLookup() }
        step("api.registry_reads") { probeRegistries() }
        // None of these need a player, so they also run on the headless dedicated-server gate.
        step("event.level_tick") { probeLevelTick() }
        step("content.damage_type") { probeDeclaredDamageType() }
        step("content.custom_tag") { probeDamageTypeTag() }
        step("api.world_weather") { probeWeather() }
        step("api.world_clock") { probeClock() }
        step("api.clock_markers") { probeClockMarkers() }
        step("api.clock_rate_and_pause") { probeClockRate() }
        step("content.custom_clock") { probeCustomClock() }
        step("content.custom_dimension") { probeCustomDimension() }
        step("api.chunk_lifecycle") { probeChunkLifecycle() }
        step("api.permission_nodes") { probePermissionNodes() }
        step("api.world_spawn_anchor") { probeWorldSpawn() }
        step("api.block_entity_read") { probeBlockEntityRead() }
        step("api.block_data_addressing") { probeBlockDataAddressing() }
        step("api.dimension_teleport") { probeDimensionTeleport() }
        // The client's copy of the clock only exists in a client process, so the dedicated run must not
        // wait for a step it can never satisfy.
        if (!dedicated) step("api.clock_client_sync") { probeClockSync() }
        step("api.server_game_rules") { probeGameRules() }
        step("api.world_particles_arguments") { probeArgumentParticles() }
        step("api.entity_equipment") { probeEntityEquipment() }
        val player = OMLGame.server?.onlinePlayers?.firstOrNull() ?: return@guard
        step("api.player_handle") { probePlayerHandle(player) }
        step("api.player_inventory") { probeInventory(player) }
        step("api.give_item") { probeGiveItem(player) }
        step("api.inventory_remove") { probeInventoryRemove(player) }
        step("api.world_block_write") { probeWorldWrite(player) }
        step("api.world_is_loaded") { probeIsLoaded(player) }
        step("api.server_broadcast") { probeSend(player) }
        step("api.player_effects") { probeEffects(player) }
        step("api.world_sound") { probeWorldSound(player) }
        step("api.player_sound") { probePlayerSound(player) }
        step("api.player_permissions") { probePermissions(player) }
        step("event.block_break") { driveBlockBreak(player) }
        step("event.entity_attack") { driveAttack(player) }
        step("event.entity_interact") { driveInteract(player) }
        step("event.living_hurt") { driveHurt(player) }
        step("event.living_hurt_rewrite") { driveHurtRewrite(player) }
        step("api.player_heal") { probeHeal(player) }
        step("event.block_used") { driveBlockUse(player) }
        step("event.item_used") { driveItemUse(player) }
        step("api.player_teleport") { probeTeleport(player) }
        step("api.entity_spawn") { probeSpawn(player) }
        step("api.entity_motion") { probeMotion(player) }
        step("api.entity_attributes") { probeAttributes(player) }
        step("api.entity_hurt") { probeDealtDamage(player) }
        step("api.entity_fire") { probeFire(player) }
        step("api.entity_removal") { probeRemoval(player) }
        step("api.block_state") { probeBlockState(player) }
        step("api.world_container") { probeContainer(player) }
        step("api.player_equipment") { probePlayerEquipment(player) }
        step("api.permission_grant") { probePermissionGrant(player) }
        step("api.player_respawn_anchor") { probePlayerRespawnAnchor(player) }
        step("api.player_dimension_teleport") { probePlayerDimensionTeleport(player) }
        step("api.player_opened_menu") { probeOpenedMenu(player) }
        step("api.world_particles") { probeParticles(player) }
        // Last: it kills the player and brings them back, so nothing may depend on the handle afterwards.
        step("event.player_respawned") { driveRespawn(player) }
        step("api.player_teleport_replaced_handle") { driveTeleportAfterRespawn() }
    }

    /** These run inside the game's tick dispatch, so a throw here would otherwise stop the game loop. */
    private inline fun guard(body: () -> Unit) {
        runCatching(body).onFailure { println("[oml_testmod] world probe interrupted: $it") }
    }

    /** Fed by the mod's ChatReceivedEvent handler: delivery is what proves the send. */
    fun observeChat(message: String) {
        if (broadcastMarker in message) E2E.check("api.server_broadcast", true, "delivered: \"$message\"")
        if (systemMarker in message) E2E.check("api.player_system_message", true, "delivered: \"$message\"")
    }

    /**
     * Runs one probe once, and only once it has reported. `false` means a precondition is not there yet
     * (the level not loaded, the chunk not sent), so the step is retried; a throw and a stale precondition
     * are both verdicts, so a defect produces a red line rather than silence.
     */
    private fun step(name: String, body: () -> Boolean) {
        if (name in reported) return
        val verdict = runCatching(body)
        verdict.onFailure {
            reported += name
            E2E.check(name, false, "driving threw ${it.javaClass.simpleName}: ${it.message}")
            return
        }
        if (verdict.getOrDefault(false)) {
            reported += name
            return
        }
        val ticks = (waited[name] ?: 0) + 1
        waited[name] = ticks
        if (ticks > GIVE_UP_TICKS) {
            reported += name
            E2E.check(name, false, "precondition still unmet after $ticks ticks")
        }
    }

    private var serverHandleProbed = false
    private var tickSnapshot = -1

    private fun probeServerHandle() {
        if (serverHandleProbed) return
        val server = OMLGame.server ?: return
        if (tickSnapshot < 0) {
            tickSnapshot = server.tickCount
            return
        }
        // A handle that cannot observe the tick counter moving is not a handle to a live server.
        if (server.tickCount <= tickSnapshot) return
        serverHandleProbed = true
        val dimensions = server.dimensionIds
        val named = server.onlinePlayers.firstOrNull()?.let { server.playerByName(it.scoreboardName) }
        E2E.check(
            "api.server_handle",
            "minecraft:overworld" in dimensions && server.isDedicatedServer() == dedicated &&
                server.playerByName("oml_e2e_absent") == null &&
                (named != null) == server.onlinePlayers.isNotEmpty(),
            "dimensions=$dimensions dedicated=${server.isDedicatedServer()} " +
                "on a ${if (dedicated) "dedicated" else "client"} run, " +
                "ticks=${server.tickCount} players=${server.onlinePlayers.size}",
        )
    }

    private var clientHandleProbed = false

    private fun probeClientHandle() {
        if (clientHandleProbed) return
        val client = OMLGame.client ?: return
        val width = client.scaledWidth
        val height = client.scaledHeight
        if (width <= 0 || height <= 0) return
        clientHandleProbed = true
        E2E.check(
            "api.client_handle",
            (client.player == null) == (client.level == null),
            "gui=${width}x$height screen=${client.currentScreen?.javaClass?.simpleName ?: "none"} " +
                "player=${client.player?.scoreboardName ?: "none"} " +
                "world=${client.level?.dimensionId ?: "none"}",
        )
    }

    /** Reads the level the client renders — the one a client-side mod or HUD is handed. */
    private fun probeClientWorldRead(): Boolean {
        val client = OMLGame.client ?: return false
        val world = client.level ?: return false
        val player = client.player ?: return false
        val pos = firstSolid(world, player.blockPosition(), 8) ?: return false
        E2E.check("api.world_block_read", true, "${world.dimensionId} at $pos is ${world.blockIdAt(pos)}")
        return true
    }

    private fun probePlayerHandle(player: ServerPlayer): Boolean {
        E2E.check(
            "api.player_handle",
            player.typeId == "minecraft:player" && player.scoreboardName.isNotBlank() &&
                player.stringUUID.isNotBlank() && player.gameModeName != null &&
                !player.isRemoved && player.health > 0f && !player.isOnFire &&
                player.health in 0f..player.maxHealth && player.position().x().isFinite() &&
                player.level().dimensionId == player.dimensionId &&
                player.mainHandItem.itemId == player.inventory.selectedItem.itemId,
            "type=${player.typeId} name=${player.scoreboardName} mode=${player.gameModeName} " +
                "dimension=${player.dimensionId} health=${player.health}/${player.maxHealth} " +
                "position=${player.position()}",
        )
        return true
    }

    private fun probeInventory(player: ServerPlayer): Boolean {
        val inventory = player.inventory
        val slot = 5
        val original = inventory.getItem(slot)
        val wrote = inventory.setSlot(slot, GLOWSTONE, 3)
        val written = inventory.getItem(slot)
        val outOfRangeWrite = inventory.setSlot(inventory.containerSize, STONE, 1)
        val unknownIdWrite = inventory.setSlot(slot, "oml_testmod:not_registered", 1)
        // Back the way it was; an empty slot has no id to write, so it is cleared through a known one.
        val restored = if (original.isEmpty) inventory.setSlot(slot, STONE, 0) else
            inventory.setSlot(slot, original.itemId, original.count)
        val readBack = inventory.getItem(slot)
        inventory.selectHotbarSlot(3)
        val selected = inventory.selectedSlot
        inventory.selectHotbarSlot(0)
        E2E.check(
            "api.player_inventory",
            inventory.containerSize >= 36 && selected == 3 && inventory.selectedSlot == 0 &&
                wrote && written.itemId == GLOWSTONE && written.count == 3 &&
                !outOfRangeWrite && !unknownIdWrite && restored &&
                readBack.itemId == original.itemId && readBack.count == original.count,
            "slots=${inventory.containerSize} wrote=$wrote read=${written.itemId}x${written.count} " +
                "out-of-range=$outOfRangeWrite unknown-id=$unknownIdWrite " +
                "restored=${readBack.itemId}x${readBack.count}",
        )
        return true
    }

    private fun probeGiveItem(player: ServerPlayer): Boolean {
        val before = player.inventory.countOf(STONE)
        val given = player.giveItem(STONE, 5)
        val after = player.inventory.countOf(STONE)
        val unknown = player.giveItem("oml_testmod:not_registered", 1)
        E2E.check(
            "api.give_item",
            given && after >= before + 5 && !unknown,
            "$STONE $before -> $after, unregistered id returned $unknown",
        )
        return true
    }

    /** Writes through the level the player stands in, which for a server player is the **server** level. */
    private fun probeWorldWrite(player: ServerPlayer): Boolean {
        val world = player.level()
        // Starts one block deeper than the break probe, so the two never take the same position.
        val feet = player.blockPosition()
        val pos = firstSolid(world, BlockPos(feet.x, feet.y - 1, feet.z), 8) ?: return false
        val original = world.blockIdAt(pos)
        val wrote = world.setBlockAt(pos, GLOWSTONE)
        val written = world.blockIdAt(pos)
        val restored = world.setBlockAt(pos, original)
        E2E.check(
            "api.world_block_write",
            wrote && written == GLOWSTONE && restored && world.blockIdAt(pos) == original,
            "$pos $original -> $written -> ${world.blockIdAt(pos)}",
        )
        return true
    }

    /**
     * Sends once. The verdict belongs to the client, where the chat line arrives — a failed send reports
     * here and cannot be overwritten afterwards, because nothing can arrive after it.
     */
    private fun probeSend(player: ServerPlayer): Boolean {
        val server = OMLGame.server ?: return false
        runCatching { server.broadcastMessage(broadcastMarker) }
            .onFailure { E2E.check("api.server_broadcast", false, "broadcast threw $it") }
        runCatching { player.sendSystemMessage(systemMarker) }
            .onFailure { E2E.check("api.player_system_message", false, "sendSystemMessage threw $it") }
        return true
    }

    /**
     * `Events.LEVEL_TICK` has to fire once per tick per level, on the side that ticks it, carrying that
     * level itself. Singleplayer is the case that matters: the integrated level and the rendered one are
     * both dispatched, so each counter moves and each reports its own `isClientSide`.
     */
    private fun probeLevelTick(): Boolean {
        if (serverLevelTicks < 2) return false
        if (!dedicated && clientLevelTicks < 2) return false
        E2E.check(
            "event.level_tick",
            firstServerLevel == OVERWORLD && (dedicated || firstClientLevel == OVERWORLD),
            "server-side levels ticked $serverLevelTicks times (first dimension $firstServerLevel), client-side " +
                "$clientLevelTicks times (first dimension $firstClientLevel), on a " +
                "${if (dedicated) "dedicated-server" else "singleplayer"} run",
        )
        return true
    }

    /** Every dimension the server reports loaded resolves to its own server-side level. */
    private fun probeLevelLookup(): Boolean {
        val server = OMLGame.server ?: return false
        val dimensions = server.dimensionIds
        if (dimensions.isEmpty()) return false
        val resolved = dimensions.map { it to server.levelById(it)?.let { level -> level.dimensionId to level.isClientSide } }
        val absent = server.levelById("oml_testmod:absent")
        E2E.check(
            "api.server_level_lookup",
            resolved.all { (id, read) -> read == (id to false) } && absent == null,
            "resolved=${resolved.joinToString { (id, read) -> "$id->$read" }} unregistered id -> $absent",
        )
        return true
    }

    /**
     * The write reaches the server's weather record; what the level reads is a ramped value the game moves
     * by 0.01 on its own weather tick, so the probe drives clear → storm → clear and waits for the live
     * read to follow each write. Each wait re-reads the level, so a value cached from the first read would
     * never see the ramp and the phase would expire instead of passing.
     *
     * The refusal is re-checked every tick: the game scales thunder by the rain level, so it cannot
     * represent thunder without rain. The write path belongs to `ServerLevel`, which is the game's own
     * statement that a client's level does not own the weather.
     */
    private fun probeWeather(): Boolean {
        val server = OMLGame.server ?: return false
        val world = server.levelById(OVERWORLD) ?: return false
        val thunderWithoutRain = world.setWeather(raining = false, thundering = true)
        if (thunderWithoutRain) {
            E2E.check("api.world_weather", false, "thunder without rain accepted=$thunderWithoutRain")
            return true
        }
        val (raining, thundering) = WEATHER_DRIVE[weatherPhase]
        if (!weatherWritten) {
            val hold = if (raining) WEATHER_HOLD_TICKS else 0
            if (!world.setWeather(raining = raining, thundering = thundering, durationTicks = hold)) return false
            weatherWritten = true
            return false
        }
        val live = server.levelById(OVERWORLD) ?: return false
        if (live.isRaining == raining && live.isThundering == thundering) {
            weatherWritten = false
            weatherWait = 0
            if (++weatherPhase == WEATHER_DRIVE.size) {
                E2E.check("api.world_weather", true, "the live read followed the write into the storm and back out")
                return true
            }
            return false
        }
        if (++weatherWait > WEATHER_RAMP_WAIT_TICKS) {
            E2E.check(
                "api.world_weather",
                false,
                "phase $weatherPhase asked for $raining/$thundering and the live read stayed " +
                    "${live.isRaining}/${live.isThundering} for $weatherWait ticks",
            )
            return true
        }
        return false
    }

    /**
     * The dimension's own clock, with the two refusals that say the handle is the game's record and not a
     * copy of it: a dimension whose type names no clock reads null rather than a clock stuck at zero, and
     * an unregistered clock id reads null instead of reaching `ServerClockManager.getInstance`, which
     * throws for a clock the game does not hold. A write through the dimension's handle has to show up in
     * the handle taken from the server, since both are meant to be the same single clock.
     */
    private fun probeClock(): Boolean {
        val server = OMLGame.server ?: return false
        val level = server.levelById(OVERWORLD) ?: return false
        val clock = level.clock ?: return false
        val instance = level.clockManager().instanceOf(clock) ?: return false
        val nether = server.levelById(NETHER) ?: return false
        val id = clock.getRegisteredName()
        val before = instance.totalTicks()
        val wanted = before + 1000
        val wrote = level.clockManager().applyTotalTicks(clock, wanted)
        val viaServer = server.clockById(id)
        val seenThere = viaServer?.let { server.clockManager().instanceOf(it)?.totalTicks() }
        val restored = level.clockManager().applyTotalTicks(clock, before)
        val flags = listOf(
            "the dimension's clock is the game's own" to (id == OVERWORLD_CLOCK),
            "the clock table lists it" to (server.registry(Registries.WORLD_CLOCK)?.contains(OVERWORLD_CLOCK) == true),
            "a clockless dimension reads null" to (nether.clock == null),
            "an unregistered id reads null" to (server.clockById(ABSENT_CLOCK) == null),
            "the write was accepted" to wrote,
            "the server names the same clock" to (viaServer?.getRegisteredName() == id),
            "the game's record moved" to (seenThere == wanted),
            "the position restores" to (restored && instance.totalTicks() == before),
        )
        E2E.check(
            "api.world_clock",
            flags.all { it.second },
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; $before -> " +
                "$seenThere (wanted $wanted), restored to ${instance.totalTicks()}, nether clock=" +
                "${nether.clock?.getRegisteredName() ?: "null"}",
        )
        return true
    }

    /**
     * The clock's marker table, which is what `/time set` and the game's own day-night logic address. The
     * game's test for sitting on a marker is exact-or-periodic, not a window, so moving onto
     * `minecraft:noon` has to make the game agree on the same call — while a marker this clock does not
     * carry is refused and leaves the position where it was.
     */
    private fun probeClockMarkers(): Boolean {
        val server = OMLGame.server ?: return false
        val clock = server.clockById(OVERWORLD_CLOCK) ?: return false
        val manager = server.clockManager()
        val markers = manager.markersOf(clock)
        val before = manager.instanceOf(clock)?.totalTicks() ?: return false
        val refused = manager.applyMarker(clock, ABSENT_MARKER)
        val unmoved = manager.instanceOf(clock)?.totalTicks() == before
        val moved = manager.applyMarker(clock, NOON_MARKER)
        val atNoon = manager.atMarker(clock, NOON_MARKER)
        val atAbsent = manager.atMarker(clock, ABSENT_MARKER)
        val restored = manager.applyTotalTicks(clock, before)
        val flags = listOf(
            "noon is offered" to (NOON_MARKER in markers),
            "day is offered" to ("minecraft:day" in markers),
            "an unknown marker is refused" to !refused,
            "the refusal moved nothing" to unmoved,
            "the move landed" to moved,
            "the game agrees the clock is there" to atNoon,
            "an unknown marker is nowhere" to !atAbsent,
            "the position restores" to restored,
        )
        E2E.check(
            "api.clock_markers",
            flags.all { it.second },
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; markers=" +
                "${markers.sorted()}, $before -> ${manager.instanceOf(clock)?.totalTicks()} after " +
                "applyMarker(noon)=$moved",
        )
        return true
    }

    private var customClockWindowTick = -1
    private var customClockWindowStart = 0L
    private var customClockFlags: List<Pair<String, Boolean>> = emptyList()

    /**
     * A clock and a timeline this mod declared, measured by what the game made of them: both ids are in the
     * game's own registry tables, the timeline answers with the clock it drives and the two markers it names
     * (including the one `/time set` hides), and the server's clock manager runs an instance for a clock no
     * dimension binds. The refusals are the game's: a marker belonging to another clock cannot move this one,
     * and only the command-visible marker is offered. The position is then watched for [CLOCK_RATE_WINDOW]
     * ticks, because "the mod's own clock counts" is a claim about what the game counted, not about OML.
     */
    private fun probeCustomClock(): Boolean {
        val server = OMLGame.server ?: return false
        val clock = server.clockById(MOD_CLOCK) ?: return false
        val timelines = server.registry(Registries.TIMELINE) ?: return false
        val manager = server.clockManager()
        val instance = manager.instanceOf(clock) ?: return false
        if (customClockWindowTick < 0) {
            val offered = manager.markersOf(clock)
            val foreignRefused = manager.applyMarker(clock, NOON_MARKER)
            val moved = manager.applyMarker(clock, MOD_MARKER_HIGH)
            customClockFlags = listOf(
                "the mod's clock is in the game's table" to (MOD_CLOCK in server.clockIds),
                "the mod's timeline is in the game's table" to (MOD_TIMELINE in server.timelineIds),
                "the timeline drives the mod's clock" to (timelines.clockDrivenBy(MOD_TIMELINE) == MOD_CLOCK),
                "both declared markers read back" to (
                    timelines.timeMarkersOf(MOD_TIMELINE) ==
                        mapOf(MOD_MARKER_LOW to 0, MOD_MARKER_HIGH to MOD_MARKER_HIGH_TICKS)
                    ),
                "markers are gathered per clock" to (
                    server.markerIdsDriving(MOD_CLOCK).toSet() == setOf(MOD_MARKER_LOW, MOD_MARKER_HIGH)
                    ),
                "an unregistered clock has none" to server.markerIdsDriving(ABSENT_CLOCK).isEmpty(),
                "only the command-visible marker is offered" to (
                    MOD_MARKER_HIGH in offered && MOD_MARKER_LOW !in offered
                    ),
                "a marker of another clock is refused" to !foreignRefused,
                "the mod's marker moved the clock" to (moved && manager.atMarker(clock, MOD_MARKER_HIGH)),
            )
            customClockWindowStart = instance.totalTicks()
            customClockWindowTick = server.tickCount
            return false
        }
        val elapsed = server.tickCount - customClockWindowTick
        if (elapsed < CLOCK_RATE_WINDOW) return false
        // The window opens inside one tick and closes inside another, so the game may have counted one less
        // or one more than the tick delta; anything else is the clock not running.
        val advanced = instance.totalTicks() - customClockWindowStart
        val flags = customClockFlags + ("the clock counts on its own" to (advanced in (elapsed - 1L)..(elapsed + 1L)))
        E2E.check(
            "content.custom_clock",
            flags.all { it.second },
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; clock at " +
                "${instance.totalTicks()} advanced $advanced in $elapsed ticks, offered markers=" +
                "${manager.markersOf(clock).sorted()}, timelines=${server.timelineIds.sorted()}",
        )
        return true
    }

    /**
     * The world's own spawn record, measured through the game. Three constraints decide what the answers mean:
     * `ServerLevel`'s read and write both forward to `MinecraftServer`, so one record serves every level; the
     * record's **own** dimension is what the game resolves at respawn time (that is how `/setworldspawn` works
     * too), so re-pointing it is a real move rather than a field that is stored and ignored; and what comes back
     * is the request moved inside that dimension's world border, which is where a respawn actually lands. The
     * restore at the end is not decoration — the record is saved with the world, and one left pointing into
     * another dimension breaks every probe that respawns a player.
     */
    private fun probeWorldSpawn(): Boolean {
        val server = OMLGame.server ?: return false
        val b = server.levelById(MOD_DIMENSION_B) ?: return false
        val before = server.worldSpawn
        val refusedDimension = server.setWorldSpawn(ABSENT_DIMENSION, ANCHOR_POSITION)
        val keptOnRefusal = server.worldSpawn == before
        val wrote = server.setWorldSpawn(OVERWORLD, ANCHOR_POSITION, ANCHOR_YAW)
        val held = server.worldSpawn
        val fromLevelB = b.respawnData
        val repointed = server.setWorldSpawn(MOD_DIMENSION_B, ANCHOR_POSITION, ANCHOR_YAW)
        val nowNamingB = server.worldSpawn
        val border = b.worldBorder
        val borderWas = border.size
        // A border this tight puts ANCHOR_POSITION's column outside it, so the game keeps the request but hands
        // back its own centre — and the centre is where a respawn actually lands.
        border.setSize(TIGHT_BORDER_SIZE)
        val refusedOutsideBorder = server.setWorldSpawn(MOD_DIMENSION_B, OUTSIDE_BORDER_POSITION, ANCHOR_YAW)
        val movedInside = server.worldSpawn
        border.setSize(borderWas)
        val restored = server.setWorldSpawn(
            before.globalPos().dimension().identifier().toString(),
            before.pos(),
            before.yaw(),
            before.pitch(),
        )
        val flags = listOf(
            "an unknown dimension is refused" to !refusedDimension,
            "the refusal left the record alone" to keptOnRefusal,
            "the write reads back through the game's own record" to (
                wrote && held.pos() == ANCHOR_POSITION && held.yaw() == ANCHOR_YAW
                ),
            "the record names the world it was pointed at" to (
                held.globalPos().dimension().identifier().toString() == OVERWORLD
                ),
            "every level reads the one record" to (fromLevelB == held),
            "the record can be pointed at another dimension" to (
                repointed && nowNamingB.pos() == ANCHOR_POSITION &&
                    nowNamingB.globalPos().dimension().identifier().toString() == MOD_DIMENSION_B
                ),
            "a position outside that dimension's border reads back inside it" to (
                !refusedOutsideBorder && movedInside.pos().x == 0 && movedInside.pos().z == 0 &&
                    movedInside.globalPos().dimension().identifier().toString() == MOD_DIMENSION_B
                ),
            "the record the world started with is put back" to restored,
        )
        E2E.check(
            "api.world_spawn_anchor",
            flags.all { it.second },
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; wrote=$wrote " +
                "held=${held.globalPos()} yaw=${held.yaw()} fromAnotherLevel=${fromLevelB.globalPos()} " +
                "refusedDimension=$refusedDimension repointed=$repointed now=${nowNamingB.globalPos()} " +
                "refusedOutside=$refusedOutsideBorder insideBorder=${movedInside.globalPos()} " +
                "restored=$restored was=$before",
        )
        return true
    }

    /**
     * A player's own respawn anchor. The refusal is the point of the first leg: the game will store any
     * dimension key it is handed, so a typo would only surface when the player dies — OML has to turn that
     * down at the write, with the anchor left exactly as it was. The read-back then goes through the game's
     * own `getRespawnConfig()`, including the `forced` mark that tells the game to use the position as given
     * instead of re-checking for a bed.
     */
    private fun probePlayerRespawnAnchor(player: ServerPlayer): Boolean {
        val server = OMLGame.server ?: return false
        val before = player.respawnAnchor
        val refused = player.setRespawnAnchor(ABSENT_DIMENSION, ANCHOR_POSITION)
        val kept = player.respawnAnchor == before
        val set = player.setRespawnAnchor(MOD_DIMENSION, ANCHOR_POSITION, ANCHOR_YAW, announce = false)
        val config = player.respawnConfig
        val cleared = player.clearRespawnAnchor()
        val restored = before == null ||
            player.setRespawnAnchor(
                before.globalPos().dimension().identifier().toString(),
                before.pos(),
                before.yaw(),
                before.pitch(),
                announce = false,
            )
        val flags = listOf(
            "an unknown dimension is refused" to !refused,
            "the refusal left the anchor alone" to kept,
            "the game holds the anchor it was given" to (
                set && config != null && config.respawnData().pos() == ANCHOR_POSITION &&
                    config.respawnData().yaw() == ANCHOR_YAW
                ),
            "the anchor names its dimension" to (
                config?.respawnData()?.globalPos()?.dimension()?.identifier()?.toString() == MOD_DIMENSION
                ),
            "an explicit anchor is used as given" to (config != null && config.forced()),
            "clearing leaves the game with none" to (cleared && player.respawnConfig == null),
            "the player's own anchor is restored" to restored,
        )
        E2E.check(
            "api.player_respawn_anchor",
            flags.all { it.second },
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; before=$before " +
                "refused=$refused set=$set config=${config?.respawnData()?.globalPos()} forced=" +
                "${config?.forced()} cleared=$cleared now=${player.respawnConfig}",
        )
        return true
    }

    /**
     * The node-addressed reads, measured against the game's own constants and its own two extreme sets. The
     * two claims worth pinning are that an id reaches the very object the game gates chat on, and that a
     * command level is not an id — the game spells `Permissions.COMMANDS_GAMEMASTER` as
     * `HasCommandLevel(GAMEMASTERS)`, so it has no id to report and OML says so instead of inventing one.
     */
    private fun probePermissionNodes(): Boolean {
        val all = PermissionSet.ALL_PERMISSIONS
        val none = PermissionSet.NO_PERMISSIONS
        val flags = listOf(
            "the game's chat node is addressable by id" to (permissionNode(CHAT_NODE) == Permissions.CHAT_SEND_MESSAGES),
            "the whole table answers it" to all.hasNode(CHAT_NODE),
            "no table holds it" to !none.hasNode(CHAT_NODE),
            "a mod node is refused by the empty table" to !none.hasNode(MOD_NODE),
            "an unparseable id answers false, not an exception" to !all.hasNode("bad id"),
            "a level reads the way the game reads it" to (
                all.hasCommandLevel("owners") && !none.hasCommandLevel("owners")
                ),
            "an unknown level name is refused" to !all.hasCommandLevel("headmaster"),
            "a command level is not an id" to (permissionNodeId(Permissions.COMMANDS_GAMEMASTER) == null),
            "an atom does have one" to (permissionNodeId(Permissions.CHAT_SEND_MESSAGES) == CHAT_NODE),
        )
        E2E.check(
            "api.permission_nodes",
            flags.all { it.second },
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; " +
                "all=$all none=$none chatNode=${permissionNode(CHAT_NODE)}",
        )
        return true
    }

    /**
     * The grant path, which is only meaningful for a player: the game builds a player's set from its op level
     * and chat state, hands it to OML's hook, and every consumer — the command gate included — then reads the
     * wrapper. So the probe grants a node nothing in vanilla knows, **asks the game's own
     * `permissions().hasPermission(...)`** whether it now answers, and dispatches the command that declares
     * that node as its requirement: refused before, run after, refused again once revoked. `clear` drops the
     * override so the game's own composition is the whole answer again.
     */
    private fun probePermissionGrant(player: ServerPlayer): Boolean {
        val server = OMLGame.server ?: return false
        val node = permissionNode(MOD_NODE) ?: return false
        val baseOurs = player.hasPermissionNode(MOD_NODE)
        val baseGame = player.permissions().hasPermission(node)
        val granted = player.grantPermissionNode(MOD_NODE)
        val grantAnswers = player.permissions().hasPermission(node)
        val reGranted = player.grantPermissionNode(MOD_NODE)
        val commands = server.getCommands()
        gatedRuns = 0
        commands.performPrefixedCommand(player.createCommandSourceStack(), "oml_e2e_gated")
        val runsAfterGrant = gatedRuns
        val revoked = player.revokePermissionNode(MOD_NODE)
        val revokeAnswers = player.permissions().hasPermission(node)
        commands.performPrefixedCommand(player.createCommandSourceStack(), "oml_e2e_gated")
        val runsAfterRevoke = gatedRuns
        val cleared = player.clearPermissionNodeGrant(MOD_NODE)
        val overrides = player.permissionNodeOverrides
        val flags = listOf(
            "nothing grants a mod node to start with" to (!baseOurs && !baseGame),
            "granting reports a change" to granted,
            "the game's own set answers the grant" to grantAnswers,
            "granting twice reports no change" to !reGranted,
            "the gated command ran once granted" to (runsAfterGrant == 1),
            "revoking reports a change" to revoked,
            "the game's own set stops answering" to !revokeAnswers,
            "the gated command stayed refused once revoked" to (runsAfterRevoke == 1),
            "clearing drops the override" to cleared,
            // The table is per player and shared by every pack in the run, so the claim has to be about *this*
            // node: cleared here, while another pack's grant (the kit grants one when a player joins) stays put.
            "this node is no longer overridden" to !overrides.containsKey(MOD_NODE),
            "another pack's grant outlives this clear" to player.hasPermissionNode(OTHER_PACK_NODE),
            "the player is back to the game's own answer" to !player.hasPermissionNode(MOD_NODE),
        )
        E2E.check(
            "api.permission_grant",
            flags.all { it.second },
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; base=$baseOurs " +
                "grant=$granted revoke=$revoked cleared=$cleared runs after grant/revoke=" +
                "$runsAfterGrant/$runsAfterRevoke overrides=$overrides level=${player.permissionLevel}",
        )
        return true
    }

    private var teleportPhase = 0
    private var teleportTick = -1
    private var pressWaitTick = -1
    private var pressWriteTick = -1
    private var pressDataPhase = 0
    private var pressDataTick = -1
    private var pressDataProgressBefore = -1
    private var pressDataWriteTick = -1
    private var pressDataIdentity = false
    private var teleportCow: LivingEntity? = null
    private var teleportMoved: Entity? = null
    private var teleportArrived = false

    /**
     * Force-loads the probe column of [dimensionId] and answers only once the game has it **ticking**: a chunk
     * that is merely present refuses block writes and does not hold entities yet, so waiting on `isLoaded`
     * would have the probes write into a column the game has not finished building. The ticket call is
     * idempotent, which is why its return value is ignored here.
     */
    private fun chunkReady(server: MinecraftServer, dimensionId: String, position: BlockPos): Boolean {
        val level = server.levelById(dimensionId) ?: return false
        level.forceLoadChunk(position)
        return tickingKey(dimensionId, ChunkPos.containing(position)) in tickingChunks
    }

    /**
     * Moving an entity between two dimensions this mod declared, both of them empty of players. Every claim is
     * the game's: the entity is in the second level's own list and not in the first's any more, its position is
     * the one the call asked for, and the two levels still disagree about their clock because their declared
     * types do. A destination the server has not loaded is refused and leaves the entity where it was.
     */
    private fun probeDimensionTeleport(): Boolean {
        val server = OMLGame.server ?: return false
        val a = server.levelById(MOD_DIMENSION) ?: return false
        val b = server.levelById(MOD_DIMENSION_B) ?: return false
        if (teleportPhase == 0) {
            if (!(chunkReady(server, MOD_DIMENSION, TELEPORT_POSITION) &&
                chunkReady(server, MOD_DIMENSION_B, TELEPORT_POSITION))) {
                if (teleportTick < 0) teleportTick = server.tickCount
                if (server.tickCount - teleportTick < CHUNK_TICK_WAIT_LIMIT) return false
                E2E.check(
                    "api.dimension_teleport", false,
                    "the declared dimensions never loaded their forced column in " +
                        "${server.tickCount - teleportTick} ticks: a=${a.isLoaded(TELEPORT_POSITION)} " +
                        "b=${b.isLoaded(TELEPORT_POSITION)}",
                )
                teleportPhase = 3
                return true
            }
            val cow = a.spawnLivingEntity(COW, TELEPORT_X, TELEPORT_Y, TELEPORT_Z)
            if (cow == null) {
                E2E.check("api.dimension_teleport", false, "no cow could be spawned in the declared dimension")
                teleportPhase = 3
                return true
            }
            teleportCow = cow
            teleportMoved = cow.teleportToLevel(MOD_DIMENSION_B, TELEPORT_X, TELEPORT_Y, TELEPORT_Z)
            teleportTick = server.tickCount
            teleportPhase = 1
            return false
        }
        if (teleportPhase == 1) {
            // The destination's section storage queues this arrival and drains it later in the tick, while
            // the probe reads at the head of tickServer: the id table answers for the moved entity only
            // after that drain. Waiting for the answer is the assertion — an entity that never arrives is
            // a defect, reported within the run rather than as a hung one.
            val pending = teleportMoved
            teleportArrived = pending != null && b.getEntity(pending.id) === pending
            if (!teleportArrived && server.tickCount - teleportTick < ENTITY_ARRIVAL_WAIT_TICKS) return false
            teleportPhase = 2
            return false
        }
        val cow = teleportCow ?: return true
        val moved = teleportMoved
        val refused = moved?.teleportToLevel(ABSENT_DIMENSION_B, 0.0, TELEPORT_Y, 0.0)
        val flags = listOf(
            "the move answered an entity" to (moved != null),
            "the game put it in the second dimension" to (moved?.dimensionId == MOD_DIMENSION_B),
            "the level it reports is the server's own" to (moved?.level() === b),
            "the column is the one the request named" to (
                moved?.blockPosition()?.x == 72 && moved.blockPosition().z == 72
                ),
            "the game still holds it" to (moved != null && !moved.isRemoved),
            "the first level let the original go" to (a.getEntity(cow.id) == null),
            "the second level holds the moved one" to teleportArrived,
            "an unloaded destination is refused" to (refused == null && moved?.level() === b),
            "the two types still disagree about their clock" to (
                a.clock?.getRegisteredName() == MOD_CLOCK && b.clock == null
                ),
        )
        // Taken before the teardown: after `discard()` and the two releases the destination holds nothing,
        // so an empty bEntities / null byId there would describe the cleanup instead of the failure.
        val seen = "moved=${moved?.dimensionId} refused=$refused " +
            "aClock=${a.clock?.getRegisteredName()} bClock=${b.clock?.getRegisteredName()} " +
            "bEntities=${b.entities().size} byId=${moved?.let { b.getEntity(it.id) }} " +
            "pos=${moved?.blockPosition()} fell=${moved?.position()} " +
            "byUuid=${moved?.let { b.getEntityInAnyDimension(it.uuid) }}"
        val back = moved?.teleportToLevel(MOD_DIMENSION, TELEPORT_X, TELEPORT_Y, TELEPORT_Z)
        (back ?: moved ?: cow as Entity).discard()
        a.releaseForceLoadedChunk(TELEPORT_POSITION)
        b.releaseForceLoadedChunk(TELEPORT_POSITION)
        E2E.check(
            "api.dimension_teleport",
            flags.all { it.second } && back?.dimensionId == MOD_DIMENSION,
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; $seen " +
                "back=${back?.dimensionId} cowRemoved=${cow.isRemoved}",
        )
        teleportPhase = 3
        teleportCow = null
        teleportMoved = null
        teleportArrived = false
        return true
    }

    /**
     * The block entity a placed block owns, read as the game's own object. The two refusals are the point:
     * an unloaded position answers null rather than reaching through `getChunkAt` and loading a chunk nobody
     * asked for, and taking the block away takes its block entity with it.
     */
    private fun probeBlockEntityRead(): Boolean {
        val server = OMLGame.server ?: return false
        val level = server.levelById(MOD_DIMENSION) ?: return false
        if (!chunkReady(server, MOD_DIMENSION, PRESS_POSITION)) {
            if (pressWaitTick < 0) pressWaitTick = server.tickCount
            if (server.tickCount - pressWaitTick < CHUNK_TICK_WAIT_LIMIT) return false
            E2E.check(
                "api.block_entity_read", false,
                "the declared dimension never brought ${PRESS_POSITION}'s chunk to ticking in " +
                    "$CHUNK_TICK_WAIT_LIMIT ticks (loaded=${level.isLoaded(PRESS_POSITION)})",
            )
            return true
        }
        // One tick of distance between the promotion and the write: the game applies a block change into a
        // chunk it promoted on this very tick through its own post-promotion pass, so the same-tick read-back
        // is not the game's finished answer. Measured: same-tick verdict false, next-tick verdict true, with
        // the position holding the block both times.
        if (pressWriteTick < 0) {
            pressWriteTick = server.tickCount
            return false
        }
        val placed = level.setBlockAt(PRESS_POSITION, PRESS_BLOCK)
        val idAfterWrite = level.blockIdAt(PRESS_POSITION)
        val entity = level.blockEntityAt(PRESS_POSITION)
        val unloaded = level.blockEntityAt(PRESS_POSITION.offset(4096, 0, 0))
        val removed = level.setBlockAt(PRESS_POSITION, "minecraft:air")
        val afterRemoval = level.blockEntityAt(PRESS_POSITION)
        val press = OMLRegistries.block(PRESS_BLOCK)
        val flags = listOf(
            "the block took, and the game holds it" to (placed && idAfterWrite == PRESS_BLOCK),
            "the game owns a block entity there" to (entity != null),
            "it reports its own position and state" to (
                entity?.blockPos == PRESS_POSITION && entity.blockState.block == press
                ),
            "an unloaded position answers null, without loading it" to (unloaded == null),
            "taking the block took the block entity too" to (removed && afterRemoval == null),
            "a press is not storage" to (entity as? Container == null),
        )
        E2E.check(
            "api.block_entity_read",
            flags.all { it.second },
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; placed=$placed " +
                "entity=${entity?.javaClass?.simpleName} pos=${entity?.blockPos} state=${entity?.blockState} " +
                "unloadedAnswer=$unloaded afterRemoval=$afterRemoval removed=$removed " +
                "idNow=${level.blockIdAt(PRESS_POSITION)}",
        )
        level.releaseForceLoadedChunk(PRESS_POSITION)
        return true
    }

    /**
     * A declared block entity's persistent store, reached from outside its own ticker, in two dimensions at the
     * same coordinates. Isolation is the point: a store addressed by position alone would answer for both
     * dimensions with one value, while the game holds one block entity per position **per level**. The progress
     * leg shows the address is the store the game ticks rather than a copy of it — the techmod's press counts a
     * cycle in `progress` every tick — and the identity leg shows the ticker's view and this one are the same
     * object. Three refusals close the contract: a position whose block has no block entity, an unloaded
     * position, and a dimension this server never loaded.
     */
    private fun probeBlockDataAddressing(): Boolean {
        val server = OMLGame.server ?: return false
        val a = server.levelById(MOD_DIMENSION) ?: return false
        val b = server.levelById(MOD_DIMENSION_B) ?: return false
        if (pressDataPhase == 0) {
            if (!(chunkReady(server, MOD_DIMENSION, PRESS_DATA_POSITION) &&
                    chunkReady(server, MOD_DIMENSION_B, PRESS_DATA_POSITION))) {
                if (pressDataTick < 0) pressDataTick = server.tickCount
                if (server.tickCount - pressDataTick < CHUNK_TICK_WAIT_LIMIT) return false
                E2E.check(
                    "api.block_data_addressing", false,
                    "neither dimension brought ${PRESS_DATA_POSITION}'s column to ticking in " +
                        "$CHUNK_TICK_WAIT_LIMIT ticks (a=${a.isLoaded(PRESS_DATA_POSITION)} " +
                        "b=${b.isLoaded(PRESS_DATA_POSITION)})",
                )
                pressDataPhase = 3
                return true
            }
            // One tick after the promotion before anything is written: the game is still finishing the chunk.
            pressDataPhase = 1
            return false
        }
        if (pressDataPhase == 1) {
            val placedA = a.setBlockAt(PRESS_DATA_POSITION, PRESS_BLOCK)
            val placedB = b.setBlockAt(PRESS_DATA_POSITION, PRESS_BLOCK)
            val dataA = server.blockDataAt(MOD_DIMENSION, PRESS_DATA_POSITION)
            val dataB = server.blockDataAt(MOD_DIMENSION_B, PRESS_DATA_POSITION)
            if (!(placedA && placedB && dataA != null && dataB != null)) {
                E2E.check(
                    "api.block_data_addressing", false,
                    "no store to address (placed a=$placedA/${a.blockIdAt(PRESS_DATA_POSITION)} " +
                        "b=$placedB/${b.blockIdAt(PRESS_DATA_POSITION)}, " +
                        "data a=${dataA != null} b=${dataB != null})",
                )
                pressDataPhase = 3
                return true
            }
            dataA.putInt("side", 1)
            dataB.putInt("side", 2)
            dataA.putString("only_in_a", "a")
            pressDataIdentity = (a.blockEntityAt(PRESS_DATA_POSITION) as? OMLBlockDataCarrier)
                ?.blockData === dataA
            pressDataProgressBefore = dataA.getInt("progress")
            pressDataWriteTick = server.tickCount
            pressDataPhase = 2
            return false
        }
        val dataA = server.blockDataAt(MOD_DIMENSION, PRESS_DATA_POSITION)
        // A block entity created during this tick's probe step is only visited by the game's own block-entity
        // pass afterwards, so one tick of distance still reads the value it was born with: wait for the ticker
        // to show itself, bounded.
        if ((dataA?.getInt("progress") ?: 0) <= pressDataProgressBefore &&
            server.tickCount - pressDataWriteTick < PROGRESS_TICK_WAIT_LIMIT) return false
        val dataB = server.blockDataAt(MOD_DIMENSION_B, PRESS_DATA_POSITION)
        val progressAfter = dataA?.getInt("progress") ?: -1
        val sideA = dataA?.getInt("side", 0)
        val sideB = dataB?.getInt("side", 0)
        val markerInB = dataB?.getString("only_in_a")
        // The removal comes after the reads above: dropping a key is also the way to lose the evidence a
        // second leg is asking about.
        val removedKey = dataA?.remove("side")
        val removedAgain = dataA?.remove("side")
        val cleared = a.setBlockAt(PRESS_DATA_POSITION, AIR) && b.setBlockAt(PRESS_DATA_POSITION, AIR)
        val flags = listOf(
            "both dimensions hold a store for the same coordinates" to (dataA != null && dataB != null),
            "each keeps its own value" to (sideA == 1 && sideB == 2),
            "one side's key is not the other's" to (markerInB == null),
            "the address reaches the store the ticker writes" to (progressAfter > pressDataProgressBefore),
            "and it is the same object the tick handler is handed" to pressDataIdentity,
            "a key can be dropped, and dropping it twice says so" to (
                removedKey == true && removedAgain == false && dataA != null && !dataA.hasKey("side")
                ),
            "a block with no block entity has no store" to (
                server.blockDataAt(MOD_DIMENSION, PRESS_DATA_POSITION.offset(8, 0, 0)) == null
                ),
            "an unloaded position answers null" to (
                server.blockDataAt(MOD_DIMENSION, BlockPos(900_000, 40, 900_000)) == null
                ),
            "a dimension this server never loaded answers null" to (
                server.blockDataAt(ABSENT_DIMENSION, PRESS_DATA_POSITION) == null
                ),
            "the column is left as it was found" to cleared,
        )
        E2E.check(
            "api.block_data_addressing",
            flags.all { it.second },
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; " +
                "progress $pressDataProgressBefore -> $progressAfter identity=$pressDataIdentity " +
                "sideA=$sideA sideB=$sideB markerInB=$markerInB " +
                "removed=$removedKey/$removedAgain cleared=$cleared " +
                "ticks=${server.tickCount - pressDataWriteTick}",
        )
        a.releaseForceLoadedChunk(PRESS_DATA_POSITION)
        b.releaseForceLoadedChunk(PRESS_DATA_POSITION)
        return true
    }

    /**
     * A player carried into the declared dimension and back, which is the same transition with the
     * connection moved along: the answer is the player the server holds afterwards, and the dimension's
     * declared clock is what the player now stands in.
     */
    private fun probePlayerDimensionTeleport(player: ServerPlayer): Boolean {
        val server = OMLGame.server ?: return false
        if (!chunkReady(server, MOD_DIMENSION, TELEPORT_POSITION)) return false
        val from = player.blockPosition()
        val there = player.teleportToLevel(MOD_DIMENSION, TELEPORT_X, TELEPORT_Y, TELEPORT_Z)
        val arrived = there?.level() === server.levelById(MOD_DIMENSION)
        val clock = there?.level()?.clock
        // Back to the column they came from, not to the probe's own coordinates: dropping a player inside
        // terrain would settle them somewhere else and the next probe, or the next run, reads that as a
        // teleport that did not happen.
        val back = there?.teleportToLevel(OVERWORLD, from.x + 0.5, from.y + 1.0, from.z + 0.5)
        val flags = listOf(
            "the player arrived in the declared dimension" to (there != null && arrived),
            "the game kept the same player instance" to (there === player),
            "the dimension's clock is the mod's own" to (clock?.getRegisteredName() == MOD_CLOCK),
            // The game settles a player out of the block they land inside, so the column is the promise and
            // the exact block height is the game's own answer.
            "the player is back in the column they came from" to (
                back?.dimensionId == OVERWORLD && back.blockPosition().x == from.x &&
                    back.blockPosition().z == from.z
                ),
        )
        E2E.check(
            "api.player_dimension_teleport",
            flags.all { it.second },
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; there=" +
                "${there?.dimensionId} same=${there === player} clock=${clock?.getRegisteredName()} " +
                "back=${back?.dimensionId} from=$from now=${back?.blockPosition()}",
        )
        server.levelById(MOD_DIMENSION)?.releaseForceLoadedChunk(TELEPORT_POSITION)
        return true
    }

    private var chunkPhase = 0
    private var chunkPhaseTick = -1

    /**
     * The chunk lifecycle, driven in the dimension this mod declared — where no player will ever walk. Forcing
     * the chunk is the game's own `/forceload` ticket, and what follows is the game's doing: it generates the
     * column, dispatches `CHUNK_STARTED_TICKING` with its own `LevelChunk`, and only then does the position
     * answer as loaded and take a block. Releasing the ticket has to make the game drop the chunk again, and
     * `CHUNK_UNLOADED` is that report. Every number here is a tick window, so a phase that never arrives
     * reports red instead of waiting for the run to end.
     */
    private fun probeChunkLifecycle(): Boolean {
        val server = OMLGame.server ?: return false
        val level = server.levelById(MOD_DIMENSION) ?: return false
        val target = ChunkPos.containing(CHUNK_PROBE_POSITION)
        if (chunkPhase == 0) {
            chunkTickingSeen = false
            chunkUnloadedSeen = false
            val added = level.forceLoadChunk(CHUNK_PROBE_POSITION)
            val read = level.isForceLoaded(CHUNK_PROBE_POSITION)
            val listed = target in level.forceLoadedChunkPositions
            val already = level.forceLoadChunk(CHUNK_PROBE_POSITION)
            if (!added || !read || !listed || already) {
                E2E.check(
                    "api.chunk_lifecycle", false,
                    "forceLoadChunk=$added isForceLoaded=$read listed=$listed second add=$already " +
                        "(a re-add must not claim it changed the table), forced=${level.forceLoadedChunkPositions}",
                )
                chunkPhase = 4
                return true
            }
            chunkPhase = 1
            chunkPhaseTick = server.tickCount
            return false
        }
        val elapsed = server.tickCount - chunkPhaseTick
        if (chunkPhase == 1) {
            if (!chunkTickingSeen) {
                if (elapsed < CHUNK_TICK_WAIT_LIMIT) return false
                E2E.check(
                    "api.chunk_lifecycle", false,
                    "CHUNK_STARTED_TICKING never arrived for $target of $MOD_DIMENSION in $elapsed ticks; " +
                        "isLoaded=${level.isLoaded(CHUNK_PROBE_POSITION)} forced=${level.forceLoadedChunkPositions}",
                )
                chunkPhase = 4
                return true
            }
            val loaded = level.isLoaded(CHUNK_PROBE_POSITION)
            val wrote = loaded && level.setBlockAt(CHUNK_PROBE_POSITION, CHUNK_PROBE_BLOCK)
            val read = level.blockIdAt(CHUNK_PROBE_POSITION)
            val released = level.releaseForceLoadedChunk(CHUNK_PROBE_POSITION)
            val stillForced = level.isForceLoaded(CHUNK_PROBE_POSITION)
            val reReleased = level.releaseForceLoadedChunk(CHUNK_PROBE_POSITION)
            chunkPhaseFlags = listOf(
                "the game reported the chunk ticking" to chunkTickingSeen,
                "the forced position reads as loaded" to loaded,
                "a block can be placed there" to (wrote && read == CHUNK_PROBE_BLOCK),
                "releasing the ticket answers changed" to released,
                "the table no longer holds it" to (!stillForced && !reReleased),
            )
            chunkPhase = 2
            chunkPhaseTick = server.tickCount
            return false
        }
        if (chunkPhase == 2) {
            if (!chunkUnloadedSeen) {
                if (elapsed < CHUNK_TICK_WAIT_LIMIT) return false
                chunkPhaseFlags += ("the game reported the chunk dropped" to false)
                E2E.check(
                    "api.chunk_lifecycle", false,
                    "${chunkPhaseFlags.filterNot { it.second }.map { it.first }} failed; " +
                        "CHUNK_UNLOADED never arrived in $elapsed ticks after release, " +
                        "isLoaded=${level.isLoaded(CHUNK_PROBE_POSITION)}",
                )
                chunkPhase = 4
                return true
            }
        }
        val flags = chunkPhaseFlags + ("the game reported the chunk dropped" to chunkUnloadedSeen)
        E2E.check(
            "api.chunk_lifecycle",
            flags.all { it.second },
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; " +
                "chunk $target of $MOD_DIMENSION loaded and dropped, " +
                "block=${level.blockIdAt(CHUNK_PROBE_POSITION)} at tick $elapsed after release",
        )
        chunkPhase = 4
        return true
    }

    private var chunkPhaseFlags: List<Pair<String, Boolean>> = emptyList()

    /**
     * The dimension this mod's content pack declared, measured by what the game made of it: a `ServerLevel`
     * exists for it (the game builds one for every entry in its own `dimension` table), it reports the declared
     * dimension type, that type carries the declared numbers, and the clock the type names is the same holder
     * the clock probes move. The weather test is the part that cannot be faked: `canHaveWeather()` is the
     * game's own `hasSkyLight && !hasCeiling && dimension != theEnd`, so declaring a roofed, sunless type is
     * what makes the game answer for this dimension the way it answers for the Nether — while the overworld,
     * read through the same call, still says yes.
     */
    private fun probeCustomDimension(): Boolean {
        val server = OMLGame.server ?: return false
        val dim = server.levelById(MOD_DIMENSION) ?: return false
        val overworld = server.levelById(OVERWORLD) ?: return false
        val clock = server.clockById(MOD_CLOCK) ?: return false
        val flags = listOf(
            "the game built a level for the declared dimension" to (MOD_DIMENSION in server.dimensionIds),
            "the type the level runs on is the declared one" to (dim.dimensionTypeId == MOD_DIMENSION_TYPE),
            "the declared type is in the game's table" to (MOD_DIMENSION_TYPE in server.dimensionTypeIds),
            "the game read the declared numbers" to (
                dim.dimensionType().height() == MOD_DIMENSION_HEIGHT &&
                    dim.dimensionType().minY() == MOD_DIMENSION_MIN_Y &&
                    dim.dimensionType().logicalHeight() == MOD_DIMENSION_HEIGHT &&
                    !dim.dimensionType().hasSkyLight() &&
                    dim.dimensionType().hasCeiling()
                ),
            "the type runs on the mod's clock" to (dim.clock == clock),
            "the clock counts for this dimension too" to ((dim.clockInstance?.totalTicks() ?: -1L) >= 0L),
            "a roofed sunless dimension gets no weather" to !dim.canHaveWeather(),
            "the overworld still does" to overworld.canHaveWeather(),
            "an undeclared dimension answers no level" to (server.levelById(ABSENT_DIMENSION) == null),
            "an undeclared type answers no type" to (server.dimensionTypeById("oml_testmod:absent_type") == null),
        )
        E2E.check(
            "content.custom_dimension",
            flags.all { it.second },
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; dimensions=" +
                "${server.dimensionIds.sorted()}, type=${dim.dimensionTypeId}, " +
                "height=${dim.dimensionType().height()} minY=${dim.dimensionType().minY()} " +
                "clock=${dim.clock?.getRegisteredName()} at ${dim.clockInstance?.totalTicks()}",
        )
        return true
    }

    /**
     * Rate and pause, measured by what the game counts: a clock at [FAST_RATE] has to add two ticks per game
     * tick, a paused one has to add none, and a resumed one has to start again at the game's own rate.
     * OML does no time arithmetic, so every number here is the game's. The refusals are the game's own
     * window — its clock manager would store any value, so OML checks against what `/time rate` accepts.
     */
    private fun probeClockRate(): Boolean {
        val server = OMLGame.server ?: return false
        // The windows measure what the game counted, so no other clock writer may be active inside one.
        if ("api.world_clock" !in reported || "api.clock_markers" !in reported) return false
        val level = server.levelById(OVERWORLD) ?: return false
        val clock = level.clock ?: return false
        val manager = level.clockManager()
        val instance = manager.instanceOf(clock) ?: return false
        val tick = server.tickCount
        if (clockPhaseTick < 0) {
            clockBaseline = instance.totalTicks()
            val refusedIdle = manager.applyRate(clock, IDLE_RATE)
            val refusedHuge = manager.applyRate(clock, IMPOSSIBLE_RATE)
            val accepted = manager.applyRate(clock, FAST_RATE)
            if (refusedIdle || refusedHuge || !accepted || instance.rate() != FAST_RATE) {
                E2E.check(
                    "api.clock_rate_and_pause",
                    false,
                    "rate window: 0 accepted=$refusedIdle, $IMPOSSIBLE_RATE accepted=$refusedHuge, " +
                        "$FAST_RATE accepted=$accepted and reads ${instance.rate()}",
                )
                clockRatePhase = 3
                return true
            }
            clockPhaseTick = tick
            clockPhaseStart = instance.totalTicks()
            return false
        }
        val elapsed = tick - clockPhaseTick
        val advanced = instance.totalTicks() - clockPhaseStart
        if (elapsed <= (if (clockRatePhase == 1) CLOCK_PAUSE_WINDOW else CLOCK_RATE_WINDOW)) {
            if (elapsed < CLOCK_WINDOW_LIMIT) return false
            E2E.check(
                "api.clock_rate_and_pause",
                false,
                "phase $clockRatePhase stalled after $elapsed ticks at rate ${instance.rate()}, " +
                    "paused=${instance.isPaused()}",
            )
            clockRatePhase = 3
            return true
        }
        val expected = when (clockRatePhase) {
            0 -> elapsed * 2L
            1 -> 0L
            else -> elapsed.toLong()
        }
        if (advanced != expected) {
            E2E.check(
                "api.clock_rate_and_pause",
                false,
                "phase $clockRatePhase (rate ${instance.rate()}, paused=${instance.isPaused()}) counted $advanced " +
                    "in $elapsed game ticks, the game's own arithmetic wants $expected",
            )
            clockRatePhase = 3
            return true
        }
        when (clockRatePhase) {
            0 -> {
                manager.applyPaused(clock, true)
                clockRatePhase = 1
            }
            1 -> {
                manager.applyPaused(clock, false)
                manager.applyRate(clock, 1f)
                clockRatePhase = 2
            }
            else -> {
                val restored = manager.applyTotalTicks(clock, clockBaseline)
                E2E.check(
                    "api.clock_rate_and_pause",
                    restored && !instance.isPaused() && instance.rate() == 1f,
                    "the game counted $FAST_RATE, nothing while paused, and 1.0 after resuming; the " +
                        "position restored to $clockBaseline is=${instance.totalTicks()} " +
                        "rate=${instance.rate()} paused=${instance.isPaused()}",
                )
                clockRatePhase = 3
                return true
            }
        }
        clockPhaseTick = tick
        clockPhaseStart = instance.totalTicks()
        return false
    }

    /**
     * Singleplayer keeps a second copy of the clock: the rendered level's, which the server pushes as soon
     * as it changes a clock (the game's own `modifyClock` broadcasts the new state to that dimension). The
     * probe writes a position the client's copy cannot reach by counting, then waits for it to arrive —
     * the half of this surface no server-side read can show. The client's side of the pair is its own
     * `ClientClockManager`, which exposes no write path at all: the client is told the time.
     */
    private fun probeClockSync(): Boolean {
        val server = OMLGame.server ?: return false
        // Its window is a clock write too: it may not run while another clock probe is still counting.
        if ("api.clock_rate_and_pause" !in reported) return false
        val clock = server.clockById(OVERWORLD_CLOCK) ?: return false
        val manager = server.clockManager()
        val instance = manager.instanceOf(clock) ?: return false
        if (dedicated) return false
        val clientLevel = OMLGame.client?.level ?: return false
        val clientClock = clientLevel.clock ?: return false
        val clientManager = clientLevel.clockManager()
        val clientInstance = clientManager.instanceOf(clientClock) ?: return false
        val tick = server.tickCount
        if (clockSyncPhase == 0) {
            clockSyncPrevious = instance.totalTicks()
            clockSyncTarget = clockSyncPrevious + CLOCK_SYNC_JUMP
            if (!manager.applyTotalTicks(clock, clockSyncTarget)) {
                E2E.check("api.clock_client_sync", false, "the server refused the write of $clockSyncTarget")
                clockSyncPhase = 2
                return true
            }
            clockSyncTick = tick
            clockSyncPhase = 1
            return false
        }
        if (clockSyncPhase != 1) return true
        // The client's manager is `ClientClockManager`: it receives time and has no write method to refuse.
        val seen = clientInstance.totalTicks()
        val arrived = seen >= clockSyncTarget && seen <= clockSyncTarget + CLOCK_SYNC_TOLERANCE
        if (arrived || tick - clockSyncTick > CLOCK_SYNC_WAIT_TICKS) {
            manager.applyTotalTicks(clock, clockSyncPrevious)
            E2E.check(
                "api.clock_client_sync",
                arrived,
                "wrote $clockSyncTarget on the server; the client read $seen after ${tick - clockSyncTick} " +
                    "ticks (the server stands at ${instance.totalTicks()})",
            )
            clockSyncPhase = 2
            return true
        }
        return false
    }

    /**
     * Game rules addressed by registry id, read and written in the game's own spelling. The value is
     * flipped and restored; the refusals are the half that cannot be faked — an unregistered id, and a
     * value the rule's own serializer will not parse.
     */
    private fun probeGameRules(): Boolean {
        val server = OMLGame.server ?: return false
        val original = server.gameRuleValue(KEEP_INVENTORY_RULE) ?: return false
        val flipped = if (original == "true") "false" else "true"
        val wrote = server.setGameRule(KEEP_INVENTORY_RULE, flipped)
        val readBack = server.gameRuleValue(KEEP_INVENTORY_RULE)
        val restored = server.setGameRule(KEEP_INVENTORY_RULE, original)
        val listed = BuiltInRegistries.GAME_RULE.contains(KEEP_INVENTORY_RULE)
        val unknownRead = server.gameRuleValue("oml_testmod:absent")
        val unknownWrite = server.setGameRule("oml_testmod:absent", "true")
        val unknownListed = BuiltInRegistries.GAME_RULE.contains("oml_testmod:absent")
        val unparseable = server.setGameRule(KEEP_INVENTORY_RULE, "3")
        E2E.check(
            "api.server_game_rules",
            wrote && readBack == flipped && restored &&
                server.gameRuleValue(KEEP_INVENTORY_RULE) == original && listed && !unknownListed &&
                unknownRead == null && !unknownWrite && !unparseable,
            "$KEEP_INVENTORY_RULE $original -> $readBack -> ${server.gameRuleValue(KEEP_INVENTORY_RULE)}; " +
                "in the registry: $listed, unknown id read=$unknownRead write=$unknownWrite " +
                "listed=$unknownListed, \"3\" into a boolean rule=$unparseable",
        )
        return true
    }

    /**
     * The three argument-carrying particles a bare id could not express, accepted by the game's own sender,
     * and the rejections: an unknown item and an unknown block. The send lives on `ServerLevel`, so a client
     * has no send call to refuse — that half of the old runtime check is the type system's now.
     */
    private fun probeArgumentParticles(): Boolean {
        val server = OMLGame.server ?: return false
        val world = server.levelById(OVERWORLD) ?: return false
        val item = world.spawnItemParticles("minecraft:diamond_sword", 0.5, 200.0, 0.5, count = 4)
        val block = world.spawnBlockParticles("minecraft:stone", 0.5, 200.0, 0.5, count = 4)
        val dust = world.spawnDustParticles(DUST_COLOR, 1.5f, 0.5, 200.0, 0.5, count = 4)
        val unknownItem = world.spawnItemParticles("oml_testmod:absent", 0.5, 200.0, 0.5)
        val unknownBlock = world.spawnBlockParticles("oml_testmod:absent", 0.5, 200.0, 0.5)
        E2E.check(
            "api.world_particles_arguments",
            item && block && dust && !unknownItem && !unknownBlock,
            "item=$item block=$block dust=$dust accepted; unknown item=$unknownItem unknown block=$unknownBlock",
        )
        return true
    }

    /**
     * `Level.isLoaded` is what separates "this chunk is not there" from "this block is air". Nothing
     * reads the far position: a server level answers the game's own getter there, which is real terrain
     * rather than air, so only the loaded flags are this probe's subject.
     */
    private fun probeIsLoaded(player: ServerPlayer): Boolean {
        val world = player.level()
        val feet = player.blockPosition()
        val ground = firstSolid(world, feet, 8) ?: return false
        val far = BlockPos(feet.x + 100_000, feet.y, feet.z)
        val farLoaded = world.isLoaded(far)
        E2E.check(
            "api.world_is_loaded",
            world.isLoaded(ground) && world.isLoaded(feet) && !farLoaded,
            "feet $feet and its ground $ground report loaded, $far reports loaded=$farLoaded",
        )
        return true
    }

    /**
     * Worn items addressed by the game's own slot name and by item id. The refusals are the point — an item the
     * game equips in no armour slot, a slot name it never uses, an unregistered id — and each one is compared
     * with the game's own `isEquippableInSlot`, so the verdict stays the game's rule rather than OML's. A cow in
     * open air is the subject, so this needs no player, and nothing is destroyed: the unequip hands the worn
     * stack back to the probe.
     */
    private fun probeEntityEquipment(): Boolean {
        val server = OMLGame.server ?: return false
        val world = server.levelById(OVERWORLD) ?: return false
        val boots = OMLRegistries.item(LEATHER_BOOTS)?.let(::ItemStack) ?: return false
        val gem = OMLRegistries.item(DIAMOND)?.let(::ItemStack) ?: return false
        val cow = world.spawnLivingEntity(COW, 0.5, 200.0, 0.5) ?: return false
        val wrote = cow.equip("feet", boots.copy())
        val readsBack = cow.itemIn("feet")?.itemId == LEATHER_BOOTS
        val refused = cow.equip("feet", gem.copy())
        val stillWorn = cow.itemIn("feet")?.itemId == LEATHER_BOOTS
        val unknownSlot = cow.equip(NO_SUCH_SLOT, boots.copy())
        val unknownId = cow.equip("feet", "oml_testmod:absent")
        val taken = cow.unequip("feet")
        val cleared = cow.itemIn("feet")?.isEmpty == true
        val flags = listOf(
            "the write was taken" to (wrote != null && wrote.isEmpty),
            "the game reads the worn item back" to readsBack,
            "the game refuses what it equips nowhere" to
                ((refused == null) && !cow.isEquippableInSlot(gem, EquipmentSlot.FEET)),
            "a refusal leaves the worn item alone" to stillWorn,
            "an unknown slot name is refused" to (unknownSlot == null),
            "an unregistered id is refused" to (unknownId == null),
            "unequipping hands back what was worn" to (taken?.itemId == LEATHER_BOOTS),
            "the slot reads clear afterwards" to cleared,
        )
        E2E.check(
            "api.entity_equipment",
            flags.all { it.second },
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}",
        )
        cow.discard()
        return true
    }

    /**
     * The player's own worn stack, read a second time out of the player container at the index the game maps the
     * slot name to — that pair of readings is what shows the entity's equipment and the container are one
     * storage instead of a copy OML keeps. Whatever the probe puts on, it puts back.
     */
    private fun probePlayerEquipment(player: ServerPlayer): Boolean {
        val inventory = player.inventory
        val chestSlot = inventory.indexOf("chest") ?: return false
        val offhandSlot = inventory.indexOf("offhand") ?: return false
        val before = player.itemIn("chest") ?: return false
        val displaced = player.equip("chest", IRON_CHESTPLATE) ?: return false
        val wornInContainer = inventory.getItem(chestSlot).itemId == IRON_CHESTPLATE
        val putBack = player.equip("chest", displaced)
        val restored = player.itemIn("chest")?.itemId == before.itemId
        val stone = OMLRegistries.item(STONE)?.let(::ItemStack) ?: return false
        val offDisplaced = player.equip("offhand", stone.copy())
        val agreesWithTheGame =
            (offDisplaced != null) == player.isEquippableInSlot(stone, EquipmentSlot.OFFHAND)
        offDisplaced?.let { player.equip("offhand", it) }
        val flags = listOf(
            "the armour index is the game's own" to (chestSlot in 36..39),
            "the offhand index is the game's constant" to (offhandSlot == Inventory.SLOT_OFFHAND),
            "the write was taken" to (displaced != null && displaced.isEmpty),
            "the container shows what the entity wears" to wornInContainer,
            "the original piece goes back on" to (putBack != null && restored),
            "the offhand verdict equals the game's rule" to agreesWithTheGame,
        )
        E2E.check(
            "api.player_equipment",
            flags.all { it.second },
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; chest=$chestSlot " +
                "offhand=$offhandSlot worn=${inventory.getItem(chestSlot).itemId}",
        )
        return true
    }

    /** Registry and tag reads: the same tables the mod's own content is materialized into. */
    private fun probeRegistries(): Boolean {
        val blocks = BuiltInRegistries.BLOCK
        val damageTypes = OMLGame.server?.registry(Registries.DAMAGE_TYPE)
        val stoneTags = blocks.tagsOf(STONE)
        val dirtTag = blocks.membersOf(Registries.BLOCK, DIRT)
        val baseStone = blocks.membersOf(Registries.BLOCK, "minecraft:base_stone_overworld")
        val flags = listOf(
            "id listed" to (STONE in blocks.ids()),
            "id resolves" to blocks.contains(STONE),
            "unregistered id" to !blocks.contains("oml_testmod:absent"),
            "item" to BuiltInRegistries.ITEM.contains(DIRT),
            "effect" to BuiltInRegistries.MOB_EFFECT.contains("minecraft:poison"),
            "sound" to BuiltInRegistries.SOUND_EVENT.contains("minecraft:entity.pig.ambient"),
            "entity type" to BuiltInRegistries.ENTITY_TYPE.contains("minecraft:cow"),
            "attribute" to BuiltInRegistries.ATTRIBUTE.contains(MAX_HEALTH_ATTRIBUTE),
            "damage type" to (damageTypes?.contains(CACTUS_DAMAGE) == true),
            "unregistered damage type" to (damageTypes?.contains("oml_testmod:absent") == false),
            "dirt tag holds dirt" to (DIRT in dirtTag),
            "base_stone_overworld holds stone" to (STONE in baseStone),
            "stone reports its tags" to ("minecraft:base_stone_overworld" in stoneTags),
            "unregistered tag is empty" to blocks.membersOf(Registries.BLOCK, "oml_testmod:absent").isEmpty(),
        )
        E2E.check(
            "api.registry_reads",
            flags.all { it.second },
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; " +
                "${blocks.ids().size} block ids, " +
                "dirt tag=${dirtTag.sorted()}, stone tags=${stoneTags.sorted()}, " +
                "unregistered tag=${blocks.membersOf(Registries.BLOCK, "oml_testmod:absent")}",
        )
        return true
    }

    /** Effects on the live player, including the three answers an unregistered id must give. */
    private fun probeEffects(player: ServerPlayer): Boolean {
        val speed = "minecraft:speed"
        val applied = player.applyEffect(speed, 200, 1)
        val instance = player.activeEffects.firstOrNull { it.effectId == speed }
        val has = player.hasEffect(speed)
        val removed = player.removeEffect(speed)
        val stillThere = player.hasEffect(speed)
        val absent = player.applyEffect("oml_testmod:absent", 20, 0) ||
            player.hasEffect("oml_testmod:absent") ||
            player.removeEffect("oml_testmod:absent")
        E2E.check(
            "api.player_effects",
            applied && has && instance != null && instance.amplifier == 1 && instance.duration in 1..200 &&
                removed && !stillThere && !absent && player.activeEffects.none { it.effectId == speed },
            "applied=$applied saw=${instance?.effectId}x${instance?.amplifier} for ${instance?.duration} " +
                "removed=$removed after=${player.activeEffects.map { it.effectId }}, absent id reported $absent",
        )
        return true
    }

    /** Healing a damaged player moves health by exactly what was asked, and never past the maximum. */
    private fun probeHeal(player: ServerPlayer): Boolean {
        val before = player.health
        if (before <= 0f) return false
        player.heal(1f)
        val after = player.health
        val expected = if (player.maxHealth - before >= 1f) before + 1f else player.maxHealth
        E2E.check(
            "api.player_heal",
            after == expected && after <= player.maxHealth,
            "health $before -> $after (expected $expected of max ${player.maxHealth})",
        )
        return true
    }

    private fun probeWorldSound(player: ServerPlayer): Boolean {
        val world = player.level()
        val at = player.position()
        val played = world.playSoundAt("minecraft:entity.pig.ambient", at.x(), at.y(), at.z(), 1f, 1f, "neutral")
        val unknownId = world.playSoundAt("oml_testmod:absent", at.x(), at.y(), at.z())
        val unknownCategory =
            world.playSoundAt("minecraft:entity.pig.ambient", at.x(), at.y(), at.z(), category = "not_a_category")
        E2E.check(
            "api.world_sound",
            played && !unknownId && !unknownCategory,
            "registered id=$played unknown id=$unknownId unknown category=$unknownCategory; a headless run " +
                "cannot hear it, this is the id and category contract",
        )
        return true
    }

    private fun probePlayerSound(player: ServerPlayer): Boolean {
        val played = player.playSound("minecraft:entity.pig.ambient")
        val unknownId = player.playSound("oml_testmod:absent")
        E2E.check(
            "api.player_sound",
            played && !unknownId,
            "player hears the registered id=$played, unknown id=$unknownId",
        )
        return true
    }

    /**
     * Ops the player through the game's own list and deops them again, then asks the two readings the same
     * question: which level does the game hold, and does `permissionLevel` report exactly that number. Which
     * number `ADMIN` lands on is the game's business (an integrated server elevates its local player), so the
     * probe asserts the agreement, not a constant.
     */
    private fun probePermissions(player: ServerPlayer): Boolean {
        val server = OMLGame.server ?: return false
        val nameAndId = player.nameAndId()
        val gameBefore = runCatching { player.permissionLevelOfGame() }
        val apiBefore = player.permissionLevel
        server.playerList.op(nameAndId, Optional.of(LevelBasedPermissionSet.ADMIN), Optional.empty())
        val gameGranted = runCatching { player.permissionLevelOfGame() }
        val apiGranted = player.permissionLevel
        server.playerList.deop(nameAndId)
        val gameAfter = runCatching { player.permissionLevelOfGame() }
        val apiAfter = player.permissionLevel
        val readFailure = listOf(gameBefore, gameGranted, gameAfter).firstNotNullOfOrNull { it.exceptionOrNull() }
        if (readFailure != null) {
            // The probe's own path into the game's permission state, not OML's read, is what failed.
            E2E.check("api.player_permissions", false, "the game's own permission read failed: $readFailure")
            return true
        }
        val levels = listOf(gameBefore, gameGranted, gameAfter).map { it.getOrThrow() }
        E2E.check(
            "api.player_permissions",
            apiBefore == levels[0] && apiGranted == levels[1] && apiAfter == levels[2] &&
                levels[1] > levels[0] && levels[2] == levels[0],
            "OML $apiBefore -> $apiGranted -> $apiAfter, the game's own $levels " +
                "(the ADMIN set handed to the game is level ${LevelBasedPermissionSet.ADMIN.level().id()})",
        )
        return true
    }

    /** The level the game itself holds for this player, the state `permissionLevel` reports. */
    private fun ServerPlayer.permissionLevelOfGame(): Int = (permissions() as LevelBasedPermissionSet).level().id()

    /** Removes the item from as many slots as it takes, and reports the exact number it took. */
    private fun probeInventoryRemove(player: ServerPlayer): Boolean {
        val inventory = player.inventory
        val before = inventory.countOf(STONE)
        if (!player.giveItem(STONE, 10)) return false
        val supplied = inventory.countOf(STONE)
        val removed = inventory.remove(STONE, 3)
        val leftAfterThree = inventory.countOf(STONE)
        val unknown = inventory.remove("oml_testmod:absent", 1)
        val rest = inventory.remove(STONE, 999)
        E2E.check(
            "api.inventory_remove",
            supplied == before + 10 && removed == 3 && leftAfterThree == supplied - 3 &&
                unknown == 0 && rest == leftAfterThree && inventory.countOf(STONE) == 0,
            "$STONE $before -> $supplied, removed $removed then $rest, unknown id removed $unknown, " +
                "left ${inventory.countOf(STONE)}",
        )
        return true
    }

    /**
     * Three moves that cover both destination branches: a plain move, a move that names the dimension it
     * is already in, and one that names a dimension the game has never heard of.
     *
     * The destination is two blocks above the player's own feet — the column they already occupy is free
     * air there, so no terrain search is needed, the move cannot bury them, and a two-block drop is under
     * the game's fall-damage threshold.
     */
    private fun probeTeleport(player: ServerPlayer): Boolean {
        val home = player.blockPosition()
        val target = BlockPos(home.x, home.y + 2, home.z)
        if (player.level().blockIdAt(target) != AIR) return false
        val moved = player.teleport(home.x + 0.5, home.y + 2.1, home.z + 0.5)
        val afterMove = player.blockPosition()
        val explicit = player.teleport(home.x + 0.5, home.y + 0.1, home.z + 0.5, player.dimensionId)
        val backHome = player.blockPosition()
        val refused = player.teleport(home.x + 0.5, home.y + 2.1, home.z + 0.5, "oml_testmod:absent")
        val stillHome = player.blockPosition()
        val flags = listOf(
            "the plain move is accepted" to moved,
            "the game put the player on the named block" to (afterMove == target),
            "naming the dimension they are in is accepted" to explicit,
            "that move lands where it was asked" to (backHome == home),
            "an unregistered dimension is refused" to !refused,
            "the refusal left the player where they were" to (stillHome == home),
        )
        E2E.check(
            "api.player_teleport",
            flags.all { it.second },
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; " +
                "${player.dimensionId} $home -> $afterMove (wanted $target) -> $backHome, " +
                "unregistered dimension returned $refused at $stillHome; targetLoaded=" +
                "${player.level().isLoaded(target)} blockUnderTarget=${player.level().blockIdAt(target)}",
        )
        return true
    }

    /**
     * Spawns a registered type beside the player and reads it back out of the level's own entity query,
     * with the rejections the surface promises: an unregistered id, and a non-living type asked of the
     * living spawn.
     */
    private fun probeSpawn(player: ServerPlayer): Boolean {
        val world = player.level()
        val at = player.position()
        val cow = world.spawnLivingEntity(COW, at.x() + 2.5, at.y() + 1.0, at.z() + 2.5) ?: return false
        val unknown = world.spawnEntity("oml_testmod:absent", at.x(), at.y(), at.z())
        val notLiving = world.spawnLivingEntity(SNOWBALL, at.x() + 3.5, at.y() + 1.0, at.z() + 3.5)
        val listed = world.entitiesOfType(COW)
        // The client has to be in the world for its own level to render the spawn at all.
        val clientWorld = OMLGame.client?.level ?: return false
        spawned = cow
        E2E.check(
            "api.entity_spawn",
            unknown == null && cow.typeId == COW && !cow.isRemoved && cow.health > 0f && notLiving == null &&
                cow.level().dimensionId == world.dimensionId &&
                listed.any { it.uuid == cow.uuid },
            "spawned ${cow.typeId} at ${cow.blockPosition()} in ${cow.dimensionId} with health ${cow.health}, the " +
                "level lists ${listed.size} of $COW, unregistered id -> $unknown, $SNOWBALL as living -> " +
                "$notLiving, the client renders ${clientWorld.dimensionId}",
        )
        return true
    }

    /**
     * Writes motion into an entity and lets the game run with it: the read-back proves the write landed on
     * the game's own vector, a position that has moved with the vector still non-zero proves it was adopted.
     *
     * A projectile carries this because it has no movement code of its own; a living entity recomputes the
     * vector from its walking input every tick. The launch needs verified open air — the game removes a
     * projectile that meets terrain, so a shot stopped by a hill measures the hill rather than the write —
     * and a shot that vanishes before the window closes is re-driven, bounded.
     */
    private fun probeMotion(player: ServerPlayer): Boolean {
        val world = player.level()
        val at = player.position()
        val tick = OMLGame.server?.tickCount ?: return false
        if (motionTick < 0) {
            if (motionAttempts > MOTION_MAX_ATTEMPTS) {
                E2E.check(
                    "api.entity_motion",
                    false,
                    "the shot was taken out of the world $MOTION_MAX_ATTEMPTS times around $at",
                )
                return true
            }
            val launch = openAirRun(world, at) ?: return false
            val shot = world.spawnEntity(SNOWBALL, launch.first, launch.second, launch.third) ?: return false
            shot.setDeltaMovement(MOTION_X, 0.0, 0.0)
            val written = shot.deltaMovement
            if (written.x() != MOTION_X || written.y() != 0.0 || written.z() != 0.0) {
                E2E.check("api.entity_motion", false, "writing x=$MOTION_X read back $written")
                return true
            }
            projectile = shot
            motionStart = shot.position().x()
            motionTick = tick
            return false
        }
        if (tick - motionTick < MOTION_WINDOW_TICKS) {
            if (projectile?.isRemoved == true) {
                motionAttempts++
                projectile = null
                motionTick = -1
            }
            return false
        }
        val shot = projectile ?: return false
        val moved = shot.position().x() - motionStart
        val stillMoving = shot.deltaMovement.x()
        E2E.check(
            "api.entity_motion",
            stillMoving > 0.0 && moved > 0.05,
            "wrote x=$MOTION_X and read it back; ${tick - motionTick} ticks later x=$stillMoving, it drifted " +
                "${"%.2f".format(moved)} blocks, removed=${shot.isRemoved}",
        )
        projectile = null
        return true
    }

    /**
     * A launch point with a clear run along +x, climbed up from above the player's head until the blocks
     * say air. Position 2.5 off the player is where a spawned entity lands anyway; the run is measured
     * generously because the shot keeps travelling after the window closes.
     */
    private fun openAirRun(world: Level, at: Vec3): Triple<Double, Double, Double>? {
        var y = at.y() + SHOT_LIFT
        repeat(SHOT_CLIMB) {
            val level = y.toInt()
            val clear = (0..SHOT_RUN).all { step ->
                world.blockIdAt(BlockPos((at.x() + step).toInt(), level, at.z().toInt())) == AIR
            }
            if (clear) return Triple(at.x() + 2.5, y, at.z() + 2.5)
            y += 1.0
        }
        return null
    }

    /**
     * The attribute layer, measured where the game itself reacts: raising the maximum-health base has to
     * show up in the health cap the game reports, and an `add_value` modifier has to move the effective
     * attack number by exactly its amount and come off again. Every write is restored, and the refusals
     * (unknown attribute, unknown operation, removing what is not there) are part of the claim.
     */
    private fun probeAttributes(player: ServerPlayer): Boolean {
        val baseBefore = player.attributeBase(MAX_HEALTH_ATTRIBUTE) ?: return false
        val damageBefore = player.attributeValue(ATTACK_DAMAGE_ATTRIBUTE) ?: return false
        val healthBefore = player.health
        val raised = player.setAttributeBase(MAX_HEALTH_ATTRIBUTE, RAISED_MAX_HEALTH)
        val raisedBase = player.attributeBase(MAX_HEALTH_ATTRIBUTE)
        val capNow = player.maxHealth
        val healthAfterRaise = player.health
        val added = player.addAttributeModifier(ATTACK_DAMAGE_ATTRIBUTE, MODIFIER_ID, MODIFIER_AMOUNT, "add_value")
        val withModifier = player.attributeValue(ATTACK_DAMAGE_ATTRIBUTE)
        val removed = player.removeAttributeModifier(ATTACK_DAMAGE_ATTRIBUTE, MODIFIER_ID)
        val withoutModifier = player.attributeValue(ATTACK_DAMAGE_ATTRIBUTE)
        val restored = player.setAttributeBase(MAX_HEALTH_ATTRIBUTE, baseBefore)
        // A cow is the other half of the contract: 26.3 gives attackers an attack damage and this kind of
        // entity carries none, so the reads have to answer null and the writes refuse — the game's own getter
        // throws for that case, which is what the surface exists to keep away from a mod.
        val cow = spawned?.takeIf { !it.isRemoved } ?: return false
        val cowDamage = cow.attributeValue(ATTACK_DAMAGE_ATTRIBUTE)
        val cowWrite = cow.setAttributeBase(ATTACK_DAMAGE_ATTRIBUTE, 9.0)
        val cowModifier = cow.addAttributeModifier(ATTACK_DAMAGE_ATTRIBUTE, MODIFIER_ID, 1.0, "add_value")
        val cowHealth = cow.attributeValue(MAX_HEALTH_ATTRIBUTE)
        val flags = listOf(
            "raise accepted" to raised,
            "base reads back" to (raisedBase == RAISED_MAX_HEALTH),
            "the game's cap follows" to (capNow == RAISED_MAX_HEALTH.toFloat()),
            "a raised cap does not refill health" to (healthAfterRaise == healthBefore),
            "modifier accepted" to added,
            "modifier moves the value" to (withModifier == damageBefore + MODIFIER_AMOUNT),
            "modifier removed" to removed,
            "the value returns" to (withoutModifier == damageBefore),
            "base restored" to (restored && player.maxHealth == baseBefore.toFloat()),
            "unknown attribute reads null" to (player.attributeValue("oml_testmod:absent") == null),
            "unknown attribute refuses write" to !player.setAttributeBase("oml_testmod:absent", 1.0),
            "unknown operation refused" to !player.addAttributeModifier(ATTACK_DAMAGE_ATTRIBUTE, MODIFIER_ID, 1.0, "sideways"),
            "removing nothing answers false" to !player.removeAttributeModifier(ATTACK_DAMAGE_ATTRIBUTE, "oml_testmod:absent"),
            "an attribute this kind of entity lacks reads null" to (cowDamage == null),
            "and its write refuses instead of throwing" to (!cowWrite && !cowModifier),
            "an attribute it does carry still reads" to (cowHealth != null),
        )
        E2E.check(
            "api.entity_attributes",
            flags.all { it.second },
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; base $baseBefore -> " +
                "$raisedBase (game cap $capNow, restored to ${player.maxHealth}), health $healthBefore -> " +
                "$healthAfterRaise, attack $damageBefore -> $withModifier -> $withoutModifier; " +
                "cow reads damage=$cowDamage write=$cowWrite modifier=$cowModifier health=$cowHealth",
        )
        return true
    }

    /**
     * Deals damage with the API instead of receiving it: the amount, the damage type and the credited
     * attacker have to arrive at the loader's own `LIVING_HURT` hook exactly as a mob's attack does, and
     * the health has to drop by what the game applied. The unregistered id is driven **first** so its
     * refusal cannot be the game's damage cooldown talking.
     */
    private fun probeDealtDamage(player: ServerPlayer): Boolean {
        val cow = spawned ?: return false
        if (cow.isRemoved) return false
        val absent = driveDamageWith(cow, "oml_testmod:absent")
        val cactus = driveDamageWith(cow, CACTUS_DAMAGE, player)
        E2E.check(
            "api.entity_hurt",
            cactus.applied && cactus.observed == Triple(HURT_AMOUNT, "cactus", true) &&
                cactus.cost == HURT_AMOUNT && !absent.applied && absent.observed == null,
            "hurt returned ${cactus.applied}, the hook saw ${cactus.observed}, health cost ${cactus.cost}; " +
                "unregistered damage type applied=${absent.applied} and reached no handler",
        )
        return true
    }

    /** What one dealt-damage drive produced: the game's answer, what the hook saw, the health it cost. */
    private data class DealtDamage(
        val applied: Boolean,
        val observed: Triple<Float, String, Boolean>?,
        val cost: Float,
    )

    private fun driveDamageWith(
        target: LivingEntity,
        damageTypeId: String,
        attacker: ServerPlayer? = null,
    ): DealtDamage {
        hurtActionObservation = null
        sourceTagObservation = null
        drivenUuid = target.uuid
        driveHurtAction = true
        val healthBefore = target.health
        val applied = try {
            target.hurt(damageTypeId, HURT_AMOUNT, attacker)
        } finally {
            driveHurtAction = false
        }
        return DealtDamage(applied, hurtActionObservation, healthBefore - target.health)
    }

    /**
     * The damage type [OMLTestMod] declared has to be the game's own registration: OML served it as a
     * datapack file, the game's reload turned it into a registry entry, and `hurt()` then accepts its id —
     * with the `LIVING_HURT` handler reporting the message id the declaration named, and the health cost
     * equal to the amount asked for.
     *
     * Needs no player, so it also runs on the headless server gate. The cow is spawned high in open air and
     * hurt in the same tick, so neither the terrain nor fall damage can move the measured cost.
     */
    private fun probeDeclaredDamageType(): Boolean {
        val server = OMLGame.server ?: return false
        val world = server.levelById(OVERWORLD) ?: return false
        if (server.registry(Registries.DAMAGE_TYPE)?.contains(MOD_DAMAGE_TYPE) != true) return false
        val cow = world.spawnLivingEntity(COW, 0.5, 200.0, 0.5) ?: return false
        val absent = driveDamageWith(cow, "oml_testmod:absent")
        val melt = driveDamageWith(cow, MOD_DAMAGE_TYPE)
        cow.discard()
        E2E.check(
            "content.damage_type",
            melt.applied && melt.observed == Triple(HURT_AMOUNT, MOD_DAMAGE_MESSAGE, false) &&
                melt.cost == HURT_AMOUNT && !absent.applied,
            "the game registered $MOD_DAMAGE_TYPE and applied it: the hook saw ${melt.observed} for " +
                "${melt.cost} health; an unregistered id applied=${absent.applied}",
        )
        return true
    }

    /**
     * The tag files OML served, read back as the game's own answer. What is worth measuring is that the file
     * format has three shapes and they resolve differently — a bare id, a `#` reference to another group whose
     * members join this one, and an optional member the registry has never heard of, which must not drop the
     * group the way a required missing one does — plus two things no read of OML's own state could show: a
     * second pack's member arriving in the one file the game reads per tag, and `DamageSource.is(tag)` on the
     * source the game built for the damage the probe dealt.
     */
    private fun probeDamageTypeTag(): Boolean {
        val server = OMLGame.server ?: return false
        val damageTypes = server.registry(Registries.DAMAGE_TYPE) ?: return false
        val world = server.levelById(OVERWORLD) ?: return false
        val cow = world.spawnLivingEntity(COW, 0.5, 200.0, 0.5) ?: return false
        val dealt = driveDamageWith(cow, MOD_DAMAGE_TYPE)
        val tagged = sourceTagObservation
        cow.discard()
        val fireMembers = damageTypes.membersOf(Registries.DAMAGE_TYPE, FIRE_TAG)
        val ownMembers = damageTypes.membersOf(Registries.DAMAGE_TYPE, MOD_TAG)
        val freezingMembers = damageTypes.membersOf(Registries.DAMAGE_TYPE, FREEZING_TAG)
        val flags = listOf(
            "the game ran the damage the tag rides on" to dealt.applied,
            "this pack's member reached the vanilla group it joined" to (MOD_DAMAGE_TYPE in fireMembers),
            "the entry reads its own groups back" to (FIRE_TAG in damageTypes.tagsOf(MOD_DAMAGE_TYPE)),
            "the game holds the group this pack opened" to (MOD_TAG in damageTypes.tagIds(Registries.DAMAGE_TYPE)),
            "a second pack's member merged into the same file" to (TECH_DAMAGE_TYPE in ownMembers),
            "a member may name another group" to ("minecraft:drown" in ownMembers),
            "an unregistered optional member leaves the group standing" to (MOD_DAMAGE_TYPE in ownMembers),
            "a replacing declaration is the group's whole content" to (
                freezingMembers == listOf(MOD_DAMAGE_TYPE)
                ),
            "the game's own source answers the tag" to (tagged == (true to false)),
        )
        E2E.check(
            "content.custom_tag",
            flags.all { it.second },
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; fire=$fireMembers " +
                "own=$ownMembers freezing=$freezingMembers meltTags=${damageTypes.tagsOf(MOD_DAMAGE_TYPE)} " +
                "tagged=$tagged applied=${dealt.applied}",
        )
        return true
    }

    /**
     * Lights a mob, reads the game's own burning state back, puts it out, and asks an immune mob to refuse
     * it — that refusal is `ignite`'s false, not a silent no-op.
     */
    private fun probeFire(player: ServerPlayer): Boolean {
        val cow = spawned ?: return false
        if (cow.isRemoved) return false
        val lit = cow.ignite(FIRE_TICKS)
        val burning = cow.isOnFire
        val ticks = cow.remainingFireTicks
        val extinguished = cow.extinguish()
        val unlit = cow.isOnFire
        val clearedAgain = cow.extinguish()
        val at = player.position()
        val strider =
            player.level().spawnLivingEntity(STRIDER, at.x() - 2.5, at.y() + 1.0, at.z() - 2.5) ?: return false
        val refused = strider.ignite(FIRE_TICKS)
        val immuneStaysUnlit = !strider.isOnFire
        strider.discard()
        E2E.check(
            "api.entity_fire",
            lit && burning && ticks in 1..FIRE_TICKS && extinguished && !unlit && !clearedAgain &&
                !refused && immuneStaysUnlit,
            "ignite($FIRE_TICKS)=$lit burning=$burning ticks=$ticks, extinguish=$extinguished then " +
                "$clearedAgain; the fire-immune $STRIDER answered $refused and burning=$immuneStaysUnlit",
        )
        return true
    }

    /**
     * Takes the entity out of the level and checks the game's own query stops listing it. It waits for the
     * steps that share this entity to report first: freeing the handle while one of them is still reading
     * it leaves that step with nothing to measure.
     */
    private fun probeRemoval(player: ServerPlayer): Boolean {
        if (!setOf("api.entity_motion", "api.entity_hurt", "api.entity_fire").all { it in reported }) return false
        val cow = spawned ?: return false
        cow.discard()
        val stillListed = player.level().entitiesOfType(COW).any { it.uuid == cow.uuid }
        E2E.check(
            "api.entity_removal",
            cow.isRemoved && !stillListed,
            "after discard() isRemoved=${cow.isRemoved}, the level still lists it=$stillListed",
        )
        spawned = null
        return true
    }

    /**
     * The state layer: the id says chest, the properties say which way it faces. Written back through the
     * level and read from it again, so the write is proved at the state rather than at the id — and an
     * illegal value, an unknown property and an unloaded position all answer the way the surface says they
     * must.
     */
    private fun probeBlockState(player: ServerPlayer): Boolean {
        val world = player.level()
        val feet = player.blockPosition()
        val ground = firstSolid(world, BlockPos(feet.x, feet.y - 1, feet.z), 8) ?: return false
        val original = world.blockStateAt(ground) ?: return false
        val unloaded = world.blockStateAt(BlockPos(feet.x + 100_000, feet.y, feet.z))
        if (!world.setBlockAt(ground, CHEST)) return false
        val placed = world.blockStateAt(ground) ?: return false
        val rotated = placed.withProperty("facing", "east")
        val wrote = rotated != null && world.setBlockAt(ground, rotated)
        val readBack = world.blockStateAt(ground)
        val badValue = placed.withProperty("facing", "sideways")
        val badProperty = placed.withProperty("oml_nonsense", "east")
        val restored = world.setBlockAt(ground, original)
        E2E.check(
            "api.block_state",
            placed.blockId == CHEST && "facing" in placed.propertyValues && wrote &&
                readBack?.property("facing") == "east" && readBack.blockId == CHEST &&
                badValue == null && badProperty == null && restored &&
                world.blockStateAt(ground)?.blockId == original.blockId && unloaded == null,
            "$ground ${original.blockId} ${original.propertyValues} -> $CHEST facing=${placed.property("facing")} " +
                "-> east read back ${readBack?.property("facing")} -> restored " +
                "${world.blockStateAt(ground)?.blockId}; " +
                "illegal value -> $badValue, unknown property -> $badProperty, unloaded position -> $unloaded",
        )
        return true
    }

    /**
     * Storage a block holds, read and written through the game's own container methods. The chest half is
     * the arithmetic (write, count, take what comes out, take what does not fit); the furnace half is the
     * part OML must not decide for itself — the block's own slot rules, which refuse the output slot and
     * any non-fuel in the fuel slot, and which a write therefore cannot paste over.
     *
     * A block with no storage and an unloaded position both answer null, which is the check that the read
     * asks the game rather than defaulting to an empty container.
     */
    private fun probeContainer(player: ServerPlayer): Boolean {
        val world = player.level()
        val feet = player.blockPosition()
        val spots = airPockets(world, feet, 2) ?: return false
        val (chestPos, chestOriginal) = spots[0]
        val (furnacePos, furnaceOriginal) = spots[1]
        if (!world.setBlockAt(chestPos, CHEST) || !world.setBlockAt(furnacePos, FURNACE)) return false
        val chest = world.containerAt(chestPos) ?: return false
        val furnace = world.containerAt(furnacePos)
        val unloaded = world.containerAt(BlockPos(feet.x + 100_000, feet.y, feet.z))
        val startedEmpty = chest.isEmpty && chest.getItem(0).isEmpty
        val wrote = chest.setSlot(0, STONE, 5)
        val readBack = chest.getItem(0)
        val counted = chest.countOf(STONE)
        val cleared = chest.setSlot(0, STONE, 0)
        val afterClear = chest.isEmpty
        chest.setSlot(0, STONE, 5)
        val tookSome = chest.takeSlot(0, 3)
        val leftAfterTake = chest.countOf(STONE)
        val tookRest = chest.takeSlot(0, 99)
        val outOfRangeWrite = chest.setSlot(CHEST_SLOTS, STONE, 1)
        val unknownWrite = chest.setSlot(0, "oml_testmod:absent", 1)
        val furnacePlaced = furnace?.setSlot(FURNACE_INPUT, STONE, 1)
        val outputRefused = furnace?.setSlot(FURNACE_OUTPUT, STONE, 1)
        // Both blocks go back before the last read, so "a block with no storage" is measured on ground the
        // probe restored rather than on one it left behind.
        world.setBlockAt(furnacePos, furnaceOriginal)
        world.setBlockAt(chestPos, chestOriginal)
        val notStorage = world.containerAt(chestPos)
        val flags = listOf(
            "the chest addresses 27 slots" to (chest.containerSize == CHEST_SLOTS),
            "a new chest is empty" to startedEmpty,
            "write accepted" to wrote,
            "the block holds what was written" to (readBack.itemId == STONE && readBack.count == 5),
            "counting agrees" to (counted == 5),
            "a zero count clears the slot" to (cleared && afterClear),
            "taking part of a stack" to (tookSome?.count == 3 && leftAfterTake == 2),
            "taking more than held gives what was there" to (tookRest?.count == 2 && chest.isEmpty),
            "an out-of-range write refuses" to !outOfRangeWrite,
            "unknown id refuses" to !unknownWrite,
            "a furnace addresses 3 slots" to (furnace?.containerSize == FURNACE_SLOTS),
            "the furnace's input accepts" to (furnacePlaced == true),
            "the fuel slot refuses a non-fuel" to (furnace?.canPlace(FURNACE_FUEL, STONE, 1) == false),
            "the output slot refuses" to (furnace?.canPlace(FURNACE_OUTPUT, STONE, 1) == false),
            "a refused slot cannot be written past" to (outputRefused == false),
            "a block without storage is not a container" to (notStorage == null),
            "an unloaded position has no container" to (unloaded == null),
        )
        E2E.check(
            "api.world_container",
            flags.all { it.second },
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; chest $chestPos " +
                "size=${chest.containerSize} wrote=$wrote read=${readBack.itemId}x${readBack.count} " +
                "counted=$counted " +
                "took=${tookSome?.count} left=$leftAfterTake rest=${tookRest?.count}; furnace $furnacePos " +
                "size=${furnace?.containerSize} canPlace(input/fuel/output)=" +
                "${furnace?.canPlace(FURNACE_INPUT, STONE, 1)}/${furnace?.canPlace(FURNACE_FUEL, STONE, 1)}" +
                "/${furnace?.canPlace(FURNACE_OUTPUT, STONE, 1)}, restored=$notStorage",
        )
        return true
    }

    /**
     * Which menu the game has this player using, and who it counts as using a block. The open is driven
     * through the game's own `ServerPlayer.openMenu` — the same call a chest interaction ends in — because
     * OML hands out no way to open a screen: what is under test is the read and the close.
     *
     * The viewer list is the game's own bookkeeping, and a chest gets it from the **menu constructor**
     * (`ChestMenu` calls `Container.startOpen`), so it only names the player while a menu exists — which is
     * exactly the pair of readings the step checks: open menu id plus one user, closed with no id and none.
     */
    private fun probeOpenedMenu(player: ServerPlayer): Boolean {
        val world = player.level()
        val pos = airPockets(world, player.blockPosition(), 1, startAt = 4)?.first()?.first ?: return false
        val original = world.blockStateAt(pos) ?: return false
        if (!world.setBlockAt(pos, CHEST)) return false
        val atRest = player.openedMenuId
        val provider = world.getBlockEntity(pos) as? MenuProvider
        val opened = provider?.let { player.openMenu(it).isPresent } == true
        val whileOpen = player.openedMenuId
        val users = world.containerAt(pos)?.openedByPlayerNames ?: emptyList()
        val closed = player.closeMenu()
        val afterClose = player.openedMenuId
        val closeAgain = player.closeMenu()
        world.setBlockAt(pos, original)
        val flags = listOf(
            "no menu at rest" to (atRest == null),
            "the game opened the chest" to opened,
            "the menu is the chest's" to (whileOpen == CHEST_MENU),
            "the game names the player as its user" to (users == listOf(player.scoreboardName)),
            "the close was taken" to closed,
            "no menu after the close" to (afterClose == null),
            "closing nothing answers false" to !closeAgain,
        )
        E2E.check(
            "api.player_opened_menu",
            flags.all { it.second } && atRest == null,
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; atRest=$atRest, " +
                "opened=$whileOpen, users=$users (wanted ${player.scoreboardName}), closed -> $afterClose, " +
                "second close=$closeAgain, block at $pos restored to ${world.blockIdAt(pos)}",
        )
        return true
    }

    /**
     * Acceptance, not delivery: what this side of the game can be held to is that a registered simple
     * particle goes out through the game's own sender, and that the two kinds it refuses — an unknown id,
     * and a particle type that needs arguments a bare id cannot carry — come back false on either side.
     */
    private fun probeParticles(player: ServerPlayer): Boolean {
        val world = player.level()
        val at = player.position()
        val accepted = world.spawnParticles(
            POOF, at.x(), at.y() + 1.0, at.z(), count = 5, offsetX = 0.5, offsetY = 0.5, offsetZ = 0.5,
        )
        val unknown = world.spawnParticles("oml_testmod:absent", at.x(), at.y(), at.z())
        val needsArguments = world.spawnParticles(DUST, at.x(), at.y(), at.z())
        val clientWorld = OMLGame.client?.level ?: return false
        E2E.check(
            "api.world_particles",
            accepted && !unknown && !needsArguments,
            "$POOF accepted=$accepted, unknown id=$unknown, $DUST (needs arguments)=$needsArguments, " +
                "the client renders ${clientWorld.dimensionId}",
        )
        return true
    }

    /**
     * Drives the game's own respawn path — `PlayerList.respawn`, the very method the hook sits on — and
     * checks that the event carries the instance the game made for the new life: a different object from
     * the one it replaced, and not removed, while the handle held from before the call reads as removed.
     *
     * The player is deliberately not killed first. A dead player is refused every later damage probe
     * within the run, and the death is saved with the world, so the next run would start dead — and the
     * promise this hook makes is about the returned instance, which is the same whether the respawn came
     * from health or from death.
     */
    private fun driveRespawn(player: ServerPlayer): Boolean {
        if (!clientLoaded(player) || player.isRemoved || player.health <= 0f) return false
        val server = OMLGame.server ?: return false
        respawnObservation = null
        driveRespawnWindow = true
        val returned = try {
            server.playerList.respawn(player, true, Entity.RemovalReason.KILLED)
        } finally {
            driveRespawnWindow = false
        }
        val seen = respawnObservation
        E2E.check(
            "event.player_respawned",
            seen != null && seen.uuid == player.uuid && !seen.isRemoved &&
                seen !== player && player.isRemoved && seen.health > 0f && seen === returned,
            "the event carried ${if (seen == null) "nothing" else "a live player ${seen.scoreboardName} at health ${seen.health}"}; " +
                "a different instance from the replaced one=${seen !== player}, the event's instance is the " +
                "one the game returned=${seen === returned}, the old handle now reads removed=${player.isRemoved}",
        )
        // A mod keeps player handles across a respawn — from a join event, a saved home, a command source — and
        // the leg below asks the one the game replaced, which is a player the game has let go.
        replacedHandle = player
        return true
    }

    /**
     * A handle the game replaced — by a death, a respawn — is a player the game has let go: the move is
     * refused, and the one standing in the world stays exactly where they are. Reaching them means addressing
     * the server's live counterpart by the uuid, which is what a mod that keeps a player around has to do.
     */
    private fun driveTeleportAfterRespawn(): Boolean {
        val old = replacedHandle ?: return false
        val live = OMLGame.server?.getPlayerList()?.getPlayer(old.uuid) ?: return false
        val at = live.blockPosition()
        val asked = old.teleport(at.x + 3.5, at.y + 0.1, at.z + 0.5)
        val now = live.blockPosition()
        E2E.check(
            "api.player_teleport_replaced_handle",
            old.isRemoved && !asked && now == at,
            "the replaced handle reads removed=${old.isRemoved}, asking it answered $asked, the live player " +
                "stood at $at and now reads $now",
        )
        replacedHandle = null
        return true
    }

    private fun driveBlockBreak(player: ServerPlayer): Boolean {
        val pos = firstSolid(player.level(), player.blockPosition(), 8) ?: return false
        val before = player.level().blockIdAt(pos)
        breakObservation = null
        breakTarget = pos
        val destroyed = try {
            player.gameMode.destroyBlock(pos)
        } finally {
            breakTarget = null
        }
        val after = player.level().blockIdAt(pos)
        val seen = breakObservation
        E2E.check(
            "event.block_break",
            seen != null && seen.first == pos && seen.second == before && !destroyed && after == before,
            "handler saw ${seen?.second} at ${seen?.first}; destroyBlock returned $destroyed; $pos $before -> $after",
        )
        return true
    }

    private fun driveAttack(player: ServerPlayer): Boolean {
        attackObservation = null
        drivenUuid = player.uuid
        driveAttack = true
        // Self-targeted, and cancelled at HEAD: the swing never runs, so the observation is the handler's
        // own report rather than a health delta.
        val outcome = runCatching { player.attack(player) }
        driveAttack = false
        val seen = attackObservation
        E2E.check(
            "event.entity_attack",
            outcome.isSuccess && seen == player.typeId,
            "handler saw target type ${seen ?: "nothing"}; the driven call " +
                "${if (outcome.isSuccess) "returned" else "threw ${outcome.exceptionOrNull()}"}",
        )
        return true
    }

    private fun driveInteract(player: ServerPlayer): Boolean {
        val position = player.position()
        interactObservation = null
        drivenUuid = player.uuid
        driveInteract = true
        val result = try {
            player.interactOn(player, InteractionHand.MAIN_HAND, position)
        } finally {
            driveInteract = false
        }
        val seen = interactObservation
        E2E.check(
            "event.entity_interact",
            seen != null && result === InteractionResult.PASS,
            "handler saw hand=$seen; interactOn returned $result (PASS is the cancel's own return value)",
        )
        return true
    }

    private fun driveHurt(player: ServerPlayer): Boolean {
        // A just-joined player is invulnerable until the client reports its terrain loaded
        // (`ServerPlayer.isInvulnerableTo`), and an invulnerable damage application returns before it
        // reaches the base `hurtServer` the hook sits on — so the drive waits for that window to close
        // instead of reporting the game's own gate as a missing event.
        if (!clientLoaded(player)) return false
        val level = player.level()
        val source = level.damageSources().generic()
        val healthBefore = player.health
        hurtObservation = null
        drivenUuid = player.uuid
        driveHurt = true
        val applied = try {
            player.hurtServer(level, source, HURT_AMOUNT)
        } finally {
            driveHurt = false
        }
        val healthAfter = player.health
        val seen = hurtObservation
        E2E.check(
            "event.living_hurt",
            seen != null && seen.first == HURT_AMOUNT && !seen.third && !applied && healthAfter == healthBefore,
            "handler saw amount=${seen?.first} type=${seen?.second} attacker=${seen?.third}; hurtServer returned $applied; " +
                "health $healthBefore -> $healthAfter in ${player.gameModeName} mode",
        )
        return true
    }

    /**
     * The same driven damage, with the handler rewriting the number instead of cancelling it. What the
     * player loses is the game's own arithmetic on top of the number (armor, difficulty), and an
     * application inside the game's damage cooldown is reduced by the previous one — so the verdict is a
     * comparison: the same damage through the same path has to cost more once a handler wrote a bigger
     * number, which is only possible if the written number is the one the game used.
     */
    private fun driveHurtRewrite(player: ServerPlayer): Boolean {
        if (!clientLoaded(player)) return false
        val level = player.level()
        val source = level.damageSources().generic()
        val tick = OMLGame.server?.tickCount ?: return false
        when (rewritePhase) {
            0 -> {
                val (delta, applied) = driveDamage(player, level, source, rewriting = false)
                if (!applied || delta <= 0f) {
                    if (controlWaits++ > REFUSED_DAMAGE_WAIT_TICKS) {
                        E2E.check(
                            "event.living_hurt_rewrite",
                            false,
                            "the unrewritten control damage was refused for $controlWaits ticks: ${playerState(player)}",
                        )
                        return true
                    }
                    return false
                }
                controlDelta = delta
                controlTick = tick
                rewritePhase = 1
                return false
            }
            1 -> {
                if (tick - controlTick <= DAMAGE_COOLDOWN_TICKS) return false
                val (delta, applied) = driveDamage(player, level, source, rewriting = true)
                val seen = rewriteObservation
                rewritePhase = 2
                E2E.check(
                    "event.living_hurt_rewrite",
                    seen == HURT_AMOUNT && applied && delta > controlDelta,
                    "handler saw $seen and wrote $REWRITTEN_AMOUNT; hurtServer returned $applied; " +
                        "the control's $HURT_AMOUNT cost $controlDelta, the rewritten amount cost $delta",
                )
                return true
            }
            else -> return true
        }
    }

    /** One driven `hurtServer` call, with or without the rewrite handler acting on it. */
    private fun driveDamage(
        player: ServerPlayer,
        level: ServerLevel,
        source: DamageSource,
        rewriting: Boolean,
    ): Pair<Float, Boolean> {
        // Both hits start from full health: a rewritten amount that would kill what is left measures its delta
        // against the remaining health, not against the amount the handler wrote.
        player.health = player.maxHealth
        val healthBefore = player.health
        rewriteObservation = null
        drivenUuid = player.uuid
        if (rewriting) driveHurtRewrite = true
        val applied = try {
            player.hurtServer(level, source, HURT_AMOUNT)
        } finally {
            if (rewriting) driveHurtRewrite = false
        }
        return (healthBefore - player.health) to applied
    }

    private fun driveBlockUse(player: ServerPlayer): Boolean {
        val pos = firstSolid(player.level(), player.blockPosition(), 8) ?: return false
        val before = player.level().blockIdAt(pos)
        blockUseObservation = null
        drivenUuid = player.uuid
        driveBlockUse = true
        var result: InteractionResult? = null
        val restored = try {
            withDirtInHand(player) { stack ->
                result = player.gameMode.useItemOn(
                    player, player.level(), stack, InteractionHand.MAIN_HAND, blockHit(pos),
                )
                true
            }
        } finally {
            driveBlockUse = false
        }
        val seen = blockUseObservation
        E2E.check(
            "event.block_used",
            restored && seen != null && seen.hit.blockPos == pos && seen.state.blockId == before &&
                seen.stack.itemId == DIRT && seen.hand == InteractionHand.MAIN_HAND &&
                seen.hit.direction == Direction.UP && result === InteractionResult.FAIL,
            "handler saw pos=${seen?.hit?.blockPos} block=${seen?.state?.blockId} item=${seen?.stack?.itemId} " +
                "hand=${seen?.hand} face=${seen?.hit?.direction}; useItemOn returned $result " +
                "(FAIL is the cancel's own return value); $pos is still ${player.level().blockIdAt(pos)}, " +
                "hand restored=$restored",
        )
        return true
    }

    private fun driveItemUse(player: ServerPlayer): Boolean {
        itemUseObservation = null
        drivenUuid = player.uuid
        driveItemUse = true
        var result: InteractionResult? = null
        val restored = try {
            withDirtInHand(player) { stack ->
                result = player.gameMode.useItem(player, player.level(), stack, InteractionHand.MAIN_HAND)
                true
            }
        } finally {
            driveItemUse = false
        }
        val seen = itemUseObservation
        E2E.check(
            "event.item_used",
            restored && seen != null && seen.stack.itemId == DIRT && seen.hand == InteractionHand.MAIN_HAND &&
                result === InteractionResult.FAIL,
            "handler saw item=${seen?.stack?.itemId} hand=${seen?.hand}; useItem returned $result " +
                "(FAIL is the cancel's own return value), hand restored=$restored",
        )
        return true
    }

    /**
     * Puts dirt in the hand slot for the driven click and puts the slot back afterwards: the event's item
     * id has to be an item the player actually holds, and an uncancelled use would consume it.
     */
    private fun withDirtInHand(player: ServerPlayer, body: (ItemStack) -> Boolean): Boolean {
        val inventory = player.inventory
        val slot = inventory.selectedSlot
        val original = inventory.getItem(slot)
        if (!inventory.setSlot(slot, DIRT, 1)) return false
        val stack = inventory.getItem(slot)
        val verdict = body(stack)
        val rewrote = if (original.isEmpty) inventory.setSlot(slot, DIRT, 0) else
            inventory.setSlot(slot, original.itemId, original.count)
        val readBack = inventory.getItem(slot)
        return verdict && rewrote && readBack.itemId == original.itemId && readBack.count == original.count
    }

    /** A click on the top face of [pos], the face the block under the player is looked at through. */
    private fun blockHit(pos: BlockPos): BlockHitResult =
        BlockHitResult(Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5), Direction.UP, pos, false)

    /** Whether the player's own client has finished loading the terrain it stands in. */
    private fun clientLoaded(player: ServerPlayer): Boolean = player.connection.hasClientLoaded()

    /** The state the game's own damage guards read, which is what a refused driven damage has to report. */
    private fun playerState(player: ServerPlayer): String =
        "mode=${player.gameModeName} health=${player.health}/${player.maxHealth} " +
            "alive=${!player.isRemoved && player.health > 0f} onFire=${player.isOnFire} " +
            "at ${player.blockPosition()} in ${player.dimensionId}"

    private val clientRun = SchedulerRun(OMLSide.CLIENT, "scheduler.client_task")
    private val serverRun = SchedulerRun(OMLSide.SERVER, "scheduler.server_task")

    /**
     * Live proof that the scheduler rides the game's own tick: a delayed task runs, a repeating one keeps
     * running, and a cancelled one stops. Bounded in ticks, like the API it exercises.
     */
    private fun tickScheduler(run: SchedulerRun) {
        if (run.done) return
        if (!run.enqueued) {
            run.enqueued = true
            OMLScheduler.schedule(run.side, delayTicks = 2) { run.oneShotRan = true }
            var handle: OMLTask? = null
            handle = OMLScheduler.repeat(run.side, intervalTicks = 2) {
                run.repeatRuns++
                if (run.repeatRuns == 3) handle?.cancel()
            }
            return
        }
        run.waited++
        if (run.repeatRuns >= 3) run.afterCancel++
        if (run.repeatRuns > 3) {
            run.done = true
            E2E.check(run.name, false, "the cancelled repeat kept running (${run.repeatRuns} times)")
        } else if (run.afterCancel >= 6) {
            run.done = true
            E2E.check(
                run.name,
                run.oneShotRan && run.repeatRuns == 3,
                "one-shot=${run.oneShotRan} repeat=${run.repeatRuns} waited=${run.waited} ticks",
            )
        } else if (run.waited > GIVE_UP_TICKS) {
            run.done = true
            E2E.check(run.name, false, "gave up after ${run.waited} ticks: one-shot=${run.oneShotRan} repeat=${run.repeatRuns}")
        }
    }

    private class SchedulerRun(val side: OMLSide, val name: String) {
        var enqueued = false
        var done = false
        var oneShotRan = false
        var repeatRuns = 0
        var afterCancel = 0
        var waited = 0
    }

    /** The first non-air position below [from], searching [depth] blocks down. */
    private fun firstSolid(world: Level, from: BlockPos, depth: Int): BlockPos? {
        for (drop in 1..depth) {
            val pos = BlockPos(from.x, from.y - drop, from.z)
            if (world.blockIdAt(pos) != AIR) return pos
        }
        return null
    }

    /**
     * [count] positions to place blocks in: air with solid ground under it, walked outward along x from the
     * player on both sides. The block that was there comes back with each one, so a probe restores what it
     * found instead of leaving terrain behind. Searching rather than guessing matters: one column over can
     * be a ledge with nothing under it for eight blocks, and a probe that gives up on that reports a missing
     * spot instead of a missing API. Columns are skipped at the player's own position, so placing a block
     * never puts one inside the player.
     */
    private fun airPockets(
        world: Level,
        feet: BlockPos,
        count: Int,
        startAt: Int = 1,
    ): List<Pair<BlockPos, BlockState>>? {
        val found = mutableListOf<Pair<BlockPos, BlockState>>()
        var offset = startAt
        while (found.size < count && offset <= POCKET_SEARCH_RANGE) {
            for (step in intArrayOf(offset, -offset)) {
                val ground = firstSolid(world, BlockPos(feet.x + step, feet.y - 1, feet.z), 8) ?: continue
                val pos = BlockPos(ground.x, ground.y + 1, ground.z)
                val original = world.blockStateAt(pos) ?: continue
                if (original.blockId == AIR && found.none { it.first == pos }) found += pos to original
            }
            offset++
        }
        return found.takeIf { it.size >= count }
    }

}
