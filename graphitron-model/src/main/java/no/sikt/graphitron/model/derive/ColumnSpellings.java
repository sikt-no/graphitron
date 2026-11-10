package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN_SPELLING;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

/**
 * The capture-cadence writer of {@code sql_column_spelling}: which columns of a table a written
 * name reaches, folded by case, the SQL name before the generated jOOQ one.
 *
 * <p>Which column a name means is a function of the catalog alone, so it is stated once here and the
 * rules that ask it join on the folded spelling instead of counting {@code sql_column} inline. A
 * spelling reaches every column whose SQL name it is; where no column of the table carries it as its
 * SQL name, it reaches every column whose generated name it is. The reach, how many columns that is,
 * is carried on each row, so a resolution (one) and an ambiguity (more) are read off the same rows
 * and a spelling no column answers is simply absent.
 *
 * <p>Runs as a stage of the catalog gatherer after {@link NameMatchedKeys}, over the columns it has
 * just flushed, and clears and re-derives the relation whole on that class's terms: it reads no graph,
 * no directive and no derivation, so two graphs sharing a store get the same rows from the same
 * tables.
 */
public final class ColumnSpellings {

    private ColumnSpellings() {}

    /** Clears and re-derives the catalog's column spellings; see the class javadoc. */
    public static void derive(DSLContext dsl) {
        dsl.deleteFrom(SQL_COLUMN_SPELLING).execute();

        var c = SQL_COLUMN.as("c");
        var m = SQL_COLUMN.as("m");
        var o = SQL_COLUMN.as("o");

        // By SQL name: every column, under its own folded name. The reach counts the columns of the
        // table folding to the same name, which is above one only where quoted names differ by case.
        dsl.insertInto(SQL_COLUMN_SPELLING)
            .columns(SQL_COLUMN_SPELLING.SOURCE_NAME, SQL_COLUMN_SPELLING.TABLE_SCHEMA,
                SQL_COLUMN_SPELLING.TABLE_NAME, SQL_COLUMN_SPELLING.SPELLING_UPPER,
                SQL_COLUMN_SPELLING.COLUMN_NAME, SQL_COLUMN_SPELLING.MATCHED_BY,
                SQL_COLUMN_SPELLING.REACH)
            .select(select(c.SOURCE_NAME, c.TABLE_SCHEMA, c.TABLE_NAME, c.COLUMN_NAME_UPPER,
                    c.COLUMN_NAME, inline("SQL_NAME"),
                    field(select(count()).from(m)
                        .where(m.SOURCE_NAME.eq(c.SOURCE_NAME))
                        .and(m.TABLE_SCHEMA.eq(c.TABLE_SCHEMA))
                        .and(m.TABLE_NAME.eq(c.TABLE_NAME))
                        .and(m.COLUMN_NAME_UPPER.eq(c.COLUMN_NAME_UPPER))))
                .from(c))
            .execute();

        // By generated name: only where no column of the table carries the spelling as its SQL
        // name, which also drops the common case of a generated name that is the SQL name folded.
        dsl.insertInto(SQL_COLUMN_SPELLING)
            .columns(SQL_COLUMN_SPELLING.SOURCE_NAME, SQL_COLUMN_SPELLING.TABLE_SCHEMA,
                SQL_COLUMN_SPELLING.TABLE_NAME, SQL_COLUMN_SPELLING.SPELLING_UPPER,
                SQL_COLUMN_SPELLING.COLUMN_NAME, SQL_COLUMN_SPELLING.MATCHED_BY,
                SQL_COLUMN_SPELLING.REACH)
            .select(select(c.SOURCE_NAME, c.TABLE_SCHEMA, c.TABLE_NAME, c.JOOQ_NAME_UPPER,
                    c.COLUMN_NAME, inline("JOOQ_NAME"),
                    field(select(count()).from(m)
                        .where(m.SOURCE_NAME.eq(c.SOURCE_NAME))
                        .and(m.TABLE_SCHEMA.eq(c.TABLE_SCHEMA))
                        .and(m.TABLE_NAME.eq(c.TABLE_NAME))
                        .and(m.JOOQ_NAME_UPPER.eq(c.JOOQ_NAME_UPPER))))
                .from(c)
                .where(notExists(selectOne().from(o)
                    .where(o.SOURCE_NAME.eq(c.SOURCE_NAME))
                    .and(o.TABLE_SCHEMA.eq(c.TABLE_SCHEMA))
                    .and(o.TABLE_NAME.eq(c.TABLE_NAME))
                    .and(o.COLUMN_NAME_UPPER.eq(c.JOOQ_NAME_UPPER)))))
            .execute();
    }
}
