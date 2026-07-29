package no.sikt.graphitron.command;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.Objects;

/**
 * One row of the launcher command relation: a root SELECT launcher, the named
 * {@code rows<Field>(dsl, env)} unit owning one coordinate's whole query composition as data.
 * The relation is keyed by {@link #coordinate}; the fetcher entry point owns connection
 * acquisition and invocation strategy, calls the launcher with the resolved {@code DSLContext},
 * and has nothing else left to say, so root and child stop building the same query two ways.
 *
 * <p>Every slot is build-time composition. Runtime values (argument filter values, orderBy
 * argument values) arrive through the rendered method's parameters, never through the command:
 * field and unit names are the command's vocabulary, argument values are the runtime's, the same
 * static/runtime line the projection gate draws.
 *
 * <p>{@link #where} is the coordinate's condition glue reference, copied off the condition
 * relation's row by the producer (the cross-family handshake: the condition family owns WHERE
 * production wholesale, the launcher consumes a ref). Absent exactly when the coordinate has no
 * condition row; the renderer composes the neutral condition from that absence. The ordering
 * rides {@link #result}'s arms, because the two co-vary (a single-record launcher is unordered
 * by construction; a connection is ordered by construction), so the illegal pairs are
 * unrepresentable rather than checked. {@link #projection} is the first cross-command edge: the
 * projection unit whose {@code $project} supplies the select list.
 *
 * <p>{@link #invocation} is the strategy axis (see {@link Invocation}); the rendered payload is
 * a derived view over {@code (invocation, result)}, not a slot of its own, since the fanned
 * strategy's marker-bearing transport ({@code List<Object>} between the scatter and its
 * collapse) is entailed by the strategy and has no business as a command fact.
 */
public record LauncherCommand(
    UnitMethodRef unit,
    FieldCoordinates coordinate,
    TableRef table,
    UnitRef projection,
    GlueCall where,
    Invocation invocation,
    ResultShape result
) {

    public LauncherCommand {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(coordinate, "a launcher row is keyed by its field coordinate");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(projection, "every launcher names the projection unit it selects from");
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(result, "result");
        // Backstop, not the enforcer: the parse boundary already rejects non-list and
        // @asConnection shapes on a fan-out coordinate (the @tenantFanOut rejection ladder in
        // {@link no.sikt.graphitron.rewrite.TenantBindingIndex}), so a fanned row is a record
        // list by classification; this guard keeps the pair unrepresentable if that check is
        // ever relaxed without an audit here.
        if (invocation instanceof Invocation.FannedOverTenants && !(result instanceof ResultShape.RecordList)) {
            throw new IllegalArgumentException(
                "a fanned launcher's composition is a record list by classification; got "
                + result.getClass().getSimpleName());
        }
    }
}
