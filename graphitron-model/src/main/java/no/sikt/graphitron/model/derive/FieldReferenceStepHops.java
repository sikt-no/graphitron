package no.sikt.graphitron.model.derive;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Select;

import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_STEP_HOP_KEYED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_STEP_HOP_KEYLESS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SPELLED_TABLE;
import static no.sikt.graphitron.model.Tables.INTENT_CONDITION_METHOD_ROUTE;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_NAME_MATCHED_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

/**
 * The capture-cadence writer of the two field-site hop relations: every table-to-table hop one
 * {@code @reference} path element could express, before anything decides which table the chain has
 * actually arrived at.
 *
 * <p>Two relations and not one, because the rows have two key shapes.
 * {@code graphitron_field_reference_step_hop_keyed} holds the hops a foreign key identifies, whose
 * identity takes in that key's name and its orientation;
 * {@code graphitron_field_reference_step_hop_keyless} holds the hops none identifies, where the
 * element coordinate with the departing and arriving triples is already total. The view over the
 * two carries the canonical name every reader spells, and what each column means is documented
 * there.
 *
 * <p>Four arms between them, one per authored form. A key element resolves its constraint name the
 * way the generator's resolver does: a leading qualifier, split off by capture and stored beside
 * the value, binds hard; an unqualified name matches the SQL constraint name; and only where no
 * SQL constraint in this graph's sources answers that name does the generated Keys-class constant
 * become eligible. A table element resolves its spelling and pins the arriving side to it, leaving
 * the foreign key to be discovered, or, where the departure is a function result that declares
 * none, reaches the arrival by matching its key columns' names. An element carrying a condition
 * and naming neither takes the route the condition method's own signature declares.
 *
 * <p>Each foreign-key arm is written in both orientations, as two statements where the rule this
 * replaces wrote one against a two-row literal: a key is a hop in either direction and which one
 * an element means depends on where the chain stands, so both are candidates and the walk narrows
 * them. A self-referential key is one hop and not two, both orientations landing on the same
 * table, which is what the reversed statements exclude.
 *
 * <p>Runs as two stages of the graphitron gatherer after the spellings they read and before the
 * field endpoints, every input being a captured fact or a plain view over captured facts by the
 * time they run.
 */
public final class FieldReferenceStepHops {

    private FieldReferenceStepHops() {}

    /** Reconciles the graph's foreign-key hops: clears the partition, then re-derives it. */
    public static void deriveKeyed(DSLContext dsl, String graphName) {
        var t = GRAPHITRON_FIELD_REFERENCE_STEP_HOP_KEYED;
        // Clear before insert. The relation carries no reading instant and a second capture of one
        // graph is the ordinary case, so appending would fail on the key rather than reconcile.
        dsl.deleteFrom(t).where(t.GRAPH_NAME.eq(graphName)).execute();
        insertKeyed(dsl, namedKey(dsl, graphName, true));
        insertKeyed(dsl, namedKey(dsl, graphName, false));
        insertKeyed(dsl, discoveredKey(dsl, graphName, true));
        insertKeyed(dsl, discoveredKey(dsl, graphName, false));
    }

    /** Reconciles the graph's keyless hops, on the same terms. */
    public static void deriveKeyless(DSLContext dsl, String graphName) {
        var t = GRAPHITRON_FIELD_REFERENCE_STEP_HOP_KEYLESS;
        dsl.deleteFrom(t).where(t.GRAPH_NAME.eq(graphName)).execute();
        insertKeyless(dsl, nameMatched(dsl, graphName));
        insertKeyless(dsl, conditionRouted(dsl, graphName));
    }

    private static void insertKeyed(DSLContext dsl, Select<? extends Record> rows) {
        var t = GRAPHITRON_FIELD_REFERENCE_STEP_HOP_KEYED;
        dsl.insertInto(t, List.of(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.ORDINAL, t.POSITION,
                t.VIA, t.KEY_MATCHED_BY, t.FROM_SOURCE_NAME, t.FROM_SCHEMA, t.FROM_TABLE,
                t.TO_SOURCE_NAME, t.TO_SCHEMA, t.TO_TABLE, t.CONSTRAINT_NAME, t.FK_ON_FROM))
            .select(rows)
            .execute();
    }

    private static void insertKeyless(DSLContext dsl, Select<? extends Record> rows) {
        var t = GRAPHITRON_FIELD_REFERENCE_STEP_HOP_KEYLESS;
        dsl.insertInto(t, List.of(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.ORDINAL, t.POSITION,
                t.VIA, t.FROM_SOURCE_NAME, t.FROM_SCHEMA, t.FROM_TABLE,
                t.TO_SOURCE_NAME, t.TO_SCHEMA, t.TO_TABLE))
            .select(rows)
            .execute();
    }

