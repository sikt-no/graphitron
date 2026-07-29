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
 * condition row; the renderer composes the neutral condition from that absence. {@link #orderBy}
 * is absent when the composition is unordered, and a single-record launcher is unordered by
 * construction (the model's ordering contract gives single-value fields no spec); the compact
 * constructor keeps the illegal pair unrepresentable rather than trusting the derivation.
 * {@link #projection} is the first cross-command edge: the projection unit whose
 * {@code $project} supplies the select list.
 *
 * <p>No invocation-strategy slot yet, deliberately: every row this relation holds today is
 * invoked directly, a fact entailed by the producer's membership (fan-out coordinates are on the
 * not-yet-migrated dial), so a one-value slot would restate the membership predicate with
 * nothing binding the two. The slot lands with the fanned-root slice, where it becomes the fact
 * distinguishing two otherwise-identical rows.
 */
public record LauncherCommand(
    UnitMethodRef unit,
    FieldCoordinates coordinate,
    TableRef table,
    UnitRef projection,
    GlueCall where,
    Ordering orderBy,
    ResultShape result
) {

    public LauncherCommand {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(coordinate, "a launcher row is keyed by its field coordinate");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(projection, "every launcher names the projection unit it selects from");
        Objects.requireNonNull(result, "result");
        if (result == ResultShape.SINGLE_RECORD && orderBy != null) {
            throw new IllegalArgumentException(
                "a single-record launcher is unordered by construction; the illegal pair must stay unrepresentable");
        }
    }
}
