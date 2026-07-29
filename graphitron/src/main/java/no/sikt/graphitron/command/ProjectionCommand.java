package no.sikt.graphitron.command;

import no.sikt.graphitron.rewrite.model.ColumnRef;
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
 * <p>Sealed on unit kind because three consumers fork on it: the naming scheme (anchor units
 * are keyed by type name, nested units by {@code (anchor, typeName)}, pivot units by
 * coordinate), the interim required-projection slot (anchor-only; nested demands hoist to the
 * anchor), and the renderer's body shape (pivot units dedupe slots by name and carry the
 * one-record sentinel).
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
     *
     * @param requiredProjection the columns the unit's SELECT must include regardless of the
     *     SDL selection, under base names: batch-key and parent-row demands of this type's
     *     children, including demands hoisted off nesting descendants (a nested requirement
     *     projects the <em>anchor</em> table's columns, because nesting shares the anchor's
     *     table context). This slot is the one deliberate exception to the gated-contribution
     *     rule, carried as a named slot precisely so no {@link Contribution} arm can be
     *     ungated; its enforcer is the parent-projection containment check (the slot's
     *     contents must contain every independently derived demand), and slot and check retire
     *     together when the correlation columns become a gated arm.
     */
    record AnchorUnit(
        UnitRef unit,
        TableRef table,
        List<Contribution> contributions,
        List<ColumnRef> requiredProjection
    ) implements ProjectionCommand {
        public AnchorUnit {
            requireCore(unit, table);
            contributions = List.copyOf(contributions);
            requiredProjection = List.copyOf(requiredProjection);
        }
    }

    /**
     * A nesting type's unit under one anchor, keyed {@code (anchor, typeName)} and named with
     * the anchor prefix. Shares the anchor's table context by definition ({@code table} is the
     * anchor's table, and the emitted parameter type is the anchor's jOOQ class); carries no
     * required-projection slot because nesting demands hoist to the anchor.
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
