package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.model.ConnectionSynthesis;
import no.sikt.graphitron.rewrite.model.ConnectionSynthesis.MintedName;
import no.sikt.graphitron.rewrite.model.FacetSpec;
import no.sikt.graphitron.rewrite.model.GraphitronType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The connection-synthesis relation: one {@link ConnectionSynthesis} row per Relay connection
 * carrier the classify walk visited, keyed by carrier coordinate, plus the schema-grain minted
 * names every carrier shares (the one {@code PageInfo} slot and the reusable
 * per-(scalar, nullability) {@code *FacetValue} pool, which are not replicated per row).
 * Accumulated by {@link ConnectionPromoter#synthesiseForField} through {@link Builder}, homed on
 * {@link GraphitronSchema#connectionSynthesis()} beside the other post-walk sidecars.
 *
 * <p>Rows never restate type-grain facts; {@link #connectionTypeOf} is the typed accessor that
 * resolves a row's registry entry (the reconciled {@link GraphitronType.ConnectionType} arm), so
 * consumers such as the plan's facet producers read the one reconciled value.
 */
public final class ConnectionSynthesisRelation {

    /** The empty relation, the default for schemas built without the classify walk. */
    public static final ConnectionSynthesisRelation EMPTY =
        new ConnectionSynthesisRelation(Map.of(), List.of(), Map.of());

    private final Map<FieldCoordinates, ConnectionSynthesis> rows;
    private final List<MintedName> sharedMinted;
    private final Map<String, GraphitronType> types;

    private ConnectionSynthesisRelation(Map<FieldCoordinates, ConnectionSynthesis> rows,
            List<MintedName> sharedMinted, Map<String, GraphitronType> types) {
        this.rows = Collections.unmodifiableMap(rows);
        this.sharedMinted = List.copyOf(sharedMinted);
        this.types = types;
    }

    /** All rows, keyed by carrier coordinate, in walk-visit order. */
    public Map<FieldCoordinates, ConnectionSynthesis> rows() {
        return rows;
    }

    /** The row at the carrier coordinate, or {@code null} when the coordinate is not a carrier. */
    public ConnectionSynthesis row(String parentTypeName, String fieldName) {
        return rows.get(FieldCoordinates.coordinates(parentTypeName, fieldName));
    }

    /**
     * The schema-grain minted names shared across every carrier: the one {@code PageInfo} slot
     * and the reusable {@code *FacetValue} pool, in first-registration order.
     */
    public List<MintedName> sharedMinted() {
        return sharedMinted;
    }

    /**
     * The typed registry accessor for a row: the reconciled
     * {@link GraphitronType.ConnectionType} arm registered under the row's connection name, or
     * {@code null} when the entry demoted (a minted name colliding with an SDL declaration
     * carries its own diagnostic on the demoted entry).
     */
    public GraphitronType.ConnectionType connectionTypeOf(ConnectionSynthesis row) {
        return types.get(row.connectionName()) instanceof GraphitronType.ConnectionType ct ? ct : null;
    }

    /** {@link #connectionTypeOf} keyed by coordinate; {@code null} when no row exists there. */
    public GraphitronType.ConnectionType connectionTypeAt(String parentTypeName, String fieldName) {
        var row = row(parentTypeName, fieldName);
        return row == null ? null : connectionTypeOf(row);
    }

    /**
     * The facet specs of the carrier at the coordinate: the registry-reconciled
     * {@link GraphitronType.ConnectionType#facets()} list, empty when the coordinate is not a
     * carrier, its entry demoted, or the connection carries no facets (every structural row).
     */
    public List<FacetSpec> facetsAt(String parentTypeName, String fieldName) {
        var ct = connectionTypeAt(parentTypeName, fieldName);
        return ct == null ? List.of() : ct.facets();
    }

    /**
     * Every name the carrier at the coordinate causes to exist, the author-visible mint set: the
     * row's own minted names plus the shared names the row implies (the {@code PageInfo} slot,
     * and one {@code *FacetValue} pool entry per facet on the row's reconciled connection entry).
     * Empty when the coordinate is not a carrier. Read by the corpus's carrier-side synthesis
     * declaration and the population pin.
     */
    public List<MintedName> mintedAt(String parentTypeName, String fieldName) {
        var row = row(parentTypeName, fieldName);
        if (row == null) {
            return List.of();
        }
        var result = new ArrayList<>(row.mintedNames());
        var byName = new HashMap<String, MintedName>();
        for (var shared : sharedMinted) {
            byName.putIfAbsent(shared.name(), shared);
        }
        var ct = connectionTypeOf(row);
        if (ct != null) {
            for (var spec : ct.facets()) {
                var pooled = byName.get(spec.facetValueTypeName());
                if (pooled != null) {
                    result.add(pooled);
                }
            }
        }
        var pageInfo = byName.get("PageInfo");
        if (pageInfo != null) {
            result.add(pageInfo);
        }
        return List.copyOf(result);
    }

    /**
     * The minted names absent from the assembled schema, deduplicated by name in row order then
     * shared order: exactly the set the post-walk rebuild registers via {@code additionalType}.
     * Read off the rows' stored discriminators, never re-probed against the schema.
     */
    public List<MintedName> absentMinted() {
        var seen = new HashSet<String>();
        var result = new ArrayList<MintedName>();
        for (var row : rows.values()) {
            for (var minted : row.mintedNames()) {
                if (minted.absentFromAssembled() && seen.add(minted.name())) {
                    result.add(minted);
                }
            }
        }
        for (var minted : sharedMinted) {
            if (minted.absentFromAssembled() && seen.add(minted.name())) {
                result.add(minted);
            }
        }
        return List.copyOf(result);
    }

    /** True when no carrier was visited (no rows and no shared names). */
    public boolean isEmpty() {
        return rows.isEmpty() && sharedMinted.isEmpty();
    }

    /**
     * The shape one carrier mints under its connection name, compared across carriers by
     * {@link Builder#add}: two rows minting the same connection name must project the same
     * element type, item nullability, edge type and facets, because the registry keeps only one
     * reconciled entry per name. Construction-internal; never stored on a row (the reconciled
     * facts live on the registry entry).
     */
    record MintedShape(String elementTypeName, boolean itemNullable, String edgeName,
                       List<FacetSpec> facets) {}

    /** A minted-name-axis disagreement: the already-registered row the new row disagrees with. */
    record NameAxisConflict(ConnectionSynthesis existingRow) {}

    /**
     * The walk-side accumulator. One instance per build, filled by
     * {@link ConnectionPromoter#synthesiseForField} in walk-visit order.
     *
     * <p>There is deliberately no duplicate-coordinate guard: the classify walk visits each type
     * once and iterates each type's fields once, so a duplicate coordinate is structurally
     * unreachable in production and a guard would prove nothing. The genuinely N:1 axis is
     * coordinate to minted connection name (an explicit {@code connectionName:} lets two carriers
     * mint one name), which {@link #add} enforces.
     */
    public static final class Builder {

        private final Map<FieldCoordinates, ConnectionSynthesis> rows = new LinkedHashMap<>();
        private final Map<String, MintedName> shared = new LinkedHashMap<>();
        private final Map<String, ConnectionSynthesis> firstRowByConnectionName = new HashMap<>();
        private final Map<String, MintedShape> shapeByConnectionName = new HashMap<>();

        /**
         * Adds one row, enforcing the minted-name axis: when another row already minted the same
         * connection name with a different {@link MintedShape}, the conflict is returned (the
         * caller owns the typed rejection; the row is still stored so the relation stays total
         * over the visited carriers). Returns {@code null} on agreement.
         */
        NameAxisConflict add(ConnectionSynthesis row, MintedShape shape) {
            rows.put(FieldCoordinates.coordinates(row.parentTypeName(), row.fieldName()), row);
            var existingShape = shapeByConnectionName.putIfAbsent(row.connectionName(), shape);
            var existingRow = firstRowByConnectionName.putIfAbsent(row.connectionName(), row);
            if (existingShape != null && !existingShape.equals(shape)) {
                return new NameAxisConflict(existingRow);
            }
            return null;
        }

        /** Records a schema-grain minted name (PageInfo, a {@code *FacetValue}); first wins. */
        void addShared(MintedName minted) {
            shared.putIfAbsent(minted.name(), minted);
        }

        /**
         * Seals the accumulation against the finished type registry view, wiring the typed
         * registry accessors.
         */
        public ConnectionSynthesisRelation build(Map<String, GraphitronType> types) {
            return new ConnectionSynthesisRelation(
                new LinkedHashMap<>(rows), List.copyOf(shared.values()), types);
        }
    }
}
