package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.impl.DSL;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ERRORS_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_OUTCOME;
import static no.sikt.graphitron.model.Tables.GRAPHQL_POLY_MEMBER;

/**
 * The error channels as the store states them: which outcome type each producer catches into
 * ({@code graphitron_producer_outcome}), and for each outcome type the {@code @error} types its
 * errors field holds, in the order a throw is matched against them ({@code graphitron_errors_field}
 * over {@code graphql_poly_member}).
 *
 * <p>A channel's dispatch table is a fact about its outcome type, since the handlers are the
 * errors field's members and every producer of one outcome type catches into the same field. So
 * there is one table per outcome type, and a producer finds its table through its outcome type.
 *
 * <p>Member order is a union's declaration order, and an interface's implementors by name, which
 * is the order graphql-java lists them in.
 *
 * @param outcomeByProducer the outcome type each producer catches into, by {@code Type.field}
 * @param membersByOutcome  each outcome type's {@code @error} types, in match order, by type name
 */
public record ErrorChannelRelation(Map<String, String> outcomeByProducer,
                                   Map<String, List<String>> membersByOutcome) {

    public ErrorChannelRelation {
        outcomeByProducer = Map.copyOf(outcomeByProducer);
        // Sorted, so the tables are emitted in one order whatever order they were read in.
        var sorted = new TreeMap<String, List<String>>();
        membersByOutcome.forEach((outcome, members) -> sorted.put(outcome, List.copyOf(members)));
        membersByOutcome = Collections.unmodifiableSortedMap(sorted);
    }

    /** No channel at all: what a generator run without a store reads. */
    public static ErrorChannelRelation empty() {
        return new ErrorChannelRelation(Map.of(), Map.of());
    }

    /** Every channel of the store's graph. */
    public static ErrorChannelRelation read(StoreHandle store) {
        var o = GRAPHITRON_PRODUCER_OUTCOME;
        var outcomeByProducer = new LinkedHashMap<String, String>();
        store.dsl()
            .select(o.TYPE_NAME, o.FIELD_NAME, o.OUTCOME_TYPE_NAME)
            .from(o)
            .where(o.GRAPH_NAME.eq(store.graphName()))
            .forEach(r -> outcomeByProducer.put(r.value1() + "." + r.value2(), r.value3()));

        var e = GRAPHITRON_ERRORS_FIELD;
        var m = GRAPHQL_POLY_MEMBER;
        var membersByOutcome = new LinkedHashMap<String, List<String>>();
        store.dsl()
            .select(e.TYPE_NAME, m.MEMBER_TYPE_NAME)
            .from(e)
            .join(m).on(m.GRAPH_NAME.eq(e.GRAPH_NAME), m.CONTAINER_NAME.eq(e.CONTAINER_NAME))
            .where(e.GRAPH_NAME.eq(store.graphName()))
            .orderBy(e.TYPE_NAME,
                DSL.when(m.CONTAINER_KIND.eq("UNION"), m.POSITION).otherwise(DSL.inline(0)),
                m.MEMBER_TYPE_NAME)
            .forEach(r -> membersByOutcome
                .computeIfAbsent(r.value1(), ignored -> new ArrayList<>())
                .add(r.value2()));
        return new ErrorChannelRelation(outcomeByProducer, membersByOutcome);
    }

    /** The outcome type the producer at {@code typeName.fieldName} catches into, if it catches. */
    public Optional<String> outcomeOf(String typeName, String fieldName) {
        return Optional.ofNullable(outcomeByProducer.get(typeName + "." + fieldName));
    }
}
