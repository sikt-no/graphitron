package no.sikt.graphitron.model.derive;

import org.jooq.CommonTableExpression;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Select;
import org.jooq.Table;

import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_STEP_HOP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_STEP_TARGET_KEYED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_STEP_TARGET_KEYLESS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_RESOLVED_TYPE_BINDING;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.denseRank;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.partitionBy;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.SQLDataType.BOOLEAN;
import static org.jooq.impl.SQLDataType.INTEGER;
import static org.jooq.impl.SQLDataType.VARCHAR;

/**
 * The capture-cadence writer of the two field-site reference-target relations: where each element
 * of a field's {@code @reference} path actually lands, walked from the enclosing type's table
 * binding one element at a time.
 *
 * <p>Recursive because the arms are sequential and nothing else about them is: an element's
 * departure is the previous element's arrival, and only the first element's departure is known
 * without walking, being the type's own binding. A row exists only for an element the chain can be
 * shown to reach, so absence here means "not reached" and never "resolves to nothing in
 * particular".
 *
 * <p>Two relations and not one, for the reason the hop below it is two: the rows have two key
 * shapes, inherited through a recursion that carries the hop's columns forward unchanged. On the
 * {@code KEY} and {@code TABLE} elements the constraint and its orientation are identity; on the
 * other two there is no key to enumerate, so the coordinate with both table triples is already
 * total. The view over the two carries the canonical name every reader spells.
 *
 * <p>Two statements per graph, each carrying the same walk and the same ranking over it and
 * differing only in a closing arm filter. Taking the filter after the ranking rather than before
 * it is load-bearing: the two counts are over the whole partition, both arms included, so an arm
 * filter applied inside the window would change the answer rather than partition it. That
 * evaluates the walk twice per capture, which is measured in hundredths of a second, against a
 * staged intermediate that would have to be reconciled like a third relation.
 */
public final class FieldReferenceStepTargets {

    private FieldReferenceStepTargets() {}

    /** The walk's own columns, in the order every statement here carries them. */
    private static final List<String> CHAIN_COLUMNS = List.of(
        "graph_name", "type_name", "field_name", "ordinal", "position", "via", "key_matched_by",
        "from_source_name", "from_schema", "from_table",
        "to_source_name", "to_schema", "to_table", "constraint_name", "fk_on_from");

    /** One of the chain's columns, qualified by the recursive term's own name. */
    private static Field<?> chainField(Name chain, String column) {
        return switch (column) {
            case "ordinal", "position" -> field(chain.append(column), INTEGER);
            case "fk_on_from" -> field(chain.append(column), BOOLEAN);
            default -> field(chain.append(column), VARCHAR);
        };
    }

