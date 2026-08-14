package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Condition;

import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;

/**
 * The foreign keys of the catalog census: one query over {@code sql_referential_constraint} joined
 * to the constraint it extends, which is where the generated {@code Keys} constant lives, and to the
 * declaring schema, which is where the class holding that constant lives.
 *
 * <p>Shared because completion, hover and goto-definition ask about the same rows from different
 * ends. Completion asks which keys touch a table, having a table and no name; hover asks which key
 * an author has named, having a name and no table; definition asks the same as hover and then wants
 * the declaration. All of them need the two endpoints and both spellings, so the filter is what
 * differs and the row is what does not.
 */
public final class CatalogKeys {

    private CatalogKeys() {}

    /**
     * The keys touching {@code table} in either direction: the ones it declares and the ones other
     * tables declare against it. One query rather than two, because a self-referencing key is a
     * single row satisfying both halves of the predicate and a union would yield it twice.
     *
     * <p>Both halves match the table's whole key, not its name. The caller has a table something
     * resolved rather than a spelling someone typed, so a key of a same-named table in another
     * schema is a different table's key and not a looser match on this one; the referenced side
     * carries its own three columns for exactly this, a cross-schema key being the case that
     * separates them.
     */
    public static List<Key> touching(StoreHandle store, CatalogTable table) {
        return read(store, SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME.eq(table.sourceName())
            .and(SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA.eq(table.schema()))
            .and(SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME.eq(table.tableName()))
            .or(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_SOURCE_NAME.eq(table.sourceName())
                .and(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_SCHEMA.eq(table.schema()))
                .and(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_TABLE.eq(table.tableName()))));
    }

    /**
     * The keys {@code spelling} names, matched the way the generator's own resolver matches so an
     * editor cannot describe a key the build would not accept: two namespaces, the SQL constraint
     * name and the generated {@code Keys} constant, both case-insensitive, and a leading
     * {@code schema.} qualifier that scopes to the declaring table's schema rather than widening the
     * set. The qualifier is split on the first dot, as the resolver splits it, and binds hard: a
     * name that exists under another schema is not an answer to a qualified spelling.
     *
     * <p>Plural, because a name need not be unique. One schema can declare the same constraint name
     * on two tables, and two schemas can each declare it once; which one an unqualified spelling
     * means is a resolution question, and the census answers with every key that spells it.
     */
    public static List<Key> named(StoreHandle store, String spelling) {
        int dot = spelling.indexOf('.');
        if (dot > 0 && dot < spelling.length() - 1) {
            return read(store, SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA
                .equalIgnoreCase(spelling.substring(0, dot))
                .and(SQL_REFERENTIAL_CONSTRAINT.CONSTRAINT_NAME
                    .equalIgnoreCase(spelling.substring(dot + 1))));
        }
        return read(store, SQL_REFERENTIAL_CONSTRAINT.CONSTRAINT_NAME.equalIgnoreCase(spelling)
            .or(SQL_CONSTRAINT.JOOQ_NAME.equalIgnoreCase(spelling)));
    }

    /**
     * Ordered by declaring schema then constraint name, which is stateable where the projection's
     * order was the generated {@code Tables} class's field order.
     *
     * <p>The schema join is an inner one and stays inner: the schema of a captured constraint is
     * always captured with it, {@code sql_schema} being written for every schema the census touches,
     * so a left join would only widen the shape to admit a row capture cannot write.
     */
    private static List<Key> read(StoreHandle store, Condition match) {
        var rows = store.dsl()
            .select(SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA, SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME,
                SQL_REFERENTIAL_CONSTRAINT.CONSTRAINT_NAME,
                SQL_REFERENTIAL_CONSTRAINT.REFERENCED_TABLE, SQL_CONSTRAINT.JOOQ_NAME,
                SQL_SCHEMA.KEYS_CLASS_FQN)
            .from(SQL_REFERENTIAL_CONSTRAINT)
            .join(SQL_CONSTRAINT).on(SQL_CONSTRAINT.SOURCE_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME)
                .and(SQL_CONSTRAINT.TABLE_SCHEMA.eq(SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA))
                .and(SQL_CONSTRAINT.TABLE_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME))
                .and(SQL_CONSTRAINT.CONSTRAINT_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.CONSTRAINT_NAME)))
            .join(SQL_SCHEMA).on(SQL_SCHEMA.SOURCE_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME)
                .and(SQL_SCHEMA.TABLE_SCHEMA.eq(SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA)))
            .where(store.reads(SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME))
            .and(match)
            .orderBy(SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA,
                SQL_REFERENTIAL_CONSTRAINT.CONSTRAINT_NAME, SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME)
            .fetch();
        var keys = new ArrayList<Key>(rows.size());
        for (var row : rows) {
            keys.add(new Key(row.value1(), row.value2(), row.value3(), row.value4(),
                nullToEmpty(row.value5()), nullToEmpty(row.value6())));
        }
        return keys;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * One foreign key: what an author can spell, and what it joins.
     *
     * @param constant the generated {@code Keys} constant, or empty where no {@code Keys} class
     *                 names the key. Empty is a fact rather than a gap: a generated model need not
     *                 carry a {@code Keys} class, and a key with no constant is one nobody can name
     *                 that way.
     * @param keysClassFqn the generated {@code Keys} class the constant is declared on, or empty
     *                 where the declaring schema has none. Per schema rather than per key, which is
     *                 why it comes from {@code sql_schema}: a multi-schema model gives each schema
     *                 its own {@code Keys} class in its own package, so the two spellings of a name
     *                 that collides across schemas are declared in two different classes. Empty
     *                 exactly when {@link #constant} is, both being facts about the same absent
     *                 class, but read from the relation that owns each rather than inferred.
     */
    public record Key(
        String schema, String table, String name, String referencedTable, String constant,
        String keysClassFqn
    ) {

        /**
         * Whether this key points away from {@code from} rather than at it. Within a set
         * {@link #touching} scoped to one table, the declaring end deciding it is enough: a key
         * that is not declared by that table is one declared against it, and a self-referencing key
         * is honestly both, which is what the outbound reading says.
         */
        public boolean outboundFrom(CatalogTable from) {
            return schema.equalsIgnoreCase(from.schema()) && table.equalsIgnoreCase(from.tableName());
        }
    }
}
