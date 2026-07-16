package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * Path into a GraphQL field's argument tree, used at data-bearing leaves
 * ({@link ValueShape.Scalar}, {@link ValueShape.ListOf}) to locate the SDL source for a value.
 *
 * <p>{@code outerArgName} is the head segment (the field-argument name); {@code deeperSegments}
 * are zero-or-more dotted {@link Segment}s below it. Each {@code Segment} carries its
 * {@code liftsList} flag from the schema: {@code true} means the input-object field at that
 * depth was list-shaped, so the emitter must stream-and-map at that step instead of stepping
 * through a plain {@code Map.get}. The head's list-shape is decided by the slot itself (handled
 * at call-site extraction time), so the {@code outerArgName} carries no list flag.
 *
 * <p>Multi-segment paths arise from default flattening over input-object arguments and
 * (eventually) from the nested {@code @argMapping} syntax. Paths live on data-bearing leaves
 * rather than on composites: in default mapping every sibling field's path shares a prefix, but
 * in the nested-mapping form sibling paths are independent, so the model represents both
 * uniformly.
 */
public record ArgPath(String outerArgName, List<Segment> deeperSegments) {

    public ArgPath { deeperSegments = List.copyOf(deeperSegments); }

    /** Single-segment path (root argument). */
    public static ArgPath head(String outerArgName) {
        return new ArgPath(outerArgName, List.of());
    }

    /**
     * Extend this path by appending a non-list-shaped {@code segment}. Used when descending
     * into input-object fields whose SDL type is not list-shaped.
     */
    public ArgPath append(String segment) {
        return append(segment, false);
    }

    /** Extend this path by appending {@code segment}, recording whether it lifts a list shape. */
    public ArgPath append(String segment, boolean liftsList) {
        var next = new java.util.ArrayList<Segment>(deeperSegments.size() + 1);
        next.addAll(deeperSegments);
        next.add(new Segment(segment, liftsList));
        return new ArgPath(outerArgName, next);
    }

    /** True when any segment below the head lifts a list shape. */
    public boolean hasListSegment() {
        for (Segment s : deeperSegments) {
            if (s.liftsList()) return true;
        }
        return false;
    }

    /**
     * One segment in the path chain. {@code liftsList=true} means the input-object field at this
     * depth had a list type; walking through it lifts each subsequent value through a stream.
     */
    public record Segment(String name, boolean liftsList) {}
}
