package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_HANDED_CLASS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_HANDED_CLASS_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_HANDED_RECORD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_HANDED_RECORD_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_CLAIM_OBJECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_CLAIM_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_PRECEDENCE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_source_claim} and its subtypes: what each rule claims an
 * occurrence's values come from, one rule view per rule, marked and swept by the stage owning the
 * rule.
 *
 * <p>Two stages own the rows, by rule. The {@code occurrence-source-claims} stage writes the rules
 * that read nothing a depth writes, for the whole graph ahead of {@link OccurrenceDepths}; the depth
 * loop writes the rules that read the parent's rows, a depth at a time, before
 * {@link OccurrenceSources} states the sources the claims that take effect name.
 */
public final class OccurrenceSourceClaims {

    private OccurrenceSourceClaims() {}

    /** One rule: its name, the kind it claims, and the view stating it. */
    private record Rule(String name, String kind) {
        Table<?> view() {
            var snake = new StringBuilder();
            for (char c : name.toCharArray()) {
                snake.append(Character.isUpperCase(c) ? "_" + Character.toLowerCase(c) : String.valueOf(c));
            }
            return table(org.jooq.impl.DSL.name(("graphitron_occurrence_source_claim_" + snake + "_rule").toUpperCase()));
        }
    }

    /** The stage writing the rules that read nothing a depth writes: the starts, the overrides and the parent field's statics. */
    private static final String AHEAD = "occurrence-source-claims";

    /** The stage writing the rules that read the parent occurrence's rows, which the depth above wrote. */
    private static final String PER_DEPTH = "occurrence-depths";

    /** The rules {@code stage} owns, as {@code graphitron_occurrence_source_precedence} states them. */
    private static List<Rule> rules(DSLContext dsl, String stage) {
        var p = GRAPHITRON_OCCURRENCE_SOURCE_PRECEDENCE;
        return dsl.select(p.RULE, p.KIND).from(p).where(p.OWNER_STAGE.eq(stage)).orderBy(p.PRIORITY)
            .fetch(r -> new Rule(r.value1(), r.value2()));
    }

