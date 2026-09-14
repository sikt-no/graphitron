package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_NAME_MATCHED_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;

/**
 * The capture-cadence writer of {@code sql_name_matched_key_column}: which table-valued function
 * results can be keyed to which tables by column name, and on which columns.
 *
 * <p>A function result declares no foreign key, so a join leaving one has no constraint to read and
 * the only rule available is the column name. This states the rule once, over the catalog alone, for
 * the two seats that leave a function result.
 *
 * <p>Runs as a stage of the catalog gatherer, after the tables, columns and keys it reads have
 * flushed. It reads nothing else: no graph, no directive, no derivation. That is why it is a
 * function of the store's catalog rather than of the run, and why clearing it whole and re-deriving
 * is right rather than merely convenient, on {@link FieldEndpoints}'s terms: two graphs sharing a
 * store get the same answer from the same tables, and a capture that happens to be the one running
 * is not a fact about the result.
 *
 * <p><b>Only complete keys are stated</b>, which is the whole of what this changes about the shape
 * it replaces. A key one of whose columns the function does not expose is not a key, so it is absent
 * rather than present-and-flagged, and the four consumers that demanded a zero shortfall now join
 * these rows instead. On the sakila example the relation holds four rows where the flagged form held
 * 324, of which 320 said only that some pair of tables nobody had asked to connect could not be.
 *
 * <p>What an author wrote that needed a key which is not here is a different fact, at that author's
 * coordinate, and belongs to whoever has one. A catalog cannot fail to connect two tables; it can
 * only not connect them, and almost no two tables in a database are connected this way.
 */
public final class NameMatchedKeys {

    private NameMatchedKeys() {}

    /** Clears and re-derives the catalog's name-matched keys; see the class javadoc. */
    public static void derive(DSLContext dsl) {
        dsl.deleteFrom(SQL_NAME_MATCHED_KEY_COLUMN).execute();

        var fn = SQL_TABLE.as("fn");
        var pk = SQL_PRIMARY_KEY;
        var kc = SQL_CONSTRAINT_COLUMN;
        var kcc = SQL_COLUMN.as("kcc");
        var fc = SQL_COLUMN.as("fc");
        var shortKc = SQL_CONSTRAINT_COLUMN.as("short_kc");
        var shortKcc = SQL_COLUMN.as("short_kcc");
        var shortFc = SQL_COLUMN.as("short_fc");

        // The arriving key's columns, each paired with the function's column of the same name. An
        // inner join where the shape this replaces left-joined: an unmatched column contributes no
        // row, so a key that is short of one contributes fewer rows than it has columns, which is
        // what the NOT EXISTS below reads to drop it whole.
        dsl.insertInto(SQL_NAME_MATCHED_KEY_COLUMN)
            .columns(SQL_NAME_MATCHED_KEY_COLUMN.SOURCE_NAME,
                SQL_NAME_MATCHED_KEY_COLUMN.TABLE_SCHEMA, SQL_NAME_MATCHED_KEY_COLUMN.TABLE_NAME,
                SQL_NAME_MATCHED_KEY_COLUMN.TO_SOURCE_NAME, SQL_NAME_MATCHED_KEY_COLUMN.TO_SCHEMA,
                SQL_NAME_MATCHED_KEY_COLUMN.TO_TABLE, SQL_NAME_MATCHED_KEY_COLUMN.POSITION,
                SQL_NAME_MATCHED_KEY_COLUMN.TO_COLUMN, SQL_NAME_MATCHED_KEY_COLUMN.COLUMN_NAME)
            .select(dsl
                .select(fn.SOURCE_NAME, fn.TABLE_SCHEMA, fn.TABLE_NAME,
                    pk.SOURCE_NAME, pk.TABLE_SCHEMA, pk.TABLE_NAME,
                    kc.POSITION, kc.COLUMN_NAME, fc.COLUMN_NAME)
                .from(fn)
                .crossJoin(pk)
                .join(kc).on(kc.SOURCE_NAME.eq(pk.SOURCE_NAME))
                    .and(kc.TABLE_SCHEMA.eq(pk.TABLE_SCHEMA))
                    .and(kc.TABLE_NAME.eq(pk.TABLE_NAME))
                    .and(kc.CONSTRAINT_NAME.eq(pk.CONSTRAINT_NAME))
                // The key column reaches its fold through the reference sql_constraint_column
                // already declares to sql_column, which is why this join is here and looks unneeded.
                .join(kcc).on(kcc.SOURCE_NAME.eq(kc.SOURCE_NAME))
                    .and(kcc.TABLE_SCHEMA.eq(kc.TABLE_SCHEMA))
                    .and(kcc.TABLE_NAME.eq(kc.TABLE_NAME))
                    .and(kcc.COLUMN_NAME.eq(kc.COLUMN_NAME))
                .join(fc).on(fc.SOURCE_NAME.eq(fn.SOURCE_NAME))
                    .and(fc.TABLE_SCHEMA.eq(fn.TABLE_SCHEMA))
                    .and(fc.TABLE_NAME.eq(fn.TABLE_NAME))
                    .and(fc.COLUMN_NAME_UPPER.eq(kcc.COLUMN_NAME_UPPER))
                .where(fn.TABLE_TYPE.eq("FUNCTION"))
                // Whole keys only. A key column the function does not expose joined to nothing
                // above, so this is what turns the missing row into a missing key rather than into
                // a key with a hole in it: no column of this key may be one the function lacks.
                .andNotExists(selectOne()
                    .from(shortKc)
                    .join(shortKcc).on(shortKcc.SOURCE_NAME.eq(shortKc.SOURCE_NAME))
                        .and(shortKcc.TABLE_SCHEMA.eq(shortKc.TABLE_SCHEMA))
                        .and(shortKcc.TABLE_NAME.eq(shortKc.TABLE_NAME))
                        .and(shortKcc.COLUMN_NAME.eq(shortKc.COLUMN_NAME))
                    .where(shortKc.SOURCE_NAME.eq(pk.SOURCE_NAME))
                    .and(shortKc.TABLE_SCHEMA.eq(pk.TABLE_SCHEMA))
                    .and(shortKc.TABLE_NAME.eq(pk.TABLE_NAME))
                    .and(shortKc.CONSTRAINT_NAME.eq(pk.CONSTRAINT_NAME))
                    .andNotExists(selectOne()
                        .from(shortFc)
                        .where(shortFc.SOURCE_NAME.eq(fn.SOURCE_NAME))
                        .and(shortFc.TABLE_SCHEMA.eq(fn.TABLE_SCHEMA))
                        .and(shortFc.TABLE_NAME.eq(fn.TABLE_NAME))
                        .and(shortFc.COLUMN_NAME_UPPER.eq(shortKcc.COLUMN_NAME_UPPER)))))
            .execute();
    }
}
