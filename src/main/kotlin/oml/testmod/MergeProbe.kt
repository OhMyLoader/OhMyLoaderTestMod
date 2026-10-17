package oml.testmod

import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager
import org.ohmyloader.api26_3.OMLGame
import java.io.IOException
import java.lang.invoke.MethodHandles
import java.lang.reflect.Modifier
import java.net.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Observation point for the class-merge demo, and the test mod's home for "measured on the real game"
 * evidence (the resource-pack probe below answers what the live `ResourceManager` actually serves).
 *
 * An `@Overwrite`d method body runs **inside the target class** (`net.minecraft.client.Minecraft`) and
 * calls here to report that the merged-in code really executed — the merge is transparent to callers,
 * so only a report emitted from inside gives E2E a definite acceptance point. [hit]'s argument is the
 * mixin's `@Unique` static field, whose initializer is spliced into the target class's `<clinit>`:
 * it **must be 7**; a 0 means the `<clinit>` was not spliced.
 */
object MergeProbe {

    private val logged = AtomicBoolean(false)

    /**
     * Observation point: what the **instance** handler (which moved into the target class via the
     * class merge) reads as `this.<@Shadow field>`.
     *
     * The passed-in value is what `this.launchedVersion` evaluates to inside the handler body —
     * before the merge it is the mixin's `@Shadow` field (always null); after the merge it points at
     * the **target class's** field (the real value). So this single line is itself evidence that the
     * real Mixin `this` semantics are in effect.
     */
    @JvmStatic
    fun hitShadow(launchedVersion: String?) {
        // A null/blank value would mean the handler read the mixin's own @Shadow field instead of the
        // target's, which is the failure this observation exists for.
        E2E.check("merge.shadow_this", !launchedVersion.isNullOrBlank(), "this.launchedVersion=$launchedVersion")
        if (shadowLogged.compareAndSet(false, true)) {
            println(
                "[oml_testmod] class merge: instance handler this semantics in effect -- " +
                    "this.launchedVersion = ${launchedVersion ?: "null"}",
            )
        }
    }

    private val shadowLogged = AtomicBoolean(false)

    private val constantLogged = AtomicBoolean(false)

    /**
     * Observation point: a **value-modifying** instance handler (`@ModifyConstant`) really ran in
     * the live game.
     *
     * The handler deliberately **returns the original value** (an identity transform), so this demo
     * changes no game behavior — all it proves is that "the instance value-modifying path works".
     * [value] is the constant it actually received, and [where] names the rule that hit — several
     * demos share this probe, so the two are reported together to tell them apart.
     */
    @JvmStatic
    fun modifiedConstant(where: String, value: Int) {
        E2E.hit("mixin.modify_constant")
        if (constantLogged.compareAndSet(false, true)) {
            println(
                "[oml_testmod] class merge: instance @ModifyConstant in effect -- $where, received constant = $value" +
                    " (identity return; game behavior unchanged)",
            )
        }
    }

    private val ctorInitLogged = AtomicBoolean(false)

    /**
     * Observation point: **constructor merging** folded the mixin's instance initializer into the
     * target class's constructor.
     *
     * [value] is the current value of the mixin's `@Unique` instance field — its initial value is
     * written in the mixin's `<init>`, so only splicing that block into the target class's `<init>`
     * keeps it from being the JVM default 0.
     */
    @JvmStatic
    fun hitCtorInit(value: Int) {
        E2E.check("merge.ctor_init", value == 1234, "mixin field initializer=$value (0 = <init> not spliced)")
        if (ctorInitLogged.compareAndSet(false, true)) {
            println(
                "[oml_testmod] class merge: constructor merge in effect -- instance field initializer in the mixin constructor = $value" +
                    " (would be 0 without the merge)",
            )
        }
    }

    private val accessLogged = AtomicBoolean(false)

