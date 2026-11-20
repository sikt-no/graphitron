package no.sikt.graphitron.rewrite.model;

import com.palantir.javapoet.TypeName;

import java.util.List;

/**
 * One request-context key the graph's calls read, resolved to the single Java type they read it
 * as.
 *
 * <p>Produced by {@code ContextArgumentClassifier} off the keys the store states every call reads.
 * The type is stored on {@link #javaType} and consumed verbatim by the factory emitter (parameter
 * list of {@code Graphitron.newExecutionInput(...)}).
 *
 * <p>{@link #sites} carries every call that reads this key, useful for downstream tooling (LSP
 * fix-its) that wants to navigate to a declaring method.
 */
public record ResolvedContextArg(String name, TypeName javaType, List<Site> sites) {
    public ResolvedContextArg {
        sites = List.copyOf(sites);
    }

    /** The method a call reading the key makes, as the store states it. */
    public record Site(String className, String methodName) {}
}
