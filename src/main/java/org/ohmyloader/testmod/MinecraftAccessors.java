package org.ohmyloader.testmod;

import org.ohmyloader.api.mixin.Accessor;
import org.ohmyloader.api.mixin.Mixin;


/**
 * Interface-mixin demo: {@code @Accessor} is declared on an abstract method of an interface, and
 * the engine synthesizes a method body in the target class according to the signature — private
 * fields can then be accessed without reflection.
 * <p>The "1 accessor synthesized" entry in the merge log is the E2E acceptance point: the interface
 * mixin was recognized, the target field was validated against the real game class, and the
 * synthesized body actually landed in the target class (any mismatch causes a hard merge failure,
 * not a silent skip). That the synthesized method actually gets invoked is proven by
 * {@code ClassMergerTest} (merge → define → invoke reflectively → assert the value read).</p>
 */
@Mixin(target = "net.minecraft.client.Minecraft")
public interface MinecraftAccessors {

    /**
     * Reads a target-class private field directly (without reflection); the return type must match
     * the field type exactly. {@code proxy} cannot serve as the target: 26.3 added a public
     * {@code getProxy()} to the target class, which makes an accessor for it redundant (the merge
     * fails loudly on that now). The target is re-verified against the real 26.3 jar on every
     * launch, so a drifted anchor fails the startup loudly.
     */
    @Accessor("demo")
    boolean readDemoFlag();
}
