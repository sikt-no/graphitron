package no.sikt.graphitron.model.derive;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;

import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT_REACH;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

/**
 * The capture-cadence writer of {@code sql_referential_constraint_reach}: each way a foreign key is
 * ridden, and whether a row it leaves meets at most one row where it arrives.
 *
 * <p>Whether a hop along a key reaches one row is a function of the catalog alone, so it is stated
 * once here and the rules that ask it join on the key and the way they rode it instead of testing
 * the arriving columns against every key of the arriving table. A key ridden FORWARD arrives on the
 * columns of the key it references, BACKWARD on its own; either is to-one where those columns cover
 * every column of some primary or unique key of the table it arrives at.
 *
 * <p>Runs as a stage of the catalog gatherer after {@link ColumnSpellings}, over the constraints it
 * has just flushed, and clears and re-derives the relation whole on that class's terms: it reads no
 * graph, so two graphs sharing a store get the same rows from the same tables.
 */
public final class ReferentialReaches {

    private ReferentialReaches() {}

    /** Clears and re-derives the catalog's referential reaches; see the class javadoc. */
    public static void derive(DSLContext dsl) {
        dsl.deleteFrom(SQL_REFERENTIAL_CONSTRAINT_REACH).execute();
        var r = SQL_REFERENTIAL_CONSTRAINT.as("r");
        var t = SQL_REFERENTIAL_CONSTRAINT_REACH;

        // FORWARD: arriving on the referenced table, on the columns of the key the foreign key names.
        dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.CONSTRAINT_NAME, t.DIRECTION, t.TO_ONE)
            .select(select(r.SOURCE_NAME, r.TABLE_SCHEMA, r.TABLE_NAME, r.CONSTRAINT_NAME, inline("FORWARD"),
                    field(covers(r.REFERENCED_SOURCE_NAME, r.REFERENCED_SCHEMA, r.REFERENCED_TABLE,
                        r.REFERENCED_CONSTRAINT_NAME)))
                .from(r))
            .execute();

        // BACKWARD: arriving on the declaring table, on the foreign key's own columns.
        dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.CONSTRAINT_NAME, t.DIRECTION, t.TO_ONE)
            .select(select(r.SOURCE_NAME, r.TABLE_SCHEMA, r.TABLE_NAME, r.CONSTRAINT_NAME, inline("BACKWARD"),
                    field(covers(r.SOURCE_NAME, r.TABLE_SCHEMA, r.TABLE_NAME, r.CONSTRAINT_NAME)))
                .from(r))
            .execute();
    }

    /**
     * Whether the columns of the arriving constraint cover every column of some primary or unique key
     * of the table it is declared on, the table the hop arrives at.
     */
    private static Condition covers(Field<String> source, Field<String> schema, Field<String> table,
                                    Field<String> arriving) {
        var k = SQL_CONSTRAINT.as("k");
        var kc = SQL_CONSTRAINT_COLUMN.as("kc");
        var ac = SQL_CONSTRAINT_COLUMN.as("ac");
        return exists(selectOne().from(k)
            .where(k.SOURCE_NAME.eq(source), k.TABLE_SCHEMA.eq(schema), k.TABLE_NAME.eq(table),
                k.CONSTRAINT_TYPE.in("PRIMARY KEY", "UNIQUE"))
            .and(notExists(selectOne().from(kc)
                .where(kc.SOURCE_NAME.eq(k.SOURCE_NAME), kc.TABLE_SCHEMA.eq(k.TABLE_SCHEMA),
                    kc.TABLE_NAME.eq(k.TABLE_NAME), kc.CONSTRAINT_NAME.eq(k.CONSTRAINT_NAME))
                .and(notExists(selectOne().from(ac)
                    .where(ac.SOURCE_NAME.eq(source), ac.TABLE_SCHEMA.eq(schema), ac.TABLE_NAME.eq(table),
                        ac.CONSTRAINT_NAME.eq(arriving), ac.COLUMN_NAME.eq(kc.COLUMN_NAME)))))));
    }
}
