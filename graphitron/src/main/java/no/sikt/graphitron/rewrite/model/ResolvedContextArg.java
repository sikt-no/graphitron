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
 * carrier. Each entry is a {@link ConflictSite.Site} (a {@link MethodRef}-backed or
 * {@link ServiceMethodCall}-carrier coordinate), the same sealed identifier {@link ConflictSite}
 * carries.
 */
public record ResolvedContextArg(String name, TypeName javaType, List<ConflictSite.Site> sites) {
    public ResolvedContextArg {
        sites = List.copyOf(sites);
    }
}
