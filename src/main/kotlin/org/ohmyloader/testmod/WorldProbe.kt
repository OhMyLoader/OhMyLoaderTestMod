package org.ohmyloader.testmod

import org.ohmyloader.api.access.OMLGame
import org.ohmyloader.api.event.Events
import org.ohmyloader.api.registry.OMLRegistries
import org.ohmyloader.api.tick.OMLScheduler
import org.ohmyloader.api.tick.OMLSide
import org.ohmyloader.api.tick.OMLTask
import org.ohmyloader.api.world.OMLBlockPos
import org.ohmyloader.api.world.OMLEntity
import org.ohmyloader.api.world.OMLLivingEntity
import org.ohmyloader.api.world.OMLPlayer
import org.ohmyloader.api.world.OMLVec3
import org.ohmyloader.api.wrapper.OMLWorld
import java.util.Optional

/**
 * Probes the typed game-access surface (`OMLGame`, world / player / inventory reads and writes, the tick
 * scheduler) and the lifecycle and interaction events.
 *
 * Reading proves the projections hand the mod what the game holds; driving game methods proves the hooks
 * fire. An unattended run has no hand, so the probe calls `destroyBlock` / `attack` / `interactOn` /
 * `hurtServer` / `useItemOn` / `useItem` itself with its own cancelling handler armed: cancellation keeps
 * the driven call from changing the world, and the game's return value and the entity's health are what
 * the assertions read.
 *
 * oml-api is the only compile dependency, so game classes come from `OMLCore.gameClassLoader()`.
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

    /** A registered damage type the hurt probe deals through the game's own path. */
    private const val CACTUS_DAMAGE = "minecraft:cactus"

    /** The damage type [OMLTestMod] declares; the game must have loaded it from OML's content pack. */
    private const val MOD_DAMAGE_TYPE = "oml_testmod:test_melt"
    private const val MOD_DAMAGE_MESSAGE = "oml_testmod.melts"
    private const val OVERWORLD = "minecraft:overworld"

    /** A dimension whose type names no clock: the game has no time to read or write there. */
    private const val NETHER = "minecraft:the_nether"

    /** The clock the overworld runs on, and the vanilla marker the clock probes move it onto. */
    private const val OVERWORLD_CLOCK = "minecraft:overworld"
    private const val NOON_MARKER = "minecraft:noon"
    private const val ABSENT_CLOCK = "oml_testmod:absent_clock"
    private const val ABSENT_MARKER = "oml_testmod:absent_marker"

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

    /** Dedicated run or client run, asked through OMLCore reflectively. */
    private val dedicated: Boolean = runCatching {
        Class.forName("org.ohmyloader.core.OMLCore").getMethod("isServerSide").invoke(null) as Boolean
    }.getOrDefault(false)

    private val gameLoader: ClassLoader by lazy {
        Class.forName("org.ohmyloader.core.OMLCore").getMethod("gameClassLoader").invoke(null) as ClassLoader
    }

    /** Unique per run: a chat line from an earlier run must not satisfy the delivery check. */
    private val broadcastMarker = "oml-e2e-broadcast-${System.nanoTime()}"
    private val systemMarker = "oml-e2e-system-${System.nanoTime()}"

    // What the cancelling handlers observed; the driving step reads them back and reports once.
    private var breakObservation: Pair<OMLBlockPos, String>? = null
    private var attackObservation: String? = null
    private var interactObservation: String? = null
    private var hurtObservation: Triple<Float, String, Boolean>? = null
    private var blockUseObservation: BlockUse? = null
    private var itemUseObservation: Pair<String, String>? = null

    /** The number the rewrite handler saw on the way in, which must be vanilla's, not the written one. */
    private var rewriteObservation: Float? = null

    /** What the hook saw of the damage this probe **dealt**: amount, damage type, and whether an attacker
     *  was credited — the same three things a mob's own attack arrives with. */
    private var hurtActionObservation: Triple<Float, String, Boolean>? = null

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
    private var spawned: OMLLivingEntity? = null

    /** The projectile the motion probe writes into, discarded once the measurement reported. */
    private var projectile: OMLEntity? = null

    /** X position the shot had when its motion was written, the baseline the game's integration is measured from. */
    private var motionStart = Double.NaN

    /** Server tick the motion was written on; negative means the write has not happened yet. */
    private var motionTick = -1

    /** How many launches the game removed before their window closed. */
    private var motionAttempts = 0

    /** Ticks the control damage has been refused for, so a refusal becomes a verdict instead of silence. */
    private var controlWaits = 0

    /** The handle [Events.PLAYER_RESPAWNED] handed out while the respawn drive was armed. */
    private var respawnObservation: OMLPlayer? = null

    /** Armed only around the driven respawn, so a player respawning on their own cannot satisfy the check. */
    @Volatile
    private var driveRespawnWindow = false

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

    /** Everything [Events.BLOCK_USED] hands a handler, kept for the driving step to assert against. */
    private data class BlockUse(
        val position: OMLBlockPos,
        val blockId: String,
        val itemId: String,
        val hand: String,
        val face: String,
    )

    // Gates: a handler cancels only the call this probe made itself, never a real player action.
    @Volatile
    private var breakTarget: OMLBlockPos? = null

    @Volatile
    private var drivenUuid: String? = null

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

    private val reported = HashSet<String>()
    private val waited = HashMap<String, Int>()

    /** Registers the handlers. Called on both sides; only the current side's hooks ever dispatch. */
    fun register() {
        Events.SERVER_STARTED.register { event ->
            val dimensions = event.server.dimensionIds
            E2E.check(
                "event.server_started",
                "minecraft:overworld" in dimensions && event.server.tickCount >= 0,
                "dimensions=$dimensions tick=${event.server.tickCount} dedicated=${event.server.isDedicated}",
            )
        }
        // Record-only, and it cannot be an expectation: it fires during teardown, which the harness only
        // reaches after the READY line an expectation would be waiting for.
        Events.SERVER_STOPPING.register { event ->
            E2E.check(
                "event.server_stopping",
                event.server.tickCount > 0,
                "handle still reads at tick ${event.server.tickCount} with ${event.server.players.size} player(s)",
            )
        }
        Events.PLAYER_JOINED.register { event ->
            val player = event.player
            E2E.check(
                "event.player_joined",
                player.name.isNotBlank() && player.gameMode != null && player.inventory.size >= 36 &&
                    player.dimensionId.isNotBlank(),
                "player=${player.name} mode=${player.gameMode} dimension=${player.dimensionId} slots=${player.inventory.size}",
            )
        }
        // Record-only, for the same teardown-ordering reason as SERVER_STOPPING.
        Events.PLAYER_LEFT.register { event ->
            E2E.check(
                "event.player_left",
                event.player.name.isNotBlank(),
                "last-known position ${event.player.blockPosition}",
            )
        }

        Events.BLOCK_BREAK.register { event ->
            if (breakTarget == event.position) {
                breakObservation = event.position to event.blockId
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
                hurtObservation = Triple(event.amount, event.damageType, event.attacker != null)
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
                hurtActionObservation = Triple(event.amount, event.damageType, event.attacker != null)
            }
        }
        Events.BLOCK_USED.register { event ->
            if (driveBlockUse && event.player.uuid == drivenUuid) {
                blockUseObservation = BlockUse(event.position, event.blockId, event.itemId, event.hand, event.face)
                event.cancel()
            }
        }
        Events.ITEM_USED.register { event ->
            if (driveItemUse && event.player.uuid == drivenUuid) {
                itemUseObservation = event.itemId to event.hand
                event.cancel()
            }
        }
        // Tripwire, never driven: nothing here kills anything. When a natural death passes through the
        // hook, the constraint worth asserting is that the death routine starts at or below zero health.
        Events.LIVING_DIED.register { event ->
            E2E.check(
                "event.living_died",
                event.entity.health <= 0f,
                "${event.entity.typeId} entered the death routine at health ${event.entity.health} (${event.damageType})",
            )
        }
        Events.PLAYER_RESPAWNED.register { event ->
            if (driveRespawnWindow) respawnObservation = event.player
        }
        // Fires up to twice per tick per dimension in singleplayer, so it only counts and remembers the
        // first dimension each side reported; the assertions live in the step below.
        Events.LEVEL_TICK.register { event ->
            val level = event.level
            if (level.isClient) {
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
        step("api.world_weather") { probeWeather() }
        step("api.world_clock") { probeClock() }
        step("api.clock_markers") { probeClockMarkers() }
        step("api.clock_rate_and_pause") { probeClockRate() }
        // The client's copy of the clock only exists in a client process, so the dedicated run must not
        // wait for a step it can never satisfy.
        if (!dedicated) step("api.clock_client_sync") { probeClockSync() }
        step("api.server_game_rules") { probeGameRules() }
        step("api.world_particles_arguments") { probeArgumentParticles() }
        val player = OMLGame.server?.players?.firstOrNull() ?: return@guard
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
        step("api.world_particles") { probeParticles(player) }
        // Last: it kills the player and brings them back, so nothing may depend on the handle afterwards.
        step("event.player_respawned") { driveRespawn(player) }
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
        val named = server.players.firstOrNull()?.let { server.playerByName(it.name) }
        E2E.check(
            "api.server_handle",
            "minecraft:overworld" in dimensions && server.isDedicated == dedicated &&
                server.playerByName("oml_e2e_absent") == null &&
                (named != null) == server.players.isNotEmpty(),
            "dimensions=$dimensions dedicated=${server.isDedicated} on a ${if (dedicated) "dedicated" else "client"} run, " +
                "ticks=${server.tickCount} players=${server.players.size}",
        )
    }

    private var clientHandleProbed = false

    private fun probeClientHandle() {
        if (clientHandleProbed) return
        val client = OMLGame.client ?: return
        val width = client.guiWidth
        val height = client.guiHeight
        if (width <= 0 || height <= 0) return
        clientHandleProbed = true
        E2E.check(
            "api.client_handle",
            (client.player == null) == (client.world == null),
            "gui=${width}x$height screen=${client.currentScreen?.screenName ?: "none"} " +
                "player=${client.player?.name ?: "none"} world=${client.world?.dimensionId ?: "none"}",
        )
    }

    /** Reads the client level through `OMLClient.world` — the projection a client mod or HUD uses. */
    private fun probeClientWorldRead(): Boolean {
        val client = OMLGame.client ?: return false
        val world = client.world ?: return false
        val player = client.player ?: return false
        val pos = firstSolid(world, player.blockPosition, 8) ?: return false
        E2E.check("api.world_block_read", true, "${world.dimensionId} at $pos is ${world.blockAt(pos)}")
        return true
    }

    private fun probePlayerHandle(player: OMLPlayer): Boolean {
        E2E.check(
            "api.player_handle",
            player.typeId == "minecraft:player" && player.name.isNotBlank() && player.uuid.isNotBlank() &&
                player.gameMode != null && player.alive && !player.removed && !player.onFire &&
                player.health in 0f..player.maxHealth && player.position.x.isFinite() &&
                player.world.dimensionId == player.dimensionId &&
                player.mainHandItem.id == player.inventory.selected.id,
            "type=${player.typeId} name=${player.name} mode=${player.gameMode} dimension=${player.dimensionId} " +
                "health=${player.health}/${player.maxHealth} position=${player.position}",
        )
        return true
    }

    private fun probeInventory(player: OMLPlayer): Boolean {
        val inventory = player.inventory
        val slot = 5
        val original = inventory.itemAt(slot) ?: return false
        val wrote = inventory.set(slot, GLOWSTONE, 3)
        val written = inventory.itemAt(slot)
        val outOfRangeWrite = inventory.set(inventory.size, STONE, 1)
        val unknownIdWrite = inventory.set(slot, "oml_testmod:not_registered", 1)
        // Back the way it was; an empty slot has no id to write, so it is cleared through a known one.
        val restored = if (original.isEmpty) inventory.set(slot, STONE, 0) else inventory.set(slot, original.id, original.count)
        val readBack = inventory.itemAt(slot)
        inventory.selectHotbarSlot(3)
        val selected = inventory.selectedSlot
        inventory.selectHotbarSlot(0)
        E2E.check(
            "api.player_inventory",
            inventory.size >= 36 && selected == 3 && inventory.selectedSlot == 0 &&
                wrote && written?.id == GLOWSTONE && written.count == 3 &&
                !outOfRangeWrite && !unknownIdWrite && restored &&
                readBack?.id == original.id && readBack.count == original.count &&
                inventory.itemAt(inventory.size) == null,
            "slots=${inventory.size} wrote=$wrote read=${written?.id}x${written?.count} " +
                "out-of-range=$outOfRangeWrite unknown-id=$unknownIdWrite restored=${readBack?.id}x${readBack?.count}",
        )
        return true
    }

    private fun probeGiveItem(player: OMLPlayer): Boolean {
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

    /** Writes through `OMLEntity.world`, which for a server player is the **server** level. */
    private fun probeWorldWrite(player: OMLPlayer): Boolean {
        val world = player.world
        // Starts one block deeper than the break probe, so the two never take the same position.
        val feet = player.blockPosition
        val pos = firstSolid(world, OMLBlockPos(feet.x, feet.y - 1, feet.z), 8) ?: return false
        val original = world.blockAt(pos)
        val wrote = world.setBlock(pos, GLOWSTONE)
        val written = world.blockAt(pos)
        val restored = world.setBlock(pos, original)
        E2E.check(
            "api.world_block_write",
            wrote && written == GLOWSTONE && restored && world.blockAt(pos) == original,
            "$pos $original -> $written -> ${world.blockAt(pos)}",
        )
        return true
    }

    /**
     * Sends once. The verdict belongs to the client, where the chat line arrives — a failed send reports
     * here and cannot be overwritten afterwards, because nothing can arrive after it.
     */
    private fun probeSend(player: OMLPlayer): Boolean {
        val server = OMLGame.server ?: return false
        runCatching { server.broadcast(broadcastMarker) }
            .onFailure { E2E.check("api.server_broadcast", false, "broadcast threw $it") }
        runCatching { player.sendSystemMessage(systemMarker) }
            .onFailure { E2E.check("api.player_system_message", false, "sendSystemMessage threw $it") }
        return true
    }

    /**
     * `Events.LEVEL_TICK` has to fire once per tick per level, on the side that ticks it, carrying that
     * level's own projection. Singleplayer is the case that matters: the integrated level and the rendered
     * one are both dispatched, so each counter moves and each reports its own `isClient`.
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

    /** `OMLServer.level`: every dimension the server reports loaded resolves to its own server-side level. */    private fun probeLevelLookup(): Boolean {
        val server = OMLGame.server ?: return false
        val dimensions = server.dimensionIds
        if (dimensions.isEmpty()) return false
        val resolved = dimensions.map { it to server.level(it)?.let { world -> world.dimensionId to world.isClient } }
        val absent = server.level("oml_testmod:absent")
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
     * read to follow each write. Each wait re-resolves the projection, so a read cached in the wrapper
     * would never see the ramp and the phase would expire instead of passing.
     *
     * Both refusals are re-checked every tick: a client-side level does not own the weather, and the game
     * scales thunder by the rain level, so it cannot represent thunder without rain.
     */
    private fun probeWeather(): Boolean {
        val server = OMLGame.server ?: return false
        val world = server.level(OVERWORLD) ?: return false
        val clientWorld = if (dedicated) null else OMLGame.client?.world
        val thunderWithoutRain = world.setWeather(raining = false, thundering = true)
        val clientTookWrite = clientWorld?.setWeather(raining = true) == true
        if (thunderWithoutRain || clientTookWrite) {
            E2E.check(
                "api.world_weather",
                false,
                "thunder without rain accepted=$thunderWithoutRain, " +
                    "client-side level took the write=$clientTookWrite",
            )
            return true
        }
        val (raining, thundering) = WEATHER_DRIVE[weatherPhase]
        if (!weatherWritten) {
            val hold = if (raining) WEATHER_HOLD_TICKS else 0
            if (!world.setWeather(raining = raining, thundering = thundering, durationTicks = hold)) return false
            weatherWritten = true
            return false
        }
        val live = server.level(OVERWORLD) ?: return false
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
        val world = server.level(OVERWORLD) ?: return false
        val clock = world.clock ?: return false
        val nether = server.level(NETHER) ?: return false
        val before = clock.totalTicks
        val wanted = before + 1000
        val wrote = clock.setTotalTicks(wanted)
        val viaServer = server.clock(clock.clockId)
        val seenThere = viaServer?.totalTicks
        val restored = clock.setTotalTicks(before)
        val flags = listOf(
            "the dimension's clock is the game's own" to (clock.clockId == OVERWORLD_CLOCK),
            "the clock table lists it" to OMLRegistries.worldClocks.contains(OVERWORLD_CLOCK),
            "a clockless dimension reads null" to (nether.clock == null),
            "an unregistered id reads null" to (server.clock(ABSENT_CLOCK) == null),
            "the write was accepted" to wrote,
            "the server names the same clock" to (viaServer?.clockId == clock.clockId),
            "the game's record moved" to (seenThere == wanted),
            "the position restores" to (restored && clock.totalTicks == before),
        )
        E2E.check(
            "api.world_clock",
            flags.all { it.second },
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; $before -> " +
                "$seenThere (wanted $wanted), restored to ${clock.totalTicks}, nether clock=" +
                "${nether.clock?.clockId ?: "null"}",
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
        val clock = server.clock(OVERWORLD_CLOCK) ?: return false
        val markers = clock.markerIds
        val before = clock.totalTicks
        val refused = clock.moveToMarker(ABSENT_MARKER)
        val unmoved = clock.totalTicks == before
        val moved = clock.moveToMarker(NOON_MARKER)
        val atNoon = clock.isAtMarker(NOON_MARKER)
        val atAbsent = clock.isAtMarker(ABSENT_MARKER)
        val restored = clock.setTotalTicks(before)
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
                "${markers.sorted()}, $before -> ${clock.totalTicks} after moveToMarker(noon)=$moved",
        )
        return true
    }

    /**
     * Rate and pause, measured by what the game counts: a clock at [FAST_RATE] has to add two ticks per
     * game tick, a paused one has to add none, and a resumed one has to start again at the game's own rate.
     * OML does no time arithmetic, so every number here is the game's. The refusals are the game's own
     * window — its clock manager would store any value, so OML checks against what `/time rate` accepts.
     */
    private fun probeClockRate(): Boolean {
        val server = OMLGame.server ?: return false
        // The windows measure what the game counted, so no other clock writer may be active inside one.
        if ("api.world_clock" !in reported || "api.clock_markers" !in reported) return false
        val clock = server.level(OVERWORLD)?.clock ?: return false
        val tick = server.tickCount
        if (clockPhaseTick < 0) {
            clockBaseline = clock.totalTicks
            val refusedIdle = clock.setRate(IDLE_RATE)
            val refusedHuge = clock.setRate(IMPOSSIBLE_RATE)
            val accepted = clock.setRate(FAST_RATE)
            if (refusedIdle || refusedHuge || !accepted || clock.rate != FAST_RATE) {
                E2E.check(
                    "api.clock_rate_and_pause",
                    false,
                    "rate window: 0 accepted=$refusedIdle, $IMPOSSIBLE_RATE accepted=$refusedHuge, " +
                        "$FAST_RATE accepted=$accepted and reads ${clock.rate}",
                )
                clockRatePhase = 3
                return true
            }
            clockPhaseTick = tick
            clockPhaseStart = clock.totalTicks
            return false
        }
        val elapsed = tick - clockPhaseTick
        val advanced = clock.totalTicks - clockPhaseStart
        if (elapsed <= (if (clockRatePhase == 1) CLOCK_PAUSE_WINDOW else CLOCK_RATE_WINDOW)) {
            if (elapsed < CLOCK_WINDOW_LIMIT) return false
            E2E.check(
                "api.clock_rate_and_pause",
                false,
                "phase $clockRatePhase stalled after $elapsed ticks at rate ${clock.rate}, " +
                    "paused=${clock.isPaused}",
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
                "phase $clockRatePhase (rate ${clock.rate}, paused=${clock.isPaused}) counted $advanced " +
                    "in $elapsed game ticks, the game's own arithmetic wants $expected",
            )
            clockRatePhase = 3
            return true
        }
        when (clockRatePhase) {
            0 -> {
                clock.setPaused(true)
                clockRatePhase = 1
            }
            1 -> {
                clock.setPaused(false)
                clock.setRate(1f)
                clockRatePhase = 2
            }
            else -> {
                val restored = clock.setTotalTicks(clockBaseline)
                E2E.check(
                    "api.clock_rate_and_pause",
                    restored && !clock.isPaused && clock.rate == 1f,
                    "the game counted $FAST_RATE, nothing while paused, and 1.0 after resuming; the " +
                        "position restored to $clockBaseline is=${clock.totalTicks} rate=${clock.rate} " +
                        "paused=${clock.isPaused}",
                )
                clockRatePhase = 3
                return true
            }
        }
        clockPhaseTick = tick
        clockPhaseStart = clock.totalTicks
        return false
    }

    /**
     * Singleplayer keeps a second copy of the clock: the rendered level's, which the server pushes as soon
     * as it changes a clock (the game's own `modifyClock` broadcasts the new state to that dimension). The
     * probe writes a position the client's copy cannot reach by counting, then waits for it to arrive —
     * the half of this surface no server-side read can show. While it waits it also checks that the
     * client's own clock refuses a write: the client is told the time, it does not set it.
     */
    private fun probeClockSync(): Boolean {
        val server = OMLGame.server ?: return false
        // Its window is a clock write too: it may not run while another clock probe is still counting.
        if ("api.clock_rate_and_pause" !in reported) return false
        val clock = server.clock(OVERWORLD_CLOCK) ?: return false
        val clientClock = if (dedicated) return false else OMLGame.client?.world?.clock ?: return false
        val tick = server.tickCount
        if (clockSyncPhase == 0) {
            clockSyncPrevious = clock.totalTicks
            clockSyncTarget = clockSyncPrevious + CLOCK_SYNC_JUMP
            if (!clock.setTotalTicks(clockSyncTarget)) {
                E2E.check("api.clock_client_sync", false, "the server refused the write of $clockSyncTarget")
                clockSyncPhase = 2
                return true
            }
            clockSyncTick = tick
            clockSyncPhase = 1
            return false
        }
        if (clockSyncPhase != 1) return true
        val tookWrite = clientClock.setTotalTicks(clientClock.totalTicks + 1)
        val seen = clientClock.totalTicks
        if (tookWrite) {
            clock.setTotalTicks(clockSyncPrevious)
            E2E.check(
                "api.clock_client_sync",
                false,
                "the rendered level's clock accepted a write of its own, so the client owns time it does not have",
            )
            clockSyncPhase = 2
            return true
        }
        val arrived = seen >= clockSyncTarget && seen <= clockSyncTarget + CLOCK_SYNC_TOLERANCE
        if (arrived || tick - clockSyncTick > CLOCK_SYNC_WAIT_TICKS) {
            clock.setTotalTicks(clockSyncPrevious)
            E2E.check(
                "api.clock_client_sync",
                arrived,
                "wrote $clockSyncTarget on the server; the client read $seen after ${tick - clockSyncTick} " +
                    "ticks (the server stands at ${clock.totalTicks})",
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
        val original = server.gameRule(KEEP_INVENTORY_RULE) ?: return false
        val flipped = if (original == "true") "false" else "true"
        val wrote = server.setGameRule(KEEP_INVENTORY_RULE, flipped)
        val readBack = server.gameRule(KEEP_INVENTORY_RULE)
        val restored = server.setGameRule(KEEP_INVENTORY_RULE, original)
        val listed = OMLRegistries.gameRules.contains(KEEP_INVENTORY_RULE)
        val unknownRead = server.gameRule("oml_testmod:absent")
        val unknownWrite = server.setGameRule("oml_testmod:absent", "true")
        val unknownListed = OMLRegistries.gameRules.contains("oml_testmod:absent")
        val unparseable = server.setGameRule(KEEP_INVENTORY_RULE, "3")
        E2E.check(
            "api.server_game_rules",
            wrote && readBack == flipped && restored &&
                server.gameRule(KEEP_INVENTORY_RULE) == original && listed && !unknownListed &&
                unknownRead == null && !unknownWrite && !unparseable,
            "$KEEP_INVENTORY_RULE $original -> $readBack -> ${server.gameRule(KEEP_INVENTORY_RULE)}; " +
                "in the registry: $listed, unknown id read=$unknownRead write=$unknownWrite " +
                "listed=$unknownListed, \"3\" into a boolean rule=$unparseable",
        )
        return true
    }

    /**
     * The three argument-carrying particles a bare id could not express, accepted by the game's own sender,
     * and the rejections: an unknown item, an unknown block, and a client-side level.
     */
    private fun probeArgumentParticles(): Boolean {
        val server = OMLGame.server ?: return false
        val world = server.level(OVERWORLD) ?: return false
        val item = world.spawnItemParticles("minecraft:diamond_sword", 0.5, 200.0, 0.5, count = 4)
        val block = world.spawnBlockParticles("minecraft:stone", 0.5, 200.0, 0.5, count = 4)
        val dust = world.spawnDustParticles(DUST_COLOR, 1.5f, 0.5, 200.0, 0.5, count = 4)
        val unknownItem = world.spawnItemParticles("oml_testmod:absent", 0.5, 200.0, 0.5)
        val unknownBlock = world.spawnBlockParticles("oml_testmod:absent", 0.5, 200.0, 0.5)
        val clientWorld = if (dedicated) null else OMLGame.client?.world
        val clientRefused = clientWorld?.spawnItemParticles("minecraft:diamond_sword", 0.0, 100.0, 0.0)
        E2E.check(
            "api.world_particles_arguments",
            item && block && dust && !unknownItem && !unknownBlock && (clientWorld == null || clientRefused == false),
            "item=$item block=$block dust=$dust accepted; unknown item=$unknownItem unknown block=$unknownBlock" +
                (if (clientWorld == null) "" else "; client-side level=$clientRefused"),
        )
        return true
    }

    /**
     * `OMLWorld.isLoaded` is what separates "this chunk is not there" from "this block is air". Nothing
     * reads the far position: a server level answers the game's own getter there, which is real terrain
     * rather than air, so only the loaded flags are this probe's subject.
     */
    private fun probeIsLoaded(player: OMLPlayer): Boolean {
        val world = player.world
        val feet = player.blockPosition
        val ground = firstSolid(world, feet, 8) ?: return false
        val far = OMLBlockPos(feet.x + 100_000, feet.y, feet.z)
        val farLoaded = world.isLoaded(far)
        E2E.check(
            "api.world_is_loaded",
            world.isLoaded(ground) && world.isLoaded(feet) && !farLoaded,
            "feet $feet and its ground $ground report loaded, $far reports loaded=$farLoaded",
        )
        return true
    }

    /** Registry and tag reads: the same tables the mod's own content is materialized into. */
    private fun probeRegistries(): Boolean {
        val blocks = OMLRegistries.blocks
        val stoneTags = blocks.tagsOf(STONE)
        val dirtTag = blocks.tagged(DIRT)
        val baseStone = blocks.tagged("minecraft:base_stone_overworld")
        val flags = listOf(
            "id listed" to (STONE in blocks.ids),
            "id resolves" to blocks.contains(STONE),
            "unregistered id" to !blocks.contains("oml_testmod:absent"),
            "item" to OMLRegistries.items.contains(DIRT),
            "effect" to OMLRegistries.mobEffects.contains("minecraft:poison"),
            "sound" to OMLRegistries.sounds.contains("minecraft:entity.pig.ambient"),
            "entity type" to OMLRegistries.entityTypes.contains("minecraft:cow"),
            "attribute" to OMLRegistries.attributes.contains(MAX_HEALTH_ATTRIBUTE),
            "damage type" to OMLRegistries.damageTypes.contains(CACTUS_DAMAGE),
            "unregistered damage type" to !OMLRegistries.damageTypes.contains("oml_testmod:absent"),
            "dirt tag holds dirt" to (DIRT in dirtTag),
            "base_stone_overworld holds stone" to (STONE in baseStone),
            "stone reports its tags" to ("minecraft:base_stone_overworld" in stoneTags),
            "unregistered tag is empty" to blocks.tagged("oml_testmod:absent").isEmpty(),
        )
        E2E.check(
            "api.registry_reads",
            flags.all { it.second },
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; ${blocks.ids.size} block ids, " +
                "dirt tag=${dirtTag.sorted()}, stone tags=${stoneTags.sorted()}, " +
                "unregistered tag=${blocks.tagged("oml_testmod:absent")}",
        )
        return true
    }

    /** Effects on the live player, including the three absent-id answers the API promises. */
    private fun probeEffects(player: OMLPlayer): Boolean {
        val speed = "minecraft:speed"
        val applied = player.applyEffect(speed, 200, 1)
        val instance = player.effects.firstOrNull { it.typeId == speed }
        val has = player.hasEffect(speed)
        val removed = player.removeEffect(speed)
        val stillThere = player.hasEffect(speed)
        val absent = player.applyEffect("oml_testmod:absent", 20, 0) ||
            player.hasEffect("oml_testmod:absent") ||
            player.removeEffect("oml_testmod:absent")
        E2E.check(
            "api.player_effects",
            applied && has && instance != null && instance.amplifier == 1 && instance.durationTicks in 1..200 &&
                removed && !stillThere && !absent && player.effects.none { it.typeId == speed },
            "applied=$applied saw=$instance removed=$removed after=${player.effects}, absent id reported $absent",
        )
        return true
    }

    /** Healing a damaged player moves health by exactly what was asked, and never past the maximum. */
    private fun probeHeal(player: OMLPlayer): Boolean {
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

    private fun probeWorldSound(player: OMLPlayer): Boolean {
        val world = player.world
        val at = player.position
        val played = world.playSound("minecraft:entity.pig.ambient", at.x, at.y, at.z, 1f, 1f, "neutral")
        val unknownId = world.playSound("oml_testmod:absent", at.x, at.y, at.z)
        val unknownCategory = world.playSound("minecraft:entity.pig.ambient", at.x, at.y, at.z, category = "not_a_category")
        E2E.check(
            "api.world_sound",
            played && !unknownId && !unknownCategory,
            "registered id=$played unknown id=$unknownId unknown category=$unknownCategory; a headless run cannot hear it, this is the id and category contract",
        )
        return true
    }

    private fun probePlayerSound(player: OMLPlayer): Boolean {
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
     * Ops the player through the game's own list and deops them again, then asks the two independent
     * readings the same question: which level does the game hold, and does the projection report exactly
     * that number. Which number `ADMIN` lands on is the game's business (an integrated server elevates
     * its local player), so the probe asserts the agreement, not a constant.
     */
    private fun probePermissions(player: OMLPlayer): Boolean {
        val server = OMLGame.server ?: return false
        val playerList = server.platform.call("getPlayerList") ?: return false
        val nameAndId = player.platform.call("nameAndId") ?: return false
        val admin = gameClass("net.minecraft.server.permissions.LevelBasedPermissionSet").getField("ADMIN").get(null)
        val gameBefore = gamePermissionLevel(player)
        val apiBefore = player.permissionLevel
        playerList.call("op", nameAndId, Optional.of(admin), Optional.empty<Any>())
        val gameGranted = gamePermissionLevel(player)
        val apiGranted = player.permissionLevel
        playerList.call("deop", nameAndId)
        val gameAfter = gamePermissionLevel(player)
        val apiAfter = player.permissionLevel
        val readFailure = listOf(gameBefore, gameGranted, gameAfter).firstNotNullOfOrNull { it.exceptionOrNull() }
        if (readFailure != null) {
            // The probe's own path into the game's permission state, not the projection, is what failed.
            E2E.check("api.player_permissions", false, "the game's own permission read failed: $readFailure")
            return true
        }
        val levels = listOf(gameBefore, gameGranted, gameAfter).map { it.getOrThrow() as Int }
        E2E.check(
            "api.player_permissions",
            apiBefore == levels[0] && apiGranted == levels[1] && apiAfter == levels[2] &&
                levels[1] > levels[0] && levels[2] == levels[0],
            "OML $apiBefore -> $apiGranted -> $apiAfter, the game's own $levels " +
                "(the ADMIN set handed to the game is level ${permissionLevelId(admin)})",
        )
        return true
    }

    /**
     * The level the game itself holds for this player, the state [OMLPlayer.permissionLevel] projects.
     * Reached through the public interfaces: the implementation class the game hands back is
     * package-private, so invoking its methods reflectively fails on access rather than on the name.
     */
    private fun gamePermissionLevel(player: OMLPlayer): Result<Any?> = runCatching {
        permissionLevelId(player.platform.javaClass.getMethod("permissions").invoke(player.platform)!!)
    }

    /** The `PermissionLevel` id carried by a `LevelBasedPermissionSet`. */
    private fun permissionLevelId(set: Any): Any? = gameClass("net.minecraft.server.permissions.PermissionLevel")
        .getMethod("id")
        .invoke(gameClass("net.minecraft.server.permissions.LevelBasedPermissionSet").getMethod("level").invoke(set))

    /** Removes the item from as many slots as it takes, and reports the exact number it took. */
    private fun probeInventoryRemove(player: OMLPlayer): Boolean {
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
    private fun probeTeleport(player: OMLPlayer): Boolean {
        val home = player.blockPosition
        val target = OMLBlockPos(home.x, home.y + 2, home.z)
        if (player.world.blockAt(target) != AIR) return false
        val moved = player.teleport(home.x + 0.5, home.y + 2.1, home.z + 0.5)
        val afterMove = player.blockPosition
        val explicit = player.teleport(home.x + 0.5, home.y + 0.1, home.z + 0.5, player.dimensionId)
        val backHome = player.blockPosition
        val refused = player.teleport(home.x + 0.5, home.y + 2.1, home.z + 0.5, "oml_testmod:absent")
        val stillHome = player.blockPosition
        E2E.check(
            "api.player_teleport",
            moved && afterMove == target && explicit && backHome == home && !refused && stillHome == home,
            "$home -> $afterMove (wanted $target) -> $backHome, unregistered dimension returned $refused at $stillHome",
        )
        return true
    }

    /**
     * Spawns a registered type beside the player and reads it back out of the level's own entity query,
     * with the rejections the API promises: an unregistered id, a client-side level, and a non-living type
     * asked of the living spawn.
     */
    private fun probeSpawn(player: OMLPlayer): Boolean {
        val world = player.world
        val at = player.position
        val cow = world.spawnLivingEntity(COW, at.x + 2.5, at.y + 1.0, at.z + 2.5) ?: return false
        val unknown = world.spawnEntity("oml_testmod:absent", at.x, at.y, at.z)
        val notLiving = world.spawnLivingEntity(SNOWBALL, at.x + 3.5, at.y + 1.0, at.z + 3.5)
        val listed = world.entitiesOfType(COW)
        val clientWorld = OMLGame.client?.world ?: return false
        val clientRefused = clientWorld.spawnEntity(COW, at.x, at.y, at.z)
        spawned = cow
        E2E.check(
            "api.entity_spawn",
            unknown == null && cow.typeId == COW && !cow.removed && cow.health > 0f && notLiving == null &&
                cow.world.dimensionId == world.dimensionId &&
                listed.any { it.uuid == cow.uuid } && clientRefused == null,
            "spawned ${cow.typeId} at ${cow.blockPosition} in ${cow.dimensionId} with health ${cow.health}, the " +
                "level lists ${listed.size} of $COW, unregistered id -> $unknown, $SNOWBALL as living -> " +
                "$notLiving, client-side level -> $clientRefused",
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
    private fun probeMotion(player: OMLPlayer): Boolean {
        val world = player.world
        val at = player.position
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
            shot.velocity = OMLVec3(MOTION_X, 0.0, 0.0)
            val written = shot.velocity
            if (written != OMLVec3(MOTION_X, 0.0, 0.0)) {
                E2E.check("api.entity_motion", false, "writing x=$MOTION_X read back $written")
                return true
            }
            projectile = shot
            motionStart = shot.position.x
            motionTick = tick
            return false
        }
        if (tick - motionTick < MOTION_WINDOW_TICKS) {
            if (projectile?.removed == true) {
                motionAttempts++
                projectile = null
                motionTick = -1
            }
            return false
        }
        val shot = projectile ?: return false
        val moved = shot.position.x - motionStart
        val stillMoving = shot.velocity.x
        E2E.check(
            "api.entity_motion",
            stillMoving > 0.0 && moved > 0.05,
            "wrote x=$MOTION_X and read it back; ${tick - motionTick} ticks later x=$stillMoving, it drifted " +
                "${"%.2f".format(moved)} blocks, removed=${shot.removed}",
        )
        projectile = null
        return true
    }

    /**
     * A launch point with a clear run along +x, climbed up from above the player's head until the blocks
     * say air. Position 2.5 off the player is where a spawned entity lands anyway; the run is measured
     * generously because the shot keeps travelling after the window closes.
     */
    private fun openAirRun(world: OMLWorld, at: OMLVec3): Triple<Double, Double, Double>? {
        var y = at.y + SHOT_LIFT
        repeat(SHOT_CLIMB) {
            val level = y.toInt()
            val clear = (0..SHOT_RUN).all { step ->
                world.blockAt(OMLBlockPos((at.x + step).toInt(), level, at.z.toInt())) == AIR
            }
            if (clear) return Triple(at.x + 2.5, y, at.z + 2.5)
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
    private fun probeAttributes(player: OMLPlayer): Boolean {
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
        )
        E2E.check(
            "api.entity_attributes",
            flags.all { it.second },
            "${flags.filterNot { it.second }.map { it.first }} failed of ${flags.size}; base $baseBefore -> " +
                "$raisedBase (game cap $capNow, restored to ${player.maxHealth}), health $healthBefore -> " +
                "$healthAfterRaise, attack $damageBefore -> $withModifier -> $withoutModifier",
        )
        return true
    }

    /**
     * Deals damage with the API instead of receiving it: the amount, the damage type and the credited
     * attacker have to arrive at the loader's own `LIVING_HURT` hook exactly as a mob's attack does, and
     * the health has to drop by what the game applied. The unregistered id is driven **first** so its
     * refusal cannot be the game's damage cooldown talking.
     */
    private fun probeDealtDamage(player: OMLPlayer): Boolean {
        val cow = spawned ?: return false
        if (cow.removed) return false
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
        target: OMLLivingEntity,
        damageTypeId: String,
        attacker: OMLPlayer? = null,
    ): DealtDamage {
        hurtActionObservation = null
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
        val world = server.level(OVERWORLD) ?: return false
        if (!OMLRegistries.damageTypes.contains(MOD_DAMAGE_TYPE)) return false
        val cow = world.spawnLivingEntity(COW, 0.5, 200.0, 0.5) ?: return false
        val absent = driveDamageWith(cow, "oml_testmod:absent")
        val melt = driveDamageWith(cow, MOD_DAMAGE_TYPE)
        cow.remove()
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
     * Lights a mob, reads the game's own burning state back, puts it out, and asks an immune mob to refuse
     * it — that refusal is the API's false, not a silent no-op.
     */
    private fun probeFire(player: OMLPlayer): Boolean {
        val cow = spawned ?: return false
        if (cow.removed) return false
        val lit = cow.setOnFire(FIRE_TICKS)
        val burning = cow.onFire
        val ticks = cow.fireTicks
        val extinguished = cow.clearFire()
        val unlit = cow.onFire
        val clearedAgain = cow.clearFire()
        val at = player.position
        val strider = player.world.spawnLivingEntity(STRIDER, at.x - 2.5, at.y + 1.0, at.z - 2.5) ?: return false
        val refused = strider.setOnFire(FIRE_TICKS)
        val immuneStaysUnlit = !strider.onFire
        strider.remove()
        E2E.check(
            "api.entity_fire",
            lit && burning && ticks in 1..FIRE_TICKS && extinguished && !unlit && !clearedAgain &&
                !refused && immuneStaysUnlit,
            "setOnFire($FIRE_TICKS)=$lit burning=$burning ticks=$ticks, clearFire=$extinguished then " +
                "$clearedAgain; the fire-immune $STRIDER answered $refused and burning=$immuneStaysUnlit",
        )
        return true
    }

    /**
     * Takes the entity out of the level and checks the game's own query stops listing it. It waits for the
     * steps that share this entity to report first: freeing the handle while one of them is still reading
     * it leaves that step with nothing to measure.
     */
    private fun probeRemoval(player: OMLPlayer): Boolean {
        if (!setOf("api.entity_motion", "api.entity_hurt", "api.entity_fire").all { it in reported }) return false
        val cow = spawned ?: return false
        if (!cow.remove()) {
            E2E.check("api.entity_removal", false, "the game refused to remove the spawned $COW")
            return true
        }
        val stillListed = player.world.entitiesOfType(COW).any { it.uuid == cow.uuid }
        E2E.check(
            "api.entity_removal",
            cow.removed && !stillListed,
            "remove() reported ${cow.removed}, the level still lists it=$stillListed",
        )
        spawned = null
        return true
    }

    /**
     * The state layer: the id says chest, the properties say which way it faces. Written back through
     * `setBlockState` and read from the level again, so the write is proved at the state rather than at the
     * id — and an illegal value, an unknown property and an unloaded position all answer the way the API
     * says they must.
     */
    private fun probeBlockState(player: OMLPlayer): Boolean {
        val world = player.world
        val feet = player.blockPosition
        val ground = firstSolid(world, OMLBlockPos(feet.x, feet.y - 1, feet.z), 8) ?: return false
        val original = world.blockStateAt(ground) ?: return false
        val unloaded = world.blockStateAt(OMLBlockPos(feet.x + 100_000, feet.y, feet.z))
        if (!world.setBlock(ground, CHEST)) return false
        val placed = world.blockStateAt(ground) ?: return false
        val rotated = placed.with("facing", "east")
        val wrote = rotated != null && world.setBlockState(ground, rotated)
        val readBack = world.blockStateAt(ground)
        val badValue = placed.with("facing", "sideways")
        val badProperty = placed.with("oml_nonsense", "east")
        val restored = world.setBlockState(ground, original)
        E2E.check(
            "api.block_state",
            placed.blockId == CHEST && "facing" in placed.properties && wrote &&
                readBack?.value("facing") == "east" && readBack?.blockId == CHEST &&
                badValue == null && badProperty == null && restored &&
                world.blockStateAt(ground)?.blockId == original.blockId && unloaded == null,
            "$ground ${original.blockId} ${original.properties} -> $CHEST facing=${placed.value("facing")} -> east " +
                "read back ${readBack?.value("facing")} -> restored ${world.blockStateAt(ground)?.blockId}; " +
                "illegal value -> $badValue, unknown property -> $badProperty, unloaded position -> $unloaded",
        )
        return true
    }

    /**
     * Acceptance, not delivery: what this side of the game can be held to is that a registered simple
     * particle goes out through the game's own sender, and that the two kinds it refuses — an unknown id,
     * and a particle type that needs arguments a bare id cannot carry — come back false on either side.
     */
    private fun probeParticles(player: OMLPlayer): Boolean {
        val world = player.world
        val at = player.position
        val accepted = world.spawnParticles(
            POOF, at.x, at.y + 1.0, at.z, count = 5, offsetX = 0.5, offsetY = 0.5, offsetZ = 0.5,
        )
        val unknown = world.spawnParticles("oml_testmod:absent", at.x, at.y, at.z)
        val needsArguments = world.spawnParticles(DUST, at.x, at.y, at.z)
        val clientWorld = OMLGame.client?.world ?: return false
        val clientSide = clientWorld.spawnParticles(POOF, at.x, at.y, at.z)
        E2E.check(
            "api.world_particles",
            accepted && !unknown && !needsArguments && clientSide == false,
            "$POOF accepted=$accepted, unknown id=$unknown, $DUST (needs arguments)=$needsArguments, " +
                "client-side level=$clientSide",
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
    private fun driveRespawn(player: OMLPlayer): Boolean {
        if (!clientLoaded(player) || !player.alive) return false
        val server = OMLGame.server ?: return false
        val playerList = server.platform.call("getPlayerList") ?: return false
        val reason = gameClass("net.minecraft.world.entity.Entity\$RemovalReason").getField("KILLED").get(null)
        respawnObservation = null
        driveRespawnWindow = true
        val returned = try {
            playerList.call("respawn", player.platform, true, reason)
        } finally {
            driveRespawnWindow = false
        }
        val seen = respawnObservation
        E2E.check(
            "event.player_respawned",
            seen != null && seen.uuid == player.uuid && !seen.removed &&
                seen.platform !== player.platform && player.removed && seen.health > 0f && returned != null,
            "the event carried ${if (seen == null) "nothing" else "a live handle for ${seen.name} at health ${seen.health}"}; " +
                "a different instance from the replaced one=${seen?.platform !== player.platform}, " +
                "the old handle now reads removed=${player.removed}",
        )
        return true
    }

    private fun driveBlockBreak(player: OMLPlayer): Boolean {
        val pos = firstSolid(player.world, player.blockPosition, 8) ?: return false
        val before = player.world.blockAt(pos)
        val gameMode = gameModeOf(player)
        breakObservation = null
        breakTarget = pos
        val destroyed = try {
            gameMode.call("destroyBlock", gameBlockPos(pos)) as Boolean
        } finally {
            breakTarget = null
        }
        val after = player.world.blockAt(pos)
        val seen = breakObservation
        E2E.check(
            "event.block_break",
            seen != null && seen.first == pos && seen.second == before && !destroyed && after == before,
            "handler saw ${seen?.second} at ${seen?.first}; destroyBlock returned $destroyed; $pos $before -> $after",
        )
        return true
    }

    private fun driveAttack(player: OMLPlayer): Boolean {
        attackObservation = null
        drivenUuid = player.uuid
        driveAttack = true
        // Self-targeted, and cancelled at HEAD: the swing never runs, so the observation is the handler's
        // own report rather than a health delta.
        val outcome = runCatching { player.platform.call("attack", player.platform) }
        driveAttack = false
        val seen = attackObservation
        E2E.check(
            "event.entity_attack",
            outcome.isSuccess && seen == player.typeId,
            "handler saw target type ${seen ?: "nothing"}; the driven call ${if (outcome.isSuccess) "returned" else "threw ${outcome.exceptionOrNull()}"}",
        )
        return true
    }

    private fun driveInteract(player: OMLPlayer): Boolean {
        val hand = gameClass("net.minecraft.world.InteractionHand").getField("MAIN_HAND").get(null)
        val position = player.position
        val hitVec = gameVec3(position.x, position.y, position.z)
        interactObservation = null
        drivenUuid = player.uuid
        driveInteract = true
        val result = try {
            player.platform.call("interactOn", player.platform, hand, hitVec)
        } finally {
            driveInteract = false
        }
        val seen = interactObservation
        E2E.check(
            "event.entity_interact",
            seen != null && result === interactionPass(),
            "handler saw hand=$seen; interactOn returned $result (PASS is the cancel's own return value)",
        )
        return true
    }

    private fun driveHurt(player: OMLPlayer): Boolean {
        // A just-joined player is invulnerable until the client reports its terrain loaded
        // (`ServerPlayer.isInvulnerableTo`), and an invulnerable damage application returns before it
        // reaches the base `hurtServer` the hook sits on — so the drive waits for that window to close
        // instead of reporting the game's own gate as a missing event.
        if (!clientLoaded(player)) return false
        val level = player.platform.call("level") ?: return false
        val source = level.call("damageSources")?.call("generic") ?: return false
        val healthBefore = player.health
        hurtObservation = null
        drivenUuid = player.uuid
        driveHurt = true
        val applied = try {
            player.platform.call("hurtServer", level, source, HURT_AMOUNT) as Boolean
        } finally {
            driveHurt = false
        }
        val healthAfter = player.health
        val seen = hurtObservation
        E2E.check(
            "event.living_hurt",
            seen != null && seen.first == HURT_AMOUNT && !seen.third && !applied && healthAfter == healthBefore,
            "handler saw amount=${seen?.first} type=${seen?.second} attacker=${seen?.third}; hurtServer returned $applied; " +
                "health $healthBefore -> $healthAfter in ${player.gameMode} mode",
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
    private fun driveHurtRewrite(player: OMLPlayer): Boolean {
        if (!clientLoaded(player)) return false
        val level = player.platform.call("level") ?: return false
        val source = level.call("damageSources")?.call("generic") ?: return false
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
    private fun driveDamage(player: OMLPlayer, level: Any, source: Any, rewriting: Boolean): Pair<Float, Boolean> {
        val healthBefore = player.health
        rewriteObservation = null
        drivenUuid = player.uuid
        if (rewriting) driveHurtRewrite = true
        val applied = try {
            player.platform.call("hurtServer", level, source, HURT_AMOUNT) as Boolean
        } finally {
            if (rewriting) driveHurtRewrite = false
        }
        return (healthBefore - player.health) to applied
    }

    private fun driveBlockUse(player: OMLPlayer): Boolean {
        val pos = firstSolid(player.world, player.blockPosition, 8) ?: return false
        val before = player.world.blockAt(pos)
        blockUseObservation = null
        drivenUuid = player.uuid
        driveBlockUse = true
        var result: Any? = null
        val restored = try {
            withDirtInHand(player) { stack ->
                result = gameModeOf(player)
                    .call("useItemOn", player.platform, player.platform.call("level"), stack, mainHand(), blockHit(pos))
                true
            }
        } finally {
            driveBlockUse = false
        }
        val seen = blockUseObservation
        E2E.check(
            "event.block_used",
            restored && seen != null && seen.position == pos && seen.blockId == before &&
                seen.itemId == DIRT && seen.hand == "main_hand" && seen.face == "up" && result === interactionFail(),
            "handler saw $seen; useItemOn returned $result (FAIL is the cancel's own return value); " +
                "$pos is still ${player.world.blockAt(pos)}, hand restored=$restored",
        )
        return true
    }

    private fun driveItemUse(player: OMLPlayer): Boolean {
        itemUseObservation = null
        drivenUuid = player.uuid
        driveItemUse = true
        var result: Any? = null
        val restored = try {
            withDirtInHand(player) { stack ->
                result = gameModeOf(player).call("useItem", player.platform, player.platform.call("level"), stack, mainHand())
                true
            }
        } finally {
            driveItemUse = false
        }
        val seen = itemUseObservation
        E2E.check(
            "event.item_used",
            restored && seen != null && seen.first == DIRT && seen.second == "main_hand" && result === interactionFail(),
            "handler saw $seen; useItem returned $result (FAIL is the cancel's own return value), hand restored=$restored",
        )
        return true
    }

    /**
     * Puts dirt in the hand slot for the driven click and puts the slot back afterwards: the event's item
     * id has to be an item the player actually holds, and an uncancelled use would consume it.
     */
    private fun withDirtInHand(player: OMLPlayer, body: (Any) -> Boolean): Boolean {
        val inventory = player.inventory
        val slot = inventory.selectedSlot
        val original = inventory.itemAt(slot) ?: return false
        if (!inventory.set(slot, DIRT, 1)) return false
        val stack = inventory.itemAt(slot)?.platform ?: return false
        val verdict = body(stack)
        val rewrote = if (original.isEmpty) inventory.set(slot, DIRT, 0) else inventory.set(slot, original.id, original.count)
        val readBack = inventory.itemAt(slot)
        return verdict && rewrote && readBack?.id == original.id && readBack.count == original.count
    }

    /**
     * `ServerPlayer.gameMode` is the break-and-use manager, a public field. The API's `gameMode` member is
     * the accessor of the same name, which returns the player's game type instead.
     */
    private fun gameModeOf(player: OMLPlayer): Any =
        player.platform.javaClass.getField("gameMode").get(player.platform)

    private fun mainHand(): Any = gameClass("net.minecraft.world.InteractionHand").getField("MAIN_HAND").get(null)

    /** A click on the top face of [pos], the face the block under the player is looked at through. */
    private fun blockHit(pos: OMLBlockPos): Any =
        gameClass("net.minecraft.world.phys.BlockHitResult")
            .getConstructor(
                gameClass("net.minecraft.world.phys.Vec3"),
                gameClass("net.minecraft.core.Direction"),
                gameClass("net.minecraft.core.BlockPos"),
                Boolean::class.java,
            )
            .newInstance(
                gameVec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5),
                gameClass("net.minecraft.core.Direction").getField("UP").get(null),
                gameBlockPos(pos),
                false,
            )

    /** Whether the player's own client has finished loading the terrain it stands in. */
    private fun clientLoaded(player: OMLPlayer): Boolean {
        val connection = player.platform.javaClass.getField("connection").get(player.platform) ?: return false
        return connection.call("hasClientLoaded") as Boolean
    }

    /** The state the game's own damage guards read, which is what a refused driven damage has to report. */
    private fun playerState(player: OMLPlayer): String =
        "mode=${player.gameMode} health=${player.health}/${player.maxHealth} alive=${player.alive} " +
            "onFire=${player.onFire} at ${player.blockPosition} in ${player.dimensionId}"

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
    private fun firstSolid(world: OMLWorld, from: OMLBlockPos, depth: Int): OMLBlockPos? {
        for (drop in 1..depth) {
            val pos = OMLBlockPos(from.x, from.y - drop, from.z)
            if (world.blockAt(pos) != AIR) return pos
        }
        return null
    }

    private fun interactionPass(): Any = gameClass("net.minecraft.world.InteractionResult").getField("PASS").get(null)

    /** A cancelled right-click answers `FAIL`, which is why the driver asserts on the driven call's own result. */
    private fun interactionFail(): Any = gameClass("net.minecraft.world.InteractionResult").getField("FAIL").get(null)

    private fun gameVec3(x: Double, y: Double, z: Double): Any =
        gameClass("net.minecraft.world.phys.Vec3")
            .getConstructor(Double::class.java, Double::class.java, Double::class.java)
            .newInstance(x, y, z)

    private fun gameBlockPos(pos: OMLBlockPos): Any =
        gameClass("net.minecraft.core.BlockPos")
            .getConstructor(Int::class.java, Int::class.java, Int::class.java)
            .newInstance(pos.x, pos.y, pos.z)

    private fun gameClass(name: String): Class<*> = gameLoader.loadClass(name)

    /** A public game method by name and arity; 26.x jars carry source names, so no mapping is needed. */
    private fun Any.call(name: String, vararg args: Any?): Any? =
        javaClass.methods
            .first { !it.isBridge && !it.isSynthetic && it.name == name && it.parameterCount == args.size }
            .invoke(this, *args)
}
