package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.Arity;
import no.sikt.graphitron.command.CatalogColumn;
import no.sikt.graphitron.command.CatalogTable;
import no.sikt.graphitron.command.ErrorDispatch;
import no.sikt.graphitron.command.JoinBasis;
import no.sikt.graphitron.command.JoinCondition;
import no.sikt.graphitron.command.KeyPair;
import no.sikt.graphitron.command.RoutineCall;
import no.sikt.graphitron.command.RoutineWriteCommand;
import no.sikt.graphitron.command.TenantAcquisition;
import no.sikt.graphitron.command.TenantRouting;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.JoinConditionRef;
import no.sikt.graphitron.rewrite.model.JoinSlot;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.RoutineRef;
import no.sikt.graphitron.rewrite.model.TableExpr;
import no.sikt.graphitron.rewrite.model.TableRef;
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
                routineCallOf(f.chain().start()),
                anchorOf(f),
                tailOf(f),
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
                routineCallOf(new TableExpr.RoutineCall(f.routine(), f.routineResultTable())),
                pairsOf(f.capturedPairs()),
                tableOf(f.targetTable()),
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
     * The re-read's departure, narrowed once here. The leaf guarantees both halves in its own
     * constructor (at least one hop, and hop 0 joining by column pairs), so this is the
     * translation of that guarantee into the shape the row declares, not a second assertion of
     * it: what the check below adds is the Java narrowing the wider carrier's type cannot
     * express, which is why the command used to re-derive it through casts at every read.
     */
    private static RoutineWriteCommand.RereadAnchor anchorOf(MutationField.MutationRoutineWriteField f) {
        var hop = hopAt(f, 0);
        if (!(hop.on() instanceof On.ColumnPairs pairs)) {
            throw new IllegalStateException(
                "the routine-write coordinate " + f.parentTypeName() + "." + f.name() + " anchors"
                + " its post-commit re-read on a hop joining by " + hop.on().getClass().getSimpleName()
                + "; the classifier's re-read-anchor verdict admits only column pairs, because"
                + " every other shape leaves the re-read no key to filter on");
        }
        return new RoutineWriteCommand.RereadAnchor(tableOf(hop.targetTable()), hop.alias(),
            pairsOf(pairs.slots()));
    }

    /** The hops after the anchor, in authored order: the re-read's forward joins. */
    private static List<RoutineWriteCommand.RereadHop> tailOf(MutationField.MutationRoutineWriteField f) {
        var tail = new ArrayList<RoutineWriteCommand.RereadHop>();
        for (int i = 1; i < f.chain().hops().size(); i++) {
            var hop = hopAt(f, i);
            tail.add(new RoutineWriteCommand.RereadHop(tableOf(hop.targetTable()), hop.alias(),
                joinBasisOf(hop, f), conditionOf(hop.filter())));
        }
        return tail;
    }

    private static JoinStep.Hop hopAt(MutationField.MutationRoutineWriteField f, int index) {
        return switch (f.chain().hops().get(index)) {
            case JoinStep.Hop hop -> hop;
        };
    }

    // -------------------------------------------------------------------------------------
    // The catalog facts, restated as the captured names the row carries. Every method below
    // narrows a walk-minted ref onto the command tier's own vocabulary: the emit types the refs
    // hold are decided again in the renderer, from these names, which is what keeps the plan from
    // deciding how a class is spelled as well as which class it is. They are the shape of a read
    // rather than a translation the plan owes the world, so a producer sourcing the same facts
    // from the store decodes rows into these types directly and drops the methods.
    // -------------------------------------------------------------------------------------

    /**
     * The re-read's join basis, decoded from the hop's own resolution.
     *
     * <p>The lateral arm is refused here, which is where the family's rule that the routine
     * appears in no statement after the one that ran it becomes structural. A routine node in a
     * chain carries that arm by the hop's own invariant, and the command tier can spell no lateral
     * join, so the shape stops at the mint instead of reaching a renderer that would have to throw
     * on it. This is the produce-time narrowing {@link no.sikt.graphitron.command.FkHop#narrow}
     * sets the precedent for.
     */
    private static JoinBasis joinBasisOf(JoinStep.Hop hop, MutationField.MutationRoutineWriteField f) {
        return switch (hop.on()) {
            case On.ColumnPairs cp -> new JoinBasis.ColumnPairs(keyingOf(cp.keying()), pairsOf(cp.slots()));
            case On.Predicate p -> new JoinBasis.Predicate(conditionOf(p.condition()));
            case On.Lateral ignored -> throw new IllegalStateException(
                "the routine-write re-read for " + f.parentTypeName() + "." + f.name() + " reaches"
                + " a lateral hop at alias '" + hop.alias() + "'; a chain admits exactly one"
                + " routine node, its start, and re-invoking it after the commit would re-execute"
                + " the write");
        };
    }

    private static JoinBasis.Keying keyingOf(On.Keying keying) {
        return switch (keying) {
            case On.Keying.ForeignKey fk -> new JoinBasis.Keying.ForeignKey(
                fk.fk().keysClass().canonicalName(), fk.fk().constantName());
            case On.Keying.NameMatchedKey ignored -> new JoinBasis.Keying.NameMatched();
        };
    }

    /** Null in, null out: an absent {@code condition:} is an absent filter, never a blank one. */
    private static JoinCondition conditionOf(JoinConditionRef ref) {
        return ref == null ? null
            : new JoinCondition(ref.method().className(), ref.method().methodName());
    }

    private static List<KeyPair> pairsOf(List<JoinSlot.FkSlot> slots) {
        return slots.stream()
            .map(s -> new KeyPair(columnOf(s.sourceSide()), columnOf(s.targetSide())))
            .toList();
    }

    private static CatalogTable tableOf(TableRef table) {
        return new CatalogTable(table.tableName(), table.javaFieldName(),
            table.tableClass().canonicalName(), table.constantsClass().canonicalName());
    }

    /**
     * A column's captured form. {@code columnClass} rather than the ref's decoded javapoet type,
     * because that name is the one the store holds and the one an array column survives: it is the
     * raw {@code Class.getName()} spelling, which the renderer decodes back.
     */
    private static CatalogColumn columnOf(ColumnRef column) {
        return new CatalogColumn(column.sqlName(), column.javaName(), column.columnClass());
    }

    /**
     * The routine call, with its IN parameters in declaration order.
     *
     * <p>Every binding is refused unless it reads a request value. A routine parameter may also
     * read a column of the node a chain arrives from, but a routine <em>write</em> sits at a
     * mutation root where the routine is the chain's head and there is no previous node to name;
     * the classifier refuses such a binding there, so reaching it here is drift rather than a
     * shape this family defers.
     */
    private static RoutineCall routineCallOf(TableExpr.RoutineCall call) {
        var routine = call.routine();
        return new RoutineCall(routine.routinesClass().canonicalName(), routine.methodName(),
            tableOf(call.resultTable()),
            routine.argBindings().stream().map(b -> argumentOf(routine, b)).toList());
    }

    private static RoutineCall.RoutineArgument argumentOf(RoutineRef routine,
            RoutineRef.ArgBinding binding) {
        if (!(binding.source() instanceof ParamSource.Arg arg)) {
            throw new IllegalStateException(
                "the routine parameter '" + binding.routineParamName() + "' of "
                + routine.methodName() + " binds a column of the chain's previous node, and a"
                + " routine write's call is the chain's head, which has no previous node");
        }
        return new RoutineCall.RoutineArgument(binding.routineParamName(),
            binding.paramType().toString(), arg.path().asString());
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
