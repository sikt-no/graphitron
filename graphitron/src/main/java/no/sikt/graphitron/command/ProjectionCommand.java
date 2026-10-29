package no.sikt.graphitron.command;

import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.rewrite.model.AliasScope;

import java.util.List;
import java.util.Objects;
import java.util.Set;

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
 * <em>anchor's</em> row and is reachable only by splicing into its anchor's list (settled: a
 * {@code @splitQuery} on the embedding field is a build-time deferred rejection at the
 * classifier's nesting arm, so no launcher reaches a nested unit);
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
     * The units whose emitted {@code $project} takes the executing {@code DSLContext dsl}: those
     * that call a session-reading condition glue directly, and every unit that calls one of
     * those, through to the roots. A least fixpoint over the call graph between units, computed
     * once by the producer after every unit is minted and carried on each row so renderers read
     * it and never recompute it. Empty where no condition reads the session, in which case no
     * signature changes.
     */
    Set<UnitRef> dslProjections();

    /** Whether this unit's {@code $project} takes {@code dsl}. */
    default boolean takesDsl() {
        return dslProjections().contains(unit());
    }

    /**
     * A table-backed type's unit ({@code TableType} / {@code NodeType}), keyed by type name.
     * Every entry is selection-gated, correlation keys included: a child whose fetcher reads
     * parent-row columns carries an ordinary {@link Contribution.Project} of those columns,
     * gated on the child's field, so an unselected child projects nothing.
     */
    record AnchorUnit(
        UnitRef unit,
        TableRef table,
        List<Contribution> contributions,
        Set<UnitRef> dslProjections
    ) implements ProjectionCommand {
        public AnchorUnit(UnitRef unit, TableRef table, List<Contribution> contributions) {
            this(unit, table, contributions, Set.of());
        }

        public AnchorUnit {
            requireCore(unit, table);
            contributions = List.copyOf(contributions);
            dslProjections = Set.copyOf(dslProjections);
            requireScope(contributions, AliasScope.RowOwner.class, "AnchorUnit " + unit.fqcn()
                + " writes into its own row, so none of its result-key aliases is path-qualified");
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
        List<Contribution> contributions,
        Set<UnitRef> dslProjections
    ) implements ProjectionCommand {
        public NestedUnit(UnitRef unit, TableRef table, List<Contribution> contributions) {
            this(unit, table, contributions, Set.of());
        }

        public NestedUnit {
            requireCore(unit, table);
            contributions = List.copyOf(contributions);
            dslProjections = Set.copyOf(dslProjections);
            requireScope(contributions, AliasScope.Spliced.class, "NestedUnit " + unit.fqcn()
                + " is spliced into its anchor's row, so every result-key alias it writes is"
                + " path-qualified");
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
        /** A pivot unit projects slot aggregates and calls no condition glue, so it never takes {@code dsl}. */
        @Override
        public Set<UnitRef> dslProjections() {
            return Set.of();
        }

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

    /** This row with the closure's answer stamped on it; a pivot row is returned as it is. */
    static ProjectionCommand withDslProjections(ProjectionCommand row, Set<UnitRef> dslProjections) {
        return switch (row) {
            case AnchorUnit a -> new AnchorUnit(a.unit(), a.table(),
                stamped(a.contributions(), dslProjections), dslProjections);
            case NestedUnit n -> new NestedUnit(n.unit(), n.table(),
                stamped(n.contributions(), dslProjections), dslProjections);
            case PivotUnit p -> p;
        };
    }

    private static List<Contribution> stamped(List<Contribution> contributions, Set<UnitRef> dslProjections) {
        return contributions.stream()
            .map(c -> c instanceof Contribution.Call call
                ? (Contribution) call.withCalleeTakesDsl(dslProjections.contains(call.callee())) : c)
            .toList();
    }

    /**
     * The stamp and the unit kind cannot disagree: every result-key-aliased contribution of the
     * unit carries the {@link AliasScope} its kind entails.
     */
    private static void requireScope(List<Contribution> contributions,
            Class<? extends AliasScope> expected, String why) {
        for (Contribution c : contributions) {
            if (c.writesResultKeyAlias() && !expected.isInstance(c.aliasScope())) {
                throw new IllegalArgumentException(why + "; '" + c.field() + "' is stamped "
                    + c.aliasScope().getClass().getSimpleName());
            }
        }
    }

    private static void requireCore(UnitRef unit, TableRef table) {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(table, "table");
    }
}
