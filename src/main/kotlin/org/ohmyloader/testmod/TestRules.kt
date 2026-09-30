package org.ohmyloader.testmod

import org.ohmyloader.api.inject.RuleSet
import org.ohmyloader.api.inject.RuleSetProvider
import org.ohmyloader.api.inject.RuleSource
import org.ohmyloader.api.inject.injection

/**
 * A hand-written rule set from a mod: proves that rules can be handed to the loader **without
 * annotations**. It targets the same class as the annotated rules in [FramerateLimitMixin]; the two
 * paths feed the same spec — the same self-check and the same hit-count report.
 *
 * The target is `com.mojang.blaze3d.platform.FramerateLimitTracker`, where 26.3 keeps the frame-rate
 * limit; the method name (`getFramerateLimit`) is unchanged. `require(1)` is kept: a rule anchored at
 * the wrong class must fail loudly at startup rather than silently do nothing. This class's only
 * dependencies are `oml-api` and itself: rule classes are loaded **before** the game classes, so
 * referencing a game class would drag it in early — the loader statically validates this; see
 * `ModRuleSets`.
 */
@RuleSource("testmod-dsl")
object TestRules : RuleSetProvider {

    private var calls = 0

    /**
     * Print entry of the merged form: it is **not merged into** any class, so the string template
     * here can use any modern syntax.
     *
     * The passed-in value is the target instance's class name — on a successful merge it is
     * `com.mojang.blaze3d.platform.FramerateLimitTracker`.
     */
    @JvmStatic
    fun reportMergedForm(owner: String) {
        println("[oml_testmod] ruleset: merged form active -- this = $owner (handler lives in the target class; no bridge, no reflection)")
    }

    /** Static entry point called from an injection point (only `@JvmStatic` in an `object` yields a
     * static method). */
    @JvmStatic
    fun onFrameLimitRead() {
        calls++
        if (calls == 1) {
            println("[oml_testmod] ruleset: hand-written mod DSL rule in effect (frame-rate limit method HEAD)")
        }
    }

    override fun rules(): RuleSet = injection {
        classTarget("com/mojang/blaze3d/platform/FramerateLimitTracker") {
            // merged form: merge FrameLimitPatch's members into the target class, so the handler runs
            // with this = target instance
            merge("org/ohmyloader/testmod/FrameLimitPatch", id = "frame-limit-patch")

            // static bridge form: the handler lives in TestRules; the injection point calls it via
            // INVOKESTATIC
            method("getFramerateLimit", desc = "()I") {
                atHead { call("org/ohmyloader/testmod/TestRules", "onFrameLimitRead", "()V") }
                require(1)
            }

            // merged form on the same anchor: call the handler that moved into the target class via
            // the merge (owner is filled in from the target class automatically)
            method("getFramerateLimit", desc = "()I") {
                atHead {
                    mergedCall("onFrameLimitRead", "(Lorg/ohmyloader/api/mixin/CallbackInfo;)V")
                }
                require(1)
            }
        }
    }
}
