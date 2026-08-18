package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

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
        return of(store, SQL_COLUMN.TABLE_NAME.equalIgnoreCase(tableName));
    }

    /**
     * The columns of one resolved table, by its whole census key. Where the spelling-keyed read
     * above answers with every table that spells a name, this one answers about the table a
     * resolution already picked, so an ambiguous name cannot widen the result behind the caller.
     */
    public static List<Column> of(StoreHandle store, CatalogTable table) {
        return of(store, keyOf(table));
    }

    /**
     * The columns of every table a binding resolved to, in schema then definition order. What a set
     * of keys says that a spelling cannot is which tables the binding actually reached: the
     * name-keyed read matches whatever spells the name anywhere in the census, and this one matches
     * what a resolution named and nothing else. Empty for an empty set, there being no table to
     * read rather than every table.
     */
    public static List<Column> of(StoreHandle store, List<CatalogTable> tables) {
        if (tables.isEmpty()) return List.of();
        return of(store, DSL.or(tables.stream().map(CatalogColumns::keyOf).toList()));
    }

    private static Condition keyOf(CatalogTable table) {
        return SQL_COLUMN.SOURCE_NAME.eq(table.sourceName())
            .and(SQL_COLUMN.TABLE_SCHEMA.eq(table.schema()))
            .and(SQL_COLUMN.TABLE_NAME.eq(table.tableName()));
    }

    private static List<Column> of(StoreHandle store, Condition tableFilter) {
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
            .and(tableFilter)
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
            return new Names(columnName, jooqName).isNamed(spelling);
        }
    }

    /**
     * A column's two names and nothing else, for a consumer that needs only to tell whether a
     * spelling reaches the column. Separate from {@link Column} so the two-spelling rule has one
     * statement while a projection selecting a whole column's facts stays the caller's choice rather
     * than the price of applying it: a surface checking a name against a table reads two columns per
     * row where a surface rendering one reads nine.
     */
    public record Names(String columnName, String jooqName) {

        /**
         * Whether {@code spelling} names this column. Case-insensitive on both names, and the rule
         * {@link Column#isNamed} is: a directive may spell either the SQL name or the one generated
         * code gives the field, and the generator's own resolution ignores case on both.
         */
        public boolean isNamed(String spelling) {
            return jooqName.equalsIgnoreCase(spelling) || columnName.equalsIgnoreCase(spelling);
        }
    }
}