    /**
     * The {@code KEY} arm: the element names a constraint, and the hop is the pair that constraint
     * connects, read in one orientation.
     */
    private static Select<? extends Record> namedKey(DSLContext dsl, String graphName,
                                                     boolean fkOnFrom) {
        var s = GRAPHITRON_FIELD_REFERENCE_STEP_ENTRY;
        var m = STORE_GRAPH_SOURCE;
        var c = SQL_CONSTRAINT;
        var rc = SQL_REFERENTIAL_CONSTRAINT;
        var other = SQL_CONSTRAINT.as("c2");
        var otherSource = STORE_GRAPH_SOURCE.as("m2");

        // The resolver's namespace precedence, stated as one condition: a qualifier binds the
        // constraint's table schema and its name together; unqualified, the SQL name answers
        // first, and the generated constant is eligible only where no SQL name in this graph's
        // sources does.
        Condition matched = s.KEY_REF_NAMESPACE_PART.isNotNull()
            .and(c.TABLE_SCHEMA_UPPER.eq(s.KEY_REF_NAMESPACE_PART_UPPER))
            .and(c.CONSTRAINT_NAME_UPPER.eq(s.KEY_REF_NAME_PART_UPPER))
            .or(s.KEY_REF_NAMESPACE_PART.isNull()
                .and(c.CONSTRAINT_NAME_UPPER.eq(s.KEY_REF_NAME_PART_UPPER)
                    .or(c.JOOQ_NAME_UPPER.eq(s.KEY_REF_NAME_PART_UPPER)
                        .and(notExists(selectOne()
                            .from(other)
                            .join(otherSource).on(otherSource.SOURCE_NAME.eq(other.SOURCE_NAME))
                            .where(otherSource.GRAPH_NAME.eq(s.GRAPH_NAME))
                            .and(other.CONSTRAINT_NAME_UPPER
                                .eq(s.KEY_REF_NAME_PART_UPPER)))))));

        var select = dsl
            .select(s.GRAPH_NAME, s.TYPE_NAME, s.FIELD_NAME, s.ORDINAL, s.POSITION,
                inline("KEY"),
                when(c.CONSTRAINT_NAME_UPPER.eq(s.KEY_REF_NAME_PART_UPPER), inline("SQL_NAME"))
                    .otherwise(inline("JOOQ_NAME")),
                fkOnFrom ? rc.SOURCE_NAME : rc.REFERENCED_SOURCE_NAME,
                fkOnFrom ? rc.TABLE_SCHEMA : rc.REFERENCED_SCHEMA,
                fkOnFrom ? rc.TABLE_NAME : rc.REFERENCED_TABLE,
                fkOnFrom ? rc.REFERENCED_SOURCE_NAME : rc.SOURCE_NAME,
                fkOnFrom ? rc.REFERENCED_SCHEMA : rc.TABLE_SCHEMA,
                fkOnFrom ? rc.REFERENCED_TABLE : rc.TABLE_NAME,
                rc.CONSTRAINT_NAME, val(fkOnFrom))
            .from(s)
            .join(m).on(m.GRAPH_NAME.eq(s.GRAPH_NAME))
            .join(c).on(c.SOURCE_NAME.eq(m.SOURCE_NAME)).and(matched)
            .join(rc).on(rc.SOURCE_NAME.eq(c.SOURCE_NAME))
                .and(rc.TABLE_SCHEMA.eq(c.TABLE_SCHEMA))
                .and(rc.TABLE_NAME.eq(c.TABLE_NAME))
                .and(rc.CONSTRAINT_NAME.eq(c.CONSTRAINT_NAME))
            .where(s.GRAPH_NAME.eq(graphName))
            .and(s.KEY_REF.isNotNull());
        return fkOnFrom ? select : select.and(crossesTables(rc));
    }

    /**
     * The {@code TABLE} arm: the element names a table, which pins the arriving side, and every
     * foreign key connecting a table to it is a candidate route.
     */
    private static Select<? extends Record> discoveredKey(DSLContext dsl, String graphName,
                                                          boolean fkOnFrom) {
        var s = GRAPHITRON_FIELD_REFERENCE_STEP_ENTRY;
        var sp = GRAPHITRON_SPELLED_TABLE;
        var rc = SQL_REFERENTIAL_CONSTRAINT;

        Condition reaches = fkOnFrom
            ? rc.REFERENCED_SOURCE_NAME.eq(sp.TABLE_SOURCE_NAME)
                .and(rc.REFERENCED_SCHEMA.eq(sp.TABLE_SCHEMA))
                .and(rc.REFERENCED_TABLE.eq(sp.TABLE_NAME))
            : rc.SOURCE_NAME.eq(sp.TABLE_SOURCE_NAME)
                .and(rc.TABLE_SCHEMA.eq(sp.TABLE_SCHEMA))
                .and(rc.TABLE_NAME.eq(sp.TABLE_NAME));

        var select = dsl
            .select(s.GRAPH_NAME, s.TYPE_NAME, s.FIELD_NAME, s.ORDINAL, s.POSITION,
                inline("TABLE"), inline((String) null),
                fkOnFrom ? rc.SOURCE_NAME : rc.REFERENCED_SOURCE_NAME,
                fkOnFrom ? rc.TABLE_SCHEMA : rc.REFERENCED_SCHEMA,
                fkOnFrom ? rc.TABLE_NAME : rc.REFERENCED_TABLE,
                sp.TABLE_SOURCE_NAME, sp.TABLE_SCHEMA, sp.TABLE_NAME,
                rc.CONSTRAINT_NAME, val(fkOnFrom))
            .from(s)
            .join(sp).on(sp.GRAPH_NAME.eq(s.GRAPH_NAME)).and(sp.SPELLING.eq(s.TABLE_REF))
            .join(rc).on(reaches)
            .where(s.GRAPH_NAME.eq(graphName))
            .and(s.TABLE_REF.isNotNull())
            .and(s.KEY_REF.isNull());
        return fkOnFrom ? select : select.and(crossesTables(rc));
    }

