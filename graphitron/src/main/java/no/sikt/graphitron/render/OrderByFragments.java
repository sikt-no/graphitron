package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.model.OrderBySpec;

import java.util.function.IntFunction;

/**
 * The shared fixed-ordering fragments: the comma-joined sort-field parts
 * ({@code alias.COL.asc(), alias.OTHER.desc()}) and the plain column parts
 * ({@code alias.COL, alias.OTHER}) a statically resolved {@link OrderBySpec.Fixed} renders to.
 * One derivation across the launcher renderer and the legacy fetcher hosts (the fetcher
 * generator's orderBy emission delegates here), so the ORDER BY fragment cannot fork during the
 * migration window.
 *
 * <p>A sort key the field's table does not hold renders through {@link SortKeyFragments} as one
 * call expression, in the sort view as the call and in the cursor view under its reserved alias.
 *
 * <p>Every declared-column sort term renders through {@link #sortTerm}, which owns the null
 * placement rule: a column that admits {@code NULL} sorts {@code NULLS LAST} in either direction,
 * and a {@code NOT NULL} column states no placement, which keeps jOOQ's row-value seek.
 */
public final class OrderByFragments {

    private OrderByFragments() {}

    /**
     * {@code <alias>.<COL>.<asc|desc>(), ...} for each entry, in spec order. A sort-key entry
     * contributes its call ({@link SortKeyFragments#call}); {@code sortKeyMethods} names the
     * emitted method of each {@code path:} entry's ordinal.
     */
    public static CodeBlock fixedSortParts(OrderBySpec.Fixed fixed, String srcAlias, IntFunction<String> sortKeyMethods) {
        var parts = CodeBlock.builder();
        var entries = fixed.entries();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            if (i > 0) {
                parts.add(", ");
            }
            parts.add(sortTerm(sortExpression(entry, srcAlias, sortKeyMethods),
                entry.direction(), entry.nullable(), null));
        }
        return parts.build();
    }

    /**
     * The value an entry sorts by over {@code srcAlias}: the column, or a sort key's call. The
     * order and the seek both read this form; a sort key's cursor column is
     * {@link #cursorColumn}, because PostgreSQL does not resolve an output alias inside
     * {@code WHERE}.
     */
    public static CodeBlock sortExpression(OrderBySpec.OrderEntry entry, String srcAlias, IntFunction<String> sortKeyMethods) {
        return switch (entry) {
            case OrderBySpec.ColumnOrderEntry column -> CodeBlock.of("$L.$L", srcAlias, column.column().javaName());
            case OrderBySpec.SortKeyEntry key -> SortKeyFragments.call(key, srcAlias, sortKeyMethods);
        };
    }

    /** The entry's cursor column: the column, or a sort key's call under its reserved alias. */
    public static CodeBlock cursorColumn(OrderBySpec.OrderEntry entry, String srcAlias, IntFunction<String> sortKeyMethods) {
        return switch (entry) {
            case OrderBySpec.ColumnOrderEntry column -> CodeBlock.of("$L.$L", srcAlias, column.column().javaName());
            case OrderBySpec.SortKeyEntry key -> SortKeyFragments.cursorColumn(key, srcAlias, sortKeyMethods);
        };
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
    public static CodeBlock fixedColumnParts(OrderBySpec.Fixed fixed, String srcAlias, IntFunction<String> sortKeyMethods) {
        var parts = CodeBlock.builder();
        var entries = fixed.entries();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                parts.add(", ");
            }
            parts.add(cursorColumn(entries.get(i), srcAlias, sortKeyMethods));
        }
        return parts.build();
    }
}
