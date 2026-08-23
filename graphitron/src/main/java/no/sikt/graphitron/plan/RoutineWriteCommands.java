package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.Arity;
import no.sikt.graphitron.command.ErrorDispatch;
import no.sikt.graphitron.command.RoutineWriteCommand;
import no.sikt.graphitron.command.TenantAcquisition;
import no.sikt.graphitron.command.TenantRouting;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.TableExpr;
import no.sikt.graphitron.rewrite.model.TenantBinding;
import no.sikt.graphitron.rewrite.model.TenantScopes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Produces the routine-write command relation ({@link RoutineWriteRelation}): one row per
 * {@code @routine}-writing mutation coordinate, carrying what the coordinate's fetcher entry
 * point emits. Membership has one home, the total switch below: the two routine-write leaves mint
 * a row and every other mutation shape is outside the relation by the fact, with no default arm,
 * so a new mutation leaf is a compile-time decision here rather than a silent non-member.
 *
 * <p>The two arms map one-to-one onto the two leaves, so this producer decides nothing the
 * classifier has not already decided; what it adds is the naming vocabulary (the entry point's
 * own address, the terminus projection unit, the units the {@code catch} arm calls) and the
 * restatement of the error channel as the arm the catch emits. That restatement is the only
 * translation step: a channel is a classification fact carrying the resolved {@code @error} types
 * it was built from, and all a catch arm emits from it is the mappings constant's name.
 */
public final class RoutineWriteCommands {

    private RoutineWriteCommands() {}

    public static RoutineWriteRelation produce(GraphitronSchema schema, String outputPackage) {
        var units = new GeneratedUnits(outputPackage);
        var rows = new ArrayList<RoutineWriteCommand>();
        for (var type : schema.types().values()) {
            for (var field : schema.fieldsOf(type.name())) {
                if (field instanceof MutationField mf) {
                    var row = rowOf(mf, units);
                    if (row != null) {
                        rows.add(row);
                    }
                }
            }
        }
        return new RoutineWriteRelation(rows, tenancyOf(schema, rows, units));
    }

    /**
     * The run's acquisition axis over these rows. Single-tenant runs state the absence once; a
     * multi-tenant run names the generated carrier and folds every covered coordinate's classified
     * binding into the arm its entry point emits.
     *
     * <p>This fold is the axis's one home for the family, copying the rule
     * {@link LauncherCommands} states for the fan-out axis beside it: a binding is a
     * classification, an acquisition is what an entry point emits, and the translation happens
     * where the row is minted rather than at every emission site.
     */
    private static TenantRouting tenancyOf(GraphitronSchema schema, List<RoutineWriteCommand> rows,
            GeneratedUnits units) {
        if (!(schema.tenantScopes() instanceof TenantScopes.Configured)) {
            return new TenantRouting.Unrouted();
        }
        Map<FieldCoordinates, TenantAcquisition> byCoordinate = new LinkedHashMap<>();
        for (var row : rows) {
            byCoordinate.put(row.coordinate(), acquisitionOf(schema, row.coordinate()));
        }
        return new TenantRouting.Routed(units.tenantConnections(), byCoordinate);
    }

    /**
     * One coordinate's classified binding as the acquisition its entry point emits. The per-row
     * family (a node id's or a federation representation's decoded tenant slot) folds onto the
     * inherited read: a mutation root is not a per-row dispatch surface, so what reaches it is the
     * value an ancestor divined.
     *
     * <p>Two arms are refused rather than translated. A missing binding is the classifier's typed
     * {@code noTenantBinding} finding, which the validator turns into a located build error before
     * the plan runs, so reaching it here means production ran on a schema validation would have
     * rejected; the refusal restates that rather than acquiring the default source, which is the
     * cross-tenant read the axis exists to prevent. A fanned binding is refused because the fanned
     * emission owns its coordinate's acquisition; a routine write is not a fannable shape, so
     * reaching it here is drift rather than a deferred feature.
     */
    private static TenantAcquisition acquisitionOf(GraphitronSchema schema, FieldCoordinates coordinate) {
        var binding = schema.tenantBindingOf(coordinate);
        if (binding == null) {
            throw new IllegalStateException(
                "the routine-write coordinate " + coordinate + " reaches a tenant-scoped table with"
                + " no tenant binding in scope; that is the classifier's noTenantBinding finding and"
                + " the validator rejects it with a located error, so a plan produced for it ran"
                + " past validation, and acquiring the default source here would read another"
                + " tenant's rows");
        }
        return switch (binding) {
            case TenantBinding.Untenanted ignored -> new TenantAcquisition.Untenanted();
            case TenantBinding.Inherited ignored -> new TenantAcquisition.Inherited();
            case TenantBinding.NodeIdBound ignored -> new TenantAcquisition.Inherited();
            case TenantBinding.EntityRepBound ignored -> new TenantAcquisition.Inherited();
            case TenantBinding.ArgumentBound bound -> new TenantAcquisition.ArgumentBound(
                bound.bindings().stream().map(RoutineWriteCommands::slotReadOf).toList(),
                bound.bindings().getFirst().column());
            case TenantBinding.FanOut ignored -> throw new IllegalStateException(
                "the routine-write coordinate " + coordinate + " is classified as tenant fan-out;"
                + " the fanned emission acquires per tenant through scatter and owns that"
                + " coordinate itself, so no entry point of this family declares its connection");
        };
    }

