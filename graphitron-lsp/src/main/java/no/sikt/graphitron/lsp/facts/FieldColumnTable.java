package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Record1;
import org.jooq.Records;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STOP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_NEED_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.selectOne;

/**
 * Which table a column name written at a field's site resolves against, when that table is not the
 * one the field's own parent is bound to: read off the join paths of the field's occurrences, their
 * stops and the path claim written at the field, keyed on the coordinate the cursor sits in.
 *
 * <p>The three arms of the answer are the three a surface needs, and the third is the absence of a
 * value. {@link Scope.Resolved} names a table, so the surface reads that table's columns instead of
 * the parent's. {@link Scope.Silent} says no column name resolves here at all, and the parent's own
 * scope must not stand in, because offering or validating against it would point the author at the
 * wrong end of a join. An empty result says there is nothing to add, which is the reading a surface
 * already falls back to: the parent's own binding answers.
 *
 * <p>A field whose producer claims disagree is silent. A field with a written path resolves where
 * its occurrences' join paths land, and is silent where one stopped or none was walked. A field with
 * no written path resolves where it lands only when it returns an object; a scalar there reads its
 * parent's own table, which is no override.
 */
public final class FieldColumnTable {

    private FieldColumnTable() {}

    /** What a column name at a field's site resolves against, where the parent's scope is not it. */
    public sealed interface Scope permits Scope.Resolved, Scope.Silent {

        /** Resolve the name against {@code table} rather than against the parent's binding. */
        record Resolved(CatalogTable table) implements Scope {}

        /** No name resolves at this site, and the parent's binding must not answer for it. */
        record Silent() implements Scope {}
    }

    /**
     * One need of one of the field's occurrences: its target's kind, where it lands, whether a join
     * path was walked there and whether it stopped. Where it lands is the join path's table where one
     * was walked, a written path's landing being stated there, and the need's own landing otherwise,
     * a table-bound target's own table.
     */
    private record Need(String targetKind, String sourceName, String schema, String table, Boolean walked,
                        Boolean stopped) {}

    /**
     * The scope for one coordinate, empty where the parent's own binding is the answer. Empty is
     * also what an unavailable store gives, which lands on the same fall-back a surface takes when it
     * cannot resolve a shape: the parent's scope, or nothing.
     */
    public static Optional<Scope> of(StoreHandle store, String typeName, String fieldName) {
        String graph = store.graphName();
        String coordinate = typeName + "." + fieldName;
        var pc = GRAPHITRON_PRODUCER_CLAIM;
        var c = GRAPHITRON_JOIN_PATH_CLAIM;
        var o = GRAPHITRON_OCCURRENCE;
        var n = GRAPHITRON_PATH_NEED_TABLE;
        var jp = GRAPHITRON_JOIN_PATH;
        var st = GRAPHITRON_JOIN_PATH_STOP;
        var t = GRAPHITRON_TYPE;
        var row = store.dsl()
            .select(
                multiset(select(pc.KIND).from(pc)
                    .where(pc.GRAPH_NAME.eq(graph), pc.COORDINATE.eq(coordinate)))
                    .convertFrom(rows -> rows.map(Record1::value1)),
                field(exists(selectOne().from(c)
                    .where(c.GRAPH_NAME.eq(graph), c.ELEMENT_COORDINATE.eq(coordinate)))),
                multiset(selectDistinct(t.KIND, coalesce(jp.TARGET_SOURCE_NAME, n.LANDING_SOURCE_NAME),
                        coalesce(jp.TARGET_SCHEMA, n.LANDING_SCHEMA), coalesce(jp.TARGET_TABLE, n.LANDING_TABLE),
                        field(jp.TARGET_TABLE.isNotNull()), field(st.POSITION.isNotNull()))
                    .from(o)
                    .join(n).on(n.GRAPH_NAME.eq(o.GRAPH_NAME), n.COORDINATE.eq(o.COORDINATE))
                    .leftJoin(jp).on(jp.GRAPH_NAME.eq(n.GRAPH_NAME), jp.COORDINATE.eq(n.COORDINATE),
                        jp.TARGET_TYPE.eq(n.TARGET_TYPE))
                    .leftJoin(st).on(st.GRAPH_NAME.eq(n.GRAPH_NAME), st.COORDINATE.eq(n.COORDINATE),
                        st.TARGET_TYPE.eq(n.TARGET_TYPE))
                    .leftJoin(t).on(t.GRAPH_NAME.eq(n.GRAPH_NAME), t.TYPE_NAME.eq(n.TARGET_TYPE))
                    .where(o.GRAPH_NAME.eq(graph), o.ELEMENT_COORDINATE.eq(coordinate),
                        o.KIND.in("FIELD", "INPUT_FIELD"))
                    .orderBy(coalesce(jp.TARGET_SCHEMA, n.LANDING_SCHEMA), coalesce(jp.TARGET_TABLE, n.LANDING_TABLE)))
                    .convertFrom(rows -> rows.map(Records.mapping(Need::new))))
            .fetchOne();
        if (row == null) {
            return Optional.empty();
        }
        List<String> producers = row.value1();
        boolean written = Boolean.TRUE.equals(row.value2());
        List<Need> needs = row.value3();
        // Two kinds of producer is a conflict, but for @lookupKey beside @routine, which is deferred.
        if (producers.size() > 1 && !Set.copyOf(producers).equals(Set.of("LOOKUP", "ROUTINE"))) {
            return Optional.of(new Scope.Silent());
        }
        // A written path's landing is stated on its join path alone.
        if (written) {
            if (needs.isEmpty() || needs.stream().anyMatch(need -> !Boolean.TRUE.equals(need.walked())
                    || Boolean.TRUE.equals(need.stopped()))) {
                return Optional.of(new Scope.Silent());
            }
            return Optional.of(resolved(needs.getFirst()));
        }
        return needs.stream()
            .filter(need -> need.table() != null && need.targetKind() != null
                && !need.targetKind().equals("SCALAR") && !need.targetKind().equals("ENUM"))
            .findFirst()
            .map(FieldColumnTable::resolved);
    }

    private static Scope resolved(Need need) {
        return new Scope.Resolved(new CatalogTable(need.sourceName(), need.schema(), need.table()));
    }
}
