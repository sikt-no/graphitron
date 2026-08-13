package no.sikt.graphitron.rewrite.session;

import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.MethodRef;

import java.util.List;
import java.util.Optional;

/**
 * The resolved session-hook carrier: the authored {@link SessionStateConfig.MethodHooks} strings
 * reflected into {@link MethodRef.StaticOnly} references, total over the outcome so every
 * emit-side reader keeps a single exhaustive fork. Minted by {@code GraphitronSchemaBuilder}
 * (the stage that owns {@code ServiceCatalog}) and hung on
 * {@link no.sikt.graphitron.rewrite.GraphitronSchema} upstream of the contextArgument
 * classification, defaulting to {@link NotConfigured} in the convenience constructors the way
 * {@code tenantScopes} defaults, so the classifier population is a function of the model's own
 * components rather than of which constructor a caller used.
 *
 * <p>The handle axis is the seal: {@link HandleLess} is a mount returning {@code void}, so
 * "handle-less implies the pinned carrier has no handle field, implies the generated unmount
 * takes only the connection" stays structural on this fork, and every emit site switches once
 * on the arm instead of re-testing the return type for {@code void}. A reflection failure never
 * reaches this carrier: the builder drains it as a schema-wide, coordinate-less
 * {@code ValidationError} and mints {@link NotConfigured}, so no hook unit is planned or
 * emitted for a build that already failed.
 *
 * <p>{@link #emitsHookImplementation()} is the single membership fact the emit plan
 * ({@code EmitPlan.produce}), the connection runtime
 * ({@code ConnectionRuntimeClassGenerator.generate}) and the dev executor
 * ({@code GraphitronDevExecutorGenerator.generate}) all read, so the plan and the renderers
 * cannot fork on different answers to "is a hook configured".
 */
public sealed interface SessionHooks permits SessionHooks.NotConfigured, SessionHooks.HandleLess, SessionHooks.Handled {

    /**
     * The {@code Configuration.data()} key the generated runtime writes the mount's handle
     * under, and the {@code $session} call-site extraction reads. One constant so the writer
     * (the connection runtime's acquisition) and the reader (the service-call emitter) cannot
     * drift; the key is scoped per pinned connection because {@code data()} is jOOQ's
     * per-{@code Configuration} map and the carrier entry owns its {@code DSLContext}.
     */
    String HANDLE_DATA_KEY = "no.sikt.graphitron.session.handle";

    /**
     * True when the configuration emits a generated hook class (either configured arm); the
     * {@link NotConfigured} arm emits nothing at all: no unit, no field, no mount call.
     */
    default boolean emitsHookImplementation() {
        return !(this instanceof NotConfigured);
    }

    /** The resolved mount, present on both configured arms. */
    default Optional<MethodRef.StaticOnly> mountRef() {
        return switch (this) {
            case NotConfigured ignored -> Optional.empty();
            case HandleLess h -> Optional.of(h.mount());
            case Handled h -> Optional.of(h.mount());
        };
    }

    /** The resolved unmount, present when {@code <unmount>} was configured. */
    default Optional<MethodRef.StaticOnly> unmountRef() {
        return switch (this) {
            case NotConfigured ignored -> Optional.empty();
            case HandleLess h -> h.unmount();
            case Handled h -> h.unmount();
        };
    }

    /**
     * The mount's payload parameters ({@link no.sikt.graphitron.rewrite.model.ParamSource.Context}-sourced,
     * in mount's own declaration order), the population that feeds
     * {@code ContextArgumentClassifier} as an additional root and grows the owned factory by one
     * name-keyed slot each. Empty when not configured or when the mount's only parameter is the
     * seam.
     */
    default List<MethodRef.Param> payloadParams() {
        return mountRef()
            .map(m -> m.params().stream()
                .filter(p -> p.source() instanceof no.sikt.graphitron.rewrite.model.ParamSource.Context)
                .toList())
            .orElse(List.of());
    }

    /** No method hooks configured (or a reflection failure already drained by the builder). */
    record NotConfigured() implements SessionHooks {
        public static final NotConfigured INSTANCE = new NotConfigured();
    }

    /**
     * A mount returning {@code void}: no handle exists, {@code $session} has nothing to bind,
     * and the unmount (when present) takes only the seam.
     */
    record HandleLess(MethodRef.StaticOnly mount, Optional<MethodRef.StaticOnly> unmount) implements SessionHooks {
        public HandleLess {
            java.util.Objects.requireNonNull(mount, "mount");
            java.util.Objects.requireNonNull(unmount, "unmount");
        }
    }

    /**
     * A mount returning a handle of {@code handleType} (the reflected return type). The handle
     * is written once per acquisition to the per-key carrier entry and read by {@code release}
     * (to call unmount) and by the {@code $session} sigil's call-site extraction.
     */
    record Handled(MethodRef.StaticOnly mount, TypeName handleType, Optional<MethodRef.StaticOnly> unmount)
        implements SessionHooks {
        public Handled {
            java.util.Objects.requireNonNull(mount, "mount");
            java.util.Objects.requireNonNull(handleType, "handleType");
            java.util.Objects.requireNonNull(unmount, "unmount");
        }
    }
}
