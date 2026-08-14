package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Field;

import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;

/**
 * The columns a table declares, as the catalog census holds them, with the generated field's doc
 * comment overlaid from the java-source family through {@link SourceDeclarations}. One query over
 * {@code sql_column} joined to its table, in table-definition order.
 *
 * <p>Shared because completion and hover want the same rows of the same relations: completion offers
 * every column of the table and hover names one of them. What each surface keeps to itself is which
 * of the two spellings it matches on, which text it prefers when both a comment and a Javadoc exist,
 * and how the result reads, none of which is a fact.
 *
 * <p>A table name two schemas both declare contributes both column lists, in schema order. That is
 * what the census says: {@code sql_table} records every table every schema declares, and resolving
 * an unqualified name against them is a derivation the store deliberately leaves open.
 */
public final class CatalogColumns {

    private CatalogColumns() {}

    /**
     * Every column of every table this graph's census holds under {@code tableName}, matched
     * case-insensitively: the name comes from a directive an author typed rather than from the
     * database, and the database's own casing is not necessarily what they typed.
     */
    public static List<Column> of(StoreHandle store, String tableName) {
        // The generated field's Javadoc, on the .java cadence, keyed by the table class FQN the
        // catalog walk captured and the column constant's own name.
        Field<String> javadoc = SourceDeclarations.fieldJavadocOf(SQL_TABLE.CLASS_FQN, SQL_COLUMN.JOOQ_NAME);
        var rows = store.dsl()
            .select(SQL_COLUMN.TABLE_SCHEMA, SQL_COLUMN.TABLE_NAME, SQL_COLUMN.COLUMN_NAME,
                SQL_COLUMN.JOOQ_NAME, SQL_COLUMN.SQL_TYPE, SQL_COLUMN.BINDING_TYPE,
                SQL_COLUMN.NULLABLE, SQL_COLUMN.DESCRIPTION, javadoc)
            .from(SQL_COLUMN)
            .join(SQL_TABLE).on(SQL_TABLE.SOURCE_NAME.eq(SQL_COLUMN.SOURCE_NAME)
                .and(SQL_TABLE.TABLE_SCHEMA.eq(SQL_COLUMN.TABLE_SCHEMA))
                .and(SQL_TABLE.TABLE_NAME.eq(SQL_COLUMN.TABLE_NAME)))
            .where(store.reads(SQL_COLUMN.SOURCE_NAME))
            .and(SQL_COLUMN.TABLE_NAME.equalIgnoreCase(tableName))
            .orderBy(SQL_COLUMN.TABLE_SCHEMA, SQL_COLUMN.ORDINAL)
            .fetch();
        var columns = new ArrayList<Column>(rows.size());
        for (var row : rows) {
            columns.add(new Column(row.value1(), row.value2(), row.value3(), row.value4(),
                row.value5(), row.value6(), row.value7(),
                nullToEmpty(row.value8()), nullToEmpty(row.value9())));
        }
        return columns;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * One column as an editor surface needs it. Two names because the census carries two: the SQL
     * name is the coordinate a database has, the jOOQ name is what generated code spells, and a
     * directive may be written either way. Two types for the same reason: the type the database
     * declares and the type jOOQ binds it to are both facts about the column and neither derives
     * from the other.
     *
     * @param comment the database comment, or empty where the column has none
     * @param javadoc the generated field's doc comment, or empty where no source has been parsed
     */
    public record Column(
        String schema, String tableName, String columnName, String jooqName,
        String sqlType, String bindingType, boolean nullable, String comment, String javadoc
    ) {

        /** Whether {@code spelling} names this column under either of its two names. */
        public boolean isNamed(String spelling) {
            return jooqName.equalsIgnoreCase(spelling) || columnName.equalsIgnoreCase(spelling);
        }
    }
}
