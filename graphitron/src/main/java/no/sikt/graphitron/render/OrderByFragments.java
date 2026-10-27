package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.model.OrderBySpec;

/**
 * The shared fixed-ordering fragments: the comma-joined sort-field parts
 * ({@code alias.COL.asc(), alias.OTHER.desc()}) and the plain column parts
 * ({@code alias.COL, alias.OTHER}) a statically resolved {@link OrderBySpec.Fixed} renders to.
 * One derivation across the launcher renderer and the legacy fetcher hosts (the fetcher
 * generator's orderBy emission delegates here), so the ORDER BY fragment cannot fork during the
 * migration window.
 *
 * <p>Every declared-column sort term renders through {@link #sortTerm}, which owns the null
 * placement rule: a column that admits {@code NULL} sorts {@code NULLS LAST} in either direction,
 * and a {@code NOT NULL} column states no placement, which keeps jOOQ's row-value seek.
 */
public final class OrderByFragments {

    private OrderByFragments() {}

    /** {@code <alias>.<COL>.<asc|desc>(), ...} for each entry, in spec order. */
    public static CodeBlock fixedSortParts(OrderBySpec.Fixed fixed, String srcAlias) {
        var parts = CodeBlock.builder();
        var entries = fixed.columns();
        for (int i = 0; i < entries.size(); i++) {
            var col = entries.get(i);
            if (i > 0) {
                parts.add(", ");
            }
            parts.add(sortTerm(CodeBlock.of("$L.$L", srcAlias, col.column().javaName()),
                col.direction(), col.nullable(), null));
        }
        return parts.build();
    }

    /**
     * One sort term over {@code expr}: {@code <expr>.<asc|desc>()}, followed by {@code .nullsLast()}
     * when {@code nullable}. With a non-null {@code flip}, the runtime-descending condition of an
     * order a request's {@code direction:} reverses, it renders
     * {@code <flip> ? <expr>.desc() : <expr>.asc()} instead, {@code direction} unused, with
     * {@code .nullsLast()} on both arms when {@code nullable}, so the {@code NULL} rows stay last
     * whichever way the request sorts.
     */
    public static CodeBlock sortTerm(CodeBlock expr, OrderBySpec.SortDirection direction, boolean nullable,
                                     CodeBlock flip) {
        String nulls = nullable ? ".nullsLast()" : "";
        if (flip == null) {
            return CodeBlock.of("$L.$L()$L", expr, direction.jooqMethodName(), nulls);
        }
        return CodeBlock.of("$L ? $L.desc()$L : $L.asc()$L", flip, expr, nulls, expr, nulls);
    }

    /** {@code <alias>.<COL>, ...} for each entry, in spec order (the cursor-column view). */
    public static CodeBlock fixedColumnParts(OrderBySpec.Fixed fixed, String srcAlias) {
        var parts = CodeBlock.builder();
        var entries = fixed.columns();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                parts.add(", ");
            }
            parts.add("$L.$L", srcAlias, entries.get(i).column().javaName());
        }
        return parts.build();
    }
}
