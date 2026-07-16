package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.TypeName;

/**
 * One site in a cross-site {@code contextArgument} type-conflict rejection: the coordinate where
 * the name was referenced and the structural {@link TypeName} that site declared.
 *
 * <p>The coordinate is a sealed {@link Site}: a {@link Site.Method} wraps a {@link MethodRef}
 * (a {@code @condition} / {@code @tableMethod} / {@code @externalField} param list), and a
 * {@link Site.Carrier} wraps a {@link ServiceMethodCall} (a root sync {@code @service} carrier,
 * whose ctor/method args hold the context slots). This was widened from a bare {@link MethodRef}:
 * the four root sync {@code @service} permits no longer carry a {@link MethodRef}, so the classifier
 * previously fabricated an empty {@code MethodRef.Service} sentinel
 * ({@code ContextArgumentClassifier.syntheticServiceMethodRef}) just to satisfy this field. The
 * sealed widening lets the carrier coordinate be carried honestly.
 *
 * <p>Captured at the classifier producing {@link Rejection.AuthorError.TypeConflict}; consumed by
 * the message renderer (via {@link Site#className()} / {@link Site#methodName()}) and by any future
 * LSP fix-it that wants to navigate to a declaring method or carrier.
 */
public record ConflictSite(Site site, TypeName declared) {

    /**
     * The directive coordinate a context-argument reference came from. Both arms expose the
     * class + method names the conflict-rejection renderer reads, projected from the wrapped
     * model value so the renderer need not switch on the arm.
     */
    public sealed interface Site permits Site.Method, Site.Carrier {
        String className();
        String methodName();

        /** A {@link MethodRef}-backed coordinate ({@code @condition} / {@code @tableMethod} / {@code @externalField}). */
        record Method(MethodRef ref) implements Site {
            @Override public String className() { return ref.className(); }
            @Override public String methodName() { return ref.methodName(); }
        }

        /** A {@link ServiceMethodCall}-carrier coordinate (a root sync {@code @service} permit). */
        record Carrier(ServiceMethodCall call) implements Site {
            @Override public String className() { return call.fqClassName(); }
            @Override public String methodName() { return call.methodName(); }
        }
    }

    /** Convenience factory for a {@link MethodRef}-backed site. */
    public static ConflictSite of(MethodRef ref, TypeName declared) {
        return new ConflictSite(new Site.Method(ref), declared);
    }

    /** Convenience factory for a {@link ServiceMethodCall}-carrier site. */
    public static ConflictSite of(ServiceMethodCall call, TypeName declared) {
        return new ConflictSite(new Site.Carrier(call), declared);
    }
}
