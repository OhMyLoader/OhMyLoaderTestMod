package org.ohmyloader.testmod

import org.ohmyloader.api.Mod
import org.ohmyloader.api.ModContext
import org.ohmyloader.api.OMLModInitializer
import org.ohmyloader.api.client.OMLKeyBindingProvider
import org.ohmyloader.api.client.OMLKeyBindingRegistry
import org.ohmyloader.api.command.OMLCommandProvider
import org.ohmyloader.api.command.OMLCommandRegistry
import org.ohmyloader.api.content.ContentRegistry
import org.ohmyloader.api.content.OMLContentProvider
import org.ohmyloader.api.event.Events
import org.ohmyloader.api.network.OMLNetworkProvider
import org.ohmyloader.api.network.OMLNetworkRegistry

/**
 * The first end-to-end test mod written from a mod author's perspective using OML.
 * Explicit registration (no annotation scanning): the listeners are lambdas, so registration is
 * traceable.
 */
@Mod(id = "oml_testmod", name = "OML Test Mod", version = "0.1.0")
class OMLTestMod : OMLModInitializer, OMLContentProvider, OMLCommandProvider, OMLNetworkProvider, OMLKeyBindingProvider {

    private var ticks = 0
    private var serverTicks = 0
    private var frameEvents = 0
    private var guiEvents = 0
    private var probeAttempts = 0
    private var componentsReadOnce = false

    override fun declareNetwork(network: OMLNetworkRegistry) = NetworkProbe.declareNetwork(network)

