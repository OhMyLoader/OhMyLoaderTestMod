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

}
