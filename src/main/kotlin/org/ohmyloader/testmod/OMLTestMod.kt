package org.ohmyloader.testmod

import org.ohmyloader.api.Mod
import org.ohmyloader.api.ModContext
import org.ohmyloader.api.OMLModInitializer
import org.ohmyloader.api.content.ContentRegistry
import org.ohmyloader.api.content.OMLContentProvider
import org.ohmyloader.api.event.Events

/**
 * The first end-to-end test mod written from a mod author's perspective using OML.
 * Explicit registration (no annotation scanning): the listeners are lambdas, so registration is
 * traceable.
 */
@Mod(id = "oml_testmod", name = "OML Test Mod", version = "0.1.0")
class OMLTestMod : OMLModInitializer, OMLContentProvider {

    private var ticks = 0
    private var serverTicks = 0
    private var frameEvents = 0
    private var guiEvents = 0
    private var probeAttempts = 0
    private var componentsReadOnce = false

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
            E2E.expect("content.item_components")
        } else {
            // Deliberately not expected here, because a session that stops at the main menu does not
            // reach them: `event.gui_open` needs a screen swap (observed when a world is joined) and
            // `merge.overwrite_static_field` needs `getLaunchedVersion()`, which the title screen never
            // asks for. They are still recorded when they do happen, so a wrong observation fails the
            // run; only "must happen on every client run" is restricted to what really does.
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
            )
        }

        // the access-flag rewrite is done by the adapter's DSL rule; here it is observed from the mod side
        MergeProbe.reportAccessWidening()

        Events.CLIENT_TICK.register {
            E2E.hit("event.tick")
            ticks++
            MergeProbe.reportResourceInjection()
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
                    "damage_per_block=$damagePerBlock, rules=${rules?.size})"
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
}
