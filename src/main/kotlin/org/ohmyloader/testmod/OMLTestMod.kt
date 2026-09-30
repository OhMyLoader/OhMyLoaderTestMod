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
    }

    override fun onInitialize(context: ModContext) {
        println("[oml_testmod] initialization complete: id=${context.id} name=${context.name} version=${context.version}")

        // the access-flag rewrite is done by the adapter's DSL rule; here it is observed from the mod side
        MergeProbe.reportAccessWidening()

        Events.CLIENT_TICK.register {
            ticks++
            MergeProbe.reportResourceInjection()
            if (ticks % 200 == 0) {
                println("[oml_testmod] ClientTickEvent received $ticks times")
            }
        }

        // the dedicated server's main loop is a separate path, only received in server mode
        Events.SERVER_TICK.register {
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
            guiEvents++
            if (guiEvents <= 3) {
                println("[oml_testmod] GuiOpenEvent (first 3): screen=${event.screen?.platform?.javaClass?.name} canceled=${event.canceled}")
            }
        }

        Events.WORLD_LOAD.register { event ->
            println("[oml_testmod] WorldLoadEvent: world=${event.world?.platform?.javaClass?.name ?: "null (disconnected)"}")
        }

        Events.CHAT_SENT.register { event ->
            println("[oml_testmod] ChatSentEvent: \"${event.message}\"")
        }

        Events.CHAT_RECEIVED.register { event ->
            println("[oml_testmod] ChatReceivedEvent: \"${event.message}\"")
        }
    }

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
        if (probeAttempts > 1200) return // ~60s of server ticks; give up quietly rather than spin forever
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
        }.onFailure {
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
