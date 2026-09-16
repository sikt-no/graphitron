package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.tables.GraphitronFieldChainLink;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.CODE_CONDITION_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_CONDITION_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_REFERENCE_CONDITION_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_REFERENCE_FOR_CONDITION_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_REFERENCE_FOR_KEY_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_REFERENCE_KEY_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_REFERENCE_FOR_TABLE_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_REFERENCE_TABLE_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.SQL_NAME_MATCHED_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_CHAIN_LINK;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TABLE_LINK;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE_ENTRY;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static org.jooq.impl.DSL.castNull;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.partitionBy;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

/**
 * The capture-cadence writer of {@code graphitron_field_table_link}: where each link of a field's
 * chain departs and arrives, toward each target the field has.
 *
 * <p>Called as a stage of the graphitron gatherer after {@link FieldRoutines}, whose rows the
 * routine arm reads and whose target key this relation shares. The order it resolves against is the
 * anchor the document capture wrote, which states once at the coordinate that a chain has a link
 * here and where it was written.
 *
 * <p>Resolved in position order rather than all at once, because a link's departure is the previous
 * link's arrival. That sequence is what collapses the candidacy a read-time rule has to carry: a
 * named foreign key is a hop in either direction, and which one an element means is only answerable
 * where the chain stands, so the rule stated both orientations as rows and left a walk to narrow
 * them. Here the departure is known before the element is read and the orientation is a consequence
 * of it.
 *
 * <p>One pass per position and not a recursive term, expanding the way
 * {@link InputOccurrencePaths} does, and with the easier job of the two: the chain's own length is
 * stated, so the passes are counted from it rather than run until one comes up empty. A pass that
 * inserts nothing is therefore not a stop condition, which matters because a {@code @routine} link
 * resolves without a departure and can follow an element that did not resolve.
 *
 * <p>Marked and swept, on {@link FieldEndpoints}' terms, and the instant does a second job here: a
 * pass reads the rows the pass before it wrote, so the departure is matched on this reading's
 * instant. That is {@code AstEntries}' rule for resolving one arm against another, and without it a
 * link would be free to depart from where a previous reading arrived.
 *
 * <p>One statement per arm, five of them, and no arm ranks against another. Two boundaries are
 * structural: a link is a directive application or an element of a path and never both, which
 * separates the routine arm from the rest, and a function result is exactly the departure that
 * declares no foreign key, which is what separates the name-matched arm from the table arm. The
 * other two are authored and each is stated once, in {@link #namesNoKey} and {@link #namesNoTable}:
 * a key is a route and a table beside it is an assertion about where that route ends, so an element
 * naming a key is the key arm's, and only an element naming neither has its route in a condition's
 * signature. The primary key is what holds all five to those boundaries, a second arm answering for
 * one link being a duplicate rather than a preference to settle.
 *
 * <p>Each arm reads its two element shapes as one relation, the one written under
 * {@code @reference} and the one under {@code @referenceFor} being one fact with one shape, which
 * is {@code SdlAnchor}'s reason for unioning the five its element anchor reads.
 *
 * <p>Each arm demands a single candidate, on {@code graphitron_tabletype}'s terms, counted over one
 * link toward one target: two links of a chain are two questions and neither makes the other
 * ambiguous. A link that resolves to nothing, or to several things, draws no row, and that is
 * visible rather than silent because the chain's length is one join away.
 */
public final class FieldTableLinks {

    private FieldTableLinks() {}

    private static final String TYPE_NAME = "type_name";
    private static final String FIELD_NAME = "field_name";
    private static final String TARGET_SOURCE_NAME = "target_source_name";
    private static final String TARGET_SCHEMA = "target_schema";
    private static final String TARGET_TABLE = "target_table";
    private static final String FROM_SOURCE_NAME = "from_source_name";
    private static final String FROM_SCHEMA = "from_schema";
    private static final String FROM_TABLE = "from_table";
    private static final String TO_SOURCE_NAME = "to_source_name";
    private static final String TO_SCHEMA = "to_schema";
    private static final String TO_TABLE = "to_table";
    private static final String KEY_MATCHED_BY = "key_matched_by";
    private static final String CONSTRAINT_SOURCE_NAME = "constraint_source_name";
    private static final String CONSTRAINT_SCHEMA = "constraint_schema";
    private static final String CONSTRAINT_TABLE = "constraint_table";
    private static final String CONSTRAINT_NAME = "constraint_name";
    private static final String FK_ON_FROM = "fk_on_from";
    private static final String NAMESPACE_UPPER = "namespace_upper";
    private static final String NAME_UPPER = "name_upper";
    private static final String SITE_NAME = "site_name";
    private static final String SITE_LINE = "site_line";
    private static final String SITE_COLUMN = "site_column";
    private static final String CLASS_NAME = "class_name";
    private static final String METHOD_NAME = "method_name";
    private static final String CANDIDATES = "candidates";

