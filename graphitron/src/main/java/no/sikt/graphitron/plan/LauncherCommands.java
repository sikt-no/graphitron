package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.CarrierDsl;
import no.sikt.graphitron.command.ConditionCommand;
import no.sikt.graphitron.command.FacetPlan;
import no.sikt.graphitron.command.GlueCall;
import no.sikt.graphitron.command.Invocation;
import no.sikt.graphitron.command.TenantStrategy;
import no.sikt.graphitron.command.LaunchSource;
import no.sikt.graphitron.command.LauncherCommand;
import no.sikt.graphitron.command.Ordering;
import no.sikt.graphitron.command.ResultShape;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.DeliveryFact;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.OperationMembers;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.RoutineResolution;
import no.sikt.graphitron.rewrite.model.RootField;
import no.sikt.graphitron.rewrite.model.TargetShape;
import no.sikt.graphitron.rewrite.model.TenantBinding;
import no.sikt.graphitron.rewrite.model.TenantScopes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Produces the launcher command relation: one {@link LauncherCommand} row per covered
 * coordinate. Membership has one home, {@link #verdictOf(List, DeliveryFact, OutputField)}:
 * anchor-hood (a view over the delivery fact) joined with the members the launch hosts. A
 * child hosting a serviceCall member launches through its loader; a coordinate hosting write
 * and reentry members launches the write's reentry companion; a select member on a
 * single-table-anchored target (or a pivot member) launches a catalog unit when the
 * coordinate is a root or its delivery is batched. No leaf identity participates in the
 * verdict; the payload builders behind each verdict arm keep their sanctioned leaf reads
 * until the dissolution slices move the payloads, guarded by loud membership-drift throws.
 *
 * <p>What replaced the retired leaf-identity membership switches' compile error for an
 * undecided new leaf: a new leaf must declare its member shape (the {@code OperationMembers}
 * crosswalk and declared-shape table are total) and its delivery arm
 * ({@link DeliveryFact#leafDerivedOf} is total), the verdict then derives, and the membership
 * censuses (the launcher membership fixture, the closure test's covered-set equality, the
 * delivery and member agreement pins) fail on any divergence between the declared facts and
 * observed minting.
 *
 * <p>The generator's dispatch does not restate this membership: it routes on row presence
 * (a coordinate with a row gets the launcher emission), so the predicate has one home and the
 * pipeline boundary pins are its observable form.
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

    private LauncherCommands() {}

    /**
     * The launch-family verdict: which launcher family a coordinate's facts place it in, or
     * {@link #NONE}. The membership declaration and the production dispatch are total switches
     * over these arms, so a new family is a compile-time decision at every consumer
     * ({@link #mintedMethodOf}, the {@link #produce} dispatch, the schema-free walk) rather
     * than a re-derived boolean; the membership census pins a per-arm non-vacuity floor over
     * its fixture so a family silently ceasing to produce is a census failure.
     */
    public enum Launch {
        /** No launcher row: the coordinate rides another statement or has no query of its own. */
        NONE,
        /** A {@code @service} child's loader delegation ({@code load<Field>}). */
        SERVICE,
        /**
         * A root {@code @service} table return's reentry companion re-select
         * ({@code rows<Field>}), keyed on the primary keys lifted off the records the
         * developer's method handed back.
         */
        SERVICE_REENTRY,
        /** A DML write's reentry companion re-select ({@code reentryRows<Field>}). */
        DML_REENTRY,
        /** A root catalog launch ({@code launcher<Field>} / {@code lookup<Field>}). */
        ROOT_CATALOG,
        /** A batched child's catalog re-query ({@code rows<Field>}). */
        BATCHED_CHILD_CATALOG
    }

    /**
     * The one membership accessor: the verdict for {@code field} under {@code schema}'s member
     * and delivery views. Every membership consumer (the closure test's covered set,
     * {@link #mintedMethodOf}, the {@link #produce} dispatch, the Encoded-DML negative pin)
     * reads the verdict here.
     */
    public static Launch verdictOf(GraphitronSchema schema, GraphitronField field) {
        if (!(field instanceof OutputField out)) {
            return Launch.NONE;
        }
        var coord = FieldCoordinates.coordinates(out.parentTypeName(), out.name());
        return verdictOf(schema.operationMembersOf(coord), schema.deliveryOf(coord), out);
    }

    /** Whether {@code field} mints a launcher row: {@link #verdictOf} against {@link Launch#NONE}. */
    public static boolean covers(GraphitronSchema schema, GraphitronField field) {
        return verdictOf(schema, field) != Launch.NONE;
    }

    /**
     * The verdict from the facts alone, parameterized on its inputs so the schema view and the
     * schema-free walk read one predicate over two fact sources (the members and delivery
     * views on a walk-built schema; the leaf projection and crosswalk on a walk-less one).
     *
     * <p>The four rules, in order: a child hosting a serviceCall member launches through its
     * loader (the call is the delivery, so anchor-hood is bypassed by the member the way the
     * member production lets the call claim the projection slot); a root hosting a serviceCall
     * beside a reentry member launches the call's companion re-select, keyed on what the
     * developer's method handed back (a table-bound root service return, the only root service
     * shape that mints reentry); write plus reentry members launch the write's companion
     * re-select (the launcher relation's reentry-sourced rows; the {@code Encoded*} returns mint
     * no reentry member, so their exclusion is the member fact, stated nowhere); a select member
     * on a single-table-anchored target, or a pivot
     * member, launches a catalog unit when the coordinate is a root (roots always run their
     * own unit) or its delivery is batched. Single-table anchoring is the target-axis fact
     * {@link TargetShape.Table}: the multi-table polymorphic family carries
     * {@code Interface} / {@code Union} shapes and its UNION-ALL stage belongs to the
     * polymorphic-emit family, roots and batched children alike.
     */
    public static Launch verdictOf(List<OperationMember> members,
            DeliveryFact delivery, OutputField out) {
        boolean root = out instanceof RootField;
        if (!root && hasKind(members, OperationMember.Kind.SERVICE_CALL)) {
            return Launch.SERVICE;
        }
        if (root && hasKind(members, OperationMember.Kind.SERVICE_CALL)
                && hasKind(members, OperationMember.Kind.REENTRY)) {
            return Launch.SERVICE_REENTRY;
        }
        if (hasKind(members, OperationMember.Kind.WRITE)
                && hasKind(members, OperationMember.Kind.REENTRY)) {
            return Launch.DML_REENTRY;
        }
        TargetShape shape = out.target().shape();
        TargetShape unwrapped = shape instanceof TargetShape.Connection c ? c.inner() : shape;
        boolean anchored =
            hasKind(members, OperationMember.Kind.SELECT)
                && unwrapped instanceof TargetShape.Table
            || hasKind(members, OperationMember.Kind.PIVOT);
        if (anchored && root) {
            return Launch.ROOT_CATALOG;
        }
        if (anchored && delivery instanceof DeliveryFact.Batched) {
            return Launch.BATCHED_CHILD_CATALOG;
        }
        return Launch.NONE;
    }

    private static boolean hasKind(List<OperationMember> members, OperationMember.Kind kind) {
        return members.stream().anyMatch(m -> m.kind() == kind);
    }

    /**
     * The delivery arm each source arm's rows carry, declared beside the dispatch as producer
     * data: the invocation axis is functionally determined by the source arm (the root and
     * discriminated kinds run direct, the batched and service children register a DataLoader,
     * the reentry companions take the keys their entry point captured). Total over
     * {@link LaunchSource}'s concrete arms; the test tree asserts totality and, at every
     * relation it builds, that each produced row's invocation arm equals the declared arm for
     * its source arm.
     */
    public static final Map<Class<? extends LaunchSource>, Class<? extends Invocation>> INVOCATION_BY_SOURCE =
        Map.ofEntries(
            Map.entry(LaunchSource.AnchorTable.class, Invocation.Direct.class),
            Map.entry(LaunchSource.RoutineChain.class, Invocation.Direct.class),
            Map.entry(LaunchSource.DiscriminatedTable.class, Invocation.Direct.class),
            Map.entry(LaunchSource.KeyedLookup.class, Invocation.Direct.class),
            Map.entry(LaunchSource.CorrelatedChain.class, Invocation.Batched.class),
            Map.entry(LaunchSource.CorrelatedLookupChain.class, Invocation.Batched.class),
            Map.entry(LaunchSource.DiscriminatedCorrelatedChain.class, Invocation.Batched.class),
            Map.entry(LaunchSource.PivotAggregate.class, Invocation.Batched.class),
            Map.entry(LaunchSource.ServiceCall.class, Invocation.Batched.class),
            Map.entry(LaunchSource.ServiceTableLift.class, Invocation.Batched.class),
            Map.entry(LaunchSource.ProjectedReentry.class, Invocation.ReturningKeyed.class),
            Map.entry(LaunchSource.DiscriminatedReentry.class, Invocation.ReturningKeyed.class));

    public static LauncherRelation produce(GraphitronSchema schema, ConditionRelation conditions,
            String outputPackage) {
        var units = new GeneratedUnits(outputPackage);
        var rows = new ArrayList<LauncherCommand>();
        for (var type : schema.types().values()) {
            for (var field : schema.fieldsOf(type.name())) {
                var verdict = verdictOf(schema, field);
                if (verdict == Launch.NONE) {
                    continue;
                }
                rows.add(switch (verdict) {
                    case NONE -> throw new IllegalStateException("unreachable: NONE is filtered above");
                    case SERVICE -> serviceRow(field, units);
                    case SERVICE_REENTRY -> serviceReentryRow(field, units);
                    case DML_REENTRY -> dmlRowOf(schema, requireDmlCarrier(field), units);
                    case ROOT_CATALOG -> rootCatalogRow(schema, field, conditions, units);
                    case BATCHED_CHILD_CATALOG -> batchedChildRow(schema, field, conditions, units);
                });
            }
        }
        var carrierDsl = schema.tenantScopes() instanceof TenantScopes.Configured
            ? CarrierDsl.ROUTED
            : CarrierDsl.ENV_ACQUIRED;
        return new LauncherRelation(rows, carrierDsl);
    }

    /**
     * The root catalog family's payload dispatch, reached only behind a
     * {@link Launch#ROOT_CATALOG} verdict: the leaf reads are the sanctioned payload half of
     * the additive window (each arm extracts what its row carries from the leaf-resolved
     * components), and the default throw is the membership-drift guard, not a membership
     * statement (membership lives on the verdict alone).
     */
    private static LauncherCommand rootCatalogRow(GraphitronSchema schema, GraphitronField field,
            ConditionRelation conditions, GeneratedUnits units) {
        return switch (field) {
            case QueryField.QueryTableField qtf -> {
                if (qtf.routine() instanceof RoutineResolution.Chain chain) {
                    yield routineRow(qtf, chain, whereOf(qtf, conditions),
                        facetPlanOf(schema, qtf, conditions, units), units);
                }
                var mapping = keyedLookupOf(
                    schema.operationMembersOf(qtf.parentTypeName(), qtf.name()));
                yield mapping != null
                    ? lookupRow(qtf, mapping, whereOf(qtf.parentTypeName(), qtf.name(), conditions), units)
                    : row(qtf, whereOf(qtf, conditions), units,
                        facetPlanOf(schema, qtf, conditions, units),
                        tenancyOf(schema, qtf, units));
            }
            case QueryField.QueryTableInterfaceField qtif -> interfaceRow(qtif,
                schema.joinedTableReprojectionOf(qtif.returnType().returnTypeName()),
                schema::fieldsOf,
                whereOf(qtif.parentTypeName(), qtif.name(), conditions), units);
            default -> throw new IllegalStateException(
                "Graphitron generator bug (launcher production): coordinate '"
                + field.qualifiedName() + "' (" + field.getClass().getSimpleName()
                + ") received a root catalog launch verdict but has no payload arm here;"
                + " the membership predicate and this payload dispatch have drifted");
        };
    }

    /**
     * The batched child catalog family's payload dispatch, reached only behind a
     * {@link Launch#BATCHED_CHILD_CATALOG} verdict; same discipline as
     * {@link #rootCatalogRow}.
     */
    private static LauncherCommand batchedChildRow(GraphitronSchema schema, GraphitronField field,
            ConditionRelation conditions, GeneratedUnits units) {
        return switch (field) {
            case ChildField.BatchedTableField btf -> {
                var mapping = keyedLookupOf(
                    schema.operationMembersOf(btf.parentTypeName(), btf.name()));
                yield mapping != null
                    ? batchedLookupRow(btf, mapping,
                        whereOf(btf.parentTypeName(), btf.name(), conditions),
                        tenancyOf(schema, btf.parentTypeName(), btf.name(), units), units)
                    : batchedRow(btf, schema, conditions, units);
            }
            case ChildField.BatchedPivotField bpf -> batchedPivotRow(bpf, units);
            case ChildField.BatchedTableInterfaceField btif -> batchedInterfaceChildRow(btif,
                schema.joinedTableReprojectionOf(btif.returnType().returnTypeName()),
                schema::fieldsOf,
                whereOf(btif.parentTypeName(), btif.name(), conditions), units);
            default -> throw new IllegalStateException(
                "Graphitron generator bug (launcher production): coordinate '"
                + field.qualifiedName() + "' (" + field.getClass().getSimpleName()
                + ") received a batched child catalog launch verdict but has no payload arm"
                + " here; the membership predicate and this payload dispatch have drifted");
        };
    }

    /**
     * The {@code @service} family's payload dispatch, reached only behind a
     * {@link Launch#SERVICE} verdict; same discipline as {@link #rootCatalogRow}.
     */
    private static LauncherCommand serviceRow(GraphitronField field, GeneratedUnits units) {
        return switch (field) {
            case ChildField.ServiceTableField stf -> serviceTableRow(stf, units);
            case ChildField.ServiceRecordField srf -> serviceRecordRow(srf, units);
            default -> throw new IllegalStateException(
                "Graphitron generator bug (launcher production): coordinate '"
                + field.qualifiedName() + "' (" + field.getClass().getSimpleName()
                + ") received a service launch verdict but has no payload arm here;"
                + " the membership predicate and this payload dispatch have drifted");
        };
    }

    /**
     * The root {@code @service} table-return family's payload dispatch, reached only behind a
     * {@link Launch#SERVICE_REENTRY} verdict; same discipline as {@link #rootCatalogRow}. Both
     * leaves carry the same three facts the companion needs (the return table, its projection
     * unit, the result cardinality), so the arms differ only in which leaf they read them off.
     */
    private static LauncherCommand serviceReentryRow(GraphitronField field, GeneratedUnits units) {
        return switch (field) {
            case QueryField.QueryServiceTableField f ->
                serviceReentryRow(f, f.returnType(), units);
            case MutationField.MutationServiceTableField f ->
                serviceReentryRow(f, f.returnType(), units);
            default -> throw new IllegalStateException(
                "Graphitron generator bug (launcher production): coordinate '"
                + field.qualifiedName() + "' (" + field.getClass().getSimpleName()
                + ") received a root service reentry launch verdict but has no payload arm here;"
                + " the membership predicate and this payload dispatch have drifted");
        };
    }

    /**
     * A root {@code @service} table return's companion row: the named unit holding the follow-up
     * SELECT, keyed on the primary keys the fetcher lifts off the records the developer's method
     * returned. The correlation is PK self-identity, the degenerate {@code OnLiftedSlots} the
     * record-sourced carrier re-fetch already runs, so the same {@link LaunchSource.ProjectedReentry}
     * arm serves this caller as serves the projected DML return; what varies is only where the
     * keys were captured. The where slot stays null (the root service leaves declare no filter
     * surface of their own; the developer's method owns the selection), the list arm's ORDER BY
     * idx is source-entailed so the ordering slot is absent, and tenancy is single by
     * classification (a tenant fan-out on a root {@code @service} is classifier-rejected).
     */
    private static LauncherCommand serviceReentryRow(OutputField field,
            no.sikt.graphitron.rewrite.model.ReturnTypeRef.TableBoundReturnType returnType,
            GeneratedUnits units) {
        var table = returnType.table();
        return new LauncherCommand(
            units.reentryRowsMethod(field.parentTypeName(), field.name()),
            FieldCoordinates.coordinates(field.parentTypeName(), field.name()),
            new LaunchSource.ProjectedReentry(
                units.typeClass(returnType.returnTypeName()),
                new no.sikt.graphitron.rewrite.model.ParentCorrelation.OnLiftedSlots(
                    table, table.primaryKeyColumns())),
            null,
            new Invocation.ReturningKeyed(),
            new TenantStrategy.Single(),
            returnType.wrapper().isList()
                ? new ResultShape.RecordList(null)
                : new ResultShape.SingleRecord());
    }

    /**
     * The write member's payload home during the additive window: the reentry companion's
     * return-shape facts still live on the DML leaf's return-expression arm, so a reentry
     * launch verdict on any other carrier is membership drift, surfaced loudly.
     */
    private static MutationField.DmlTableField requireDmlCarrier(GraphitronField field) {
        if (!(field instanceof MutationField.DmlTableField dml)) {
            throw new IllegalStateException(
                "Graphitron generator bug (launcher production): coordinate '"
                + field.qualifiedName() + "' (" + field.getClass().getSimpleName()
                + ") received a DML reentry launch verdict but carries no DML return"
                + " expression; the membership predicate and the write payload home have"
                + " drifted");
        }
        return dml;
    }

    /**
     * The DML reentry companion's payload dispatch over
     * {@link no.sikt.graphitron.rewrite.model.DmlReturnExpression}'s arms, reached only behind
     * a {@link Launch#DML_REENTRY} verdict; this switch is the one home of the
     * kind-to-(source, result) projection, total with no default so a new return arm is a
     * compile-time decision. The {@code Projected*} and {@code Discriminated*} arms mint the
     * coordinate's reentry companion row (the write itself stays with the mutation entry
     * point, which is deliberately not thin: it owns the transaction, the dialect guard, the
     * no-match guard and the channel envelope); the {@code Encoded*} arms mint no reentry
     * member, so their exclusion is the member fact and reaching them here is membership
     * drift, surfaced loudly.
     */
    private static LauncherCommand dmlRowOf(GraphitronSchema schema,
            no.sikt.graphitron.rewrite.model.MutationField.DmlTableField field, GeneratedUnits units) {
        return switch (field.returnExpression()) {
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.EncodedSingle ignored ->
                throw encodedDriftGuard(field);
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.EncodedList ignored ->
                throw encodedDriftGuard(field);
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedSingle ps ->
                reentryRow(field,
                    new LaunchSource.ProjectedReentry(units.typeClass(ps.returnTypeName()),
                        ps.reentryCorrelation()),
                    new ResultShape.SingleRecord(), units);
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedList pl ->
                reentryRow(field,
                    new LaunchSource.ProjectedReentry(units.typeClass(pl.returnTypeName()),
                        pl.reentryCorrelation()),
                    new ResultShape.RecordList(null), units);
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.DiscriminatedSingle ds ->
                reentryRow(field,
                    discriminatedReentrySource(schema, ds.interfaceName(), ds.discriminatorColumn(),
                        ds.knownDiscriminatorValues(), ds.participants(), ds.reentryCorrelation(), units),
                    new ResultShape.SingleRecord(), units);
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.DiscriminatedList dl ->
                reentryRow(field,
                    discriminatedReentrySource(schema, dl.interfaceName(), dl.discriminatorColumn(),
                        dl.knownDiscriminatorValues(), dl.participants(), dl.reentryCorrelation(), units),
                    new ResultShape.RecordList(null), units);
        };
    }

    private static IllegalStateException encodedDriftGuard(
            no.sikt.graphitron.rewrite.model.MutationField.DmlTableField field) {
        return new IllegalStateException(
            "Graphitron generator bug (launcher production): coordinate '"
            + field.qualifiedName() + "' carries an encoded DML return, which mints no reentry"
            + " member, yet received a DML reentry launch verdict; the membership predicate and"
            + " the return-expression payload dispatch have drifted");
    }

    /**
     * A DML reentry companion's row: the named unit holding the mutation's follow-up SELECT,
     * keyed on the {@code RETURNING}-captured keys. The where slot stays null (the mutation's
     * filter surface belongs to the write, never the re-select); the list arm's ORDER BY idx is
     * source-entailed, so the ordering slot is absent; single-tenant by classification
     * (backstopped on the command with the delivery biconditional).
     */
    private static LauncherCommand reentryRow(
            no.sikt.graphitron.rewrite.model.MutationField.DmlTableField field,
            LaunchSource source, ResultShape result, GeneratedUnits units) {
        return new LauncherCommand(
            units.reentryRowsMethod(field.parentTypeName(), field.name()),
            FieldCoordinates.coordinates(field.parentTypeName(), field.name()),
            source,
            null,
            new Invocation.ReturningKeyed(),
            new TenantStrategy.Single(),
            result);
    }

    /**
     * The discriminated reentry's source: the borrowed-whole {@link LaunchSource.DiscriminatedTable}
     * payload assembled exactly as {@link #interfaceRow}'s (the base slice copied off the
     * schema's joined-table reprojection fold, the branches through the shared assembly), plus
     * the write's correlation.
     */
    private static LaunchSource.DiscriminatedReentry discriminatedReentrySource(
            GraphitronSchema schema, String interfaceName, ColumnRef discriminatorColumn,
            List<String> knownDiscriminatorValues,
            List<no.sikt.graphitron.rewrite.model.ParticipantRef> participants,
            no.sikt.graphitron.rewrite.model.ParentCorrelation.OnLiftedSlots correlation,
            GeneratedUnits units) {
        var reprojection = schema.joinedTableReprojectionOf(interfaceName);
        return new LaunchSource.DiscriminatedReentry(
            new LaunchSource.DiscriminatedTable(correlation.targetTable(),
                discriminatorColumn, knownDiscriminatorValues,
                reprojection.baseSlice(),
                discriminatedBranches(participants, discriminatorColumn, reprojection, units),
                selectionRestriction(participants, schema::fieldsOf, units)),
            correlation);
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
     *
     * <p>The walk reads the same membership predicate as the schema path
     * ({@link #verdictOf(List, DeliveryFact, OutputField)}), sourced from the leaf projection
     * and the delivery crosswalk (the walk-less fact sources behind the schema views), so the
     * two walks cannot disagree on membership. The DML reentry companions are deliberately
     * absent: a schema-free assembly builds no mutation writes, so no captured
     * {@code RETURNING} keys exist for a companion to re-select by, and the reentry verdict is
     * skipped rather than served. The root {@code @service} companion is served, the same
     * reasoning read the other way: the keys are captured from the developer's returned records
     * at the call site, so the row needs only leaf facts, and these leaves the assemblies build.
     */
    public static LauncherRelation produceWithoutSchema(List<? extends GraphitronField> fields,
            String outputPackage) {
        var units = new GeneratedUnits(outputPackage);
        var rows = new ArrayList<LauncherCommand>();
        // The schema view's stand-in for the alias-owner projection: a schema-free assembly's own
        // field list, grouped by declaring type. A participant whose fields the assembly never
        // handed us contributes no per-type name, so the fold restricts nothing for it, which is
        // this path's pre-existing behaviour rather than a claim about its participants.
        var fieldsByParentType = new java.util.LinkedHashMap<String, List<GraphitronField>>();
        for (var field : fields) {
            fieldsByParentType
                .computeIfAbsent(field.parentTypeName(), key -> new ArrayList<>())
                .add(field);
        }
        java.util.function.Function<String, List<? extends GraphitronField>> fieldsOfType =
            typeName -> fieldsByParentType.getOrDefault(typeName, List.of());
        for (var field : fields) {
            if (!(field instanceof OutputField out)) {
                continue;
            }
            var verdict = verdictOf(OperationMembers.membersOf(out), DeliveryFact.leafDerivedOf(out), out);
            if (verdict == Launch.NONE || verdict == Launch.DML_REENTRY) {
                continue;
            }
            rows.add(switch (field) {
                case QueryField.QueryTableField qtf -> {
                    if (qtf.routine() instanceof RoutineResolution.Chain chain) {
                        yield routineRow(qtf, chain, glueFromFilters(qtf, units), null, units);
                    }
                    var mapping = keyedLookupOf(OperationMembers.membersOf(qtf));
                    yield mapping != null
                        ? lookupRow(qtf, mapping, glueFromFilters(qtf, units), units)
                        : row(qtf, glueFromFilters(qtf, units), units, null, new TenantStrategy.Single());
                }
                // The residence split is a classified-schema fact; a schema-free assembly's
                // joined participants carry no base slice and no detail fields, the same
                // fallback the retired inline assembly took on a null schema.
                case QueryField.QueryTableInterfaceField qtif ->
                    interfaceRow(qtif, no.sikt.graphitron.rewrite.JoinedTableReprojection.EMPTY,
                        fieldsOfType, glueFromInterfaceFilters(qtif, units), units);
                case ChildField.BatchedTableField btf -> {
                    var mapping = keyedLookupOf(OperationMembers.membersOf(btf));
                    var glue = glueFromFilters(btf.parentTypeName(), btf.name(), btf.filters(), units);
                    yield mapping != null
                        ? batchedLookupRow(btf, mapping, glue, new TenantStrategy.Single(), units)
                        : batchedRow(btf, glue, new TenantStrategy.Single(), units);
                }
                case ChildField.BatchedPivotField bpf -> batchedPivotRow(bpf, units);
                // The residence split is a classified-schema fact, the root interface arm's
                // fallback: no base slice, no detail fields.
                case ChildField.BatchedTableInterfaceField btif -> batchedInterfaceChildRow(btif,
                    no.sikt.graphitron.rewrite.JoinedTableReprojection.EMPTY,
                    fieldsOfType,
                    glueFromFilters(btif.parentTypeName(), btif.name(), btif.filters(), units),
                    units);
                case ChildField.ServiceTableField stf -> serviceTableRow(stf, units);
                case ChildField.ServiceRecordField srf -> serviceRecordRow(srf, units);
                // Unlike the DML companions, the root service companion is served here: its
                // payload needs only leaf facts (the return table's primary key and the
                // projection unit), and the unit-tier fetcher assemblies do build these leaves.
                case QueryField.QueryServiceTableField qstf -> serviceReentryRow(qstf, units);
                case MutationField.MutationServiceTableField mstf -> serviceReentryRow(mstf, units);
                default -> throw new IllegalStateException(
                    "Graphitron generator bug (schema-free launcher production): field '"
                    + field.qualifiedName() + "' received a launch verdict but has no"
                    + " schema-free production arm here; the membership predicate and this"
                    + " payload dispatch have drifted");
            });
        }
        return new LauncherRelation(rows, CarrierDsl.ENV_ACQUIRED);
    }

    /**
     * The lookup member's mapping payload, or null when the coordinate's member set carries no
     * lookup member. The payload dispatch's fork input: the verdict decides that a row exists,
     * the member decides which payload builder shapes it.
     */
    private static no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping keyedLookupOf(
            List<OperationMember> members) {
        for (var m : members) {
            if (m instanceof OperationMember.Lookup lookup) {
                return switch (lookup.lookupMapping()) {
                    case no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping cm -> cm;
                };
            }
        }
        return null;
    }

    private static GlueCall glueFromFilters(QueryField.QueryTableField qtf, GeneratedUnits units) {
        return glueFromFilters(qtf.parentTypeName(), qtf.name(), qtf.filters(), units);
    }

    private static GlueCall glueFromInterfaceFilters(QueryField.QueryTableInterfaceField qtif,
            GeneratedUnits units) {
        return glueFromFilters(qtif.parentTypeName(), qtif.name(), qtif.filters(), units);
    }

    private static GlueCall glueFromFilters(String parentTypeName, String fieldName,
            List<no.sikt.graphitron.rewrite.model.WhereFilter> filters, GeneratedUnits units) {
        if (filters.isEmpty()) {
            return null;
        }
        return new GlueCall(units.conditionMethod(parentTypeName, fieldName),
            no.sikt.graphitron.rewrite.model.WhereFilter.anyReadRequestContext(filters));
    }

    /**
     * A {@code @lookupKey} row: the source arm carries the anchor, the terminus projection, the
     * borrowed key mapping (whose VALUES rows the emitted input-rows helper builds; its ref is
     * minted here through the same formula the helper emission reads) and entails the input
     * ordering, so the list shape's ordering slot is absent. The launcher unit keeps the
     * pre-seam {@code lookup<Field>} name through its own minting scheme. Always direct; never
     * a connection (both classifier-rejected pairs, backstopped on the command).
     */
    private static LauncherCommand lookupRow(QueryField.QueryTableField qtf,
            no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping mapping, GlueCall where,
            GeneratedUnits units) {
        var owner = units.fetchers(qtf.parentTypeName());
        return new LauncherCommand(
            units.lookupMethod(qtf.parentTypeName(), qtf.name()),
            FieldCoordinates.coordinates(qtf.parentTypeName(), qtf.name()),
            new LaunchSource.KeyedLookup(qtf.returnType().table(),
                units.typeClass(qtf.returnType().returnTypeName()),
                mapping,
                units.inputRowsMethod(owner, qtf.name())),
            where,
            new Invocation.Direct(),
            new TenantStrategy.Single(),
            new ResultShape.RecordList(null));
    }

    private static LauncherCommand row(QueryField.QueryTableField qtf, GlueCall where, GeneratedUnits units,
            FacetPlan facets, TenantStrategy tenancy) {
        return new LauncherCommand(
            units.launcherMethod(qtf.parentTypeName(), qtf.name()),
            FieldCoordinates.coordinates(qtf.parentTypeName(), qtf.name()),
            new LaunchSource.AnchorTable(qtf.returnType().table(),
                units.typeClass(qtf.returnType().returnTypeName())),
            where,
            new Invocation.Direct(),
            tenancy,
            resultShapeOf(qtf, units, facets));
    }

    /**
     * A {@code @routine} chain row, the {@link RoutineResolution.Chain} fork of the root table
     * read: the source arm carries the borrowed start expression and the narrowed hop list (the
     * chain constructor's own guarantee), the projection targets the terminus type. The WHERE
     * slot and the result shape come off the coordinate's own components, through the same two
     * views the anchor-sourced row reads, because the read surface is independent of the source:
     * both resolve against the chain's terminus alias, which is what the renderer's select list
     * targets too. That includes the connection shape and its facet plan: a chain is a composite
     * FROM, not a different kind of read.
     */
    private static LauncherCommand routineRow(QueryField.QueryTableField qtf,
            RoutineResolution.Chain chain, GlueCall where, FacetPlan facets, GeneratedUnits units) {
        var hops = chain.chain().hops().stream()
            .map(step -> (no.sikt.graphitron.rewrite.model.JoinStep.Hop) step)
            .toList();
        return new LauncherCommand(
            units.launcherMethod(qtf.parentTypeName(), qtf.name()),
            FieldCoordinates.coordinates(qtf.parentTypeName(), qtf.name()),
            new LaunchSource.RoutineChain(chain.chain().start(), hops,
                units.typeClass(qtf.returnType().returnTypeName())),
            where,
            new Invocation.Direct(),
            new TenantStrategy.Single(),
            resultShapeOf(qtf, units, facets));
    }

    /**
     * A single-table discriminated interface row: the source arm carries the base table, the
     * source-entailed discriminator restriction, the whole-query base slice (copied off the
     * schema's joined-table reprojection fold) and the per-participant branches. Always
     * single-tenant: the fan-out ladder rejects {@code @tenantFanOut} on interface-typed fields.
     *
     * <p>The connection arm's facet plan is {@code null}, and legitimately so at both ends: no
     * facet synthesis reaches this coordinate, because
     * {@link no.sikt.graphitron.rewrite.GraphitronSchemaBuilder#unsupportedFacetCarrierReason}
     * rejects a carrier whose element is not a {@code @table}-backed object type at the SDL
     * boundary, which an interface element never is.
     */
    private static LauncherCommand interfaceRow(QueryField.QueryTableInterfaceField qtif,
            no.sikt.graphitron.rewrite.JoinedTableReprojection reprojection,
            java.util.function.Function<String, List<? extends GraphitronField>> fieldsOfType,
            GlueCall where, GeneratedUnits units) {
        var ordering = orderingOf(qtif.orderBy(), qtif.parentTypeName(), qtif.name(), units);
        return new LauncherCommand(
            units.launcherMethod(qtif.parentTypeName(), qtif.name()),
            FieldCoordinates.coordinates(qtif.parentTypeName(), qtif.name()),
            new LaunchSource.DiscriminatedTable(qtif.returnType().table(),
                qtif.discriminatorColumn(), qtif.knownDiscriminatorValues(),
                reprojection.baseSlice(),
                discriminatedBranches(qtif.participants(), qtif.discriminatorColumn(),
                    reprojection, units),
                selectionRestriction(qtif.participants(), fieldsOfType, units)),
            where,
            new Invocation.Direct(),
            new TenantStrategy.Single(),
            interfaceResultOf(qtif, ordering, units));
    }

    /** The discriminated root's payload shape, forked on the coordinate's wrapper. */
    private static ResultShape interfaceResultOf(QueryField.QueryTableInterfaceField qtif,
            Ordering ordering, GeneratedUnits units) {
        if (qtif.returnType().wrapper() instanceof FieldWrapper.Connection conn) {
            return connectionShape(conn, ordering, qtif.qualifiedName(), null, units);
        }
        return qtif.returnType().wrapper().isList()
            ? new ResultShape.RecordList(ordering)
            : new ResultShape.SingleRecord();
    }

    /**
     * The fold's selection restriction, as a projection over the participants' stamped
     * {@link no.sikt.graphitron.rewrite.model.AliasOwner} facts: the field names whose alias the
     * declaring participant type qualifies, which are exactly the names whose occurrences must be
     * scoped per participant for the alias to stay honest (see
     * {@link LaunchSource.DiscriminatedTable.SelectionRestriction}). Read off the stamped verdict
     * rather than re-derived from the interface declarations, so the restriction's granularity and
     * the alias's granularity cannot drift apart; a second derivation would agree today and
     * silently diverge the day a new alias-minting family missed one of them.
     *
     * <p>{@code fieldsOfType} is how the caller reaches a participant's classified fields: the
     * schema's own field view where a schema exists, and the assembly's own field list where it
     * does not. A lookup answering nothing yields no names, so the fold restricts nothing, which
     * is the pre-existing behaviour rather than a guess.
     *
     * <p>Sorted and deduplicated: the set is emitted as a literal, so its order is part of the
     * generated source and has to be a function of the schema alone.
     */
    public static LaunchSource.DiscriminatedTable.SelectionRestriction selectionRestriction(
            List<no.sikt.graphitron.rewrite.model.ParticipantRef> participants,
            java.util.function.Function<String, List<? extends GraphitronField>> fieldsOfType,
            GeneratedUnits units) {
        var perType = new java.util.TreeSet<String>();
        for (var participant : participants) {
            if (!(participant instanceof no.sikt.graphitron.rewrite.model.ParticipantRef.TableBound tb)) {
                continue;
            }
            for (var field : fieldsOfType.apply(tb.typeName())) {
                if (field instanceof no.sikt.graphitron.rewrite.model.ResultKeyAliasedField rk
                        && rk.aliasOwner() instanceof no.sikt.graphitron.rewrite.model.AliasOwner.QualifiedBy q
                        && q.owner().equals(tb.typeName())) {
                    perType.add(field.name());
                }
            }
        }
        return new LaunchSource.DiscriminatedTable.SelectionRestriction(
            units.singleton(GeneratedUnits.SUB_UTIL, POLYMORPHIC_SELECTION_SET_CLASS),
            List.copyOf(perType));
    }

    /**
     * The generated per-participant selection view's class name. Spelled here rather than read off
     * {@code PolymorphicSelectionSetClassGenerator}: the plan tier does not depend on the
     * generators tier, and {@code EmitPlan} spells the same name for the same reason.
     */
    private static final String POLYMORPHIC_SELECTION_SET_CLASS = "PolymorphicSelectionSet";

    /**
     * The per-participant branch assembly, shared with the legacy interface-reprojection call
     * sites (child twin, service fetcher, DML follow-ups) so the branch derivation and the
     * projection-ref minting have one home. Total over the table-backed variants; a non-table
     * participant cannot reach here (the parse boundary rejects non-table members of a
     * discriminated interface).
     *
     * <p>{@code discriminatorColumn} is read only to build the single-table branches' cross-table
     * gates: the lowering happens here, not in the renderer, so every consumer of the assembly
     * inherits one SQL shape for a participant scalar one {@code @reference} hop off the base.
     */
    public static List<LaunchSource.DiscriminatedTable.Branch> discriminatedBranches(
            List<no.sikt.graphitron.rewrite.model.ParticipantRef> participants,
            ColumnRef discriminatorColumn,
            no.sikt.graphitron.rewrite.JoinedTableReprojection reprojection, GeneratedUnits units) {
        var branches = new ArrayList<LaunchSource.DiscriminatedTable.Branch>(participants.size());
        for (var participant : participants) {
            branches.add(switch (participant) {
                case no.sikt.graphitron.rewrite.model.ParticipantRef.TableBound tb ->
                    new LaunchSource.DiscriminatedTable.Branch.SingleTable(tb,
                        units.typeClass(tb.typeName()),
                        crossTableTerms(tb, discriminatorColumn));
                case no.sikt.graphitron.rewrite.model.ParticipantRef.JoinedTableBound jtb ->
                    new LaunchSource.DiscriminatedTable.Branch.JoinedDetail(jtb,
                        reprojection.detailFieldsOf(jtb.typeName()));
                case no.sikt.graphitron.rewrite.model.ParticipantRef.Unbound unbound ->
                    throw new IllegalStateException(
                        "Graphitron generator bug (discriminated branch assembly): non-table"
                        + " participant '" + unbound.typeName() + "' reached branch assembly;"
                        + " the classifier rejects non-table members of a discriminated interface");
            });
        }
        return branches;
    }

    /**
     * A single-table participant's cross-table fields, lowered to capped correlated subselects:
     * one hop, the participant's fixed alias as the projected name (what the per-field fetcher
     * reads back), and the branch's discriminator equality as the gate, so a row of another
     * participant's type projects NULL. A participant carrying no {@code @discriminator} value
     * contributes no terms: an ungated subselect would resolve the reference for every row
     * regardless of type, which is the same reason the assembly skips that participant's join
     * arms today.
     */
    private static List<LaunchSource.DiscriminatedTable.Branch.SingleTable.CrossTableTerm>
            crossTableTerms(no.sikt.graphitron.rewrite.model.ParticipantRef.TableBound tb,
                    ColumnRef discriminatorColumn) {
        if (tb.discriminatorValue() == null) {
            return List.of();
        }
        return tb.crossTableFields().stream()
            .map(ctf -> new LaunchSource.DiscriminatedTable.Branch.SingleTable.CrossTableTerm(
                ctf.fieldName(),
                new no.sikt.graphitron.command.SelectTerm.ScalarSubselect(
                    List.of(ctf.hop()),
                    new no.sikt.graphitron.rewrite.model.ParentCorrelation.OnFkSlots(ctf.hop()),
                    ctf.column(),
                    ctf.aliasName(),
                    new no.sikt.graphitron.command.SelectTerm.ScalarSubselect.ParentColumnEquals(
                        discriminatorColumn, tb.discriminatorValue()))))
            .toList();
    }

    /**
     * The tenancy strategy, from the coordinate's tenancy binding: a fan-out coordinate runs
     * the composition once per domain tenant through the scatter carrier (whose ref rides the
     * arm), everything else is single-tenant. This is the one home of the fan-out fact for the
     * root family; the generator's dispatch and entry point read the arm, never the binding.
     */
    private static TenantStrategy tenancyOf(GraphitronSchema schema, QueryField.QueryTableField qtf,
            GeneratedUnits units) {
        return tenancyOf(schema, qtf.parentTypeName(), qtf.name(), units);
    }

    private static TenantStrategy tenancyOf(GraphitronSchema schema, String parentTypeName,
            String fieldName, GeneratedUnits units) {
        return schema.tenantBindingOf(parentTypeName, fieldName) instanceof TenantBinding.FanOut
            ? new TenantStrategy.Fanned(units.tenantConnections())
            : new TenantStrategy.Single();
    }

    /**
     * A plain batched child's row: the source arm borrows the coordinate's correlation and hop
     * chain whole (the same facts the inline child's projection wrap reads), the delivery arm
     * carries the key and loader facts the entry point's registration reads, and the result
     * shape follows the per-key cardinality fact. The WHERE handshake is the root family's:
     * glue copied off the condition relation by coordinate; the per-hop filters ride the source
     * arm's hops and stay join-path content.
     */
    private static LauncherCommand batchedRow(
            no.sikt.graphitron.rewrite.model.ChildField.BatchedTableField btf,
            GraphitronSchema schema, ConditionRelation conditions, GeneratedUnits units) {
        return batchedRow(btf, whereOf(btf.parentTypeName(), btf.name(), conditions),
            tenancyOf(schema, btf.parentTypeName(), btf.name(), units), units);
    }

    private static LauncherCommand batchedRow(
            no.sikt.graphitron.rewrite.model.ChildField.BatchedTableField btf,
            GlueCall where, TenantStrategy tenancy, GeneratedUnits units) {
        return new LauncherCommand(
            units.rowsMethod(btf.parentTypeName(), btf.name()),
            FieldCoordinates.coordinates(btf.parentTypeName(), btf.name()),
            new LaunchSource.CorrelatedChain(btf.returnType().table(),
                units.typeClass(btf.returnType().returnTypeName()),
                btf.joinPath(), btf.parentCorrelation()),
            where,
            new Invocation.Batched(btf.sourceKey(), btf.loaderRegistration()),
            tenancy,
            batchedResultOf(btf, units));
    }

    /**
     * A batched discriminated-interface child's row: the plain batched child's delivery and
     * topology facts with the root interface arm's source payload dropped in, so the two halves
     * each have one derivation. Always single-tenant at the strategy level (the fan-out ladder
     * rejects {@code @tenantFanOut} on interface-typed fields); the per-tenant partitioning this
     * coordinate does need rides the loader <em>name</em>, which the entry point derives through
     * the tenancy binding, not this row.
     *
     * <p>The result forks on the coordinate's wrapper through {@link #interfaceChildResultOf}:
     * the connection shape when the author wrote {@code @asConnection}, the ordered list shape
     * otherwise. A batched list orders globally and the {@code __idx__} scatter preserves
     * relative order inside each parent's bucket, so the ordering the unbatched twin applies per
     * parent survives the loader boundary unchanged; the connection shape re-keys the same
     * global ordering per parent through its windowed rank.
     */
    private static LauncherCommand batchedInterfaceChildRow(
            no.sikt.graphitron.rewrite.model.ChildField.BatchedTableInterfaceField btif,
            no.sikt.graphitron.rewrite.JoinedTableReprojection reprojection,
            java.util.function.Function<String, List<? extends GraphitronField>> fieldsOfType,
            GlueCall where, GeneratedUnits units) {
        return new LauncherCommand(
            units.rowsMethod(btif.parentTypeName(), btif.name()),
            FieldCoordinates.coordinates(btif.parentTypeName(), btif.name()),
            new LaunchSource.DiscriminatedCorrelatedChain(
                new LaunchSource.DiscriminatedTable(btif.returnType().table(),
                    btif.discriminatorColumn(), btif.knownDiscriminatorValues(),
                    reprojection.baseSlice(),
                    discriminatedBranches(btif.participants(), btif.discriminatorColumn(),
                        reprojection, units),
                    selectionRestriction(btif.participants(), fieldsOfType, units)),
                btif.joinPath(), btif.parentCorrelation()),
            where,
            new Invocation.Batched(btif.sourceKey(), btif.loaderRegistration()),
            new TenantStrategy.Single(),
            interfaceChildResultOf(btif, units));
    }

    /**
     * The batched discriminated child's payload shape, forked on the coordinate's wrapper. The
     * connection arm derives through {@link #connectionShape}, the same home both root arms use.
     * Its facet plan is {@code null} for the reason {@link #interfaceRow}'s is:
     * {@link no.sikt.graphitron.rewrite.GraphitronSchemaBuilder#unsupportedFacetCarrierReason}
     * rejects a carrier whose element is not a {@code @table}-backed object type at the SDL
     * boundary, which an interface element never is.
     */
    private static ResultShape interfaceChildResultOf(
            no.sikt.graphitron.rewrite.model.ChildField.BatchedTableInterfaceField btif,
            GeneratedUnits units) {
        var ordering = orderingOf(btif.orderBy(), btif.parentTypeName(), btif.name(), units);
        if (btif.returnType().wrapper() instanceof FieldWrapper.Connection conn) {
            return connectionShape(conn, ordering,
                btif.parentTypeName() + "." + btif.name(), null, units);
        }
        return new ResultShape.RecordList(ordering);
    }

    /**
     * The {@code @lookupKey} batched child's row: {@code CorrelatedChain}'s facts plus the
     * borrowed key mapping and the input-rows helper ref (minted here through the same formula
     * the helper emission reads, the root lookup's division). The result derives from the
     * per-key cardinality capability like the plain sibling's, but the one-record-per-key cell
     * has no batched-lookup emission (the legacy emitter paired a {@code Record}-valued loader
     * with a list-shaped rows method there, which does not compile), so production fails loud
     * on it rather than asserting a shape the model contradicts; the validator accepts that
     * schema today, a recorded mirror gap.
     */
    private static LauncherCommand batchedLookupRow(
            no.sikt.graphitron.rewrite.model.ChildField.BatchedTableField blf,
            no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping mapping,
            GlueCall where, TenantStrategy tenancy, GeneratedUnits units) {
        if (blf.emitsSingleRecordPerKey()) {
            throw new IllegalStateException(
                "Graphitron generator bug (batched lookup child): coordinate '"
                + blf.qualifiedName() + "' answers one record per key (a single-cardinality"
                + " record-arm lookup, or a loadMany dispatch); no batched-lookup emission"
                + " exists for that cell, and generating the list shape against a"
                + " Record-valued loader does not compile. Failing at production keeps the"
                + " gap loud until a single-shaped lookup emission or a validator rejection"
                + " lands.");
        }
        return new LauncherCommand(
            units.rowsMethod(blf.parentTypeName(), blf.name()),
            FieldCoordinates.coordinates(blf.parentTypeName(), blf.name()),
            new LaunchSource.CorrelatedLookupChain(blf.returnType().table(),
                units.typeClass(blf.returnType().returnTypeName()),
                blf.joinPath(), blf.parentCorrelation(),
                mapping,
                units.inputRowsMethod(units.fetchers(blf.parentTypeName()), blf.name())),
            where,
            new Invocation.Batched(blf.sourceKey(), blf.loaderRegistration()),
            tenancy,
            new ResultShape.RecordList(null));
    }

    /**
     * The {@code @pivot} batched child's row: the source arm carries the attribute table, the
     * coordinate-grain pivot projection unit (minted through the same scheme the emission
     * reads) and the narrowed FK correlation whose slots the renderer's LEFT JOIN reads. No
     * WHERE slot: the leaf carries no filter surface (no condition row exists for the
     * coordinate), and the arm's key-preserving topology admits no per-row filtering anyway.
     * One record per key is the pivot invariant, so the result is the single shape; tenancy is
     * single by classification (backstopped on the command). The correlation narrowing is a
     * checked pattern rather than a cast: the pivot spec pins the path to one unfiltered FK
     * hop, which the classifier always lands on {@code OnFkSlots}, so anything else is a
     * generator bug surfaced here at production.
     */
    private static LauncherCommand batchedPivotRow(
            no.sikt.graphitron.rewrite.model.ChildField.BatchedPivotField bpf, GeneratedUnits units) {
        if (!(bpf.parentCorrelation()
                instanceof no.sikt.graphitron.rewrite.model.ParentCorrelation.OnFkSlots fkSlots)) {
            throw new IllegalStateException(
                "Graphitron generator bug (batched pivot child): coordinate '"
                + bpf.qualifiedName() + "' carries a "
                + bpf.parentCorrelation().getClass().getSimpleName()
                + " correlation; a @pivot path is a single unfiltered FK hop, which the"
                + " classifier always lands on ParentCorrelation.OnFkSlots");
        }
        return new LauncherCommand(
            units.rowsMethod(bpf.parentTypeName(), bpf.name()),
            FieldCoordinates.coordinates(bpf.parentTypeName(), bpf.name()),
            new LaunchSource.PivotAggregate(bpf.pivot().table(),
                units.pivotUnit(bpf.parentTypeName(), bpf.name()), fkSlots),
            null,
            new Invocation.Batched(bpf.sourceKey(), bpf.loaderRegistration()),
            new TenantStrategy.Single(),
            new ResultShape.SingleRecord());
    }

    /**
     * The {@code @service} table child's row: the source arm carries the developer's method
     * ref, the returned table and the projection unit the lift re-projects through; the
     * delivery arm carries the key and loader facts. No WHERE slot (the classifier constructs
     * the leaf with an empty filter surface); the result slot is the service arms' typed
     * vacuity. The key facts are validator-guaranteed present for the table leaf (a
     * table-bound {@code @service} without a Sources parameter is rejected).
     */
    private static LauncherCommand serviceTableRow(
            no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField stf, GeneratedUnits units) {
        return new LauncherCommand(
            units.loadMethod(stf.parentTypeName(), stf.name()),
            FieldCoordinates.coordinates(stf.parentTypeName(), stf.name()),
            new LaunchSource.ServiceTableLift(
                (no.sikt.graphitron.rewrite.model.MethodRef.Service) stf.method(),
                stf.returnType().table(),
                units.typeClass(stf.returnType().returnTypeName())),
            null,
            new Invocation.Batched(stf.sourceKey(), stf.loaderRegistration()),
            new TenantStrategy.Single(),
            new ResultShape.LoaderDelegated());
    }

    /**
     * The {@code @service} record child's row: pure delegation, so the source arm carries only
     * the method ref, whose declared return type IS the rows method's return type (the
     * classifier acceptance enforces the equality). One loud production guard remains, for the
     * validator's last recorded skip hole here: a backing-less result return skips the return-shape
     * equality check entirely (the legacy emission wrapped the whole reflected type once more, which
     * does not compile), so it fails here with the cause until a validator rejection lands. The
     * companion guard on a null key is gone: the leaf's compact constructor pins both key components
     * non-null, and the classifier rejects a child {@code @service} declaring no {@code Sources}
     * parameter before a leaf exists.
     */
    private static LauncherCommand serviceRecordRow(
            no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField srf, GeneratedUnits units) {
        if (no.sikt.graphitron.rewrite.model.RowsMethodShape.strictPerKeyType(srf.returnType()) == null
                && !(srf.returnType() instanceof no.sikt.graphitron.rewrite.model.ReturnTypeRef.ScalarReturnType)) {
            throw new IllegalStateException(
                "Graphitron generator bug (service record child): coordinate '"
                + srf.qualifiedName() + "' returns a shape the classifier's return-type"
                + " equality check skips (a backing-less result type), so the developer"
                + " method's declared return type is unverified against the loader container"
                + " wrap; the legacy emission produced uncompilable output here. Failing at"
                + " production keeps the gap loud until a validator rejection lands.");
        }
        return new LauncherCommand(
            units.loadMethod(srf.parentTypeName(), srf.name()),
            FieldCoordinates.coordinates(srf.parentTypeName(), srf.name()),
            new LaunchSource.ServiceCall(
                (no.sikt.graphitron.rewrite.model.MethodRef.Service) srf.method()),
            null,
            new Invocation.Batched(srf.sourceKey(), srf.loaderRegistration()),
            new TenantStrategy.Single(),
            new ResultShape.LoaderDelegated());
    }

    /**
     * The batched child's payload shape: the connection wrapper carries the ordering (total
     * there, validator-enforced), the wrapper's default page size and the connection runtime's
     * refs, exactly the root connection's derivation minus facets (facet synthesis is a
     * directive-driven root-carrier concern); otherwise the per-key cardinality fact decides
     * between the single and list shapes, both unordered (the batched non-connection emission
     * renders no ordering, a pinned current behaviour).
     */
    private static ResultShape batchedResultOf(
            no.sikt.graphitron.rewrite.model.ChildField.BatchedTableField btf, GeneratedUnits units) {
        if (btf.returnType().wrapper()
                instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn) {
            return new ResultShape.Connection(
                orderingOf(btf.orderBy(), btf.parentTypeName(), btf.name(), units),
                conn.defaultPageSize(), units.connectionHelper(), units.connectionResult(), null);
        }
        return btf.emitsSingleRecordPerKey()
            ? new ResultShape.SingleRecord()
            : new ResultShape.RecordList(null);
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
            return connectionShape(conn, orderingOf(qtf, units), qtf.qualifiedName(), facets, units);
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
     * The connection payload both root arms build: the ordering (total on a connection, so an
     * absent one is a production-time backstop of the validator's pagination-requires-ordering
     * rejection), the wrapper's default page size, and the connection runtime's unit refs copied
     * off the naming vocabulary. One home, so the plain root and the discriminated one cannot
     * derive the same shape differently.
     */
    private static ResultShape.Connection connectionShape(FieldWrapper.Connection conn,
            Ordering ordering, String coordinate, FacetPlan facets, GeneratedUnits units) {
        if (ordering == null) {
            throw new IllegalStateException(
                "connection coordinate '" + coordinate + "' has no resolvable ordering;"
                + " the validator rejects pagination without ordering before production");
        }
        return new ResultShape.Connection(ordering, conn.defaultPageSize(),
            units.connectionHelper(), units.connectionResult(), facets);
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
     * The validator's mirror of the relation's case-folded method-name census: every launcher
     * method the schema's covered coordinates mint, grouped case-folded by
     * {@code (owner, method)}, with the groups that collide across distinct coordinates
     * returned for rejection (the projection producer's address-census division: the relation
     * constructor's hard failure is the backstop, this is what an author sees, located at the
     * colliding declarations). The verdict-to-scheme mapping reads the same launch verdict the
     * production reads, at name grain only; a drift produces a census that misses or
     * over-reports, caught by the constructor backstop either way.
     */
    public static List<MethodCollision> methodCollisions(GraphitronSchema schema) {
        var units = new GeneratedUnits("");
        var origins = new java.util.LinkedHashMap<String,
            java.util.LinkedHashMap<String, graphql.language.SourceLocation>>();
        for (var type : schema.types().values()) {
            for (var field : schema.fieldsOf(type.name())) {
                var ref = mintedMethodOf(schema, field, units);
                if (ref == null) {
                    continue;
                }
                var key = (ref.owner().fqcn() + "#" + ref.methodName())
                    .toLowerCase(java.util.Locale.ROOT);
                origins.computeIfAbsent(key, k -> new java.util.LinkedHashMap<>())
                    .putIfAbsent("field '" + field.qualifiedName() + "'", field.location());
            }
        }
        return origins.entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .map(e -> new MethodCollision(e.getKey(),
                e.getValue().entrySet().stream()
                    .map(o -> new MethodOrigin(o.getKey(), o.getValue()))
                    .toList()))
            .toList();
    }

    /** One case-folded launcher-method collision: the folded {@code owner#method} key and its origins. */
    public record MethodCollision(String foldedKey, List<MethodOrigin> origins) {}

    /** One colliding coordinate: its description and source location, for the located rejection. */
    public record MethodOrigin(String description,
            graphql.language.SourceLocation location) {}

    /**
     * The name the covered family would mint for {@code field}, or {@code null} for a
     * non-member: a total switch over the launch verdict, reading only the naming schemes
     * (never conditions or tenancy, which a name census does not need). The lookup fork inside
     * the root catalog arm is a member read (the lookup member's presence names the
     * {@code lookup<Field>} scheme), so no leaf identity participates in the census either.
     */
    private static no.sikt.graphitron.command.UnitMethodRef mintedMethodOf(
            GraphitronSchema schema, GraphitronField field, GeneratedUnits units) {
        return switch (verdictOf(schema, field)) {
            case NONE -> null;
            case SERVICE -> units.loadMethod(field.parentTypeName(), field.name());
            case SERVICE_REENTRY, DML_REENTRY ->
                units.reentryRowsMethod(field.parentTypeName(), field.name());
            case ROOT_CATALOG ->
                hasKind(schema.operationMembersOf(field.parentTypeName(), field.name()),
                        OperationMember.Kind.LOOKUP)
                    ? units.lookupMethod(field.parentTypeName(), field.name())
                    : units.launcherMethod(field.parentTypeName(), field.name());
            case BATCHED_CHILD_CATALOG -> units.rowsMethod(field.parentTypeName(), field.name());
        };
    }

    /**
     * The coordinate's condition glue reference, copied off the condition relation's row
     * (single-sourcing the env-appending fact on that relation; the launcher never recomputes it
     * from filters, and absence in the relation <em>is</em> the absence), or {@code null} when
     * the coordinate has no row.
     */
    private static GlueCall whereOf(QueryField.QueryTableField qtf, ConditionRelation conditions) {
        return whereOf(qtf.parentTypeName(), qtf.name(), conditions);
    }

    private static GlueCall whereOf(String parentTypeName, String fieldName, ConditionRelation conditions) {
        return conditionRowOf(parentTypeName, fieldName, conditions)
            .map(r -> new GlueCall(r.glue(), r.readsRequestContext()))
            .orElse(null);
    }

    private static java.util.Optional<ConditionCommand> conditionRowOf(
            QueryField.QueryTableField qtf, ConditionRelation conditions) {
        return conditionRowOf(qtf.parentTypeName(), qtf.name(), conditions);
    }

    private static java.util.Optional<ConditionCommand> conditionRowOf(
            String parentTypeName, String fieldName, ConditionRelation conditions) {
        return conditions.soleRowFor(parentTypeName, fieldName);
    }

    /**
     * The ordering, from the model's resolved spec: a nonempty fixed order renders inline
     * ({@link Ordering.Columns}), an argument-driven order dispatches through the emitted helper
     * whose refs are minted here ({@link Ordering.Helper}), and everything else (no spec, or a
     * fixed spec with no columns) is unordered, an absent slot on the {@code RecordList} arm.
     */
    private static Ordering orderingOf(QueryField.QueryTableField qtf, GeneratedUnits units) {
        return orderingOf(qtf.orderBy(), qtf.parentTypeName(), qtf.name(), units);
    }

    private static Ordering orderingOf(OrderBySpec orderBy, String parentTypeName, String fieldName,
            GeneratedUnits units) {
        return switch (orderBy) {
            case OrderBySpec.Fixed fixed when !fixed.columns().isEmpty() -> new Ordering.Columns(fixed);
            case OrderBySpec.Argument ignored -> new Ordering.Helper(
                units.orderByHelperMethod(parentTypeName, fieldName), units.orderByResult());
            default -> null;
        };
    }
}
