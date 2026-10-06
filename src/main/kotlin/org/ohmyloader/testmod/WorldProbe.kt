package org.ohmyloader.testmod

import org.ohmyloader.api.access.OMLGame
import org.ohmyloader.api.event.Events
import org.ohmyloader.api.tick.OMLScheduler
import org.ohmyloader.api.tick.OMLSide
import org.ohmyloader.api.tick.OMLTask
import org.ohmyloader.api.world.OMLBlockPos
import org.ohmyloader.api.world.OMLPlayer
import org.ohmyloader.api.wrapper.OMLWorld

/**
 * Probes the typed game-access surface (`OMLGame`, world / player / inventory reads and writes, the tick
 * scheduler) and the lifecycle and interaction events.
 *
 * Reading proves the projections hand the mod what the game holds; driving game methods proves the hooks
 * fire. An unattended run has no hand, so the probe calls `destroyBlock` / `attack` / `interactOn` /
 * `hurtServer` itself with its own cancelling handler armed: cancellation keeps the driven call from
 * changing the world, and the game's return value and the entity's health are what the assertions read.
 *
 * oml-api is the only compile dependency, so game classes come from `OMLCore.gameClassLoader()`.
 */
object WorldProbe {

    private const val AIR = "minecraft:air"
    private const val STONE = "minecraft:stone"
    private const val GLOWSTONE = "minecraft:glowstone"
    private const val HURT_AMOUNT = 2f

    /** A probe whose precondition has not been met for this many ticks has a defect, not a slow world. */
    private const val GIVE_UP_TICKS = 600

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
        val player = OMLGame.server?.players?.firstOrNull() ?: return@guard
        step("api.player_handle") { probePlayerHandle(player) }
        step("api.player_inventory") { probeInventory(player) }
        step("api.give_item") { probeGiveItem(player) }
        step("api.world_block_write") { probeWorldWrite(player) }
        step("api.server_broadcast") { probeSend(player) }
        step("event.block_break") { driveBlockBreak(player) }
        step("event.entity_attack") { driveAttack(player) }
        step("event.entity_interact") { driveInteract(player) }
        step("event.living_hurt") { driveHurt(player) }
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

    private fun driveBlockBreak(player: OMLPlayer): Boolean {
        val pos = firstSolid(player.world, player.blockPosition, 8) ?: return false
        val before = player.world.blockAt(pos)
        // `ServerPlayer.gameMode` is the break manager, a public field; the API's gameMode member maps to
        // the accessor of the same name, which returns the player's GameType instead.
        val gameMode = player.platform.javaClass.getField("gameMode").get(player.platform)
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
        val hitVec = gameClass("net.minecraft.world.phys.Vec3")
            .getConstructor(Double::class.java, Double::class.java, Double::class.java)
            .newInstance(position.x, position.y, position.z)
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

    /** Whether the player's own client has finished loading the terrain it stands in. */
    private fun clientLoaded(player: OMLPlayer): Boolean {
        val connection = player.platform.javaClass.getField("connection").get(player.platform) ?: return false
        return connection.call("hasClientLoaded") as Boolean
    }

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
