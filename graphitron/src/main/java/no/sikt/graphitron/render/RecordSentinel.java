package no.sikt.graphitron.render;

import no.sikt.graphitron.command.CatalogColumn;
import no.sikt.graphitron.command.CatalogTable;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;

import java.util.List;

/**
 * The non-null, all-null-column record a record-carrier fetcher returns from its {@code catch}
 * arm. graphql-java's object completion short-circuits a null parent's children, so a carrier
 * whose write threw must still hand back something shaped like its key record: the data field
 * renders null off the all-null columns, and the errors field reads the routed throwable out of
 * {@code localContext}.
 *
 * <p>The record is structurally valid and wholly null, so the data field's key-restricted SELECT
 * short-circuits to no row (jOOQ resolves {@code WHERE key = null} to none) while the carrier
 * itself stays traversable. The columns are projected as the target table's own key fields rather
 * than as bare names, so the carried record and the data field that reads its correlation back
 * agree by field identity rather than by jOOQ's name-lookup fallback. Construction touches no
 * connection: {@link org.jooq.SQLDialect#DEFAULT} keeps it pure.
 *
 * <p>One derivation, read by the renderers on the command seam and by the unmigrated DML carrier
 * hosts alike.
 */
public final class RecordSentinel {

    private RecordSentinel() {}

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName SQL_DIALECT = ClassName.get("org.jooq", "SQLDialect");

    /** {@code DSL.using(SQLDialect.DEFAULT).newRecord(<key fields>)} — the single-arity source. */
    public static CodeBlock single(TableRef table, List<ColumnRef> keyColumns) {
        return sentinel("newRecord", table, keyColumns);
    }

    /**
     * {@code DSL.using(SQLDialect.DEFAULT).newResult(<key fields>)} — the many-arity source. The
     * empty {@code Result} feeds the data fetcher, which projects no rows and renders the SDL data
     * field as an empty list.
     */
    public static CodeBlock bulk(TableRef table, List<ColumnRef> keyColumns) {
        return sentinel("newResult", table, keyColumns);
    }

    /** {@link #single(TableRef, List)} for a caller whose table arrived as a command row. */
    public static CodeBlock single(CatalogTable table, List<CatalogColumn> keyColumns) {
        return sentinel("newRecord", table, keyColumns);
    }

    /** {@link #bulk(TableRef, List)} for a caller whose table arrived as a command row. */
    public static CodeBlock bulk(CatalogTable table, List<CatalogColumn> keyColumns) {
        return sentinel("newResult", table, keyColumns);
    }

    private static CodeBlock sentinel(String factory, CatalogTable table,
            List<CatalogColumn> keyColumns) {
        var b = CodeBlock.builder().add("$T.using($T.DEFAULT).$L(", DSL, SQL_DIALECT, factory);
        for (int i = 0; i < keyColumns.size(); i++) {
            if (i > 0) b.add(", ");
            b.add(CatalogRefs.constantColumn(table, keyColumns.get(i)));
        }
        b.add(")");
        return b.build();
    }

    private static CodeBlock sentinel(String factory, TableRef table, List<ColumnRef> keyColumns) {
        var b = CodeBlock.builder().add("$T.using($T.DEFAULT).$L(", DSL, SQL_DIALECT, factory);
        for (int i = 0; i < keyColumns.size(); i++) {
            if (i > 0) b.add(", ");
            b.add("$T.$L.$L", CatalogRefs.constantsClass(table), table.javaFieldName(), keyColumns.get(i).javaName());
        }
        b.add(")");
        return b.build();
    }
}