    /**
     * The {@code NAME_MATCH} arm: the departure is a table-valued function's result, which
     * declares no constraint, so the route is the arriving table's key columns matched by name
     * against the columns the function exposes.
     */
    private static Select<? extends Record> nameMatched(DSLContext dsl, String graphName) {
        var s = GRAPHITRON_FIELD_REFERENCE_STEP_ENTRY;
        var sp = GRAPHITRON_SPELLED_TABLE;
        var m = STORE_GRAPH_SOURCE;
        var fn = SQL_TABLE.as("fn");
        var p = SQL_NAME_MATCHED_KEY_COLUMN;

        return dsl
            .select(s.GRAPH_NAME, s.TYPE_NAME, s.FIELD_NAME, s.ORDINAL, s.POSITION,
                inline("NAME_MATCH"),
                fn.SOURCE_NAME, fn.TABLE_SCHEMA, fn.TABLE_NAME,
                sp.TABLE_SOURCE_NAME, sp.TABLE_SCHEMA, sp.TABLE_NAME)
            .from(s)
            .join(sp).on(sp.GRAPH_NAME.eq(s.GRAPH_NAME)).and(sp.SPELLING.eq(s.TABLE_REF))
            .join(m).on(m.GRAPH_NAME.eq(s.GRAPH_NAME))
            .join(fn).on(fn.SOURCE_NAME.eq(m.SOURCE_NAME)).and(fn.TABLE_TYPE.eq("FUNCTION"))
            .where(s.GRAPH_NAME.eq(graphName))
            .and(s.TABLE_REF.isNotNull())
            .and(s.KEY_REF.isNull())
            // The pairing must come up total: the rule that decides that lives on the relation
            // this probes, a carrier's inferred hop reaching it from a coordinate authoring no
            // element at all.
            .and(exists(selectOne()
                .from(p)
                .where(p.SOURCE_NAME.eq(fn.SOURCE_NAME))
                .and(p.TABLE_SCHEMA.eq(fn.TABLE_SCHEMA))
                .and(p.TABLE_NAME.eq(fn.TABLE_NAME))
                .and(p.TO_SOURCE_NAME.eq(sp.TABLE_SOURCE_NAME))
                .and(p.TO_SCHEMA.eq(sp.TABLE_SCHEMA))
                .and(p.TO_TABLE.eq(sp.TABLE_NAME))));
    }

    /**
     * The {@code CONDITION} arm: the element names neither a key nor a table, so its route is the
     * one the condition method's own signature declares, read from the relation that states it
     * rather than restated here.
     */
    private static Select<? extends Record> conditionRouted(DSLContext dsl, String graphName) {
        var s = GRAPHITRON_FIELD_REFERENCE_STEP_ENTRY;
        var r = INTENT_CONDITION_METHOD_ROUTE;

        return dsl
            .select(s.GRAPH_NAME, s.TYPE_NAME, s.FIELD_NAME, s.ORDINAL, s.POSITION,
                inline("CONDITION"),
                r.FROM_SOURCE_NAME, r.FROM_SCHEMA, r.FROM_TABLE,
                r.TO_SOURCE_NAME, r.TO_SCHEMA, r.TO_TABLE)
            .from(s)
            .join(r).on(r.GRAPH_NAME.eq(s.GRAPH_NAME))
                .and(r.CLASS_NAME.eq(s.CLASS_NAME))
                .and(r.METHOD.eq(s.METHOD))
            .where(s.GRAPH_NAME.eq(graphName))
            .and(s.CLASS_NAME.isNotNull())
            .and(s.KEY_REF.isNull())
            .and(s.TABLE_REF.isNull());
    }

    /**
     * What the reversed orientation excludes: a self-referential key lands on the table it departs
     * from, so its two orientations are one hop and the reversed statement must not write it twice.
     */
    private static Condition crossesTables(
            no.sikt.graphitron.model.tables.SqlReferentialConstraint rc) {
        return rc.SOURCE_NAME.ne(rc.REFERENCED_SOURCE_NAME)
            .or(rc.TABLE_SCHEMA.ne(rc.REFERENCED_SCHEMA))
            .or(rc.TABLE_NAME.ne(rc.REFERENCED_TABLE));
    }
}
