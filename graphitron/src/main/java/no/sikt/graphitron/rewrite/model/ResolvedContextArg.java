package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.TypeName;

import java.util.List;

/**
 * Classifier output for one {@code contextArgument} name resolved to a single Java type across
 * every directive site that references it.
 *
 * <p>Produced by the cross-site context-argument agreement classifier. The classifier walks every
 * {@link MethodRef.Param.Typed} whose source is {@link ParamSource.Context}, keys by parameter
 * name, and requires the structural {@link TypeName} to agree across all sites. The agreed type is
 * stored on {@link #javaType} and consumed verbatim by both the factory emitter (parameter list of
 * {@code Graphitron.newExecutionInput(...)}) and the call-site emitter ({@code $T.class} literal
 * at the {@code getContextArgument} call).
 *
 * <p>{@link #sites} carries every directive site that referenced this name in declaration order,
 * useful for downstream tooling (LSP fix-its) that wants to navigate to a declaring method or
 * carrier. Each entry is a {@link Site}, which wraps the model value the reference came from and
 * so stays with the classifier's output rather than riding a rejection: {@link ConflictSite}
 * carries the same coordinate as names, because a rejection is a fact the store holds.
 */
public record ResolvedContextArg(String name, TypeName javaType, List<Site> sites) {
    public ResolvedContextArg {
        sites = List.copyOf(sites);
    }

    /**
     * The directive coordinate a context-argument reference came from. Every arm projects the
     * class + method names a message or a fix-it reads, so a consumer need not switch on the arm.
     */
    public sealed interface Site permits Site.Method, Site.Carrier, Site.SessionMount {
        String className();
        String methodName();

        /** A {@link MethodRef}-backed coordinate ({@code @condition} / {@code @externalField}). */
        record Method(MethodRef ref) implements Site {
            @Override public String className() { return ref.className(); }
            @Override public String methodName() { return ref.methodName(); }
        }

        /** A {@link ServiceMethodCall}-carrier coordinate (a root sync {@code @service} permit). */
        record Carrier(ServiceMethodCall call) implements Site {
            @Override public String className() { return call.fqClassName(); }
            @Override public String methodName() { return call.methodName(); }
        }

        /**
         * The {@code <sessionState>} {@code <mount>} method's payload-parameter population, so a
         * mount-versus-{@code @service} type conflict names the {@code <mount>} element rather
         * than only the routine class the reference happens to resolve to.
         */
        record SessionMount(MethodRef ref) implements Site {
            @Override public String className() { return "<mount> " + ref.className(); }
            @Override public String methodName() { return ref.methodName(); }
        }
    }
}
