package no.sikt.graphitron.rewrite;

import java.util.ArrayList;
import java.util.List;

/**
 * Pre-resolved path expression on the right-hand side of an {@code argMapping} entry.
 *
 * <p>R53 binds Java parameters to GraphQL slots by single name; R84 generalises that to a dot-path
 * walking into nested input fields (e.g. {@code input.kvotesporsmalId}). The head names a slot at
 * the directive's scope (a GraphQL argument for {@code @service} / {@code @tableMethod} /
 * argument-level {@code @condition}; an input field for input-field-level {@code @condition});
 * each subsequent segment names a field on the resolved input-object type at that depth.
 *
 * <p>{@code PathExpr} is a sub-taxonomy of the value side of {@link ArgBindingMap#byJavaName()}.
 * The R53 case reduces to a single-segment {@link Head}; multi-segment overrides produce a
 * chain of {@link Step} on top of a {@link Head}. The {@code liftsList} flag on each {@code Step}
 * records whether the schema type at that depth was list-shaped, so the leaf Java type and the
 * runtime walking code are precomputed at classify time and the emitter never re-asks the GraphQL
 * schema (rewrite-design-principles "Generation-thinking").
 *
 * <p>Distinct information vs. a sibling: {@code Head} carries only a slot name; {@code Step}
 * carries the field name at a depth plus the {@code liftsList} flag. A flat {@code String} cannot
 * encode the per-step list-shape decision, so the carrier is required (rewrite-design-principles
 * "Sub-taxonomies for resolution outcomes").
 */
sealed interface PathExpr {

    /** The head segment name (the slot name at the directive's scope). */
    String headName();

    /** Returns the full segment chain, head first, depth-ordered. */
    List<Segment> segments();

    /** Renders the expression as it would appear in {@code argMapping} (e.g. {@code "input.kvotesporsmalId"}). */
    default String asString() {
        var segs = segments();
        if (segs.size() == 1) return segs.get(0).name();
        var sb = new StringBuilder(segs.get(0).name());
        for (int i = 1; i < segs.size(); i++) {
            sb.append('.').append(segs.get(i).name());
        }
        return sb.toString();
    }

    /** True for the bare {@link Head} case (no child segments). */
    default boolean isHead() {
        return segments().size() == 1;
    }

    /**
     * Single segment, naming a slot at the directive's scope.
     *
     * <p>This is the wire-compatible R53 shape: an {@code argMapping} entry whose right-hand side
     * is a bare name (no {@code .}) parses to {@code Head}; the existing identity entries for the
     * no-override case are also {@code Head}.
     */
    record Head(String name) implements PathExpr {
        @Override public String headName() { return name; }
        @Override public List<Segment> segments() { return List.of(new Segment(name, false)); }
        @Override public String asString() { return name; }
    }

    /**
     * One additional segment on top of a {@link PathExpr} parent. {@code fieldName} names a field
     * on the resolved input-object type at the parent's depth; {@code liftsList} records whether
     * the schema type at that depth was list-shaped (so the leaf Java type wraps in {@code List<>}
     * for each list-shaped intermediate).
     */
    record Step(PathExpr parent, String fieldName, boolean liftsList) implements PathExpr {
        @Override public String headName() { return parent.headName(); }
        @Override public List<Segment> segments() {
            var out = new ArrayList<Segment>(parent.segments());
            out.add(new Segment(fieldName, liftsList));
            return List.copyOf(out);
        }
    }

    /**
     * One segment in the chain. {@code liftsList} on the head segment is always {@code false}
     * (the head's list-shape is decided by the slot itself, which is handled at call-site
     * extraction time, not by path-walking). On non-head segments, {@code liftsList=true} means
     * the input-object field at that depth had a list type, and walking through it should
     * lift each subsequent value through {@code map}.
     */
    record Segment(String name, boolean liftsList) {}

    /** Builds a {@code Head} for the no-segment case. */
    static PathExpr head(String name) {
        return new Head(name);
    }

    /** Builds a {@code Step} chain by appending {@code fieldName} to {@code parent}. */
    static PathExpr step(PathExpr parent, String fieldName, boolean liftsList) {
        return new Step(parent, fieldName, liftsList);
    }
}