    /** Reconciles the graph's reference targets: clears both partitions, then re-derives them. */
    public static void derive(DSLContext dsl, String graphName) {
        var keyed = GRAPHITRON_FIELD_REFERENCE_STEP_TARGET_KEYED;
        var keyless = GRAPHITRON_FIELD_REFERENCE_STEP_TARGET_KEYLESS;
        dsl.deleteFrom(keyed).where(keyed.GRAPH_NAME.eq(graphName)).execute();
        dsl.deleteFrom(keyless).where(keyless.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(keyed, List.of(keyed.GRAPH_NAME, keyed.TYPE_NAME, keyed.FIELD_NAME,
                keyed.ORDINAL, keyed.POSITION, keyed.VIA, keyed.KEY_MATCHED_BY,
                keyed.FROM_SOURCE_NAME, keyed.FROM_SCHEMA, keyed.FROM_TABLE,
                keyed.TO_SOURCE_NAME, keyed.TO_SCHEMA, keyed.TO_TABLE,
                keyed.CONSTRAINT_NAME, keyed.FK_ON_FROM, keyed.TARGETS, keyed.CANDIDATES))
            .select(walked(dsl, graphName, true))
            .execute();
        dsl.insertInto(keyless, List.of(keyless.GRAPH_NAME, keyless.TYPE_NAME, keyless.FIELD_NAME,
                keyless.ORDINAL, keyless.POSITION, keyless.VIA,
                keyless.FROM_SOURCE_NAME, keyless.FROM_SCHEMA, keyless.FROM_TABLE,
                keyless.TO_SOURCE_NAME, keyless.TO_SCHEMA, keyless.TO_TABLE,
                keyless.TARGETS, keyless.CANDIDATES))
            .select(walked(dsl, graphName, false))
            .execute();
    }

    /**
     * The walk, ranked, with one arm taken out of it. One text for both arms, so the two
     * statements cannot drift apart about what the chain is or what the counts are over.
     */
    private static Select<? extends Record> walked(DSLContext dsl, String graphName,
                                                   boolean keyedArm) {
        Name chain = name("chain");
        var ranked = ranked(dsl, chain).asTable("ranked");
        List<Field<?>> projected = new java.util.ArrayList<>();
        for (String column : CHAIN_COLUMNS) {
            if (!keyedArm && (column.equals("key_matched_by") || column.equals("constraint_name")
                || column.equals("fk_on_from"))) {
                continue;
            }
            projected.add(ranked.field(column));
        }
        var coordinate = List.<Field<?>>of(ranked.field("graph_name"), ranked.field("type_name"),
            ranked.field("field_name"), ranked.field("ordinal"), ranked.field("position"));
        projected.add(max(ranked.field("target_rank", Integer.class)).over(partitionBy(coordinate))
            .cast(INTEGER));
        projected.add(count().over(partitionBy(coordinate)).cast(INTEGER));

        var arm = ranked.field("via", String.class).in(keyedArm
            ? List.of("KEY", "TABLE") : List.of("NAME_MATCH", "CONDITION"));
        return dsl.withRecursive(chainOf(dsl, chain, graphName))
            .select(projected)
            .from(ranked)
            .where(arm);
    }

    /** The finished chain with each element's arrivals ranked, which is what both counts read. */
    private static Select<? extends Record> ranked(DSLContext dsl, Name chain) {
        List<Field<?>> projected = new java.util.ArrayList<>(
            CHAIN_COLUMNS.stream().map(column -> chainField(chain, column)).toList());
        projected.add(denseRank().over(partitionBy(chainField(chain, "graph_name"),
                chainField(chain, "type_name"), chainField(chain, "field_name"),
                chainField(chain, "ordinal"), chainField(chain, "position"))
            .orderBy(chainField(chain, "to_source_name"), chainField(chain, "to_schema"),
                chainField(chain, "to_table")))
            .as("target_rank"));
        return dsl.select(projected).from(table(chain));
    }

    /**
     * The chain itself: the hops departing the enclosing type's binding, then every hop whose
     * departure is a reached arrival one position further along.
     */
    private static CommonTableExpression<?> chainOf(DSLContext dsl, Name chain,
                                                         String graphName) {
        var hop = GRAPHITRON_FIELD_REFERENCE_STEP_HOP;
        var step = GRAPHITRON_FIELD_REFERENCE_STEP_HOP.as("step");
        var bound = GRAPHITRON_RESOLVED_TYPE_BINDING;
        var previous = table(chain).as("p");

        var seed = dsl
            .select(hop.GRAPH_NAME, hop.TYPE_NAME, hop.FIELD_NAME, hop.ORDINAL, hop.POSITION,
                hop.VIA, hop.KEY_MATCHED_BY, hop.FROM_SOURCE_NAME, hop.FROM_SCHEMA, hop.FROM_TABLE,
                hop.TO_SOURCE_NAME, hop.TO_SCHEMA, hop.TO_TABLE, hop.CONSTRAINT_NAME,
                hop.FK_ON_FROM)
            .from(hop)
            .join(bound).on(bound.GRAPH_NAME.eq(hop.GRAPH_NAME))
                .and(bound.TYPE_NAME.eq(hop.TYPE_NAME))
                .and(bound.TABLE_SOURCE_NAME.eq(hop.FROM_SOURCE_NAME))
                .and(bound.TABLE_SCHEMA.eq(hop.FROM_SCHEMA))
                .and(bound.TABLE_NAME.eq(hop.FROM_TABLE))
            .where(hop.POSITION.eq(0))
            .and(hop.GRAPH_NAME.eq(graphName));

        var recursive = dsl
            .select(step.GRAPH_NAME, step.TYPE_NAME, step.FIELD_NAME, step.ORDINAL, step.POSITION,
                step.VIA, step.KEY_MATCHED_BY, step.FROM_SOURCE_NAME, step.FROM_SCHEMA,
                step.FROM_TABLE, step.TO_SOURCE_NAME, step.TO_SCHEMA, step.TO_TABLE,
                step.CONSTRAINT_NAME, step.FK_ON_FROM)
            .from(previous)
            .join(step).on(step.GRAPH_NAME.eq(walkedField(previous, "graph_name", VARCHAR)))
                .and(step.TYPE_NAME.eq(walkedField(previous, "type_name", VARCHAR)))
                .and(step.FIELD_NAME.eq(walkedField(previous, "field_name", VARCHAR)))
                .and(step.ORDINAL.eq(walkedField(previous, "ordinal", INTEGER)))
                .and(step.POSITION.eq(walkedField(previous, "position", INTEGER).plus(1)))
                .and(step.FROM_SOURCE_NAME.eq(walkedField(previous, "to_source_name", VARCHAR)))
                .and(step.FROM_SCHEMA.eq(walkedField(previous, "to_schema", VARCHAR)))
                .and(step.FROM_TABLE.eq(walkedField(previous, "to_table", VARCHAR)));

        return chain.fields(CHAIN_COLUMNS.toArray(String[]::new)).as(seed.union(recursive));
    }

    /** One column of the accumulated chain, qualified by the alias the recursive term joins it as. */
    private static <T> Field<T> walkedField(Table<?> previous, String column,
                                            org.jooq.DataType<T> type) {
        return field(name(previous.getName(), column), type);
    }
}
