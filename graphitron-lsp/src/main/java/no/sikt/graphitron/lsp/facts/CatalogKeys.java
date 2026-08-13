package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Condition;

import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;

/**
 * The foreign keys of the catalog census: one query over {@code sql_referential_constraint} joined
 * to the constraint it extends, which is where the generated {@code Keys} constant lives.
 *
 * <p>Shared because completion and hover ask about the same rows from opposite ends. Completion asks
 * which keys touch a table, having a table and no name; hover asks which key an author has named,
 * having a name and no table. Both need the two endpoints and both spellings, so the filter is what
 * differs and the row is what does not.
 */
public final class CatalogKeys {

    private CatalogKeys() {}

    /**
     * The keys touching {@code tableName} in either direction: the ones it declares and the ones
     * other tables declare against it. One query rather than two, because a self-referencing key is
     * a single row satisfying both halves of the predicate and a union would yield it twice.
     */
    public static List<Key> touching(StoreHandle store, String tableName) {
        return read(store, SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME.equalIgnoreCase(tableName)
            .or(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_TABLE.equalIgnoreCase(tableName)));
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
     */
    private static List<Key> read(StoreHandle store, Condition match) {
        var rows = store.dsl()
            .select(SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA, SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME,
                SQL_REFERENTIAL_CONSTRAINT.CONSTRAINT_NAME,
                SQL_REFERENTIAL_CONSTRAINT.REFERENCED_TABLE, SQL_CONSTRAINT.JOOQ_NAME)
            .from(SQL_REFERENTIAL_CONSTRAINT)
            .join(SQL_CONSTRAINT).on(SQL_CONSTRAINT.SOURCE_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME)
                .and(SQL_CONSTRAINT.TABLE_SCHEMA.eq(SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA))
                .and(SQL_CONSTRAINT.TABLE_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME))
                .and(SQL_CONSTRAINT.CONSTRAINT_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.CONSTRAINT_NAME)))
            .where(store.reads(SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME))
            .and(match)
            .orderBy(SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA,
                SQL_REFERENTIAL_CONSTRAINT.CONSTRAINT_NAME, SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME)
            .fetch();
        var keys = new ArrayList<Key>(rows.size());
        for (var row : rows) {
            keys.add(new Key(row.value1(), row.value2(), row.value3(), row.value4(),
                row.value5() == null ? "" : row.value5()));
        }
        return keys;
    }

    /**
     * One foreign key: what an author can spell, and what it joins.
     *
     * @param constant the generated {@code Keys} constant, or empty where no {@code Keys} class
     *                 names the key. Empty is a fact rather than a gap: a generated model need not
     *                 carry a {@code Keys} class, and a key with no constant is one nobody can name
     *                 that way.
     */
    public record Key(
        String schema, String table, String name, String referencedTable, String constant
    ) {

        /** Whether this key points away from {@code tableName} rather than at it. */
        public boolean outboundFrom(String tableName) {
            return table.equalsIgnoreCase(tableName);
        }
    }
}