    /** One bound slot's runtime read, restated in the command vocabulary the renderer reads. */
    private static TenantAcquisition.SlotRead slotReadOf(TenantBinding.BoundSlot slot) {
        return switch (slot.read()) {
            case TenantBinding.SlotRead.TopLevelArg ignored ->
                new TenantAcquisition.SlotRead.TopLevelArg(slot.slotName());
            case TenantBinding.SlotRead.NestedInput nested ->
                new TenantAcquisition.SlotRead.NestedInput(nested.outerArgName(), nested.path());
            case TenantBinding.SlotRead.ContextArg ignored ->
                new TenantAcquisition.SlotRead.ContextArg(slot.slotName());
        };
    }

    /**
     * The mutation family's membership-and-production switch, total with no default. Only the two
     * routine-write leaves are members: every other mutation shape writes through DML or delegates
     * to a developer service, and neither emits a routine call.
     */
    private static RoutineWriteCommand rowOf(MutationField field, GeneratedUnits units) {
        return switch (field) {
            case MutationField.MutationRoutineWriteField f -> new RoutineWriteCommand.ChainReread(
                units.fetcherEntryMethod(f.parentTypeName(), f.name()),
                FieldCoordinates.coordinates(f.parentTypeName(), f.name()),
                f.chain(),
                units.typeClass(f.returnType().returnTypeName()),
                f.returnType().wrapper().isList() ? Arity.LIST : Arity.SINGLE,
                // The leaf's channel is structurally absent (a chain-re-reading routine write
                // carries no payload carrier to route into), so the disposition is the router's
                // privacy arm. Derived from the leaf's own slot rather than hard-coded, so a
                // channel appearing there surfaces as a rejected translation, not a dropped fact.
                dispatchFor(f.errorChannel(), units));
            case MutationField.MutationRoutineWriteRecordField f -> new RoutineWriteCommand.CarrierKeys(
                units.fetcherEntryMethod(f.parentTypeName(), f.name()),
                FieldCoordinates.coordinates(f.parentTypeName(), f.name()),
                new TableExpr.RoutineCall(f.routine(), f.routineResultTable()),
                f.capturedPairs(),
                f.targetTable(),
                // The payload data field's SDL wrapper, the only cardinality claim for this shape:
                // jOOQ types every table-valued function as a Table<R>, so the catalog carries no
                // per-call cardinality fact.
                f.dataFieldArrival() == no.sikt.graphitron.rewrite.model.Arity.MANY
                    ? Arity.LIST : Arity.SINGLE,
                dispatchFor(f.errorChannel(), units));
            case MutationField.DmlTableField ignored -> null;
            case MutationField.MutationServiceTableField ignored -> null;
            case MutationField.MutationServiceRecordField ignored -> null;
            case MutationField.MutationServicePolymorphicField ignored -> null;
            case MutationField.MutationServiceTableInterfaceField ignored -> null;
            case MutationField.MutationDmlRecordField ignored -> null;
            case MutationField.MutationBulkDmlRecordField ignored -> null;
        };
    }

    /**
     * The channel restated as what the {@code catch} arm emits. An absent channel is the router's
     * privacy disposition; a {@link ErrorChannel.LocalContext} channel hands the matched throwable
     * back as graphql-java {@code localContext}, and its mappings constant is all the arm needs.
     * The remaining channel arm is unreachable on these two leaves by classification: a routine
     * write's carrier is a directiveless structural payload, which has no developer payload class
     * for the mapped arm to instantiate.
     */
    private static ErrorDispatch dispatchFor(Optional<? extends ErrorChannel> channel, GeneratedUnits units) {
        if (channel.isEmpty()) {
            return new ErrorDispatch.Redacting(units.errorRouter());
        }
        if (channel.get() instanceof ErrorChannel.LocalContext lc) {
            return new ErrorDispatch.LocalContextRouted(units.errorRouter(), units.errorMappings(),
                lc.mappingsConstantName());
        }
        throw new IllegalStateException(
            "a routine-write coordinate carries an error channel this relation cannot state: "
            + channel.get().getClass().getSimpleName() + "; the routine-write carrier is a"
            + " directiveless structural payload, so the classifier produces only the"
            + " localContext-routed channel here");
    }

    /**
     * The relation over a bare field set, for the fetcher generator's nesting-reached fallback,
     * which holds fields but no schema. Mirrors the launcher producer's overload at the same call
     * site: membership stays here rather than the generator asserting that a nesting-reached
     * type's children hold no mutation root field.
     *
     * <p>Unrouted by construction, and correct rather than approximate: a nesting-reached type's
     * children are never mutation roots, so this relation holds no rows for an acquisition to
     * cover.
     */
    public static RoutineWriteRelation produceWithoutSchema(List<? extends GraphitronField> fields,
            String outputPackage) {
        var units = new GeneratedUnits(outputPackage);
        var rows = new ArrayList<RoutineWriteCommand>();
        for (var field : fields) {
            if (field instanceof MutationField mf) {
                var row = rowOf(mf, units);
                if (row != null) {
                    rows.add(row);
                }
            }
        }
        return RoutineWriteRelation.unrouted(rows);
    }
}