    /**
     * The stage: the class each field hands back and the tables it is a record of, which the rules
     * under such a field read by its key; then the rules that read nothing a depth writes, for the
     * whole graph, and their sweep.
     */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        writeHanded(dsl, graph, touchedAt);
        var rules = rules(dsl, AHEAD);
        for (Rule rule : rules) {
            write(dsl, graph, null, touchedAt, rule);
        }
        sweep(dsl, graph, null, touchedAt, rules);
    }

    /**
     * The claims of the rules that read the parent's rows, for the occurrences at {@code depth}, then
     * the sweep of their stale claims there, so the sources read only this reading's.
     */
    public static void writeDepth(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        var rules = rules(dsl, PER_DEPTH);
        for (Rule rule : rules) {
            write(dsl, graph, depth, touchedAt, rule);
        }
        sweep(dsl, graph, depth, touchedAt, rules);
    }

    /** The handed classes, then the tables they are records of, each swept of what this reading did not state. */
    private static void writeHanded(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var h = GRAPHITRON_FIELD_HANDED_CLASS;
        var hr = GRAPHITRON_FIELD_HANDED_CLASS_RULE;
        dsl.insertInto(h)
            .columns(h.GRAPH_NAME, h.TYPE_NAME, h.FIELD_NAME, h.CLASS_NAME, h.TOUCHED_AT)
            .select(selectDistinct(hr.GRAPH_NAME, hr.TYPE_NAME, hr.FIELD_NAME, hr.CLASS_NAME,
                    val(touchedAt, h.TOUCHED_AT))
                .from(hr).where(hr.GRAPH_NAME.eq(graph)))
            .onConflict(h.GRAPH_NAME, h.TYPE_NAME, h.FIELD_NAME)
            .doUpdate()
            .set(h.CLASS_NAME, excluded(h.CLASS_NAME))
            .set(h.TOUCHED_AT, excluded(h.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(h).where(h.GRAPH_NAME.eq(graph)).and(h.TOUCHED_AT.ne(touchedAt)).execute();
        var t = GRAPHITRON_FIELD_HANDED_RECORD;
        var tr = GRAPHITRON_FIELD_HANDED_RECORD_RULE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA,
                t.TABLE_NAME, t.TOUCHED_AT)
            .select(selectDistinct(tr.GRAPH_NAME, tr.TYPE_NAME, tr.FIELD_NAME, tr.TABLE_SOURCE_NAME,
                    tr.TABLE_SCHEMA, tr.TABLE_NAME, val(touchedAt, t.TOUCHED_AT))
                .from(tr).where(tr.GRAPH_NAME.eq(graph)))
            .onConflict(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA,
                t.TABLE_NAME)
            .doUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(t).where(t.GRAPH_NAME.eq(graph)).and(t.TOUCHED_AT.ne(touchedAt)).execute();
    }

    /** One rule's claims, at {@code depth} or for the whole graph where it is null, supertype first. */
    private static void write(DSLContext dsl, String graph, Integer depth, LocalDateTime touchedAt, Rule rule) {
        var v = rule.view();
        Field<String> vGraph = field(name(v.getName(), "GRAPH_NAME"), String.class);
        Field<String> vCoordinate = field(name(v.getName(), "COORDINATE"), String.class);
        Field<String> vRule = field(name(v.getName(), "RULE"), String.class);
        Field<String> vKind = field(name(v.getName(), "KIND"), String.class);
        var scope = vGraph.eq(graph).and(OccurrenceProducers.atDepth(vGraph, vCoordinate, depth));
        var c = GRAPHITRON_OCCURRENCE_SOURCE_CLAIM;
        dsl.insertInto(c)
            .columns(c.GRAPH_NAME, c.COORDINATE, c.RULE, c.KIND, c.TOUCHED_AT)
            .select(selectDistinct(vGraph, vCoordinate, vRule, vKind, val(touchedAt, c.TOUCHED_AT))
                .from(v).where(scope))
            .onConflict(c.GRAPH_NAME, c.COORDINATE, c.RULE)
            .doUpdate()
            .set(c.KIND, excluded(c.KIND))
            .set(c.TOUCHED_AT, excluded(c.TOUCHED_AT))
            .execute();
        if (rule.kind().equals("TABLE")) {
            var t = GRAPHITRON_OCCURRENCE_SOURCE_CLAIM_TABLE;
            dsl.insertInto(t)
                .columns(t.GRAPH_NAME, t.COORDINATE, t.RULE, t.KIND, t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA,
                    t.TABLE_NAME, t.TOUCHED_AT)
                .select(selectDistinct(vGraph, vCoordinate, vRule, vKind,
                        field(name(v.getName(), "TABLE_SOURCE_NAME"), String.class),
                        field(name(v.getName(), "TABLE_SCHEMA"), String.class),
                        field(name(v.getName(), "TABLE_NAME"), String.class), val(touchedAt, t.TOUCHED_AT))
                    .from(v).where(scope))
                .onConflict(t.GRAPH_NAME, t.COORDINATE, t.RULE, t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME)
                .doUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .execute();
        } else {
            var b = GRAPHITRON_OCCURRENCE_SOURCE_CLAIM_OBJECT;
            dsl.insertInto(b)
                .columns(b.GRAPH_NAME, b.COORDINATE, b.RULE, b.KIND, b.CLASS_NAME, b.TOUCHED_AT)
                .select(selectDistinct(vGraph, vCoordinate, vRule, vKind,
                        field(name(v.getName(), "CLASS_NAME"), String.class), val(touchedAt, b.TOUCHED_AT))
                    .from(v).where(scope))
                .onConflict(b.GRAPH_NAME, b.COORDINATE, b.RULE)
                .doUpdate()
                .set(b.CLASS_NAME, excluded(b.CLASS_NAME))
                .set(b.TOUCHED_AT, excluded(b.TOUCHED_AT))
                .execute();
        }
    }

    /**
     * Deletes the claims of {@code rules} this reading did not mark, at {@code depth} or for the
     * whole graph where it is null, subtypes before the claim they hang on.
     */
    private static void sweep(DSLContext dsl, String graph, Integer depth, LocalDateTime touchedAt,
                              List<Rule> rules) {
        var names = rules.stream().map(Rule::name).toList();
        for (Table<?> t : List.<Table<?>>of(GRAPHITRON_OCCURRENCE_SOURCE_CLAIM_OBJECT,
                GRAPHITRON_OCCURRENCE_SOURCE_CLAIM_TABLE, GRAPHITRON_OCCURRENCE_SOURCE_CLAIM)) {
            Field<String> tGraph = t.field("GRAPH_NAME", String.class);
            dsl.deleteFrom(t)
                .where(tGraph.eq(graph))
                .and(t.field("RULE", String.class).in(names))
                .and(t.field("TOUCHED_AT", LocalDateTime.class).ne(touchedAt))
                .and(OccurrenceProducers.atDepth(tGraph, t.field("COORDINATE", String.class), depth))
                .execute();
        }
    }
}
