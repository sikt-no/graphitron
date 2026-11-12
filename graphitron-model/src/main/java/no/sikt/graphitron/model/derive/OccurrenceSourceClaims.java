package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_CLAIM_OBJECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_CLAIM_TABLE;
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

    /** The rules that read nothing a depth writes: the starts, the overrides and the parent field's statics. */
    private static final List<Rule> AHEAD = List.of(
        new Rule("declaringTable", "TABLE"), new Rule("rootRoutine", "TABLE"),
        new Rule("ownServiceRecord", "TABLE"), new Rule("recordParameter", "TABLE"),
        new Rule("argumentTarget", "TABLE"), new Rule("argumentMember", "TABLE"),
        new Rule("argumentWrite", "TABLE"));

    /** The rules that read the parent occurrence's rows, which the depth above wrote. */
    private static final List<Rule> PER_DEPTH = List.of(
        new Rule("record", "TABLE"), new Rule("object", "OBJECT"), new Rule("objectMember", "OBJECT"),
        new Rule("writePayload", "TABLE"), new Rule("writePayloadMember", "TABLE"),
        new Rule("nestedCopy", "TABLE"), new Rule("nestedPath", "TABLE"),
        new Rule("argumentCopy", "TABLE"), new Rule("argumentPath", "TABLE"),
        new Rule("inputFieldCopy", "TABLE"));

    /** The stage: the rules that read nothing a depth writes, for the whole graph, then their sweep. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        for (Rule rule : AHEAD) {
            write(dsl, graph, null, touchedAt, rule);
        }
        sweep(dsl, graph, null, touchedAt, AHEAD);
    }

    /**
     * The claims of the rules that read the parent's rows, for the occurrences at {@code depth}, then
     * the sweep of their stale claims there, so the sources read only this reading's.
     */
    public static void writeDepth(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        for (Rule rule : PER_DEPTH) {
            write(dsl, graph, depth, touchedAt, rule);
        }
        sweep(dsl, graph, depth, touchedAt, PER_DEPTH);
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
