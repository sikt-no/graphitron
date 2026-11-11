package no.sikt.graphitron.command;

import java.util.List;
import java.util.Objects;

/**
 * One row of the global command relation: emit the units of one global family. The row names
 * every unit it commits, and the write step lands each rendered unit at its committed
 * {@link UnitRef}, so a renderer emitting an undeclared unit, or dropping a declared one, is a
 * pipeline failure rather than silent drift.
 *
 * <p>Sealed by edge character, not by kind. The fixed substrate ({@link Fixed}) commits units
 * whose outbound references are entailed by the {@link GlobalUnitKind} alone: which generated
 * units the facade, the schema class or the connection runtime reference is a property of the
 * family, the same for every schema, so the recompile-graph projection derives those edges from
 * the arm and the rows carry only the committed refs. The one family whose outbound references
 * depend on the classified schema's population carries them as data
 * ({@link EntityDispatch#dispatchTargets()}), mandatory and non-empty, because no arm-entailed
 * rule can name which per-type projection classes the emitted dispatch will reference.
 */
public sealed interface GlobalCommand {

    /** The relation's key: which global family this row is. Each kind appears at most once per plan. */
    GlobalUnitKind kind();

    /** Every unit this row commits; the write step addresses the rendered specs by these refs. */
    List<UnitRef> units();

    /**
     * A fixed-substrate family: the committed unit set is decided per run (some kinds are gated,
     * {@code CONNECTION_RUNTIME}'s set grows a configured hook implementation), but the emitted
     * units' outbound references are the same for every schema, so the edge view entails them
     * from the kind and this row carries nothing beyond the refs.
     */
    record Fixed(GlobalUnitKind kind, List<UnitRef> units) implements GlobalCommand {

        public Fixed {
            if (kind == null) {
                throw new IllegalArgumentException("a global command requires a unit kind");
            }
            if (kind == GlobalUnitKind.FACETS) {
                throw new IllegalArgumentException(
                    "the facets family's records are schema-dependent; produce a Facets row, never a fixed one");
            }
            if (kind == GlobalUnitKind.ENTITY_FETCHER_DISPATCH) {
                throw new IllegalArgumentException(
                    "the entity dispatch family's outbound references are schema-dependent;"
                        + " produce an EntityDispatch row, never a fixed one");
            }
            if (units == null || units.isEmpty()) {
                throw new IllegalArgumentException(
                    "a global command commits at least one unit; a family that emits nothing has no row");
            }
            units = List.copyOf(units);
            long distinctNames = units.stream().map(UnitRef::simpleName).distinct().count();
            if (distinctNames != units.size()) {
                throw new IllegalArgumentException(
                    "a global command's units carry distinct simple names; the write step addresses them by name");
            }
        }
    }

    /**
     * The entity/node dispatch family ({@code EntityFetcherDispatch}): present exactly when the
     * schema declares resolvable entities, and the emitted class's per-type handle/select
     * methods reference one per-type projection class per resolvable entity. Node types join
     * through the {@code @node}-to-{@code @key} synthesis, so the target set covers both the
     * federation {@code _entities} population and the node-lookup population. Those references
     * are the one global edge population no arm can entail, so the row carries them:
     * {@code dispatchTargets} is mandatory and non-empty (the row's own membership gate
     * guarantees at least one resolvable entity exists).
     */
    record EntityDispatch(UnitRef unit, List<UnitRef> dispatchTargets) implements GlobalCommand {

        public EntityDispatch {
            Objects.requireNonNull(unit, "unit");
            if (dispatchTargets == null || dispatchTargets.isEmpty()) {
                throw new IllegalArgumentException(
                    "the dispatch row exists exactly when resolvable entities exist, so its"
                        + " target set is never empty; an empty set is a producer bug");
            }
            dispatchTargets = List.copyOf(dispatchTargets);
        }

        @Override
        public GlobalUnitKind kind() {
            return GlobalUnitKind.ENTITY_FETCHER_DISPATCH;
        }

        @Override
        public List<UnitRef> units() {
            return List.of(unit);
        }
    }

    /**
     * The facets family's row: one record per facet value type (its {@code value} and
     * {@code count}) and one per facets type, a component per facet naming the facet value type
     * its buckets are. Present exactly when the schema has a faceted connection.
     */
    record Facets(UnitRef unit, List<String> facetValueTypes, List<FacetsRecord> facetsTypes)
            implements GlobalCommand {

        public Facets {
            Objects.requireNonNull(unit, "unit");
            if (facetsTypes == null || facetsTypes.isEmpty()) {
                throw new IllegalArgumentException(
                    "the facets row exists exactly when a faceted connection exists; an empty set is a producer bug");
            }
            facetValueTypes = List.copyOf(facetValueTypes);
            facetsTypes = List.copyOf(facetsTypes);
        }

        @Override
        public GlobalUnitKind kind() {
            return GlobalUnitKind.FACETS;
        }

        @Override
        public List<UnitRef> units() {
            return List.of(unit);
        }
    }

    /** One facets type: its name and, in field order, each facet's field and facet value type. */
    record FacetsRecord(String name, List<FacetComponent> facets) {

        public FacetsRecord {
            facets = List.copyOf(facets);
        }
    }

    /** One facet of a facets type: the field's name and the facet value type its buckets are. */
    record FacetComponent(String fieldName, String facetValueType) {}
}
