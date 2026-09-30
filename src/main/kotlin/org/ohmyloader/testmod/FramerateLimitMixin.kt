package org.ohmyloader.testmod

import org.ohmyloader.api.mixin.*

/**
 * The frame-rate-limit half of the Mixin front-end validation, split onto its own target class.
 *
 * 26.3 moved the limit off `Minecraft`: there is no `getFramerateLimit()` there any more, and
 * `Minecraft.renderFrame` reads the value from
 * `com.mojang.blaze3d.platform.FramerateLimitTracker.getFramerateLimit()I` instead. A `@Mixin` declares
 * its target per class, so following the method across classes means a second mixin rather than a
 * second annotation. Both rules keep the **same shapes** as their `Minecraft`-era counterparts — only
 * the target class changed, not what the engine has to do.
 */
@Mixin(target = "com.mojang.blaze3d.platform.FramerateLimitTracker")
class FramerateLimitMixin {

    private var frameLimitCalls = 0
    private var fpsCalls = 0

    /**
     * `TAIL`: lands before the **last** return of the target method.
     *
     * 26.3's version is a `tableswitch` over the throttle reason and therefore has several returns, so
     * `TAIL` (last return) and `RETURN` (every return) are not interchangeable here. `TAIL` is the one
     * that means "the value the caller will use".
     */
    @Inject(method = "getFramerateLimit", desc = "()I", at = At(At.TAIL))
    fun onLimitFramerate(ci: CallbackInfo) {
        frameLimitCalls++
        if (frameLimitCalls == 1) {
            println("[oml_testmod] Mixin: getFramerateLimit TAIL injection in effect")
        }
    }

    /**
     * **Short-circuit with a value** on the live (26.3) location: the handler makes the target method
     * return 60 directly, and only for the first 5 calls so the clamped value is proven to reach the
     * caller without permanently masking the original read path.
     */
    @Inject(method = "getFramerateLimit", desc = "()I", at = At(At.HEAD), cancellable = true)
    fun capFramerateLimit(ci: CallbackInfoReturnable<Int>) {
        fpsCalls++
        if (fpsCalls <= 5) {
            if (fpsCalls == 1) {
                println("[oml_testmod] Mixin: short-circuit with value in effect (getFramerateLimit returns 60 directly)")
            }
            ci.setReturnValue(60)
        }
    }
}
