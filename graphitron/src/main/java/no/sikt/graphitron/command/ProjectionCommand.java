package no.sikt.graphitron.command;

import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.List;
import java.util.Objects;

/**
 * One projection unit: an emitted {@code $project} method that takes the result-key-grouped
 * selection, the caller-supplied table instance, and the request environment, and returns the
 * SELECT-list fields the selection gates. Every projection unit is caller-parameterised: the
 * table instance always arrives from the caller, so there is no owns-its-alias case and no
 * alias axis on the command.
 *
 * <p>Sealed on unit kind because the kinds differ structurally, not by provenance: an anchor
 * unit projects a row of its own table and is the unit other commands may name as a callee (a
 * root launcher's select list, a multiset subselect's inner list); a nested unit projects the
 * <em>anchor's</em> row and is reachable only by splicing into its anchor's list (whether a
 * {@code @splitQuery} nesting field additionally launches one is that feature's open question);
 * a pivot unit projects slot aggregates over an attribute table and renders through its own
 * body shape (slot dedupe by name, the one-record sentinel). The keying follows the structure:
 * type name, {@code (anchor, typeName)}, coordinate.
 */
public sealed interface ProjectionCommand {

    /** The unit's committed address. */
    UnitRef unit();

    /** The table whose columns the contributions name (the {@code table} parameter's type). */
    TableRef table();

    /** Every selection-gated entry, in SDL declaration order. */
    List<Contribution> contributions();

    /**
     * A table-backed type's unit ({@code TableType} / {@code NodeType}), keyed by type name.
     * Every entry is selection-gated, correlation keys included: a child whose fetcher reads
     * parent-row columns carries an ordinary {@link Contribution.Project} of those columns,
     * gated on the child's field, so an unselected child projects nothing.
     */
    record AnchorUnit(
        UnitRef unit,
        TableRef table,
        List<Contribution> contributions
    ) implements ProjectionCommand {
        public AnchorUnit {
            requireCore(unit, table);
            contributions = List.copyOf(contributions);
        }
    }

    /**
     * A nesting type's unit under one anchor, keyed {@code (anchor, typeName)} and named with
     * the anchor prefix. Shares the anchor's table context by definition ({@code table} is the
     * anchor's table, and the emitted parameter type is the anchor's jOOQ class), so a nested
     * child's correlation-key arm projects <em>anchor</em>-table columns and lands in the
     * anchor's list through the splice.
     */
    record NestedUnit(
        UnitRef unit,
        TableRef table,
        List<Contribution> contributions
    ) implements ProjectionCommand {
        public NestedUnit {
            requireCore(unit, table);
            contributions = List.copyOf(contributions);
        }
    }

    /**
     * A {@code @pivot} projection's unit, keyed by the pivot-bearing coordinate (the
     * {@link no.sikt.graphitron.rewrite.model.PivotSpec} is coordinate-grain: two coordinates
     * can reach the same projection type over different attribute tables). {@code table} is the
     * attribute table; contributions are the slots, each landing one filtered aggregate. The
     * renderer's body dedupes selected slots by name (one projected column serves every alias
     * of a slot, since the read is by name) and appends the one-record sentinel when the
     * selection carries no slot.
     */
    record PivotUnit(
        UnitRef unit,
        TableRef table,
        List<Contribution> contributions
    ) implements ProjectionCommand {
        public PivotUnit {
            requireCore(unit, table);
            contributions = List.copyOf(contributions);
            for (Contribution c : contributions) {
                if (!(c instanceof Contribution.Project p)
                        || p.terms().stream().anyMatch(t -> !(t instanceof SelectTerm.Aggregate))) {
                    throw new IllegalArgumentException(
                        "PivotUnit contributions are slot aggregates (Project with Aggregate "
                        + "terms only); got " + c);
                }
            }
        }
    }

    private static void requireCore(UnitRef unit, TableRef table) {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(table, "table");
    }
}
