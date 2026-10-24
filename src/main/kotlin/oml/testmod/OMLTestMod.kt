package oml.testmod

import net.minecraft.client.gui.screens.BackupConfirmScreen
import net.minecraft.core.component.DataComponentMap
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import org.ohmyloader.api.Mod
import org.ohmyloader.api.ModContext
import org.ohmyloader.api.OMLModInitializer
import org.ohmyloader.api.client.OMLKeyBindingProvider
import org.ohmyloader.api.client.OMLKeyBindingRegistry
import org.ohmyloader.api.command.OMLCommandProvider
import org.ohmyloader.api.command.OMLCommandRegistry
import org.ohmyloader.api.content.ContentRegistry
import org.ohmyloader.api.content.OMLContentProvider
import org.ohmyloader.api.network.OMLNetworkProvider
import org.ohmyloader.api.network.OMLNetworkRegistry
import org.ohmyloader.api26_3.OMLGame
import org.ohmyloader.api26_3.currentScreen
import org.ohmyloader.api26_3.event.Events

/**
 * The first end-to-end test mod written from a mod author's perspective using OML.
 * Explicit registration (no annotation scanning): the listeners are lambdas, so registration is
 * traceable.
 */
@Mod(id = "oml_testmod", name = "OML Test Mod", version = "0.1.0")
class OMLTestMod : OMLModInitializer, OMLContentProvider, OMLCommandProvider, OMLNetworkProvider,
    OMLKeyBindingProvider {

    private var ticks = 0
    private var serverTicks = 0
    private var frameEvents = 0
    private var guiEvents = 0
    private var probeAttempts = 0
    private var componentsReadOnce = false

    /** Probe budget: after 1200 ticks (~60s) a retried probe gives up with a failing check instead of spinning forever. */
    private fun probeGaveUp(attempts: Int, vararg checkIds: String): Boolean {
        if (attempts <= 1200) return false
        for (id in checkIds) E2E.check(id, false, "probe gave up after 1200 attempts (~60s of ticks)")
        return true
    }

    override fun declareNetwork(network: OMLNetworkRegistry) = NetworkProbe.declareNetwork(network)

    override fun declareCommands(commands: OMLCommandRegistry) {
        commands.register("oml_e2e_hello") {
            executes { E2E.hit("command.executed") }
        }
        // Permission-node gate: nothing in vanilla grants this id, so the command is out of reach until the
        // probe grants the node to the player — and out of reach again once it revokes it.
        commands.register("oml_e2e_gated") {
            permissionNode = "oml_testmod:build"
            executes { WorldProbe.gatedRuns++ }
        }
    }

    override fun declareKeyBindings(keyBindings: OMLKeyBindingRegistry) {
        // Not expected anywhere: nothing in the harness presses the key, but a manual press must
        // be recorded — a wrong observation still fails the run.
        keyBindings.register("probe", "key.keyboard.k") { E2E.hit("keybind.pressed") }
    }

    override fun declareContent(registry: ContentRegistry) {
        val block = registry.declareBlock("test_block")
        println("[oml_testmod] declared block: $block")

        // Data-component integration probe: a sword-ish item with native components. The adapter
        // materializes maxDamage into DataComponents.MAX_DAMAGE, the attack values into
        // DataComponents.ATTRIBUTE_MODIFIERS (ADD_VALUE modifiers on ATTACK_DAMAGE / ATTACK_SPEED,
        // MAINHAND group) and the tool fields into DataComponents.TOOL. In game:
        // /give @s oml_testmod:test_sword, then check the durability bar, the +7 Attack Damage /
        // +1.6 Attack Speed tooltip lines, and mining stone vs. dirt speeds.
        val sword = registry.declareItem("test_sword") {
            maxDamage = 592
            attackDamage = 7.0
            attackSpeed = -2.4 // vanilla base 4.0 → 1.6 effective, same presentation as vanilla swords
            miningSpeed = 1.0f
            toolDamagePerBlock = 2
            minesAndDrops("minecraft:stone", 8.0f)
            overrideSpeed("minecraft:dirt", 4.0f)
        }
        println("[oml_testmod] declared item: $sword")
        // Damage-type probe: OML turns this into `data/oml_testmod/damage_type/test_melt.json` inside the
        // injected content pack, so the game's own datapack reload registers it — WorldProbe then reads it
        // back from `OMLRegistries.damageTypes` and hurts a cow with it, where the `LIVING_HURT` handler
        // has to report "oml_testmod.melts" as the damage type.
        registry.declareDamageType(
            id = "test_melt",
            messageId = "oml_testmod.melts",
            effects = "burning",
            exhaustion = 0.2f,
        )
        // Tag probe: OML serves these as `data/minecraft/tags/damage_type/<is_fire|is_freezing>.json` and
        // `data/oml_testmod/tags/damage_type/melting_causes.json`, so the game's own tag loader reads them.
        // Joining `#minecraft:is_fire` is the case that changes what the game does with the damage; the pack's
        // own group carries the other shapes the file format has — a reference to another group, an optional
        // member nothing registers, and a member from a second pack (TechMod adds `techmod:crush` to it, and
        // the game reads one file per tag).
        registry.declareTag("minecraft:damage_type", "minecraft:is_fire") { element("test_melt") }
        registry.declareTag("minecraft:damage_type", "melting_causes") {
            element("test_melt")
            tag("minecraft:is_drowning")
            element("no_such_type", required = false)
        }
        // Replacing a group the game shipped: the file's members become the whole group, which also measures
        // where OML's injected pack ranks against the builtin one. No probe runs the freezing path.
        registry.declareTag("minecraft:damage_type", "minecraft:is_freezing") {
            replace = true
            element("test_melt")
        }
        // Time-track probe: OML serves these as `data/oml_testmod/world_clock/test_clock.json` and
        // `data/oml_testmod/timeline/test_timeline.json` from the injected content pack, so the game's own
        // datapack reload registers both. WorldProbe reads them back out of the game's registries and moves
        // the clock onto the mod's own marker — no dimension runs on this clock, and it counts anyway.
        registry.declareClock("test_clock")
        registry.declareTimeline("test_timeline") {
            clock = "test_clock"
            periodTicks = 24000
            marker("low_tide", 0)
            marker("high_tide", 12000, inCommands = true)
        }
        // Dimension-track probe: these become `data/oml_testmod/dimension_type/test_deep.json` and
        // `data/oml_testmod/dimension/test_dim.json`. The game builds a level for every entry in its
        // `dimension` table when the server starts, so WorldProbe asks the running server for that level and
        // reads back the flags it declared — `has_skylight = false` is the game's own weather test's input.
        registry.declareDimensionType("test_deep") {
            skyLight = false
            ceiling = true
            minY = 0
            height = 128
            logicalHeight = 128
            ambientLight = 0.1f
            skybox = "none"
            clock = "test_clock"
            timelines += "test_timeline"
        }
        registry.declareDimension("test_dim") {
            type = "test_deep"
            layer("minecraft:bedrock", 1)
            biome = "minecraft:plains"
        }
        // A second dimension, on a type that names no clock at all: the pair is what the cross-dimension
        // teleport probe moves an entity between without needing a player to load a vanilla chunk.
        registry.declareDimensionType("test_shallow") {
            minY = 0
            height = 128
            logicalHeight = 128
            skybox = "none"
        }
        registry.declareDimension("test_dim_b") {
            type = "test_shallow"
            layer("minecraft:bedrock", 1)
            biome = "minecraft:plains"
        }
        E2E.hit("content.declared")
    }

    override fun onInitialize(context: ModContext) {
        println("[oml_testmod] initialization complete: id=${context.id} name=${context.name} version=${context.version}")

        // E2E runs (see E2E): the probes below end in a verdict the caller can fail on. The
        // expectations are declared per side because over half of them can only be observed on one.
        E2E.install()
        WorldProbe.register()
        E2E.expect("content.declared", "event.tick")
        if (isServerSide()) {
            E2E.expect(
                "content.item_components", "recipe.loaded", "worldgen.ore_feature",
                "command.executed", "config.generated", "network.unknown_player",
                "event.server_started", "api.server_handle", "api.server_level_lookup",
                "api.registry_reads", "scheduler.server_task",
                // None of these need a player: the server ticks its own levels, the content pack is
                // reloaded during boot, and weather, clocks, game rules and particles belong to the level.
                "event.level_tick", "content.damage_type", "content.custom_tag", "api.world_weather",
                "api.world_clock", "api.clock_markers", "api.clock_rate_and_pause",
                "content.custom_clock", "content.custom_dimension", "api.chunk_lifecycle",
                "api.permission_nodes", "api.block_entity_read", "api.block_data_addressing", "api.dimension_teleport",
                "api.world_spawn_anchor",
                "api.server_game_rules", "api.world_particles_arguments", "api.entity_equipment",
            )
        } else {
            // Deliberately not expected here, because a session that stops at the main menu does not
            // reach them: `event.gui_open` needs a screen swap and `merge.overwrite_static_field` needs
            // `getLaunchedVersion()`, which the title screen never asks for. They are still recorded
            // when they do happen, so a wrong observation fails the run; only "must happen on every
            // client run" is restricted to what really does.
            E2E.expect(
                "merge.shadow_this", "merge.ctor_init",
                "inject.access_widening",
                // run_tick.cancel fires on the 600th runTick call, so expecting it makes READY wait for
                // those frames; the wait is bounded by graceSeconds in wall-clock terms and 600 frames fit
                // inside it even at the ~20fps a software-rendered runner manages.
                "mixin.ctor_head", "mixin.run_tick.head", "mixin.run_tick.capture",
                "mixin.run_tick.return_ordinal", "mixin.run_tick.cancel",
                "mixin.frame_limit.tail", "mixin.frame_limit.head",
                "mixin.modify_constant", "mixin.modify_return_value", "mixin.modify_expression_value",
                "pack.byid.blockstate", "pack.byid.model", "pack.byid.item_def", "pack.byid.texture",
                "pack.byid.lang",
                "pack.dir.blockstates", "pack.dir.models_block", "pack.dir.items",
                "pack.dir.textures_block", "pack.dir.textures_particle", "pack.dir.lang",
                "keybind.registered", "creative.tab_registered",
                "scheduler.client_task",
            )
            // AC-1 includes joining a world, and the run says whether it was asked to: the harness
            // cannot create one, so it only expects the join when it arranged for a save to exist.
            // The payload round trip needs that connection too — singleplayer's client and integrated
            // server are two ends of a real loopback, so the join is what makes the trip observable.
            if (System.getProperty("oml.e2e.quickPlay") != null) {
                // The HUD pass runs only with a world loaded, so it shares the join's condition.
                // The rest of this list is the typed access surface and the interaction hooks: every
                // entry needs a player inside a world, which is exactly what the join provides.
                E2E.expect(
                    "event.world_load",
                    "network.server_received",
                    "network.round_trip",
                    "hud.rendered",
                    "event.server_started",
                    "event.player_joined",
                    "api.server_handle",
                    "api.server_level_lookup",
                    "api.registry_reads",
                    "api.player_handle",
                    "api.player_inventory",
                    "api.give_item",
                    "api.inventory_remove",
                    // The client handle's claim only holds once the client owns a world (see WorldProbe).
                    "api.client_handle",
                    "api.world_block_read",
                    "api.world_block_write",
                    "api.world_is_loaded",
                    "api.player_effects",
                    "api.player_heal",
                    "api.player_sound",
                    "api.world_sound",
                    "api.player_permissions",
                    "api.player_teleport",
                    "api.entity_spawn",
                    "api.entity_motion",
                    "api.entity_removal",
                    "api.block_state",
                    "api.world_particles",
                    "event.player_respawned",
                    "api.player_teleport_replaced_handle",
                    "api.entity_attributes",
                    "api.entity_hurt",
                    "api.entity_fire",
                    // In singleplayer these are the two-level case: the integrated level and the rendered
                    // one each report their own tick, and the mod's damage type is loaded either way.
                    "event.level_tick",
                    "content.damage_type",
                    "content.custom_tag",
                    "api.world_weather",
                    "api.server_game_rules",
                    "api.world_particles_arguments",
                    "api.entity_equipment",
                    "api.world_clock",
                    "api.clock_markers",
                    "api.clock_rate_and_pause",
                    "content.custom_clock",
                    "content.custom_dimension",
                    "api.chunk_lifecycle",
                    "api.permission_nodes",
                    "api.permission_grant",
                    "api.block_entity_read",
                    "api.block_data_addressing",
                    "api.world_spawn_anchor",
                    "api.player_respawn_anchor",
                    "api.dimension_teleport",
                    "api.player_dimension_teleport",
                    "api.clock_client_sync",
                    "api.world_container",
                    "api.player_opened_menu",
                    "api.player_equipment",
                    "scheduler.server_task",
                    "scheduler.client_task",
                    "api.server_broadcast",
                    "api.player_system_message",
                    "event.block_break",
                    "event.entity_attack",
                    "event.entity_interact",
                    "event.living_hurt",
                    "event.living_hurt_rewrite",
                    "event.block_used",
                    "event.item_used",
                )
            }
        }

        // the access-flag rewrite is done by the adapter's DSL rule; here it is observed from the mod side
        MergeProbe.reportAccessWidening()

        Events.CLIENT_TICK.register {
            E2E.hit("event.tick")
            ticks++
            dismissBackupGate()
            NetworkProbe.tickClientSide()
            MergeProbe.reportResourceInjection()
            // Only an E2E run drives the probes: they kill and respawn the player, move them around, rewrite
            // game rules and place blocks. Anywhere else they would be experimenting in someone's play world.
            if (E2E.isEnabled) {
                WorldProbe.tickClient()
                probeClientFeatures()
            }
            if (ticks % 200 == 0) {
                println("[oml_testmod] ClientTickEvent received $ticks times")
            }
        }

        // the dedicated server's main loop is a separate path, only received in server mode
        Events.SERVER_TICK.register {
            E2E.hit("event.tick")
            serverTicks++
            // The dedicated server loads its server resources during boot, which is when vanilla
            // bakes the item holders' component maps — the first point where components are
            // readable. The probe retries until the bake has run.
            if (E2E.isEnabled) {
                probeItemComponents()
                probeServerData()
            }
            NetworkProbe.tickServerSide()
            if (E2E.isEnabled) WorldProbe.tickServer()
            if (serverTicks == 1) {
                println("[oml_testmod] ServerTickEvent active (OML has hooked the server main loop)")
            }
            if (serverTicks % 200 == 0) {
                println("[oml_testmod] ServerTickEvent received $serverTicks times")
            }
        }

        Events.FRAME_RATE_LIMIT.register { event ->
            frameEvents++
            if (frameEvents <= 2) {
                println("[oml_testmod] FrameRateLimitEvent (first 2): limit=${event.limit}")
            }
        }

        Events.GUI_OPEN.register { event ->
            E2E.hit("event.gui_open")
            guiEvents++
            if (guiEvents <= 3) {
                println("[oml_testmod] GuiOpenEvent (first 3): screen=${event.screen?.javaClass?.name} canceled=${event.canceled}")
            }
        }

        Events.WORLD_LOAD.register { event ->
            E2E.hit("event.world_load")
            if (event.world != null) NetworkProbe.worldLoaded()
            println("[oml_testmod] WorldLoadEvent: world=${event.world?.javaClass?.name ?: "null (disconnected)"}")
        }

        Events.CHAT_SENT.register { event ->
            E2E.hit("event.chat_sent")
            println("[oml_testmod] ChatSentEvent: \"${event.message}\"")
        }

        Events.CHAT_RECEIVED.register { event ->
            E2E.hit("event.chat_received")
            // Delivery of the loader's own sends is the verdict behind api.server_broadcast.
            WorldProbe.observeChat(event.message)
            println("[oml_testmod] ChatReceivedEvent: \"${event.message}\"")
        }

        // The HUD draw surface arrives once per frame while a world is loaded (see GameEvents).
        Events.HUD_RENDER.register { _ ->
            E2E.hit("hud.rendered")
        }

        // A dedicated server never boots a client, so the game's own client instance is the side test.
        if (isServerSide()) println("[oml_testmod] running as the dedicated server")
    }


    /**
     * In-game data-component probe: reads [test_sword] back out of `BuiltInRegistries.ITEM` and its
     * native `DataComponentMap` — asserting the freeze-point materialization produced components the
     * running game resolves like vanilla items (max damage, attribute modifiers, tool rules).
     *
     * 26.3 binds an item's component map onto its holder during the server-resources bake, so the probe
     * retries until the bake has run, with vanilla `diamond_sword` as the control: "both unbound" means
     * "bake not run yet", while "vanilla bound but ours missing" is the defect this probe exists to catch.
     */
    private fun probeItemComponents() {
        if (componentsReadOnce) return
        probeAttempts++
        if (probeGaveUp(probeAttempts, "content.item_components")) {
            componentsReadOnce = true
            return
        }
        runCatching {
            val vanilla = readComponents("minecraft", "diamond_sword")
                ?: return // bake not run yet (vanilla control is unbound too) — retry on the next tick
            println(
                "[oml_testmod] component probe [diamond_sword, control]: " +
                    "max_damage=${vanilla.get(DataComponents.MAX_DAMAGE)}",
            )

            val ours = readComponents("oml_testmod", "test_sword")
                ?: error("component bake has run (vanilla items bound) but test_sword is NOT bound")
            println("[oml_testmod] component probe [test_sword]: max_damage=${ours.get(DataComponents.MAX_DAMAGE)}")
            E2E.check("content.item_components", true, "max_damage=${ours.get(DataComponents.MAX_DAMAGE)}")
            // The declared values (592 / two ADD_VALUE modifiers / two tool rules) are asserted, not
            // just printed: "the item exists" and "the components the declaration asked for are there"
            // are different claims, and only the second one catches a bake that silently dropped them.
            E2E.check(
                "content.item_components.max_damage",
                ours.get(DataComponents.MAX_DAMAGE) == 592,
                "expected 592, got ${ours.get(DataComponents.MAX_DAMAGE)}",
            )

            val modifiers = ours.get(DataComponents.ATTRIBUTE_MODIFIERS)
            for (entry in modifiers?.modifiers().orEmpty()) {
                println(
                    "[oml_testmod] component probe [test_sword]: attribute=${
                        entry.attribute().unwrapKey().orElse(null)
                    } " +
                        "amount=${entry.modifier().amount()} slot=${entry.slot()}",
                )
            }

            val tool = ours.get(DataComponents.TOOL)
            println(
                "[oml_testmod] component probe [test_sword]: tool(default_mining_speed=${tool?.defaultMiningSpeed()}, " +
                    "damage_per_block=${tool?.damagePerBlock()}, rules=${tool?.rules()?.size})",
            )
            E2E.check(
                "content.item_components.attributes",
                modifiers?.modifiers()?.size == 2,
                "expected 2 attribute modifiers, got ${modifiers?.modifiers()?.size}",
            )
            E2E.check(
                "content.item_components.tool_rules",
                tool?.rules()?.size == 2,
                "expected 2 tool rules, got ${tool?.rules()?.size}",
            )
        }.onFailure {
            E2E.check("content.item_components", false, it.toString())
            println("[oml_testmod] component probe failed: $it")
            var cause: Throwable? = it.cause
            while (cause != null) {
                println("[oml_testmod] component probe caused by: $cause")
                cause = cause.cause
            }
        }
        // Terminal on success and failure alike: once the bake has run, no retry can change what the
        // map holds, and re-running would only replay the same checks every tick. The unbound-control
        // retry above returns non-locally, so it never reaches this.
        componentsReadOnce = true
    }

    /**
     * Reads the named item's component map, or null while the holder's component bake has not run —
     * vanilla's `Holder.Reference` guard throws while the components are unbound.
     */
    private fun readComponents(namespace: String, path: String): DataComponentMap? {
        val holder = BuiltInRegistries.ITEM.get(Identifier.fromNamespaceAndPath(namespace, path))
            .orElseThrow { IllegalStateException("$namespace:$path missing from BuiltInRegistries.ITEM") }
        return try {
            holder.value().components()
        } catch (e: NullPointerException) {
            null
        }
    }


    private var serverDataProbed = false

    private var clientFeaturesProbed = false
    private var clientProbeAttempts = 0
    private var lastBackupGate: Any? = null

    /**
     * Unattended runs must survive vanilla's modded-worldgen gate: a world whose generation was
     * touched by a datapack (our ore merge makes the world's generation lifecycle non-stable)
     * loads behind a [BackupConfirmScreen] that only a human could answer, so the world join and
     * everything downstream of it never happens. The harness proceeds without a backup — the
     * world was just generated by a server boot and holds nothing to back up.
     */
    private fun dismissBackupGate() {
        runCatching {
            val screen = OMLGame.client?.currentScreen ?: return
            if (screen === lastBackupGate) return
            if (screen !is BackupConfirmScreen) return
            lastBackupGate = screen
            println("[oml_testmod] world gate screen ${screen.javaClass.name} — proceeding without backup")
            // The proceed callback is a private field holding a package-private listener class: driving
            // the gate means reaching both, which is the one reflection this mod keeps on purpose.
            val field = BackupConfirmScreen::class.java.getDeclaredField("onProceed").apply { isAccessible = true }
            val listener = field.get(screen)
            val proceed = listener.javaClass.methods.first { it.name == "proceed" && it.parameterCount == 2 }
                .apply { isAccessible = true }
            proceed.invoke(listener, false, false)
        }.onFailure {
            println("[oml_testmod] backup-gate dismissal failed: $it")
        }
    }


    private fun probeClientFeatures() {
        if (clientFeaturesProbed || ticks <= 5) return
        clientProbeAttempts++
        if (probeGaveUp(clientProbeAttempts, "keybind.registered", "creative.tab_registered")) return
        runCatching {
            val minecraft = OMLGame.client ?: error("no client instance in this process")

            // keybind.registered — the declared binding sits on the live Options.keyMappings array
            val mappings = minecraft.options.keyMappings
            val bound = mappings.any { it.name == "key.oml_testmod.probe" }
            E2E.check(
                "keybind.registered",
                bound,
                "key.oml_testmod.probe is not on Options.keyMappings (size ${mappings.size})",
            )

            // creative.tab_registered — the oml:main tab exists in the creative-mode-tab registry
            val tabPresent = BuiltInRegistries.CREATIVE_MODE_TAB
                .get(Identifier.fromNamespaceAndPath("oml", "main")).isPresent
            E2E.check(
                "creative.tab_registered",
                tabPresent,
                "oml:main missing from BuiltInRegistries.CREATIVE_MODE_TAB",
            )
            clientFeaturesProbed = true
        }.onFailure {
            if (clientProbeAttempts % 200 == 0) {
                println("[oml_testmod] client feature probe retry: $it")
            }
        }
    }


    private fun probeServerData() {
        if (serverDataProbed) return
        runCatching {
            val server = OMLGame.server ?: return // server not up yet; retry on the next tick
            serverDataProbed = true

            // recipe.loaded — the smelting recipes the techmod declares, read back from the
            // server's own recipe manager
            fun recipeLoaded(id: String): Boolean = server.recipeManager.byKey(
                ResourceKey.create(
                    Registries.RECIPE, Identifier.fromNamespaceAndPath("techmod", id),
                ),
            ).isPresent

            val recipesOk = recipeLoaded("techmod_raw_copper") && recipeLoaded("techmod_raw_tin")
            E2E.check("recipe.loaded", recipesOk, "techmod smelting recipes missing from the recipe manager")

            // worldgen.ore_feature — the placed features the ore declarations materialized.
            val placedFeatures = server.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE)

            fun oreFeatureLoaded(id: String): Boolean = placedFeatures.containsKey(
                ResourceKey.create(
                    Registries.PLACED_FEATURE, Identifier.fromNamespaceAndPath("techmod", id),
                ),
            )

            val featuresOk = oreFeatureLoaded("ore_copper_ore") && oreFeatureLoaded("ore_tin_ore")
            E2E.check(
                "worldgen.ore_feature",
                featuresOk,
                "techmod placed features missing from the worldgen registries",
            )

            // command.executed — dispatch our own probe command through the live dispatcher; the
            // hook-driven registration is lazy on the first command, and this IS that first one
            server.commands.performPrefixedCommand(server.createCommandSourceStack(), "oml_e2e_hello")

            // modlist.registered — the loader's built-in /oml command joined the same dispatcher;
            // dispatching it proves the listing runs over the real mod state.
            val omlNode = server.commands.dispatcher.root.getChild("oml")
            E2E.check("modlist.registered", omlNode != null, "the built-in /oml command is not registered")
            server.commands.performPrefixedCommand(server.createCommandSourceStack(), "oml mods")

            // config.generated — the techmod config file was generated with its declared defaults
            val configFile = java.io.File("config/techmod.toml")
            val configOk = configFile.isFile && "ping_prefix" in configFile.readText()
            E2E.check("config.generated", configOk, "config/techmod.toml missing or incomplete")
        }.onFailure {
            serverDataProbed = true
            E2E.check("recipe.loaded", false, "probe failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

}
