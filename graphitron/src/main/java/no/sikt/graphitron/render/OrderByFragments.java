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
            parts.add("$L.$L.$L()", srcAlias, col.column().javaName(), col.direction().jooqMethodName());
        }
        return parts.build();
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
