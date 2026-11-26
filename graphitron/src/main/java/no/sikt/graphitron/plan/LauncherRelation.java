package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.CarrierDsl;
import no.sikt.graphitron.command.LauncherCommand;
import no.sikt.graphitron.command.UnitMethodRef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The launcher command relation of one generation run: one row per migrated root SELECT
 * coordinate, keyed by the coordinate alone. "Exactly one launcher per covered coordinate" is
 * the relation's key rather than a property a test hunts for; a producer minting two rows for
 * one coordinate fails at construction, in the {@link CoordinateIndex} every coordinate-keyed
 * relation holds. The key is the coordinate because the launch is the dispatch
 * target a coordinate's operation member set renders into (one query unit hosting select,
 * condition, orderBy and paginate members composed into one SELECT; the reentry rows host the
 * member set's keyed re-select), so a member is never a second key column here. The write
 * member is deliberately unmaterialized in this relation: the write stays with the mutation
 * entry point, and only its reentry companion launches through these rows.
 *
 * <p>The covered family widens slice by slice as {@code LauncherCommands}' migration dial
 * shrinks; the derived-fact-equals-key-set membership enforcer lands with the closing slice,
 * when the dial empties.
 *
 * <p>{@link #carrierDsl} is the run-grain carrier-routing fact (see {@link CarrierDsl}),
 * carried on the family view that renders carriers rather than copied onto every row; it moves
 * up to the plan if a second family ever reads it.
 *
 * <p>{@link #connections} is the store's answer for the run's connection fields, the connection
 * type each returns and the positions it fills, read once by the producer and carried here, beside
 * {@link #carrierDsl}, for the renderers: every connection producer names the class it builds and
 * the constructor it calls from this one answer, the one the class itself is rendered from, so the
 * rows carry no connection class of their own.
 *
 * <p>{@link #argumentOrders} is the {@code @orderBy} spec of each root whose row the store states,
 * keyed by coordinate: what the row's {@code Ordering.Helper} names is rendered from it rather than
 * from the walk's field, so the helper and the row come off one source.
 *
 * <p>{@link #nodeFetchers} is each node fetcher the store states, keyed by coordinate: its root
 * method is rendered from it rather than from the walk's field.
 *
 * <p>{@link #multiTables} is each root over several tables the store states, keyed by coordinate:
 * its participants and their condition calls are read from it rather than from the walk's field.
 *
 * <p><b>Method-name census.</b> Coordinate uniqueness does not imply method-name uniqueness:
 * the {@code rows} / {@code load} / {@code lookup} formulas upper-camel a field name, which is
 * not injective, and several naming schemes mint onto one fetchers class. This relation
 * therefore also passes a {@code (owner, method)} census, keyed on the minted
 * {@link UnitMethodRef} value itself, failing construction when two rows mint one emitted
 * method (the invariant the retired method-command registry's commit throw used to carry). The
 * validator's launcher-method census is the authored-schema mirror, so an authored collision
 * fails validation with a located error before production runs.
 *
 * <p>The comparison is exact, and complete for being exact: both sides of the key are names this
 * generator minted onto one owning class, so two rows that mint one method carry the identical
 * ref. A case fold belongs only where two namespaces meet, which a method name never does (it is
 * never a file name; {@link no.sikt.graphitron.model.diagnostics.Rejection.InvalidSchema.CaseFoldCollision}
 * carries the filesystem rationale for the population that does). Folding here would reject pairs
 * whose emitted methods are distinct and legal, which is by definition not a collision. The owner
 * half needs no fold of its own either: {@code GraphitronSchemaBuilder}'s type-name-stem census
 * already rejects two case-equivalent stems upstream, so two fetchers owners cannot differ only in
 * case by the time a producer runs.
 */
public record LauncherRelation(CoordinateIndex<LauncherCommand> index, CarrierDsl carrierDsl,
        no.sikt.graphitron.emit.ConnectionsQuery.FieldConnections connections,
        Map<FieldCoordinates, no.sikt.graphitron.rewrite.model.OrderBySpec.Argument> argumentOrders,
        Map<FieldCoordinates, no.sikt.graphitron.emit.RootQuery.NodeFetcher> nodeFetchers,
        Map<FieldCoordinates, no.sikt.graphitron.emit.RootQuery.MultiTable> multiTables,
        Map<FieldCoordinates, no.sikt.graphitron.emit.RootFrame> rootFrames) {

    /** The relation over {@code rows}, indexed on the coordinate key it is declared to have. */
    public LauncherRelation(List<LauncherCommand> rows, CarrierDsl carrierDsl,
            no.sikt.graphitron.emit.ConnectionsQuery.FieldConnections connections) {
        this(rows, carrierDsl, connections, Map.of());
    }

    /** The relation over {@code rows}, with the store's {@code @orderBy} spec of each root it states. */
    public LauncherRelation(List<LauncherCommand> rows, CarrierDsl carrierDsl,
            no.sikt.graphitron.emit.ConnectionsQuery.FieldConnections connections,
            Map<FieldCoordinates, no.sikt.graphitron.rewrite.model.OrderBySpec.Argument> argumentOrders) {
        this(rows, carrierDsl, connections, argumentOrders, Map.of());
    }

    /** The relation over {@code rows}, with the root order specs and the node fetchers the store states. */
    public LauncherRelation(List<LauncherCommand> rows, CarrierDsl carrierDsl,
            no.sikt.graphitron.emit.ConnectionsQuery.FieldConnections connections,
            Map<FieldCoordinates, no.sikt.graphitron.rewrite.model.OrderBySpec.Argument> argumentOrders,
            Map<FieldCoordinates, no.sikt.graphitron.emit.RootQuery.NodeFetcher> nodeFetchers) {
        this(rows, carrierDsl, connections, argumentOrders, nodeFetchers, Map.of());
    }

    /** The relation over {@code rows}, with the root order specs, node fetchers and multi-table roots the store states. */
    public LauncherRelation(List<LauncherCommand> rows, CarrierDsl carrierDsl,
            no.sikt.graphitron.emit.ConnectionsQuery.FieldConnections connections,
            Map<FieldCoordinates, no.sikt.graphitron.rewrite.model.OrderBySpec.Argument> argumentOrders,
            Map<FieldCoordinates, no.sikt.graphitron.emit.RootQuery.NodeFetcher> nodeFetchers,
            Map<FieldCoordinates, no.sikt.graphitron.emit.RootQuery.MultiTable> multiTables) {
        this(rows, carrierDsl, connections, argumentOrders, nodeFetchers, multiTables, Map.of());
    }

    /**
     * The relation over {@code rows}, with the root order specs, node fetchers and multi-table roots the
     * store states, and the root reads it routes, whose fetcher is the frame around the launcher.
     */
    public LauncherRelation(List<LauncherCommand> rows, CarrierDsl carrierDsl,
            no.sikt.graphitron.emit.ConnectionsQuery.FieldConnections connections,
            Map<FieldCoordinates, no.sikt.graphitron.rewrite.model.OrderBySpec.Argument> argumentOrders,
            Map<FieldCoordinates, no.sikt.graphitron.emit.RootQuery.NodeFetcher> nodeFetchers,
            Map<FieldCoordinates, no.sikt.graphitron.emit.RootQuery.MultiTable> multiTables,
            Map<FieldCoordinates, no.sikt.graphitron.emit.RootFrame> rootFrames) {
        this(CoordinateIndex.of(rows, LauncherCommand::coordinate, "launcher"), carrierDsl, connections,
            argumentOrders, nodeFetchers, multiTables, rootFrames);
    }

    public LauncherRelation {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(carrierDsl, "carrierDsl");
        Objects.requireNonNull(connections, "connections");
        argumentOrders = Map.copyOf(argumentOrders);
        nodeFetchers = Map.copyOf(nodeFetchers);
        multiTables = Map.copyOf(multiTables);
        rootFrames = Map.copyOf(rootFrames);
        var byMethod = new LinkedHashMap<UnitMethodRef, LauncherCommand>();
        for (var row : index.rows()) {
            var existing = byMethod.putIfAbsent(row.unit(), row);
            if (existing != null) {
                throw new IllegalArgumentException(
                    "launcher method '" + row.unit().owner().fqcn() + "#"
                    + row.unit().methodName() + "' minted twice: coordinates "
                    + existing.coordinate() + " and " + row.coordinate()
                    + "; every emitted method is exactly one row's output, and the validator's"
                    + " launcher-method census must reject this before production");
            }
        }
    }

    /** The rows in producer order. */
    public List<LauncherCommand> rows() {
        return index.rows();
    }

    /** The rows by coordinate, for per-coordinate reads. */
    public Map<FieldCoordinates, LauncherCommand> byCoordinate() {
        return index.byCoordinate();
    }

    /**
     * The row for one coordinate, empty when the coordinate has not migrated onto the seam. The
     * fetcher generator dispatches on this presence rather than restating the producer's
     * membership predicate: a present row gets the launcher emission, an absent one falls
     * through to its legacy builder, and the two ends cannot drift because only one of them
     * decides.
     */
    public Optional<LauncherCommand> rowFor(String parentTypeName, String fieldName) {
        return index.rowFor(parentTypeName, fieldName);
    }

    /** The store's {@code @orderBy} spec for the root at {@code parentTypeName.fieldName}, where it states one. */
    public Optional<no.sikt.graphitron.rewrite.model.OrderBySpec.Argument> argumentOrderFor(String parentTypeName,
            String fieldName) {
        return Optional.ofNullable(argumentOrders.get(FieldCoordinates.coordinates(parentTypeName, fieldName)));
    }

    /** The store's node fetcher at {@code parentTypeName.fieldName}, where it states one. */
    public Optional<no.sikt.graphitron.emit.RootQuery.NodeFetcher> nodeFetcherFor(String parentTypeName,
            String fieldName) {
        return Optional.ofNullable(nodeFetchers.get(FieldCoordinates.coordinates(parentTypeName, fieldName)));
    }

    /** The frame the root read at this coordinate runs in, where the store routes it. */
    public Optional<no.sikt.graphitron.emit.RootFrame> rootFrameFor(String parentTypeName, String fieldName) {
        return Optional.ofNullable(rootFrames.get(FieldCoordinates.coordinates(parentTypeName, fieldName)));
    }

    /** The root over several tables the store states at this coordinate, if it states one. */
    public Optional<no.sikt.graphitron.emit.RootQuery.MultiTable> multiTableFor(String parentTypeName,
            String fieldName) {
        return Optional.ofNullable(multiTables.get(FieldCoordinates.coordinates(parentTypeName, fieldName)));
    }
}
