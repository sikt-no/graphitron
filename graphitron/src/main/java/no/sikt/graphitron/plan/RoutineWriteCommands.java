package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.Arity;
import no.sikt.graphitron.command.ErrorDispatch;
import no.sikt.graphitron.command.JoinBasis;
import no.sikt.graphitron.command.RoutineWriteCommand;
import no.sikt.graphitron.command.TenantAcquisition;
import no.sikt.graphitron.command.TenantRouting;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.TenantBinding;
import no.sikt.graphitron.rewrite.model.TenantScopes;
import no.sikt.graphitron.rewrite.model.WithErrorChannel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Produces the routine-write command relation ({@link RoutineWriteRelation}): one row per
 * {@code @routine}-writing mutation coordinate, carrying what the coordinate's fetcher entry
 * point emits.
 *
 * <p>Membership is read, not decided. {@link RoutineWriteFacts} states which coordinates the store
 * admits and every catalog fact their emission reads, so this producer walks no schema: it names
 * things. What it adds over the facts is the naming vocabulary (the entry point's own address, the
 * terminus projection unit, the units the {@code catch} arm calls) and two folds the store does not
 * state per coordinate, each of which retires with the family that owns it:
 *
 * <ul>
 *   <li>the error channel's minted constant, whose name comes from a whole-schema dedup over every
 *       channel-carrying field ({@link no.sikt.graphitron.rewrite.MappingsConstantNameDedup}) and so
 *       cannot be minted from one coordinate's facts;</li>
 *   <li>the run's tenant binding, which the tenancy family converts.</li>
 * </ul>
 *
 * <p>Both read the classified schema through interfaces the whole model shares rather than through
 * a mutation leaf, which is what keeps this producer's remaining schema use off the leaf taxonomy.
 */
public final class RoutineWriteCommands {

    private RoutineWriteCommands() {}

    /**
     * The relation for one run. A null store is the no-store arm's, and yields no rows: a plan
     * produced without a store emits no routine write rather than falling back to a walk, the walk
     * having been the thing this read replaced.
     */
    public static RoutineWriteRelation produce(StoreHandle store, GraphitronSchema schema,
            String outputPackage) {
        var units = new GeneratedUnits(outputPackage);
        var facts = store == null ? RoutineWriteFacts.Rows.empty() : RoutineWriteFacts.read(store);
        var rows = facts.rows().stream()
            .map(fact -> rowOf(fact, schema, units))
            .toList();
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
     * One admitted coordinate's row, in the seat the store put it in. The seat is the whole of the
     * branch: a chain-seated write re-reads by walking its own {@code @reference} chain, a
     * carrier-seated one by the key its payload data field captured, and no third shape reaches
     * here because the seat relation admits no third verdict.
     */
    private static RoutineWriteCommand rowOf(RoutineWriteFacts.Row fact, GraphitronSchema schema,
            GeneratedUnits units) {
        var coordinate = FieldCoordinates.coordinates(fact.typeName(), fact.fieldName());
        var unit = units.fetcherEntryMethod(fact.typeName(), fact.fieldName());
        var arity = fact.listReturn() ? Arity.LIST : Arity.SINGLE;
        var errors = dispatchFor(schema, coordinate, units);
        return switch (fact.seat()) {
            case CHAIN -> new RoutineWriteCommand.ChainReread(unit, coordinate, fact.call(),
                anchorOf(fact), tailOf(fact),
                units.typeClass(fact.returnTypeName()), arity, errors);
            case CARRIER -> new RoutineWriteCommand.CarrierKeys(unit, coordinate, fact.call(),
                fact.capturedPairs(), fact.targetTable(), arity, errors);
        };
    }

    /**
     * The re-read's departure: the chain's first hop, which the statement selects from rather than
     * joins, so what the row keeps of it is its table, its alias and the pairing the capture
     * projects and filters on.
     */
    private static RoutineWriteCommand.RereadAnchor anchorOf(RoutineWriteFacts.Row fact) {
        if (fact.hops().isEmpty()) {
            throw new IllegalStateException(
                "the chain-seated routine write at " + fact.typeName() + "." + fact.fieldName()
                + " states no hop after its routine node, so its post-commit re-read has no table"
                + " to depart from; the seat relation admits that shape only with a chain to walk");
        }
        var hop = fact.hops().getFirst();
        if (!(hop.on() instanceof JoinBasis.ColumnPairs pairs)) {
            throw new IllegalStateException(
                "the routine-write coordinate " + fact.typeName() + "." + fact.fieldName()
                + " anchors its post-commit re-read on a hop joining by "
                + hop.on().getClass().getSimpleName() + "; only column pairs can anchor it, because"
                + " every other shape leaves the re-read no key to filter on");
        }
        return new RoutineWriteCommand.RereadAnchor(hop.table(), hop.alias(), pairs.pairs());
    }

    /** The hops after the anchor, in chain order: the re-read's forward joins. */
    private static List<RoutineWriteCommand.RereadHop> tailOf(RoutineWriteFacts.Row fact) {
        return fact.hops().stream().skip(1)
            .map(hop -> new RoutineWriteCommand.RereadHop(hop.table(), hop.alias(), hop.on(),
                hop.filter()))
            .toList();
    }

    /**
     * The coordinate's error channel restated as what the {@code catch} arm emits, read off the
     * classified field rather than the store.
     *
     * <p>This is the one fact of the row the store cannot state per coordinate. A routed channel's
     * mappings constant is named by a dedup over every channel-carrying field in the schema
     * ({@link no.sikt.graphitron.rewrite.MappingsConstantNameDedup}), so minting it from one
     * coordinate's facts would either collide with a sibling's constant or invent a second naming
     * rule; the read stays on the schema until that fold has a home of its own. It narrows to
     * {@link WithErrorChannel}, which the whole classified model shares, so no mutation leaf is
     * named here.
     *
     * <p>An absent channel is the router's privacy disposition; a {@link ErrorChannel.LocalContext}
     * channel hands the matched throwable back as graphql-java {@code localContext}, and its
     * mappings constant is all the arm needs. The remaining channel arm is unreachable on these two
     * shapes by classification: a routine write's carrier is a directiveless structural payload,
     * which has no developer payload class for the mapped arm to instantiate.
     */
    private static ErrorDispatch dispatchFor(GraphitronSchema schema, FieldCoordinates coordinate,
            GeneratedUnits units) {
        var field = schema.fields().get(coordinate);
        var channel = field instanceof WithErrorChannel withChannel
            ? withChannel.errorChannel()
            : Optional.<ErrorChannel>empty();
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
}