    /**
     * Observation point: the **access-flag rewrite** genuinely landed on the game class.
     *
     * `Minecraft.proxy` was originally `private final java.net.Proxy`; the DSL widens it to `public`
     * (and drops `final`). Here a read handle is obtained via `MethodHandles.publicLookup()` to prove
     * that "any package can access it as an ordinary public member" — `publicLookup` only recognizes
     * public members, throwing `IllegalAccessException` otherwise. The probe only reads flag bits and
     * the handle, never the value, so it changes no game behavior.
     */
    @JvmStatic
    fun reportAccessWidening() {
        if (!accessLogged.compareAndSet(false, true)) return
        // The rewrite itself is a **client-side** rule — `MinecraftHookTransformer` is installed in
        // client mode only — while 26.3's single jar makes `Minecraft` exist on the dedicated server as
        // well. Asking there answers "still private final", which is true and is no evidence about the
        // rewrite, so the probe says which side it does not cover rather than printing a line that
        // reads like a broken acceptance point.
        if (OMLGame.client == null) {
            println("[oml_testmod] access rewrite: skipped on the dedicated server (the rule is a client-side hook)")
            return
        }
        // This mod is loaded as a mod (the main loader defines it and the game classes alike), so its own
        // `Minecraft` reference is the transformed class the game runs — which is what makes reading the
        // field's modifiers evidence rather than a look at a second copy.
        val field = runCatching { Minecraft::class.java.getDeclaredField("proxy") }.getOrElse {
            E2E.check("inject.access_widening", false, "field unreadable: $it")
            return
        }
        val handle = runCatching {
            MethodHandles.publicLookup().findGetter(field.declaringClass, "proxy", Proxy::class.java)
        }
        println(
            "[oml_testmod] access rewrite: Minecraft.proxy is now ${Modifier.toString(field.modifiers)}" +
                " (was private final); publicLookup read handle = " +
                if (handle.isSuccess) "usable" else "unusable (${handle.exceptionOrNull()?.javaClass?.simpleName})",
        )
        E2E.check(
            "inject.access_widening",
            Modifier.isPublic(field.modifiers) && handle.isSuccess,
            "modifiers=${Modifier.toString(field.modifiers)} publicLookup=${if (handle.isSuccess) "usable" else "unusable"}",
        )
    }

    private val returnValueLogged = AtomicBoolean(false)

    /**
     * Observation point: a **return-value-modifying** instance handler ran.
     *
     * The passed-in value is the target method's return value (identity return; game behavior
     * unchanged); [where] names the rule that hit — several demos share this probe.
     */
    @JvmStatic
    fun modifiedReturnValue(where: String, value: Int) {
        E2E.hit("mixin.modify_return_value")
        if (returnValueLogged.compareAndSet(false, true)) {
            println(
                "[oml_testmod] class merge: @ModifyReturnValue in effect -- $where, received return value = $value" +
                    " (identity return; game behavior unchanged)",
            )
        }
    }

    private val expressionValueLogged = AtomicBoolean(false)

    /**
     * Observation point: an **expression-output-modifying** instance handler ran.
     *
     * The passed-in value is what that expression produced (here an int constant in the target
     * method). The difference from {@link #modifiedReturnValue} is the anchor: one is "the method's
     * return value", the other is "the result of a single instruction".
     */
    @JvmStatic
    fun modifiedExpressionValue(where: String, value: Int) {
        E2E.hit("mixin.modify_expression_value")
        if (expressionValueLogged.compareAndSet(false, true)) {
            println(
                "[oml_testmod] class merge: @ModifyExpressionValue in effect -- $where, received produced value = $value" +
                    " (identity return; game behavior unchanged)",
            )
        }
    }

    /**
     * Observation point: the **mod resource pack** really serves assets — measured through the game's
     * own `ResourceManager`, not the adapter's bookkeeping. Called every client tick until the control
     * path resolves: the initial resource reload is asynchronous, so a vanilla file that must exist
     * (`minecraft:blockstates/bedrock.json`) is the only way to tell "the injected pack is broken"
     * apart from "the reload has not finished yet".
     *
     * Both access paths the game uses are measured — `getResource` by id and `listResources` by
     * directory (see the inline notes below) — and every hit reports `sourcePackId()` and the byte
     * count, so the answer is not just "present" but "these bytes were served by pack X".
     */
    @JvmStatic
    fun reportResourceInjection() {
        if (resourceLogged.get()) return
        val lines = runCatching { probeResources() }.getOrElse { failure ->
            if (resourceLogged.compareAndSet(false, true)) {
                println("[oml_testmod] resource pack: probe failed -- $failure")
            }
            return
        } ?: return // null == the initial (asynchronous) reload has not completed yet; ask again next tick
        if (resourceLogged.compareAndSet(false, true)) lines.forEach(::println)
    }

