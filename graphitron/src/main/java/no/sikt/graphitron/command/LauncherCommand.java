package no.sikt.graphitron.command;

import graphql.schema.FieldCoordinates;

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
 * unrepresentable rather than checked. {@link #source} is the sourcing-and-projection axis (see
 * {@link LaunchSource}); its projection refs are the first cross-command edges, the units whose
 * {@code $project} supplies the select list.
 *
 * <p>{@link #invocation} is the delivery axis (see {@link Invocation}) and {@link #tenancy}
 * the tenancy axis (see {@link TenantStrategy}), independent by measurement; the rendered
 * payload is a derived view over {@code (invocation, tenancy, result)}, not a slot of its own,
 * since the fanned strategy's marker-bearing transport ({@code List<Object>} between the
 * scatter and its collapse) is entailed by the strategy and has no business as a command fact.
 */
public record LauncherCommand(
    UnitMethodRef unit,
    FieldCoordinates coordinate,
    LaunchSource source,
    GlueCall where,
    Invocation invocation,
    TenantStrategy tenancy,
    ResultShape result
) {

    public LauncherCommand {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(coordinate, "a launcher row is keyed by its field coordinate");
        Objects.requireNonNull(source, "every launcher states how its rows are sourced and projected");
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(tenancy, "tenancy");
        Objects.requireNonNull(result, "result");
        // Cross-axis backstops, not the enforcers; each mirrors a parse-boundary rejection and
        // keeps the pair unrepresentable if that check is ever relaxed without an audit here.
        // Each backstop is stated per axis pair (source-by-tenancy, source-by-result), never
        // per position in the tree, so relaxing one axis for a family is a deliberate edit to
        // exactly one pair.
        // Fanned implies record list: the @tenantFanOut rejection ladder
        // (no.sikt.graphitron.rewrite.TenantBindingIndex) rejects non-list and @asConnection
        // shapes on a fan-out coordinate.
        if (tenancy instanceof TenantStrategy.Fanned && !(result instanceof ResultShape.RecordList)) {
            throw new IllegalArgumentException(
                "a fanned launcher's composition is a record list by classification; got "
                + result.getClass().getSimpleName());
        }
        // A routine chain runs single-tenant and never paginates: the classifier's routine
        // verdict (FieldBuilder's routine chain rules) rejects @asConnection on the chain, and
        // no fanned routine emission exists (the legacy path failed generation on the pair).
        if (source instanceof LaunchSource.RoutineChain) {
            if (!(tenancy instanceof TenantStrategy.Single)) {
                throw new IllegalArgumentException(
                    "a routine-chain launcher runs single-tenant; got "
                    + tenancy.getClass().getSimpleName());
            }
            if (result instanceof ResultShape.Connection) {
                throw new IllegalArgumentException(
                    "a routine-chain launcher never paginates; the classifier rejects @asConnection on the chain");
            }
        }
        // A discriminated interface runs single-tenant and never paginates: the fan-out ladder
        // rejects @tenantFanOut on interface-typed fields, and the classifier defers
        // @asConnection on the single-table-interface root (no paginating emission exists for
        // the participant-driven select list).
        if (source instanceof LaunchSource.DiscriminatedTable) {
            if (!(tenancy instanceof TenantStrategy.Single)) {
                throw new IllegalArgumentException(
                    "a discriminated-interface launcher runs single-tenant; got "
                    + tenancy.getClass().getSimpleName());
            }
            if (result instanceof ResultShape.Connection) {
                throw new IllegalArgumentException(
                    "a discriminated-interface launcher never paginates; the classifier defers"
                    + " @asConnection on the single-table-interface root");
            }
        }
        // A keyed lookup runs single-tenant and never paginates: the fan-out ladder rejects
        // @tenantFanOut with @lookupKey (fanning breaks one-row-per-key), and the classifier
        // rejects the connection return ("lookup fields must not return a connection").
        if (source instanceof LaunchSource.KeyedLookup) {
            if (!(tenancy instanceof TenantStrategy.Single)) {
                throw new IllegalArgumentException(
                    "a keyed-lookup launcher runs single-tenant; got "
                    + tenancy.getClass().getSimpleName());
            }
            if (result instanceof ResultShape.Connection) {
                throw new IllegalArgumentException(
                    "a keyed-lookup launcher never paginates; the classifier rejects the"
                    + " connection return on @lookupKey fields");
            }
        }
    }
}
