package org.ohmyloader.testmod

import org.ohmyloader.testmod.MergeProbe.gameLoader
import java.lang.invoke.MethodHandles
import java.lang.reflect.Modifier
import java.net.Proxy
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Observation point for the class-merge demo, and the test mod's home for "measured on the real game"
 * evidence (the resource-pack probe below answers a question no unit test can: what the live
 * `ResourceManager` actually serves).
 *
 * An `@Overwrite`d method body runs **inside the target class** (`net.minecraft.client.Minecraft`) and
 * calls here to report that the merged-in code really executed — the merge is transparent to callers
 * (the signature and behavior are unchanged), so only a report emitted from inside gives E2E a definite
 * acceptance point. [hit]'s argument is the mixin's `@Unique` static field, whose initializer is spliced
 * into the target class's `<clinit>`: it **must be 7**; a 0 means the `<clinit>` was not spliced, which
 * is exactly what this is watching for.
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
        if (shadowLogged.compareAndSet(false, true)) {
            println(
                "[oml_testmod] class merge: instance handler this semantics in effect -- " +
                    "this.launchedVersion = ${launchedVersion ?: "null"}"
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
        if (constantLogged.compareAndSet(false, true)) {
            println(
                "[oml_testmod] class merge: instance @ModifyConstant in effect -- $where, received constant = $value" +
                    " (identity return; game behavior unchanged)"
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
        if (ctorInitLogged.compareAndSet(false, true)) {
            println(
                "[oml_testmod] class merge: constructor merge in effect -- instance field initializer in the mixin constructor = $value" +
                    " (would be 0 without the merge)"
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
        if (omlCore("isServerSide") as Boolean) {
            println("[oml_testmod] access rewrite: skipped on the dedicated server (the rule is a client-side hook)")
            return
        }
        // The game class is asked for through the **game loader**, never through this class's own
        // loader — see [gameLoader] for what that distinction costs. `getDeclaredField` resolves the
        // type of *every* declared field, not just the one named, so on the wrong loader the failure
        // is a `NoClassDefFoundError` about some unrelated library type and says nothing about whether
        // the access rewrite landed. It is still wrapped: if it ever fails on the right loader, the
        // chain and the loader of each name are printed rather than swallowed.
        val field = runCatching {
            gameLoader().loadClass("net.minecraft.client.Minecraft").getDeclaredField("proxy")
        }.getOrElse {
            reportUnreflectable(it)
            return
        }
        val handle = runCatching {
            MethodHandles.publicLookup().findGetter(field.declaringClass, "proxy", Proxy::class.java)
        }
        println(
            "[oml_testmod] access rewrite: Minecraft.proxy is now ${Modifier.toString(field.modifiers)}" +
                " (was private final); publicLookup read handle = " +
                if (handle.isSuccess) "usable" else "unusable (${handle.exceptionOrNull()?.javaClass?.simpleName})"
        )
    }

    /**
     * The loader the **game's own classes** are defined by, asked of the loader instead of read off
     * this class. `MergeProbe::class.java.classLoader` is the *launcher layer* loader, not the game
     * loader: OML keeps `org.ohmyloader.` parent-first, so this mod (in that namespace) is defined by
     * the layer too. The layer's search path is the launch classpath — it carries the game jar (so a
     * *second*, untransformed copy of every game class is definable from it) but **not** the version's
     * library directory, and it has no local fallback. Reflecting a game class through it therefore
     * dies on the first field type that lives in a library jar (on 26.3: `NoClassDefFoundError:
     * com/mojang/brigadier/Message` on the client, `.../authlib/exceptions/AuthenticationException`
     * on the server).
     */
    private fun gameLoader(): ClassLoader = omlCore("gameClassLoader") as ClassLoader

    /**
     * One of `OMLCore`'s public static entry points, invoked reflectively.
     *
     * By name rather than by type for the reason [gameLoader] gives: a mod compiles against `oml-api`
     * alone, and the loader's core is an implementation artifact a mod must not gain a compile
     * dependency on. `OMLCore` is parent-first, so this resolves to the same class OML itself runs.
     */
    private fun omlCore(method: String): Any? =
        Class.forName("org.ohmyloader.core.OMLCore").getMethod(method).invoke(null)

    /**
     * Why the probe above could not get a `Field` at all, and **which loader** resolved what.
     *
     * The failure is printed with its whole cause chain, because the class named by a
     * `NoClassDefFoundError` is not always the one being looked for — it is the first type that could
     * not be resolved. A few names this path depends on are then each resolved explicitly and reported
     * with the loader that answered (a `URLClassLoader` would mean the *layer* answered, i.e. a
     * duplicate game class, not OML's transformed one). Between "which name failed" and "which loader
     * answered", the two ways this can go wrong — wrong loader, genuinely unresolvable type — are told
     * apart without a second run.
     */
    private fun reportUnreflectable(failure: Throwable) {
        println("[oml_testmod] access rewrite: Minecraft.proxy is not reflectable through the game loader; skipping this probe")
        val chain = generateSequence(failure) { it.cause }
            .joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }
        println("[oml_testmod]   failure chain: $chain")
        val loader = gameLoader()
        for (name in listOf(
            "net.minecraft.client.Minecraft",
            "net.minecraft.network.chat.Component",
            "com.mojang.brigadier.Message",
            "com.mojang.authlib.GameProfile",
        )) {
            val where = runCatching { Class.forName(name, false, loader) }.fold(
                onSuccess = { it.classLoader?.javaClass?.simpleName ?: "bootstrap" },
                onFailure = { "unresolved ($it)" },
            )
            println("[oml_testmod]   $name -> $where")
        }
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
        if (returnValueLogged.compareAndSet(false, true)) {
            println(
                "[oml_testmod] class merge: @ModifyReturnValue in effect -- $where, received return value = $value" +
                    " (identity return; game behavior unchanged)"
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
        if (expressionValueLogged.compareAndSet(false, true)) {
            println(
                "[oml_testmod] class merge: @ModifyExpressionValue in effect -- $where, received produced value = $value" +
                    " (identity return; game behavior unchanged)"
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
     * count, so the answer is not just "present" but "these bytes were served by pack X". All
     * reflection goes through [gameLoader]: this mod compiles against `oml-api` only.
     */
    @JvmStatic
    fun reportResourceInjection() {
        if (resourceLogged.get()) return
        val lines = runCatching { probeResources(gameLoader()) }.getOrElse { failure ->
            if (resourceLogged.compareAndSet(false, true)) {
                println("[oml_testmod] resource pack: probe failed -- $failure")
            }
            return
        } ?: return // null == the initial (asynchronous) reload has not completed yet; ask again next tick
        if (resourceLogged.compareAndSet(false, true)) lines.forEach(::println)
    }

    private val resourceLogged = AtomicBoolean(false)

    /** One line per measurement, or null while the initial reload is still running. */
    private fun probeResources(loader: ClassLoader): List<String>? {
        val minecraftClass = loader.loadClass("net.minecraft.client.Minecraft")
        val minecraft = minecraftClass.getMethod("getInstance").invoke(null) ?: return null
        val manager = minecraftClass.getMethod("getResourceManager").invoke(minecraft)
        val parse = loader.loadClass("net.minecraft.resources.Identifier").getMethod("parse", String::class.java)
        val getResource = manager.javaClass.methods.first { it.name == "getResource" && it.parameterCount == 1 }

        fun resourceOf(id: String): Any? =
            (getResource.invoke(manager, parse.invoke(null, id)) as Optional<*>).orElse(null)

        if (resourceOf("minecraft:blockstates/bedrock.json") == null) return null

        val out = mutableListOf<String>()
        // Deliberately not only block assets — sounds, lang, atlas sprites and overrides are the half a
        // pack tends to get wrong. The synthesized ids exist in no file at all and the texture is a real
        // jar file, so a served hit must report `oml_mod_resources` as its source pack.
        out += "[oml_testmod] resource pack getResource (by id: synthesized block assets, jar assets):"
        for (id in listOf(
            "oml_testmod:blockstates/test_block.json",
            "oml_testmod:models/block/test_block.json",
            "oml_testmod:items/test_block.json",
            "oml_testmod:textures/block/test_block.png",
            "oml_testmod:lang/en_us.json",
        )) {
            val served = resourceOf(id)?.let { resource ->
                val bytes = (resource.javaClass.getMethod("open").invoke(resource) as java.io.InputStream)
                    .use { it.readBytes().size }
                val pack = resource.javaClass.getMethod("sourcePackId").invoke(resource) as String
                "$bytes byte(s) from $pack"
            } ?: "missing"
            out += "[oml_testmod]   $id -> $served"
        }

        // Selector is a functional interface; a Proxy answering true accepts every candidate
        val selectorClass = loader.loadClass($$"net.minecraft.server.packs.resources.ResourceManager$Selector")
        val selector = java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(selectorClass)) { proxy, method, args ->
            when (method.name) {
                "isIncluded" -> true
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> args[0] === proxy
                "toString" -> "OMLSelectorAll"
                else -> null
            }
        }
        val listResources = manager.javaClass.methods.first { it.name == "listResources" && it.parameterCount == 2 }
        // Discovery by directory — the path that runs through the injected PackResources.listResources
        // and how 26.3 finds sounds (Sound.SOUND_LISTER = FileToIdConverter("sounds", ".ogg")), particles,
        // atlas sprites and blockstates/items.
        out += "[oml_testmod] resource pack listResources (discovery by directory — this is the path a mod's sounds, atlas sprites and overrides depend on):"
        for (dir in listOf(
            "blockstates",
            "models/block",
            "items",
            "textures/block",
            "textures/particle",
            "lang",
        )) {
            val found = listResources.invoke(manager, dir, selector) as Map<*, *>
            // our own files only: the mod namespace, or our marker name inside the minecraft namespace.
            // The minecraft-namespace `oml_test.png` is a name vanilla does not have, so it exercises a
            // real override without risking the game's own assets; `sounds/oml_test.ogg` is an
            // unreferenced placeholder, so the decoder never sees it and the listing can be measured.
            val mine =
                found.keys.map { it.toString() }.filter { it.startsWith("oml_testmod:") || it.contains("oml_test") }
                    .sorted()
            out += "[oml_testmod]   $dir -> ${if (mine.isEmpty()) "none" else mine.joinToString(", ")}"
        }
        return out
    }

    @JvmStatic
    fun hit(mergedStaticField: Int) {
        if (logged.compareAndSet(false, true)) {
            println(
                "[oml_testmod] class merge: @Overwrite method body executed, " +
                    "@Unique static field (initial value from the spliced <clinit>) = $mergedStaticField"
            )
        }
    }
}
