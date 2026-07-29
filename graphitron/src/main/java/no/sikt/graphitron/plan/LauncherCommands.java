package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.CarrierDsl;
import no.sikt.graphitron.command.ConditionCommand;
import no.sikt.graphitron.command.FacetPlan;
import no.sikt.graphitron.command.GlueCall;
import no.sikt.graphitron.command.Invocation;
import no.sikt.graphitron.command.LaunchSource;
import no.sikt.graphitron.command.LauncherCommand;
import no.sikt.graphitron.command.Ordering;
import no.sikt.graphitron.command.ResultShape;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.TenantBinding;
import no.sikt.graphitron.rewrite.model.TenantScopes;

import java.util.ArrayList;
import java.util.List;

/**
 * Produces the launcher command relation: one {@link LauncherCommand} row per covered root
 * SELECT coordinate that has migrated onto the seam. Membership is the covered-family fact
 * minus a named, shrink-only migration dial:
 *
 * <ul>
 *   <li>{@link #coveredFamily}: the root coordinates whose fetch is a SELECT launcher against a
 *       table target, derived from the leaf's kind (table, lookup-table, routine-table and
 *       single-table-interface roots are in; polymorphic, node, service and DML roots are out by
 *       the fact, with no exemption list anywhere).</li>
 *   <li>{@link NotYetMigrated}: the covered shapes whose launcher slices have not landed, each a
 *       named dial entry a later slice deletes; the dial's true-set shrinks monotonically (the
 *       connection entry narrowed to its faceted half when the page-query slice landed). The
 *       closing slice empties the dial and lands the derived-fact-equals-key-set membership
 *       enforcer in the same commit, so the dial being empty <em>is</em> the migration being
 *       complete.</li>
 * </ul>
 *
 * <p>The generator's dispatch does not restate this membership: it routes on row presence
 * (a coordinate with a row gets the launcher emission, one without falls through to its legacy
 * builder), so the predicate has one home and the non-vacuity pins are its enforcer.
 *
 * <p>Production runs after the condition relation, because a launcher row's WHERE slot is a
 * reference into it: the producer copies the coordinate's glue ref and its env-appending answer
 * off the condition row (the cross-family handshake; the condition family owns WHERE production
 * wholesale), and a coordinate with no condition row gets an absent slot, from which the
 * renderer composes the neutral condition. Facetedness is read off the same row
 * ({@code facets()} nonempty exactly when the coordinate is a faceted {@code @asConnection}),
 * never re-derived from the schema.
 */
public final class LauncherCommands {

    /**
     * The migration dial: covered shapes whose launcher emission has not yet moved onto the
     * seam. Shrink-only on its true-set; each entry names the slice that deletes it.
     */
    enum NotYetMigrated {
        /** The single-table-interface root's discriminator reprojection (tail slice). */
        TABLE_INTERFACE,
        /** The lookup root, whose named unit already exists and folds in last (closing slice). */
        LOOKUP
    }

    private LauncherCommands() {}

    public static LauncherRelation produce(GraphitronSchema schema, ConditionRelation conditions,
            String outputPackage) {
        var units = new GeneratedUnits(outputPackage);
        var rows = new ArrayList<LauncherCommand>();
        for (var type : schema.types().values()) {
            for (var field : schema.fieldsOf(type.name())) {
                if (coveredFamily(field)
                        && dialEntryOf(schema, (QueryField) field) == null) {
                    rows.add(switch ((QueryField) field) {
                        case QueryField.QueryTableField qtf -> row(qtf, whereOf(qtf, conditions), units,
                            facetPlanOf(schema, qtf, conditions, units),
                            invocationOf(schema, qtf, units));
                        case QueryField.QueryRoutineTableField qrtf -> routineRow(qrtf, units);
                        default -> throw new IllegalStateException(
                            "unmigrated covered kind reached row production: " + field.getClass().getSimpleName());
                    });
                }
            }
        }
        var carrierDsl = schema.tenantScopes() instanceof TenantScopes.Configured
            ? CarrierDsl.ROUTED
            : CarrierDsl.ENV_ACQUIRED;
        return new LauncherRelation(rows, carrierDsl);
    }