    override fun declareCommands(commands: OMLCommandRegistry) {
        commands.register("oml_e2e_hello") {
            executes { E2E.hit("command.executed") }
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
        E2E.hit("content.declared")
    }

    override fun onInitialize(context: ModContext) {
        println("[oml_testmod] initialization complete: id=${context.id} name=${context.name} version=${context.version}")

        // E2E runs (see E2E): the probes below end in a verdict the caller can fail on. The
        // expectations are declared per side because over half of them can only be observed on one.
        E2E.install()
        E2E.expect("content.declared", "event.tick")
        if (isServerSide()) {
            E2E.expect(
                "content.item_components", "recipe.loaded", "worldgen.ore_feature",
                "command.executed", "config.generated", "network.unknown_player",
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
                "mixin.ctor_head", "mixin.run_tick.head", "mixin.run_tick.capture",
                "mixin.run_tick.return_ordinal",
                "mixin.frame_limit.tail", "mixin.frame_limit.head",
                "mixin.modify_constant", "mixin.modify_return_value", "mixin.modify_expression_value",
                "pack.byid.blockstate", "pack.byid.model", "pack.byid.item_def", "pack.byid.texture",
                "pack.byid.lang",
                "pack.dir.blockstates", "pack.dir.models_block", "pack.dir.items",
                "pack.dir.textures_block", "pack.dir.textures_particle", "pack.dir.lang",
                "keybind.registered", "creative.tab_registered",
            )
            // AC-1 includes joining a world, and the run says whether it was asked to: the harness
            // cannot create one, so it only expects the join when it arranged for a save to exist.
            // The payload round trip needs that connection too — singleplayer's client and integrated
            // server are two ends of a real loopback, so the join is what makes the trip observable.
            if (System.getProperty("oml.e2e.quickPlay") != null) {
                // The HUD pass runs only with a world loaded, so it shares the join's condition.
                E2E.expect("event.world_load", "network.server_received", "network.round_trip", "hud.rendered")
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
            probeClientFeatures()
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
            probeItemComponents()
            probeServerData()
            NetworkProbe.tickServerSide()
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
                println("[oml_testmod] GuiOpenEvent (first 3): screen=${event.screen?.platform?.javaClass?.name} canceled=${event.canceled}")
            }
        }

        Events.WORLD_LOAD.register { event ->
            E2E.hit("event.world_load")
            if (event.world != null) NetworkProbe.worldLoaded()
            println("[oml_testmod] WorldLoadEvent: world=${event.world?.platform?.javaClass?.name ?: "null (disconnected)"}")
        }

        Events.CHAT_SENT.register { event ->
            E2E.hit("event.chat_sent")
            println("[oml_testmod] ChatSentEvent: \"${event.message}\"")
        }

        Events.CHAT_RECEIVED.register { event ->
            E2E.hit("event.chat_received")
            println("[oml_testmod] ChatReceivedEvent: \"${event.message}\"")
        }

        // The HUD draw surface arrives once per frame while a world is loaded (see GameEvents).
        Events.HUD_RENDER.register { _ ->
            E2E.hit("hud.rendered")
        }

        // The side is asked through OMLCore (reflective: this mod compiles against oml-api only).
        if (isServerSide()) println("[oml_testmod] running as the dedicated server")
    }

    private fun isServerSide(): Boolean =
        runCatching {
            Class.forName("org.ohmyloader.core.OMLCore").getMethod("isServerSide").invoke(null) as Boolean
        }.getOrDefault(false)

    /**
     * In-game data-component probe: reads [test_sword] back out of `BuiltInRegistries.ITEM` and
     * reads the item's native `DataComponentMap` — proving the freeze-point materialization
     * produced components the running game resolves identically to vanilla items.
     *
     * 26.3 binds an item's component map onto its registry holder during the server-resources
     * bake (`ReloadableServerResources` → `DataComponentInitializers` → `bindComponents`), so the
     * probe **retries** until the bake has run, and reads a vanilla item (`diamond_sword`) as the
     * control: "both unbound" means "bake not run yet", while "vanilla bound but ours missing"
     * would mean the bake skipped modded items — a real defect this probe must catch. All
     * reflection goes through the game class loader; failures print instead of killing mod init.
     */
    private fun probeItemComponents() {
        if (componentsReadOnce) return
        probeAttempts++
        if (probeAttempts > 1200) {
            E2E.check("content.item_components", false, "probe gave up after 1200 attempts (~60s of ticks)")
            return
        }
        runCatching {
            // A mod compiles against oml-api only, so OMLCore is reached reflectively by name —
            // same pattern MergeProbe uses (org.ohmyloader.* is parent-first, one runtime identity).
            val loader = Class.forName("org.ohmyloader.core.OMLCore")
                .getMethod("gameClassLoader").invoke(null) as ClassLoader
            val vanilla =
                readComponents(loader, "minecraft", "diamond_sword")
                    ?: return // bake not run yet (vanilla control is unbound too) — retry on the next tick
            componentsReadOnce = true
            println("[oml_testmod] component probe [diamond_sword, control]: max_damage=${vanilla.maxDamage}")

            val ours = readComponents(loader, "oml_testmod", "test_sword")
                ?: error("component bake has run (vanilla items bound) but test_sword is NOT bound")
            println("[oml_testmod] component probe [test_sword]: max_damage=${ours.maxDamage}")
            E2E.check("content.item_components", true, "max_damage=${ours.maxDamage}")
            // The declared values (592 / two ADD_VALUE modifiers / two tool rules) are asserted, not
            // just printed: "the item exists" and "the components the declaration asked for are there"
            // are different claims, and only the second one catches a bake that silently dropped them.
            E2E.check(
                "content.item_components.max_damage",
                (ours.maxDamage as? Int) == 592,
                "expected 592, got ${ours.maxDamage}",
            )

            val dataComponents = loader.loadClass("net.minecraft.core.component.DataComponents")
            val modifiers = ours.getComponent(dataComponents.getField("ATTRIBUTE_MODIFIERS").get(null))
            val entries = modifiers.javaClass.getMethod("modifiers").invoke(modifiers) as? List<*>
            for (entry in entries.orEmpty()) {
                val attribute = entry!!.javaClass.getMethod("attribute").invoke(entry)
                val modifier = entry.javaClass.getMethod("modifier").invoke(entry)
                val slot = entry.javaClass.getMethod("slot").invoke(entry)
                val attrId = (attribute.javaClass.methods
                    .first { it.name == "unwrapKey" }.invoke(attribute) as java.util.Optional<*>)
                    .orElse(null)?.toString()
                val amount = modifier.javaClass.getMethod("amount").invoke(modifier)
                println("[oml_testmod] component probe [test_sword]: attribute=$attrId amount=$amount slot=$slot")
            }

            val tool = ours.getComponent(dataComponents.getField("TOOL").get(null))
            val rules = tool.javaClass.getMethod("rules").invoke(tool) as? List<*>
            val defaultSpeed = tool.javaClass.getMethod("defaultMiningSpeed").invoke(tool)
            val damagePerBlock = tool.javaClass.getMethod("damagePerBlock").invoke(tool)
            println(
                "[oml_testmod] component probe [test_sword]: tool(default_mining_speed=$defaultSpeed, " +
                    "damage_per_block=$damagePerBlock, rules=${rules?.size})",
            )
            E2E.check(
                "content.item_components.attributes",
                entries?.size == 2,
                "expected 2 attribute modifiers, got ${entries?.size}",
            )
            E2E.check(
                "content.item_components.tool_rules",
                rules?.size == 2,
                "expected 2 tool rules, got ${rules?.size}",
            )
        }.onFailure {
            E2E.check("content.item_components", false, it.toString())
            println("[oml_testmod] component probe failed: $it")
            // The reflective path wraps the real failure; the full chain is what diagnoses it.
            var cause: Throwable? = it.cause
            while (cause != null) {
                println("[oml_testmod] component probe caused by: $cause")
                cause = cause.cause
            }
        }
    }

    /** The read-back of one item's component map: max damage (null = component absent), the map, and its typed getter. */
    private class ProbedComponents(
        val maxDamage: Any?,
        val map: Any,
        val getComponent: (Any) -> Any,
    )

    /**
     * Reads the named item's component map, or null while the holder's component bake has not run
     * ("Components not bound yet" — vanilla's Holder.Reference guard).
     */
    private fun readComponents(loader: ClassLoader, namespace: String, path: String): ProbedComponents? {
        val identifier = loader.loadClass("net.minecraft.resources.Identifier")
            .getMethod("fromNamespaceAndPath", String::class.java, String::class.java)
            .invoke(null, namespace, path)
        val itemRegistry = loader.loadClass("net.minecraft.core.registries.BuiltInRegistries")
            .getField("ITEM").get(null)
        val getById = itemRegistry.javaClass.methods.first {
            it.name == "get" && it.parameterCount == 1 && it.parameterTypes[0].simpleName == "Identifier"
        }
        val holder = (getById.invoke(itemRegistry, identifier) as java.util.Optional<*>)
            .orElseThrow { IllegalStateException("$namespace:$path missing from BuiltInRegistries.ITEM") }
        val item = holder.javaClass.getMethod("value").invoke(holder)
        val map = try {
            item.javaClass.getMethod("components").invoke(item)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            if (e.cause is NullPointerException) return null // "Components not bound yet"
            throw e
        }
        val getComponent = map.javaClass.methods.first {
            it.name == "get" && it.parameterCount == 1 && it.parameterTypes[0].simpleName == "DataComponentType"
        }
        // The runtime map class (DataComponentMap$Builder$SimpleMap) is package-private; without
        // this the Method.invoke itself throws IllegalAccessException even for a public method.
        getComponent.isAccessible = true
        val dataComponents = loader.loadClass("net.minecraft.core.component.DataComponents")
        val maxDamage = getComponent.invoke(map, dataComponents.getField("MAX_DAMAGE").get(null))
        return ProbedComponents(maxDamage, map) { type -> getComponent.invoke(map, type)!! }
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
     * world was just generated by a server boot and holds nothing to back up. Matched reflectively
     * by name: this mod compiles against oml-api only.
     */
    private fun dismissBackupGate() {
        runCatching {
            val loader = Class.forName("org.ohmyloader.core.OMLCore")
                .getMethod("gameClassLoader").invoke(null) as ClassLoader
            val minecraft = loader.loadClass("net.minecraft.client.Minecraft")
                .getMethod("getInstance").invoke(null) ?: return
            val gui = minecraft.javaClass.getField("gui").get(minecraft)
            val screen = gui.javaClass.getMethod("screen").invoke(gui) ?: return
            if (screen === lastBackupGate) return
            if (screen.javaClass.name != "net.minecraft.client.gui.screens.BackupConfirmScreen") return
            lastBackupGate = screen
            println("[oml_testmod] world gate screen ${screen.javaClass.name} — proceeding without backup")
            val field = screen.javaClass.getDeclaredField("onProceed")
            field.isAccessible = true
            val listener = field.get(screen)
            val proceed = listener.javaClass.methods.first { it.name == "proceed" && it.parameterCount == 2 }
            // The listener is a package-private lambda class; without this even the public
            // Method.invoke throws IllegalAccessException (same as the component-map getter).
            proceed.isAccessible = true
            proceed.invoke(listener, false, false)
        }.onFailure {
            println("[oml_testmod] backup-gate dismissal failed: $it")
        }
    }

    /**
     * Client-side probes for the key-binding and creative-tab features, retried from tick 6: the
     * bindings apply lazily on the first client tick, and the tab materializes at the registry
     * freeze point. Both are main-menu-visible, so they are checked on every client run. All
     * reflection goes through the game class loader; failures print instead of killing mod init
     * (same pattern as [probeItemComponents]).
     */
    private fun probeClientFeatures() {
        if (clientFeaturesProbed || ticks <= 5) return
        clientProbeAttempts++
        if (clientProbeAttempts > 1200) {
            E2E.check("keybind.registered", false, "probe gave up after 1200 attempts (~60s of ticks)")
            E2E.check("creative.tab_registered", false, "probe gave up after 1200 attempts (~60s of ticks)")
            return
        }
        runCatching {
            val loader = Class.forName("org.ohmyloader.core.OMLCore")
                .getMethod("gameClassLoader").invoke(null) as ClassLoader

            // keybind.registered — the declared binding sits on the live Options.keyMappings array
            val minecraft = loader.loadClass("net.minecraft.client.Minecraft")
                .getMethod("getInstance").invoke(null)
                ?: error("Minecraft.getInstance() is null")
            val options = minecraft.javaClass.getField("options").get(minecraft)
            val mappings = options.javaClass.getField("keyMappings").get(options) as Array<*>
            val bound = mappings.any {
                it!!.javaClass.getMethod("getName").invoke(it) == "key.oml_testmod.probe"
            }
            E2E.check(
                "keybind.registered",
                bound,
                "key.oml_testmod.probe is not on Options.keyMappings (size ${mappings.size})",
            )

            // creative.tab_registered — the oml:main tab exists in the creative-mode-tab registry
            val identifier = loader.loadClass("net.minecraft.resources.Identifier")
                .getMethod("fromNamespaceAndPath", String::class.java, String::class.java)
                .invoke(null, "oml", "main")
            val tabRegistry = loader.loadClass("net.minecraft.core.registries.BuiltInRegistries")
                .getField("CREATIVE_MODE_TAB").get(null)
            val getById = tabRegistry.javaClass.methods.first {
                it.name == "get" && it.parameterCount == 1 && it.parameterTypes[0].simpleName == "Identifier"
            }
            val tabPresent = (getById.invoke(tabRegistry, identifier) as java.util.Optional<*>).isPresent
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

    /**
     * The datapack-content probes (M2): a declared recipe present in the server's recipe manager,
     * and the declared ore's placed feature present in the worldgen registries. This is the pair
     * of checks whose absence let four datapack-side defects (recipe JSON shape, missing listing
     * enumeration, the reload wipe, the server repository gap) pass every gate — blocks and
     * materialization were verified, the datapack end was not. Runs on the server ticks (the
     * registries are populated by then) through the game class loader; `serverInstance` comes
     * from the constructor hook via OMLCore.
     */
    private fun probeServerData() {
        if (serverDataProbed) return
        runCatching {
            val core = Class.forName("org.ohmyloader.core.OMLCore")
            val server = core.getField("serverInstance").get(null)
                ?: return // server not up yet; retry on the next tick
            serverDataProbed = true
            val loader = core.getMethod("gameClassLoader").invoke(null) as ClassLoader

            val resourceKey = loader.loadClass("net.minecraft.resources.ResourceKey")
            val identifier = loader.loadClass("net.minecraft.resources.Identifier")
            val identifierOf = identifier.getMethod("fromNamespaceAndPath", String::class.java, String::class.java)
            val keyCreate = resourceKey.getMethod("create", resourceKey, identifier)
            val registries = loader.loadClass("net.minecraft.core.registries.Registries")

            // recipe.loaded — the smelting recipes the techmod declares, read back from the
            // server's own recipe manager
            val recipeManager = server.javaClass.getMethod("getRecipeManager").invoke(server)
            val recipeRegistryKey = registries.getField("RECIPE").get(null)
            val byKey = recipeManager.javaClass.methods.firstOrNull {
                it.name == "byKey" && it.parameterTypes[0] == resourceKey
            } ?: error("byKey(ResourceKey) not found on ${recipeManager.javaClass.name}")

            fun recipeLoaded(id: String): Boolean {
                val key = keyCreate.invoke(null, recipeRegistryKey, identifierOf.invoke(null, "techmod", id))
                return (byKey.invoke(recipeManager, key) as java.util.Optional<*>).isPresent
            }

            val recipesOk = recipeLoaded("techmod_raw_copper") && recipeLoaded("techmod_raw_tin")
            E2E.check("recipe.loaded", recipesOk, "techmod smelting recipes missing from the recipe manager")

            // worldgen.ore_feature — the placed features the ore declarations materialized.
            // 26.3 renamed RegistryAccess.registryOrThrow to lookupOrThrow, with several
            // same-erasure overloads; the registry-returning one is picked by return type.
            val registryAccess = server.javaClass.getMethod("registryAccess").invoke(server)
            val registryLookup = registryAccess.javaClass.methods.firstOrNull {
                it.name == "lookupOrThrow" && it.returnType.simpleName == "Registry"
            } ?: error("lookupOrThrow(Registry) not found on ${registryAccess.javaClass.name}")
            val placedFeatureKey = registries.getField("PLACED_FEATURE").get(null)
            val featureRegistry = registryLookup.invoke(registryAccess, placedFeatureKey)
            val containsKey = featureRegistry.javaClass.methods.firstOrNull {
                it.name == "containsKey" && it.parameterTypes[0] == resourceKey
            } ?: error("containsKey(ResourceKey) not found on ${featureRegistry.javaClass.name}")

            fun oreFeatureLoaded(id: String): Boolean {
                val key = keyCreate.invoke(null, placedFeatureKey, identifierOf.invoke(null, "techmod", id))
                return containsKey.invoke(featureRegistry, key) as Boolean
            }

            val featuresOk = oreFeatureLoaded("ore_copper_ore") && oreFeatureLoaded("ore_tin_ore")
            E2E.check(
                "worldgen.ore_feature",
                featuresOk,
                "techmod placed features missing from the worldgen registries",
            )

            // command.executed — dispatch our own probe command through the live dispatcher; the
            // hook-driven registration is lazy on the first command, and this IS that first one
            val gameCommands = server.javaClass.getMethod("getCommands").invoke(server)
            val commandSource = server.javaClass.getMethod("createCommandSourceStack").invoke(server)
            val perform = gameCommands.javaClass.methods.first {
                it.name == "performPrefixedCommand" && it.parameterCount == 2
            }
            perform.invoke(gameCommands, commandSource, "oml_e2e_hello")

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
