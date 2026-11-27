package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import no.sikt.graphitron.emit.RootQuery.Derived;
import no.sikt.graphitron.emit.RootQuery.Key;
import no.sikt.graphitron.emit.RootQuery.Pair;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.render.ColumnComparison;

import java.util.ArrayList;
import java.util.List;

/**
 * How one key of an order is rendered over the table local {@code table} names: the value it sorts by,
 * its sort term, and the cursor column a connection reads it back from. A key reading the table's own
 * column is that column; a key down a path is one correlated scalar subselect over its hops, each to-one;
 * a key a consumer's method computes is that method called with the table. A key's collation applies
 * to what it sorts by, not to the value its cursor reads back.
 */
public final class OrderKeys {

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");

    private OrderKeys() {}

    /**
     * The alias a derived key's value is selected under, so the selection merge, which dedupes by
     * {@code Field.getName()}, keeps it beside a selected column of the same name and the cursor reads
     * exactly the value the order sorts on. Keyed by the key's ordinal among its field's derived keys.
     */
    public static String cursorAlias(int ordinal) {
        return "__sk_" + ordinal;
    }

    /** The value {@code key} sorts by over {@code table}. */
    public static CodeBlock expression(Key key, String table) {
        return switch (key.derived()) {
            case null -> CodeBlock.of("$L.$L", table, key.column().javaName());
            case Derived.Path path -> pathValue(path, key, table);
            case Derived.Method method -> CodeBlock.of("$T.$L($L)", CatalogRefs.className(method.className()),
                method.methodName(), table);
        };
    }

    /**
     * {@code <expr>.<asc|desc>()}, or {@code <flip> ? <expr>.desc() : <expr>.asc()} where {@code flip} is the
     * runtime-descending condition of an order a request's {@code direction:} reverses, {@code <expr>}
     * collated where the key states a collation. A key the store states may be {@code NULL} sorts
     * {@code NULLS LAST} either way.
     */
    public static CodeBlock sortTerm(Key key, String table, CodeBlock flip) {
        var read = expression(key, table);
        var expr = key.collation() == null ? read : CodeBlock.of("$L.collate($S)", read, key.collation());
        String nulls = key.nullable() ? ".nullsLast()" : "";
        if (flip == null) {
            return CodeBlock.of("$L.$L()$L", expr, "DESC".equals(key.direction()) ? "desc" : "asc", nulls);
        }
        return CodeBlock.of("$L ? $L.desc()$L : $L.asc()$L", flip, expr, nulls, expr, nulls);
    }

    /** The key's cursor column: the column, or a derived key's value under {@link #cursorAlias}. */
    public static CodeBlock cursorColumn(Key key, String table, int ordinal) {
        return key.derived() == null
            ? expression(key, table)
            : CodeBlock.of("$L.as($S)", expression(key, table), cursorAlias(ordinal));
    }

    /**
     * {@code DSL.field(DSL.select(<last>.<COL>).from(<first>).join(...).on(...).where(<first> to the row))}:
     * each hop aliased after the row's own alias, so it meets no alias of the calling query.
     */
    private static CodeBlock pathValue(Derived.Path path, Key key, String table) {
        var hops = path.hops();
        if (hops.isEmpty()) {
            throw new IllegalStateException("Graphitron generator bug (order key): the path key reading "
                + key.column().javaName() + " has no hops; the store states a path key only where its path lands");
        }
        var aliases = new ArrayList<String>();
        for (int i = 0; i < hops.size(); i++) {
            var hop = hops.get(i);
            aliases.add(CodeBlock.of("$T.$L.as($L.getName() + $S)", CatalogRefs.constantsClass(hop.target()),
                hop.target().javaFieldName(), table, "_k" + i).toString());
        }
        var select = CodeBlock.builder().add("$T.field($T.select($L.$L).from($L)", DSL, DSL,
            aliases.getLast(), key.column().javaName(), aliases.getFirst());
        for (int i = 1; i < hops.size(); i++) {
            select.add(".join($L).on($L)", aliases.get(i), pairs(hops.get(i).pairs(), aliases.get(i - 1), aliases.get(i)));
        }
        return select.add(".where($L))", pairs(hops.getFirst().pairs(), table, aliases.getFirst())).build();
    }

    /**
     * Each pair's column on the side a hop leaves equal to its column on the side it reaches, the two
     * reconciled where a converter has made their Java types differ.
     */
    private static CodeBlock pairs(List<Pair> pairs, String left, String right) {
        var parts = new ArrayList<CodeBlock>();
        for (var pair : pairs) {
            parts.add(ColumnComparison.equality(left, pair.from(), right, pair.to()));
        }
        return CodeBlock.join(parts, ", ");
    }
}
