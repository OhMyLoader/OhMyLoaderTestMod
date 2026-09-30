package org.ohmyloader.testmod;

import org.ohmyloader.api.mixin.*;

/**
 * Class-merging demo: this mixin's fields/methods are physically merged into
 * {@code net.minecraft.client.Minecraft}.
 *
 * <p>The handlers are behaviorally equivalent identity implementations of trivial, stable target
 * methods, so the merge pipeline is proven without changing game behavior. Exercised paths:
 * {@link Shadow} — after merging, the code resides inside the target class and reads the private
 * {@code proxy} field without reflection; {@link Overwrite} — replaces an entire method body;
 * {@link Unique} — a static field whose initializer only becomes non-zero when the mixin's
 * {@code <clinit>} is spliced in. Java rather than Kotlin deliberately: an {@code @Shadow} field must
 * be declared but never initialized, which Kotlin property syntax forbids without {@code lateinit}.</p>
 */
@Mixin(target = "net.minecraft.client.Minecraft")
public class MinecraftMergeMixin {

    /**
     * A static field merged into the target class: its initial value of 7 lives in the mixin's
     * `<clinit>`.
     */
    @Unique
    private static int omlMergeCount = 7;
    /**
     * An <b>instance</b> field merged into the target class: its initial value of 1234 is written
     * in the mixin's own constructor (Java compiles instance-field initializers into {@code <init>}).
     *
     * <p>It is {@code final}, and the JVM only permits assigning a final instance field in the
     * {@code <init>} of the class that <b>declares the field</b> — so that {@code PUTFIELD} must
     * physically land in the target class's constructor. This field is the acceptance point for
     * constructor merging: active ⇒ {@code run()} reads 1234; not active ⇒ it keeps the JVM default
     * of 0 (the field is still merged in, so there are no other symptoms).</p>
     */
    @Unique
    private final int omlCtorInit = 1234;
    /**
     * A private field in the target class; {@code @Shadow} merely declares that it exists and it
     * is not merged in a second time.
     */
    @Shadow
    private String launchedVersion;

    /**
     * A behavior-equivalent {@code @Overwrite} of a trivial, stable method (reads one field, returns
     * it). {@code getProxy()} cannot serve as the target: 26.3 added that exact public method to the
     * target class, which turns an overwrite into a collision — the merge fails loudly instead of
     * picking a winner.
     */
    @Overwrite
    public String getLaunchedVersion() {
        MergeProbe.hit(omlMergeCount);
        omlMergeCount = omlMergeCount + 1;
        return this.launchedVersion;
    }

    /**
     * An <b>instance</b> handler — merged into the target class along with the class, invoked directly
     * by the injection point, so {@code this} here is the <b>Minecraft instance</b> and
     * {@code this.proxy} reads <b>Minecraft's field</b>. Under the injection model instead, the handler
     * stays on the mixin class, is invoked via reflection, and the {@code @Shadow} fields of a mixin
     * instance are never initialized — that would read {@code null} here. This line is the evidence of
     * the difference.
     *
     * <p>{@code run()} is a parameterless instance method of the target class (the main loop entry
     * point). Also reports {@code omlCtorInit} once — non-zero only when constructor merging is active
     * (see that field's comment).</p>
     */
    @Inject(method = "run", at = @At(At.HEAD))
    private void onRunReadsShadowField(CallbackInfo ci) {
        MergeProbe.hitShadow(this.launchedVersion);
        MergeProbe.hitCtorInit(this.omlCtorInit);
    }

    /**
     * A <b>value-modifying</b> ({@code @ModifyConstant}) instance handler, on the same merged path as
     * the {@code @Inject} above: the injection point calls it directly via {@code INVOKEVIRTUAL}, and
     * <b>there is no receiver in the parameter list</b> — the signature is {@code (int) -> int}.
     * Writing the receiver in makes the JVM treat the value as a reference: the class then fails to
     * define with {@code VerifyError: Bad local variable type}. Deliberately an identity transform —
     * this proves the pipeline runs, it does not change game behavior. Two handlers exist, one per
     * {@code (method, constant)} pair ({@code getLimitFramerate()} carries {@code bipush 30},
     * {@code getFramerateLimit()} {@code bipush 60}); the method name decides the site, and a name the
     * target class does not have is a soft miss (no strategy, same as Mixin). The site also exercises
     * a degenerate vanilla stack frame (see {@code FrameRepair}).
     */
    @ModifyConstant(method = "getLimitFramerate", constant = @Constant(intValue = 30))
    private int onLimitFramerateConstant(int value) {
        MergeProbe.modifiedConstant("getLimitFramerate/30", value);
        return value;
    }

    /**
     * See {@link #onLimitFramerateConstant} — the other constant site, under the other method name.
     */
    @ModifyConstant(method = "getFramerateLimit", constant = @Constant(intValue = 60))
    private int onFramerateLimitConstant(int value) {
        MergeProbe.modifiedConstant("getFramerateLimit/60", value);
        return value;
    }

    /**
     * A <b>return-value-modifying</b> ({@code @ModifyReturnValue}) instance handler.
     *
     * <p>With the anchor omitted it defaults to {@code RETURN}: at each return of the target method
     * the top of the stack is the return value, which the handler intercepts and replaces. The handler
     * takes the form {@code (R)R} ({@code R} is the target method's return type); the receiver is
     * still {@code this} and is not written into the parameter list. The method name decides the site,
     * and a name the target class does not have makes the rule a soft miss (no strategy, same as
     * Mixin). Deliberately an identity transform; unlike {@code @ModifyVariable(at = RETURN)}, it
     * validates the handler shape against the <b>return type</b>, so it holds for any return type.</p>
     */
    @ModifyReturnValue(method = "getLimitFramerate")
    private int onLimitFramerateReturn(int value) {
        MergeProbe.modifiedReturnValue("getLimitFramerate", value);
        return value;
    }

    /**
     * See {@link #onLimitFramerateReturn}.
     */
    @ModifyReturnValue(method = "getFramerateLimit")
    private int onFramerateLimitReturn(int value) {
        MergeProbe.modifiedReturnValue("getFramerateLimit", value);
        return value;
    }

    /**
     * An <b>expression-value-modifying</b> ({@code @ModifyExpressionValue}) instance handler.
     *
     * <p>The anchor is an expression that <b>produces a value</b> — here the integer constant inside
     * the target method: the expression still executes as usual, only its result is swapped out (its
     * division of labor with {@code @Redirect}, which replaces the entire call, arguments included).
     * The anchor sits on the same constant the {@code @ModifyConstant} demo uses, so the two stack on
     * one site; both return identically, so stacking them does not change game behavior.</p>
     */
    @ModifyExpressionValue(
        method = "getLimitFramerate",
        at = @At(value = At.CONSTANT, args = "intValue=30"))
    private int onLimitFramerateExpressionValue(int value) {
        MergeProbe.modifiedExpressionValue("getLimitFramerate/30", value);
        return value;
    }

    /**
     * See {@link #onLimitFramerateExpressionValue}.
     */
    @ModifyExpressionValue(
        method = "getFramerateLimit",
        at = @At(value = At.CONSTANT, args = "intValue=60"))
    private int onFramerateLimitExpressionValue(int value) {
        MergeProbe.modifiedExpressionValue("getFramerateLimit/60", value);
        return value;
    }
}
