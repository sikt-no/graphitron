package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.model.On;

/**
 * The proven-FK join fragments condition rendering composes: the keyed join of a reach-path hop
 * and the correlation {@code WHERE} tying the first hop's alias back to the outer row. These
 * moved here from the legacy join emitter when the condition glue renderer became their first
 * render-side consumer; {@code JoinPathEmitter} delegates to them, so the emission keeps one
 * derivation across migrated and unmigrated hosts. Every entry point takes a proven
 * {@link On.ColumnPairs} (the command's {@code FkHop} narrowing), never a raw {@code On}.
 */
public final class JoinFragments {

    private JoinFragments() {}

    /**
     * A keyed bridging join for chains walked terminal-first: the <em>previous</em> node's alias
     * is joined in ({@code .join(prev)}), with the ON dispatched on the pair's keying: a catalog
     * FK renders {@code .onKey(Keys.<CONSTANT>)}, a name-matched key renders explicit column
     * equalities so the reviewer sees the inferred pairing. Callers supply their own surrounding
     * whitespace.
     *
     * @param cp        the hop's column pairs
     * @param prevAlias the previous node's alias (the hop's origin side, being joined in)
     * @param hopAlias  the hop's own alias (already in scope in the enclosing FROM/JOIN chain)
     */
    public static CodeBlock emitBridgingJoin(On.ColumnPairs cp, String prevAlias, String hopAlias) {
        return emitKeyedJoin(cp, /*joinedAlias=*/prevAlias, prevAlias, hopAlias);
    }

    /**
     * Forward-order sibling of {@link #emitBridgingJoin} for chains emitted start-first: each
     * hop joins its <em>own</em> alias in ({@code .join(hop)}) with the same keying-dispatched ON.
     */
    public static CodeBlock emitForwardJoin(On.ColumnPairs cp, String prevAlias, String hopAlias) {
        return emitKeyedJoin(cp, /*joinedAlias=*/hopAlias, prevAlias, hopAlias);
    }

    private static CodeBlock emitKeyedJoin(On.ColumnPairs cp, String joinedAlias,
            String prevAlias, String hopAlias) {
        return switch (cp.keying()) {
            case On.Keying.ForeignKey k -> CodeBlock.of(".join($L).onKey($T.$L)",
                joinedAlias, k.fk().keysClass(), k.fk().constantName());
            case On.Keying.NameMatchedKey ignored -> {
                var on = CodeBlock.builder();
                int i = 0;
                for (var slot : cp.slots()) {
                    if (i > 0) on.add(".and(");
                    on.add("$L.$L.eq($L.$L)",
                        prevAlias, slot.sourceSide().javaName(),
                        hopAlias, slot.targetSide().javaName());
                    if (i > 0) on.add(")");
                    i++;
                }
                yield CodeBlock.of(".join($L).on($L)", joinedAlias, on.build());
            }
        };
    }

    /**
     * The correlation predicate tying a reach path's first hop back to the outer query:
     * {@code firstAlias.<target>.eq(parentAlias.<source>)} per column pair, ANDed. The
     * {@code sourceSide} is always the column on the source (parent) table and
     * {@code targetSide} the column on the target (first-hop) table, regardless of which end of
     * the catalog FK each maps to; slots are never empty ({@link On.ColumnPairs} rejects the
     * degenerate shape at construction).
     */
    public static CodeBlock emitCorrelationWhere(On.ColumnPairs first, String firstAlias,
            String parentAlias) {
        var code = CodeBlock.builder();
        int i = 0;
        for (var slot : first.slots()) {
            if (i > 0) code.add(".and(");
            code.add("$L.$L.eq($L.$L)",
                firstAlias, slot.targetSide().javaName(),
                parentAlias, slot.sourceSide().javaName());
            if (i > 0) code.add(")");
            i++;
        }
        return code.build();
    }
}
