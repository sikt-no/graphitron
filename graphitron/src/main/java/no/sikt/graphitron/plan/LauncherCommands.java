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
 * SELECT coordinate. Membership has one home, {@link #rowOf}: a single switch, total over
 * {@link QueryField}'s permits, whose arms either mint the coordinate's row or state why the
 * kind is outside the family (polymorphic, node, service roots, all out by the fact, with no
 * exemption list anywhere). The migration dial that excluded not-yet-landed shapes while the
 * seam was being built emptied with the lookup root's fold and is deleted; that the switch has
 * no default and no dial <em>is</em> the membership enforcer: a new root kind is a compile
 * error here, and flipping an existing kind's verdict is a visible arm edit, never a silent
 * exclusion.
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

    public static LauncherRelation produce(GraphitronSchema schema, ConditionRelation conditions,
            String outputPackage) {
        var units = new GeneratedUnits(outputPackage);
        var rows = new ArrayList<LauncherCommand>();
        for (var type : schema.types().values()) {
            for (var field : schema.fieldsOf(type.name())) {
                if (field instanceof QueryField qf) {
                    var row = rowOf(schema, qf, conditions, units);
                    if (row != null) {
                        rows.add(row);
                    }
                }
                if (field instanceof no.sikt.graphitron.rewrite.model.ChildField cf) {
                    var row = childRowOf(schema, cf, conditions, units);
                    if (row != null) {
                        rows.add(row);
                    }
                }
                if (field instanceof no.sikt.graphitron.rewrite.model.MutationField.DmlTableField dml) {
                    var row = dmlRowOf(schema, dml, units);
                    if (row != null) {
                        rows.add(row);
                    }
                }
            }
        }
        var carrierDsl = schema.tenantScopes() instanceof TenantScopes.Configured
            ? CarrierDsl.ROUTED
            : CarrierDsl.ENV_ACQUIRED;
        return new LauncherRelation(rows, carrierDsl);
    }

    /**
     * The one membership-and-production switch: each permit either mints the coordinate's row
     * or is outside the family by the fact ({@code null}). Total with no default, so a new
     * root kind is a compile-time decision here rather than a runtime throw or a silent
     * exclusion; this totality is the membership enforcer the migration dial's deletion
     * promised.
     */
    private static LauncherCommand rowOf(GraphitronSchema schema, QueryField field,
            ConditionRelation conditions, GeneratedUnits units) {
        return switch (field) {
            case QueryField.QueryTableField qtf -> row(qtf, whereOf(qtf, conditions), units,
                facetPlanOf(schema, qtf, conditions, units),
                tenancyOf(schema, qtf, units));
            case QueryField.QueryRoutineTableField qrtf -> routineRow(qrtf, units);
            case QueryField.QueryTableInterfaceField qtif -> interfaceRow(qtif,
                schema.joinedTableReprojectionOf(qtif.returnType().returnTypeName()),
                whereOf(qtif.parentTypeName(), qtif.name(), conditions), units);
            case QueryField.QueryLookupTableField qlf -> lookupRow(qlf,
                whereOf(qlf.parentTypeName(), qlf.name(), conditions), units);
            // Polymorphic targets (their UNION-ALL stage is a dedicated polymorphic-emit
            // family), node dispatch, and the service-backed roots are outside by the fact.
            case QueryField.QueryInterfaceField ignored -> null;
            case QueryField.QueryUnionField ignored -> null;
            case QueryField.QueryNodeField ignored -> null;
            case QueryField.QueryNodesField ignored -> null;
            case QueryField.QueryServiceTableField ignored -> null;
            case QueryField.QueryServiceRecordField ignored -> null;
            case QueryField.QueryServicePolymorphicField ignored -> null;
            case QueryField.QueryServiceTableInterfaceField ignored -> null;
        };
    }

    /**
     * The child family's membership-and-production switch, the {@link #rowOf} shape over
     * {@link no.sikt.graphitron.rewrite.model.ChildField}'s permits: each leaf either mints the
     * coordinate's row or is outside the family by the fact ({@code null}), total with no
     * default, so a new child leaf is a compile-time decision here rather than a silent
     * non-member.
     */
    private static LauncherCommand childRowOf(GraphitronSchema schema,
            no.sikt.graphitron.rewrite.model.ChildField field,
            ConditionRelation conditions, GeneratedUnits units) {
        return switch (field) {
            // The batched and service families own their coordinates' whole payload production.
            case no.sikt.graphitron.rewrite.model.ChildField.BatchedTableField btf ->
                batchedRow(btf, schema, conditions, units);
            case no.sikt.graphitron.rewrite.model.ChildField.BatchedLookupTableField blf ->
                batchedLookupRow(blf,
                    whereOf(blf.parentTypeName(), blf.name(), conditions),
                    tenancyOf(schema, blf.parentTypeName(), blf.name(), units), units);
            case no.sikt.graphitron.rewrite.model.ChildField.BatchedPivotField bpf ->
                batchedPivotRow(bpf, units);
            case no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField stf ->
                serviceTableRow(stf, units);
            case no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField srf ->
                serviceRecordRow(srf, units);
            // Inline SQL children: their composition rides the parent's query (the projection
            // wrap or a correlated subquery), so no launcher unit exists at the coordinate.
            case no.sikt.graphitron.rewrite.model.ChildField.TableField ignored -> null;
            case no.sikt.graphitron.rewrite.model.ChildField.LookupTableField ignored -> null;
            case no.sikt.graphitron.rewrite.model.ChildField.TableInterfaceField ignored -> null;
            // Inline polymorphic delivery: the UNION-ALL stage is the dedicated
            // polymorphic-emit family, out by the fact like the polymorphic roots.
            case no.sikt.graphitron.rewrite.model.ChildField.InterfaceField ignored -> null;
            case no.sikt.graphitron.rewrite.model.ChildField.UnionField ignored -> null;
            // The batched polymorphic pair: names minted through the same GeneratedUnits scheme
            // with no row behind them, the one decided emitted-and-uncommitted population (the
            // per-participant UNION assembly is the polymorphic-emit family's, not a launcher).
            case no.sikt.graphitron.rewrite.model.ChildField.BatchedInterfaceField ignored -> null;
            case no.sikt.graphitron.rewrite.model.ChildField.BatchedUnionField ignored -> null;
            // Column and scalar reads off the parent's already-fetched row: no query of their own.
            case no.sikt.graphitron.rewrite.model.ChildField.ColumnBackedField ignored -> null;
            case no.sikt.graphitron.rewrite.model.ChildField.ColumnBackedReferenceField ignored -> null;
            case no.sikt.graphitron.rewrite.model.ChildField.ParticipantColumnReferenceField ignored -> null;
            case no.sikt.graphitron.rewrite.model.ChildField.ComputedField ignored -> null;
            case no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdField ignored -> null;
            case no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdFieldFromReturning ignored -> null;
            // Pass-through and record shapes: they read the parent's row (or a service record)
            // through the projection family, never a query of their own.
            case no.sikt.graphitron.rewrite.model.ChildField.NestingField ignored -> null;
            case no.sikt.graphitron.rewrite.model.ChildField.RecordReadField ignored -> null;
            case no.sikt.graphitron.rewrite.model.ChildField.RecordCompositeField ignored -> null;
            // The inline pivot rides its parent's multiset; the slot rides the aggregate's row.
            case no.sikt.graphitron.rewrite.model.ChildField.PivotField ignored -> null;
            case no.sikt.graphitron.rewrite.model.ChildField.PivotSlotField ignored -> null;
            // The error channel is synthesised delivery, not a query coordinate.
            case no.sikt.graphitron.rewrite.model.ChildField.ErrorsField ignored -> null;
        };
    }

    /**
     * The DML family's membership-and-production switch, the {@link #rowOf} shape over
     * {@link no.sikt.graphitron.rewrite.model.DmlReturnExpression}'s arms; this switch is the
     * one home of the kind-to-(source, result) projection. The {@code Projected*} and
     * {@code Discriminated*} arms mint the coordinate's reentry companion row (the write itself
     * stays with the mutation entry point, which is deliberately not thin: it owns the
     * transaction, the dialect guard, the no-match guard and the channel envelope); the
     * {@code Encoded*} arms carry no reentry and get no row.
     */
    private static LauncherCommand dmlRowOf(GraphitronSchema schema,
            no.sikt.graphitron.rewrite.model.MutationField.DmlTableField field, GeneratedUnits units) {
        return switch (field.returnExpression()) {
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.EncodedSingle ignored -> null;
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.EncodedList ignored -> null;
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
            GraphitronSchema schema, String interfaceName, String discriminatorColumn,
            List<String> knownDiscriminatorValues,
            List<no.sikt.graphitron.rewrite.model.ParticipantRef> participants,
            no.sikt.graphitron.rewrite.model.ParentCorrelation.OnLiftedSlots correlation,
            GeneratedUnits units) {
        var reprojection = schema.joinedTableReprojectionOf(interfaceName);
        return new LaunchSource.DiscriminatedReentry(
            new LaunchSource.DiscriminatedTable(correlation.targetTable(),
                discriminatorColumn, knownDiscriminatorValues,
                reprojection.baseSlice(),
                discriminatedBranches(participants, reprojection, units)),
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
     */
    public static LauncherRelation produceWithoutSchema(List<? extends GraphitronField> fields,
            String outputPackage) {
        var units = new GeneratedUnits(outputPackage);
        var rows = new ArrayList<LauncherCommand>();
        for (var field : fields) {
            if (field instanceof QueryField.QueryTableField qtf) {
                rows.add(row(qtf, glueFromFilters(qtf, units), units, null, new TenantStrategy.Single()));
            } else if (field instanceof QueryField.QueryRoutineTableField qrtf) {
                rows.add(routineRow(qrtf, units));
            } else if (field instanceof QueryField.QueryTableInterfaceField qtif) {
                // The residence split is a classified-schema fact; a schema-free assembly's
                // joined participants carry no base slice and no detail fields, the same
                // fallback the retired inline assembly took on a null schema.
                rows.add(interfaceRow(qtif, no.sikt.graphitron.rewrite.JoinedTableReprojection.EMPTY,
                    glueFromInterfaceFilters(qtif, units), units));
            } else if (field instanceof QueryField.QueryLookupTableField qlf) {
                rows.add(lookupRow(qlf,
                    glueFromFilters(qlf.parentTypeName(), qlf.name(), qlf.filters(), units), units));
            } else if (field instanceof no.sikt.graphitron.rewrite.model.ChildField.BatchedTableField btf) {
                rows.add(batchedRow(btf,
                    glueFromFilters(btf.parentTypeName(), btf.name(), btf.filters(), units),
                    new TenantStrategy.Single(), units));
            } else if (field instanceof no.sikt.graphitron.rewrite.model.ChildField.BatchedLookupTableField blf) {
                rows.add(batchedLookupRow(blf,
                    glueFromFilters(blf.parentTypeName(), blf.name(), blf.filters(), units),
                    new TenantStrategy.Single(), units));
            } else if (field instanceof no.sikt.graphitron.rewrite.model.ChildField.BatchedPivotField bpf) {
                rows.add(batchedPivotRow(bpf, units));
            } else if (field instanceof no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField stf) {
                rows.add(serviceTableRow(stf, units));
            } else if (field instanceof no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField srf) {
                rows.add(serviceRecordRow(srf, units));
            }
        }
        return new LauncherRelation(rows, CarrierDsl.ENV_ACQUIRED);
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
    private static LauncherCommand lookupRow(QueryField.QueryLookupTableField qlf, GlueCall where,
            GeneratedUnits units) {
        var owner = units.fetchers(qlf.parentTypeName());
        return new LauncherCommand(
            units.lookupMethod(qlf.parentTypeName(), qlf.name()),
            FieldCoordinates.coordinates(qlf.parentTypeName(), qlf.name()),
            new LaunchSource.KeyedLookup(qlf.returnType().table(),
                units.typeClass(qlf.returnType().returnTypeName()),
                (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) qlf.lookupMapping(),
                units.inputRowsMethod(owner, qlf.name())),
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
            new TenantStrategy.Single(),
            qrtf.returnType().wrapper().isList()
                ? new ResultShape.RecordList(null)
                : new ResultShape.SingleRecord());
    }

    /**
     * A single-table discriminated interface row: the source arm carries the base table, the
     * source-entailed discriminator restriction, the whole-query base slice (copied off the
     * schema's joined-table reprojection fold) and the per-participant branches. Always
     * single-tenant: the fan-out ladder rejects {@code @tenantFanOut} on
     * interface-typed fields. Never {@link ResultShape.Connection}: the classifier defers
     * {@code @asConnection} on this root, and the command backstop mirrors both.
     */
    private static LauncherCommand interfaceRow(QueryField.QueryTableInterfaceField qtif,
            no.sikt.graphitron.rewrite.JoinedTableReprojection reprojection,
            GlueCall where, GeneratedUnits units) {
        return new LauncherCommand(
            units.launcherMethod(qtif.parentTypeName(), qtif.name()),
            FieldCoordinates.coordinates(qtif.parentTypeName(), qtif.name()),
            new LaunchSource.DiscriminatedTable(qtif.returnType().table(),
                qtif.discriminatorColumn(), qtif.knownDiscriminatorValues(),
                reprojection.baseSlice(),
                discriminatedBranches(qtif.participants(), reprojection, units)),
            where,
            new Invocation.Direct(),
            new TenantStrategy.Single(),
            qtif.returnType().wrapper().isList()
                ? new ResultShape.RecordList(orderingOf(qtif.orderBy(), qtif.parentTypeName(),
                    qtif.name(), units))
                : new ResultShape.SingleRecord());
    }

    /**
     * The per-participant branch assembly, shared with the legacy interface-reprojection call
     * sites (child twin, service fetcher, DML follow-ups) so the branch derivation and the
     * projection-ref minting have one home. Total over the table-backed variants; a non-table
     * participant cannot reach here (the parse boundary rejects non-table members of a
     * discriminated interface).
     */
    public static List<LaunchSource.DiscriminatedTable.Branch> discriminatedBranches(
            List<no.sikt.graphitron.rewrite.model.ParticipantRef> participants,
            no.sikt.graphitron.rewrite.JoinedTableReprojection reprojection, GeneratedUnits units) {
        var branches = new ArrayList<LaunchSource.DiscriminatedTable.Branch>(participants.size());
        for (var participant : participants) {
            branches.add(switch (participant) {
                case no.sikt.graphitron.rewrite.model.ParticipantRef.TableBound tb ->
                    new LaunchSource.DiscriminatedTable.Branch.SingleTable(tb,
                        units.typeClass(tb.typeName()));
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
            no.sikt.graphitron.rewrite.model.ChildField.BatchedLookupTableField blf,
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
                (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) blf.lookupMapping(),
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
            new LaunchSource.PivotAggregate(bpf.spec().pivotTable(),
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
     * classifier acceptance enforces the equality). Two loud production guards for the
     * validator's recorded skip holes: a record child without a Sources parameter classifies
     * with null key facts today (nothing rejects it; the legacy emission raised a bare NPE),
     * and a backing-less result return skips the return-shape equality check entirely (the
     * legacy emission wrapped the whole reflected type once more, which does not compile).
     * Both fail here with the cause until a validator rejection lands.
     */
    private static LauncherCommand serviceRecordRow(
            no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField srf, GeneratedUnits units) {
        if (srf.sourceKey() == null || srf.loaderRegistration() == null) {
            throw new IllegalStateException(
                "Graphitron generator bug (service record child): coordinate '"
                + srf.qualifiedName() + "' has no Sources parameter, so no DataLoader key"
                + " exists; the validator accepts this shape today (a recorded mirror gap;"
                + " the table-bound sibling is rejected) and no emission exists for it.");
        }
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
     * The validator's mirror of the relation's case-folded method-name census: every launcher
     * method the schema's covered coordinates mint, grouped case-folded by
     * {@code (owner, method)}, with the groups that collide across distinct coordinates
     * returned for rejection (the projection producer's address-census division: the relation
     * constructor's hard failure is the backstop, this is what an author sees, located at the
     * colliding declarations). The kind-to-scheme mapping restates the membership switches
     * above at name grain only; a drift produces a census that misses or over-reports, caught
     * by the constructor backstop either way.
     */
    public static List<MethodCollision> methodCollisions(GraphitronSchema schema) {
        var units = new GeneratedUnits("");
        var origins = new java.util.LinkedHashMap<String,
            java.util.LinkedHashMap<String, graphql.language.SourceLocation>>();
        for (var type : schema.types().values()) {
            for (var field : schema.fieldsOf(type.name())) {
                var ref = mintedMethodOf(field, units);
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
     * non-member: the census-grain restatement of {@link #rowOf}, {@link #childRowOf} and
     * {@link #dmlRowOf}'s minting arms, reading only the naming schemes (never conditions or
     * tenancy, which a name census does not need).
     */
    private static no.sikt.graphitron.command.UnitMethodRef mintedMethodOf(
            GraphitronField field, GeneratedUnits units) {
        return switch (field) {
            case QueryField.QueryTableField f -> units.launcherMethod(f.parentTypeName(), f.name());
            case QueryField.QueryRoutineTableField f -> units.launcherMethod(f.parentTypeName(), f.name());
            case QueryField.QueryTableInterfaceField f -> units.launcherMethod(f.parentTypeName(), f.name());
            case QueryField.QueryLookupTableField f -> units.lookupMethod(f.parentTypeName(), f.name());
            case no.sikt.graphitron.rewrite.model.ChildField.BatchedTableField f ->
                units.rowsMethod(f.parentTypeName(), f.name());
            case no.sikt.graphitron.rewrite.model.ChildField.BatchedLookupTableField f ->
                units.rowsMethod(f.parentTypeName(), f.name());
            case no.sikt.graphitron.rewrite.model.ChildField.BatchedPivotField f ->
                units.rowsMethod(f.parentTypeName(), f.name());
            case no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField f ->
                units.loadMethod(f.parentTypeName(), f.name());
            case no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField f ->
                units.loadMethod(f.parentTypeName(), f.name());
            case no.sikt.graphitron.rewrite.model.MutationField.DmlTableField f ->
                switch (f.returnExpression()) {
                    case no.sikt.graphitron.rewrite.model.DmlReturnExpression.EncodedSingle ignored -> null;
                    case no.sikt.graphitron.rewrite.model.DmlReturnExpression.EncodedList ignored -> null;
                    default -> units.reentryRowsMethod(f.parentTypeName(), f.name());
                };
            default -> null;
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
        var coordinate = FieldCoordinates.coordinates(parentTypeName, fieldName);
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