    /**
     * Row production for a schema-free emission context (the unit-tier fetcher assemblies that
     * build model records by hand and never construct a {@link GraphitronSchema}). Mirrors what
     * {@link #produce} would mint given the fields' schema: no schema means no tenancy (the same
     * fallback the DSL-declaration emitter takes), so no fan-out exclusion arises and the
     * carrier fact is {@link CarrierDsl#ENV_ACQUIRED}; the WHERE ref derives from the field's
     * own filters through the same naming formula the condition producer mints (the schema-free
     * sibling of the relation copy; both ends read {@code GeneratedUnits}, so they cannot
     * disagree). Facetedness is a schema fact, so schema-free connection rows are facetless by
     * construction, matching what a schema-free assembly can classify.
     */
    public static LauncherRelation produceWithoutSchema(List<? extends GraphitronField> fields,
            String outputPackage) {
        var units = new GeneratedUnits(outputPackage);
        var rows = new ArrayList<LauncherCommand>();
        for (var field : fields) {
            if (field instanceof QueryField.QueryTableField qtf) {
                rows.add(row(qtf, glueFromFilters(qtf, units), units, null, new Invocation.Direct()));
            } else if (field instanceof QueryField.QueryRoutineTableField qrtf) {
                rows.add(routineRow(qrtf, units));
            }
        }
        return new LauncherRelation(rows, CarrierDsl.ENV_ACQUIRED);
    }

    private static GlueCall glueFromFilters(QueryField.QueryTableField qtf, GeneratedUnits units) {
        if (qtf.filters().isEmpty()) {
            return null;
        }
        return new GlueCall(units.conditionMethod(qtf.parentTypeName(), qtf.name()),
            no.sikt.graphitron.rewrite.model.WhereFilter.anyReadRequestContext(qtf.filters()));
    }

    /**
     * The covered-family fact, written in its final form from the first slice: a root SELECT
     * launcher against a table target. The switch is total over {@link QueryField}'s permits so
     * a new root kind is a compile-time decision here, not a silent exclusion.
     */
    static boolean coveredFamily(GraphitronField field) {
        if (!(field instanceof QueryField qf)) {
            return false;
        }
        return switch (qf) {
            case QueryField.QueryTableField ignored -> true;
            case QueryField.QueryLookupTableField ignored -> true;
            case QueryField.QueryRoutineTableField ignored -> true;
            case QueryField.QueryTableInterfaceField ignored -> true;
            // Polymorphic targets (their UNION-ALL stage is a dedicated polymorphic-emit
            // family), node dispatch, and the service-backed roots are outside by the fact.
            case QueryField.QueryInterfaceField ignored -> false;
            case QueryField.QueryUnionField ignored -> false;
            case QueryField.QueryNodeField ignored -> false;
            case QueryField.QueryNodesField ignored -> false;
            case QueryField.QueryServiceTableField ignored -> false;
            case QueryField.QueryServiceRecordField ignored -> false;
            case QueryField.QueryServicePolymorphicField ignored -> false;
            case QueryField.QueryServiceTableInterfaceField ignored -> false;
        };
    }

    /**
     * The dial entry excluding a covered coordinate from this run's relation, or {@code null}
     * when the coordinate's shape has migrated. One classification, read by the producer alone;
     * the boundary pins assert the dial-excluded shapes appear zero times while their entries
     * exist.
     */
    static NotYetMigrated dialEntryOf(GraphitronSchema schema, QueryField field) {
        // Total over the permits (a new root kind is a compile error here, matching
        // coveredFamily); the non-covered kinds are unreachable behind the coveredFamily guard.
        return switch (field) {
            case QueryField.QueryTableField ignored -> null;
            case QueryField.QueryRoutineTableField ignored -> null;
            case QueryField.QueryTableInterfaceField ignored -> NotYetMigrated.TABLE_INTERFACE;
            case QueryField.QueryLookupTableField ignored -> NotYetMigrated.LOOKUP;
            case QueryField.QueryInterfaceField ignored -> notCovered(field);
            case QueryField.QueryUnionField ignored -> notCovered(field);
            case QueryField.QueryNodeField ignored -> notCovered(field);
            case QueryField.QueryNodesField ignored -> notCovered(field);
            case QueryField.QueryServiceTableField ignored -> notCovered(field);
            case QueryField.QueryServiceRecordField ignored -> notCovered(field);
            case QueryField.QueryServicePolymorphicField ignored -> notCovered(field);
            case QueryField.QueryServiceTableInterfaceField ignored -> notCovered(field);
        };
    }