    private val resourceLogged = AtomicBoolean(false)

    /** One line per measurement, or null while the initial reload is still running. */
    private fun probeResources(): List<String>? {
        val manager = OMLGame.client?.resourceManager ?: return null

        fun resourceOf(id: String) = manager.getResource(Identifier.parse(id)).orElse(null)

        if (resourceOf("minecraft:blockstates/bedrock.json") == null) return null

        val out = mutableListOf<String>()
        // Deliberately not only block assets — sounds, lang, atlas sprites and overrides are the half a
        // pack tends to get wrong. The synthesized ids exist in no file at all and the texture is a real
        // jar file, so a served hit must report `oml_mod_resources` as its source pack.
        out += "[oml_testmod] resource pack getResource (by id: synthesized block assets, jar assets):"
        for ([check, id] in listOf(
            "pack.byid.blockstate" to "oml_testmod:blockstates/test_block.json",
            "pack.byid.model" to "oml_testmod:models/block/test_block.json",
            "pack.byid.item_def" to "oml_testmod:items/test_block.json",
            "pack.byid.texture" to "oml_testmod:textures/block/test_block.png",
            "pack.byid.lang" to "oml_testmod:lang/en_us.json",
        )) {
            val served = resourceOf(id)?.let { resource ->
                val bytes = try {
                    resource.open().use { it.readBytes().size }
                } catch (e: IOException) {
                    "unreadable ($e)"
                }
                "$bytes byte(s) from ${resource.sourcePackId()}"
            } ?: "missing"
            out += "[oml_testmod]   $id -> $served"
            E2E.check(check, served != "missing", "$id -> $served")
        }

        // Every candidate accepted, which is the only thing the listing measurement needs.
        val selector = ResourceManager.Selector { true }
        // Discovery by directory — the path that runs through the injected PackResources.listResources
        // and how 26.3 finds sounds (Sound.SOUND_LISTER = FileToIdConverter("sounds", ".ogg")), particles,
        // atlas sprites and blockstates/items.
        out += "[oml_testmod] resource pack listResources (discovery by directory — this is the path a mod's sounds, atlas sprites and overrides depend on):"
        for ([check, dir] in listOf(
            "pack.dir.blockstates" to "blockstates",
            "pack.dir.models_block" to "models/block",
            "pack.dir.items" to "items",
            "pack.dir.textures_block" to "textures/block",
            "pack.dir.textures_particle" to "textures/particle",
            "pack.dir.lang" to "lang",
        )) {
            val found = manager.listResources(dir, selector)
            // our own files only: the mod namespace, or our marker name inside the minecraft namespace.
            // The minecraft-namespace `oml_test.png` is a name vanilla does not have, so it exercises a
            // real override without risking the game's own assets; `sounds/oml_test.ogg` is an
            // unreferenced placeholder, so the decoder never sees it and the listing can be measured.
            val mine =
                found.keys.map { it.toString() }.filter { it.startsWith("oml_testmod:") || it.contains("oml_test") }
                    .sorted()
            out += "[oml_testmod]   $dir -> ${if (mine.isEmpty()) "none" else mine.joinToString(", ")}"
            E2E.check(check, mine.isNotEmpty(), "$dir -> ${mine.size} entry(ies)")
        }
        return out
    }

    @JvmStatic
    fun hit(mergedStaticField: Int) {
        E2E.check(
            "merge.overwrite_static_field",
            mergedStaticField == 7,
            "@Unique static field=$mergedStaticField (7 = <clinit> spliced)",
        )
        if (logged.compareAndSet(false, true)) {
            println(
                "[oml_testmod] class merge: @Overwrite method body executed, " +
                    "@Unique static field (initial value from the spliced <clinit>) = $mergedStaticField",
            )
        }
    }
}
