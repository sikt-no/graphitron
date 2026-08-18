package no.sikt.graphitron.command;

import graphql.schema.FieldCoordinates;

import java.util.Objects;

/**
 * One row of the launcher command relation: the named {@code rows<Field>}-shaped unit owning
 * one coordinate's whole payload production as data — a query composition for the SQL-composing
 * source arms, a delegated service invocation (plus, for the table arm, its re-projection) for
 * the {@code @service} arms.
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
        // A routine chain runs single-tenant: no fanned routine emission exists (the legacy path
        // failed generation on the pair). There is no result-axis half, the same absence the
        // discriminated arm below states: the chain paginates like any other root read, and the
        // residual invariants on the pair (pagination-requires-ordering, the facet carrier's own
        // admission) are enforced where they are owned, so a check here would invent one with no
        // parse-boundary owner to mirror.
        if (source instanceof LaunchSource.RoutineChain
                && !(tenancy instanceof TenantStrategy.Single)) {
            throw new IllegalArgumentException(
                "a routine-chain launcher runs single-tenant; got "
                + tenancy.getClass().getSimpleName());
        }
        // A discriminated interface runs single-tenant: the fan-out ladder rejects @tenantFanOut
        // on interface-typed fields. There is no result-axis half here: the arm paginates, and
        // the residual invariants on the pair are enforced where they are owned
        // (pagination-requires-ordering in the validator, facet rejection at the SDL boundary),
        // so a check here would invent one with no parse-boundary owner to mirror.
        if (source instanceof LaunchSource.DiscriminatedTable
                && !(tenancy instanceof TenantStrategy.Single)) {
            throw new IllegalArgumentException(
                "a discriminated-interface launcher runs single-tenant; got "
                + tenancy.getClass().getSimpleName());
        }
        // The batched lookup child mirrors the keyed-lookup root's pair: the fan-out ladder
        // rejects @tenantFanOut with @lookupKey (fanning breaks one-row-per-key), and the
        // resolver/validator pair rejects the connection return on both lookup arms.
        if (source instanceof LaunchSource.CorrelatedLookupChain) {
            if (!(tenancy instanceof TenantStrategy.Single)) {
                throw new IllegalArgumentException(
                    "a batched-lookup launcher runs single-tenant; got "
                    + tenancy.getClass().getSimpleName());
            }
            if (result instanceof ResultShape.Connection) {
                throw new IllegalArgumentException(
                    "a batched-lookup launcher never paginates; lookup fields must not return"
                    + " a connection");
            }
        }
        // The service arms and the LoaderDelegated result pin each other in both directions:
        // the service payload derives wholly from the source and delivery axes, so the result
        // slot is typed vacuity read at no site, and no other source may claim it. Never
        // Fanned (the @tenantFanOut ladder rejects the @service pair at classify time). The
        // Connection cell has no child-level classifier mirror (only the root @service
        // rejection exists); a child @service declared @asConnection classifies today with the
        // Connection wrapper riding only the loader's valueIsList fact, a recorded mirror gap
        // beside the batched lookup's two.
        boolean serviceSource = source instanceof LaunchSource.ServiceCall
            || source instanceof LaunchSource.ServiceTableLift;
        if (serviceSource != result instanceof ResultShape.LoaderDelegated) {
            throw new IllegalArgumentException(
                "the service arms and the LoaderDelegated result imply each other; got "
                + source.getClass().getSimpleName() + " with "
                + result.getClass().getSimpleName());
        }
        if (serviceSource && !(tenancy instanceof TenantStrategy.Single)) {
            throw new IllegalArgumentException(
                "a service-delegating launcher runs single-tenant (the fan-out ladder rejects"
                + " @tenantFanOut with @service); got " + tenancy.getClass().getSimpleName());
        }
        // The batched pivot runs single-tenant and answers one record per key: the fan-out
        // ladder's non-list rung (no.sikt.graphitron.rewrite.TenantBindingIndex) rejects
        // @tenantFanOut on a non-list field and a @pivot field is single by classification,
        // and one projection record per parent is the pivot invariant itself (the
        // key-preserving left join exists to guarantee it).
        if (source instanceof LaunchSource.PivotAggregate) {
            if (!(tenancy instanceof TenantStrategy.Single)) {
                throw new IllegalArgumentException(
                    "a batched-pivot launcher runs single-tenant; got "
                    + tenancy.getClass().getSimpleName());
            }
            if (!(result instanceof ResultShape.SingleRecord)) {
                throw new IllegalArgumentException(
                    "a batched-pivot launcher answers one record per key (the pivot"
                    + " invariant); got " + result.getClass().getSimpleName());
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
        // The reentry arms and the ReturningKeyed delivery imply each other in both directions
        // (the service/LoaderDelegated precedent): the keys parameter only makes sense against
        // a key-restricted source, and no other delivery can hand captured RETURNING keys in.
        boolean reentrySource = source instanceof LaunchSource.Reentry;
        if (reentrySource != invocation instanceof Invocation.ReturningKeyed) {
            throw new IllegalArgumentException(
                "the reentry arms and the ReturningKeyed delivery imply each other; got "
                + source.getClass().getSimpleName() + " with "
                + invocation.getClass().getSimpleName());
        }
        if (reentrySource) {
            // Single-tenant only: the fan-out ladder
            // (no.sikt.graphitron.rewrite.TenantBindingIndex) rejects @tenantFanOut on
            // mutation-side coordinates, so a fanned companion is unreachable by classification.
            if (!(tenancy instanceof TenantStrategy.Single)) {
                throw new IllegalArgumentException(
                    "a reentry companion runs single-tenant (the fan-out ladder rejects"
                    + " @tenantFanOut on mutations); got " + tenancy.getClass().getSimpleName());
            }
            // Never a connection (no paginating mutation payload exists) and never
            // LoaderDelegated (that shape is the service arms' biconditional partner).
            if (result instanceof ResultShape.Connection
                || result instanceof ResultShape.LoaderDelegated) {
                throw new IllegalArgumentException(
                    "a reentry companion answers the written records themselves; got "
                    + result.getClass().getSimpleName());
            }
        }
    }
}