    private static NotYetMigrated notCovered(QueryField field) {
        throw new IllegalArgumentException(
            "dialEntryOf is defined over the covered family; got " + field.getClass().getSimpleName());
    }

    private static LauncherCommand row(QueryField.QueryTableField qtf, GlueCall where, GeneratedUnits units,
            FacetPlan facets, Invocation invocation) {
        return new LauncherCommand(
            units.launcherMethod(qtf.parentTypeName(), qtf.name()),
            FieldCoordinates.coordinates(qtf.parentTypeName(), qtf.name()),
            new LaunchSource.AnchorTable(qtf.returnType().table(),
                units.typeClass(qtf.returnType().returnTypeName())),
            where,
            invocation,
            resultShapeOf(qtf, units, facets));
    }

    /**
     * A {@code @routine} chain row: the source arm carries the borrowed start expression and the
     * narrowed hop list (the chain constructor's own guarantee), the projection targets the
     * terminus type. No WHERE slot (the leaf carries no filter surface, so no condition row
     * exists) and no ordering (root routine lists are unordered by classification; the
     * {@code @orderBy} surface is deferred on the chain).
     */
    private static LauncherCommand routineRow(QueryField.QueryRoutineTableField qrtf, GeneratedUnits units) {
        var hops = qrtf.chain().hops().stream()
            .map(step -> (no.sikt.graphitron.rewrite.model.JoinStep.Hop) step)
            .toList();
        return new LauncherCommand(
            units.launcherMethod(qrtf.parentTypeName(), qrtf.name()),
            FieldCoordinates.coordinates(qrtf.parentTypeName(), qrtf.name()),
            new LaunchSource.RoutineChain(qrtf.chain().start(), hops,
                units.typeClass(qrtf.returnType().returnTypeName())),
            null,
            new Invocation.Direct(),
            qrtf.returnType().wrapper().isList()
                ? new ResultShape.RecordList(null)
                : new ResultShape.SingleRecord());
    }

    /**
     * The invocation strategy, from the coordinate's tenancy binding: a fan-out coordinate runs
     * the composition once per domain tenant through the scatter carrier (whose ref rides the
     * arm), everything else is one direct call. This is the one home of the fan-out fact for the
     * root family; the generator's dispatch and entry point read the arm, never the binding.
     */
    private static Invocation invocationOf(GraphitronSchema schema, QueryField.QueryTableField qtf,
            GeneratedUnits units) {
        return schema.tenantBindingOf(qtf.parentTypeName(), qtf.name()) instanceof TenantBinding.FanOut
            ? new Invocation.FannedOverTenants(units.tenantConnections())
            : new Invocation.Direct();
    }

    /**
     * The payload shape, derived from the coordinate's wrapper: the connection arm carries the
     * ordering (total there: pagination requires ordering, validator-enforced, and production
     * backstops it), the default page size, the connection runtime's unit refs copied off the
     * naming vocabulary so the launcher's edges to them are data, and the facet plan when the
     * coordinate carries facets.
     */
    private static ResultShape resultShapeOf(QueryField.QueryTableField qtf, GeneratedUnits units,
            FacetPlan facets) {
        if (qtf.returnType().wrapper() instanceof FieldWrapper.Connection conn) {
            var ordering = orderingOf(qtf, units);
            if (ordering == null) {
                throw new IllegalStateException(
                    "connection coordinate '" + qtf.qualifiedName() + "' has no resolvable ordering;"
                    + " the validator rejects pagination without ordering before production");
            }
            return new ResultShape.Connection(ordering, conn.defaultPageSize(),
                units.connectionHelper(), units.connectionResult(), facets);
        }
        if (facets != null) {
            throw new IllegalStateException(
                "coordinate '" + qtf.qualifiedName() + "' carries facets but is not a connection;"
                + " the classifier synthesises facet carriers only for @asConnection coordinates");
        }
        return qtf.returnType().wrapper().isList()
            ? new ResultShape.RecordList(orderingOf(qtf, units))
            : new ResultShape.SingleRecord();
    }

