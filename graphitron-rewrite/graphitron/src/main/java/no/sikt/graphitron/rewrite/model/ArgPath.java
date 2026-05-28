package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * Path into a GraphQL field's argument tree, used at data-bearing leaves
 * ({@link ValueShape.Scalar}, {@link ValueShape.ListOf}) to locate the SDL source for a value.
 *
 * <p>{@code outerArgName} is the head segment (the field-argument name); {@code deeperSegments}
 * are zero-or-more dotted segments below it. Multi-segment paths arise from default flattening
 * over input-object arguments and (when R249 lands) from the nested {@code @argMapping} syntax.
 *
 * <p>Paths live on data-bearing leaves rather than on composites: in default mapping every
 * sibling field's path shares a prefix, but in the nested-mapping form sibling paths are
 * independent, so the model represents both uniformly.
 */
public record ArgPath(String outerArgName, List<String> deeperSegments) {

    public ArgPath { deeperSegments = List.copyOf(deeperSegments); }

    /** Single-segment path (root argument). */
    public static ArgPath head(String outerArgName) {
        return new ArgPath(outerArgName, List.of());
    }

    /** Extend this path by appending {@code segment}. */
    public ArgPath append(String segment) {
        var next = new java.util.ArrayList<String>(deeperSegments.size() + 1);
        next.addAll(deeperSegments);
        next.add(segment);
        return new ArgPath(outerArgName, next);
    }
}
