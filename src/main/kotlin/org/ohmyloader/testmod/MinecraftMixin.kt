package org.ohmyloader.testmod

import org.ohmyloader.api.mixin.*

/**
 * Mixin front-end validation on `Minecraft`: HEAD injection, cancellable short-circuiting,
 * **argument capture**, and the **CTOR_HEAD anchor**.
 *
 * The anchors are the shapes 26.3 actually has: `runTick` takes a `(Z)` render flag, which is what
 * argument capture needs to have something to capture, and the frame-rate limit is no longer a
 * `Minecraft` method — it moved to `com.mojang.blaze3d.platform.FramerateLimitTracker`, which is why
 * that half lives in [FramerateLimitMixin]. A rule that neither reads an argument nor produces a return
 * value needs one handler shape; once it must read an argument or produce a return value, the shape is
 * pinned by the target and has to be declared to match — the testmod lays out both. `TAIL` retains the
 * Mixin semantics (**the last** return); "every return" uses `RETURN`.
 */
@Mixin(target = "net.minecraft.client.Minecraft")
class MinecraftMixin {

    private var runTickCalls = 0
    private var renderFlagCalls = 0
    private var firstReturnCalls = 0
    private var fpsLimitCalls = 0
    private var constructed = false

    /**
     * CTOR_HEAD: the injection point lands on the first instruction **after** the constructor's
     * `super()` call.
     *
     * `HEAD` would land before `super()` — at that point `this` is not yet initialized, and a
     * handler touching `this` would be illegal bytecode. `CTOR_HEAD` is the only safe entry anchor
     * inside a constructor. Writing the method name as `<init>` matches every constructor overload.
     */
    @Inject(method = "<init>", at = At(At.CTOR_HEAD))
    fun onMinecraftConstructed(ci: CallbackInfo) {
        if (!constructed) {
            constructed = true
            println("[oml_testmod] Mixin: CTOR_HEAD anchor in effect (after constructor super())")
        }
    }

    @Inject(method = "runTick", at = At(At.HEAD), cancellable = true)
    fun onRunTick(ci: CallbackInfo) {
        runTickCalls++
        if (runTickCalls == 1) {
            println("[oml_testmod] Mixin: runTick HEAD injection in effect")
        }
        if (runTickCalls == 600) {
            println("[oml_testmod] Mixin: short-circuiting runTick once (game logic pauses for 1 tick, then resumes)")
            ci.cancel()
        }
    }

    /**
     * Argument capture (isomorphic with Mixin): `runTick(Z)V` passes "whether this is a render
     * frame" in as a parameter; the handler declares a `Boolean` parameter **before** `CallbackInfo`
     * to receive it. `locals = CAPTURE_FAILHARD` must be written explicitly — local-capture cannot
     * be verified statically, so the author states the intent (`CAPTURE_FAILSOFT` just skips and
     * prints the reason); the slot is located by the engine's data-flow analysis at the injection
     * point by type, so `desc` may even be left empty. `desc = "(Z)V"` is written out to match only
     * the render-flag overload — a nonexistent descriptor is a **soft miss** here (no `require`),
     * which keeps this a demonstration rather than a startup failure.
     */
    @Inject(
        method = "runTick",
        desc = "(Z)V",
        at = At(At.HEAD),
        locals = LocalCapture.CAPTURE_FAILHARD,
        // no policy is set on purpose: a missing descriptor is a soft failure (same as Mixin), not a
        // startup error
    )
    fun onRunTickWithRenderFlag(render: Boolean, ci: CallbackInfo) {
        renderFlagCalls++
        if (renderFlagCalls == 1) {
            println("[oml_testmod] Mixin: runTick argument capture in effect -- render=$render")
        }
    }

    /**
     * New field: `@At(ordinal = …)` selects the Nth match, starting at 0.
     *
     * `ordinal = 0` means "before the first return", and it demonstrates `@At`'s full selection
     * surface: an ordinal is resolved after the other filters, so the same anchor form can be aimed
     * at any match without a second injection point.
     */
    @Inject(method = "runTick", at = At(value = At.RETURN, ordinal = 0))
    fun onRunTickFirstReturn(ci: CallbackInfo) {
        firstReturnCalls++
        if (firstReturnCalls == 1) {
            println("[oml_testmod] Mixin: @At(RETURN, ordinal = 0) in effect (before the first return)")
        }
    }

    /**
     * **Short-circuit with a value**: clamps the frame-rate limit to 60.
     *
     * `Minecraft.getLimitFramerate()` does not exist on 26.3 — the limit moved to
     * `com.mojang.blaze3d.platform.FramerateLimitTracker` (see [FramerateLimitMixin]) — so this rule
     * is a **soft miss**: with no `require`, the miss is a warning rather than a startup failure. It
     * stays as the "cancellable handler on `Minecraft`" counterpart to the live rule, written in the
     * same shape. `CallbackInfoReturnable.setReturnValue(v)` automatically cancels the target method:
     * a matched target **directly returns 60** and its body never runs. Only the first 5 calls are
     * clamped — that proves the value really reaches the caller without permanently masking the
     * original read path.
     */
    @Inject(method = "getLimitFramerate", desc = "()I", at = At(At.HEAD), cancellable = true)
    fun capFramerateLimit(ci: CallbackInfoReturnable<Int>) {
        fpsLimitCalls++
        if (fpsLimitCalls <= 5) {
            if (fpsLimitCalls == 1) {
                println("[oml_testmod] Mixin: short-circuit with value in effect (getLimitFramerate returns 60 directly)")
            }
            ci.setReturnValue(60)
        }
    }
}