    /**
     * The faceted carrier's plan, or {@code null} for the non-faceted (or facet-free) carrier:
     * the decode specs come from the coordinate's synthesised connection carrier (the same
     * derivation the condition producer's fragment builder reads), the fragment refs are minted
     * through the naming vocabulary and cross-checked against the condition row's own fragment
     * set, so the two families cannot drift on which methods exist, and the env-appending fork
     * is the row-grained fact copied off the same row.
     */
    private static FacetPlan facetPlanOf(GraphitronSchema schema, QueryField.QueryTableField qtf,
            ConditionRelation conditions, GeneratedUnits units) {
        var specs = ConditionCommands.facetsFor(schema, qtf.parentTypeName(), qtf.name());
        if (specs.isEmpty()) {
            return null;
        }
        var row = conditionRowOf(qtf, conditions).orElseThrow(() -> new IllegalStateException(
            "faceted coordinate '" + qtf.qualifiedName() + "' has no condition row; facet inputs"
            + " are filters, so a faceted coordinate always has one"));
        var fragmentRefs = row.facets().stream().map(f -> f.method()).toList();
        boolean takesEnv = row.readsRequestContext();
        var base = units.facetBaseConditionMethod(qtf.parentTypeName(), qtf.name());
        requireFragment(fragmentRefs, base, qtf);
        var entries = new ArrayList<FacetPlan.Entry>(specs.size());
        for (var spec : specs) {
            var fragment = units.facetConditionMethod(qtf.parentTypeName(), qtf.name(), spec.inputFieldName());
            requireFragment(fragmentRefs, fragment, qtf);
            entries.add(new FacetPlan.Entry(spec, new GlueCall(fragment, takesEnv)));
        }
        return new FacetPlan(new GlueCall(base, takesEnv), entries);
    }

    private static void requireFragment(List<no.sikt.graphitron.command.UnitMethodRef> fragmentRefs,
            no.sikt.graphitron.command.UnitMethodRef ref, QueryField.QueryTableField qtf) {
        if (!fragmentRefs.contains(ref)) {
            throw new IllegalStateException(
                "faceted coordinate '" + qtf.qualifiedName() + "': the launcher's facet plan names"
                + " fragment '" + ref.methodName() + "' but the condition row's fragment set does"
                + " not carry it; the two producers read one naming formula and have drifted");
        }
    }

    /**
     * The coordinate's condition glue reference, copied off the condition relation's row
     * (single-sourcing the env-appending fact on that relation; the launcher never recomputes it
     * from filters, and absence in the relation <em>is</em> the absence), or {@code null} when
     * the coordinate has no row.
     */
    private static GlueCall whereOf(QueryField.QueryTableField qtf, ConditionRelation conditions) {
        return conditionRowOf(qtf, conditions)
            .map(r -> new GlueCall(r.glue(), r.readsRequestContext()))
            .orElse(null);
    }

    private static java.util.Optional<ConditionCommand> conditionRowOf(
            QueryField.QueryTableField qtf, ConditionRelation conditions) {
        var coordinate = FieldCoordinates.coordinates(qtf.parentTypeName(), qtf.name());
        return conditions.rows().stream()
            .filter(r -> r.coordinate().equals(coordinate))
            .findFirst();
    }

    /**
     * The ordering, from the model's resolved spec: a nonempty fixed order renders inline
     * ({@link Ordering.Columns}), an argument-driven order dispatches through the emitted helper
     * whose refs are minted here ({@link Ordering.Helper}), and everything else (no spec, or a
     * fixed spec with no columns) is unordered, an absent slot on the {@code RecordList} arm.
     */
    private static Ordering orderingOf(QueryField.QueryTableField qtf, GeneratedUnits units) {
        return switch (qtf.orderBy()) {
            case OrderBySpec.Fixed fixed when !fixed.columns().isEmpty() -> new Ordering.Columns(fixed);
            case OrderBySpec.Argument ignored -> new Ordering.Helper(
                units.orderByHelperMethod(qtf.parentTypeName(), qtf.name()), units.orderByResult());
            default -> null;
        };
    }
}