    /** Re-derives the graph's resolved chain links; see the class javadoc. */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        Integer last = dsl.select(max(GRAPHITRON_FIELD_CHAIN_LINK.POSITION))
            .from(GRAPHITRON_FIELD_CHAIN_LINK)
            .where(GRAPHITRON_FIELD_CHAIN_LINK.GRAPH_NAME.eq(graphName))
            .fetchOne(0, Integer.class);
        for (int position = 0; last != null && position <= last; position++) {
            routines(dsl, graphName, position, touchedAt);
            keys(dsl, graphName, position, touchedAt);
            tables(dsl, graphName, position, touchedAt);
            nameMatches(dsl, graphName, position, touchedAt);
            conditions(dsl, graphName, position, touchedAt);
        }
        // After every pass, because a link at position three is a link this reading resolved as
        // much as one at position zero.
        dsl.deleteFrom(GRAPHITRON_FIELD_TABLE_LINK)
            .where(GRAPHITRON_FIELD_TABLE_LINK.GRAPH_NAME.eq(graphName))
            .and(GRAPHITRON_FIELD_TABLE_LINK.TOUCHED_AT.ne(touchedAt))
            .execute();
    }

    /**
     * The routine arm at one position: a link that is a {@code @routine} application arrives at the
     * function result, which {@link FieldRoutines} resolved per target and per application.
     *
     * <p>It departs from nothing, whatever its position. A function result is where a chain's rows
     * begin, so the link consumes no departure and needs none to resolve, which is also why this
     * arm reads no previous row.
     *
     * <p>The application is found by the written position the link points at, which is why an
     * ordinal and a position never have to be compared: two relations number one field's
     * applications independently, and the written position is what both agree on.
     */
    private static void routines(DSLContext dsl, String graph, int position,
                                 LocalDateTime touchedAt) {
        var cl = GRAPHITRON_FIELD_CHAIN_LINK;
        var re = GRAPHITRON_ROUTINE_ENTRY;
        var fr = GRAPHITRON_FIELD_ROUTINE;
        var t = GRAPHITRON_FIELD_TABLE_LINK;

        var resolved = dsl
            .select(cl.TYPE_NAME.as(TYPE_NAME), cl.FIELD_NAME.as(FIELD_NAME),
                fr.TO_SOURCE_NAME.as(TARGET_SOURCE_NAME), fr.TO_SCHEMA.as(TARGET_SCHEMA),
                fr.TO_TABLE.as(TARGET_TABLE),
                fr.RESULT_SOURCE_NAME.as(TO_SOURCE_NAME), fr.RESULT_SCHEMA.as(TO_SCHEMA),
                fr.RESULT_TABLE.as(TO_TABLE),
                count().over(partitionBy(cl.TYPE_NAME, cl.FIELD_NAME,
                    fr.TO_SOURCE_NAME, fr.TO_SCHEMA, fr.TO_TABLE)).as(CANDIDATES))
            .from(cl)
            .join(re).on(re.GRAPH_NAME.eq(cl.GRAPH_NAME), re.TYPE_NAME.eq(cl.TYPE_NAME),
                re.FIELD_NAME.eq(cl.FIELD_NAME), re.SOURCE_NAME.eq(cl.SOURCE_NAME),
                re.SOURCE_LINE.eq(cl.SOURCE_LINE), re.SOURCE_COLUMN.eq(cl.SOURCE_COLUMN))
            .join(fr).on(fr.GRAPH_NAME.eq(re.GRAPH_NAME), fr.TYPE_NAME.eq(re.TYPE_NAME),
                fr.FIELD_NAME.eq(re.FIELD_NAME), fr.ORDINAL.eq(re.ORDINAL))
            .where(cl.GRAPH_NAME.eq(graph))
            .and(cl.POSITION.eq(position))
            .asTable("resolved");

        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME,
                t.TARGET_SOURCE_NAME, t.TARGET_SCHEMA, t.TARGET_TABLE, t.POSITION,
                t.VIA, t.KEY_MATCHED_BY,
                t.CONSTRAINT_SOURCE_NAME, t.CONSTRAINT_SCHEMA, t.CONSTRAINT_TABLE,
                t.CONSTRAINT_NAME, t.FK_ON_FROM,
                t.FROM_SOURCE_NAME, t.FROM_SCHEMA, t.FROM_TABLE,
                t.TO_SOURCE_NAME, t.TO_SCHEMA, t.TO_TABLE, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME),
                    resolved.field(TYPE_NAME, String.class),
                    resolved.field(FIELD_NAME, String.class),
                    resolved.field(TARGET_SOURCE_NAME, String.class),
                    resolved.field(TARGET_SCHEMA, String.class),
                    resolved.field(TARGET_TABLE, String.class),
                    val(position, t.POSITION), val("ROUTINE", t.VIA),
                    castNull(t.KEY_MATCHED_BY),
                    castNull(t.CONSTRAINT_SOURCE_NAME), castNull(t.CONSTRAINT_SCHEMA),
                    castNull(t.CONSTRAINT_TABLE), castNull(t.CONSTRAINT_NAME),
                    castNull(t.FK_ON_FROM),
                    // The three departure columns are null together, which is the fact rather than
                    // a gap: a function result is where the chain's rows begin.
                    castNull(t.FROM_SOURCE_NAME), castNull(t.FROM_SCHEMA),
                    castNull(t.FROM_TABLE),
                    resolved.field(TO_SOURCE_NAME, String.class),
                    resolved.field(TO_SCHEMA, String.class),
                    resolved.field(TO_TABLE, String.class),
                    val(touchedAt, t.TOUCHED_AT))
                .from(resolved)
                .where(resolved.field(CANDIDATES, Integer.class).eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.VIA, excluded(t.VIA))
            .set(t.KEY_MATCHED_BY, excluded(t.KEY_MATCHED_BY))
            .set(t.CONSTRAINT_SOURCE_NAME, excluded(t.CONSTRAINT_SOURCE_NAME))
            .set(t.CONSTRAINT_SCHEMA, excluded(t.CONSTRAINT_SCHEMA))
            .set(t.CONSTRAINT_TABLE, excluded(t.CONSTRAINT_TABLE))
            .set(t.CONSTRAINT_NAME, excluded(t.CONSTRAINT_NAME))
            .set(t.FK_ON_FROM, excluded(t.FK_ON_FROM))
            .set(t.FROM_SOURCE_NAME, excluded(t.FROM_SOURCE_NAME))
            .set(t.FROM_SCHEMA, excluded(t.FROM_SCHEMA))
            .set(t.FROM_TABLE, excluded(t.FROM_TABLE))
            .set(t.TO_SOURCE_NAME, excluded(t.TO_SOURCE_NAME))
            .set(t.TO_SCHEMA, excluded(t.TO_SCHEMA))
            .set(t.TO_TABLE, excluded(t.TO_TABLE))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * The key arm at one position: a link whose element named a foreign key arrives at whichever end
     * of that key the departure is not.
     *
     * <p>The spelling resolves under the generator's own namespace precedence. A written qualifier
     * binds hard, and it names the schema of the table holding the constraint rather than a
     * namespace the constraint has of its own; an unqualified name matches the SQL constraint name;
     * and the generated constant's name is eligible only where no SQL constraint in this graph's
     * sources answers, which is a precedence rather than a looser match on either.
     *
     * <p>The orientation is read off the departure. Where the departure declares the key the link
     * runs along it and arrives at the referenced table, and otherwise it runs against it. A
     * self-referential key satisfies both and the first reading is then the answer: both land on
     * the same table, so what the flag settles there is which columns the join uses rather than
     * where the link goes.
     *
     * <p>A link whose departure is unknown draws no row, which is a fact about the coordinate rather
     * than a gap: a field with no table of its own and no {@code @routine} before its first element
     * has nothing for that element to depart from.
     */
    private static void keys(DSLContext dsl, String graph, int position, LocalDateTime touchedAt) {
        var cl = GRAPHITRON_FIELD_CHAIN_LINK;
        var c = SQL_CONSTRAINT;
        var c2 = SQL_CONSTRAINT.as("c2");
        var rc = SQL_REFERENTIAL_CONSTRAINT;
        var m = STORE_GRAPH_SOURCE;
        var m2 = STORE_GRAPH_SOURCE.as("m2");
        var t = GRAPHITRON_FIELD_TABLE_LINK;
        var spellings = spellings(dsl, graph);
        var d = departures(dsl, graph, position, touchedAt);

        Field<String> namespaceUpper = spellings.field(NAMESPACE_UPPER, String.class);
        Field<String> nameUpper = spellings.field(NAME_UPPER, String.class);
        Field<String> fromSource = d.field(FROM_SOURCE_NAME, String.class);
        Field<String> fromSchema = d.field(FROM_SCHEMA, String.class);
        Field<String> fromTable = d.field(FROM_TABLE, String.class);
        Field<String> target = d.field(TARGET_SOURCE_NAME, String.class);
        Field<String> targetSchema = d.field(TARGET_SCHEMA, String.class);
        Field<String> targetTable = d.field(TARGET_TABLE, String.class);

        Condition keyIsOnDeparture = rc.SOURCE_NAME.eq(fromSource)
            .and(rc.TABLE_SCHEMA.eq(fromSchema)).and(rc.TABLE_NAME.eq(fromTable));
        Condition keyPointsAtDeparture = rc.REFERENCED_SOURCE_NAME.eq(fromSource)
            .and(rc.REFERENCED_SCHEMA.eq(fromSchema)).and(rc.REFERENCED_TABLE.eq(fromTable));
        Condition named = namespaceUpper.isNotNull()
            .and(c.TABLE_SCHEMA_UPPER.eq(namespaceUpper))
            .and(c.CONSTRAINT_NAME_UPPER.eq(nameUpper))
            .or(namespaceUpper.isNull().and(c.CONSTRAINT_NAME_UPPER.eq(nameUpper)
                .or(c.JOOQ_NAME_UPPER.eq(nameUpper)
                    .and(notExists(selectOne().from(c2)
                        .join(m2).on(m2.SOURCE_NAME.eq(c2.SOURCE_NAME))
                        .where(m2.GRAPH_NAME.eq(val(graph, m2.GRAPH_NAME)))
                        .and(c2.CONSTRAINT_NAME_UPPER.eq(nameUpper)))))));

        var resolved = dsl
            .select(cl.TYPE_NAME.as(TYPE_NAME), cl.FIELD_NAME.as(FIELD_NAME),
                target.as(TARGET_SOURCE_NAME), targetSchema.as(TARGET_SCHEMA),
                targetTable.as(TARGET_TABLE),
                when(c.CONSTRAINT_NAME_UPPER.eq(nameUpper), val("SQL_NAME", t.KEY_MATCHED_BY))
                    .otherwise(val("JOOQ_NAME", t.KEY_MATCHED_BY)).as(KEY_MATCHED_BY),
                rc.SOURCE_NAME.as(CONSTRAINT_SOURCE_NAME),
                rc.TABLE_SCHEMA.as(CONSTRAINT_SCHEMA), rc.TABLE_NAME.as(CONSTRAINT_TABLE),
                rc.CONSTRAINT_NAME.as(CONSTRAINT_NAME),
                when(keyIsOnDeparture, val(true, t.FK_ON_FROM))
                    .otherwise(val(false, t.FK_ON_FROM)).as(FK_ON_FROM),
                fromSource.as(FROM_SOURCE_NAME), fromSchema.as(FROM_SCHEMA),
                fromTable.as(FROM_TABLE),
                when(keyIsOnDeparture, rc.REFERENCED_SOURCE_NAME).otherwise(rc.SOURCE_NAME)
                    .as(TO_SOURCE_NAME),
                when(keyIsOnDeparture, rc.REFERENCED_SCHEMA).otherwise(rc.TABLE_SCHEMA)
                    .as(TO_SCHEMA),
                when(keyIsOnDeparture, rc.REFERENCED_TABLE).otherwise(rc.TABLE_NAME)
                    .as(TO_TABLE),
                count().over(partitionBy(cl.TYPE_NAME, cl.FIELD_NAME,
                    target, targetSchema, targetTable)).as(CANDIDATES))
            .from(cl)
            .join(d).on(d.field(cl.TYPE_NAME).eq(cl.TYPE_NAME),
                d.field(cl.FIELD_NAME).eq(cl.FIELD_NAME))
            .join(spellings).on(spellings.field(SITE_NAME, String.class).eq(cl.SOURCE_NAME),
                spellings.field(SITE_LINE, Integer.class).eq(cl.SOURCE_LINE),
                spellings.field(SITE_COLUMN, Integer.class).eq(cl.SOURCE_COLUMN))
            .join(m).on(m.GRAPH_NAME.eq(cl.GRAPH_NAME))
            .join(c).on(c.SOURCE_NAME.eq(m.SOURCE_NAME)).and(named)
            .join(rc).on(rc.SOURCE_NAME.eq(c.SOURCE_NAME),
                rc.TABLE_SCHEMA.eq(c.TABLE_SCHEMA), rc.TABLE_NAME.eq(c.TABLE_NAME),
                rc.CONSTRAINT_NAME.eq(c.CONSTRAINT_NAME))
            .where(cl.GRAPH_NAME.eq(graph))
            .and(cl.POSITION.eq(position))
            .and(keyIsOnDeparture.or(keyPointsAtDeparture))
            .asTable("resolved");

        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME,
                t.TARGET_SOURCE_NAME, t.TARGET_SCHEMA, t.TARGET_TABLE, t.POSITION,
                t.VIA, t.KEY_MATCHED_BY,
                t.CONSTRAINT_SOURCE_NAME, t.CONSTRAINT_SCHEMA, t.CONSTRAINT_TABLE,
                t.CONSTRAINT_NAME, t.FK_ON_FROM,
                t.FROM_SOURCE_NAME, t.FROM_SCHEMA, t.FROM_TABLE,
                t.TO_SOURCE_NAME, t.TO_SCHEMA, t.TO_TABLE, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME),
                    resolved.field(TYPE_NAME, String.class),
                    resolved.field(FIELD_NAME, String.class),
                    resolved.field(TARGET_SOURCE_NAME, String.class),
                    resolved.field(TARGET_SCHEMA, String.class),
                    resolved.field(TARGET_TABLE, String.class),
                    val(position, t.POSITION), val("KEY", t.VIA),
                    resolved.field(KEY_MATCHED_BY, String.class),
                    resolved.field(CONSTRAINT_SOURCE_NAME, String.class),
                    resolved.field(CONSTRAINT_SCHEMA, String.class),
                    resolved.field(CONSTRAINT_TABLE, String.class),
                    resolved.field(CONSTRAINT_NAME, String.class),
                    resolved.field(FK_ON_FROM, Boolean.class),
                    resolved.field(FROM_SOURCE_NAME, String.class),
                    resolved.field(FROM_SCHEMA, String.class),
                    resolved.field(FROM_TABLE, String.class),
                    resolved.field(TO_SOURCE_NAME, String.class),
                    resolved.field(TO_SCHEMA, String.class),
                    resolved.field(TO_TABLE, String.class),
                    val(touchedAt, t.TOUCHED_AT))
                .from(resolved)
                .where(resolved.field(CANDIDATES, Integer.class).eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.VIA, excluded(t.VIA))
            .set(t.KEY_MATCHED_BY, excluded(t.KEY_MATCHED_BY))
            .set(t.CONSTRAINT_SOURCE_NAME, excluded(t.CONSTRAINT_SOURCE_NAME))
            .set(t.CONSTRAINT_SCHEMA, excluded(t.CONSTRAINT_SCHEMA))
            .set(t.CONSTRAINT_TABLE, excluded(t.CONSTRAINT_TABLE))
            .set(t.CONSTRAINT_NAME, excluded(t.CONSTRAINT_NAME))
            .set(t.FK_ON_FROM, excluded(t.FK_ON_FROM))
            .set(t.FROM_SOURCE_NAME, excluded(t.FROM_SOURCE_NAME))
            .set(t.FROM_SCHEMA, excluded(t.FROM_SCHEMA))
            .set(t.FROM_TABLE, excluded(t.FROM_TABLE))
            .set(t.TO_SOURCE_NAME, excluded(t.TO_SOURCE_NAME))
            .set(t.TO_SCHEMA, excluded(t.TO_SCHEMA))
            .set(t.TO_TABLE, excluded(t.TO_TABLE))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * The table arm at one position: a link whose element named a table arrives there, and the
     * foreign key between the departure and it is what carries the rows across.
     *
     * <p>The spelling resolves against the catalog directly and not through the relation of
     * resolved spellings, which is {@link FieldRoutines}' reason and the same one: that relation is
     * refilled after this stage runs, so reading it here would resolve this reading's elements
     * against the last reading's catalog. The match folds case on both sides and an unqualified
     * name is admitted against any schema the graph's sources carry.
     *
     * <p>An element naming a key as well is the key arm's and not this one's. The key is the route
     * there, and the table beside it is an assertion about where the route ends, which is a fact
     * about the coordinate rather than a second way to travel.
     *
     * <p>Both ends are known here, so the foreign key is a lookup rather than a discovery: the one
     * constraint joining the departure to the arrival, in whichever direction it runs. Two
     * constraints joining one pair of tables are two candidates and draw no row, which is the
     * ambiguity this arm cannot settle and the coordinate has to.
     */
    private static void tables(DSLContext dsl, String graph, int position,
                               LocalDateTime touchedAt) {
        var cl = GRAPHITRON_FIELD_CHAIN_LINK;
        var rc = SQL_REFERENTIAL_CONSTRAINT;
        var m = STORE_GRAPH_SOURCE;
        var st = SQL_TABLE;
        var t = GRAPHITRON_FIELD_TABLE_LINK;
        var named = tableSpellings(dsl, graph);
        var d = departures(dsl, graph, position, touchedAt);

        Field<String> fromSource = d.field(FROM_SOURCE_NAME, String.class);
        Field<String> fromSchema = d.field(FROM_SCHEMA, String.class);
        Field<String> fromTable = d.field(FROM_TABLE, String.class);
        Field<String> namespaceUpper = named.field(NAMESPACE_UPPER, String.class);
        Field<String> nameUpper = named.field(NAME_UPPER, String.class);

        Condition fkOnDeparture = rc.SOURCE_NAME.eq(fromSource)
            .and(rc.TABLE_SCHEMA.eq(fromSchema)).and(rc.TABLE_NAME.eq(fromTable))
            .and(rc.REFERENCED_SOURCE_NAME.eq(st.SOURCE_NAME))
            .and(rc.REFERENCED_SCHEMA.eq(st.TABLE_SCHEMA))
            .and(rc.REFERENCED_TABLE.eq(st.TABLE_NAME));
        Condition fkOnArrival = rc.REFERENCED_SOURCE_NAME.eq(fromSource)
            .and(rc.REFERENCED_SCHEMA.eq(fromSchema)).and(rc.REFERENCED_TABLE.eq(fromTable))
            .and(rc.SOURCE_NAME.eq(st.SOURCE_NAME))
            .and(rc.TABLE_SCHEMA.eq(st.TABLE_SCHEMA))
            .and(rc.TABLE_NAME.eq(st.TABLE_NAME));

        var resolved = dsl
            .select(cl.TYPE_NAME.as(TYPE_NAME), cl.FIELD_NAME.as(FIELD_NAME),
                d.field(TARGET_SOURCE_NAME, String.class).as(TARGET_SOURCE_NAME),
                d.field(TARGET_SCHEMA, String.class).as(TARGET_SCHEMA),
                d.field(TARGET_TABLE, String.class).as(TARGET_TABLE),
                rc.SOURCE_NAME.as(CONSTRAINT_SOURCE_NAME),
                rc.TABLE_SCHEMA.as(CONSTRAINT_SCHEMA), rc.TABLE_NAME.as(CONSTRAINT_TABLE),
                rc.CONSTRAINT_NAME.as(CONSTRAINT_NAME),
                when(fkOnDeparture, val(true, t.FK_ON_FROM))
                    .otherwise(val(false, t.FK_ON_FROM)).as(FK_ON_FROM),
                fromSource.as(FROM_SOURCE_NAME), fromSchema.as(FROM_SCHEMA),
                fromTable.as(FROM_TABLE),
                st.SOURCE_NAME.as(TO_SOURCE_NAME), st.TABLE_SCHEMA.as(TO_SCHEMA),
                st.TABLE_NAME.as(TO_TABLE),
                count().over(partitionBy(cl.TYPE_NAME, cl.FIELD_NAME,
                    d.field(TARGET_SOURCE_NAME, String.class),
                    d.field(TARGET_SCHEMA, String.class),
                    d.field(TARGET_TABLE, String.class))).as(CANDIDATES))
            .from(cl)
            .join(d).on(d.field(cl.TYPE_NAME).eq(cl.TYPE_NAME),
                d.field(cl.FIELD_NAME).eq(cl.FIELD_NAME))
            .join(named).on(named.field(SITE_NAME, String.class).eq(cl.SOURCE_NAME),
                named.field(SITE_LINE, Integer.class).eq(cl.SOURCE_LINE),
                named.field(SITE_COLUMN, Integer.class).eq(cl.SOURCE_COLUMN))
            .join(m).on(m.GRAPH_NAME.eq(cl.GRAPH_NAME))
            .join(st).on(st.SOURCE_NAME.eq(m.SOURCE_NAME), st.TABLE_NAME_UPPER.eq(nameUpper))
            .and(namespaceUpper.isNull().or(st.TABLE_SCHEMA_UPPER.eq(namespaceUpper)))
            .join(rc).on(fkOnDeparture.or(fkOnArrival))
            .where(cl.GRAPH_NAME.eq(graph))
            .and(cl.POSITION.eq(position))
            .and(namesNoKey(cl))
            .asTable("resolved");

        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME,
                t.TARGET_SOURCE_NAME, t.TARGET_SCHEMA, t.TARGET_TABLE, t.POSITION,
                t.VIA, t.KEY_MATCHED_BY,
                t.CONSTRAINT_SOURCE_NAME, t.CONSTRAINT_SCHEMA, t.CONSTRAINT_TABLE,
                t.CONSTRAINT_NAME, t.FK_ON_FROM,
                t.FROM_SOURCE_NAME, t.FROM_SCHEMA, t.FROM_TABLE,
                t.TO_SOURCE_NAME, t.TO_SCHEMA, t.TO_TABLE, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME),
                    resolved.field(TYPE_NAME, String.class),
                    resolved.field(FIELD_NAME, String.class),
                    resolved.field(TARGET_SOURCE_NAME, String.class),
                    resolved.field(TARGET_SCHEMA, String.class),
                    resolved.field(TARGET_TABLE, String.class),
                    val(position, t.POSITION), val("TABLE", t.VIA),
                    castNull(t.KEY_MATCHED_BY),
                    resolved.field(CONSTRAINT_SOURCE_NAME, String.class),
                    resolved.field(CONSTRAINT_SCHEMA, String.class),
                    resolved.field(CONSTRAINT_TABLE, String.class),
                    resolved.field(CONSTRAINT_NAME, String.class),
                    resolved.field(FK_ON_FROM, Boolean.class),
                    resolved.field(FROM_SOURCE_NAME, String.class),
                    resolved.field(FROM_SCHEMA, String.class),
                    resolved.field(FROM_TABLE, String.class),
                    resolved.field(TO_SOURCE_NAME, String.class),
                    resolved.field(TO_SCHEMA, String.class),
                    resolved.field(TO_TABLE, String.class),
                    val(touchedAt, t.TOUCHED_AT))
                .from(resolved)
                .where(resolved.field(CANDIDATES, Integer.class).eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.VIA, excluded(t.VIA))
            .set(t.KEY_MATCHED_BY, excluded(t.KEY_MATCHED_BY))
            .set(t.CONSTRAINT_SOURCE_NAME, excluded(t.CONSTRAINT_SOURCE_NAME))
            .set(t.CONSTRAINT_SCHEMA, excluded(t.CONSTRAINT_SCHEMA))
            .set(t.CONSTRAINT_TABLE, excluded(t.CONSTRAINT_TABLE))
            .set(t.CONSTRAINT_NAME, excluded(t.CONSTRAINT_NAME))
            .set(t.FK_ON_FROM, excluded(t.FK_ON_FROM))
            .set(t.FROM_SOURCE_NAME, excluded(t.FROM_SOURCE_NAME))
            .set(t.FROM_SCHEMA, excluded(t.FROM_SCHEMA))
            .set(t.FROM_TABLE, excluded(t.FROM_TABLE))
            .set(t.TO_SOURCE_NAME, excluded(t.TO_SOURCE_NAME))
            .set(t.TO_SCHEMA, excluded(t.TO_SCHEMA))
            .set(t.TO_TABLE, excluded(t.TO_TABLE))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * The same element where the departure is a function result, which declares no foreign key for
     * the arm above to find. The route is then the one the generator applies there and the only one
     * available: the arrival's primary key matched to the function's columns by name, which
     * {@code sql_name_matched_key_column} states, and states only where the whole key matches.
     *
     * <p>It cannot collide with the arm above, a function result being exactly the departure that
     * declares nothing for a foreign key to be found on. The two are one authored form and two
     * routes, which is why {@code via} tells them apart: what a reader joins on differs, a
     * constraint on one and a column pairing on the other.
     */
    private static void nameMatches(DSLContext dsl, String graph, int position,
                                    LocalDateTime touchedAt) {
        var cl = GRAPHITRON_FIELD_CHAIN_LINK;
        var m = STORE_GRAPH_SOURCE;
        var st = SQL_TABLE;
        var fn = SQL_TABLE.as("fn");
        var pair = SQL_NAME_MATCHED_KEY_COLUMN;
        var t = GRAPHITRON_FIELD_TABLE_LINK;
        var named = tableSpellings(dsl, graph);
        var d = departures(dsl, graph, position, touchedAt);

        Field<String> fromSource = d.field(FROM_SOURCE_NAME, String.class);
        Field<String> fromSchema = d.field(FROM_SCHEMA, String.class);
        Field<String> fromTable = d.field(FROM_TABLE, String.class);
        Field<String> namespaceUpper = named.field(NAMESPACE_UPPER, String.class);
        Field<String> nameUpper = named.field(NAME_UPPER, String.class);

        var resolved = dsl
            .select(cl.TYPE_NAME.as(TYPE_NAME), cl.FIELD_NAME.as(FIELD_NAME),
                d.field(TARGET_SOURCE_NAME, String.class).as(TARGET_SOURCE_NAME),
                d.field(TARGET_SCHEMA, String.class).as(TARGET_SCHEMA),
                d.field(TARGET_TABLE, String.class).as(TARGET_TABLE),
                fromSource.as(FROM_SOURCE_NAME), fromSchema.as(FROM_SCHEMA),
                fromTable.as(FROM_TABLE),
                st.SOURCE_NAME.as(TO_SOURCE_NAME), st.TABLE_SCHEMA.as(TO_SCHEMA),
                st.TABLE_NAME.as(TO_TABLE),
                count().over(partitionBy(cl.TYPE_NAME, cl.FIELD_NAME,
                    d.field(TARGET_SOURCE_NAME, String.class),
                    d.field(TARGET_SCHEMA, String.class),
                    d.field(TARGET_TABLE, String.class))).as(CANDIDATES))
            .from(cl)
            .join(d).on(d.field(cl.TYPE_NAME).eq(cl.TYPE_NAME),
                d.field(cl.FIELD_NAME).eq(cl.FIELD_NAME))
            .join(named).on(named.field(SITE_NAME, String.class).eq(cl.SOURCE_NAME),
                named.field(SITE_LINE, Integer.class).eq(cl.SOURCE_LINE),
                named.field(SITE_COLUMN, Integer.class).eq(cl.SOURCE_COLUMN))
            // The departure is the function, which is what makes this arm the one that answers.
            .join(fn).on(fn.SOURCE_NAME.eq(fromSource), fn.TABLE_SCHEMA.eq(fromSchema),
                fn.TABLE_NAME.eq(fromTable), fn.TABLE_TYPE.eq("FUNCTION"))
            .join(m).on(m.GRAPH_NAME.eq(cl.GRAPH_NAME))
            .join(st).on(st.SOURCE_NAME.eq(m.SOURCE_NAME), st.TABLE_NAME_UPPER.eq(nameUpper))
            .and(namespaceUpper.isNull().or(st.TABLE_SCHEMA_UPPER.eq(namespaceUpper)))
            .where(cl.GRAPH_NAME.eq(graph))
            .and(cl.POSITION.eq(position))
            .and(namesNoKey(cl))
            .andExists(selectOne().from(pair)
                .where(pair.SOURCE_NAME.eq(fn.SOURCE_NAME),
                    pair.TABLE_SCHEMA.eq(fn.TABLE_SCHEMA), pair.TABLE_NAME.eq(fn.TABLE_NAME),
                    pair.TO_SOURCE_NAME.eq(st.SOURCE_NAME), pair.TO_SCHEMA.eq(st.TABLE_SCHEMA),
                    pair.TO_TABLE.eq(st.TABLE_NAME)))
            .asTable("resolved");

        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME,
                t.TARGET_SOURCE_NAME, t.TARGET_SCHEMA, t.TARGET_TABLE, t.POSITION,
                t.VIA, t.KEY_MATCHED_BY,
                t.CONSTRAINT_SOURCE_NAME, t.CONSTRAINT_SCHEMA, t.CONSTRAINT_TABLE,
                t.CONSTRAINT_NAME, t.FK_ON_FROM,
                t.FROM_SOURCE_NAME, t.FROM_SCHEMA, t.FROM_TABLE,
                t.TO_SOURCE_NAME, t.TO_SCHEMA, t.TO_TABLE, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME),
                    resolved.field(TYPE_NAME, String.class),
                    resolved.field(FIELD_NAME, String.class),
                    resolved.field(TARGET_SOURCE_NAME, String.class),
                    resolved.field(TARGET_SCHEMA, String.class),
                    resolved.field(TARGET_TABLE, String.class),
                    val(position, t.POSITION), val("NAME_MATCH", t.VIA),
                    castNull(t.KEY_MATCHED_BY),
                    castNull(t.CONSTRAINT_SOURCE_NAME), castNull(t.CONSTRAINT_SCHEMA),
                    castNull(t.CONSTRAINT_TABLE), castNull(t.CONSTRAINT_NAME),
                    castNull(t.FK_ON_FROM),
                    resolved.field(FROM_SOURCE_NAME, String.class),
                    resolved.field(FROM_SCHEMA, String.class),
                    resolved.field(FROM_TABLE, String.class),
                    resolved.field(TO_SOURCE_NAME, String.class),
                    resolved.field(TO_SCHEMA, String.class),
                    resolved.field(TO_TABLE, String.class),
                    val(touchedAt, t.TOUCHED_AT))
                .from(resolved)
                .where(resolved.field(CANDIDATES, Integer.class).eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.VIA, excluded(t.VIA))
            .set(t.KEY_MATCHED_BY, excluded(t.KEY_MATCHED_BY))
            .set(t.CONSTRAINT_SOURCE_NAME, excluded(t.CONSTRAINT_SOURCE_NAME))
            .set(t.CONSTRAINT_SCHEMA, excluded(t.CONSTRAINT_SCHEMA))
            .set(t.CONSTRAINT_TABLE, excluded(t.CONSTRAINT_TABLE))
            .set(t.CONSTRAINT_NAME, excluded(t.CONSTRAINT_NAME))
            .set(t.FK_ON_FROM, excluded(t.FK_ON_FROM))
            .set(t.FROM_SOURCE_NAME, excluded(t.FROM_SOURCE_NAME))
            .set(t.FROM_SCHEMA, excluded(t.FROM_SCHEMA))
            .set(t.FROM_TABLE, excluded(t.FROM_TABLE))
            .set(t.TO_SOURCE_NAME, excluded(t.TO_SOURCE_NAME))
            .set(t.TO_SCHEMA, excluded(t.TO_SCHEMA))
            .set(t.TO_TABLE, excluded(t.TO_TABLE))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * The condition arm at one position: an element carrying a condition and naming neither a key
     * nor a table has no foreign key to read, so its route is the one its method's own signature
     * declares. The parameter after the first names the table the link arrives at.
     *
     * <p>Read off the {@code code_} family, which is the classpath as the arm that admits a
     * condition sees it, and written before the walk, so it is there to be queried when this runs.
     *
     * <p>Two things the read-time rule had to do are gone, and both for the same reason: it
     * answered for a class and a method with no coordinate attached. It enumerated every table in
     * the graph's sources as a candidate departure wherever the first parameter named none, and it
     * demanded every overload of a name agree on its first parameter before it would answer at all.
     * Here the departure is already known, so neither is a question. What is left of both is one
     * demand: that nothing about the first parameter contradicts the departure. A method with no
     * first parameter, or one whose first parameter is not a generated table, contradicts nothing
     * and the chain's own departure stands; a first parameter naming a different table is a method
     * this link cannot be travelling through.
     *
     * <p>That predicate is also what tells overloads apart, so no unanimity is needed: the ones
     * whose first parameter contradicts the departure are gone, and two survivors reaching the same
     * arrival are two candidates and draw no row. A row existing therefore means exactly one
     * signature answered, which is the invariant a reader of the method itself can lean on.
     */
    private static void conditions(DSLContext dsl, String graph, int position,
                                   LocalDateTime touchedAt) {
        var cl = GRAPHITRON_FIELD_CHAIN_LINK;
        var cm = CODE_CONDITION_METHOD;
        var arrives = CODE_CONDITION_METHOD_PARAMETER.as("arrives");
        var departs = CODE_CONDITION_METHOD_PARAMETER;
        var m = STORE_GRAPH_SOURCE;
        var mt = STORE_GRAPH_SOURCE.as("mt");
        var md = STORE_GRAPH_SOURCE.as("md");
        var st = SQL_TABLE;
        var other = SQL_TABLE.as("other");
        var t = GRAPHITRON_FIELD_TABLE_LINK;
        var stated = conditionSpellings(dsl, graph);
        var d = departures(dsl, graph, position, touchedAt);

        Field<String> fromSource = d.field(FROM_SOURCE_NAME, String.class);
        Field<String> fromSchema = d.field(FROM_SCHEMA, String.class);
        Field<String> fromTable = d.field(FROM_TABLE, String.class);

        // Nothing about the first parameter contradicts the departure. A parameter naming a table
        // other than where the chain stands is the whole of what this excludes.
        Condition departureStands = notExists(selectOne().from(departs)
            .join(md).on(md.GRAPH_NAME.eq(cl.GRAPH_NAME))
            .join(other).on(other.SOURCE_NAME.eq(md.SOURCE_NAME),
                other.CLASS_FQN.eq(departs.PARAMETER_TYPE))
            .where(departs.SOURCE_NAME.eq(cm.SOURCE_NAME),
                departs.CLASS_NAME.eq(cm.CLASS_NAME),
                departs.METHOD_NAME.eq(cm.METHOD_NAME),
                departs.DESCRIPTOR.eq(cm.DESCRIPTOR),
                departs.POSITION.eq(0))
            .and(other.SOURCE_NAME.ne(fromSource)
                .or(other.TABLE_SCHEMA.ne(fromSchema))
                .or(other.TABLE_NAME.ne(fromTable))));

        var resolved = dsl
            .select(cl.TYPE_NAME.as(TYPE_NAME), cl.FIELD_NAME.as(FIELD_NAME),
                d.field(TARGET_SOURCE_NAME, String.class).as(TARGET_SOURCE_NAME),
                d.field(TARGET_SCHEMA, String.class).as(TARGET_SCHEMA),
                d.field(TARGET_TABLE, String.class).as(TARGET_TABLE),
                fromSource.as(FROM_SOURCE_NAME), fromSchema.as(FROM_SCHEMA),
                fromTable.as(FROM_TABLE),
                st.SOURCE_NAME.as(TO_SOURCE_NAME), st.TABLE_SCHEMA.as(TO_SCHEMA),
                st.TABLE_NAME.as(TO_TABLE),
                count().over(partitionBy(cl.TYPE_NAME, cl.FIELD_NAME,
                    d.field(TARGET_SOURCE_NAME, String.class),
                    d.field(TARGET_SCHEMA, String.class),
                    d.field(TARGET_TABLE, String.class))).as(CANDIDATES))
            .from(cl)
            .join(d).on(d.field(cl.TYPE_NAME).eq(cl.TYPE_NAME),
                d.field(cl.FIELD_NAME).eq(cl.FIELD_NAME))
            .join(stated).on(stated.field(SITE_NAME, String.class).eq(cl.SOURCE_NAME),
                stated.field(SITE_LINE, Integer.class).eq(cl.SOURCE_LINE),
                stated.field(SITE_COLUMN, Integer.class).eq(cl.SOURCE_COLUMN))
            .join(m).on(m.GRAPH_NAME.eq(cl.GRAPH_NAME))
            .join(cm).on(cm.SOURCE_NAME.eq(m.SOURCE_NAME),
                cm.CLASS_NAME.eq(stated.field(CLASS_NAME, String.class)),
                cm.METHOD_NAME.eq(stated.field(METHOD_NAME, String.class)))
            // The parameter after the first names the arrival, which is what makes this a route.
            .join(arrives).on(arrives.SOURCE_NAME.eq(cm.SOURCE_NAME),
                arrives.CLASS_NAME.eq(cm.CLASS_NAME), arrives.METHOD_NAME.eq(cm.METHOD_NAME),
                arrives.DESCRIPTOR.eq(cm.DESCRIPTOR), arrives.POSITION.eq(1))
            .join(mt).on(mt.GRAPH_NAME.eq(cl.GRAPH_NAME))
            .join(st).on(st.SOURCE_NAME.eq(mt.SOURCE_NAME),
                st.CLASS_FQN.eq(arrives.PARAMETER_TYPE))
            .where(cl.GRAPH_NAME.eq(graph))
            .and(cl.POSITION.eq(position))
            .and(namesNoKey(cl))
            .and(namesNoTable(cl))
            .and(departureStands)
            .asTable("resolved");

        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME,
                t.TARGET_SOURCE_NAME, t.TARGET_SCHEMA, t.TARGET_TABLE, t.POSITION,
                t.VIA, t.KEY_MATCHED_BY,
                t.CONSTRAINT_SOURCE_NAME, t.CONSTRAINT_SCHEMA, t.CONSTRAINT_TABLE,
                t.CONSTRAINT_NAME, t.FK_ON_FROM,
                t.FROM_SOURCE_NAME, t.FROM_SCHEMA, t.FROM_TABLE,
                t.TO_SOURCE_NAME, t.TO_SCHEMA, t.TO_TABLE, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME),
                    resolved.field(TYPE_NAME, String.class),
                    resolved.field(FIELD_NAME, String.class),
                    resolved.field(TARGET_SOURCE_NAME, String.class),
                    resolved.field(TARGET_SCHEMA, String.class),
                    resolved.field(TARGET_TABLE, String.class),
                    val(position, t.POSITION), val("CONDITION", t.VIA),
                    castNull(t.KEY_MATCHED_BY),
                    castNull(t.CONSTRAINT_SOURCE_NAME), castNull(t.CONSTRAINT_SCHEMA),
                    castNull(t.CONSTRAINT_TABLE), castNull(t.CONSTRAINT_NAME),
                    castNull(t.FK_ON_FROM),
                    resolved.field(FROM_SOURCE_NAME, String.class),
                    resolved.field(FROM_SCHEMA, String.class),
                    resolved.field(FROM_TABLE, String.class),
                    resolved.field(TO_SOURCE_NAME, String.class),
                    resolved.field(TO_SCHEMA, String.class),
                    resolved.field(TO_TABLE, String.class),
                    val(touchedAt, t.TOUCHED_AT))
                .from(resolved)
                .where(resolved.field(CANDIDATES, Integer.class).eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.VIA, excluded(t.VIA))
            .set(t.KEY_MATCHED_BY, excluded(t.KEY_MATCHED_BY))
            .set(t.CONSTRAINT_SOURCE_NAME, excluded(t.CONSTRAINT_SOURCE_NAME))
            .set(t.CONSTRAINT_SCHEMA, excluded(t.CONSTRAINT_SCHEMA))
            .set(t.CONSTRAINT_TABLE, excluded(t.CONSTRAINT_TABLE))
            .set(t.CONSTRAINT_NAME, excluded(t.CONSTRAINT_NAME))
            .set(t.FK_ON_FROM, excluded(t.FK_ON_FROM))
            .set(t.FROM_SOURCE_NAME, excluded(t.FROM_SOURCE_NAME))
            .set(t.FROM_SCHEMA, excluded(t.FROM_SCHEMA))
            .set(t.FROM_TABLE, excluded(t.FROM_TABLE))
            .set(t.TO_SOURCE_NAME, excluded(t.TO_SOURCE_NAME))
            .set(t.TO_SCHEMA, excluded(t.TO_SCHEMA))
            .set(t.TO_TABLE, excluded(t.TO_TABLE))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * What the condition arm demands beyond {@link #namesNoKey}: that the element named no table
     * either. A condition written beside a key or a table filters that hop rather than routing it,
     * so only an element naming neither has its route in the method's signature.
     */
    private static Condition namesNoTable(GraphitronFieldChainLink cl) {
        var s = GRAPHITRON_AST_FIELD_REFERENCE_TABLE_STEP_ENTRY;
        var f = GRAPHITRON_AST_FIELD_REFERENCE_FOR_TABLE_STEP_ENTRY;
        return notExists(selectOne().from(s)
            .where(s.GRAPH_NAME.eq(cl.GRAPH_NAME), s.SOURCE_NAME.eq(cl.SOURCE_NAME),
                s.SOURCE_LINE.eq(cl.SOURCE_LINE), s.SOURCE_COLUMN.eq(cl.SOURCE_COLUMN)))
            .and(notExists(selectOne().from(f)
                .where(f.GRAPH_NAME.eq(cl.GRAPH_NAME), f.SOURCE_NAME.eq(cl.SOURCE_NAME),
                    f.SOURCE_LINE.eq(cl.SOURCE_LINE), f.SOURCE_COLUMN.eq(cl.SOURCE_COLUMN))));
    }

    /**
     * The condition an element named, whichever of the two directives wrote it, on the terms
     * {@link #spellings} states for the key. A method the author left out is no signature to read,
     * so the join below drops the element rather than this relation excluding it.
     */
    private static Table<?> conditionSpellings(DSLContext dsl, String graph) {
        var s = GRAPHITRON_AST_FIELD_REFERENCE_CONDITION_STEP_ENTRY;
        var f = GRAPHITRON_AST_FIELD_REFERENCE_FOR_CONDITION_STEP_ENTRY;
        return dsl
            .select(s.SOURCE_NAME.as(SITE_NAME), s.SOURCE_LINE.as(SITE_LINE),
                s.SOURCE_COLUMN.as(SITE_COLUMN),
                s.CLASS_NAME.as(CLASS_NAME), s.METHOD.as(METHOD_NAME))
            .from(s).where(s.GRAPH_NAME.eq(graph))
            .unionAll(dsl
                .select(f.SOURCE_NAME, f.SOURCE_LINE, f.SOURCE_COLUMN, f.CLASS_NAME, f.METHOD)
                .from(f).where(f.GRAPH_NAME.eq(graph)))
            .asTable("stated");
    }

    /**
     * What both table arms demand of an element: that it named no key. An element naming both is
     * the key arm's, the key being the route and the table an assertion about where it ends, and
     * the two arms would otherwise answer for one link and meet on its key.
     */
    private static Condition namesNoKey(GraphitronFieldChainLink cl) {
        var k = GRAPHITRON_AST_FIELD_REFERENCE_KEY_STEP_ENTRY;
        var kf = GRAPHITRON_AST_FIELD_REFERENCE_FOR_KEY_STEP_ENTRY;
        return notExists(selectOne().from(k)
            .where(k.GRAPH_NAME.eq(cl.GRAPH_NAME), k.SOURCE_NAME.eq(cl.SOURCE_NAME),
                k.SOURCE_LINE.eq(cl.SOURCE_LINE), k.SOURCE_COLUMN.eq(cl.SOURCE_COLUMN)))
            .and(notExists(selectOne().from(kf)
                .where(kf.GRAPH_NAME.eq(cl.GRAPH_NAME), kf.SOURCE_NAME.eq(cl.SOURCE_NAME),
                    kf.SOURCE_LINE.eq(cl.SOURCE_LINE), kf.SOURCE_COLUMN.eq(cl.SOURCE_COLUMN))));
    }

    /**
     * The table an element named, whichever of the two directives wrote it, on the terms
     * {@link #spellings} states for the key: one fact with one shape, so one relation.
     */
    private static Table<?> tableSpellings(DSLContext dsl, String graph) {
        var s = GRAPHITRON_AST_FIELD_REFERENCE_TABLE_STEP_ENTRY;
        var f = GRAPHITRON_AST_FIELD_REFERENCE_FOR_TABLE_STEP_ENTRY;
        return dsl
            .select(s.SOURCE_NAME.as(SITE_NAME), s.SOURCE_LINE.as(SITE_LINE),
                s.SOURCE_COLUMN.as(SITE_COLUMN),
                s.TABLE_REF_NAMESPACE_PART_UPPER.as(NAMESPACE_UPPER),
                s.TABLE_REF_NAME_PART_UPPER.as(NAME_UPPER))
            .from(s).where(s.GRAPH_NAME.eq(graph))
            .unionAll(dsl
                .select(f.SOURCE_NAME, f.SOURCE_LINE, f.SOURCE_COLUMN,
                    f.TABLE_REF_NAMESPACE_PART_UPPER, f.TABLE_REF_NAME_PART_UPPER)
                .from(f).where(f.GRAPH_NAME.eq(graph)))
            .asTable("named");
    }

    /**
     * The key an element named, whichever of the two directives wrote it: one fact with one shape,
     * so one relation. A bag rather than a set costs nothing, no written position being under both
     * directives at once.
     */
    private static Table<?> spellings(DSLContext dsl, String graph) {
        var s = GRAPHITRON_AST_FIELD_REFERENCE_KEY_STEP_ENTRY;
        var f = GRAPHITRON_AST_FIELD_REFERENCE_FOR_KEY_STEP_ENTRY;
        return dsl
            .select(s.SOURCE_NAME.as(SITE_NAME), s.SOURCE_LINE.as(SITE_LINE),
                s.SOURCE_COLUMN.as(SITE_COLUMN),
                s.KEY_REF_NAMESPACE_PART_UPPER.as(NAMESPACE_UPPER),
                s.KEY_REF_NAME_PART_UPPER.as(NAME_UPPER))
            .from(s).where(s.GRAPH_NAME.eq(graph))
            .unionAll(dsl
                .select(f.SOURCE_NAME, f.SOURCE_LINE, f.SOURCE_COLUMN,
                    f.KEY_REF_NAMESPACE_PART_UPPER, f.KEY_REF_NAME_PART_UPPER)
                .from(f).where(f.GRAPH_NAME.eq(graph)))
            .asTable("spellings");
    }

    /**
     * Where each chain departs at this position: the enclosing type's binding at position zero,
     * which {@code graphitron_field_table} holds beside the target, and the previous link's arrival
     * after that. The two shapes carry the same column names, so an arm joins one thing whichever
     * position it is resolving.
     *
     * <p>Both are matched on this reading's instant, the second because it is a row this reading
     * wrote a pass ago and a previous reading's row is one the sweep has yet to take.
     */
    private static Table<?> departures(DSLContext dsl, String graph, int position,
                                       LocalDateTime touchedAt) {
        if (position == 0) {
            var ft = GRAPHITRON_FIELD_TABLE;
            return dsl
                .select(ft.TYPE_NAME, ft.FIELD_NAME,
                    ft.TO_SOURCE_NAME.as(TARGET_SOURCE_NAME), ft.TO_SCHEMA.as(TARGET_SCHEMA),
                    ft.TO_TABLE.as(TARGET_TABLE),
                    ft.FROM_SOURCE_NAME.as(FROM_SOURCE_NAME), ft.FROM_SCHEMA.as(FROM_SCHEMA),
                    ft.FROM_TABLE.as(FROM_TABLE))
                .from(ft)
                .where(ft.GRAPH_NAME.eq(graph))
                .and(ft.TOUCHED_AT.eq(touchedAt))
                .and(ft.FROM_SOURCE_NAME.isNotNull())
                .asTable("departures");
        }
        var l = GRAPHITRON_FIELD_TABLE_LINK;
        return dsl
            .select(l.TYPE_NAME, l.FIELD_NAME,
                l.TARGET_SOURCE_NAME.as(TARGET_SOURCE_NAME), l.TARGET_SCHEMA.as(TARGET_SCHEMA),
                l.TARGET_TABLE.as(TARGET_TABLE),
                l.TO_SOURCE_NAME.as(FROM_SOURCE_NAME), l.TO_SCHEMA.as(FROM_SCHEMA),
                l.TO_TABLE.as(FROM_TABLE))
            .from(l)
            .where(l.GRAPH_NAME.eq(graph))
            .and(l.TOUCHED_AT.eq(touchedAt))
            .and(l.POSITION.eq(position - 1))
            .asTable("departures");
    }
}
