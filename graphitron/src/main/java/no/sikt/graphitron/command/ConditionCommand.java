package no.sikt.graphitron.command;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.model.jooq.TableRef;

import java.util.List;

/**
 * One row of the condition command relation: everything a caller needs to call one coordinate's
 * WHERE surface. Keyed {@code (coordinate, table)}: a polymorphic root expands to one row per
 * participant table, so a participant row differs from its siblings in {@link #table} and the
 * key needs no second column. A coordinate with an empty live filter set has no row, and every
 * consumer composes the neutral condition from that absence.
 *
 * <p>{@link #glue} is total, one per row: the emitted method that composes this coordinate's
 * predicates into one {@code org.jooq.Condition},
 * {@code Condition <method>(<JooqTable> table, Map<String, Object> args)}. Callers supply
 * {@code env.getArguments()} or {@code <sf>.getArguments()}; both surfaces expose the same coerced
 * map, so the old per-site argument-source fork is one call-site expression. A row that reads the
 * request context ({@link #readsRequestContext}) appends
 * {@code graphql.schema.DataFetchingEnvironment env} to that signature, uniformly across the glue
 * method and its facet fragments; context is request-global, so the ancestor env every call site
 * holds serves it correctly. A row that reads the session ({@link #readsSession}) appends
 * {@code org.jooq.DSLContext dsl} after that, the executing connection's context, which the
 * session handle rides.
 *
 * @param readsRequestContext true when any argument the row's predicates consume reads the
 *     request context ({@code @condition(contextArguments:)}). Row-grained deliberately: the glue
 *     method and every facet fragment fork together, so a call site derives one fact per
 *     coordinate rather than one per method.
 * @param readsSession true when any argument reads the session handle
 *     ({@code argMapping: "x: $session"} or {@code "x: $session.<member>"}), so this row's
 *     emitted methods append the executing {@code DSLContext dsl}, after {@code env} where both
 *     are taken. Row-grained like {@code readsRequestContext}.
 * @param decodesNodeId true when any argument decodes a {@code @nodeId}, so this row's emitted
 *     glue lifts a decode helper (referencing the generated {@code NodeIdEncoder} and the client
 *     exception the THROW-mode helper raises). The recompile-graph projection reads the fact off
 *     the row instead of re-walking the model's filters.
 */
public record ConditionCommand(
    FieldCoordinates coordinate,
    TableRef table,
    UnitMethodRef glue,
    List<FacetFragment> facets,
    boolean readsRequestContext,
    boolean readsSession,
    boolean decodesNodeId
) {

    public ConditionCommand {
        if (coordinate == null) {
            throw new IllegalArgumentException("a condition row is keyed by its field coordinate");
        }
        if (table == null) {
            throw new IllegalArgumentException("a condition row is keyed by its resolved table");
        }
        if (glue == null) {
            throw new IllegalArgumentException("glue is total: every condition row names its glue method");
        }
        facets = facets == null ? List.of() : List.copyOf(facets);
    }
}
