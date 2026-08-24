package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.render.CompositeDecodeHelperRegistry;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.generators.schema.ConstraintViolationsClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.ErrorMappingsClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.OutcomeClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ConnectionHelperClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.OrderByResultClassGenerator;
import no.sikt.graphitron.render.ArgumentValueSource;
import no.sikt.graphitron.render.PreviousNodeRef;
import no.sikt.graphitron.render.ProjectionCall;
import no.sikt.graphitron.render.RoutineCallEmitter;
import no.sikt.graphitron.render.ValuesJoinRowBuilder;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.BatchKeyField;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.KeyLift;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ChildField.PivotSpecField;
import no.sikt.graphitron.rewrite.model.DialectRequirement;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.SqlDialectFamily;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.InputColumnBinding;
import no.sikt.graphitron.rewrite.model.InputColumnBindingGroup;
import no.sikt.graphitron.rewrite.model.FilterBinding;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.ColumnOverlap;
import no.sikt.graphitron.rewrite.model.ColumnOverlap.OverlapColumn;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.KeyColumn;
import no.sikt.graphitron.rewrite.model.SetColumn;
import no.sikt.graphitron.rewrite.model.UpdateRows;
import no.sikt.graphitron.rewrite.model.MethodBackedField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.ParentCorrelation;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.ServiceKeySource;
import no.sikt.graphitron.rewrite.model.ServiceMethodCall;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.ParticipantFilters;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.*;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Generates a {@link TypeSpec} for one {@code <TypeName>Fetchers} class in {@code rewrite.fetchers}.
 *
 * <ul>
 *   <li>{@link ChildField.ColumnBackedField} — a reified {@code public static} source-only read method
 *       (collected from {@link FetcherEmitter#bind}), registered wrapped in
 *       {@code new LightFetcher<>(<Type>Fetchers::column)}. {@code LightFetcher} implements
 *       {@link graphql.schema.LightDataFetcher} so the runtime uses the lighter call path while
 *       the read stays a findable per-field symbol.</li>
 *   <li>{@link QueryField.QueryTableField} — {@code public static} method taking
 *       {@code DataFetchingEnvironment}, returning {@code Result<Record>} or {@code Record},
 *       wired by method reference.</li>
 *   <li>a lookup-keyed {@link QueryField.QueryTableField} additionally splits into a thin
 *       data fetcher (named after the field, e.g. {@code filmById}) delegating to a rows
 *       method (e.g. {@code lookupFilmById}) which performs the actual SQL. The rows method
 *       is callable independently (e.g. by Apollo Federation {@code _entities}
 *       resolution).</li>
 *   <li>All other field types — stub throwing {@link UnsupportedOperationException}.</li>
 * </ul>
 *
 * <p>Emitted Table-bound helpers ({@code <fieldName>OrderBy}) take the aliased {@code Table}
 * as a parameter — see "Helper-locality" in {@code docs/architecture/reference/emitter-conventions.adoc}.
 */
public class TypeFetcherGenerator {

    /**
     * Legacy two-arg overload used by unit-tier tests that build only the model (no assembled
     * schema). The validator pre-step falls back to the legacy Map-based walk when the
     * assembled schema is unavailable; tests that need the typed-record pre-step shape rely on
     * the three-arg overload below.
     */
    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage) {
        return generate(schema, null, outputPackage);
    }

    /**
     * Overload for callers that hold no {@link no.sikt.graphitron.plan.EmitPlan}. The
     * {@code assembled} parameter is the graphql-java {@link graphql.schema.GraphQLSchema} the
     * rewrite is being generated against; the validator pre-step reads it via
     * {@link TypeFetcherEmissionContext#assembledSchema()} to resolve each SDL arg's
     * input-type-ness and switch input-typed args to the typed-record walk target
     * ({@code <InputName>.fromMap(...)}). Produces the launcher relation the root emission
     * dispatches on from the same schema (and defaults the command registry to a per-call
     * throwaway), so test callers exercise the real row-presence routing.
     */
    public static List<TypeSpec> generate(GraphitronSchema schema, graphql.schema.GraphQLSchema assembled, String outputPackage) {
        var typeUnits = no.sikt.graphitron.plan.TypeUnitCommands.produce(schema, outputPackage);
        return generate(schema, assembled, outputPackage,
            no.sikt.graphitron.plan.LauncherCommands.produce(schema,
                no.sikt.graphitron.plan.ConditionCommands.produce(schema, outputPackage), outputPackage),
            typeUnits.fetchers(),
            typeUnits.errorFetchers(),
            // Rowless: the routine-write relation is read from the fact store, and this overload
            // holds no handle to one. A @routine-writing coordinate reaching here therefore fails
            // the dispatch's drift guard by its name rather than emitting something plausible;
            // such a caller wants the store-backed plan instead.
            no.sikt.graphitron.plan.RoutineWriteCommands.produce(null, schema, outputPackage),
            no.sikt.graphitron.command.KeyProjectionRelation.empty());
    }

    /**
     * Canonical entry point. {@code launchers} is the plan's launcher command relation: a
     * coordinate with a row gets the launcher emission (the rendered {@code rows<Field>} unit
     * plus its entry point), one without falls through to its legacy builder, so the
     * covered-family predicate lives in the producer alone; the pipeline surfaces the same
     * relation on the generation result so the bidirectional closure oracle can join it against
     * the emitted units. {@code routineWrites} is the same arrangement for the two
     * {@code @routine}-writing mutation shapes, whose entry points render wholly from their row.
     * {@code keyProjections} is the graph's projected {@code argMapping} bindings, the one relation
     * that comes from the fact store rather than the walk; a routine call whose IN parameter binds one
     * reads its column off a decoded node id instead of off the wire map.
     * The overloads above default the walk-derived relations to ones produced from the same schema and
     * the projections to empty, there being no store behind a schema-only caller.
     */
    public static List<TypeSpec> generate(GraphitronSchema schema, graphql.schema.GraphQLSchema assembled,
            String outputPackage,
            no.sikt.graphitron.plan.LauncherRelation launchers,
            List<no.sikt.graphitron.command.TypeUnitCommand.FetchersUnit> rows,
            List<no.sikt.graphitron.command.TypeUnitCommand.ErrorFetchersUnit> errorRows,
            no.sikt.graphitron.plan.RoutineWriteRelation routineWrites,
            no.sikt.graphitron.command.KeyProjectionRelation keyProjections) {
        // First-occurrence-wins index of the NestingField embedding each nesting-reached type, so a
        // mixed-source type's ResultType TypeSpec can pair each dual-shape coordinate with its
        // nesting-arm column read. Built over the same schema.fields() iteration order
        // FetcherRegistrationsEmitter uses, so the reference site and the method site agree on the
        // representative parent. Deliberately a different order than the reach fold's (see
        // NestingReach's javadoc): this index answers per-coordinate pairing, not membership.
        var nestingByType = new LinkedHashMap<String, ChildField.NestingField>();
        schema.fields().values().forEach(f -> indexNestingByType(f, nestingByType));

        // Membership is the row set (the type-unit relation's two fetchers kinds); this method
        // renders one class per row. The plain rows fork on the row's type classification: the
        // fetcher-hosting variants keep the full dispatch build, and an unclassified name is a
        // nesting/pivot-reached type whose content comes from the reach fold's one representative
        // wiring. The @error rows are their own arm, rendered from the row's own refs below.
        var reach = schema.nestingReach();
        var result = new ArrayList<TypeSpec>(rows.size() + errorRows.size());
        for (var row : rows) {
            var type = schema.type(row.typeName());
            if (type instanceof GraphitronType.TableType || type instanceof GraphitronType.NodeType
                    || type instanceof GraphitronType.RootType || type instanceof GraphitronType.ResultType) {
                result.add(generateForType(schema, row.typeName(), assembled, outputPackage,
                    nestingByType.get(row.typeName()), launchers, routineWrites, keyProjections));
            } else {
                var wiring = reach.wiringFor(row.typeName());
                if (wiring == null) {
                    throw new IllegalStateException(
                        "Graphitron generator bug (fetchers fold): row for type '" + row.typeName()
                        + "' names neither a fetcher-hosting classification nor a nesting-reached"
                        + " type; the producer's membership and this renderer have drifted");
                }
                var nestedFields = wiring.nestedFields().stream()
                    .map(f -> (GraphitronField) f)
                    .sorted(Comparator.comparing(GraphitronField::name))
                    .toList();
                result.add(generateTypeSpec(row.typeName(), wiring.returnType().table(), null, nestedFields,
                    assembled, outputPackage, null, null,
                    no.sikt.graphitron.plan.LauncherCommands.produceWithoutSchema(nestedFields, outputPackage),
                    // The run's own relation rather than one derived from these fields: a
                    // nesting-reached type's children are never mutation roots, so every lookup
                    // against it comes back empty, which is exactly the behaviour this arm wants.
                    routineWrites,
                    keyProjections));
            }
        }
        for (var row : errorRows) {
            if (!(schema.type(row.typeName()) instanceof GraphitronType.ErrorType et)) {
                throw new IllegalStateException(
                    "Graphitron generator bug (fetchers fold): @error row for type '" + row.typeName()
                    + "' does not name an @error classification; the producer's membership and this"
                    + " renderer have drifted");
            }
            result.add(no.sikt.graphitron.rewrite.generators.util.ErrorTypeFetcherClassGenerator
                .generateFor(et, no.sikt.graphitron.javapoet.ClassName.get(
                    row.errorMappings().packageName(), row.errorMappings().simpleName()),
                    schema.errorFieldReads(row.typeName())));
        }
        return result;
    }

    /**
     * Adds one {@code decode<Record>} target per node type this class's projected {@code argMapping}
     * bindings decode against, into the same map the input-bean decoders land in. Two families want
     * the same body and this is where they meet: the map's key is the record class, so a class hosting
     * both an input-bean {@code @nodeId} member and a projected routine parameter over one node type
     * emits one body and both call sites name it through the class's own resolver.
     *
     * <p>The leaf carrier is built here rather than on the command row because it is walk-side
     * vocabulary, which a command may not hold; the projection carries the same facts in pure-data
     * form (its wire type id, its key columns and a {@link TableRef}) and this shell is where they
     * are put back into the shape the legacy body builder takes. The encoder class is the one part
     * the row cannot carry, being generator configuration rather than a captured fact, so it is
     * minted here from the output package this run emits into. {@code nonNull} is irrelevant to the
     * body and passed {@code false}, the flag being the input-bean member's own nullability rather
     * than a fact about the decode.
     */
    private static void collectProjectionDecoders(
            no.sikt.graphitron.command.KeyProjectionRelation keyProjections, String typeName,
            String outputPackage,
            Map<no.sikt.graphitron.javapoet.ClassName, CallSiteExtraction.NodeIdDecodeRecord> out) {
        var encoderClass = no.sikt.graphitron.render.NodeIdEncoderRef.of(outputPackage);
        keyProjections.rows().stream()
            .filter(row -> row.coordinate().getTypeName().equals(typeName))
            .forEach(row -> out.putIfAbsent(row.nodeTable().recordClass(),
                new CallSiteExtraction.NodeIdDecodeRecord(encoderClass,
                    row.typeId(), row.keyColumns(), row.nodeTable(), false)));
    }

    /** First-occurrence-wins index of the {@code NestingField} embedding each nesting-reached type. */
    private static void indexNestingByType(GraphitronField field, Map<String, ChildField.NestingField> out) {
        // A pivot edge reaches its projection type with the same generic-Record shape a nesting
        // edge does; it joins this index through a synthetic wiring carrier (see
        // pivotWiring) so a pivot-first representative feeds the same dual-shape seam.
        if (field instanceof PivotSpecField p) {
            out.putIfAbsent(p.spec().projectionTypeName(), pivotWiring(p));
            return;
        }
        if (!(field instanceof ChildField.NestingField nf)) {
            return;
        }
        out.putIfAbsent(nf.returnType().returnTypeName(), nf);
        nf.nestedFields().forEach(child -> indexNestingByType(child, out));
    }

    /** See {@link no.sikt.graphitron.rewrite.NestingReach#pivotWiring}. */
    private static ChildField.NestingField pivotWiring(ChildField.PivotSpecField field) {
        return no.sikt.graphitron.rewrite.NestingReach.pivotWiring(field);
    }

    private static TypeSpec generateForType(GraphitronSchema schema, String typeName, graphql.schema.GraphQLSchema assembled, String outputPackage,
            ChildField.NestingField dualWiring,
            no.sikt.graphitron.plan.LauncherRelation launchers,
            no.sikt.graphitron.plan.RoutineWriteRelation routineWrites,
            no.sikt.graphitron.command.KeyProjectionRelation keyProjections) {
        var type = schema.type(typeName);
        var fields = schema.fieldsOf(typeName).stream()
            .filter(f -> !(f instanceof GraphitronField.UnclassifiedField))
            .sorted(Comparator.comparing(GraphitronField::name))
            .toList();
        TableRef parentTable = type instanceof GraphitronType.TableBackedType tbt ? tbt.table() : null;
        GraphitronType.ResultType resultType = type instanceof GraphitronType.ResultType rt ? rt : null;
        return generateTypeSpec(typeName, parentTable, resultType, fields, assembled, outputPackage, schema,
            dualWiring, launchers, routineWrites, keyProjections);
    }

    /**
     * The reified source-shape dispatch method for a coordinate whose shape set is the dual
     * {@code {generic Record, class-backed accessor}}, or {@code null} when single-reach (the caller
     * falls back to {@link FetcherEmitter#bind}). Pairs the accessor arm ({@code field}) with the nesting
     * arm's {@code ColumnBackedField} from {@code dualWiring}, via the same {@link FetcherEmitter#bindDualShape}
     * call {@code FetcherRegistrationsEmitter} uses, so the emitted method and the reference agree.
     */
    private static FetcherEmitter.FetcherBinding dualShapeBinding(GraphitronSchema schema, String typeName,
            GraphitronField field, ClassName fetchersClass, GraphitronType.ResultType resultType,
            String outputPackage, ChildField.NestingField dualWiring) {
        if (dualWiring == null || resultType == null || schema == null
                || !no.sikt.graphitron.rewrite.model.ReachableSourceShape.requiresDispatch(
                    schema.reachableSourceShapes(typeName, field.name()))) {
            return null;
        }
        ChildField columnArm = null;
        for (var f : dualWiring.nestedFields()) {
            if ((f instanceof ChildField.ColumnBackedField || f instanceof ChildField.PivotSlotField)
                    && f.name().equals(field.name())) {
                columnArm = f;
                break;
            }
        }
        if (columnArm == null) {
            return null;
        }
        return FetcherEmitter.bindDualShape(field, columnArm, fetchersClass,
            dualWiring.returnType().table(), resultType, outputPackage);
    }

    // Fetcher-specific constants (cross-generator constants come from GeneratorUtils via static import)
    private static final ClassName COMPLETABLE_FUTURE   = ClassName.get("java.util.concurrent", "CompletableFuture");
    private static final ClassName DATA_LOADER          = ClassName.get("org.dataloader", "DataLoader");
    private static final ClassName DATA_LOADER_FACTORY  = ClassName.get("org.dataloader", "DataLoaderFactory");
    private static final ClassName BATCH_LOADER_ENV     = ClassName.get("org.dataloader", "BatchLoaderEnvironment");
    private static final ClassName ARRAY_LIST           = ClassName.get("java.util", "ArrayList");
    private static final ClassName SET                  = ClassName.get("java.util", "Set");
    private static final ClassName MAP                  = ClassName.get("java.util", "Map");
    private static final ClassName DATA_FETCHER_RESULT  = ClassName.get("graphql.execution", "DataFetcherResult");
    /** {@code List<SortField<?>>} — the return type of every {@code *OrderBy} helper method. */
    private static final TypeName SORT_FIELD_LIST       = ParameterizedTypeName.get(LIST,
        ParameterizedTypeName.get(SORT_FIELD, WildcardTypeName.subtypeOf(Object.class)));

    /**
     * Leaves with a real arm in {@link #generateTypeSpec}'s switch (no {@code stub(f)} call).
     * Together with {@link #STUBBED_VARIANTS}{@code .keySet()}, {@link #NOT_DISPATCHED_LEAVES},
     * and the projected set the census test derives from the projection producer's dispatch,
     * this forms an exhaustive, disjoint partition of every sealed
     * leaf of {@link GraphitronField};
     * enforced by {@code GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus}.
     * Moving an entry from {@link #STUBBED_VARIANTS} to this set is the expected review signal
     * when a stub becomes a real implementation.
     */
    public static final Set<Class<? extends GraphitronField>> IMPLEMENTED_LEAVES = Set.of(
        ChildField.ColumnBackedField.class,
        ChildField.ComputedField.class,
        QueryField.QueryNodeField.class,
        QueryField.QueryNodesField.class,
        QueryField.QueryTableField.class,
        QueryField.QueryServiceTableField.class,
        QueryField.QueryServiceRecordField.class,
        QueryField.QueryServicePolymorphicField.class,
        QueryField.QueryServiceTableInterfaceField.class,
        MutationField.MutationServiceTableInterfaceField.class,
        MutationField.DmlTableField.class,
        MutationField.MutationRoutineWriteField.class,
        MutationField.MutationRoutineWriteRecordField.class,
        MutationField.MutationDmlRecordField.class,
        MutationField.MutationBulkDmlRecordField.class,
        MutationField.MutationServiceTableField.class,
        MutationField.MutationServiceRecordField.class,
        MutationField.MutationServicePolymorphicField.class,
        ChildField.ServiceTableField.class,
        ChildField.ServiceRecordField.class,
        ChildField.BatchedTableField.class,
        ChildField.BatchedPivotField.class,
        ChildField.RecordReadField.class,
        ChildField.RecordCompositeField.class,
        ChildField.SingleRecordIdField.class,
        ChildField.SingleRecordIdFieldFromReturning.class,
        QueryField.QueryTableInterfaceField.class,
        ChildField.TableInterfaceField.class,
        ChildField.BatchedTableInterfaceField.class,
        ChildField.ParticipantColumnReferenceField.class,
        QueryField.QueryInterfaceField.class,
        QueryField.QueryUnionField.class,
        ChildField.InterfaceField.class,
        ChildField.UnionField.class,
        ChildField.BatchedInterfaceField.class,
        ChildField.BatchedUnionField.class,
        ChildField.ErrorsField.class);

    /**
     * Leaves that can never reach the fetcher switch at runtime: {@link InputField} leaves are
     * only attached to input-object types (which {@link #generate} doesn't process), and
     * {@link GraphitronField.UnclassifiedField} is filtered out inside {@link #generateForType}
     * before dispatch. The switch still has a "cannot occur" arm for it (so the compiler sees the
     * switch as exhaustive) but the arm throws {@link AssertionError} rather than emitting code.
     */
    public static final Set<Class<? extends GraphitronField>> NOT_DISPATCHED_LEAVES = Set.of(
        GraphitronField.UnclassifiedField.class,
        InputField.ColumnBackedField.class,
        InputField.ColumnBackedReferenceField.class,
        InputField.NestingField.class,
        InputField.UnboundField.class,
        InputField.ConditionOwnedField.class);

    /**
     * Maps each unimplemented field variant class to the {@link Rejection.Deferred} that both the
     * generated stub method ({@link #stub}) and {@code GraphitronSchemaValidator.validateVariantIsImplemented}
     * project. The deferred value carries a {@code summary} and a
     * {@link Rejection.StubKey.VariantClass} naming the same variant class the map keys on; the
     * uniform {@link Rejection.Deferred#message()} renderer produces the user-facing prose for both
     * paths so the validator's deferred-gate output stays in lock-step with the runtime stub message.
     *
     * <p>Consumed by {@code GraphitronSchemaValidator.validateVariantIsImplemented} via
     * {@code STUBBED_VARIANTS.get(field.getClass())} to produce a build-time error rather than a
     * runtime exception when a schema uses a variant that cannot yet be generated.
     *
     * <p>Invariants:
     * <ul>
     *   <li>Every key must be a concrete sealed leaf in the {@link GraphitronField} hierarchy.
     *       Enforced by {@code GeneratorCoverageTest.notImplementedReasonsContainsOnlyConcreteSealedLeaves}.</li>
     *   <li>Together with {@link #IMPLEMENTED_LEAVES}, {@link #NOT_DISPATCHED_LEAVES}, and
     *       the projected set the census test derives from the projection producer's dispatch
     *       ({@code ProjectionCommands.CONTRIBUTION_MINTING_LEAVES} minus the dual-arm kinds),
     *       this forms a disjoint partition of every {@link GraphitronField} leaf.
     *       Enforced by {@code GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus}.</li>
     *   <li>Adding a case arm that calls {@link #stub} must also add the class here.
     *       Enforced at generator-run time via {@link Objects#requireNonNull} in {@link #stub};
     *       fails the first time a schema triggers that variant.</li>
     *   <li>Removing the last {@code stub(f)} call for a class should remove its map entry (and
     *       typically move it to {@link #IMPLEMENTED_LEAVES} instead).
     *       The partition test catches an orphan entry as soon as any other set references it.</li>
     * </ul>
     */
    // Empty: every reachable leaf has a real or projected arm. The rooted-at-parent NodeId
    // reference shape is rejected ahead of generation by
    // GraphitronSchemaValidator.validateColumnBackedReferenceField.
    public static final Map<Class<? extends GraphitronField>, Rejection.Deferred> STUBBED_VARIANTS =
        Map.of();

    /**
     * Overload for tests and callers that don't need to specify a {@link GraphitronType.ResultType}.
     * Delegates to the 6-arg form with {@code resultType = null} and empty package strings.
     */
    static TypeSpec generateTypeSpec(String typeName, TableRef parentTable, List<GraphitronField> fields) {
        return generateTypeSpec(typeName, parentTable, null, fields, null, "");
    }

    /**
     * Backward-compat overload for unit-tier tests that built the model only (no assembled
     * schema). The validator pre-step falls back to its legacy Map-walk shape.
     */
    static TypeSpec generateTypeSpec(String typeName, TableRef parentTable,
            GraphitronType.ResultType resultType, List<GraphitronField> fields,
            String outputPackage) {
        return generateTypeSpec(typeName, parentTable, resultType, fields, null, outputPackage);
    }

    /**
     * Generates the {@code *Fetchers} class TypeSpec for the given GraphQL type.
     *
     * @param typeName    the GraphQL type name (e.g. {@code "Film"})
     * @param parentTable the resolved {@link TableRef} for the type, or {@code null} for root types
     * @param resultType  the resolved {@link GraphitronType.ResultType} for class-backed parents,
     *                    or {@code null} for table-backed and root types
     * @param fields      the classified fields belonging to this type
     */
    static TypeSpec generateTypeSpec(String typeName, TableRef parentTable,
            GraphitronType.ResultType resultType, List<GraphitronField> fields,
            graphql.schema.GraphQLSchema assembled,
            String outputPackage) {
        return generateTypeSpec(typeName, parentTable, resultType, fields, assembled, outputPackage, null,
            null,
            no.sikt.graphitron.plan.LauncherCommands.produceWithoutSchema(fields, outputPackage),
            // Empty, and that is the overload's shape rather than a gap: the routine-write relation
            // is read from the fact store, which a model-only caller has none of. A coordinate that
            // writes through @routine fails the dispatch's drift guard by name here, which is the
            // signal to render it through a store-backed plan instead.
            no.sikt.graphitron.plan.RoutineWriteRelation.unrouted(java.util.List.of()),
            no.sikt.graphitron.command.KeyProjectionRelation.empty());
    }

    /**
     * Canonical form. {@code graphitronSchema} is the classified schema, threaded so the
     * joined-table interface fetcher can read each participant's classified fields; {@code null}
     * for unit-tier model-only and nested-type callers (which never emit a joined-table interface).
     * {@code commands} is the per-run method-command registry; the non-canonical overloads
     * default it to a per-call throwaway. {@code launchers} is the launcher relation the root
     * emission dispatches on and {@code routineWrites} the routine-write relation the two
     * {@code @routine}-writing mutation arms render from; the schema-free overloads derive both
     * from the fields themselves. {@code keyProjections} is the graph's projected {@code argMapping}
     * bindings, which no walk-side caller can derive at all: it comes from the fact store, so the
     * schema-free overloads pass the empty relation.
     */
    static TypeSpec generateTypeSpec(String typeName, TableRef parentTable,
            GraphitronType.ResultType resultType, List<GraphitronField> fields,
            graphql.schema.GraphQLSchema assembled,
            String outputPackage,
            GraphitronSchema graphitronSchema,
            ChildField.NestingField dualWiring,
            no.sikt.graphitron.plan.LauncherRelation launchers,
            no.sikt.graphitron.plan.RoutineWriteRelation routineWrites,
            no.sikt.graphitron.command.KeyProjectionRelation keyProjections) {
        var fetchersRef = new no.sikt.graphitron.plan.GeneratedUnits(outputPackage).fetchers(typeName);
        var className = fetchersRef.simpleName();
        var builder = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC);
        // The class this type's reified fetcher reads are referenced through (e.g.
        // FilmFetchers::title). Only the reified method is collected below; the registration value
        // FetcherEmitter pairs with it is emitted by FetcherRegistrationsEmitter, not here.
        var reifiedFetchersClass = ClassName.get(fetchersRef.packageName(), fetchersRef.simpleName());

        // Per-class scratchpad for deferred helper-method emission. Every emitter that writes a
        // graphitronContext(env) call obtains the CodeBlock through ctx.graphitronContextCall(),
        // which records the dependency; class assembly drains the set below to decide which
        // helper methods to materialise.
        var ctx = new TypeFetcherEmissionContext(assembled, typeName, graphitronSchema);
        ctx.setKeyProjections(keyProjections);

        // When this type is a flipped Outcome payload (it owns a WrapperArm errors field), its
        // children receive a non-null Outcome as env.getSource(). DataLoader-backed data fields
        // (the record-sourced BatchedTableField arms, lookup-keyed or not)
        // arm-switch inside
        // their generated fetcher method: narrow Success, read the key off success.value(), and
        // return completedFuture(null) on the ErrorList arm. The same predicate drives the
        // registration-site routing in FetcherRegistrationsEmitter; FetcherEmitter.hasWrapperArmErrors
        // is the single home so the two sites cannot drift.
        boolean sourceIsOutcome = FetcherEmitter.hasWrapperArmErrors(fields);

        // Build the create* / decode* helper-name resolver from every jOOQ-record @service carrier,
        // every bean (POJO / @record) class, and every @nodeId record-decode target on this class
        // (both coordinates) BEFORE any field body emits, then stash it on ctx. Every naming site
        // (the two call-site emitters, the bean / decode helper emitters, and the helper-emission
        // drain) reads this one resolver, so a call site and its helper agree on the name by
        // construction. The bean-class and decode-record collection is hoisted up front here (out of
        // the drain below) so the resolver can compute a cross-class-collision-free stem for the union
        // of jOOQ records and beans before any name is emitted; the drain reuses the same collected
        // maps. The jOOQ arm keys on binding shape, not record class, so two @service fields taking one
        // record through different input shapes get distinct helpers (and identical shapes collapse).
        var jooqCarriers = collectJooqRecordCarriers(fields);
        var beanHelpers = collectBeanHelpers(fields);
        var scalarDecoders = new java.util.LinkedHashMap<no.sikt.graphitron.javapoet.ClassName,
            CallSiteExtraction.NodeIdDecodeRecord>();
        var listDecoders = new java.util.LinkedHashMap<no.sikt.graphitron.javapoet.ClassName,
            CallSiteExtraction.NodeIdDecodeRecord>();
        InputBeanInstantiationEmitter.collectRecordDecoders(beanHelpers.values(),
            scalarDecoders, listDecoders);
        // A projected argMapping binding decodes a node id into that node type's own record, which is
        // the same decode<Record> body an input-bean member's @nodeId needs; both register here so one
        // class hosts one body under one name however many sites call it. Registered before the
        // resolver is built, so the decode* namespace is sized over the union.
        collectProjectionDecoders(keyProjections, typeName, outputPackage, scalarDecoders);
        // A producer parameter typed as a node type's own record takes the whole decoded tuple, and
        // the body that materialises it is the same decode<Record> an input-bean member's @nodeId
        // needs. Registered alongside those so one class hosts one body under one name, whichever
        // coordinate calls it.
        collectParamRecordDecoders(fields, scalarDecoders, listDecoders);
        var fetchersHelperNames = FetchersHelperNames.of(
            jooqCarriers, beanHelpers.keySet(), scalarDecoders.keySet());
        ctx.setFetchersHelperNames(fetchersHelperNames);

        // One decode-helper registry per <Type>Fetchers class: split rows-method and lookup-rows
        // filter sites that decode a @nodeId argument lift a per-class private static helper through
        // it. collectInto co-locates construct and drain onto this class's builder so a lifted
        // helper can never be silently dropped.
        boolean[] lookupScatterNeeded = new boolean[1];
        CompositeDecodeHelperRegistry.collectInto(builder, outputPackage, registry -> {
        // The same collector the service slot emitter reads, so a @nodeId argument's decode and a
        // filter's decode of the same node type share one lifted body.
        ctx.setNodeIdDecodeHelpers(registry);
        for (var field : fields) {
            switch (field) {
                case ChildField.ColumnBackedField cf -> {
                    if (parentTable == null) {
                        // ColumnBackedField requires a table-backed parent — classifier invariant.
                        // The validator rejects this before generation; treat as a bug if reached.
                        throw new IllegalStateException(
                            "ColumnBackedField '" + cf.qualifiedName()
                            + "' classified on a non-table-backed parent — classifier invariant violated");
                    }
                    // The reified source-only read (single column, or the composite-key NodeId
                    // encode) is collected below via FetcherEmitter.bind (registered wrapped in
                    // LightFetcher); this arm emits no method itself.
                }
                case QueryField.QueryTableField qtf -> {
                    // Row-presence dispatch: the launcher producer's membership is the one
                    // predicate deciding which coordinates launch through the seam; every
                    // QueryTableField coordinate mints a row now (the invocation strategy,
                    // fan-out included, is a field on it), so absence is a drift bug.
                    var launcherRow = launchers.rowFor(qtf.parentTypeName(), qtf.name())
                        .orElseThrow(() -> new IllegalStateException(
                            "Graphitron generator bug (root launcher dispatch): root coordinate '"
                            + qtf.qualifiedName() + "' has no launcher row;"
                            + " the producer's membership and this dispatch have drifted"));
                    builder.addMethod(buildQueryTableFetcher(ctx, qtf, launcherRow, outputPackage));
                    builder.addMethod(no.sikt.graphitron.render.RootLauncherRenderer
                        .render(launcherRow, launchers.carrierDsl(), ctx.argPathHelpers(), ctx.projectedKeyHost()));
                    // The keyed-lookup row additionally owns a VALUES-building input-rows
                    // helper; the row's source arm is the fork, the same shape the batched
                    // rows renderer reads, so no leaf identity and no schema participate.
                    if (launcherRow.source()
                            instanceof no.sikt.graphitron.command.LaunchSource.KeyedLookup keyedLookup) {
                        var lookupTableClass = GeneratorUtils.ResolvedTableNames
                            .of(qtf.returnType().table(), qtf.returnType().returnTypeName(), outputPackage)
                            .jooqTableClass();
                        builder.addMethod(no.sikt.graphitron.render.LookupRows.buildInputRowsMethod(
                            keyedLookup.mapping(), keyedLookup.inputRows().methodName(),
                            lookupTableClass, no.sikt.graphitron.render.LookupRows.ArgSource.ENV,
                            qtf.name()));
                        // A list-returning lookup scatters its flat join into one slot per key.
                        // Gated on the list arm because the single arm has one slot by
                        // construction, and emitted once per class however many lookups it holds.
                        if (launcherRow.result() instanceof no.sikt.graphitron.command.ResultShape.RecordList) {
                            lookupScatterNeeded[0] = true;
                        }
                    }
                }
                case ChildField.ServiceTableField stf -> {
                    // Lift-back projection. The loader value is the projected Record (carrying
                    // the multiset @reference columns), not the developer-returned XRecord; the lift
                    // rows-method calls the service, then re-projects the returned records by identity
                    // through Type.$project(...). The call expression is composed here (argument
                    // extraction rides this context's per-class helper naming) and handed to the
                    // launcher renderer's service lift arm as the shell's fragment.
                    var stfService = (MethodRef.Service) stf.method();
                    CodeBlock stfServiceCall = CodeBlock.of("$L.$L($L)",
                        serviceCallTarget(stfService, ClassName.bestGuess(stf.method().className())),
                        stf.method().methodName(),
                        ArgCallEmitter.buildMethodBackedCallArgs(ctx, stf.method(), null, CodeBlock.of("keys"),
                            outputPackage, stf.qualifiedName()));
                    var liftRow = launchers.rowFor(stf.parentTypeName(), stf.name())
                        .orElseThrow(() -> new IllegalStateException(
                            "Graphitron generator bug (service table child dispatch): coordinate '"
                            + stf.qualifiedName() + "' has no launcher row;"
                            + " the producer's membership and this dispatch have drifted"));
                    builder.addMethod(buildServiceDataFetcher(ctx, stf.name(), stf, stf.returnType(), stf.keySource(), RECORD, outputPackage, stf.errorChannel(), liftRow.unit().methodName()));
                    builder.addMethod(no.sikt.graphitron.render.RootLauncherRenderer
                        .render(liftRow, launchers.carrierDsl(),
                            TenantDslEmitter.resolve(ctx, stf, outputPackage).declaration(),
                            stfServiceCall, ctx.argPathHelpers(), ctx.projectedKeyHost()));
                }
                case ChildField.ServiceRecordField srf -> {
                    var srfService = (MethodRef.Service) srf.method();
                    CodeBlock srfServiceCall = CodeBlock.of("$L.$L($L)",
                        serviceCallTarget(srfService, ClassName.bestGuess(srf.method().className())),
                        srf.method().methodName(),
                        ArgCallEmitter.buildMethodBackedCallArgs(ctx, srf.method(), null, CodeBlock.of("keys"),
                            outputPackage, srf.qualifiedName()));
                    var delegateRow = launchers.rowFor(srf.parentTypeName(), srf.name())
                        .orElseThrow(() -> new IllegalStateException(
                            "Graphitron generator bug (service record child dispatch): coordinate '"
                            + srf.qualifiedName() + "' has no launcher row;"
                            + " the producer's membership and this dispatch have drifted"));
                    builder.addMethod(buildServiceDataFetcher(ctx, srf.name(), srf, srf.returnType(), srf.keySource(), srf.elementType(), outputPackage, srf.errorChannel(), delegateRow.unit().methodName()));
                    builder.addMethod(no.sikt.graphitron.render.RootLauncherRenderer
                        .render(delegateRow, launchers.carrierDsl(),
                            TenantDslEmitter.resolve(ctx, srf, outputPackage).declaration(),
                            srfServiceCall, ctx.argPathHelpers(), ctx.projectedKeyHost()));
                }
                case ChildField.BatchedTableField btf -> {
                    // One fetcher builder for both source shapes: the stored
                    // source-shape fact gates the key lift and the record-arm prelude inside
                    // buildBatchedDataFetcher; the framing is shared.
                    var batchedRow = launchers.rowFor(btf.parentTypeName(), btf.name())
                        .orElseThrow(() -> new IllegalStateException(
                            "Graphitron generator bug (batched child dispatch): coordinate '"
                            + btf.qualifiedName() + "' has no launcher row;"
                            + " the producer's membership and this dispatch have drifted"));
                    builder.addMethod(buildBatchedDataFetcher(ctx, btf, btf.returnType(), btf.sourceKey(), btf.lift(), parentTable, resultType, sourceIsOutcome, outputPackage, batchedRow));
                    var dslDeclaration = batchedRow.tenancy()
                            instanceof no.sikt.graphitron.command.TenantStrategy.Single
                        ? TenantDslEmitter.resolve(ctx, btf, outputPackage).declaration()
                        : no.sikt.graphitron.javapoet.CodeBlock.of("");
                    builder.addMethod(no.sikt.graphitron.render.RootLauncherRenderer
                        .render(batchedRow, launchers.carrierDsl(), dslDeclaration, ctx.argPathHelpers(), ctx.projectedKeyHost()));
                    // The correlated-lookup row additionally owns the VALUES-building
                    // input-rows helper, named by the row's minted ref (one derivation with
                    // the body's call). The env-based variant reads args from
                    // env.getArgument(name): correct for a batched fetcher whose @lookupKey
                    // args live on the field itself (vs. the inline child-lookup path where
                    // args live on a parent's SelectedField). Identical for both source
                    // shapes; the row's source arm is the fork.
                    if (batchedRow.source()
                            instanceof no.sikt.graphitron.command.LaunchSource.CorrelatedLookupChain lookupChain) {
                        var lookupTableClass = GeneratorUtils.ResolvedTableNames
                            .of(btf.returnType().table(), btf.returnType().returnTypeName(), outputPackage)
                            .jooqTableClass();
                        builder.addMethod(no.sikt.graphitron.render.LookupRows.buildInputRowsMethod(
                            lookupChain.mapping(), lookupChain.inputRows().methodName(),
                            lookupTableClass, no.sikt.graphitron.render.LookupRows.ArgSource.ENV,
                            btf.name()));
                    }
                }
                case QueryField.QueryNodeField f              -> builder.addMethod(buildQueryNodeFetcher(ctx, f, outputPackage));
                case QueryField.QueryNodesField f             -> builder.addMethod(buildQueryNodesFetcher(ctx, f, outputPackage));
                case QueryField.QueryServiceTableField f      -> builder.addMethod(buildQueryServiceTableFetcher(ctx, f, outputPackage));
                case QueryField.QueryServiceRecordField f     -> builder.addMethod(buildQueryServiceRecordFetcher(ctx, f, outputPackage));
                case QueryField.QueryServicePolymorphicField f ->
                    MultiTablePolymorphicEmitter
                        .emitServiceMethods(ctx, f.name(), f.serviceMethodCall(), f.participants(),
                            f.returnType().wrapper().isList(), outputPackage)
                        .forEach(builder::addMethod);
                case QueryField.QueryServiceTableInterfaceField f ->
                    MultiTablePolymorphicEmitter
                        .emitServiceTableInterfaceMethods(ctx, f.name(), f.serviceMethodCall(), f.returnType(),
                            f.discriminatorColumn(), f.knownDiscriminatorValues(), f.participants(),
                            f.returnType().wrapper().isList(), outputPackage)
                        .forEach(builder::addMethod);
                case QueryField.QueryTableInterfaceField f    -> {
                    var interfaceRow = launchers.rowFor(f.parentTypeName(), f.name())
                        .orElseThrow(() -> new IllegalStateException(
                            "Graphitron generator bug (root launcher dispatch): interface root coordinate '"
                            + f.qualifiedName() + "' has no launcher row;"
                            + " the producer's membership and this dispatch have drifted"));
                    builder.addMethod(buildQueryTableFetcher(ctx, f, interfaceRow, outputPackage));
                    builder.addMethod(no.sikt.graphitron.render.RootLauncherRenderer
                        .render(interfaceRow, launchers.carrierDsl(), ctx.argPathHelpers(), ctx.projectedKeyHost()));
                }
                case QueryField.QueryInterfaceField f -> {
                    var participantFilters = participantFiltersByTypename(f.participantFilters());
                    if (f.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn) {
                        MultiTablePolymorphicEmitter
                            .emitRootConnectionMethods(ctx, f.parentTypeName(), f.name(), f.participants(), participantFilters,
                                f.nodeIdArgDispatches(), registry, conn.defaultPageSize(), outputPackage)
                            .forEach(builder::addMethod);
                    } else {
                        MultiTablePolymorphicEmitter
                            .emitMethods(ctx, f.parentTypeName(), f.name(), f.participants(), participantFilters,
                                f.nodeIdArgDispatches(), registry, f.returnType().wrapper().isList(), outputPackage)
                            .forEach(builder::addMethod);
                    }
                }
                case QueryField.QueryUnionField f -> {
                    var participantFilters = participantFiltersByTypename(f.participantFilters());
                    if (f.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn) {
                        MultiTablePolymorphicEmitter
                            .emitRootConnectionMethods(ctx, f.parentTypeName(), f.name(), f.participants(), participantFilters,
                                f.nodeIdArgDispatches(), registry, conn.defaultPageSize(), outputPackage)
                            .forEach(builder::addMethod);
                    } else {
                        MultiTablePolymorphicEmitter
                            .emitMethods(ctx, f.parentTypeName(), f.name(), f.participants(), participantFilters,
                                f.nodeIdArgDispatches(), registry, f.returnType().wrapper().isList(), outputPackage)
                            .forEach(builder::addMethod);
                    }
                }
                case MutationField.DmlTableField f -> builder.addMethod(buildDmlTableFetcher(ctx, f, outputPackage,
                    launchers.rowFor(f.parentTypeName(), f.name()).orElse(null), launchers.carrierDsl()));
                // Both @routine-writing shapes render wholly from their command row and the
                // relation's own tenancy axis. What still rides beside them are per-class
                // collectors (the arg-path helpers, the projected-key host, the request-context
                // seam), which are drains rather than decisions: an emitted call and the helper
                // it names have to land on one class together.
                case MutationField.MutationRoutineWriteField f ->
                    builder.addMethod(renderRoutineWrite(ctx, f, routineWrites));
                case MutationField.MutationRoutineWriteRecordField f ->
                    builder.addMethod(renderRoutineWrite(ctx, f, routineWrites));
                case MutationField.MutationServiceTableField f -> builder.addMethod(buildMutationServiceTableFetcher(ctx, f, outputPackage));
                case MutationField.MutationServiceRecordField f -> builder.addMethod(buildMutationServiceRecordFetcher(ctx, f, outputPackage));
                case MutationField.MutationServicePolymorphicField f ->
                    MultiTablePolymorphicEmitter
                        .emitServiceMethods(ctx, f.name(), f.serviceMethodCall(), f.participants(),
                            f.returnType().wrapper().isList(), outputPackage)
                        .forEach(builder::addMethod);
                case MutationField.MutationServiceTableInterfaceField f ->
                    MultiTablePolymorphicEmitter
                        .emitServiceTableInterfaceMethods(ctx, f.name(), f.serviceMethodCall(), f.returnType(),
                            f.discriminatorColumn(), f.knownDiscriminatorValues(), f.participants(),
                            f.returnType().wrapper().isList(), outputPackage)
                        .forEach(builder::addMethod);
                case MutationField.MutationDmlRecordField f    -> builder.addMethod(buildMutationDmlRecordFetcher(ctx, f, outputPackage));
                case MutationField.MutationBulkDmlRecordField f -> builder.addMethod(buildMutationBulkDmlRecordFetcher(ctx, f, outputPackage));
                // ColumnBackedReferenceField: inline projection via the type's $project unit
                // (Direct compaction); the read of that aliased projection is reified by
                // FetcherEmitter.bind and collected below. The validator rejects the
                // NodeIdEncodeKeys (every arity) and condition-join shapes ahead of generation;
                // no per-shape carve-out is needed here.
                case ChildField.ColumnBackedReferenceField ignored -> { }
                // ChildField.TableField (lookup-keyed or not): inline projection via
                // the type's $project unit; the alias-pickup read is reified by
                // FetcherEmitter.bind and collected below.
                case ChildField.TableField ignored              -> { }
                case ChildField.TableInterfaceField f           -> builder.addMethod(buildTableInterfaceFieldFetcher(ctx, f, outputPackage));
                case ChildField.BatchedTableInterfaceField f -> {
                    // The batched twin: the plain batched child's entry point verbatim (the
                    // source shape is Table, so the key lift is the wrap-driven column
                    // projection and the prelude is empty) over the launcher's discriminated
                    // correlated rows method.
                    var interfaceBatchedRow = launchers.rowFor(f.parentTypeName(), f.name())
                        .orElseThrow(() -> new IllegalStateException(
                            "Graphitron generator bug (batched interface child dispatch): coordinate '"
                            + f.qualifiedName() + "' has no launcher row;"
                            + " the producer's membership and this dispatch have drifted"));
                    builder.addMethod(buildBatchedDataFetcher(ctx, f, f.returnType(), f.sourceKey(),
                        f.lift(), parentTable, resultType, sourceIsOutcome, outputPackage,
                        interfaceBatchedRow));
                    builder.addMethod(no.sikt.graphitron.render.RootLauncherRenderer.render(
                        interfaceBatchedRow, launchers.carrierDsl(),
                        TenantDslEmitter.resolve(ctx, f, outputPackage).declaration(),
                        ctx.argPathHelpers(), ctx.projectedKeyHost()));
                }
                // ParticipantColumnReferenceField: the value is materialised in the parent record by
                // the enclosing TableInterfaceField fetcher's discriminator-gated correlated
                // subselect; the read of it back is reified by FetcherEmitter.bind into a named
                // source-only method (wrapped in LightFetcher), collected below. No-op arm here.
                case ChildField.ParticipantColumnReferenceField ignored -> { }
                // SingleRecordIdFieldFromReturning: the PK column read (+ optional NodeId
                // encode) is reified by FetcherEmitter.bind into a named (DataFetchingEnvironment
                // env) method, collected below. No-op arm here.
                case ChildField.SingleRecordIdFieldFromReturning ignored -> { }
                // The @service-carrier ID sibling: the Outcome/source narrowing + node-key
                // read + NodeId encode is likewise reified by FetcherEmitter.bind into a named env
                // method, collected below. No-op arm here.
                case ChildField.SingleRecordIdField ignored -> { }
                // Inline polymorphic children: single cardinality, plus the degenerate
                // all-unbound set at any cardinality (its connection form emits the root
                // fetcher shape with no filters, exactly as before the delivery split).
                case ChildField.InterfaceField f -> {
                    if (f.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn) {
                        MultiTablePolymorphicEmitter
                            .emitRootConnectionMethods(ctx, f.parentTypeName(), f.name(), f.participants(), Map.of(),
                                List.of(), registry, conn.defaultPageSize(), outputPackage)
                            .forEach(builder::addMethod);
                    } else {
                        MultiTablePolymorphicEmitter
                            .emitInlineMethods(ctx, f.name(), f.participants(), f.participantJoinPaths(),
                                f.sourceKey(), f.parentKeyLift(), f.parentKeyOwnerTable(), f.parentResultType(),
                                f.returnType().wrapper().isList(), outputPackage)
                            .forEach(builder::addMethod);
                    }
                }
                case ChildField.UnionField f -> {
                    if (f.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn) {
                        MultiTablePolymorphicEmitter
                            .emitRootConnectionMethods(ctx, f.parentTypeName(), f.name(), f.participants(), Map.of(),
                                List.of(), registry, conn.defaultPageSize(), outputPackage)
                            .forEach(builder::addMethod);
                    } else {
                        MultiTablePolymorphicEmitter
                            .emitInlineMethods(ctx, f.name(), f.participants(), f.participantJoinPaths(),
                                f.sourceKey(), f.parentKeyLift(), f.parentKeyOwnerTable(), f.parentResultType(),
                                f.returnType().wrapper().isList(), outputPackage)
                            .forEach(builder::addMethod);
                    }
                }
                // Batched polymorphic children: the DataLoader delivery. The rows-method name
                // is a GeneratedUnits ref (the scheme with no row behind it, the orderBy-helper
                // precedent for emitted-but-uncommitted methods); the emitter reads the leaf's
                // minted LoaderRegistration for the load dispatch instead of re-deriving it.
                case ChildField.BatchedInterfaceField f -> {
                    var rowsName = new no.sikt.graphitron.plan.GeneratedUnits(outputPackage)
                        .rowsMethod(f.parentTypeName(), f.name()).methodName();
                    if (f.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn) {
                        MultiTablePolymorphicEmitter
                            .emitBatchedConnectionMethods(ctx, f, rowsName, f.participants(), f.participantJoinPaths(),
                                conn.defaultPageSize(), f.parentKeyLift(), f.parentKeyOwnerTable(),
                                f.parentResultType(), outputPackage)
                            .forEach(builder::addMethod);
                    } else {
                        MultiTablePolymorphicEmitter
                            .emitBatchedListMethods(ctx, f, rowsName, f.participants(), f.participantJoinPaths(),
                                f.parentKeyLift(), f.parentKeyOwnerTable(), f.parentResultType(), outputPackage)
                            .forEach(builder::addMethod);
                    }
                }
                case ChildField.BatchedUnionField f -> {
                    var rowsName = new no.sikt.graphitron.plan.GeneratedUnits(outputPackage)
                        .rowsMethod(f.parentTypeName(), f.name()).methodName();
                    if (f.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn) {
                        MultiTablePolymorphicEmitter
                            .emitBatchedConnectionMethods(ctx, f, rowsName, f.participants(), f.participantJoinPaths(),
                                conn.defaultPageSize(), f.parentKeyLift(), f.parentKeyOwnerTable(),
                                f.parentResultType(), outputPackage)
                            .forEach(builder::addMethod);
                    } else {
                        MultiTablePolymorphicEmitter
                            .emitBatchedListMethods(ctx, f, rowsName, f.participants(), f.participantJoinPaths(),
                                f.parentKeyLift(), f.parentKeyOwnerTable(), f.parentResultType(), outputPackage)
                            .forEach(builder::addMethod);
                    }
                }
                case ChildField.NestingField ignored            -> { /* source passthrough reified by FetcherEmitter.bind, collected below */ }
                // Inline @pivot: projection via the coordinate's pivot $project unit (the multiset
                // arm); the multiset unwrap read is reified by FetcherEmitter.bind, collected below.
                case ChildField.PivotField ignored              -> { }
                // A projection slot's by-name read is reified by FetcherEmitter.bind on the
                // projection type's own Fetchers class (collectNestedFetcherClasses); no-op here.
                case ChildField.PivotSlotField ignored          -> { }
                case ChildField.BatchedPivotField f -> {
                    var pivotRow = launchers.rowFor(f.parentTypeName(), f.name())
                        .orElseThrow(() -> new IllegalStateException(
                            "Graphitron generator bug (batched pivot child dispatch): coordinate '"
                            + f.qualifiedName() + "' has no launcher row;"
                            + " the producer's membership and this dispatch have drifted"));
                    builder.addMethod(buildPivotBatchedDataFetcher(ctx, f, parentTable, outputPackage, pivotRow.unit().methodName()));
                    builder.addMethod(no.sikt.graphitron.render.RootLauncherRenderer
                        .render(pivotRow, launchers.carrierDsl(),
                            TenantDslEmitter.resolve(ctx, f, outputPackage).declaration(),
                            ctx.argPathHelpers(), ctx.projectedKeyHost()));
                }
                case ChildField.RecordReadField ignored         -> { /* locator read reified by FetcherEmitter.bind, collected below */ }
                // The @service record-composite carrier's data field: the Outcome/source
                // narrowing + verbatim projection of the producer's composite record(s) is reified by
                // FetcherEmitter.bind into a named (DataFetchingEnvironment env) method, collected below.
                case ChildField.RecordCompositeField ignored    -> { /* source passthrough reified by FetcherEmitter.bind, collected below */ }
                case ChildField.ComputedField ignored           -> { /* alias-pickup read reified by FetcherEmitter.bind; projected via the type's $project unit */ }
                case ChildField.ErrorsField ignored             -> { /* LocalContext / WrapperArm reified by FetcherEmitter.bind; PayloadAccessor still PropertyDataFetcher.fetching */ }
                // Cannot occur — filtered by generateForType before dispatch
                case InputField ignored ->
                    throw new AssertionError("InputField in type dispatch: " + ignored.qualifiedName());
                case GraphitronField.UnclassifiedField ignored ->
                    throw new AssertionError("UnclassifiedField in type dispatch: " + ignored.qualifiedName());
            }
            // Reify the inline / light reads onto this class. A dual-shape coordinate (reached both as a
            // nesting projection and a class-backed accessor) reifies the source-shape dispatch method,
            // paired 1:1 with the reference FetcherRegistrationsEmitter emits from the same bindDualShape
            // call; every other coordinate reifies through bind(), which returns Reified for exactly the
            // variants the switch above handles with a no-method arm (column reads, source passthroughs,
            // the errors transports, the single-record carriers) and Inline for the method-backed ones,
            // so there is no double-emission.
            var binding = dualShapeBinding(graphitronSchema, typeName, field, reifiedFetchersClass,
                resultType, outputPackage, dualWiring);
            if (binding == null) {
                binding = FetcherEmitter.bind(field, reifiedFetchersClass, parentTable, resultType,
                    outputPackage, sourceIsOutcome);
            }
            if (binding instanceof FetcherEmitter.FetcherBinding.Reified reified) {
                builder.addMethod(reified.method());
            }
        }
        });

        // Companion methods declared by field-body emitters (the DML reentry rows methods)
        // drain onto the class before the helper drain below.
        ctx.drainCompanionMethods().forEach(builder::addMethod);

        // Nested-argument descents any @routine dot-path binding on this class registered. Drained
        // after the companion methods, which may themselves register one.
        ctx.argPathHelpers().emit().forEach(builder::addMethod);

        if (ctx.isRequested(TypeFetcherEmissionContext.HelperKind.GRAPHITRON_CONTEXT)) {
            builder.addMethod(buildGraphitronContextHelper(outputPackage));
        }

        // Emit per-bean instantiation helpers (createBean / createBeans) for any InputBean
        // extraction. The bean-class dedup map was collected up front (collectBeanHelpers) so the
        // resolver could size the create* stem namespace; the emission here reuses it. A single bean
        // class always emits exactly one pair of helpers per *Fetchers class, named through the same
        // resolver every call site consulted.
        for (var ib : beanHelpers.values()) {
            builder.addMethod(InputBeanInstantiationEmitter.buildSingularHelper(ib, fetchersHelperNames));
            builder.addMethod(InputBeanInstantiationEmitter.buildPluralHelper(ib,
                no.sikt.graphitron.javapoet.ClassName.bestGuess(outputPackage + "." + className),
                fetchersHelperNames));
        }
        // One create<Record> / create<Record>List pair per distinct binding shape, named through
        // the same resolver every call site consulted (built up front, stashed on ctx). Contended
        // record classes (>1 shape) split into ordinal-suffixed helpers; the common single-shape case
        // stays the bare create<Record> pair.
        var jooqRecordHelperNames = fetchersHelperNames.jooqRecord();
        for (var jr : jooqRecordHelperNames.distinctShapes()) {
            builder.addMethod(JooqRecordInstantiationEmitter.buildSingularHelper(jr, jooqRecordHelperNames));
            builder.addMethod(JooqRecordInstantiationEmitter.buildPluralHelper(jr, jooqRecordHelperNames));
        }

        // Emit one decode<RecordType>Record helper per jOOQ-record-typed @nodeId input-bean
        // member reached by the collected beans, plus a decode<RecordType>RecordList variant for
        // list-valued members (which delegates to the scalar helper per element). The create<Bean>
        // helper bodies call these by name. Scalar and list decode maps were also collected up front
        // (so the resolver could size the decode* namespace); reuse them here. Scalar and list
        // variants dedup independently by record type; the scalar helper is always emitted because
        // the list variant delegates to it.
        for (var rec : scalarDecoders.values()) {
            builder.addMethod(InputBeanInstantiationEmitter.buildRecordDecodeHelper(rec, fetchersHelperNames));
        }
        for (var rec : listDecoders.values()) {
            builder.addMethod(InputBeanInstantiationEmitter.buildRecordDecodeHelperList(rec, fetchersHelperNames));
        }

        // Emit orderBy helper methods for fields with a dynamic @orderBy argument. Covers
        // QueryTableField (root connection + list fetchers) and BatchedTableField+Connection
        // (per-parent paginated rows method; Table-sourced only, by ctor invariant). The method
        // name derives through the naming vocabulary, the same formula the launcher producer
        // mints onto Ordering.Helper refs, so the helper and its command-side callers cannot
        // disagree (the unmigrated fetcher bodies still spell the call inline through
        // buildOrderByCode until their own migration).
        var namingVocabulary = new no.sikt.graphitron.plan.GeneratedUnits(outputPackage);
        for (var field : fields) {
            if (field instanceof QueryField.QueryTableField qtf
                    && qtf.orderBy() instanceof OrderBySpec.Argument arg) {
                var tableRef = qtf.returnType().table();
                var names = GeneratorUtils.ResolvedTableNames.of(tableRef, qtf.returnType().returnTypeName(), outputPackage);
                builder.addMethod(buildOrderByHelperMethod(
                    namingVocabulary.orderByHelperMethod(typeName, qtf.name()).methodName(),
                    arg, names, tableRef, outputPackage));
            } else if (field instanceof QueryField.QueryTableInterfaceField qtif
                    && qtif.orderBy() instanceof OrderBySpec.Argument arg) {
                // The interface root's launcher references the same minted helper ref as the
                // table root's; before the launcher migration the legacy body spelled the call
                // inline while nothing emitted the helper, so an @orderBy argument on this
                // coordinate produced uncompilable output. Emitting it here closes that gap.
                var tableRef = qtif.returnType().table();
                var names = GeneratorUtils.ResolvedTableNames.of(tableRef, qtif.returnType().returnTypeName(), outputPackage);
                builder.addMethod(buildOrderByHelperMethod(
                    namingVocabulary.orderByHelperMethod(typeName, qtif.name()).methodName(),
                    arg, names, tableRef, outputPackage));
            } else if (field instanceof ChildField.BatchedTableInterfaceField btif
                    && btif.orderBy() instanceof OrderBySpec.Argument arg) {
                // The batched discriminated child orders the whole batch through the launcher's
                // Ordering.Helper arm, which calls the same minted ref; emit it here, exactly as
                // the interface root's arm above does for its own coordinate.
                var tableRef = btif.returnType().table();
                var names = GeneratorUtils.ResolvedTableNames.of(tableRef, btif.returnType().returnTypeName(), outputPackage);
                builder.addMethod(buildOrderByHelperMethod(
                    namingVocabulary.orderByHelperMethod(typeName, btif.name()).methodName(),
                    arg, names, tableRef, outputPackage));
            } else if (field instanceof ChildField.BatchedTableField btf
                    && btf.returnType().wrapper() instanceof FieldWrapper.Connection
                    && btf.orderBy() instanceof OrderBySpec.Argument arg) {
                var tableRef = btf.returnType().table();
                var names = GeneratorUtils.ResolvedTableNames.of(tableRef, btf.returnType().returnTypeName(), outputPackage);
                builder.addMethod(buildOrderByHelperMethod(
                    namingVocabulary.orderByHelperMethod(typeName, btf.name()).methodName(),
                    arg, names, tableRef, outputPackage));
            }
        }

        if (lookupScatterNeeded[0]) {
            builder.addMethod(SplitRowsMethodEmitter.buildScatterLookupByIdxHelper());
        }

        // Emit list-shape scatterByIdx helper whenever any plain-list-cardinality Split* or
        // record-backed batched field is present. Single-cardinality fields use
        // scatterSingleByIdx; Connection-cardinality fields use scatterConnectionByIdx.
        boolean hasListSplitField = fields.stream().anyMatch(f ->
            f instanceof ChildField.BatchedTableInterfaceField btif
                && btif.returnType().wrapper() instanceof FieldWrapper.List
            || f instanceof ChildField.BatchedTableField btf
                && (btf.returnType().wrapper() instanceof FieldWrapper.List
                    || (btf.lookup().isKeyed()
                        && btf.sourceShape() == no.sikt.graphitron.rewrite.model.SourceShape.Record)));
        if (hasListSplitField) {
            builder.addMethod(SplitRowsMethodEmitter.buildScatterByIdxHelper());
        }

        // Single-cardinality sibling: scatterSingleByIdx returns List<Record> (one slot per key,
        // null where no match) rather than List<List<Record>>. Gated on the BatchKeyField
        // capability emitsSingleRecordPerKey, which folds two structurally unrelated triggers
        // (single-cardinality batched fields, the loadMany accessor-arity dispatch) onto
        // one uniform answer; any variant whose rows method emits one record per key reaches
        // this gate by implementing the capability, with no extra disjunct here.
        boolean hasSingleRecordPerKeyField = fields.stream()
            .anyMatch(f -> f instanceof BatchKeyField bkf && bkf.emitsSingleRecordPerKey());
        if (hasSingleRecordPerKeyField) {
            builder.addMethod(SplitRowsMethodEmitter.buildScatterSingleByIdxHelper());
        }

        // Connection-cardinality sibling: scatterConnectionByIdx returns List<ConnectionResult>.
        // Each per-parent bucket wraps the over-fetch slice with the shared PageRequest from the
        // windowed rows-method invocation.
        boolean hasConnectionSplitField = fields.stream().anyMatch(f ->
            f instanceof ChildField.BatchedTableField btf
                && btf.returnType().wrapper() instanceof FieldWrapper.Connection
            || f instanceof ChildField.BatchedTableInterfaceField btif
                && btif.returnType().wrapper() instanceof FieldWrapper.Connection);
        if (hasConnectionSplitField) {
            builder.addMethod(SplitRowsMethodEmitter.buildScatterConnectionByIdxHelper(outputPackage,
                TenantDslEmitter.isMultiTenant(ctx)));
        }

        // emptyScatter is needed whenever @lookupKey input can be empty at request time, which
        // is exactly the keyed-lookup batched reads; plain batched fields never use the
        // empty-input short-circuit.
        boolean hasSplitLookupField = fields.stream().anyMatch(f ->
            f instanceof ChildField.BatchedTableField btf
                && btf.lookup().isKeyed());
        if (hasSplitLookupField) {
            builder.addMethod(SplitRowsMethodEmitter.buildEmptyScatterHelper());
        }

        // parentKeyCellValue is the RowN-key scalar extraction used by the parent-input VALUES
        // cells: RowN keys expose their cells only as Fields, so the bind Param's value is
        // recovered through this per-class helper. Emitted iff any field on this class emits a
        // Row-keyed parent-input rows method; RecordN-keyed fields read k.valueN() directly.
        boolean hasRowKeyedParentInput = fields.stream()
            .anyMatch(TypeFetcherGenerator::emitsRowKeyedParentInputRowsMethod);
        if (hasRowKeyedParentInput) {
            builder.addMethod(SplitRowsMethodEmitter.buildParentKeyCellValueHelper());
        }

        var typeSpec = builder.build();
        assertNoDuplicateHelperSignatures(className, typeSpec);
        return typeSpec;
    }

    /**
     * Generation-time backstop: assert that no two methods on the assembled {@code <Type>Fetchers}
     * class share a Java signature (name plus parameter-type list). With the {@link FetchersHelperNames}
     * union-namespace resolver in place this never fires on valid input; it is the invariant's enforcer
     * against a future naming site that bypasses the resolver, turning silently-uncompilable emitted
     * output into a loud generator failure. Mirrors the routing-hole throw in
     * {@link JooqRecordHelperNames} and {@link FetchersHelperNames}, and correctly stays at generation
     * time (it guards a generator naming decision, not a classifier fact).
     */
    private static void assertNoDuplicateHelperSignatures(String className, TypeSpec typeSpec) {
        var seen = new java.util.HashSet<String>();
        for (var method : typeSpec.methodSpecs()) {
            var signature = new StringBuilder(method.name()).append('(');
            for (var param : method.parameters()) {
                signature.append(param.type()).append(',');
            }
            signature.append(')');
            if (!seen.add(signature.toString())) {
                throw new IllegalStateException(
                    "Two helper methods on generated class '" + className + "' share the signature "
                    + signature + "; the emitted output would not compile. A create*/decode* naming "
                    + "site bypassed the FetchersHelperNames resolver, or a stem collision escaped it.");
            }
        }
    }

    /**
     * Whether this field's emit includes a rows method that materialises its DataLoader keys
     * into a parent-input {@code VALUES} table from {@code RowN}-shaped keys — the gate for the
     * per-class {@code parentKeyCellValue} helper (see
     * {@link SplitRowsMethodEmitter#buildParentKeyCellValueHelper()}). Mirrors the emission
     * routing above: the four split/record prelude variants always emit the parent-input rows
     * method; the polymorphic child fields only on the batched (list / connection) arms with at
     * least one table-bound participant.
     */
    private static boolean emitsRowKeyedParentInputRowsMethod(GraphitronField field) {
        return switch (field) {
            case ChildField.BatchedTableField f -> f.sourceKey().wrap() instanceof SourceKey.Wrap.Row;
            case ChildField.BatchedPivotField f -> f.sourceKey().wrap() instanceof SourceKey.Wrap.Row;
            case ChildField.BatchedTableInterfaceField f -> f.sourceKey().wrap() instanceof SourceKey.Wrap.Row;
            // Batched delivery and a table-bound participant are both leaf identity after the
            // polymorphic delivery split, so only the key wrap is left to read.
            case ChildField.BatchedInterfaceField f -> f.sourceKey().wrap() instanceof SourceKey.Wrap.Row;
            case ChildField.BatchedUnionField f -> f.sourceKey().wrap() instanceof SourceKey.Wrap.Row;
            default -> false;
        };
    }

    /**
     * Generates the thin fetcher entry point for a root-query table field whose composition
     * lives in a launcher command: connection acquisition (the tenancy-forked {@code dsl}
     * declaration), one call to the rendered {@code rows<Field>} launcher unit, and the
     * error-channel framing. The launcher's name is read off the row's minted ref, never
     * recomputed here; the call is class-qualified deliberately, since it is the one edge the
     * emitted-method closure walk cannot see unqualified (both methods share this class) and
     * the entry point is not itself a command row yet.
     *
     * <p>Generated code (list variant):
     * <pre>{@code
     * public static DataFetcherResult<Result<Record>> films(DataFetchingEnvironment env) {
     *     try {
     *         DSLContext dsl = graphitronContext(env).getDslContext(env);
     *         Result<Record> payload = QueryFetchers.rowsFilms(dsl, env);
     *         return DataFetcherResult.<Result<Record>>newResult().data(payload).build();
     *     } catch (Exception e) {
     *         return ErrorRouter.surfaceClientErrorOrRedact(e, env);
     *     }
     * }
     * }</pre>
     */
    private static MethodSpec buildQueryTableFetcher(TypeFetcherEmissionContext ctx,
            no.sikt.graphitron.rewrite.model.OutputField field,
            no.sikt.graphitron.command.LauncherCommand row, String outputPackage) {
        var valueType = no.sikt.graphitron.render.RootLauncherRenderer.valueTypeOf(row);
        var launcherClass = ClassName.get(row.unit().owner().packageName(), row.unit().owner().simpleName());

        var builder = MethodSpec.methodBuilder(field.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");

        builder.beginControlFlow("try");
        // The one tenancy fork in the entry point, reading the row's strategy arm: the fanned
        // strategy owns its plural acquisition (no dsl declaration, no localContext tail; the
        // scatter carrier hands each element its tenant) and collapses the outcome list, where
        // the single-tenant strategy acquires one DSLContext and wraps the payload.
        // TenantDslEmitter's FanOut invariant throw is this fork's build-time enforcer: routing
        // a fanned coordinate through the single-tenant arm fails generation loudly.
        if (row.tenancy() instanceof no.sikt.graphitron.command.TenantStrategy.Fanned fanned) {
            var tenantConnections = ClassName.get(fanned.carrier().packageName(), fanned.carrier().simpleName());
            builder.addStatement("return $T.collapseFanOut(env, $T.$L(env))",
                tenantConnections, launcherClass, row.unit().methodName());
        } else {
            var tenantDsl = TenantDslEmitter.resolve(ctx, field, outputPackage);
            builder.addCode(tenantDsl.declaration());
            builder.addStatement("$T payload = $T.$L(dsl, env)", valueType, launcherClass, row.unit().methodName());
            builder.addCode(returnSyncSuccess(valueType, "payload", tenantDsl.localContextTail()));
        }
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(noChannelCatchArm(outputPackage));
        builder.endControlFlow();

        return builder.build();
    }


    /**
     * Generates the fetcher for a {@link ChildField.TableInterfaceField}.
     *
     * <p>Executes a per-parent SQL query: conditions on the single-hop FK join path extracted
     * from {@code env.getSource()}, then projects all columns via {@code table.asterisk()} plus
     * the discriminator column so the {@code TypeResolver} can route the result to the correct
     * concrete type. The classifier guarantees a single-hop FK-derived {@link JoinStep.Hop};
     * multi-hop and condition-join paths are rejected at classification time.
     *
     * <p>Generated code (single-value variant, one-hop FK where child holds the FK):
     * <pre>{@code
     * public static Record filmContent(DataFetchingEnvironment env) {
     *     Record parentRecord = (Record) env.getSource();
     *     ContentTable table = Tables.CONTENT;
     *     DSLContext dsl = graphitronContext(env).getDslContext(env);
     *     Condition condition = DSL.field(DSL.name("FILM_ID")).eq(parentRecord.get(DSL.name("FILM_ID")));
     *     return dsl
     *         .select(table.asterisk(), DSL.field(table.getQualifiedName().append(DSL.name("CONTENT_TYPE")), Object.class))
     *         .from(table)
     *         .where(condition)
     *         .fetchOne();
     * }
     * }</pre>
     */
    private static MethodSpec buildTableInterfaceFieldFetcher(
            TypeFetcherEmissionContext ctx, ChildField.TableInterfaceField tif, String outputPackage) {
        var tableRef = tif.returnType().table();
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, tif.returnType().returnTypeName(), outputPackage);
        boolean isList = tif.returnType().wrapper().isList();
        var valueType = isList ? (TypeName) ParameterizedTypeName.get(RESULT, RECORD) : RECORD;

        var builder = MethodSpec.methodBuilder(tif.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");

        builder.beginControlFlow("try");
        builder.addStatement("$T parentRecord = ($T) env.getSource()", RECORD, RECORD);
        builder.addCode(GeneratorUtils.declareTableLocal(names, tableRef));
        String tableLocal = names.tableLocalName();

        builder.addCode(TenantDslEmitter.resolve(ctx, tif, outputPackage).declaration());

        // Build join-path condition. Only the single-hop FK-derived shape is supported;
        // multi-hop and condition-join paths are caught at classification time.
        builder.addCode(buildJoinPathCondition(tif.joinPath(), tableRef.tableName()));
        // Shared discriminator-filter + projection + join assembly (see the launcher's
        // discriminated arm).
        builder.addCode(buildTableInterfaceReprojection(ctx, tif.returnType().returnTypeName(), tableRef,
            tif.participants(), tif.discriminatorColumn(),
            tif.knownDiscriminatorValues(), List.of(), tableLocal, outputPackage));

        if (isList) {
            builder.addCode(buildOrderByCode(tif.orderBy(), tif.name(), tableLocal));
            builder.addStatement("$T payload = step.where(condition).orderBy(orderBy).fetch()", valueType);
        } else {
            builder.addStatement("$T payload = step.where(condition).fetchOne()", valueType);
        }
        builder.addCode(returnSyncSuccess(valueType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(noChannelCatchArm(outputPackage));
        builder.endControlFlow();

        return builder.build();
    }

    /**
     * Builds the {@code Condition} declaration from a single-hop FK-derived {@link JoinStep.Hop} path
     * for a {@link ChildField.TableInterfaceField} fetcher.
     *
     * <p>The hop's source side is the parent table, target side the child table — by synthesis-time
     * orientation. The FK-direction question (which end of the catalog FK lives on which side)
     * was answered once in {@code BuildContext.synthesizeFkJoin} and baked into the slot pair, so
     * the emitter reads {@code child.<targetSide> = parentRecord.<sourceSide>} uniformly without
     * re-deriving direction.
     *
     * <p>Precondition: the classifier guarantees exactly one FK-derived {@link JoinStep.Hop}
     * (multi-hop and condition-join paths are rejected at classification time).
     */
    private static CodeBlock buildJoinPathCondition(List<JoinStep> joinPath, String childTableName) {
        var fkJoin = (On.ColumnPairs) ((JoinStep.Hop) joinPath.get(0)).on();
        var slot = fkJoin.slots().get(0);
        String childCol        = slot.targetSide().sqlName();
        String parentRecordCol = slot.sourceSide().sqlName();

        return CodeBlock.builder()
            .addStatement("$T condition = $T.field($T.name($S)).eq(parentRecord.get($T.name($S)))",
                CONDITION, DSL, DSL, childCol, DSL, parentRecordCol)
            .build();
    }

    /**
     * The shared read/projection body of the two discriminated-interface consumers that have not
     * migrated onto the launcher seam ({@link #buildTableInterfaceFieldFetcher} and the service
     * single-table-interface fetcher in {@link MultiTablePolymorphicEmitter}; the DML
     * discriminated follow-ups reach the same assembly through the launcher's reentry arm): a
     * thin delegate that derives the
     * {@link no.sikt.graphitron.command.LaunchSource.DiscriminatedTable} arm's data (the
     * residence split off the schema's joined-table reprojection fold, the branches through the
     * launcher producer's one assembly) and renders it through the relocated fragment
     * ({@link no.sikt.graphitron.render.DiscriminatedTableFragments}), so the launcher and the
     * legacy call sites cannot drift on the assembly.
     *
     * <p>Precondition: the caller has declared {@code condition} ({@code Condition}) and
     * {@code dsl} ({@code DSLContext}) locals and a {@code tableLocal} holding the shared
     * {@code @table}'s jOOQ instance; the caller finishes the chain
     * ({@code step.where(condition)…fetch()}).
     */
    static CodeBlock buildTableInterfaceReprojection(
            TypeFetcherEmissionContext ctx, String interfaceTypeName, TableRef tableRef,
            List<ParticipantRef> participants, ColumnRef discriminatorColumn,
            List<String> knownDiscriminatorValues, List<ColumnRef> alwaysProject,
            String tableLocal, String outputPackage) {
        var schema = ctx.graphitronSchema();
        var reprojection = schema == null
            ? no.sikt.graphitron.rewrite.JoinedTableReprojection.EMPTY
            : schema.joinedTableReprojectionOf(interfaceTypeName);
        var units = new no.sikt.graphitron.plan.GeneratedUnits(outputPackage);
        var source = new no.sikt.graphitron.command.LaunchSource.DiscriminatedTable(
            tableRef, discriminatorColumn, knownDiscriminatorValues, reprojection.baseSlice(),
            no.sikt.graphitron.plan.LauncherCommands.discriminatedBranches(
                participants, discriminatorColumn, reprojection, units),
            no.sikt.graphitron.plan.LauncherCommands.selectionRestriction(
                participants,
                schema == null ? typeName -> java.util.List.of() : schema::fieldsOf,
                units));
        return no.sikt.graphitron.render.DiscriminatedTableFragments.assembly(
            source, alwaysProject, tableLocal);
    }

    /**
     * Projects a multi-table polymorphic field's per-participant filter carriers into the
     * typename-keyed map the {@link MultiTablePolymorphicEmitter} branch loops consume, mirroring the
 * typename-keyed {@code participantJoinPaths} the same loops already take.
     */
    private static Map<String, List<WhereFilter>> participantFiltersByTypename(
            List<ParticipantFilters> participantFilters) {
        var byTypename = new LinkedHashMap<String, List<WhereFilter>>();
        for (var pf : participantFilters) {
            byTypename.put(pf.participant().typeName(), pf.filters());
        }
        return byTypename;
    }

    private static CodeBlock buildConditionCall(QueryField.QueryTableField qtf, String srcAlias, String outputPackage) {
        return buildConditionCall(qtf.parentTypeName(), qtf.name(), qtf.filters(), srcAlias, outputPackage);
    }

    /**
     * The one-line glue call every fetcher-hosted coordinate with live filters emits:
     * {@code Condition condition = <Parent>Conditions.<field>Condition(<alias>, env.getArguments()[, env]);}.
     * Naming and the env-appending fork live in {@link ConditionGlueCall}; the argument map is
     * the env's coerced arguments ({@code getArgument} is {@code getArguments().get}).
     *
     * <p>A coordinate with no live filters has no condition row and no glue method; its fetcher
     * composes the neutral condition from that absence. (The retired shim emitted a
     * {@code return DSL.noCondition();} method for these; the empty-set shims stop existing, a
     * shape change with no SQL effect.)
     */
    private static CodeBlock buildConditionCall(String parentTypeName, String fieldName,
            java.util.List<WhereFilter> filters, String srcAlias, String outputPackage) {
        if (filters.isEmpty()) {
            return CodeBlock.builder()
                .addStatement("$T condition = $T.noCondition()", CONDITION, DSL)
                .build();
        }
        return CodeBlock.builder()
            .addStatement("$T condition = $L", CONDITION,
                ConditionGlueCall.expression(parentTypeName, fieldName, filters, srcAlias,
                    CodeBlock.of("env.getArguments()"), outputPackage))
            .build();
    }


    /**
     * Renders the fetcher entry point of one {@code @routine}-writing mutation coordinate off its
     * command row ({@link no.sikt.graphitron.render.RoutineWriteFetcherRenderer}, total over the
     * row's two arms) and the relation's own tenancy axis. This shell reads the leaf for its name
     * alone; everything the body says, the connection it acquires included, comes off the plan.
     */
    private static MethodSpec renderRoutineWrite(TypeFetcherEmissionContext ctx,
            no.sikt.graphitron.rewrite.model.OutputField field,
            no.sikt.graphitron.plan.RoutineWriteRelation routineWrites) {
        var row = routineWrites.rowFor(ctx.parentTypeName(), field.name())
            .orElseThrow(() -> new IllegalStateException(
                "Graphitron generator bug (routine-write dispatch): coordinate '"
                + ctx.parentTypeName() + "." + field.name() + "' has no routine-write row;"
                + " the producer's membership and this dispatch have drifted. The relation is read"
                + " from the fact store, so a caller that generated without one plans no row for"
                + " any routine write and reaches this by construction"));
        return no.sikt.graphitron.render.RoutineWriteFetcherRenderer.render(
            row, routineWrites.tenancy(), ctx.argPathHelpers(), ctx.projectedKeyHost(),
            ctx.requestContextRead());
    }


    /**
     * Emits the fetcher for a {@link QueryField.QueryServiceTableField}: a direct call to
     * the developer service method, with an optional {@code dsl} local declared first
     * if the method takes a {@link org.jooq.DSLContext}. No projection — graphql-java's
     * column fetchers traverse the service-returned {@code Record}/{@code Result<Record>}.
     *
     * <p>Return type is the specific {@code Result<<RecordClass>>} for List cardinality or
     * the specific {@code <RecordClass>} for Single. Type-strictness is enforced at classifier
     * time: the strict return-type comparison in {@code ServiceDirectiveResolver}'s classify
     * phase rejects methods whose declared parameterized return type doesn't match the expected
     * record class for the field's {@code @table}-bound return type.
     */
    private static MethodSpec buildQueryServiceTableFetcher(TypeFetcherEmissionContext ctx, QueryField.QueryServiceTableField qstf,
                                                             String outputPackage) {
        var tableRef = qstf.returnType().table();
        var recordClass = tableRef.recordClass();
        boolean isList = qstf.returnType().wrapper().isList();
        // For List cardinality, the developer's declared return type is either Result<XRecord>
        // or List<XRecord> (validated in ServiceDirectiveResolver.validateRootListTableBoundReturnPair);
        // declare the local with whichever shape the developer chose so the generated
        // assignment compiles. graphql-java accepts either as a list value.
        TypeName returnType = isList ? qstf.serviceMethodCall().javaReturnType() : recordClass;
        return buildServiceFetcherCommon(ctx, qstf.name(), qstf.serviceMethodCall(),
            qstf.parentTypeName(), returnType, qstf.errorChannel(), outputPackage);
    }

    /**
     * Emits the fetcher for a {@link QueryField.QueryServiceRecordField}: same body shape as
     * {@link #buildQueryServiceTableFetcher} but the declared return type covers two
     * sub-shapes:
     *
     * <ul>
     *   <li>{@code ResultReturnType} with non-null {@code fqClassName} (a reflected Java
     *       backing class): declare the specific class for Single, or
     *       {@code java.util.List<className>} for List. Validated strictly at classifier time.</li>
     *   <li>{@code ResultReturnType} with null {@code fqClassName} (PojoResultType) or
     *       {@code ScalarReturnType}: declare based on the developer method's actual reflected
     *       return type. No strict validation — the dev's declared return is the source of
     *       truth, and graphql-java coerces.</li>
     * </ul>
     */
    private static MethodSpec buildQueryServiceRecordFetcher(TypeFetcherEmissionContext ctx, QueryField.QueryServiceRecordField qsrf,
                                                              String outputPackage) {
        TypeName returnType = computeServiceRecordReturnType(qsrf);
        return buildServiceFetcherCommon(ctx, qsrf.name(), qsrf.serviceMethodCall(),
            qsrf.parentTypeName(), returnType, qsrf.errorChannel(), outputPackage);
    }

    /**
     * Computes the emitter's declared return type for a {@link QueryField.QueryServiceRecordField}
     * based on the field's resolved {@link ReturnTypeRef} and (when needed) the method's actual
     * reflected return type. See {@link #buildQueryServiceRecordFetcher} for the policy.
     */
    private static TypeName computeServiceRecordReturnType(QueryField.QueryServiceRecordField qsrf) {
        // Source the declared return from ServiceMethodCall.javaReturnType, the structured TypeName
        // captured at walk time, so the emitter declares the matching shape directly without
        // parsing a string. This covers both sub-shapes uniformly:
        //   - ResultReturnType with a non-null fqClassName (a reflected Java backing class):
        //     checkServiceReturnMatchesPayload has verified javaReturnType equals the SDL payload
        //     type (isList ? List<payload> : payload), so this is the specific validated class.
        //   - PojoResultType (null fqClassName) or ScalarReturnType: the developer's declared
        //     return is the source of truth and graphql-java coerces.
        // Rebuilding the type via ClassName.bestGuess over the binary fqClassName would carry a
        // nested backing class through as the non-compiling Outer$Nested (bestGuess never splits
        // on '$'); javaReturnType resolves it structurally to Outer.Nested, the JLS-legal form.
        return qsrf.serviceMethodCall().javaReturnType();
    }

    /**
     * Emits the fetcher for a {@link MutationField.MutationServiceTableField}: identical body
     * shape to {@link #buildQueryServiceTableFetcher}. Root mutation fields have no parent table
     * and no parent-batching context, so the emission delegates to the shared
     * {@link #buildServiceFetcherCommon} helper without alteration. The shared helper handles
     * the pre-execution Jakarta validation pre-step and the try/catch wrapper uniformly across
     * query and mutation services; the success arm is universal passthrough.
     */
    private static MethodSpec buildMutationServiceTableFetcher(TypeFetcherEmissionContext ctx, MutationField.MutationServiceTableField mstf,
                                                                String outputPackage) {
        var tableRef = mstf.returnType().table();
        var recordClass = tableRef.recordClass();
        boolean isList = mstf.returnType().wrapper().isList();
        // See buildQueryServiceTableFetcher for the List-cardinality policy.
        TypeName returnType = isList ? mstf.serviceMethodCall().javaReturnType() : recordClass;
        return buildServiceFetcherCommon(ctx, mstf.name(), mstf.serviceMethodCall(),
            mstf.parentTypeName(), returnType, mstf.errorChannel(), outputPackage);
    }

    /**
     * Emits the fetcher for a {@link MutationField.MutationServiceRecordField}: identical body
     * shape to {@link #buildQueryServiceRecordFetcher}. Both {@code ResultReturnType} (with or
     * without a backing class) and {@code ScalarReturnType} return shapes are
     * handled by {@link #computeMutationServiceRecordReturnType}, mirroring the query side.
     */
    private static MethodSpec buildMutationServiceRecordFetcher(TypeFetcherEmissionContext ctx, MutationField.MutationServiceRecordField msrf,
                                                                 String outputPackage) {
        TypeName returnType = computeMutationServiceRecordReturnType(msrf);
        return buildServiceFetcherCommon(ctx, msrf.name(), msrf.serviceMethodCall(),
            msrf.parentTypeName(), returnType, msrf.errorChannel(), outputPackage);
    }

    /**
     * Mirrors {@link #computeServiceRecordReturnType} for the mutation side. Identical policy:
     * source the declared return from {@link no.sikt.graphitron.rewrite.model.ServiceMethodCall#javaReturnType()},
     * the structured {@link TypeName} captured at walk time. {@code checkServiceReturnMatchesPayload}
     * guards the mutation path (as it does the query path) and has verified that {@code javaReturnType}
     * equals the SDL payload type at the re-levelled cardinality ({@code isList ? List<payload> : payload},
     * with the composite-carrier re-levelling applied), so trusting {@code javaReturnType} directly
     * yields exactly the validated shape for both sub-shapes:
     * <ul>
     *   <li>{@code ResultReturnType} with a non-null {@code fqClassName} (a reflected backing class):
     *   {@code javaReturnType} is the validated payload type, {@code List<composite>} included, so the
     *   fetcher and its Outcome wrapping match the exact type the service yields.</li>
     *   <li>{@code PojoResultType} (null {@code fqClassName}) or {@code ScalarReturnType}: the developer's
     *   reflected return is the source of truth and graphql-java coerces.</li>
     * </ul>
     */
    private static TypeName computeMutationServiceRecordReturnType(MutationField.MutationServiceRecordField msrf) {
        return msrf.serviceMethodCall().javaReturnType();
    }

    /**
     * Shared body shape for the four service-backed root fetchers
     * ({@link #buildQueryServiceTableFetcher}, {@link #buildQueryServiceRecordFetcher},
     * {@link #buildMutationServiceTableFetcher}, {@link #buildMutationServiceRecordFetcher}):
     * optional {@code dsl} local + direct {@code return ServiceClass.method(<args>);}. Mutation
     * services share the body shape because they run synchronously, on a root field with no
     * parent-batching context, and the developer-supplied method owns the transaction scope.
     *
     * <p>When the channel carries any {@link no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ValidationHandler},
     * the wrapper inserts a pre-execution Jakarta validation step ahead of the try block:
     * walks every {@link ParamSource.Arg} parameter, validates each non-null arg via the
     * {@code GraphitronContext}-supplied {@code Validator}, and short-circuits with the
     * payload's errors-arm filled by the violations when any are produced.
     *
     * <p>The success arm is universal passthrough: the service method returns the SDL payload
     * class directly, and the emitter forwards the return value into the
     * {@link DataFetcherResult} without further assembly. Per-field wiring (graphql-java's
     * child fetchers) projects SDL fields off the parent's domain return, so the generator
     * does not construct output DTOs on the happy path.
     *
     * <p>The catch arm forks on {@code errorChannel}: a present channel routes through
     * {@code ErrorRouter.dispatch} with the channel's mapping table and synthesized payload
     * factory; an absent channel routes through {@code ErrorRouter.redact}. Generator-side DTO
     * construction is unavoidable on the error path because no value was returned for per-field
     * wiring to project from.
     */
    private static MethodSpec buildServiceFetcherCommon(TypeFetcherEmissionContext ctx, String fieldName,
                                                        ServiceMethodCall carrier,
                                                        String parentTypeName, TypeName valueType,
                                                        Optional<ErrorChannel.Mapped> errorChannel,
                                                        String outputPackage) {
        // An @service outcome field (Mapped channel) hands graphql-java a typed Outcome<X>
        // source. The DataFetcherResult payload type becomes Outcome<X>; the inner method result
        // local stays X (the service's return), wrapped in Success on the happy path and replaced by
        // ErrorList on the mapped-error path. A channel-less @service field (no errors field) keeps
        // the bare X payload and the redact-only catch arm.
        boolean wrap = errorChannel.isPresent();
        TypeName payloadType = wrap ? outcomeOf(valueType, outputPackage) : valueType;

        var builder = MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(payloadType))
            .addParameter(ENV, "env");

        // Pre-execution Jakarta validation. Emitted ahead of the try block so a Validator-side
        // throw still propagates to the wrapper's catch arm uniformly with the body's exceptions;
        // the body is never invoked when violations exist. The early return wraps the violation
        // list in Outcome.ErrorList (channel-agnostic under the wrapper).
        if (wrap && hasValidationHandler(errorChannel.get())) {
            builder.addCode(validatorPreStep(ctx, carrier, fieldName, payloadType, outputPackage));
        }

        builder.beginControlFlow("try");
        // Register the same-class graphitronContext(env) helper before the emitter expands; the
        // emitter generates unqualified calls and relies on the *Fetchers-class helper that
        // {@link #buildGraphitronContextHelper} installs when GRAPHITRON_CONTEXT is requested.
        ctx.graphitronContextCall();
        ServiceMethodCallEmitter.emit(carrier, valueType, ctx.fetchersHelperNames(),
                TenantDslEmitter.dslExpression(ctx, fieldName, outputPackage),
                outputPackage, parentTypeName + "." + fieldName, ctx.nodeIdDecodeHelpers())
            .forEach(builder::addStatement);
        if (wrap) {
            builder.addCode(returnSyncSuccessWrapped(payloadType, outputPackage, "result"));
        } else {
            builder.addCode(returnSyncSuccess(valueType, "result"));
        }
        builder.nextControlFlow("catch ($T e)", Exception.class);
        if (wrap) {
            builder.addCode(ChannelCatchArmEmitter.emit(errorChannel.get(), payloadType, outputPackage));
        } else {
            builder.addCode(catchArm(outputPackage, Optional.empty()));
        }
        builder.endControlFlow();

        return builder.build();
    }

    /** {@code Outcome<X>} in the run's schema-support package, boxing primitive {@code X}.*/
    private static TypeName outcomeOf(TypeName valueType, String outputPackage) {
        return ParameterizedTypeName.get(
            ClassName.get(outputPackage + ".schema", OutcomeClassGenerator.CLASS_NAME), boxed(valueType));
    }

    /** Success-path return wrapping the method result in {@code Outcome.Success}.*/
    private static CodeBlock returnSyncSuccessWrapped(TypeName outcomeType, String outputPackage, String resultLocal) {
        var success = ClassName.get(outputPackage + ".schema", OutcomeClassGenerator.CLASS_NAME)
            .nestedClass(OutcomeClassGenerator.SUCCESS_CLASS);
        return CodeBlock.of("return $T.<$T>newResult().data(new $T<>($L)).build();\n",
            DATA_FETCHER_RESULT, outcomeType, success, resultLocal);
    }

    /**
     * Builds the call-target {@link CodeBlock} for a service method invocation: either the bare
     * class name {@code ServiceClass} for the {@link MethodRef.CallShape.Static} arm, or a
     * fresh-instance expression {@code new ServiceClass(dsl)} for
     * {@link MethodRef.CallShape.InstanceWithDslHolder}. The caller appends
     * {@code .methodName(args)}.
     *
     * <p>The instance form requires the surrounding method to declare a {@code DSLContext dsl}
     * local in scope before the call site; the root-fetcher and rows-method emitters both gate
     * that local on {@link #needsDsl(MethodRef.CallShape)} (which reads the same arm). Package-
     * private to enable direct unit-tier exercise from {@code MethodRefCallShapeTest}.
     */
    static CodeBlock serviceCallTarget(MethodRef.Service method, ClassName serviceClass) {
        return switch (method.callShape()) {
            case MethodRef.CallShape.Static ignored -> CodeBlock.of("$T", serviceClass);
            case MethodRef.CallShape.InstanceWithDslHolder holder ->
                CodeBlock.of("new $T($L)", serviceClass, holderCtorArgs(holder));
        };
    }

    /**
     * Renders the holder constructor's actual-argument list. A {@link ParamSource.DslContext}
     * ctor parameter reads the surrounding {@code dsl} local; a {@link ParamSource.Context}
     * parameter extracts inline via the {@code graphitronContext(env)} helper, mirroring
     * {@code ServiceMethodCallEmitter}'s {@code FromContext} emit. A single-{@code DSLContext}
     * holder renders as {@code new ClassName(dsl)}.
     */
    private static CodeBlock holderCtorArgs(MethodRef.CallShape.InstanceWithDslHolder holder) {
        CodeBlock.Builder b = CodeBlock.builder();
        boolean first = true;
        for (MethodRef.Param p : holder.ctorParams()) {
            if (!first) b.add(", ");
            first = false;
            if (p.source() instanceof ParamSource.DslContext) {
                b.add("dsl");
            } else {
                TypeName javaType = p instanceof MethodRef.Param.Typed t ? t.javaType() : ClassName.OBJECT;
                b.add("($T) graphitronContext(env).getContextArgument(env, $S)", javaType, p.name());
            }
        }
        return b.build();
    }

    /**
     * Decides whether the surrounding method needs a {@code DSLContext dsl} local. Delegates to
     * {@link MethodRef.CallShape#needsDsl()}, the one home of the static-vs-instance fork (the
     * launcher renderer's service arm reads the same method), kept as a named local seam for
     * {@link #buildServiceFetcherCommon} and the unit-tier exercise in
     * {@code MethodRefCallShapeTest}.
     */
    static boolean needsDsl(MethodRef.CallShape callShape) {
        return callShape.needsDsl();
    }

    /** Whether any flattened handler on the channel is a {@code ValidationHandler}. */
    private static boolean hasValidationHandler(ErrorChannel channel) {
        return channel.mappedErrorTypes().stream()
            .flatMap(et -> et.handlers().stream())
            .anyMatch(h -> h instanceof no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ValidationHandler);
    }

    /**
     * Emits the wrapper's pre-execution Jakarta validation block. Walks every
     * {@link ParamSource.Arg} parameter on the service method, validates each non-null arg via
     * the {@code GraphitronContext}-supplied {@code Validator}, accumulates each violation as a
     * {@code GraphQLError} via the generated {@code ConstraintViolations.toGraphQLError}, and
     * short-circuits with the payload's errors-arm filled by the violations list when the
     * accumulator is non-empty.
     *
     * <p>Input-typed SDL args materialise through the graphitron-emitted class's
     * {@code fromMap(Map<String,Object>)} factory before the validator walks them. The
     * fetcher boundary feeds the typed instance into
     * {@code validator.validate(<typed>)}; the walk produces zero violations when no
     * programmatic {@code ConstraintMapping} entries are attached. Scalar / enum SDL args stay
     * on the raw value path. When the assembled schema is unavailable (some unit-tier tests
     * build the model only), the pre-step falls back to validating the raw value for every arg.
     *
     * <p>Walks {@link ServiceMethodCall#methodArgs()} for
     * {@link no.sikt.graphitron.rewrite.model.MappingEntry.FromArg}
     * entries. The {@link no.sikt.graphitron.rewrite.model.ValueShape} carries each top-level
     * arg's outer arg name on its data-bearing leaves; {@link #outerArgOfValueShape} descends
     * to the first available leaf to extract it. Ctor args are not walked (the walker forbids
     * {@code FromArg} entries in {@code ctorArgs} structurally).
     */
    private static CodeBlock validatorPreStep(TypeFetcherEmissionContext ctx, ServiceMethodCall carrier,
                                              String fieldName,
                                              TypeName outcomeType, String outputPackage) {
        var validator = ClassName.get("jakarta.validation", "Validator");
        var constraintViolation = ClassName.get("jakarta.validation", "ConstraintViolation");
        // List<Object> so the violations feed straight into Outcome.ErrorList(List<Object>) on the
        // early return; each element is a GraphQLError from ConstraintViolations.toGraphQLError.
        var listOfErrors = ParameterizedTypeName.get(LIST, ClassName.get(Object.class));
        var arrayList = ClassName.get("java.util", "ArrayList");
        var constraintViolations = ClassName.get(outputPackage + ".schema",
            ConstraintViolationsClassGenerator.CLASS_NAME);
        var violationWildcard = ParameterizedTypeName.get(constraintViolation,
            WildcardTypeName.subtypeOf(Object.class));
        var mapStringObject = ParameterizedTypeName.get(
            ClassName.get(Map.class), ClassName.get(String.class), ClassName.get(Object.class));

        var b = CodeBlock.builder();
        b.addStatement("$T validator = $L.getValidator(env)", validator, ctx.graphitronContextCall());
        b.addStatement("$T violations = new $T<>()", listOfErrors, arrayList);
        for (var entry : carrier.methodArgs()) {
            if (!(entry instanceof no.sikt.graphitron.rewrite.model.MappingEntry.FromArg fromArg)) continue;
            String argName = outerArgOfValueShape(fromArg.shape());
            String local = "arg_" + sanitizeIdent(argName);
            ClassName inputClass = resolveInputArgClass(ctx, fieldName, argName, outputPackage);
            if (inputClass != null) {
                // Input-typed SDL arg: materialise the graphitron-emitted class via fromMap
                // and walk the typed instance. The local is the validator's target (typed),
                // not the raw Map. The class goes out of scope after the pre-step; downstream
                // value reads route through the bean path or the existing Map.get pattern.
                b.addStatement("$T $L_raw = env.getArgument($S)",
                    mapStringObject, local, argName);
                b.addStatement("$T $L = $L_raw == null ? null : $T.fromMap($L_raw)",
                    inputClass, local, local, inputClass, local);
            } else {
                b.addStatement("$T $L = env.getArgument($S)", Object.class, local, argName);
            }
            b.beginControlFlow("if ($L != null)", local);
            b.beginControlFlow("for ($T violation : validator.validate($L))", violationWildcard, local);
            b.addStatement("violations.add($T.toGraphQLError(violation, env, $S))",
                constraintViolations, argName);
            b.endControlFlow();
            b.endControlFlow();
        }
        b.beginControlFlow("if (!violations.isEmpty())");
        b.add(ChannelEarlyReturnEmitter.emit(outcomeType, "violations", outputPackage));
        b.endControlFlow();
        return b.build();
    }

    /**
     * Walks a {@link ServiceMethodCall} carrier and projects every {@link
     * no.sikt.graphitron.rewrite.model.ValueShape.RecordInput} /
     * {@link no.sikt.graphitron.rewrite.model.ValueShape.JavaBeanInput} (including transitively
     * via {@link no.sikt.graphitron.rewrite.model.ValueShape.ListOf} and nested
     * {@link no.sikt.graphitron.rewrite.model.ValueShape.FieldBinding}s) into the helper-queue
     * map as a synthetic {@link CallSiteExtraction.InputBean}. The synthetic carries the
     * structural detail {@link InputBeanInstantiationEmitter} needs (bean class, target, field
     * bindings); these mirror what {@link CallSiteExtraction.InputBean} carries today for the
     * legacy MethodBackedField walk, so both arms dedup on the same {@code ClassName} key.
     */
    private static void collectBeanHelpersFromCarrier(
            ServiceMethodCall carrier,
            java.util.Map<no.sikt.graphitron.javapoet.ClassName, CallSiteExtraction.InputBean> out) {
        if (carrier instanceof ServiceMethodCall.Instance inst) {
            for (var e : inst.ctorArgs()) collectFromMappingEntry(e, out);
        }
        for (var e : carrier.methodArgs()) collectFromMappingEntry(e, out);
    }

    private static void collectFromMappingEntry(
            no.sikt.graphitron.rewrite.model.MappingEntry entry,
            java.util.Map<no.sikt.graphitron.javapoet.ClassName, CallSiteExtraction.InputBean> out) {
        if (entry instanceof no.sikt.graphitron.rewrite.model.MappingEntry.FromArg fromArg) {
            collectFromValueShape(fromArg.shape(), out);
        }
    }

    private static void collectFromValueShape(
            no.sikt.graphitron.rewrite.model.ValueShape shape,
            java.util.Map<no.sikt.graphitron.javapoet.ClassName, CallSiteExtraction.InputBean> out) {
        switch (shape) {
            case no.sikt.graphitron.rewrite.model.ValueShape.Scalar ignored -> { /* leaf */ }
            case no.sikt.graphitron.rewrite.model.ValueShape.ListOf l -> collectFromValueShape(l.elementShape(), out);
            case no.sikt.graphitron.rewrite.model.ValueShape.RecordInput rec ->
                registerBeanHelper(rec.javaClass(), CallSiteExtraction.InputBean.Target.RECORD, rec.fields(), out);
            case no.sikt.graphitron.rewrite.model.ValueShape.JavaBeanInput jb ->
                registerBeanHelper(jb.javaClass(), CallSiteExtraction.InputBean.Target.JAVA_BEAN, jb.fields(), out);
            // A jOOQ TableRecord input param. Its create<Record> helper is collected up front by
            // collectJooqRecordCarriers, not here — this walk is the bean-only leg of the dual walk.
            case no.sikt.graphitron.rewrite.model.ValueShape.JooqRecordInput ignored -> { /* jooq handled up front */ }
        }
    }

    private static void registerBeanHelper(
            no.sikt.graphitron.javapoet.ClassName beanClass,
            CallSiteExtraction.InputBean.Target target,
            List<no.sikt.graphitron.rewrite.model.ValueShape.FieldBinding> vsFields,
            java.util.Map<no.sikt.graphitron.javapoet.ClassName, CallSiteExtraction.InputBean> out) {
        if (out.containsKey(beanClass)) return;
        var fieldBindings = new java.util.ArrayList<CallSiteExtraction.FieldBinding>(vsFields.size());
        for (var vfb : vsFields) {
            var leafCarrier = leafForFieldBinding(vfb.shape());
            boolean isList = vfb.shape() instanceof no.sikt.graphitron.rewrite.model.ValueShape.ListOf;
            var inner = isList
                ? ((no.sikt.graphitron.rewrite.model.ValueShape.ListOf) vfb.shape()).elementShape()
                : vfb.shape();
            String javaElementTypeName = innerElementTypeNameOf(inner);
            fieldBindings.add(new CallSiteExtraction.FieldBinding(
                vfb.accessPath(), vfb.javaFieldName(), leafCarrier, isList, javaElementTypeName));
        }
        var ib = new CallSiteExtraction.InputBean(beanClass, target, fieldBindings);
        out.put(beanClass, ib);
        // Recurse so nested beans register their own helper.
        for (var vfb : vsFields) {
            collectFromValueShape(vfb.shape(), out);
        }
    }

    /**
     * Collect every bean (POJO / {@code @record}) class reached by an {@code InputBean} extraction on
     * this {@code <Type>Fetchers} class, across both coordinates, into a dedup map keyed by bean
     * {@link no.sikt.graphitron.javapoet.ClassName} in first-encounter order. Nested beans are
     * collected transitively so a single bean class appears exactly once. The child / root-permit
     * coordinate is the {@link MethodBackedField#method() callParams} walk; the root coordinate is the
     * {@link no.sikt.graphitron.rewrite.model.ServiceField} carrier's composite {@code ValueShape}
     * arms. Hoisted out of the drain so the resolver can size the {@code create*} stem namespace over
     * the union of these bean classes and the jOOQ-record carrier classes before any name is emitted;
     * the drain reuses the returned map for emission.
     */
    private static java.util.LinkedHashMap<no.sikt.graphitron.javapoet.ClassName, CallSiteExtraction.InputBean>
            collectBeanHelpers(List<GraphitronField> fields) {
        var beanHelpers = new java.util.LinkedHashMap<no.sikt.graphitron.javapoet.ClassName,
            CallSiteExtraction.InputBean>();
        fields.stream()
            .filter(f -> f instanceof MethodBackedField)
            .map(f -> (MethodBackedField) f)
            .flatMap(f -> f.method().callParams().stream())
            .filter(p -> p.extraction() instanceof CallSiteExtraction.InputBean)
            .map(p -> (CallSiteExtraction.InputBean) p.extraction())
            .forEach(ib -> InputBeanInstantiationEmitter.collectTransitively(ib, beanHelpers));
        // Walk the four service permits' carriers for composite ValueShape arms.
        for (var field : fields) {
            if (field instanceof no.sikt.graphitron.rewrite.model.ServiceField sf) {
                collectBeanHelpersFromCarrier(sf.serviceMethodCall(), beanHelpers);
            }
        }
        return beanHelpers;
    }

    /**
     * Collect every {@link CallSiteExtraction.NodeIdDecodeRecord} sitting directly on a producer
     * parameter of this {@code <Type>Fetchers} class, across both coordinates: the child / root
     * permit's {@link MethodBackedField#method() callParams}, and the root
     * {@link no.sikt.graphitron.rewrite.model.ServiceField} carrier's scalar leaves. Sibling to
     * {@link InputBeanInstantiationEmitter#collectRecordDecoders}, which collects the same leaf one
     * level in, on a bean's member; both feed the same two dedup maps so a record type reached both
     * ways emits one helper.
     *
     * <p>List-ness is read off the slot's declared Java type rather than off the leaf, exactly as
     * the bean walk reads it off the enclosing binding: the leaf is arity- and shape-agnostic and a
     * {@code List<XRecord>} parameter is what asks for the plural variant.
     */
    private static void collectParamRecordDecoders(List<GraphitronField> fields,
            java.util.Map<no.sikt.graphitron.javapoet.ClassName, CallSiteExtraction.NodeIdDecodeRecord> scalarOut,
            java.util.Map<no.sikt.graphitron.javapoet.ClassName, CallSiteExtraction.NodeIdDecodeRecord> listOut) {
        for (var field : fields) {
            if (field instanceof MethodBackedField mbf) {
                for (var p : mbf.method().callParams()) {
                    if (p.extraction() instanceof CallSiteExtraction.NodeIdDecodeRecord rec) {
                        record0(rec, p.list(), scalarOut, listOut);
                    }
                }
            }
            if (field instanceof no.sikt.graphitron.rewrite.model.ServiceField sf) {
                for (var e : allServiceEntries(sf.serviceMethodCall())) {
                    if (e instanceof no.sikt.graphitron.rewrite.model.MappingEntry.FromArg fromArg
                            && fromArg.shape() instanceof no.sikt.graphitron.rewrite.model.ValueShape.Scalar s
                            && s.leafTransform() instanceof CallSiteExtraction.NodeIdDecodeRecord rec) {
                        record0(rec, isListTypeName(s.javaType(), rec.table().recordClass()),
                            scalarOut, listOut);
                    }
                }
            }
        }
    }

    /** One decode-record registration: the scalar body always, the plural variant on a list slot. */
    private static void record0(CallSiteExtraction.NodeIdDecodeRecord rec, boolean list,
            java.util.Map<no.sikt.graphitron.javapoet.ClassName, CallSiteExtraction.NodeIdDecodeRecord> scalarOut,
            java.util.Map<no.sikt.graphitron.javapoet.ClassName, CallSiteExtraction.NodeIdDecodeRecord> listOut) {
        var key = rec.table().recordClass();
        scalarOut.putIfAbsent(key, rec);
        if (list) {
            listOut.putIfAbsent(key, rec);
        }
    }

    /** Whether a slot's declared Java type is {@code List<elementClass>}. */
    private static boolean isListTypeName(no.sikt.graphitron.javapoet.TypeName javaType,
            no.sikt.graphitron.javapoet.ClassName elementClass) {
        return javaType instanceof no.sikt.graphitron.javapoet.ParameterizedTypeName ptn
            && ptn.rawType().equals(no.sikt.graphitron.javapoet.ClassName.get(List.class))
            && ptn.typeArguments().size() == 1
            && ptn.typeArguments().getFirst().equals(elementClass);
    }

    /** A service carrier's constructor and method entries in one list, in emission order. */
    private static List<no.sikt.graphitron.rewrite.model.MappingEntry> allServiceEntries(
            ServiceMethodCall carrier) {
        var all = new java.util.ArrayList<no.sikt.graphitron.rewrite.model.MappingEntry>();
        if (carrier instanceof ServiceMethodCall.Instance inst) {
            all.addAll(inst.ctorArgs());
        }
        all.addAll(carrier.methodArgs());
        return all;
    }

    /**
     * Collect every jOOQ-record {@code @service} carrier on this {@code <Type>Fetchers} class,
     * across both coordinates, into a flat list (dedup by shape happens in
     * {@link JooqRecordHelperNames#of}). The child / root-permit coordinate is the
     * {@link MethodBackedField#method() callParams} walk; the root coordinate is the
     * {@link no.sikt.graphitron.rewrite.model.ServiceField} carrier's
     * {@link no.sikt.graphitron.rewrite.model.ValueShape.JooqRecordInput} leaves. Order is
     * first-encounter, so the resolver's uncontended work-list preserves the original emission order.
     */
    private static List<CallSiteExtraction.JooqRecord> collectJooqRecordCarriers(List<GraphitronField> fields) {
        var out = new java.util.ArrayList<CallSiteExtraction.JooqRecord>();
        fields.stream()
            .filter(f -> f instanceof MethodBackedField)
            .map(f -> (MethodBackedField) f)
            .flatMap(f -> f.method().callParams().stream())
            .filter(p -> p.extraction() instanceof CallSiteExtraction.JooqRecord)
            .map(p -> (CallSiteExtraction.JooqRecord) p.extraction())
            .forEach(out::add);
        for (var field : fields) {
            if (field instanceof no.sikt.graphitron.rewrite.model.ServiceField sf) {
                collectJooqRecordInputsFromCarrier(sf.serviceMethodCall(), out);
            }
        }
        return out;
    }

    private static void collectJooqRecordInputsFromCarrier(
            ServiceMethodCall carrier, List<CallSiteExtraction.JooqRecord> out) {
        if (carrier instanceof ServiceMethodCall.Instance inst) {
            for (var e : inst.ctorArgs()) collectJooqRecordInputsFromEntry(e, out);
        }
        for (var e : carrier.methodArgs()) collectJooqRecordInputsFromEntry(e, out);
    }

    private static void collectJooqRecordInputsFromEntry(
            no.sikt.graphitron.rewrite.model.MappingEntry entry, List<CallSiteExtraction.JooqRecord> out) {
        if (entry instanceof no.sikt.graphitron.rewrite.model.MappingEntry.FromArg fromArg) {
            collectJooqRecordInputsFromShape(fromArg.shape(), out);
        }
    }

    private static void collectJooqRecordInputsFromShape(
            no.sikt.graphitron.rewrite.model.ValueShape shape, List<CallSiteExtraction.JooqRecord> out) {
        switch (shape) {
            case no.sikt.graphitron.rewrite.model.ValueShape.Scalar ignored -> { /* leaf */ }
            case no.sikt.graphitron.rewrite.model.ValueShape.ListOf l -> collectJooqRecordInputsFromShape(l.elementShape(), out);
            // A jOOQ record never nests inside a bean, but recurse for parity so a future nesting cannot
            // silently drop a carrier from the resolver.
            case no.sikt.graphitron.rewrite.model.ValueShape.RecordInput rec -> {
                for (var f : rec.fields()) collectJooqRecordInputsFromShape(f.shape(), out);
            }
            case no.sikt.graphitron.rewrite.model.ValueShape.JavaBeanInput jb -> {
                for (var f : jb.fields()) collectJooqRecordInputsFromShape(f.shape(), out);
            }
            case no.sikt.graphitron.rewrite.model.ValueShape.JooqRecordInput jr -> out.add(jr.carrier());
        }
    }

    /** Returns the per-field leaf extraction the InputBean helper uses to expand each field. */
    private static CallSiteExtraction leafForFieldBinding(no.sikt.graphitron.rewrite.model.ValueShape shape) {
        return switch (shape) {
            case no.sikt.graphitron.rewrite.model.ValueShape.Scalar s -> s.leafTransform();
            case no.sikt.graphitron.rewrite.model.ValueShape.ListOf l ->
                l.elementShape() instanceof no.sikt.graphitron.rewrite.model.ValueShape.Scalar ls
                    ? ls.leafTransform() : leafForFieldBinding(l.elementShape());
            case no.sikt.graphitron.rewrite.model.ValueShape.RecordInput rec ->
                new CallSiteExtraction.InputBean(rec.javaClass(),
                    CallSiteExtraction.InputBean.Target.RECORD,
                    convertNestedFieldBindings(rec.fields()));
            case no.sikt.graphitron.rewrite.model.ValueShape.JavaBeanInput jb ->
                new CallSiteExtraction.InputBean(jb.javaClass(),
                    CallSiteExtraction.InputBean.Target.JAVA_BEAN,
                    convertNestedFieldBindings(jb.fields()));
            // Forced by the sealed addition, unreachable here — a JooqRecordInput is never an
            // InputBean field shape, so this leaf-for-field-binding walk never meets one.
            case no.sikt.graphitron.rewrite.model.ValueShape.JooqRecordInput jr ->
                throw new IllegalStateException(
                    "JooqRecordInput is not an InputBean field shape: " + jr.carrier().table().recordClass());
        };
    }

    private static List<CallSiteExtraction.FieldBinding> convertNestedFieldBindings(
            List<no.sikt.graphitron.rewrite.model.ValueShape.FieldBinding> vsFields) {
        var out = new java.util.ArrayList<CallSiteExtraction.FieldBinding>(vsFields.size());
        for (var vfb : vsFields) {
            boolean isList = vfb.shape() instanceof no.sikt.graphitron.rewrite.model.ValueShape.ListOf;
            var inner = isList
                ? ((no.sikt.graphitron.rewrite.model.ValueShape.ListOf) vfb.shape()).elementShape()
                : vfb.shape();
            out.add(new CallSiteExtraction.FieldBinding(
                vfb.accessPath(), vfb.javaFieldName(),
                leafForFieldBinding(vfb.shape()), isList, innerElementTypeNameOf(inner)));
        }
        return out;
    }

    private static String innerElementTypeNameOf(no.sikt.graphitron.rewrite.model.ValueShape shape) {
        return switch (shape) {
            case no.sikt.graphitron.rewrite.model.ValueShape.Scalar s -> s.javaType().toString();
            case no.sikt.graphitron.rewrite.model.ValueShape.ListOf l -> innerElementTypeNameOf(l.elementShape());
            case no.sikt.graphitron.rewrite.model.ValueShape.RecordInput r -> r.javaClass().toString();
            case no.sikt.graphitron.rewrite.model.ValueShape.JavaBeanInput jb -> jb.javaClass().toString();
            // Forced by the sealed addition; a JooqRecordInput is never an InputBean field shape,
            // but the record class is the trivially-correct inner element type if ever reached.
            case no.sikt.graphitron.rewrite.model.ValueShape.JooqRecordInput jr -> jr.carrier().table().recordClass().toString();
        };
    }

    /**
     * Descends into a {@link no.sikt.graphitron.rewrite.model.ValueShape} until a data-bearing
     * leaf ({@code Scalar} / {@code ListOf}) is found, then returns its outer-arg name. The
     * walker emits every {@link no.sikt.graphitron.rewrite.model.ValueShape.RecordInput} /
     * {@link no.sikt.graphitron.rewrite.model.ValueShape.JavaBeanInput} with siblings that
     * share the same outer arg as a prefix, so descending to any leaf is sufficient.
     */
    private static String outerArgOfValueShape(no.sikt.graphitron.rewrite.model.ValueShape shape) {
        return switch (shape) {
            case no.sikt.graphitron.rewrite.model.ValueShape.Scalar s -> s.sdlPath().outerArgName();
            case no.sikt.graphitron.rewrite.model.ValueShape.ListOf l -> l.sdlPath().outerArgName();
            case no.sikt.graphitron.rewrite.model.ValueShape.RecordInput r ->
                r.fields().isEmpty() ? "" : outerArgOfValueShape(r.fields().getFirst().shape());
            case no.sikt.graphitron.rewrite.model.ValueShape.JavaBeanInput jb ->
                jb.fields().isEmpty() ? "" : outerArgOfValueShape(jb.fields().getFirst().shape());
            // A JooqRecordInput carries its own path; defensive read for the forced arm.
            case no.sikt.graphitron.rewrite.model.ValueShape.JooqRecordInput jr -> jr.sdlPath().outerArgName();
        };
    }

    /**
     * Resolves an SDL arg name to the graphitron-emitted input-class {@link ClassName} when the
     * arg's SDL type unwraps to an {@code GraphQLInputObjectType}. Returns {@code null} for
     * scalar / enum args, for unresolved fields, and when the assembled schema isn't available
     * (model-only build path).
     */
    private static ClassName resolveInputArgClass(TypeFetcherEmissionContext ctx,
                                                  String fieldName, String argName,
                                                  String outputPackage) {
        var assembled = ctx.assembledSchema();
        if (assembled == null) return null;
        var parent = assembled.getType(ctx.parentTypeName());
        if (!(parent instanceof graphql.schema.GraphQLObjectType obj)) return null;
        var field = obj.getFieldDefinition(fieldName);
        if (field == null) return null;
        var argument = field.getArgument(argName);
        if (argument == null) return null;
        var base = graphql.schema.GraphQLTypeUtil.unwrapAll(argument.getType());
        if (!(base instanceof graphql.schema.GraphQLInputObjectType in)) return null;
        return ClassName.get(outputPackage + ".inputs", in.getName());
    }

    /**
     * Sanitises a GraphQL argument name into a Java identifier suffix for use as a local
     * variable name in the validator pre-step. Replaces every non-{@code [A-Za-z0-9_]}
     * character with {@code _}; GraphQL arg names are already restricted to ASCII identifier
     * characters today, so this is a future-proofing pass rather than a real normalisation.
     */
    private static String sanitizeIdent(String name) {
        var sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append((Character.isLetterOrDigit(c) || c == '_') ? c : '_');
        }
        return sb.toString();
    }

    /**
     * The direct-return DML dispatch: one arm per write verb, forking on the leaf's carried
     * write arm. The per-verb emit bodies stay distinct because the SQL statements genuinely
     * differ; what dissolved is the per-verb leaf identity, not the per-verb statement.
     */
    private static MethodSpec buildDmlTableFetcher(TypeFetcherEmissionContext ctx, MutationField.DmlTableField f,
                                                   String outputPackage,
                                                   no.sikt.graphitron.command.LauncherCommand row,
                                                   no.sikt.graphitron.command.CarrierDsl carrierDsl) {
        return switch (f.write()) {
            case OperationMember.Write.Insert w -> buildMutationInsertFetcher(ctx, f, w, outputPackage, row, carrierDsl);
            case OperationMember.Write.Update w -> buildMutationUpdateFetcher(ctx, f, w, outputPackage, row, carrierDsl);
            case OperationMember.Write.Delete w -> buildMutationDeleteFetcher(ctx, f, w, outputPackage, row, carrierDsl);
            case OperationMember.Write.Upsert w -> buildMutationUpsertFetcher(ctx, f, w, outputPackage, row, carrierDsl);
        };
    }

    /**
     * Emits a fetcher for the Delete write arm: a synchronous static
     * method that runs {@code dsl.deleteFrom(table).where(<lookupKey predicates>)
     * .returningResult(<keys or $project>).fetchOne(...)}.
     *
     * <p>Empty-match semantics: {@code .fetchOne(...)} returns {@code null} when the WHERE clause
     * matches no row. graphql-java surfaces that as a GraphQL null on a nullable field, or a
     * non-null violation on {@code ID!}/{@code Type!}.
     */
    private static MethodSpec buildMutationDeleteFetcher(TypeFetcherEmissionContext ctx, MutationField.DmlTableField f,
                                                          OperationMember.Write.Delete w,
                                                          String outputPackage,
                                                          no.sikt.graphitron.command.LauncherCommand row,
                                                          no.sikt.graphitron.command.CarrierDsl carrierDsl) {
        // The WHERE columns come off the DeleteRows carrier (deleteRows().whereColumns()) and
        // the slim arg surface (inputArg). The carrier's KeyColumn list projects into the
        // InputColumnBindingGroup shape the shared lookup-WHERE emitters consume via keyGroupsOf.
        var inputArg = w.inputArg();
        var tableRef = inputArg.table();
        var tablesOnly = GeneratorUtils.ResolvedTableNames.ofTable(tableRef);
        String tableLocal = tablesOnly.tableLocalName();
        var whereGroups = keyGroupsOf(w.deleteRows().whereColumns());

        var dmlChain = CodeBlock.builder().add(".deleteFrom($L)\n", tableLocal);
        var postInGuard = CodeBlock.builder();
        if (inputArg.list()) {
            dmlChain.add(".where(").add(buildBulkLookupRowIn(whereGroups, tablesOnly, tableRef)).add(")\n");
        } else {
            var chunk = buildLookupWhereSingleRow(whereGroups, tablesOnly, tableRef, "in");
            postInGuard.add(chunk.decodeLocals());
            dmlChain.add(".where(").add(chunk.whereExpr()).add(")\n");
        }

        return buildDmlFetcher(ctx, f, f.returnExpression(), f.errorChannel(),
            inputArg.name(), tableRef, tablesOnly, tableLocal,
            outputPackage, dmlChain.build(),
            f.dialectRequirement(), postInGuard.build(), inputArg.list(), row, carrierDsl);
    }

    /**
     * Emits a fetcher for the Insert write arm: a synchronous static
     * method that runs {@code dsl.insertInto(table, cols...).values(vals...)
     * .returningResult(<keys or $project>).fetchOne(...)}.
     *
     * <p>Column list is every {@code InputField.ColumnBackedField} in {@code tia.fields()} in
     * declaration order; values list is parallel, with each value bound via
     * {@code DSL.val(in.get("name"), Tables.T.COL.getDataType())} (the two-argument form
     * delegates coercion to the column's registered jOOQ {@code Converter}). {@code @lookupKey}
     * fields are included verbatim — INSERT does not treat them specially. The classifier
     * guarantees that every input field is a {@code Direct}-extracted {@code ColumnField},
     * which lets the loop walk {@code tia.fields()} with a single cast.
     */
    private static MethodSpec buildMutationInsertFetcher(TypeFetcherEmissionContext ctx, MutationField.DmlTableField f,
                                                          OperationMember.Write.Insert w,
                                                          String outputPackage,
                                                          no.sikt.graphitron.command.LauncherCommand row,
                                                          no.sikt.graphitron.command.CarrierDsl carrierDsl) {
        var tia = w.input();
        var tableRef = tia.inputTable();
        var tablesOnly = GeneratorUtils.ResolvedTableNames.ofTable(tableRef);
        String tableLocal = tablesOnly.tableLocalName();

        var fields = tia.fields();
        var colList = buildInsertColumnList(fields, tablesOnly, tableRef);

        var dmlChain = CodeBlock.builder()
            .add(".insertInto($L, ", tableLocal).add(colList).add(")\n");
        var postInGuard = CodeBlock.builder();
        if (tia.list()) {
            // Bulk INSERT: per-row decode locals (if any) live inside the stream lambda body,
            // switching the lambda from single-expression form to block form when needed.
            boolean hasDecodeLocals = anyNodeIdCarrier(fields);
            if (hasDecodeLocals) {
                dmlChain.add(".valuesOfRows(in.stream()\n").indent()
                    .add(".map(row -> {\n").indent()
                    .add(buildInsertDecodeLocals(fields, "row", "insertKey", tablesOnly, tableRef))
                    .add("return $T.row(\n", DSL).indent()
                    .add(buildPerCellValueList(fields, tablesOnly, tableRef, "row", "insertKey")).unindent()
                    .add(");\n").unindent()
                    .add("})\n")
                    .add(".toList())\n").unindent();
            } else {
                dmlChain.add(".valuesOfRows(in.stream()\n").indent()
                    .add(".map(row -> $T.row(\n", DSL).indent()
                    .add(buildPerCellValueList(fields, tablesOnly, tableRef, "row", "insertKey")).unindent()
                    .add("))\n")
                    .add(".toList())\n").unindent();
            }
        } else {
            postInGuard.add(buildInsertDecodeLocals(fields, "in", "insertKey", tablesOnly, tableRef));
            dmlChain.add(".values(\n").indent()
                .add(buildPerCellValueList(fields, tablesOnly, tableRef, "in", "insertKey")).unindent()
                .add(")\n");
        }

        return buildDmlFetcher(ctx, f, f.returnExpression(), f.errorChannel(),
            tia.name(), tableRef, tablesOnly, tableLocal,
            outputPackage, dmlChain.build(),
            f.dialectRequirement(), postInGuard.build(), tia.list(), row, carrierDsl);
    }

    /**
     * True iff any field on {@code fields} bears a {@link CallSiteExtraction.NodeIdDecodeKeys}
     * carrier: a {@link InputField.ColumnBackedField} or {@link InputField.ColumnBackedReferenceField}
     * with NodeId extraction (which every composite instance carries by construction). Drives the
     * bulk-INSERT / bulk-UPSERT lambda shape choice (single-expression vs block-with-decode-locals).
     */
    private static boolean anyNodeIdCarrier(List<InputField> fields) {
        // Descend nested grouping inputs; a NodeId leaf anywhere drives the block-lambda shape.
        for (var leaf : flattenInsertLeaves(fields, List.of())) {
            var f = leaf.field();
            if (f instanceof InputField.ColumnBackedField cf
                && cf.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys) return true;
            if (f instanceof InputField.ColumnBackedReferenceField crf
                && crf.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys) return true;
        }
        return false;
    }

    /**
     * Per-cell missing-vs-null dispatch: emits one ternary expression per column at the supplied
     * map-local (e.g. {@code in} for single-row, {@code row} for the bulk-stream lambda).
     * Absent key → {@code DSL.defaultValue(dataType)} (jOOQ renders {@code DEFAULT}); present key
     * (including explicit null) → {@code DSL.val(map.get("name"), dataType)} (typed bind).
     * Comma-separated, newline-terminated per cell so the formatted output is readable.
     *
 * <p>Dispatches on carrier identity and extraction:
     * <ul>
     *   <li>{@link InputField.ColumnBackedField} with {@link CallSiteExtraction.Direct} (or
     *       non-NodeId extraction) — one cell, value read directly from
     *       {@code mapLocal.get(name)}.</li>
     *   <li>{@link InputField.ColumnBackedField} with {@link CallSiteExtraction.NodeIdDecodeKeys}
     *       — N cells (one per column, a single cell at arity 1), values read from the
     *       per-record decode local ({@code insertKey_<fi>.value1()..value<N>()}). Caller must
     *       declare the decode local; see {@link #buildInsertDecodeLocals}.</li>
     * </ul>
     */
    private static CodeBlock buildPerCellValueList(
            List<InputField> fields,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef,
            String mapLocal,
            String localPrefix) {
        // An INSERT with a column written by more than one carrier dedups to one cell per column,
        // coalescing over the present writers via the per-column cell local emitted in the decode-locals
        // prep. A non-overlapping INSERT keeps the one-leaf-one-cell walk below (byte-identical).
        if (hasInsertOverlap(fields)) {
            return buildPerCellValueListDeduped(fields, tablesOnly, tableRef, mapLocal, localPrefix);
        }
        var b = CodeBlock.builder();
        boolean first = true;
        // Descend nested grouping inputs to a flat leaf list; each leaf carries its wire access
        // path (a single name for a top-level field, byte-identical to the non-nested case). The presence
        // test and value read use the path, honoring the absent-vs-null contract: an absent leaf (or
        // an absent / non-Map outer level) resolves to DEFAULT; a present leaf (including explicit
        // null) binds the typed value.
        var leaves = flattenInsertLeaves(fields, List.of());
        for (int fi = 0; fi < leaves.size(); fi++) {
            var f = leaves.get(fi).field();
            var path = leaves.get(fi).path();
            var presence = nestedContainsKeyExpr(mapLocal, path, "ic" + fi);
            switch (f) {
                case InputField.ColumnBackedField cf -> {
                    if (cf.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys) {
                        // NodeId-decoded write: one cell per column reading the decode local's
                        // positional accessors — an arity-uniform loop, value1() alone at arity 1.
                        String recLocal = localPrefix + "_" + fi;
                        for (int ci = 0; ci < cf.columns().size(); ci++) {
                            var col = cf.columns().get(ci);
                            if (!first) b.add(",\n");
                            first = false;
                            b.add("$L ? $T.val($L.value$L(), $T.$L.$L.getDataType()) : $T.defaultValue($T.$L.$L.getDataType())",
                                presence,
                                DSL, recLocal, ci + 1,
                                tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName(),
                                DSL,
                                tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
                        }
                    } else {
                        // Direct implies arity 1 (the carrier's constructor invariant).
                        if (!first) b.add(",\n");
                        first = false;
                        b.add("$L ? $T.val($L, $T.$L.$L.getDataType()) : $T.defaultValue($T.$L.$L.getDataType())",
                            presence,
                            DSL, ArgCallEmitter.nestedMapValueExpr(mapLocal, path),
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.columns().get(0).javaName(),
                            DSL,
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.columns().get(0).javaName());
                    }
                }
                case InputField.ColumnBackedReferenceField crf -> {
                    // FK-target reference; same per-slot shape as the NodeId-decoded value
                    // carrier, but walks the binding's own-table tuple (the input's own FK columns,
                    // permuted into NodeType key order) instead of columns().
                    String recLocal = localPrefix + "_" + fi;
                    var refColumns = localColumnsOf(crf);
                    for (int ci = 0; ci < refColumns.size(); ci++) {
                        var col = refColumns.get(ci);
                        if (!first) b.add(",\n");
                        first = false;
                        b.add("$L ? $T.val($L.value$L(), $T.$L.$L.getDataType()) : $T.defaultValue($T.$L.$L.getDataType())",
                            presence,
                            DSL, recLocal, ci + 1,
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName(),
                            DSL,
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
                    }
                }
                default -> throw new IllegalStateException(
                    "INSERT cell-list dispatch reached unsupported carrier: "
                    + f.getClass().getSimpleName() + "; classifier should have rejected this");
            }
        }
        return b.build();
    }

    /**
     * Builds the INSERT column list by walking {@code tia.fields()} and dispatching on carrier:
     * {@link InputField.ColumnBackedField} contributes one column ref per {@code columns()} slot
     * (in declaration order); the reference carrier contributes its lifted FK columns.
     */
    private static CodeBlock buildInsertColumnList(
            List<InputField> fields,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef) {
        // An INSERT with an overlapping column emits that column once (the dedup that turns the
        // Postgres "column specified more than once" crash into one column + one coalesced cell). A
        // non-overlapping INSERT keeps the one-leaf-one-column walk below (byte-identical).
        if (hasInsertOverlap(fields)) {
            return buildInsertColumnListDeduped(fields, tablesOnly, tableRef);
        }
        var b = CodeBlock.builder();
        boolean first = true;
        // Nested grouping inputs flatten in place; the column order matches the flattened
        // VALUES cell order in buildPerCellValueList by construction (both walk flattenInsertLeaves).
        for (var leaf : flattenInsertLeaves(fields, List.of())) {
            var f = leaf.field();
            switch (f) {
                case InputField.ColumnBackedField cf -> {
                    for (var col : cf.columns()) {
                        if (!first) b.add(", ");
                        first = false;
                        b.add("$T.$L.$L",
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
                    }
                }
                case InputField.ColumnBackedReferenceField crf -> {
                    for (var col : localColumnsOf(crf)) {
                        if (!first) b.add(", ");
                        first = false;
                        b.add("$T.$L.$L",
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
                    }
                }
                default -> throw new IllegalStateException(
                    "INSERT column-list dispatch reached unsupported carrier: "
                    + f.getClass().getSimpleName() + "; classifier should have rejected this");
            }
        }
        return b.build();
    }

    /** The INSERT column list driven off {@link #insertColumnPlan}, one entry per distinct column
     *  (a shared column appears once). Used only when {@link #hasInsertOverlap}. */
    private static CodeBlock buildInsertColumnListDeduped(List<InputField> fields,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef) {
        var b = CodeBlock.builder();
        boolean first = true;
        for (var oc : insertColumnPlan(fields)) {
            if (!first) b.add(", ");
            first = false;
            b.add("$T.$L.$L", tablesOnly.tablesClass(), tableRef.javaFieldName(), oc.column().javaName());
        }
        return b.build();
    }

    /** The VALUES cell list driven off {@link #insertColumnPlan}, one cell per distinct column. A
     *  shared column emits its pre-built coalesce local ({@code <prefix>Cell<ci>}, declared by
     *  {@link #emitInsertAgreementPrep}); a disjoint column keeps the existing one-leaf-one-cell shape. */
    private static CodeBlock buildPerCellValueListDeduped(List<InputField> fields,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef, String mapLocal, String localPrefix) {
        var b = CodeBlock.builder();
        var plan = insertColumnPlan(fields);
        boolean first = true;
        for (int ci = 0; ci < plan.size(); ci++) {
            var oc = plan.get(ci);
            if (!first) b.add(",\n");
            first = false;
            if (oc.shared()) {
                b.add("$L", localPrefix + "Cell" + ci);
            } else {
                emitInsertCell(b, oc.contributors().get(0), mapLocal, localPrefix, tablesOnly, tableRef);
            }
        }
        return b.build();
    }

    /** Emits one disjoint-column VALUES cell, reproducing the per-carrier shape of
     *  {@link #buildPerCellValueList}: a decode reads {@code <prefix>_<fi>.value<slot+1>()}, a plain field
     *  reads the (possibly nested) map value; absent → {@code DSL.defaultValue}, present → typed bind. */
    private static void emitInsertCell(CodeBlock.Builder b, ColumnOverlap.Contributor c, String mapLocal,
            String localPrefix, GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef) {
        var v = (SetGroupWriter) c.writer();
        var path = v.group().accessPath();
        CodeBlock presence = nestedContainsKeyExpr(mapLocal, path, "ic" + v.index());
        var col = c.column();
        if (c.writer().decode()) {
            b.add("$L ? $T.val($L_$L.value$L(), $T.$L.$L.getDataType()) : $T.defaultValue($T.$L.$L.getDataType())",
                presence, DSL, localPrefix, v.index(), c.slot() + 1,
                tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName(),
                DSL, tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
        } else {
            b.add("$L ? $T.val($L, $T.$L.$L.getDataType()) : $T.defaultValue($T.$L.$L.getDataType())",
                presence, DSL, ArgCallEmitter.nestedMapValueExpr(mapLocal, path),
                tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName(),
                DSL, tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
        }
    }

    /**
     * Builds the per-record NodeId decode locals for an INSERT/UPSERT INSERT-arm. For each
     * NodeId-bearing carrier ({@link InputField.ColumnBackedField} /
     * {@link InputField.ColumnBackedReferenceField} with {@link CallSiteExtraction.NodeIdDecodeKeys}),
     * emits one {@code Record<N> insertKey_<fi> = ...} local reading from {@code mapLocal}.
     * Locals are conditional on the source key's presence so an absent key (DEFAULT-resolved
     * cell) does not force a decode; null returns on a present key throw
     * {@code GraphqlErrorException}, mirroring the lookup-WHERE null handling.
     */
    private static CodeBlock buildInsertDecodeLocals(
            List<InputField> fields,
            String mapLocal,
            String localPrefix,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef) {
        var locals = CodeBlock.builder();
        ClassName graphqlErr = ClassName.get("graphql", "GraphqlErrorException");
        // Flat leaf order matches buildPerCellValueList so the decode-local index lines up; a
        // nested NodeId leaf reads its wire value via the null-safe descent over its access path.
        var leaves = flattenInsertLeaves(fields, List.of());
        for (int fi = 0; fi < leaves.size(); fi++) {
            var f = leaves.get(fi).field();
            var path = leaves.get(fi).path();
            CallSiteExtraction.NodeIdDecodeKeys nidk = switch (f) {
                case InputField.ColumnBackedField cf when cf.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys n -> n;
                case InputField.ColumnBackedReferenceField crf when crf.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys n -> n;
                default -> null;
            };
            if (nidk == null) continue;
            String sourceField = f.name();
            String recLocal = localPrefix + "_" + fi;
            ClassName encoderClass = nidk.decodeMethod().encoderClass();
            String methodName = nidk.decodeMethod().methodName();
            TypeName recordType = nidk.decodeMethod().returnType();
            locals.addStatement("$T $L = ($L instanceof $T _s$L) ? $T.$L(_s$L) : null",
                recordType, recLocal, ArgCallEmitter.nestedMapValueExpr(mapLocal, path),
                String.class, recLocal, encoderClass, methodName, recLocal);
            locals.beginControlFlow("if ($L && $L == null)",
                    nestedContainsKeyExpr(mapLocal, path, "id" + fi), recLocal)
                .addStatement("throw $T.newErrorException().message($S).build()", graphqlErr,
                    "Decoded NodeId did not match the expected type for input field '" + sourceField + "'")
                .endControlFlow();
        }
        // For any column written by more than one carrier, emit the value-agreement prep here (in
        // the same scope as the decode locals, before the VALUES cells read them). Empty when there is no
        // overlap, so a non-overlapping INSERT's prep is byte-identical to the decode-locals-only form.
        emitInsertAgreementPrep(locals, fields, mapLocal, localPrefix, tablesOnly, tableRef);
        return locals.build();
    }

    /**
     * The per-column overlap plan for an INSERT. Adapts each {@code SetField} leaf
     * (descending {@link InputField.NestingField} via {@link #flattenInsertLeaves}) into a
     * {@link SetGroupWriter} carrying its leaf index (the decode-local suffix {@link #buildInsertDecodeLocals}
     * emits, {@code <prefix>_<fi>}) and feeds them to the shared {@link ColumnOverlap#groupByColumn}. The two
     * INSERT walks ({@link #buildInsertColumnList}, {@link #buildPerCellValueList}) and the agreement prep all
     * derive from this one deterministic plan, so the column list, the VALUES cells, and the per-column cell
     * locals stay positionally aligned by construction.
     */
    private static List<SetGroupWriter> insertSetGroupWriters(List<InputField> fields) {
        var leaves = flattenInsertLeaves(fields, List.of());
        var out = new ArrayList<SetGroupWriter>();
        for (int fi = 0; fi < leaves.size(); fi++) {
            var f = leaves.get(fi).field();
            var path = leaves.get(fi).path();
            if (!(f instanceof InputField.SetField sf)) {
                continue;
            }
            out.add(new SetGroupWriter(fi,
                new SetGroup(sf.name(), setFieldColumns(sf), setFieldNodeIdExtraction(sf), path)));
        }
        return out;
    }

    private static List<OverlapColumn> insertColumnPlan(List<InputField> fields) {
        return ColumnOverlap.groupByColumn(insertSetGroupWriters(fields));
    }

    /** True when some backing column on the INSERT plan is written by more than one carrier.*/
    private static boolean hasInsertOverlap(List<InputField> fields) {
        return insertColumnPlan(fields).stream().anyMatch(OverlapColumn::shared);
    }

    /**
     * Emits the agreement prep for every shared column on the INSERT plan. For each, a
     * {@code List<Object>} gathers the present writers' values (presence-guarded, so an omitted writer
     * cannot conflict), {@code requireColumnAgreement} pairwise-checks them against the first present
     * (coerced through the column's {@code DataType}), and a {@code Field<?> <prefix>Cell<ci>} local
     * coalesces to {@code DSL.val} of the first present value or {@code DSL.defaultValue} when none is
     * present. {@link #buildPerCellValueList} then emits that one local as the column's single VALUES cell.
     * An overlap reaching here always has at least one decode (the all-plain overlap is the validate-time
     * reject), so a {@code NodeIdEncoder} class is always available.
     */
    private static void emitInsertAgreementPrep(CodeBlock.Builder locals, List<InputField> fields,
            String mapLocal, String localPrefix,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef) {
        var fieldCn = ClassName.get("org.jooq", "Field");
        var plan = insertColumnPlan(fields);
        for (int ci = 0; ci < plan.size(); ci++) {
            var oc = plan.get(ci);
            if (!oc.shared()) {
                continue;
            }
            var col = oc.column();
            String listName = localPrefix + "Agree" + ci;
            String cellName = localPrefix + "Cell" + ci;
            String label = "input fields " + oc.contributors().stream()
                .map(c -> "'" + c.writer().label() + "'")
                .distinct()
                .collect(java.util.stream.Collectors.joining(", "));
            ClassName encoderClass = oc.contributors().stream()
                .map(c -> ((SetGroupWriter) c.writer()).group())
                .filter(g -> g.nidk() != null)
                .map(g -> g.nidk().decodeMethod().encoderClass())
                .findFirst().orElseThrow();
            locals.addStatement("$T<$T> $L = new $T<>()",
                ClassName.get(List.class), Object.class, listName, ClassName.get(ArrayList.class));
            int wi = 0;
            // The present-writer gather flows through the shared value-read seam
            // (appendAgreementValue); the INSERT decode local <prefix>_<fi> is non-null whenever present
            // (buildInsertDecodeLocals throws on a present-but-mismatched id before this prep runs), so the
            // seam's extra `&& decodeLocal != null` guard is always-true here.
            for (var c : oc.contributors()) {
                var v = (SetGroupWriter) c.writer();
                appendAgreementValue(locals, v.group(), c.slot(), mapLocal,
                    localPrefix + "_" + v.index(), listName, "ag" + ci + "w" + (wi++));
            }
            String idx = listName + "Idx";
            locals.beginControlFlow("for (int $L = 1; $L < $L.size(); $L++)", idx, idx, listName, idx)
                .addStatement("$T.requireColumnAgreement($S, $T.$L.$L.getDataType(), $L.get(0), $L.get($L))",
                    encoderClass, label, tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName(),
                    listName, listName, idx)
                .endControlFlow();
            // The cell is typed to the column's Java type (Field<ColType>, not Field<?>) so it matches the
            // typed .values(Field<T1>, ...) overload the deduped INSERT column list produces.
            var cellType = ParameterizedTypeName.get(fieldCn, col.columnType());
            locals.addStatement("$T $L", cellType, cellName);
            locals.beginControlFlow("if ($L.isEmpty())", listName)
                .addStatement("$L = $T.defaultValue($T.$L.$L.getDataType())",
                    cellName, DSL, tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName())
                .nextControlFlow("else")
                .addStatement("$L = $T.val($L.get(0), $T.$L.$L.getDataType())",
                    cellName, DSL, listName, tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName())
                .endControlFlow();
        }
    }

    /**
 * Target columns a {@code SetField} carrier writes to on the input's own table. The
     * walk is uniform across both admissible SetField shapes: the value carrier sources from
     * {@code columns()}, the reference carrier from its {@link FilterBinding.Local} tuple.
     */
    private static List<no.sikt.graphitron.rewrite.model.ColumnRef> setFieldColumns(InputField.SetField sf) {
        return switch (sf) {
            case InputField.ColumnBackedField cf -> cf.columns();
            case InputField.ColumnBackedReferenceField crf -> localColumnsOf(crf);
        };
    }

    /**
     * The own-table column tuple a reference carrier writes to. Every write-side emitter reads its
     * columns through here, so the {@link FilterBinding.Remote} case has exactly one place to fail:
     * a remote-bound carrier identifies its target through a join and owns no column on this table,
     * which the write rails' gates ({@code MutationInputResolver.admitMutationInputFields},
     * {@code UpdateRowsWalker.classifyInto}, {@code DeleteRowsWalker.classifyInto}) reject before any
     * emitter runs. Reaching here means a gate was bypassed, so it throws rather than inventing a
     * tuple and emitting a silently wrong statement.
     */
    private static List<no.sikt.graphitron.rewrite.model.ColumnRef> localColumnsOf(
            InputField.ColumnBackedReferenceField crf) {
        return switch (crf.binding()) {
            case FilterBinding.Local(var ownTableColumns) -> ownTableColumns;
            case FilterBinding.Remote ignored -> throw new IllegalStateException(
                "write-side emit reached the remote-bound reference carrier '" + crf.name()
                + "'; the rail's validate-time gate should have rejected it");
        };
    }

    /**
 * NodeId decode extraction for a {@code SetField} carrier, or {@code null} when the
     * value is read raw from the input map. Drives whether the SET-emitter site declares a
     * per-field decode local and reads {@code .value<i+1>()} for each slot, or reads
     * {@code map.get(name)} verbatim.
     */
    private static CallSiteExtraction.NodeIdDecodeKeys setFieldNodeIdExtraction(InputField.SetField sf) {
        return switch (sf) {
            case InputField.ColumnBackedField cf
                when cf.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys n -> n;
            case InputField.ColumnBackedReferenceField crf
                when crf.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys n -> n;
            default -> null;
        };
    }

    /**
 * A SET-side input field reduced to what the SET emitter needs — the SDL field name (the
     * leaf Map key), its target columns on the input's own table, the NodeId decode extraction (or
     * {@code null} for a raw map read), and the nested-input wire access path. This is the carrier-driven
     * analogue of an {@code InputField.SetField}; the UPDATE walker carrier names the partition
     * directly, so the SET emitters consume these groups rather than re-deriving columns from
     * {@code InputField}.
     *
     * <p>{@code accessPath} is the SDL key chain from the argument-value root map to the leaf:
     * {@code [name]} for a top-level field (the emit reads / presence-checks {@code map.get(name)},
     * byte-identical to the non-nested case) or a multi-segment path for a leaf in a nested grouping input
     * (the emit descends the wire map, honoring absent-vs-null at every layer).
     */
    private record SetGroup(String name, List<ColumnRef> columns,
                            CallSiteExtraction.NodeIdDecodeKeys nidk, List<String> accessPath) {}

    /**
     * Adapts a {@link SetGroup} (with its stable {@code index}, used to name the per-group decode
     * local) into the shared {@link ColumnOverlap.ColumnWriter} view. The INSERT plan, the two
     * UPDATE-SET plan sites, and the cross-partition preamble build these and feed them to
     * {@link ColumnOverlap#groupByColumn} / the value-read seam. The emission downcasts
     * {@code Contributor.writer()} back to this view to reach the wrapped {@link SetGroup} (for the
     * value-read seam, which takes a {@code SetGroup}) and the {@code index} (the decode-local suffix). The
     * {@code columns()} order is the decode-record slot order, satisfying the {@link ColumnOverlap.ColumnWriter}
     * invariant.
     */
    private record SetGroupWriter(int index, SetGroup group) implements ColumnOverlap.ColumnWriter {
        @Override public List<ColumnRef> targetColumns() { return group.columns(); }
        @Override public boolean decode() { return group.nidk() != null; }
        @Override public String label() { return String.join(".", group.accessPath()); }
    }

    /** The {@link SetGroupWriter} views for an UPDATE-SET plan, one per {@link SetGroup}, the view's
     *  {@code index} being its position in {@code setGroups} (the decode-local suffix sites 4 / 5 / 6 use). */
    private static List<SetGroupWriter> setGroupWriters(List<SetGroup> setGroups) {
        var out = new ArrayList<SetGroupWriter>();
        for (int gi = 0; gi < setGroups.size(); gi++) {
            out.add(new SetGroupWriter(gi, setGroups.get(gi)));
        }
        return out;
    }

    /**
     * Adapt a {@code List<InputField.SetField>} (the payload-returning DML record fetchers carry
     * a {@code TableInputArg}) into the {@link SetGroup} shape the SET emitters consume. The
     * carrier-driven UPDATE path uses {@link #setGroupsOf} instead.
     * {@code tia.setFields()} is always empty in the current model, so this never sees nested input.
     */
    private static List<SetGroup> setGroupsOfFields(List<InputField.SetField> setFields) {
        var out = new ArrayList<SetGroup>();
        for (var sf : setFields) {
            out.add(new SetGroup(sf.name(), setFieldColumns(sf), setFieldNodeIdExtraction(sf), List.of(sf.name())));
        }
        return out;
    }

    // ---- Nested-input wire-access helpers ----------------------------------------------------
    //
    // A leaf flattened out of a NestingField carries a CallSiteExtraction.NestedInputField whose
    // path() is the SDL key chain from the @table argument root to the leaf. A top-level leaf
    // carries its plain extraction (Direct / NodeIdDecodeKeys) and the access path is just its own
    // SDL field name, so every emit site below collapses to byte-identical output for the
    // non-nested case.

    /** The wire access path for a leaf carrier: a nested leaf's {@code NestedInputField.path()},
     *  or {@code [sdlName]} for a top-level leaf. */
    private static List<String> accessPathOf(String sdlName, CallSiteExtraction extraction) {
        return extraction instanceof CallSiteExtraction.NestedInputField nif
            ? nif.path() : List.of(sdlName);
    }

    /** The real leaf extraction behind a (possibly nested) carrier extraction — {@code Direct} /
     *  {@code NodeIdDecodeKeys} once the {@code NestedInputField} envelope is peeled. */
    private static CallSiteExtraction leafExtractionOf(CallSiteExtraction extraction) {
        return extraction instanceof CallSiteExtraction.NestedInputField nif
            ? nif.leaf() : extraction;
    }

    /**
     * Null-safe presence test for a leaf at {@code accessPath} under a map-typed local. Single
     * segment → {@code mapLocal.containsKey(key)} (byte-identical). Deeper → an {@code instanceof
     * Map<?, ?>} chain over the prefix levels ending in {@code containsKey} on the leaf's parent
     * map, so an absent key or a non-{@code Map} (including {@code null}) at any layer reads as
     * absent. {@code salt} uniquifies the pattern variables across peer expressions.
     */
    private static CodeBlock nestedContainsKeyExpr(String mapLocal, List<String> accessPath, String salt) {
        if (accessPath.size() == 1) {
            return CodeBlock.of("$L.containsKey($S)", mapLocal, accessPath.get(0));
        }
        var b = CodeBlock.builder();
        int last = accessPath.size() - 1;
        String cur = mapLocal;
        for (int d = 0; d < last; d++) {
            String inner = "cm" + salt + "_" + d;
            b.add("$L.get($S) instanceof $T<?, ?> $L && ", cur, accessPath.get(d), Map.class, inner);
            cur = inner;
        }
        b.add("$L.containsKey($S)", cur, accessPath.get(last));
        return b.build();
    }

    /** Leaf body for {@link #emitNestedPresenceGuardedLeaf}: emits the write(s) for the leaf, given
     *  the innermost descended map local and the leaf's own SDL key. */
    @FunctionalInterface
    private interface NestedLeafBody {
        void emit(String innerMapLocal, String leafKey);
    }

    /**
     * Emit a presence-guarded leaf write, descending {@code accessPath} from {@code rootMapLocal}.
     * Single segment → {@code if (root.containsKey(key)) { body(root, key) }} (byte-identical to the
     * non-nested SET put). Deeper → nested {@code if (containsKey) { var o = get; if (o instanceof
     * Map<?, ?> m) { ... } }} guards honoring the absent-vs-null contract: an absent key or a
     * non-{@code Map} outer value (including an explicit {@code null}) skips the whole subtree; at
     * the leaf, {@code containsKey} decides whether the column is written and the value (which may
     * be {@code null}) decides what it is written to. {@code uid} uniquifies the descent locals.
     */
    private static void emitNestedPresenceGuardedLeaf(
            CodeBlock.Builder block, String rootMapLocal, List<String> accessPath,
            String uid, NestedLeafBody body) {
        emitNestedDescend(block, rootMapLocal, accessPath, 0, uid, body);
    }

    private static void emitNestedDescend(
            CodeBlock.Builder block, String mapLocal, List<String> path, int depth,
            String uid, NestedLeafBody body) {
        String key = path.get(depth);
        block.beginControlFlow("if ($L.containsKey($S))", mapLocal, key);
        if (depth == path.size() - 1) {
            body.emit(mapLocal, key);
        } else {
            String obj = "outerVal_" + uid + "_" + depth;
            String inner = "grpMap_" + uid + "_" + depth;
            block.addStatement("Object $L = $L.get($S)", obj, mapLocal, key);
            block.beginControlFlow("if ($L instanceof $T<?, ?> $L)", obj, Map.class, inner);
            emitNestedDescend(block, inner, path, depth + 1, uid, body);
            block.endControlFlow();
        }
        block.endControlFlow();
    }

    /** A leaf carrier flattened out of (possibly) a {@link InputField.NestingField}, paired
     *  with its wire access path. Produced by {@link #flattenInsertLeaves} so the INSERT emitters
     *  walk a flat leaf list (never a {@code NestingField}) with a per-leaf descent path. */
    private record InsertLeaf(InputField field, List<String> path) {}

    /**
     * Flatten {@code fields} into leaf carriers in declaration order, descending into any
 * {@link InputField.NestingField} grouping input and accumulating the SDL access path.
     * A top-level leaf gets path {@code [name]}; a nested leaf gets the full key chain. The INSERT
     * column-list / VALUES / decode-local emitters all walk this one flat list so a leaf's index
     * (used to name its decode local) is consistent across them.
     */
    private static List<InsertLeaf> flattenInsertLeaves(List<InputField> fields, List<String> prefix) {
        var out = new ArrayList<InsertLeaf>();
        for (var f : fields) {
            if (f instanceof InputField.NestingField nf) {
                var child = new ArrayList<>(prefix);
                child.add(nf.name());
                out.addAll(flattenInsertLeaves(nf.fields(), child));
            } else {
                var path = new ArrayList<>(prefix);
                path.add(f.name());
                out.add(new InsertLeaf(f, path));
            }
        }
        return out;
    }

    /**
 * Project the UPDATE carrier's flat {@link SetColumn} list back into per-field
     * {@link SetGroup}s, grouping by wire access path in encounter order. A composite-NodeId field
     * contributes several {@code SetColumn}s sharing one path; they regroup into one
     * {@code SetGroup} whose columns line up positionally with the decode {@code Record<N>} slots.
     *
 * <p>Grouping is by access path (the leaf's {@code NestedInputField.path()} or
     * {@code [sdlFieldName]} for a top-level leaf) rather than by SDL field name, so two leaves with
     * the same local name under different nested groups stay distinct; the leaf extraction is peeled
     * out of the {@code NestedInputField} envelope before the NodeId check. For top-level leaves the
     * path is {@code [name]}, so the grouping and the resulting {@code SetGroup}s are byte-identical
     * to the by-name grouping.
     */
    private static List<SetGroup> setGroupsOf(List<SetColumn> setColumns) {
        var byPath = new java.util.LinkedHashMap<List<String>, List<SetColumn>>();
        for (var sc : setColumns) {
            byPath.computeIfAbsent(accessPathOf(sc.sdlFieldName(), sc.extraction()), k -> new ArrayList<>()).add(sc);
        }
        var out = new ArrayList<SetGroup>();
        for (var e : byPath.entrySet()) {
            var path = e.getKey();
            var cols = e.getValue().stream().map(SetColumn::targetColumn).toList();
            var leafExtraction = leafExtractionOf(e.getValue().get(0).extraction());
            var nidk = leafExtraction instanceof CallSiteExtraction.NodeIdDecodeKeys n ? n : null;
            out.add(new SetGroup(path.get(path.size() - 1), cols, nidk, path));
        }
        return out;
    }

    /**
 * Project the UPDATE carrier's flat {@link KeyColumn} list into the
     * {@link InputColumnBindingGroup}s the lookup-WHERE emitters consume, grouping by wire access
     * path in encounter order. A single-column field becomes a {@code MapGroup} (carrying its
     * extraction so an arity-1 NodeId still routes through the decode local); a multi-column field
     * becomes a {@code DecodedRecordGroup} whose positional {@code RecordBinding}s mirror the decode
     * {@code Record<N>} slots. This reconstructs exactly the {@code fieldBindings()} shape the legacy
     * {@code TableInputArg} produced for the WHERE half.
     *
 * <p>Grouping is by access path (same rationale as {@link #setGroupsOf}). A
     * {@code MapGroup}'s binding keeps the full extraction (the value-read emitter peels the path);
     * a {@code DecodedRecordGroup} peels the leaf {@code NodeIdDecodeKeys} for the decode call and
     * carries the access path explicitly so the decode-local read descends a nested composite key.
     */
    private static List<InputColumnBindingGroup> keyGroupsOf(List<KeyColumn> keyColumns) {
        var byPath = new java.util.LinkedHashMap<List<String>, List<KeyColumn>>();
        for (var kc : keyColumns) {
            byPath.computeIfAbsent(accessPathOf(kc.sdlFieldName(), kc.extraction()), k -> new ArrayList<>()).add(kc);
        }
        var out = new ArrayList<InputColumnBindingGroup>();
        for (var e : byPath.entrySet()) {
            var path = e.getKey();
            var group = e.getValue();
            if (group.size() == 1) {
                var kc = group.get(0);
                out.add(new InputColumnBindingGroup.MapGroup(List.of(
                    new InputColumnBinding.MapBinding(kc.sdlFieldName(), kc.targetColumn(), kc.extraction()))));
            } else {
                var bindings = new ArrayList<InputColumnBinding.RecordBinding>();
                for (int i = 0; i < group.size(); i++) {
                    bindings.add(new InputColumnBinding.RecordBinding(i, group.get(i).targetColumn()));
                }
                out.add(new InputColumnBindingGroup.DecodedRecordGroup(
                    path.get(path.size() - 1),
                    (CallSiteExtraction.NodeIdDecodeKeys) leafExtractionOf(group.get(0).extraction()),
                    bindings, path));
            }
        }
        return out;
    }

    /**
 * Emits {@code Map<Field<?>, Object>} {@code.put(t.col, DSL.val(value, t.col.getDataType()))}
     * statements for each {@code SetField} on {@code setFields}, guarded by a presence check on
     * the SDL field name. The walk is uniform across the four admissible SetField shapes:
     *
     * <ul>
     *   <li>Direct {@link InputField.ColumnBackedField} — one {@code put}; value reads
     *       {@code mapLocal.get(name)}.</li>
     *   <li>NodeId-decoded arity-1 {@link InputField.ColumnBackedField} — one {@code put};
     *       declares a per-field decode local inside the conditional and reads
     *       {@code decodeLocal.value1()}.</li>
     *   <li>Composite {@link InputField.ColumnBackedField} — N {@code put}s (one per slot);
     *       declares a per-field decode local and reads {@code decodeLocal.value<i+1>()} for
     *       slot i.</li>
     *   <li>{@link InputField.ColumnBackedReferenceField} (either arity) — same as the
     *       same-table NodeId arms but target columns come from the carrier's
     *       {@link FilterBinding.Local} binding (FK columns on the input's own table) rather than
     *       {@code columns()}.</li>
     * </ul>
     *
     * <p>{@code presenceLocal} is the local consulted by {@code containsKey} / {@code contains}
     * (e.g. {@code "in"} for single-row Map, {@code "firstKeys"} for bulk uniform-shape gate);
     * {@code presenceCall} is the method invoked on it ({@code "containsKey"} for Map,
     * {@code "contains"} for Set). For Maps, both the gate and the value-read use the same map;
     * for Sets, the gate uses the set and the value-read uses a separate map local
     * {@code valueMapLocal}.
     */
    private static void emitSetMapPuts(
            CodeBlock.Builder block,
            List<SetGroup> setFields,
            String setsLocal,
            String presenceLocal,
            String valueMapLocal,
            String decodeLocalPrefix,
            GeneratorUtils.ResolvedTableNames tablesOnly,
            TableRef tableRef) {
        // emitSetMapPuts is always called with presenceLocal == valueMapLocal (single-row "in" or
        // per-row "row" Map); the nested-descent walk uses that one Map local as both the
        // presence and value root, honoring absent-vs-null at every nesting layer.
        String root = valueMapLocal;
        for (int sfi = 0; sfi < setFields.size(); sfi++) {
            var sf = setFields.get(sfi);
            var cols = sf.columns();
            var nidk = sf.nidk();
            String recLocal = nidk != null ? decodeLocalPrefix + "_" + sfi : null;
            emitNestedPresenceGuardedLeaf(block, root, sf.accessPath(), decodeLocalPrefix + sfi,
                (innerMap, leafKey) -> {
                    if (nidk != null) {
                        appendDecodeLocal(block, recLocal, nidk, innerMap, leafKey);
                    }
                    for (int ci = 0; ci < cols.size(); ci++) {
                        var col = cols.get(ci);
                        if (nidk != null) {
                            block.addStatement(
                                "$L.put($T.$L.$L, $T.val($L.value$L(), $T.$L.$L.getDataType()))",
                                setsLocal,
                                tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName(),
                                DSL, recLocal, ci + 1,
                                tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
                        } else {
                            block.addStatement(
                                "$L.put($T.$L.$L, $T.val($L.get($S), $T.$L.$L.getDataType()))",
                                setsLocal,
                                tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName(),
                                DSL, innerMap, leafKey,
                                tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
                        }
                    }
                });
        }
    }

    /**
     * On the single-row UPDATE SET path: emits the value-agreement preamble for every SET
     * column written by more than one carrier where at least one is a {@code @nodeId} decode (the
     * all-plain SET overlap is the validate-time {@link no.sikt.graphitron.rewrite.walker.UpdateRowsWalker} reject, so it never reaches
     * here). Without this, the single-row {@code emitSetMapPuts} would silently last-write-wins through
     * {@code Map.put}. Emits nothing when there is no decode-involving SET overlap, so a non-overlapping
     * UPDATE's SET emission is byte-identical.
     *
     * <p>Each participating decode group is decoded once into a preamble-local record (a deliberate
     * second decode alongside {@code emitSetMapPuts}'s own, acceptable for the rare overlap and kept
     * self-contained so the existing SET emission is untouched); a present-but-mismatched id decodes to
     * {@code null} and is skipped here, with {@code emitSetMapPuts}'s decode local surfacing the mismatch
     * throw as before. For each shared column the present writers' values are gathered and pairwise-checked
     * against the first present through {@code requireColumnAgreement}, coerced via the column's DataType.
     */
    private static void emitSetAgreementPreamble(
            CodeBlock.Builder block, List<SetGroup> setGroups, String mapLocal, String decodeLocalPrefix,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef) {
        // The per-column grouping is the shared ColumnOverlap.groupByColumn; this site forks on
        // shared() && !allPlain() (a decode-involving overlap; the all-plain overlap is the upstream
        // UpdateRowsWalker reject) and routes its re-decode / present-value read through the value-read
        // seam (emitAgreementDecodeLocal / appendAgreementValue).
        var plan = ColumnOverlap.groupByColumn(setGroupWriters(setGroups));
        var sharedDecodeColumns = plan.stream().filter(oc -> oc.shared() && !oc.allPlain()).toList();
        if (sharedDecodeColumns.isEmpty()) return;

        var listCn = ClassName.get("java.util", "List");
        var arrayListCn = ClassName.get("java.util", "ArrayList");
        // One re-decode local per participating decode group (reused across its slots).
        var decodeGroups = new LinkedHashSet<Integer>();
        for (var oc : sharedDecodeColumns) {
            for (var c : oc.contributors()) {
                if (c.writer().decode()) decodeGroups.add(((SetGroupWriter) c.writer()).index());
            }
        }
        for (int gi : decodeGroups) {
            emitAgreementDecodeLocal(block, setGroups.get(gi), mapLocal, decodeLocalPrefix + "Agree_" + gi, "" + gi);
        }
        int ci = 0;
        for (var oc : sharedDecodeColumns) {
            var col = oc.column();
            String listName = decodeLocalPrefix + "SetAgree" + ci;
            String label = "input fields " + oc.contributors().stream()
                .map(c -> "'" + c.writer().label() + "'")
                .distinct()
                .collect(java.util.stream.Collectors.joining(", "));
            ClassName encoderClass = oc.contributors().stream()
                .map(c -> ((SetGroupWriter) c.writer()).group())
                .filter(g -> g.nidk() != null)
                .map(g -> g.nidk().decodeMethod().encoderClass())
                .findFirst().orElseThrow();
            block.addStatement("$T<$T> $L = new $T<>()", listCn, Object.class, listName, arrayListCn);
            int wi = 0;
            for (var c : oc.contributors()) {
                var v = (SetGroupWriter) c.writer();
                appendAgreementValue(block, v.group(), c.slot(), mapLocal,
                    decodeLocalPrefix + "Agree_" + v.index(), listName, "sa" + ci + "w" + (wi++));
            }
            String idx = listName + "Idx";
            block.beginControlFlow("for (int $L = 1; $L < $L.size(); $L++)", idx, idx, listName, idx)
                .addStatement("$T.requireColumnAgreement($S, $T.$L.$L.getDataType(), $L.get(0), $L.get($L))",
                    encoderClass, label, tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName(),
                    listName, listName, idx)
                .endControlFlow();
            ci++;
        }
    }

    /**
     * Cross-partition (WHERE∩SET) value-agreement preamble for the single-row UPDATE. A
     * self-FK {@code @reference} routes its lifted columns wholly to SET ({@code UpdateRowsWalker})
     * while the row identity comes from the WHERE (matched-key) partition, so a column the self-FK
     * shares with the identity field (e.g. {@code email.mailbox_id}: the FK
     * {@code email_in_reply_to_fk} shares {@code mailbox_id} with the PK) appears in BOTH partitions.
     * The FK constraint forces the two equal for any well-formed input, but a malformed input could
     * disagree, so this checks agreement before the DML runs. Intersecting two partitions is a
     * different operation from {@link #emitSetAgreementPreamble}'s within-clause grouping, so it
     * stays a named sibling: it adopts the shared {@link SetGroupWriter} leaf view and the
     * value-read seam ({@link #emitAgreementDecodeLocal} / {@link #appendAgreementValue}) but keeps
     * its bespoke intersection walk; a single site with no drift partner does not earn an
     * extracted {@code intersectByColumn} primitive.
     *
     * <p>For each column present in both a key group and a set group: each side is re-decoded into a
     * self-contained preamble-local record (presence-guarded; a present-but-mismatched id decodes to
     * {@code null} and is skipped here, the WHERE/SET decode locals surfacing the throw), the present
     * values gathered into a {@code List} and pairwise-checked through {@code requireColumnAgreement}
     * (coerced via the column {@code DataType}). The throw names both contributing input fields (the
     * identity field and the self-FK field), mirroring {@link #emitInsertAgreementPrep}'s label. Emits
     * nothing (byte-identical) when there is no key∩set overlap, so a non-self-FK UPDATE is untouched.
     */
    private static void emitKeySetAgreementPreamble(
            CodeBlock.Builder block, List<SetGroup> keyGroups, List<SetGroup> setGroups,
            String mapLocal, String decodeLocalPrefix,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef) {
        // Both partitions adapt into the shared SetGroupWriter leaf view; the bespoke intersection
        // then reads each view's wrapped group / index. The first key contributor per column.
        record KeyHit(SetGroupWriter writer, int slot) {}
        var keyWriters = setGroupWriters(keyGroups);
        var keyByColumn = new java.util.LinkedHashMap<String, KeyHit>();
        for (var kw : keyWriters) {
            var cols = kw.group().columns();
            for (int s = 0; s < cols.size(); s++) {
                keyByColumn.putIfAbsent(cols.get(s).sqlName(), new KeyHit(kw, s));
            }
        }
        // shared columns: a set-group column that is also a key column, carrying its key contributor and
        // its set contributor (the view + slot on each side) and the resolved column.
        record SharedHit(KeyHit key, SetGroupWriter setWriter, int setSlot, ColumnRef column) {}
        var shared = new ArrayList<SharedHit>();
        var keyDecodeGroups = new LinkedHashSet<Integer>();
        var setDecodeGroups = new LinkedHashSet<Integer>();
        for (var sw : setGroupWriters(setGroups)) {
            var cols = sw.group().columns();
            for (int setSlot = 0; setSlot < cols.size(); setSlot++) {
                var kh = keyByColumn.get(cols.get(setSlot).sqlName());
                if (kh == null) continue;
                shared.add(new SharedHit(kh, sw, setSlot, cols.get(setSlot)));
                if (kh.writer().group().nidk() != null) keyDecodeGroups.add(kh.writer().index());
                if (sw.group().nidk() != null) setDecodeGroups.add(sw.index());
            }
        }
        if (shared.isEmpty()) return;

        var listCn = ClassName.get("java.util", "List");
        var arrayListCn = ClassName.get("java.util", "ArrayList");
        // One re-decode local per participating decode group on each side (reused across its slots).
        for (int gi : keyDecodeGroups) {
            emitAgreementDecodeLocal(block, keyGroups.get(gi), mapLocal, decodeLocalPrefix + "AgreeK_" + gi, "ksaK" + gi);
        }
        for (int gi : setDecodeGroups) {
            emitAgreementDecodeLocal(block, setGroups.get(gi), mapLocal, decodeLocalPrefix + "AgreeS_" + gi, "ksaS" + gi);
        }
        for (int ci = 0; ci < shared.size(); ci++) {
            var sh = shared.get(ci);
            var keyGroup = sh.key().writer().group();
            var setGroup = sh.setWriter().group();
            var col = sh.column();
            String listName = decodeLocalPrefix + "Agree" + ci;
            String label = "input fields '" + String.join(".", keyGroup.accessPath()) + "', '"
                + String.join(".", setGroup.accessPath()) + "'";
            // The self-FK SET side always carries a @nodeId decode, so an encoder class is available;
            // fall back to the key side defensively if only it decodes.
            ClassName encoderClass = setGroup.nidk() != null
                ? setGroup.nidk().decodeMethod().encoderClass()
                : keyGroup.nidk().decodeMethod().encoderClass();
            block.addStatement("$T<$T> $L = new $T<>()", listCn, Object.class, listName, arrayListCn);
            appendAgreementValue(block, keyGroup, sh.key().slot(), mapLocal,
                decodeLocalPrefix + "AgreeK_" + sh.key().writer().index(), listName, "ksaK" + ci);
            appendAgreementValue(block, setGroup, sh.setSlot(), mapLocal,
                decodeLocalPrefix + "AgreeS_" + sh.setWriter().index(), listName, "ksaS" + ci);
            String idx = listName + "Idx";
            block.beginControlFlow("for (int $L = 1; $L < $L.size(); $L++)", idx, idx, listName, idx)
                .addStatement("$T.requireColumnAgreement($S, $T.$L.$L.getDataType(), $L.get(0), $L.get($L))",
                    encoderClass, label, tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName(),
                    listName, listName, idx)
                .endControlFlow();
        }
    }

    /** Re-decode a key/set group's {@code @nodeId} wire value into a preamble-local record,
     *  {@code null} on a present-but-mismatched id (the WHERE/SET decode local surfaces the throw).
     *  Mirrors {@link #emitSetAgreementPreamble}'s per-group re-decode. */
    private static void emitAgreementDecodeLocal(
            CodeBlock.Builder block, SetGroup group, String mapLocal, String local, String salt) {
        var nidk = group.nidk();
        block.addStatement("$T $L = ($L instanceof $T _sa$L) ? $T.$L(_sa$L) : null",
            nidk.decodeMethod().returnType(), local,
            ArgCallEmitter.nestedMapValueExpr(mapLocal, group.accessPath()), String.class, salt,
            nidk.decodeMethod().encoderClass(), nidk.decodeMethod().methodName(), salt);
    }

    /** Append one side's present value for the shared column to the agreement list. A decode
     *  group reads its record slot ({@code value<slot+1>()}) guarded on presence + a non-null decode;
     *  a plain field reads the (possibly nested) wire value guarded on presence. */
    private static void appendAgreementValue(
            CodeBlock.Builder block, SetGroup group, int slot, String mapLocal, String decodeLocal,
            String listName, String salt) {
        var presence = nestedContainsKeyExpr(mapLocal, group.accessPath(), salt);
        if (group.nidk() != null) {
            block.beginControlFlow("if ($L && $L != null)", presence, decodeLocal)
                .addStatement("$L.add($L.value$L())", listName, decodeLocal, slot + 1)
                .endControlFlow();
        } else {
            block.beginControlFlow("if ($L)", presence)
                .addStatement("$L.add($L)", listName, ArgCallEmitter.nestedMapValueExpr(mapLocal, group.accessPath()))
                .endControlFlow();
        }
    }

    /**
 * Emits the UPSERT DO-UPDATE {@code setsUpdate.put(t.col, DSL.excluded(t.col))} statements
     * for each {@code SetField}, guarded by a presence check on the SDL field name. Walks
     * {@link #setFieldColumns} so composite and reference carriers emit one entry per target
     * column.
     */
    private static void emitSetExcludedPuts(
            CodeBlock.Builder block,
            List<InputField.SetField> setFields,
            String setsLocal,
            String presenceLocal,
            String presenceCall,
            GeneratorUtils.ResolvedTableNames tablesOnly,
            TableRef tableRef) {
        for (var sf : setFields) {
            var cols = setFieldColumns(sf);
            block.beginControlFlow("if ($L.$L($S))", presenceLocal, presenceCall, sf.name());
            for (var col : cols) {
                block.addStatement(
                    "$L.put($T.$L.$L, $T.excluded($T.$L.$L))",
                    setsLocal,
                    tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName(),
                    DSL,
                    tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
            }
            block.endControlFlow();
        }
    }

    /**
     * The per-column plan for the bulk UPDATE SET clause, the SET analogue of
     * {@link #insertColumnPlan}: the shared {@link ColumnOverlap#groupByColumn} over {@link SetGroupWriter}
     * views (each carrying its source {@link SetGroup} index, the decode-local suffix). The three bulk SET
     * emitters ({@link #emitSetVColNameAdds}, {@link #emitSetBulkCellAdds}, {@link #emitSetVFieldPuts}) all
     * walk this one deterministic plan, so the {@code v(…)} column-name list, the per-row cells, and the
     * {@code sets.put} entries emit exactly one entry per distinct column and cannot drift out of positional
     * alignment. A column with two or more writers is {@code shared()}.
     */
    private static List<OverlapColumn> setColumnPlan(List<SetGroup> setGroups) {
        return ColumnOverlap.groupByColumn(setGroupWriters(setGroups));
    }

    /**
 * The first-row presence gate for a bulk-SET plan column. A disjoint column keeps its single
     * writer's gate ({@link #firstRowSetPresenceExpr}, byte-identical to the pre-dedup per-group gate); a
     * shared column's gate is the <em>disjunction</em> of its contributing writers' first-row presence, so
     * the v-column-name list, the per-row cell, and the SET-map entry all appear together iff any writer
     * is present. The uniform-shape guard makes the present-writer set uniform across rows, so projecting
     * the first row's disjunction onto every row is safe. {@code saltPrefix} uniquifies the nested
     * pattern variables across the three emitters' peer expressions.
     */
    private static CodeBlock setColumnPresenceGate(OverlapColumn sc, String saltPrefix) {
        var contributors = sc.contributors();
        if (contributors.size() == 1) {
            return firstRowSetPresenceExpr(((SetGroupWriter) contributors.get(0).writer()).group().accessPath(), saltPrefix);
        }
        var b = CodeBlock.builder();
        for (int i = 0; i < contributors.size(); i++) {
            if (i > 0) b.add(" || ");
            var path = ((SetGroupWriter) contributors.get(i).writer()).group().accessPath();
            b.add("($L)", firstRowSetPresenceExpr(path, saltPrefix + "w" + i));
        }
        return b.build();
    }

    /**
 * Appends {@code vColNames.add(t.col.getName())} for each distinct bulk-SET plan column,
     * gated on the column's first-row presence ({@link #setColumnPresenceGate}). One entry per distinct
     * column (was: one per group-column). A column already supplied by the WHERE side
     * ({@code lookupSqlNames}, the self-FK cross-partition case) is skipped here — the lookup-key v-column
     * already carries it, and re-adding would reintroduce the duplicate-{@code v}-column crash the dedup avoids.
     */
    private static void emitSetVColNameAdds(
            CodeBlock.Builder block,
            List<SetGroup> setFields,
            Set<String> lookupSqlNames,
            GeneratorUtils.ResolvedTableNames tablesOnly,
            TableRef tableRef) {
        var plan = setColumnPlan(setFields);
        for (int ci = 0; ci < plan.size(); ci++) {
            var sc = plan.get(ci);
            if (lookupSqlNames.contains(sc.column().sqlName())) {
                continue; // cross-partition: the WHERE side already added this v-column.
            }
            block.beginControlFlow("if ($L)", setColumnPresenceGate(sc, "vc" + ci));
            block.addStatement("vColNames.add($T.$L.$L.getName())",
                tablesOnly.tablesClass(), tableRef.javaFieldName(), sc.column().javaName());
            block.endControlFlow();
        }
    }

    /**
     * The bulk-UPDATE uniform-shape gate for one SET group: is the leaf present in the first input
 * row? Single segment → {@code firstKeys.contains(name)} (byte-identical). Nested →
     * a null-safe descent of {@code in.get(0)} ending in {@code containsKey} on the leaf's parent.
     * The {@link #buildUniformShapeGuard} keySet checks ensure every row agrees with the first row's
     * shape (top-level and nested), so gating the column list on the first row is safe.
     */
    private static CodeBlock firstRowSetPresenceExpr(List<String> accessPath, String salt) {
        if (accessPath.size() == 1) {
            return CodeBlock.of("firstKeys.contains($S)", accessPath.get(0));
        }
        return nestedContainsKeyExpr("in.get(0)", accessPath, salt);
    }

    /**
 * Emits the bulk-UPDATE per-row {@code cells.add(...)} list off the {@link #setColumnPlan}
     * (one cell per distinct column), guarded by the first-row presence gate
     * ({@link #setColumnPresenceGate}). Two phases share one per-row decode:
     *
     * <ol>
     *   <li><b>Decode locals</b> ({@link #emitBulkSetDecodeLocals}) — one {@code Record<N>} per
     *       NodeId-bearing {@link SetGroup}, declared once per row (INSERT-style: instanceof guard,
     *       presence-gated throw), so a composite group's columns and any shared-column gather all read the
     *       same decode, never re-decoding per writer.</li>
     *   <li><b>Cells</b> — one {@code cells.add} per plan column: a <b>disjoint</b> column reproduces the
     *       pre-dedup per-writer shape (decode reads {@code <prefix>_<gi>.value<slot+1>()}, plain reads the
     *       per-row value); a <b>within-SET shared</b> column gathers the present writers' values (reusing
     *       {@link #appendAgreementValue}), pairwise-checks them through
     *       {@code NodeIdEncoder.requireColumnAgreement}, and adds the single coalesced
     *       {@code DSL.val(firstPresent, col.getDataType())} cell — {@link #emitInsertAgreementPrep}'s
     *       coalesced-cell shape transplanted into the row loop (no {@code DSL.defaultValue} branch, since
     *       the conditional gate guarantees a present writer).</li>
     * </ol>
     *
     * <p>A column already supplied by the WHERE side ({@code lookupSqlNames}, the self-FK cross-partition
     * case) is skipped here — the lookup-key v-column already carries its cell; the WHERE∩SET value check
     * is {@link #emitBulkKeySetAgreement}, run alongside.
     */
    private static void emitSetBulkCellAdds(
            CodeBlock.Builder block,
            List<SetGroup> setFields,
            Set<String> lookupSqlNames,
            String decodeLocalPrefix,
            GeneratorUtils.ResolvedTableNames tablesOnly,
            TableRef tableRef) {
        emitBulkSetDecodeLocals(block, setFields, decodeLocalPrefix);
        var listCn = ClassName.get("java.util", "List");
        var arrayListCn = ClassName.get("java.util", "ArrayList");
        var plan = setColumnPlan(setFields);
        for (int ci = 0; ci < plan.size(); ci++) {
            var sc = plan.get(ci);
            var col = sc.column();
            if (lookupSqlNames.contains(col.sqlName())) {
                continue; // cross-partition: the WHERE side already added this cell.
            }
            block.beginControlFlow("if ($L)", setColumnPresenceGate(sc, "bc" + ci));
            if (sc.shared()) {
                String listName = decodeLocalPrefix + "SetAgree" + ci;
                String label = "input fields " + sc.contributors().stream()
                    .map(c -> "'" + c.writer().label() + "'")
                    .distinct()
                    .collect(java.util.stream.Collectors.joining(", "));
                // A within-SET shared column reaching here always has at least one @nodeId decode writer
                // (the all-plain overlap is the UpdateRowsWalker PlainColumnCollision reject), so an
                // encoder class is always available.
                ClassName encoderClass = sc.contributors().stream()
                    .map(c -> ((SetGroupWriter) c.writer()).group())
                    .filter(g -> g.nidk() != null)
                    .map(g -> g.nidk().decodeMethod().encoderClass())
                    .findFirst().orElseThrow();
                block.addStatement("$T<$T> $L = new $T<>()", listCn, Object.class, listName, arrayListCn);
                int wi = 0;
                for (var c : sc.contributors()) {
                    var v = (SetGroupWriter) c.writer();
                    appendAgreementValue(block, v.group(), c.slot(), "row",
                        decodeLocalPrefix + "_" + v.index(), listName, "bsa" + ci + "w" + (wi++));
                }
                String idx = listName + "Idx";
                block.beginControlFlow("for (int $L = 1; $L < $L.size(); $L++)", idx, idx, listName, idx)
                    .addStatement("$T.requireColumnAgreement($S, $T.$L.$L.getDataType(), $L.get(0), $L.get($L))",
                        encoderClass, label, tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName(),
                        listName, listName, idx)
                    .endControlFlow();
                block.addStatement("cells.add($T.val($L.get(0), $T.$L.$L.getDataType()))",
                    DSL, listName, tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
            } else {
                var c = sc.contributors().get(0);
                var v = (SetGroupWriter) c.writer();
                var g = v.group();
                if (g.nidk() != null) {
                    block.addStatement("cells.add($T.val($L.value$L(), $T.$L.$L.getDataType()))",
                        DSL, decodeLocalPrefix + "_" + v.index(), c.slot() + 1,
                        tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
                } else {
                    block.addStatement("cells.add($T.val($L, $T.$L.$L.getDataType()))",
                        DSL, ArgCallEmitter.nestedMapValueExpr("row", g.accessPath()),
                        tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
                }
            }
            block.endControlFlow();
        }
    }

    /**
     * Emits the per-row decode locals for the bulk SET clause: one {@code Record<N>} per
     * {@link SetGroup} carrying a {@code @nodeId}, declared once per row. Mirrors
     * {@link #buildInsertDecodeLocals}: the local is declared unconditionally with an {@code instanceof
     * String} guard (absent / non-string wire value → {@code null}) and a presence-gated null-check throw
     * (a present-but-mismatched id surfaces the same {@code GraphqlErrorException} as the single-row path).
     * Declaring it once at the top of the row body lets a composite group's several cells and a
     * shared column's gather all read one decode without re-decoding per writer.
     */
    private static void emitBulkSetDecodeLocals(
            CodeBlock.Builder block, List<SetGroup> setFields, String decodeLocalPrefix) {
        ClassName graphqlErr = ClassName.get("graphql", "GraphqlErrorException");
        for (int gi = 0; gi < setFields.size(); gi++) {
            var sf = setFields.get(gi);
            var nidk = sf.nidk();
            if (nidk == null) continue;
            var path = sf.accessPath();
            String recLocal = decodeLocalPrefix + "_" + gi;
            block.addStatement("$T $L = ($L instanceof $T _s$L) ? $T.$L(_s$L) : null",
                nidk.decodeMethod().returnType(), recLocal,
                ArgCallEmitter.nestedMapValueExpr("row", path), String.class, recLocal,
                nidk.decodeMethod().encoderClass(), nidk.decodeMethod().methodName(), recLocal);
            block.beginControlFlow("if ($L && $L == null)",
                    nestedContainsKeyExpr("row", path, "bsid" + gi), recLocal)
                .addStatement("throw $T.newErrorException().message($S).build()", graphqlErr,
                    "Decoded NodeId did not match the expected type for input field '" + sf.name() + "'")
                .endControlFlow();
        }
    }

    /**
 * Emits {@code sets.put(t.col, v.field(t.col))} off the {@link #setColumnPlan} (one put
     * per distinct column), guarded by {@link #setColumnPresenceGate}. Unlike the two v-populating
     * emitters, this one does <em>not</em> skip a cross-partition column (one shared with the WHERE key,
     * the self-FK case): {@code v.field(t.col)} resolves to the lookup-key v-column the WHERE side added,
     * so the put is a no-op set of the column to its own joined value — matching the single-row SET-map
     * semantics and keeping {@code sets} non-empty so the empty-SET runtime guard does not fire on a valid
     * self-FK input. Do not "tidy up" this put; removing it reintroduces the empty-SET throw on the
     * minimal self-FK shape.
     */
    private static void emitSetVFieldPuts(
            CodeBlock.Builder block,
            List<SetGroup> setFields,
            GeneratorUtils.ResolvedTableNames tablesOnly,
            TableRef tableRef) {
        var plan = setColumnPlan(setFields);
        for (int ci = 0; ci < plan.size(); ci++) {
            var sc = plan.get(ci);
            block.beginControlFlow("if ($L)", setColumnPresenceGate(sc, "vf" + ci));
            block.addStatement("sets.put($T.$L.$L, v.field($T.$L.$L))",
                tablesOnly.tablesClass(), tableRef.javaFieldName(), sc.column().javaName(),
                tablesOnly.tablesClass(), tableRef.javaFieldName(), sc.column().javaName());
            block.endControlFlow();
        }
    }

    /**
 * The bulk-path WHERE∩SET value-agreement, the per-row analogue of the single-row
     * {@link #emitKeySetAgreementPreamble}. A self-FK {@code @reference} routes its lifted columns wholly
     * to SET while the row identity comes from the WHERE key, so a column the self-FK shares with the
     * identity field (e.g. {@code email.mailbox_id}) sits in both partitions. The shared column reaches
     * {@code v} once from the WHERE side (the SET emitters skip it), is SET to that joined value (a no-op),
     * and this check asserts the two writers agreed before the DML — the FK forces them equal, a malformed
     * input could disagree.
     *
     * <p>Unlike the single-row preamble, this re-uses the per-row decode locals already emitted in the row
     * loop — the WHERE-side {@code bulkKey<gi>} / {@code bulkKey<gi>_<bi>} ({@link #emitLookupKeyDecodeLocals})
     * and the SET-side {@code <decodeLocalPrefix>_<gi>} ({@link #emitBulkSetDecodeLocals}) — rather than
     * re-decoding, so the generated row body decodes each id once. Both locals are non-null by the time
     * this runs (their declarations throw on a present-but-mismatched id), so the check is a single
     * {@code requireColumnAgreement} call gated on the self-FK field's first-row presence; an omitted
     * nullable self-FK skips it. Emits nothing (byte-identical) when there is no WHERE∩SET overlap.
     */
    private static void emitBulkKeySetAgreement(
            CodeBlock.Builder block,
            List<InputColumnBindingGroup> keyGroups,
            List<SetGroup> setGroups,
            String keyDecodeLocalPrefix,
            String setDecodeLocalPrefix,
            GeneratorUtils.ResolvedTableNames tablesOnly,
            TableRef tableRef) {
        // WHERE-side value expression + source field name per key-column sqlName, reading the per-row
        // decode locals emitLookupKeyDecodeLocals declared (mirrors emitLookupKeyCellAdds' value read).
        record KeySide(CodeBlock value, String fieldName, CallSiteExtraction.NodeIdDecodeKeys nidk) {}
        var keyByColumn = new java.util.LinkedHashMap<String, KeySide>();
        for (int gi = 0; gi < keyGroups.size(); gi++) {
            switch (keyGroups.get(gi)) {
                case InputColumnBindingGroup.MapGroup mg -> {
                    for (int bi = 0; bi < mg.bindings().size(); bi++) {
                        var binding = mg.bindings().get(bi);
                        var leaf = leafExtractionOf(binding.extraction());
                        CodeBlock value = leaf instanceof CallSiteExtraction.NodeIdDecodeKeys
                            ? CodeBlock.of("$L_$L.value1()", keyDecodeLocalPrefix + gi, bi)
                            : ArgCallEmitter.nestedMapValueExpr("row", accessPathOf(binding.fieldName(), binding.extraction()));
                        keyByColumn.putIfAbsent(binding.targetColumn().sqlName(),
                            new KeySide(value, binding.fieldName(),
                                leaf instanceof CallSiteExtraction.NodeIdDecodeKeys n ? n : null));
                    }
                }
                case InputColumnBindingGroup.DecodedRecordGroup drg -> {
                    for (var binding : drg.bindings()) {
                        keyByColumn.putIfAbsent(binding.targetColumn().sqlName(),
                            new KeySide(CodeBlock.of("$L.value$L()", keyDecodeLocalPrefix + gi, binding.index() + 1),
                                drg.sourceFieldName(), drg.extraction()));
                    }
                }
            }
        }
        for (int gi = 0; gi < setGroups.size(); gi++) {
            var sg = setGroups.get(gi);
            for (int s = 0; s < sg.columns().size(); s++) {
                var col = sg.columns().get(s);
                var keySide = keyByColumn.get(col.sqlName());
                if (keySide == null) continue; // not a WHERE∩SET column.
                CodeBlock setValue = sg.nidk() != null
                    ? CodeBlock.of("$L_$L.value$L()", setDecodeLocalPrefix, gi, s + 1)
                    : ArgCallEmitter.nestedMapValueExpr("row", sg.accessPath());
                // A WHERE∩SET column always carries a @nodeId decode on at least one side (a self-FK SET
                // reference; the key field is typically a @nodeId too), so an encoder class is available.
                ClassName encoderClass = sg.nidk() != null
                    ? sg.nidk().decodeMethod().encoderClass()
                    : keySide.nidk().decodeMethod().encoderClass();
                String label = "input fields '" + keySide.fieldName() + "', '"
                    + String.join(".", sg.accessPath()) + "'";
                block.beginControlFlow("if ($L)", firstRowSetPresenceExpr(sg.accessPath(), "ksa" + gi + "_" + s));
                block.addStatement("$T.requireColumnAgreement($S, $T.$L.$L.getDataType(), $L, $L)",
                    encoderClass, label, tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName(),
                    keySide.value(), setValue);
                block.endControlFlow();
            }
        }
    }

    /**
     * Emits a fetcher for the Update write arm: a synchronous static
     * method that runs {@code dsl.update(table).set(col, val)... .where(<lookupKey predicates>)
     * .returningResult(<keys or $project>).fetchOne(...)}.
     *
     * <p>SET clause is {@code tia.setFields()} (the typed non-{@code @lookupKey}
     * {@code ColumnField} projection on {@code TableInputArg}). Invariant #4 guarantees this
     * projection is non-empty. WHERE clause is the {@code @lookupKey} fieldBindings, chained
     * with {@code .and(...)} via the shared {@link #buildLookupWhereSingleRow} helper. Empty-match
     * semantics: {@code .fetchOne(...)} returns {@code null} when the WHERE clause matches no
     * row, same as DELETE.
     */
    private static MethodSpec buildMutationUpdateFetcher(TypeFetcherEmissionContext ctx, MutationField.DmlTableField f,
                                                          OperationMember.Write.Update w,
                                                          String outputPackage,
                                                          no.sikt.graphitron.command.LauncherCommand row,
                                                          no.sikt.graphitron.command.CarrierDsl carrierDsl) {
        // SET / WHERE partition and the matched-key identity come off the UpdateRows carrier
        // (updateRows.setColumns() / keyColumns()) and the slim arg surface (inputArg). Carrier
        // slots project into the SetGroup / InputColumnBindingGroup shapes the shared SET /
        // lookup-WHERE emitters consume.
        var inputArg = w.inputArg();
        var tableRef = inputArg.table();
        var tablesOnly = GeneratorUtils.ResolvedTableNames.ofTable(tableRef);
        String tableLocal = tablesOnly.tableLocalName();
        var setGroups = setGroupsOf(w.updateRows().setColumns());
        var keyGroups = keyGroupsOf(w.updateRows().keyColumns());

        if (inputArg.list()) {
            return buildBulkUpdateFetcher(ctx, f, outputPackage, inputArg, tableRef, tablesOnly, tableLocal,
                setGroups, keyGroups, row, carrierDsl);
        }

        // Single-row UPDATE: build the SET clause dynamically from the present-key set so absent
        // fields drop out (PATCH semantics) and explicit-null fields bind typed null. The map
        // is consumed by jOOQ's `.set(Map<? extends Field<?>, ?>)` overload, which preserves
        // the chain shape (`UpdateSetMoreStep<R>` → `.where(...).returningResult(...)`).
        var fieldClass = ClassName.get("org.jooq", "Field");
        var linkedHashMap = ClassName.get("java.util", "LinkedHashMap");
        var postInGuard = CodeBlock.builder();
        postInGuard.addStatement("$T<$T<?>, Object> sets = new $T<>()", MAP, fieldClass, linkedHashMap);
        // Value-agreement preamble for any SET column written by more than one carrier with a
        // @nodeId decode among them; the silent last-write-wins the Map.put below would otherwise allow.
        // No-op (byte-identical) when there is no such overlap.
        emitSetAgreementPreamble(postInGuard, setGroups, "in", "setKey", tablesOnly, tableRef);
        // Cross-partition (WHERE∩SET) value-agreement preamble. A self-FK @reference routes its
        // lifted columns wholly to SET while the row identity comes from the WHERE key, so a column the
        // self-FK shares with the identity field (e.g. email.mailbox_id) sits in both partitions; the FK
        // forces them equal, this checks it before the DML. Key-side groups are projected into the
        // SetGroup shape (by access path, nidk peeled) so the preamble reads each side's slot uniformly.
        // No-op (byte-identical) when there is no key∩set overlap.
        var keySetGroups = setGroupsOf(w.updateRows().keyColumns().stream()
            .map(kc -> new SetColumn(kc.sdlFieldName(), kc.targetColumn(), kc.extraction()))
            .toList());
        emitKeySetAgreementPreamble(postInGuard, keySetGroups, setGroups, "in", "keySet", tablesOnly, tableRef);
        emitSetMapPuts(postInGuard, setGroups, "sets", "in", "in",
            "setKey", tablesOnly, tableRef);
        // Runtime PATCH guard: the carrier guarantees the schema has at least one settable column,
        // but a caller may omit every set-field value (sending only key columns); fail with a
        // friendly message rather than letting jOOQ reject an empty SET map.
        postInGuard.beginControlFlow("if (sets.isEmpty())")
            .addStatement("throw new $T($S)", IllegalArgumentException.class,
                "@mutation(typeName: UPDATE) call has no settable fields present; "
                    + "only key fields were provided")
            .endControlFlow();

        var whereChunk = buildLookupWhereSingleRow(keyGroups, tablesOnly, tableRef, "in");
        postInGuard.add(whereChunk.decodeLocals());
        var dmlChain = CodeBlock.builder()
            .add(".update($L)\n", tableLocal)
            .add(".set(sets)\n")
            .add(".where(").add(whereChunk.whereExpr()).add(")\n")
            .build();

        return buildDmlFetcher(ctx, f, f.returnExpression(), f.errorChannel(),
            inputArg.name(), tableRef, tablesOnly, tableLocal,
            outputPackage, dmlChain, f.dialectRequirement(), postInGuard.build(), inputArg.list(),
            row, carrierDsl);
    }

    /**
     * Bulk UPDATE: emits a Postgres-only {@code UPDATE t SET c = v.c FROM (VALUES …) AS v(k, c…)
     * WHERE t.k = v.k} statement. Three guards ride {@code postInGuard}:
     * <ol>
     *   <li><b>Uniform-shape</b> — every row's {@code keySet()} must equal the first row's;
     *       a divergent row would need its own SET clause, which one statement can't carry.</li>
     *   <li><b>No-set-fields-present</b> — at least one {@code tia.setFields()} entry must be
     *       in {@code firstKeys}; otherwise {@code SET} would be empty and jOOQ rejects.</li>
     *   <li><b>Duplicate-lookup-key</b> — distinct lookup-key tuples per row, otherwise
     *       Postgres' implementation-defined join silently drops one row's data.</li>
     * </ol>
     * A separate typed {@link DialectRequirement.RequiresFamily}({@code POSTGRES}) on the model
     * rejects non-Postgres dialects: only Postgres speaks the {@code UPDATE … FROM (VALUES …)} form
     * jOOQ renders here; {@link #emitDialectGuard} renders it.
     */
    private static MethodSpec buildBulkUpdateFetcher(TypeFetcherEmissionContext ctx,
                                                     MutationField.DmlTableField f,
                                                     String outputPackage,
                                                     no.sikt.graphitron.rewrite.model.InputArgRef inputArg,
                                                     TableRef tableRef,
                                                     GeneratorUtils.ResolvedTableNames tablesOnly,
                                                     String tableLocal,
                                                     List<SetGroup> setGroups,
                                                     List<InputColumnBindingGroup> keyGroups,
                                                     no.sikt.graphitron.command.LauncherCommand row,
                                                     no.sikt.graphitron.command.CarrierDsl carrierDsl) {
        var fieldClass = ClassName.get("org.jooq", "Field");
        var arrayList = ClassName.get("java.util", "ArrayList");
        var linkedHashMap = ClassName.get("java.util", "LinkedHashMap");
        var hashSet = ClassName.get("java.util", "HashSet");
        // RowN is the right erased element type: DSL.row(Field<?>...) returns RowN, and
        // DSL.values(RowN...) is the matching varargs overload. Using the parent Row would
        // produce Row[], which has no DSL.values overload (varargs requires RowN[] or one
        // of the typed Row1<T1>...Row22 forms).
        var rowClass = ClassName.get("org.jooq", "RowN");
        var tableClass = ClassName.get("org.jooq", "Table");
        var groups = keyGroups;
        // Flatten lookup-key target columns across groups for the join-on-column-names construction;
        // every column appears once at slot index i in vColNames / cells / WHERE.
        var lookupTargetColumns = new ArrayList<no.sikt.graphitron.rewrite.model.ColumnRef>();
        for (var g : groups) lookupTargetColumns.addAll(g.targetColumns());
        // SET columns whose backing column is already a WHERE/lookup-key v-column (the self-FK
        // cross-partition overlap). The two v-populating SET emitters skip these so the column appears in
        // v once; emitBulkKeySetAgreement checks the WHERE and SET writers agreed.
        var lookupSqlNames = new LinkedHashSet<String>();
        for (var col : lookupTargetColumns) lookupSqlNames.add(col.sqlName());

        var postInGuard = CodeBlock.builder();
        postInGuard.addStatement("$T<?> firstKeys = in.get(0).keySet()", SET);
        postInGuard.add(buildUniformShapeGuard("UPDATE"));
        // A nested SET leaf is in the column list iff present in the first row; every row must
        // then agree with the first row's nested shape (the top-level keySet guard above only checks
        // the outer keys), else per-row cells would misalign with the column list.
        postInGuard.add(buildNestedShapeGuards(setGroups));

        // Build v-table column-name list: lookup-key columns (unconditional) + set-field columns
        // present in firstKeys, in declaration order. Strings come from each Field's getName()
        // so jOOQ's typed v.field(Field<T>) overload returns the correctly typed v-column.
        postInGuard.addStatement("$T<String> vColNames = new $T<>()",
            ClassName.get(List.class), arrayList);
        for (var col : lookupTargetColumns) {
            postInGuard.addStatement("vColNames.add($T.$L.$L.getName())",
                tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
        }
        emitSetVColNameAdds(postInGuard, setGroups, lookupSqlNames, tablesOnly, tableRef);

        // Build per-row v-table cells imperatively, mirroring the column-name walk above so
        // the cell positions line up by construction. DSL.row(Field<?>...) packages the cells;
        // the final List<Row> drives DSL.values(Row...) and the v-table alias. Imperative loop
        // (rather than stream) because the firstKeys-conditional cell adds are control-flow,
        // not expressions.
        postInGuard.addStatement("$T<$T> vRows = new $T<>()",
            ClassName.get(List.class), rowClass, arrayList);
        postInGuard.beginControlFlow("for ($T<?, ?> row : in)", MAP);
        postInGuard.addStatement("$T<$T<?>> cells = new $T<>()",
            ClassName.get(List.class), fieldClass, arrayList);
        // Per-row decode locals for any NodeId-decoded groups (composite-PK or arity-1 NodeId
        // ColumnField), shared by all positional bindings of the same source field.
        emitLookupKeyDecodeLocals(postInGuard, groups, "row");
        for (int gi = 0; gi < groups.size(); gi++) {
            var g = groups.get(gi);
            emitLookupKeyCellAdds(postInGuard, g, gi, "row", tablesOnly, tableRef);
        }
        emitSetBulkCellAdds(postInGuard, setGroups, lookupSqlNames, "bulkSetKey", tablesOnly, tableRef);
        // WHERE∩SET per-row value agreement (self-FK shared column), reusing the bulkKey / bulkSetKey
        // decode locals declared above this in the loop body. No-op when there is no cross-partition overlap.
        emitBulkKeySetAgreement(postInGuard, groups, setGroups, "bulkKey", "bulkSetKey", tablesOnly, tableRef);
        postInGuard.addStatement("vRows.add($T.row(cells.toArray(new $T<?>[0])))", DSL, fieldClass);
        postInGuard.endControlFlow();
        postInGuard.addStatement("$T<?> v = $T.values(vRows.toArray(new $T[0])).as($S, vColNames.toArray(new String[0]))",
            tableClass, DSL, rowClass, "v");

        // SET map: same firstKeys-conditional walk over setFields, producing
        // { t.col -> v.field(t.col) } entries. The typed Table.field(Field<T>) overload returns
        // the matching v-column with the target column's type, so no cast is needed at the
        // .set(Map<? extends Field<?>, ?>) call site.
        postInGuard.addStatement("$T<$T<?>, Object> sets = new $T<>()", MAP, fieldClass, linkedHashMap);
        emitSetVFieldPuts(postInGuard, setGroups, tablesOnly, tableRef);
        postInGuard.beginControlFlow("if (sets.isEmpty())")
            .addStatement("throw new $T($S)", IllegalArgumentException.class,
                "@mutation(typeName: UPDATE) bulk call has no settable fields present in the input rows; "
                    + "only key fields were provided")
            .endControlFlow();

        // Duplicate-lookup-key guard: build a HashSet<List<Object>> over the per-row lookup-key
        // tuples; throw when set size differs from row count. The tuple identity comes from the
        // wire-format source-field values (MapBinding.fieldName per binding; DecodedRecordGroup
        // uses its sourceFieldName once for the whole group — the encoded NodeId string is a
        // stable identity for the decoded tuple).
        var lookupKeyTuple = CodeBlock.builder().add("$T.of(", ClassName.get(List.class));
        boolean firstTupleSlot = true;
        for (var g : groups) {
            switch (g) {
                case InputColumnBindingGroup.MapGroup mg -> {
                    for (var binding : mg.bindings()) {
                        if (!firstTupleSlot) lookupKeyTuple.add(", ");
                        firstTupleSlot = false;
                        lookupKeyTuple.add("$L", ArgCallEmitter.nestedMapValueExpr(
                            "row", accessPathOf(binding.fieldName(), binding.extraction())));
                    }
                }
                case InputColumnBindingGroup.DecodedRecordGroup drg -> {
                    if (!firstTupleSlot) lookupKeyTuple.add(", ");
                    firstTupleSlot = false;
                    lookupKeyTuple.add("$L", ArgCallEmitter.nestedMapValueExpr("row", drg.accessPath()));
                }
            }
        }
        lookupKeyTuple.add(")");
        postInGuard.addStatement("$T<$T<Object>> seenKeys = new $T<>()",
            hashSet, ClassName.get(List.class), hashSet);
        postInGuard.beginControlFlow("for ($T<?, ?> row : in)", MAP)
            .addStatement("seenKeys.add($L)", lookupKeyTuple.build())
            .endControlFlow();
        postInGuard.beginControlFlow("if (seenKeys.size() != in.size())")
            .addStatement("throw new $T($S)", IllegalArgumentException.class,
                "@mutation(typeName: UPDATE) bulk input contains rows with duplicate "
                    + "@lookupKey tuples; one statement can join each parent row to at most "
                    + "one input row")
            .endControlFlow();

        // WHERE clause joins t to v on the lookup-key columns (chained .and(...)). The lookup
        // keys are unconditional in vColNames, so v.field(t.k) always resolves.
        var whereExpr = CodeBlock.builder();
        for (int i = 0; i < lookupTargetColumns.size(); i++) {
            var col = lookupTargetColumns.get(i);
            if (i > 0) whereExpr.add(".and(");
            whereExpr.add("$T.$L.$L.eq(v.field($T.$L.$L))",
                tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName(),
                tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
            if (i > 0) whereExpr.add(")");
        }

        var dmlChain = CodeBlock.builder()
            .add(".update($L)\n", tableLocal)
            .add(".set(sets)\n")
            .add(".from(v)\n")
            .add(".where(").add(whereExpr.build()).add(")\n")
            .build();

        return buildDmlFetcher(ctx, f, f.returnExpression(), f.errorChannel(),
            inputArg.name(), tableRef, tablesOnly, tableLocal,
            outputPackage, dmlChain, f.dialectRequirement(), postInGuard.build(), inputArg.list(),
            row, carrierDsl);
    }

    /**
     * Emits a fetcher for the Upsert write arm: a synchronous static
     * method that runs {@code dsl.insertInto(table, cols...).values(vals...).onConflict(<keys>)
     * .doUpdate().set(col, val)... .returningResult(<keys or $project>).fetchOne(...)}.
     *
     * <p>Column/values lists are identical to INSERT (every {@code InputField.ColumnBackedField} in
     * declaration order, {@code @lookupKey} fields included so the user-supplied PK lands on the
     * insert branch). Conflict keys come from {@code tia.fieldBindings()}. Conflict action: when
     * {@code tia.setFields()} is non-empty, emit {@code .doUpdate().set(...)} over those fields;
     * otherwise emit {@code .doNothing()} (jOOQ rejects {@code .doUpdate()} with an empty SET).
     *
     * <p>PostgreSQL-only: {@code ON CONFLICT} is a Postgres extension.
     */
    private static MethodSpec buildMutationUpsertFetcher(TypeFetcherEmissionContext ctx, MutationField.DmlTableField f,
                                                          OperationMember.Write.Upsert w,
                                                          String outputPackage,
                                                          no.sikt.graphitron.command.LauncherCommand row,
                                                          no.sikt.graphitron.command.CarrierDsl carrierDsl) {
        var tia = w.input();
        var tableRef = tia.inputTable();
        var tablesOnly = GeneratorUtils.ResolvedTableNames.ofTable(tableRef);
        String tableLocal = tablesOnly.tableLocalName();

        var fields = tia.fields();
        var colList = buildInsertColumnList(fields, tablesOnly, tableRef);

        // When the .doUpdate() branch fires, build the SET map dynamically from
        // the present-key set and bind each value to DSL.excluded(col) (the just-attempted
        // INSERT cell). Combined with the per-row containsKey-gated INSERT cells above, an
        // omitted column is DEFAULT on the INSERT branch *and* drops out of DO UPDATE SET on
        // the conflict branch — so the existing row's value survives a conflict (PATCH
        // semantics on the update branch). A naive `c = EXCLUDED.c` for every setFields()
        // column would resolve EXCLUDED.c to the table default whenever the proposed INSERT
        // row used DEFAULT, overwriting the existing row's value with the default; dynamic
        // SET avoids that silent-data-loss footgun. The .doNothing() mode (setFields() empty
        // at codegen) skips the walk entirely. On the bulk arm, the present-key set is
        // captured once from the first row (firstKeys) after a uniformity guard ensures
        // every row's keySet matches; one shared SET clause is correct because every
        // conflicting INSERT row uses the same EXCLUDED column set.
        var fieldClass = ClassName.get("org.jooq", "Field");
        var linkedHashMap = ClassName.get("java.util", "LinkedHashMap");
        var postInGuard = CodeBlock.builder();
        if (!tia.setFields().isEmpty()) {
            String presentKeysLocal;
            if (tia.list()) {
                postInGuard.addStatement("$T<?> firstKeys = in.get(0).keySet()", SET);
                postInGuard.add(buildUniformShapeGuard("UPSERT"));
                presentKeysLocal = "firstKeys";
            } else {
                presentKeysLocal = "in.keySet()";
            }
            postInGuard.addStatement("$T<$T<?>, Object> setsUpdate = new $T<>()", MAP, fieldClass, linkedHashMap);
            emitSetExcludedPuts(postInGuard, tia.setFields(), "setsUpdate",
                presentKeysLocal.equals("in.keySet()") ? "in" : presentKeysLocal,
                presentKeysLocal.equals("in.keySet()") ? "containsKey" : "contains",
                tablesOnly, tableRef);
            postInGuard.beginControlFlow("if (setsUpdate.isEmpty())")
                .addStatement("throw new $T($S)", IllegalArgumentException.class,
                    "@mutation(typeName: UPSERT) call has no settable fields present; "
                        + "only @lookupKey fields were provided")
                .endControlFlow();
        }

        var conflictCols = CodeBlock.builder();
        var conflictTargetColumns = new ArrayList<no.sikt.graphitron.rewrite.model.ColumnRef>();
        for (var g : tia.fieldBindings()) conflictTargetColumns.addAll(g.targetColumns());
        for (int i = 0; i < conflictTargetColumns.size(); i++) {
            if (i > 0) conflictCols.add(", ");
            conflictCols.add("$T.$L.$L",
                tablesOnly.tablesClass(), tableRef.javaFieldName(),
                conflictTargetColumns.get(i).javaName());
        }

        var dmlChain = CodeBlock.builder()
            .add(".insertInto($L, ", tableLocal).add(colList).add(")\n");
        if (tia.list()) {
            boolean hasDecodeLocals = anyNodeIdCarrier(fields);
            if (hasDecodeLocals) {
                dmlChain.add(".valuesOfRows(in.stream()\n").indent()
                    .add(".map(row -> {\n").indent()
                    .add(buildInsertDecodeLocals(fields, "row", "insertKey", tablesOnly, tableRef))
                    .add("return $T.row(\n", DSL).indent()
                    .add(buildPerCellValueList(fields, tablesOnly, tableRef, "row", "insertKey")).unindent()
                    .add(");\n").unindent()
                    .add("})\n")
                    .add(".toList())\n").unindent();
            } else {
                dmlChain.add(".valuesOfRows(in.stream()\n").indent()
                    .add(".map(row -> $T.row(\n", DSL).indent()
                    .add(buildPerCellValueList(fields, tablesOnly, tableRef, "row", "insertKey")).unindent()
                    .add("))\n")
                    .add(".toList())\n").unindent();
            }
        } else {
            // Single-row decode locals lift into postInGuard. The if-not-empty block above
            // already wrote setsUpdate-side guards; appending the decode locals here keeps the
            // statement order (uniform-shape guard → setsUpdate construction → decode locals).
            postInGuard.add(buildInsertDecodeLocals(fields, "in", "insertKey", tablesOnly, tableRef));
            dmlChain.add(".values(\n").indent()
                .add(buildPerCellValueList(fields, tablesOnly, tableRef, "in", "insertKey")).unindent()
                .add(")\n");
        }
        dmlChain.add(".onConflict(").add(conflictCols.build()).add(")\n");
        if (!tia.setFields().isEmpty()) {
            dmlChain.add(".doUpdate()\n").add(".set(setsUpdate)\n");
        } else {
            dmlChain.add(".doNothing()\n");
        }

        // JOOQ silently translates `.onConflict(...).doUpdate()` (and `.doNothing()`) to an
        // Oracle `MERGE INTO ...` statement whose concurrency, conflict-key matching, and
        // `RETURNING` semantics differ from PostgreSQL `ON CONFLICT`, and it exposes no setting to
        // disable the emulation. The Oracle rejection is a typed
        // DialectRequirement.RejectsFamily(ORACLE) on the model, rendered by emitDialectGuard
        // (jOOQ's family() folds every commercial ORACLE* version to ORACLE, so the guard gates
        // them all).
        return buildDmlFetcher(ctx, f, f.returnExpression(), f.errorChannel(),
            tia.name(), tableRef, tablesOnly, tableLocal,
            outputPackage, dmlChain.build(), f.dialectRequirement(), postInGuard.build(), tia.list(),
            row, carrierDsl);
    }

    /**
     * Bulk-arm uniform-shape guard: emits a runtime walk across {@code in} that throws
     * {@link IllegalArgumentException} when any row's {@code keySet()} diverges from
     * {@code firstKeys}. Caller must have bound {@code firstKeys} immediately above (the
     * code-block reads from that local). Used by bulk UPDATE (always when set-side fields
     * are present) and bulk UPSERT (only when {@code tia.setFields()} is non-empty,
     * i.e. {@code .doUpdate()} mode).
     */
    private static CodeBlock buildUniformShapeGuard(String verb) {
        return CodeBlock.builder()
            .beginControlFlow("for (int rowIdx = 1; rowIdx < in.size(); rowIdx++)")
            .beginControlFlow("if (!in.get(rowIdx).keySet().equals(firstKeys))")
            .addStatement("throw new $T(\"@mutation(typeName: $L) bulk input rows must share the same present-key set; row \" + rowIdx + \" has keys \" + in.get(rowIdx).keySet() + \" but row 0 has \" + firstKeys)",
                IllegalArgumentException.class, verb)
            .endControlFlow()
            .endControlFlow()
            .build();
    }

    /**
 * Per-nesting-node shape guards for bulk UPDATE. For each distinct nesting prefix that a
     * SET leaf descends through (e.g. {@code [lokalisering]} for a leaf at
     * {@code [lokalisering, landkode]}), assert every input row's map at that prefix has the same
     * keySet as the first row's — comparing {@code null} (prefix absent or not a {@code Map}) for
     * {@code null}. This complements {@link #buildUniformShapeGuard}'s top-level keySet check: the
     * SET column list is built from the first row's present nested leaves, so a row diverging in its
     * nested shape would misalign the per-row cells. Empty when no SET leaf is nested.
     *
     * <p>Only the {@code UPDATE … FROM (VALUES …)} bulk shape needs this: it is the one bulk DML form
     * that shares a single column list across all rows. Bulk DELETE (per-row WHERE) and bulk INSERT
     * (per-row VALUES via {@link #buildPerCellValueList}) read each {@code row} independently, so each
     * row stands alone and no cross-row nested-shape agreement is required.
     */
    private static CodeBlock buildNestedShapeGuards(List<SetGroup> setGroups) {
        var prefixes = new LinkedHashSet<List<String>>();
        for (var sg : setGroups) {
            var p = sg.accessPath();
            for (int k = 1; k < p.size(); k++) {
                prefixes.add(List.copyOf(p.subList(0, k)));
            }
        }
        if (prefixes.isEmpty()) {
            return CodeBlock.of("");
        }
        var objects = ClassName.get("java.util", "Objects");
        var b = CodeBlock.builder();
        int idx = 0;
        for (var prefix : prefixes) {
            b.beginControlFlow("for (int rowIdx = 1; rowIdx < in.size(); rowIdx++)");
            b.addStatement("$T<?> firstShape$L = $L", SET, idx, nestedKeySetOrNull("in.get(0)", prefix, "f" + idx));
            b.addStatement("$T<?> rowShape$L = $L", SET, idx, nestedKeySetOrNull("in.get(rowIdx)", prefix, "r" + idx));
            b.beginControlFlow("if (!$T.equals(firstShape$L, rowShape$L))", objects, idx, idx);
            b.addStatement("throw new $T($S + rowIdx + $S)", IllegalArgumentException.class,
                "@mutation(typeName: UPDATE) bulk input rows must share the same nested-input shape; row ",
                " differs from row 0 under nested group '" + String.join(".", prefix) + "'");
            b.endControlFlow();
            b.endControlFlow();
            idx++;
        }
        return b.build();
    }

    /**
     * Expression yielding the keySet of the {@code Map} reached by descending {@code prefix} under
     * {@code rowExpr}, or {@code null} if any level is absent or not a {@code Map}. Used by
     * {@link #buildNestedShapeGuards}.
     */
    private static CodeBlock nestedKeySetOrNull(String rowExpr, List<String> prefix, String salt) {
        var cond = CodeBlock.builder();
        String cur = rowExpr;
        for (int d = 0; d < prefix.size(); d++) {
            if (d > 0) cond.add(" && ");
            String inner = "sm" + salt + "_" + d;
            cond.add("$L.get($S) instanceof $T<?, ?> $L", cur, prefix.get(d), Map.class, inner);
            cur = inner;
        }
        return CodeBlock.of("($L) ? $L.keySet() : null", cond.build(), cur);
    }

    /**
     * Single-row lookup-WHERE chunk: a CodeBlock to drop into {@code postInGuard} declaring any
     * per-NodeId decode locals (for {@link InputColumnBindingGroup.DecodedRecordGroup} and for
     * arity-1 NodeId-decoded {@link InputColumnBinding.MapBinding}), plus the WHERE expression
     * that reads the typed slot values out of those locals.
     */
    private record LookupWhereChunk(CodeBlock decodeLocals, CodeBlock whereExpr) {}

    /**
     * Builds the single-row lookup-WHERE chunk: decode locals lifted to {@code postInGuard}, plus
     * the WHERE expression chained with {@code .and(...)} per slot. Shared by DELETE and UPDATE.
     *
     * <ul>
     *   <li>{@link InputColumnBindingGroup.MapGroup} — per binding, emits
     *       {@code t.col.eq(DSL.val(in.get(name), t.col.getDataType()))}. When the binding's
     *       extraction is {@link CallSiteExtraction.NodeIdDecodeKeys}, the value source becomes
     *       {@code lookupKey<i>.value1()} (the per-row decode local, declared above) and the
     *       wrapping {@code DSL.val} keeps the typed column-data-type binding.</li>
     *   <li>{@link InputColumnBindingGroup.DecodedRecordGroup} — emits one decode local
     *       (with {@code ThrowOnMismatch} null handling) above,
     *       and N {@code t.col_k.eq(lookupKey<i>.value<k+1>())} chained equalities into the
     *       WHERE expression.</li>
     * </ul>
     */
    private static LookupWhereChunk buildLookupWhereSingleRow(
            no.sikt.graphitron.rewrite.ArgumentRef.InputTypeArg.TableInputArg tia,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef) {
        return buildLookupWhereSingleRow(tia, tablesOnly, tableRef, "in");
    }

    /**
     * Bulk-input extension: same lookup-WHERE construction as the no-arg overload but reading from a
     * caller-named map local rather than the implicit {@code "in"}. Used by
     * {@link #buildMutationBulkDmlRecordFetcher}'s per-row UPDATE arm, which iterates the bulk
     * input list and binds each {@code Map<?, ?>} to a per-row local named {@code "row"}; the
     * decode-locals and the WHERE predicate read off that per-row map without colliding with the
     * outer {@code "in"} list cast.
     */
    private static LookupWhereChunk buildLookupWhereSingleRow(
            no.sikt.graphitron.rewrite.ArgumentRef.InputTypeArg.TableInputArg tia,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef,
            String mapLocal) {
        return buildLookupWhereSingleRow(tia.fieldBindings(), tablesOnly, tableRef, mapLocal);
    }

    /**
 * The lookup-WHERE chunk built from already-projected {@link InputColumnBindingGroup}s
     * rather than a {@code TableInputArg}. The UPDATE walker carrier projects its
     * {@code keyColumns()} into these groups ({@link #keyGroupsOf}) and calls this overload; the
     * legacy {@code TableInputArg} overloads above delegate here with {@code tia.fieldBindings()}.
     */
    private static LookupWhereChunk buildLookupWhereSingleRow(
            List<InputColumnBindingGroup> groups,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef,
            String mapLocal) {
        var locals = CodeBlock.builder();
        var whereExpr = CodeBlock.builder();
        int slotIndex = 0;
        for (int gi = 0; gi < groups.size(); gi++) {
            var g = groups.get(gi);
            switch (g) {
                case InputColumnBindingGroup.MapGroup mg -> {
                    for (var binding : mg.bindings()) {
                        if (slotIndex > 0) whereExpr.add(".and(");
                        whereExpr.add("$T.$L.$L.eq(",
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), binding.targetColumn().javaName());
                        appendMapBindingValueExpr(whereExpr, locals, binding, mapLocal,
                            tablesOnly, tableRef, gi);
                        whereExpr.add(")");
                        if (slotIndex > 0) whereExpr.add(")");
                        slotIndex++;
                    }
                }
                case InputColumnBindingGroup.DecodedRecordGroup drg -> {
                    String recLocal = "lookupKey" + gi;
                    appendDecodeLocal(locals, recLocal, drg.extraction(),
                        ArgCallEmitter.nestedMapValueExpr(mapLocal, drg.accessPath()), drg.sourceFieldName());
                    for (var binding : drg.bindings()) {
                        if (slotIndex > 0) whereExpr.add(".and(");
                        whereExpr.add("$T.$L.$L.eq($L.value$L())",
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), binding.targetColumn().javaName(),
                            recLocal, binding.index() + 1);
                        if (slotIndex > 0) whereExpr.add(")");
                        slotIndex++;
                    }
                }
            }
        }
        return new LookupWhereChunk(locals.build(), whereExpr.build());
    }

    /**
     * Emits a value expression for one {@link InputColumnBinding.MapBinding}. The wire value is read
     * via {@link ArgCallEmitter#nestedMapValueExpr} from the binding's nested-input access path (peeled from
     * its extraction): a plain {@code mapLocal.get(fieldName)} for a top-level binding, a null-safe
     * nested descent for a binding buried in a grouping input. For a
     * {@link CallSiteExtraction.NodeIdDecodeKeys} leaf extraction, lifts the per-binding decode call
     * to a local (declared into {@code locals}) reading that wire value and emits
     * {@code DSL.val(decoded.value1(), t.col.getDataType())}; otherwise emits
     * {@code DSL.val(<wire value>, t.col.getDataType())}.
     */
    private static void appendMapBindingValueExpr(
            CodeBlock.Builder whereExpr,
            CodeBlock.Builder locals,
            InputColumnBinding.MapBinding binding,
            String mapLocal,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef,
            int groupIndex) {
        var path = accessPathOf(binding.fieldName(), binding.extraction());
        var wireValue = ArgCallEmitter.nestedMapValueExpr(mapLocal, path);
        if (leafExtractionOf(binding.extraction()) instanceof CallSiteExtraction.NodeIdDecodeKeys nidk) {
            String recLocal = "lookupKey" + groupIndex;
            appendDecodeLocal(locals, recLocal, nidk, wireValue, binding.fieldName());
            whereExpr.add("$T.val($L.value1(), $T.$L.$L.getDataType())",
                DSL, recLocal,
                tablesOnly.tablesClass(), tableRef.javaFieldName(), binding.targetColumn().javaName());
        } else {
            whereExpr.add("$T.val($L, $T.$L.$L.getDataType())",
                DSL, wireValue,
                tablesOnly.tablesClass(), tableRef.javaFieldName(), binding.targetColumn().javaName());
        }
    }

    /**
     * Declares a per-row decode local {@code recLocal} reading {@code mapLocal.get(sourceField)},
     * with {@code ThrowOnMismatch} producing a {@code GraphqlErrorException} on a null decode
     * return: a wrong-type id is an authored-input contract violation. At the single-row mutation
     * site a malformed id would surface as a runtime failure in any case, because the SET / DELETE
     * WHERE shape has no per-row skip semantics to fall back on.
     */
    private static void appendDecodeLocal(
            CodeBlock.Builder locals,
            String recLocal,
            CallSiteExtraction.NodeIdDecodeKeys nidk,
            String mapLocal,
            String sourceField) {
        appendDecodeLocal(locals, recLocal, nidk, CodeBlock.of("$L.get($S)", mapLocal, sourceField), sourceField);
    }

    /**
     * Nested-input overload: declare the decode local from an arbitrary wire-value expression rather than a
     * plain {@code mapLocal.get(sourceField)}, so a NodeId leaf buried in a nested grouping input
     * reads via the null-safe descent ({@link ArgCallEmitter#nestedMapValueExpr}). {@code sourceField}
     * names the leaf for the error message only. For a top-level leaf the convenience overload above
     * passes {@code mapLocal.get(sourceField)}, byte-identical to the non-nested case.
     */
    private static void appendDecodeLocal(
            CodeBlock.Builder locals,
            String recLocal,
            CallSiteExtraction.NodeIdDecodeKeys nidk,
            CodeBlock wireValueExpr,
            String sourceField) {
        ClassName encoderClass = nidk.decodeMethod().encoderClass();
        String methodName = nidk.decodeMethod().methodName();
        TypeName recordType = nidk.decodeMethod().returnType();
        ClassName graphqlErr = ClassName.get("graphql", "GraphqlErrorException");
        locals.addStatement("$T $L = ($L instanceof $T _s$L) ? $T.$L(_s$L) : null",
            recordType, recLocal, wireValueExpr, String.class, recLocal, encoderClass, methodName, recLocal);
        locals.beginControlFlow("if ($L == null)", recLocal)
            .addStatement("throw $T.newErrorException().message($S).build()", graphqlErr,
                "Decoded NodeId did not match the expected type for input field '" + sourceField + "'")
            .endControlFlow();
    }

    /**
     * Emits per-row decode locals for every NodeId-decoded lookup-key group on the TIA. One
     * {@code Record<N>} local per {@link InputColumnBindingGroup.DecodedRecordGroup} or per
     * NodeIdDecodeKeys-extracted {@link InputColumnBinding.MapBinding}, named
     * {@code bulkKey<gi>} (composite) / {@code bulkKey<gi>_<bi>} (per-binding). Reads from
     * {@code mapLocal.get(sourceField)}. Used by bulk-arm walks where the decode runs inside the
     * per-row loop / lambda body. {@link InputColumnBindingGroup.MapGroup} bindings with
     * non-NodeId extractions emit no locals.
     */
    private static void emitLookupKeyDecodeLocals(
            CodeBlock.Builder block,
            List<InputColumnBindingGroup> groups,
            String mapLocal) {
        for (int gi = 0; gi < groups.size(); gi++) {
            var g = groups.get(gi);
            switch (g) {
                case InputColumnBindingGroup.MapGroup mg -> {
                    for (int bi = 0; bi < mg.bindings().size(); bi++) {
                        var binding = mg.bindings().get(bi);
                        if (leafExtractionOf(binding.extraction()) instanceof CallSiteExtraction.NodeIdDecodeKeys nidk) {
                            var path = accessPathOf(binding.fieldName(), binding.extraction());
                            appendDecodeLocal(block, "bulkKey" + gi + "_" + bi, nidk,
                                ArgCallEmitter.nestedMapValueExpr(mapLocal, path), binding.fieldName());
                        }
                    }
                }
                case InputColumnBindingGroup.DecodedRecordGroup drg ->
                    appendDecodeLocal(block, "bulkKey" + gi, drg.extraction(),
                        ArgCallEmitter.nestedMapValueExpr(mapLocal, drg.accessPath()), drg.sourceFieldName());
            }
        }
    }

    /**
     * Emits per-row {@code cells.add($T.val(...))} statements for one lookup-key group.
     * MapBinding entries with NodeIdDecodeKeys read from the matching {@code bulkKey<gi>_<bi>}
     * local declared by {@link #emitLookupKeyDecodeLocals}; DecodedRecordGroup entries read
     * {@code bulkKey<gi>.value<k+1>()} per slot. Direct-extracted MapBindings read raw
     * {@code mapLocal.get(name)} verbatim.
     */
    private static void emitLookupKeyCellAdds(
            CodeBlock.Builder block,
            InputColumnBindingGroup g,
            int groupIndex,
            String mapLocal,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef) {
        switch (g) {
            case InputColumnBindingGroup.MapGroup mg -> {
                for (int bi = 0; bi < mg.bindings().size(); bi++) {
                    var binding = mg.bindings().get(bi);
                    if (leafExtractionOf(binding.extraction()) instanceof CallSiteExtraction.NodeIdDecodeKeys) {
                        String recLocal = "bulkKey" + groupIndex + "_" + bi;
                        block.addStatement("cells.add($T.val($L.value1(), $T.$L.$L.getDataType()))",
                            DSL, recLocal,
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), binding.targetColumn().javaName());
                    } else {
                        var path = accessPathOf(binding.fieldName(), binding.extraction());
                        block.addStatement("cells.add($T.val($L, $T.$L.$L.getDataType()))",
                            DSL, ArgCallEmitter.nestedMapValueExpr(mapLocal, path),
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), binding.targetColumn().javaName());
                    }
                }
            }
            case InputColumnBindingGroup.DecodedRecordGroup drg -> {
                String recLocal = "bulkKey" + groupIndex;
                for (var binding : drg.bindings()) {
                    block.addStatement("cells.add($T.val($L.value$L(), $T.$L.$L.getDataType()))",
                        DSL, recLocal, binding.index() + 1,
                        tablesOnly.tablesClass(), tableRef.javaFieldName(), binding.targetColumn().javaName());
                }
            }
        }
    }

    /**
     * Builds a bulk lookup-key row-tuple {@code IN} predicate from already-projected
     * {@link InputColumnBindingGroup}s: emits
     * {@code DSL.row(t.k1, ...).in(in.stream().map(row -> DSL.row(<per-slot value expr>)).toList())}.
     * Per-row decode for {@link InputColumnBindingGroup.DecodedRecordGroup} and
     * NodeIdDecodeKeys-extracted {@link InputColumnBinding.MapBinding} lives inside the stream
     * lambda (one decode call per arg per row). One shape regardless of key arity (PostgreSQL
     * renders 1-key {@code (col) IN ((v))} identically to {@code col IN (v)}).
     *
     * <p>The direct-return bulk DELETE projects its
     * {@link no.sikt.graphitron.rewrite.model.DeleteRows} carrier's
     * {@code whereColumns()} into these groups via {@link #keyGroupsOf} and calls this directly;
     * there is no {@code TableInputArg}-taking overload.
     */
    private static CodeBlock buildBulkLookupRowIn(
            List<InputColumnBindingGroup> groups,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef) {
        var b = CodeBlock.builder().add("$T.row(", DSL);
        boolean first = true;
        for (var g : groups) {
            for (var col : g.targetColumns()) {
                if (!first) b.add(", ");
                first = false;
                b.add("$T.$L.$L",
                    tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
            }
        }
        // Block-lambda form when any group requires a per-row decode; expression-lambda form
        // (today's all-Direct shape) otherwise, so existing pipeline tests stay byte-identical.
        boolean needsDecodeLambda = groupsNeedDecode(groups);
        if (needsDecodeLambda) {
            b.add(").in(in.stream().map(row -> {\n").indent();
            var lambdaLocals = CodeBlock.builder();
            for (int gi = 0; gi < groups.size(); gi++) {
                var g = groups.get(gi);
                switch (g) {
                    case InputColumnBindingGroup.MapGroup mg -> {
                        for (int bi = 0; bi < mg.bindings().size(); bi++) {
                            var binding = mg.bindings().get(bi);
                            if (leafExtractionOf(binding.extraction()) instanceof CallSiteExtraction.NodeIdDecodeKeys nidk) {
                                String recLocal = "bulkKey" + gi + "_" + bi;
                                var path = accessPathOf(binding.fieldName(), binding.extraction());
                                appendDecodeLocal(lambdaLocals, recLocal, nidk,
                                    ArgCallEmitter.nestedMapValueExpr("row", path), binding.fieldName());
                            }
                        }
                    }
                    case InputColumnBindingGroup.DecodedRecordGroup drg -> {
                        String recLocal = "bulkKey" + gi;
                        appendDecodeLocal(lambdaLocals, recLocal, drg.extraction(),
                            ArgCallEmitter.nestedMapValueExpr("row", drg.accessPath()), drg.sourceFieldName());
                    }
                }
            }
            b.add(lambdaLocals.build());
            b.add("return $T.row(", DSL);
            first = true;
            for (int gi = 0; gi < groups.size(); gi++) {
                var g = groups.get(gi);
                appendBulkRowCells(b, g, gi, first, tablesOnly, tableRef);
                first = first && g.targetColumns().isEmpty();
            }
            b.add(");\n").unindent().add("}).toList())");
        } else {
            b.add(").in(in.stream().map(row -> $T.row(", DSL);
            first = true;
            for (var g : groups) {
                switch (g) {
                    case InputColumnBindingGroup.MapGroup mg -> {
                        for (var binding : mg.bindings()) {
                            if (!first) b.add(", ");
                            first = false;
                            var path = accessPathOf(binding.fieldName(), binding.extraction());
                            b.add("$T.val($L, $T.$L.$L.getDataType())",
                                DSL, ArgCallEmitter.nestedMapValueExpr("row", path),
                                tablesOnly.tablesClass(), tableRef.javaFieldName(), binding.targetColumn().javaName());
                        }
                    }
                    case InputColumnBindingGroup.DecodedRecordGroup drg ->
                        throw new IllegalStateException("groupsNeedDecode bug: DecodedRecordGroup reached the expression-lambda arm");
                }
            }
            b.add(")).toList())");
        }
        return b.build();
    }

    /** True iff any group on the TIA's bindings requires a per-row decode call. */
    private static boolean groupsNeedDecode(List<InputColumnBindingGroup> groups) {
        for (var g : groups) {
            switch (g) {
                case InputColumnBindingGroup.MapGroup mg -> {
                    for (var binding : mg.bindings()) {
                        if (leafExtractionOf(binding.extraction()) instanceof CallSiteExtraction.NodeIdDecodeKeys) return true;
                    }
                }
                case InputColumnBindingGroup.DecodedRecordGroup drg -> { return true; }
            }
        }
        return false;
    }

    /** Block-lambda cell emission for one group; helper for the decode-bearing arm of buildBulkLookupRowIn. */
    private static void appendBulkRowCells(
            CodeBlock.Builder b, InputColumnBindingGroup g, int groupIndex, boolean startFirst,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef) {
        boolean first = startFirst;
        switch (g) {
            case InputColumnBindingGroup.MapGroup mg -> {
                for (int bi = 0; bi < mg.bindings().size(); bi++) {
                    var binding = mg.bindings().get(bi);
                    if (!first) b.add(", ");
                    first = false;
                    if (leafExtractionOf(binding.extraction()) instanceof CallSiteExtraction.NodeIdDecodeKeys) {
                        String recLocal = "bulkKey" + groupIndex + "_" + bi;
                        b.add("$T.val($L.value1(), $T.$L.$L.getDataType())",
                            DSL, recLocal,
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), binding.targetColumn().javaName());
                    } else {
                        var path = accessPathOf(binding.fieldName(), binding.extraction());
                        b.add("$T.val($L, $T.$L.$L.getDataType())",
                            DSL, ArgCallEmitter.nestedMapValueExpr("row", path),
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), binding.targetColumn().javaName());
                    }
                }
            }
            case InputColumnBindingGroup.DecodedRecordGroup drg -> {
                String recLocal = "bulkKey" + groupIndex;
                for (var binding : drg.bindings()) {
                    if (!first) b.add(", ");
                    first = false;
                    b.add("$T.val($L.value$L(), $T.$L.$L.getDataType())",
                        DSL, recLocal, binding.index() + 1,
                        tablesOnly.tablesClass(), tableRef.javaFieldName(), binding.targetColumn().javaName());
                }
            }
        }
    }

    /**
     * Common DML fetcher skeleton shared across the DML verbs. Wraps the verb-specific
     * {@code dmlChain} (e.g. {@code .deleteFrom(...).where(...)} or
     * {@code .insertInto(...).values(...)}) in the standard try/catch + {@code returnSyncSuccess}
     * envelope, then dispatches the {@link no.sikt.graphitron.rewrite.model.DmlReturnExpression}
     * arm to the shared projection-terminator helper. Single point of contact for the
     * try/catch wrapper, the {@code env.getArgument} cast, and the {@code dsl} chain start.
     */
    private static MethodSpec buildDmlFetcher(
            TypeFetcherEmissionContext ctx,
            no.sikt.graphitron.rewrite.model.MutationField.DmlTableField field,
            no.sikt.graphitron.rewrite.model.DmlReturnExpression rex,
            Optional<ErrorChannel.RouterDispatched> errorChannel,
            String inputArgName,
            TableRef tableRef,
            GeneratorUtils.ResolvedTableNames tablesOnly,
            String tableLocal,
            String outputPackage,
            CodeBlock dmlChain,
            DialectRequirement dialectRequirement,
            boolean listInput,
            no.sikt.graphitron.command.LauncherCommand row,
            no.sikt.graphitron.command.CarrierDsl carrierDsl) {
        return buildDmlFetcher(ctx, field, rex, errorChannel, inputArgName, tableRef,
            tablesOnly, tableLocal, outputPackage, dmlChain,
            dialectRequirement, /*postInGuard=*/ CodeBlock.of(""), listInput, row, carrierDsl);
    }

    /**
     * The typed dialect guard plus the optional {@code postInGuard} {@link CodeBlock} and the
     * bulk-input cardinality bit:
     * <ul>
     *   <li>{@code dialectRequirement} — the verb's typed dialect constraint, always present
     *       from the model ({@link DialectRequirement.None} when unconstrained). Rendered by
     *       {@link #emitDialectGuard} immediately after the {@code dsl} local is bound, before the
     *       {@code in} cast. UPSERT carries {@link DialectRequirement.RejectsFamily}({@code ORACLE})
     *       (jOOQ silently translates {@code .onConflict(...)} to {@code MERGE INTO} on Oracle, with
     *       semantics drift) and bulk UPDATE carries {@link DialectRequirement.RequiresFamily}
     *       ({@code POSTGRES}) (the {@code UPDATE ... FROM (VALUES ...)} form is a Postgres
     *       extension); INSERT / DELETE / single-row UPDATE carry {@code None} and emit nothing.</li>
     *   <li>{@code postInGuard} — emitted immediately after the {@code in} cast and before
     *       {@code tableLocal} is bound (and after the empty-list short-circuit when
     *       {@code listInput}). Used by UPDATE / UPSERT to build the dynamic SET map from the
     *       present-key set and run the no-set-fields-present runtime check, and by bulk UPDATE
     *       to run the uniform-shape and duplicate-lookup-key guards before chain construction.</li>
     *   <li>{@code listInput} — when {@code true}, the {@code in} cast lifts to
     *       {@code List<Map<?,?>>} and an empty-list short-circuit is emitted between the cast
     *       and {@code postInGuard}, returning a typed empty {@link DataFetcherResult} without
     *       round-tripping. Invariant #15 guarantees the bulk arm only reaches list-cardinality
     *       return shapes (EncodedList / ProjectedList), so {@code valueType} is already
     *       {@code List<X>} and {@code List.of()} is its typed empty.</li>
     * </ul>
     */
    private static MethodSpec buildDmlFetcher(
            TypeFetcherEmissionContext ctx,
            no.sikt.graphitron.rewrite.model.MutationField.DmlTableField field,
            no.sikt.graphitron.rewrite.model.DmlReturnExpression rex,
            Optional<ErrorChannel.RouterDispatched> errorChannel,
            String inputArgName,
            TableRef tableRef,
            GeneratorUtils.ResolvedTableNames tablesOnly,
            String tableLocal,
            String outputPackage,
            CodeBlock dmlChain,
            DialectRequirement dialectRequirement,
            CodeBlock postInGuard,
            boolean listInput,
            no.sikt.graphitron.command.LauncherCommand row,
            no.sikt.graphitron.command.CarrierDsl carrierDsl) {
        TypeName valueType = switch (rex) {
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.EncodedSingle es -> ClassName.get(String.class);
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.EncodedList el ->
                ParameterizedTypeName.get(ClassName.get(List.class), ClassName.get(String.class));
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedSingle ps -> RECORD;
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedList pl ->
                ParameterizedTypeName.get(ClassName.get(List.class), RECORD);
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.DiscriminatedSingle ds -> RECORD;
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.DiscriminatedList dl ->
                ParameterizedTypeName.get(ClassName.get(List.class), RECORD);
        };
        var builder = MethodSpec.methodBuilder(field.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");

        builder.beginControlFlow("try");
        var tenantDsl = TenantDslEmitter.resolve(ctx, field, outputPackage);
        builder.addCode(tenantDsl.declaration());
        emitDialectGuard(builder, dialectRequirement);
        if (listInput) {
            builder.addStatement("$T<$T<?, ?>> in = env.getArgument($S)",
                ClassName.get(List.class), MAP, inputArgName);
            // Empty-list contract: no round-trip, return typed empty list. Bypasses the
            // projection terminator entirely; jOOQ rejects empty VALUES on every verb, so the
            // short-circuit is mandatory, not just an optimisation.
            builder.beginControlFlow("if (in.isEmpty())")
                .addStatement("return $T.<$T>newResult().data($T.of()).build()",
                    DATA_FETCHER_RESULT, valueType, ClassName.get(List.class))
                .endControlFlow();
        } else {
            builder.addStatement("$T<?, ?> in = ($T<?, ?>) env.getArgument($S)", MAP, MAP, inputArgName);
        }
        if (!postInGuard.isEmpty()) {
            builder.addCode(postInGuard);
        }
        builder.addStatement("$T $L = $T.$L",
            tablesOnly.jooqTableClass(), tableLocal,
            tablesOnly.tablesClass(), tableRef.javaFieldName());

        builder.addCode(emitDmlReturnExpression(ctx, field, rex, row, carrierDsl, valueType,
            tableRef, tablesOnly, outputPackage, dmlChain));
        builder.addCode(returnSyncSuccess(valueType, "payload", tenantDsl.localContextTail()));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(catchArm(outputPackage, errorChannel));
        builder.endControlFlow();
        return builder.build();
    }

    /**
     * Renders the request-time dialect guard from the model's typed {@link DialectRequirement}.
     * Both the {@code RequiresFamily} and {@code RejectsFamily} arms compare
     * {@code dsl.dialect().family().name()} against the family's {@link SqlDialectFamily#jooqFamilyName()}
     * and throw an {@link UnsupportedOperationException} carrying the model's {@code reason()}.
     *
     * <p>The check uses jOOQ's own {@code SQLDialect.family()} rather than
     * {@link SqlDialectFamily#fromDialectName(String)} because emitted code cannot reference the
     * generator-internal {@code SqlDialectFamily} enum: the {@code graphitron} artifact is on a
     * consumer's <em>test</em> classpath only, while these fetchers compile as the consumer's main
     * sources (the generate mojo adds them via {@code addCompileSourceRoot}); generated code sees
     * only its own output package plus jOOQ. jOOQ's {@code family()} collapses every versioned
     * dialect spelling onto its family constant, so the check gates every version of the family.
     * The {@link DialectRequirement.None}
     * arm emits nothing, keeping INSERT / DELETE / single-row UPDATE fetchers guard-free.
     */
    private static void emitDialectGuard(MethodSpec.Builder b, DialectRequirement req) {
        switch (req) {
            case DialectRequirement.None ignored -> { /* no dialect constraint */ }
            case DialectRequirement.RequiresFamily r ->
                b.beginControlFlow("if (!$S.equals(dsl.dialect().family().name()))",
                        r.family().jooqFamilyName())
                 .addStatement("throw new $T($S)", UnsupportedOperationException.class, r.reason())
                 .endControlFlow();
            case DialectRequirement.RejectsFamily r ->
                b.beginControlFlow("if ($S.equals(dsl.dialect().family().name()))",
                        r.family().jooqFamilyName())
                 .addStatement("throw new $T($S)", UnsupportedOperationException.class, r.reason())
                 .endControlFlow();
        }
    }

    /**
     * Emits the projection terminator and the {@code payload} local declaration, dispatched on
     * the pre-resolved {@link no.sikt.graphitron.rewrite.model.DmlReturnExpression} arm. Verb-
     * neutral: takes a pre-built {@code dmlChain} (e.g. {@code .deleteFrom(filmTable).where(...)}
     * or {@code .insertInto(filmTable, cols...).values(...)}) and appends
     * {@code .returningResult(...).fetchOne(...)}. The {@code Projected*} / {@code Discriminated*}
     * arms converge on {@link #emitReentry}: the write halves are identical, and the follow-up
     * SELECT's fork now lives in the launcher row's source arm.
     */
    private static CodeBlock emitDmlReturnExpression(
            TypeFetcherEmissionContext ctx,
            no.sikt.graphitron.rewrite.model.MutationField.DmlTableField field,
            no.sikt.graphitron.rewrite.model.DmlReturnExpression rex,
            no.sikt.graphitron.command.LauncherCommand row,
            no.sikt.graphitron.command.CarrierDsl carrierDsl,
            TypeName valueType,
            TableRef tableRef,
            GeneratorUtils.ResolvedTableNames tablesOnly,
            String outputPackage,
            CodeBlock dmlChain) {
        return switch (rex) {
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.EncodedSingle es ->
                emitEncoded(es.encode(), valueType, tableRef, tablesOnly, dmlChain, /*isList=*/ false);
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.EncodedList el ->
                emitEncoded(el.encode(), valueType, tableRef, tablesOnly, dmlChain, /*isList=*/ true);
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedSingle ignored ->
                emitReentry(ctx, field, row, carrierDsl, valueType, outputPackage, dmlChain);
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedList ignored ->
                emitReentry(ctx, field, row, carrierDsl, valueType, outputPackage, dmlChain);
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.DiscriminatedSingle ignored ->
                emitReentry(ctx, field, row, carrierDsl, valueType, outputPackage, dmlChain);
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.DiscriminatedList ignored ->
                emitReentry(ctx, field, row, carrierDsl, valueType, outputPackage, dmlChain);
        };
    }

    private static CodeBlock emitEncoded(
            no.sikt.graphitron.rewrite.model.HelperRef.Encode encode,
            TypeName valueType, TableRef tableRef,
            GeneratorUtils.ResolvedTableNames tablesOnly,
            CodeBlock dmlChain, boolean isList) {
        // ID return: project the NodeType's key columns and call the per-type encoder helper
        // resolved by the classifier (encode<TypeName>(v0, v1, ...)). The typeId is baked into
        // the method name; no generic encode(typeId, ...) call is emitted from the rewrite.
        var keyCols = encode.paramSignature();
        var body = CodeBlock.builder()
            .add("$T payload = dsl\n", valueType).indent()
            .add(dmlChain)
            .add(".returningResult(");
        for (int i = 0; i < keyCols.size(); i++) {
            if (i > 0) body.add(", ");
            body.add("$T.$L.$L", tablesOnly.tablesClass(), tableRef.javaFieldName(), keyCols.get(i).javaName());
        }
        body.add(")\n");

        var lambda = CodeBlock.builder().add("r -> $T.$L(", encode.encoderClass(), encode.methodName());
        for (int i = 0; i < keyCols.size(); i++) {
            if (i > 0) lambda.add(", ");
            var col = keyCols.get(i);
            lambda.add("r.get($T.$L.$L)", tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
        }
        lambda.add(")");
        body.add(isList ? ".fetch(" : ".fetchOne(").add(lambda.build()).add(");\n").unindent();
        return body.build();
    }

    /**
     * The {@code Projected*} / {@code Discriminated*} DML return arms' shared write-half emit,
     * the two-step shape: the DML runs inside {@code dsl.transactionResult(tx -> ...)} with a
     * key-only {@code RETURNING} clause derived from the launcher row's correlation (the one
     * fact the companion's {@code keys} parameter type also derives from, so the assignment
     * compatibility across the generated call boundary is structural); the payload re-select
     * lives in the {@code rows<Name>} reentry companion the row renders
     * ({@link no.sikt.graphitron.render.RootLauncherRenderer} over the row's
     * {@link no.sikt.graphitron.command.LaunchSource.Reentry} arm), called with the captured
     * keys outside the transaction. The write half, the dialect guard, the no-match guard and
     * the channel envelope stay in this fetcher: the mutation entry point is deliberately not
     * thin, and only the re-select is the launcher unit's.
     *
     * <p>Read errors during the follow-up SELECT or during nested traversal propagate as field
     * errors and cannot undo the DML. Mirror of the carrier path's two-step shape in
     * {@link #buildMutationDmlRecordFetcher} and the data-field fetcher emitted by
     * {@code FetcherEmitter.buildSingleRecordTableFetcherValue}; the difference is that the
     * direct-{@code @table} path keeps the follow-up SELECT in the same fetchers class and
     * returns a {@code Record} (or {@code List<Record>}) directly, where the carrier path hands
     * the key Result to the data field's fetcher and lets it run the SELECT.
     */
    private static CodeBlock emitReentry(
            TypeFetcherEmissionContext ctx,
            no.sikt.graphitron.rewrite.model.MutationField.DmlTableField field,
            no.sikt.graphitron.command.LauncherCommand row,
            no.sikt.graphitron.command.CarrierDsl carrierDsl,
            TypeName valueType,
            String outputPackage,
            CodeBlock dmlChain) {
        if (row == null) {
            throw new IllegalStateException(
                "Graphitron generator bug (DML reentry): coordinate '" + field.qualifiedName()
                + "' reached the projected/discriminated write emit with no launcher row; the"
                + " producer mints a reentry companion row for every Projected*/Discriminated*"
                + " return arm");
        }
        var reentry = (no.sikt.graphitron.command.LaunchSource.Reentry) row.source();
        boolean isList = row.result() instanceof no.sikt.graphitron.command.ResultShape.RecordList;
        String rowsName = row.unit().methodName();
        ctx.addCompanionMethod(no.sikt.graphitron.render.RootLauncherRenderer.render(
            row, carrierDsl, TenantDslEmitter.resolve(ctx, field, outputPackage).declaration(),
            ctx.argPathHelpers(), ctx.projectedKeyHost()));

        var body = CodeBlock.builder()
            .add(emitKeysTransaction(reentry.correlation(), dmlChain, isList));

        if (!isList) {
            // Single-row UPDATE / DELETE with no match: keys is null. Skip the follow-up SELECT
            // and return null; matches the pre-two-step .fetchOne(r -> r) contract.
            body.add("if (keys == null) return $T.<$T>newResult().data(null).build();\n",
                DATA_FETCHER_RESULT, valueType);
        }

        body.add("$T payload = $L(keys, env);\n", valueType, rowsName);
        return body.build();
    }

    /**
     * Step 1 of the two-step DML re-projection ({@link #emitReentry}): runs the {@code dmlChain}
     * inside {@code dsl.transactionResult(...)} with a {@code RETURNING} clause over the
     * reentry correlation's columns and declares a {@code keys} local holding the committed
     * keys ({@code RecordN<...>} for single, {@code Result<RecordN<...>>} for list). Requires
     * {@code dsl} in scope; the caller supplies the verb-specific {@code dmlChain}. The column
     * list and the companion's keys parameter derive from the one correlation fact.
     */
    private static CodeBlock emitKeysTransaction(
            ParentCorrelation.OnLiftedSlots correlation,
            CodeBlock dmlChain, boolean isList) {
        var keyCols = correlation.columns();
        var owner = correlation.targetTable();
        var keyRowType = no.sikt.graphitron.rewrite.model.SourceKey.keyElementType(
            new no.sikt.graphitron.rewrite.model.SourceKey.Wrap.Record(), keyCols);
        TypeName keysType = isList
            ? ParameterizedTypeName.get(RESULT, keyRowType)
            : keyRowType;

        var body = CodeBlock.builder()
            .add("$T keys = dsl.transactionResult(tx -> $T.using(tx)\n", keysType, DSL).indent()
            .add(dmlChain)
            .add(".returningResult(");
        for (int i = 0; i < keyCols.size(); i++) {
            if (i > 0) body.add(", ");
            var col = keyCols.get(i);
            body.add("$T.$L.$L", owner.constantsClass(), owner.javaFieldName(), col.javaName());
        }
        body.add(")\n")
            .add(isList ? ".fetch());\n" : ".fetchOne());\n").unindent();
        return body.build();
    }

    /**
     * Builds the {@code orderBy} variable declaration for a fetcher body.
     *
     * <p>When {@code fieldName} is non-null and {@code orderBy} is an {@link OrderBySpec.Argument},
     * emits a call to the {@code <fieldName>OrderBy} helper method. Otherwise, inlines the
     * fixed or empty list.
     */
    private static CodeBlock buildOrderByCode(OrderBySpec orderBy, String fieldName, String srcAlias) {
        var code = CodeBlock.builder();
        switch (orderBy) {
            case OrderBySpec.Fixed fixed -> {
                if (fixed.columns().isEmpty()) {
                    code.addStatement("$T<$T<?>> orderBy = $T.of()", LIST, SORT_FIELD, LIST);
                } else {
                    code.addStatement("$T<$T<?>> orderBy = $T.of($L)", LIST, SORT_FIELD, LIST,
                        no.sikt.graphitron.render.OrderByFragments.fixedSortParts(fixed, srcAlias));
                }
            }
            case OrderBySpec.Argument arg -> {
                if (fieldName != null) {
                    // Helper now returns OrderByResult; extract just the sort fields for non-connection fetchers.
                    // Pass srcAlias so the helper's column refs bind to the caller's aliased Table instance.
                    code.addStatement("$T orderBy = $LOrderBy(env, $L).sortFields()", SORT_FIELD_LIST, fieldName, srcAlias);
                } else {
                    code.add(buildOrderByCode(arg.base(), null, srcAlias));
                }
            }
            case OrderBySpec.None none ->
                code.addStatement("$T<$T<?>> orderBy = $T.of()", LIST, SORT_FIELD, LIST);
        }
        return code.build();
    }

    // -----------------------------------------------------------------------
    // OrderBy helper method generation
    // -----------------------------------------------------------------------

    /**
     * Generates the private static {@code <fieldName>OrderBy(DataFetchingEnvironment env, <Table> aliased)}
     * helper.
     *
     * <p>The helper reads the {@code @orderBy} argument from {@code env}, dispatches over the
     * sort-field name via a switch expression (single arg) or accumulates into a list (list arg),
     * and returns an {@code OrderByResult}. Fetcher bodies call this helper instead of
     * inlining the dispatch logic.
     *
     * <p>The aliased table instance is a parameter rather than a locally-declared Table, so
     * callers with different aliasing schemes share one helper. Root connection fetchers pass
     * their canonical {@code tableLocal} (the un-aliased {@code Tables.FILM}); Split+Connection
     * rows methods pass the FK-chain terminal alias (e.g. {@code Tables.ACTOR.as("actorsConnection_a1")}).
     *
     * <p>{@code methodName} arrives from the caller, derived through the naming vocabulary
     * ({@code GeneratedUnits.orderByHelperMethod}), so the helper's name has one formula with
     * the launcher rows that reference it.
     */
    private static MethodSpec buildOrderByHelperMethod(
            String methodName,
            OrderBySpec.Argument arg,
            GeneratorUtils.ResolvedTableNames names,
            TableRef tableRef, String outputPackage) {

        var orderByResultClass = ClassName.get(
            outputPackage + ".util", OrderByResultClassGenerator.CLASS_NAME);

        String tableLocal = names.tableLocalName();
        var builder = MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(orderByResultClass)
            .addParameter(ENV, "env")
            .addParameter(names.jooqTableClass(), tableLocal);

        var baseExpr = buildBaseReturnExpr(arg.base(), tableLocal, outputPackage);
        if (arg.list()) {
            builder.addCode(buildListArgOrderByBody(arg, baseExpr, tableLocal, outputPackage));
        } else {
            builder.addCode(buildSingleArgOrderByBody(arg, baseExpr, tableLocal, outputPackage));
        }

        return builder.build();
    }

    /**
     * Returns the fallback expression ({@code new OrderByResult(List.of(table.COL.asc()), List.of(table.COL))}
     * or {@code new OrderByResult(List.of(), List.of())}) used when no {@code @orderBy} argument is
     * supplied at runtime.
     */
    private static CodeBlock buildBaseReturnExpr(OrderBySpec base, String srcAlias, String outputPackage) {
        var orderByResultClass = ClassName.get(
            outputPackage + ".util", OrderByResultClassGenerator.CLASS_NAME);
        return switch (base) {
            case OrderBySpec.Fixed fixed when !fixed.columns().isEmpty() ->
                CodeBlock.of("new $T($T.of($L), $T.of($L))",
                    orderByResultClass, LIST,
                    no.sikt.graphitron.render.OrderByFragments.fixedSortParts(fixed, srcAlias),
                    LIST,
                    no.sikt.graphitron.render.OrderByFragments.fixedColumnParts(fixed, srcAlias));
            default -> CodeBlock.of("new $T($T.of(), $T.of())", orderByResultClass, LIST, LIST);
        };
    }

    /**
     * Builds the body for an orderBy helper where the argument is a single map
     * ({@code Map<String, Object>}).
     *
     * <pre>{@code
     * Map<String, Object> orderArg = env.getArgument("order");
     * if (orderArg == null) return List.of(table.FILM_ID.asc());
     * String field = (String) orderArg.get("field");
     * String dir   = (String) orderArg.get("direction");
     * return switch (field) {
     *     case "TITLE" -> List.of("DESC".equals(dir) ? table.TITLE.desc() : table.TITLE.asc());
     *     default -> List.of(table.FILM_ID.asc());
     * };
     * }</pre>
     */
    private static CodeBlock buildSingleArgOrderByBody(OrderBySpec.Argument arg, CodeBlock baseExpr, String srcAlias, String outputPackage) {
        var code = CodeBlock.builder();
        var orderByResultClass = ClassName.get(
            outputPackage + ".util", OrderByResultClassGenerator.CLASS_NAME);
        code.addStatement("$T<$T, $T> orderArg = env.getArgument($S)", MAP, String.class, Object.class, arg.name());
        code.add("if (orderArg == null) return $L;\n", baseExpr);
        code.addStatement("$T field = ($T) orderArg.get($S)", String.class, String.class, arg.sortFieldName());
        code.addStatement("$T dir = ($T) orderArg.get($S)", String.class, String.class, arg.directionFieldName());
        code.add("return switch (field) {\n");
        code.indent();
        for (var namedOrder : arg.namedOrders()) {
            var cols = namedOrder.order().columns();
            var sortParts = CodeBlock.builder();
            var colParts = CodeBlock.builder();
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) { sortParts.add(", "); colParts.add(", "); }
                var col = cols.get(i);
                if (namedOrder.order().uniformAsc()) {
                    // Uniform-ASC: runtime `dir` flips the whole spec.
                    sortParts.add("$S.equals(dir) ? $L.$L.desc() : $L.$L.asc()",
                        "DESC", srcAlias, col.column().javaName(), srcAlias, col.column().javaName());
                } else {
                    // Direction-locked: SDL author baked in per-entry directions; ignore runtime dir.
                    sortParts.add("$L.$L.$L()",
                        srcAlias, col.column().javaName(), col.direction().jooqMethodName());
                }
                colParts.add("$L.$L", srcAlias, col.column().javaName());
            }
            code.add("case $S -> new $T($T.of($L), $T.of($L));\n",
                namedOrder.name(), orderByResultClass, LIST, sortParts.build(), LIST, colParts.build());
        }
        code.add("default -> $L;\n", baseExpr);
        code.unindent();
        code.add("};\n");
        return code.build();
    }

    /**
     * Builds the body for an orderBy helper where the argument is a list of maps
     * ({@code List<Map<String, Object>>}).
     *
     * <pre>{@code
     * List<Map<String, Object>> orderArgs = env.getArgument("order");
     * if (orderArgs == null || orderArgs.isEmpty()) return List.of(table.FILM_ID.asc());
     * var parts = new ArrayList<SortField<?>>();
     * for (var entry : orderArgs) {
     *     String f = (String) entry.get("field");
     *     String d = (String) entry.get("direction");
     *     switch (f) {
     *         case "TITLE" -> parts.add("DESC".equals(d) ? table.TITLE.desc() : table.TITLE.asc());
     *     }
     * }
     * return parts;
     * }</pre>
     */
    private static CodeBlock buildListArgOrderByBody(OrderBySpec.Argument arg, CodeBlock baseExpr, String srcAlias, String outputPackage) {
        var code = CodeBlock.builder();
        var JOOQ_FIELD = ClassName.get("org.jooq", "Field");
        var WILDCARD_FIELD = ParameterizedTypeName.get(JOOQ_FIELD,
            no.sikt.graphitron.javapoet.WildcardTypeName.subtypeOf(Object.class));
        var orderByResultClass = ClassName.get(
            outputPackage + ".util", OrderByResultClassGenerator.CLASS_NAME);
        code.addStatement("$T<$T<$T, $T>> orderArgs = env.getArgument($S)",
            LIST, MAP, String.class, Object.class, arg.name());
        code.add("if (orderArgs == null || orderArgs.isEmpty()) return $L;\n", baseExpr);
        code.addStatement("$T<$T<?>> sortParts = new $T<>()", ARRAY_LIST, SORT_FIELD, ARRAY_LIST);
        code.addStatement("$T<$T> colParts = new $T<>()", ARRAY_LIST, WILDCARD_FIELD, ARRAY_LIST);
        code.add("for ($T<$T, $T> entry : orderArgs) {\n", MAP, String.class, Object.class);
        code.indent();
        code.addStatement("$T f = ($T) entry.get($S)", String.class, String.class, arg.sortFieldName());
        code.addStatement("$T d = ($T) entry.get($S)", String.class, String.class, arg.directionFieldName());
        code.add("switch (f) {\n");
        code.indent();
        for (var namedOrder : arg.namedOrders()) {
            var cols = namedOrder.order().columns();
            code.add("case $S -> {\n", namedOrder.name());
            code.indent();
            for (var col : cols) {
                if (namedOrder.order().uniformAsc()) {
                    code.addStatement("sortParts.add($S.equals(d) ? $L.$L.desc() : $L.$L.asc())",
                        "DESC", srcAlias, col.column().javaName(), srcAlias, col.column().javaName());
                } else {
                    code.addStatement("sortParts.add($L.$L.$L())",
                        srcAlias, col.column().javaName(), col.direction().jooqMethodName());
                }
                code.addStatement("colParts.add($L.$L)", srcAlias, col.column().javaName());
            }
            code.unindent();
            code.add("}\n");
        }
        code.unindent();
        code.add("}\n");
        code.unindent();
        code.add("}\n");
        code.addStatement("return new $T(sortParts, colParts)", orderByResultClass);
        return code.build();
    }

    private static MethodSpec buildQueryNodeFetcher(TypeFetcherEmissionContext ctx, QueryField.QueryNodeField field, String outputPackage) {
        var queryNodeFetcher = ClassName.get(outputPackage + ".fetchers",
            no.sikt.graphitron.rewrite.generators.util.QueryNodeFetcherClassGenerator.CLASS_NAME);
        TypeName valueType = RECORD;
        var builder = MethodSpec.methodBuilder(field.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");
        builder.beginControlFlow("try");
        builder.addStatement("$T payload = $T.$L(env)",
            valueType, queryNodeFetcher,
            no.sikt.graphitron.rewrite.generators.util.QueryNodeFetcherClassGenerator.DISPATCH_METHOD);
        builder.addCode(returnSyncSuccess(valueType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(noChannelCatchArm(outputPackage));
        builder.endControlFlow();
        return builder.build();
    }

    private static MethodSpec buildQueryNodesFetcher(TypeFetcherEmissionContext ctx, QueryField.QueryNodesField field, String outputPackage) {
        var queryNodeFetcher = ClassName.get(outputPackage + ".fetchers",
            no.sikt.graphitron.rewrite.generators.util.QueryNodeFetcherClassGenerator.CLASS_NAME);
        TypeName valueType = ParameterizedTypeName.get(LIST, RECORD);
        return MethodSpec.methodBuilder(field.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(asyncResultType(valueType))
            .addParameter(ENV, "env")
            .addCode(CodeBlock.builder()
                .add("return $T.$L(env)\n", queryNodeFetcher,
                    no.sikt.graphitron.rewrite.generators.util.QueryNodeFetcherClassGenerator.DISPATCH_NODES_METHOD)
                .add("    ").add(asyncWrapTail(valueType, outputPackage, Optional.empty())).add(";\n")
                .build())
            .build();
    }

    /**
     * Emits the fetcher for a {@link MutationField.MutationDmlRecordField} — the
     * record-returning DML mutation. Body is two-step: the DML chain (per-kind) runs inside
     * {@code dsl.transactionResult(tx -> DSL.using(tx)....)}, projects the input table's PK
     * columns via {@code .returningResult(PK1, PK2, ...)}, and returns a single
     * {@code RecordN<...>} via {@code .fetchOne()}. The transaction commits when
     * {@code transactionResult} returns; the materialised key Record outlives it, and the
     * response SELECT happens later in the data field's
     * record-sourced {@link ChildField.BatchedTableField} fetcher — outside the transaction, so read
     * errors during traversal cannot undo the DML.
     *
     * <p>DML chain construction reuses the existing per-kind helpers
     * ({@link #buildPerCellValueList}, {@link #buildLookupWhereSingleRow}) so the SET / WHERE /
     * ON CONFLICT logic stays in lock-step with the direct-{@code @table} fetcher. DELETE is
     * not handled here because the mutation classifier rejects DELETE-with-carrier; the row
     * is gone before the response SELECT can read it. Bulk-input + single-payload combinations
     * are rejected upstream by {@code MutationInputResolver.validateReturnType} (Invariant
     * #15); only single-cardinality input + single-cardinality payload reaches this fetcher.
     *
     * <p><b>Design decision: {@code .returningResult(pkCols)} not {@code .returning(*)}.</b>
     * The PK-only RETURNING keeps the write transaction minimal: the data-field projection
     * (potentially many columns, joined tables, computed expressions) runs in a separate
     * read-only SELECT after {@code transactionResult} returns. Switching to
     * {@code .returning(*)} and projecting the captured row directly would conflate two
     * concerns — the write transaction would carry the full read-projection's locking
     * footprint, and partial-projection cases (the response selection is a subset of the table)
     * would still need the follow-up SELECT for joined or computed fields. The PK echo is the
     * narrowest payload the data-field fetcher needs and lives inside the smallest possible
     * transaction window.
     */
    private static MethodSpec buildMutationDmlRecordFetcher(
            TypeFetcherEmissionContext ctx, MutationField.MutationDmlRecordField f, String outputPackage) {
        // One fetcher skeleton, per-write-arm chain: the Insert / Upsert arms drive the
        // statement off the @table input arg, the Update / Delete arms off their walker
        // carriers (the same sources the direct-return bodies read).
        return switch (f.write()) {
            case OperationMember.Write.Insert _, OperationMember.Write.Upsert _ -> {
                var input = recordCarrierInput(f.write());
                yield buildSingleRecordTwoStepFetcher(
                    ctx, f, input.name(), input.inputTable(), f.errorChannel(), f.qualifiedName(),
                    (tablesOnly, tableLocal) -> buildDmlChainForRecord(f.write(), input.inputTable(), tablesOnly, tableLocal),
                    outputPackage);
            }
            case OperationMember.Write.Update w -> {
                var setGroups = setGroupsOf(w.updateRows().setColumns());
                var keyGroups = keyGroupsOf(w.updateRows().keyColumns());
                yield buildSingleRecordTwoStepFetcher(
                    ctx, f, w.inputArg().name(), w.inputArg().table(), f.errorChannel(), f.qualifiedName(),
                    (tablesOnly, tableLocal) -> buildCarrierUpdateChainSingle(
                        setGroups, keyGroups, w.inputArg().table(), tablesOnly, tableLocal),
                    outputPackage);
            }
            case OperationMember.Write.Delete w -> {
                var whereGroups = keyGroupsOf(w.deleteRows().whereColumns());
                yield buildSingleRecordTwoStepFetcher(
                    ctx, f, w.inputArg().name(), w.inputArg().table(), f.errorChannel(), f.qualifiedName(),
                    (tablesOnly, tableLocal) -> buildRecordDeleteChain(
                        whereGroups, w.inputArg().table(), tablesOnly, tableLocal),
                    outputPackage);
            }
        };
    }

    /**
     * The record carriers' {@code @table} input surface: the Insert / Upsert arms carry it; an
     * Update or Delete arm on a record carrier is rejected by the leaf's compact constructor,
     * so those arms are drift guards, not dispatch.
     */
    private static no.sikt.graphitron.rewrite.ArgumentRef.InputTypeArg.TableInputArg recordCarrierInput(
            OperationMember.Write.Dml write) {
        return switch (write) {
            case OperationMember.Write.Insert i -> i.input();
            case OperationMember.Write.Upsert u -> u.input();
            case OperationMember.Write.Update _, OperationMember.Write.Delete _ ->
                throw new IllegalStateException(
                    "record-backed DML carrier holds a write arm its constructor rejects: "
                    + write.getClass().getSimpleName());
        };
    }

    /**
 * The two-step single-record DML emit skeleton shared by
     * {@link #buildMutationDmlRecordFetcher} (record-carrier INSERT/UPSERT/DELETE, SET/WHERE off the
     * {@code TableInputArg}) and {@link #buildMutationUpdatePayloadFetcher} (payload-returning
     * UPDATE, SET/WHERE off the {@link UpdateRows} carrier). The shape is invariant across both: a
     * PK-only {@code .returningResult(pkCols).fetchOne()} inside {@code dsl.transactionResult(...)},
     * a {@link #returnSyncSuccess} wrap, and a {@link #catchArm} routing thrown exceptions through
     * the error channel with a {@link #singleRecordSentinelFor} non-null sentinel. Only the DML
     * chain (and its pre-DML guards) varies; {@code chainFn} is the seam, parameterised on the
     * resolved table names rather than threading a {@code DmlKind} that re-switches internally.
     */
    private static MethodSpec buildSingleRecordTwoStepFetcher(
            TypeFetcherEmissionContext ctx, no.sikt.graphitron.rewrite.model.OutputField field, String argName,
            TableRef tableRef, Optional<ErrorChannel.RouterDispatched> errorChannel, String qualifiedName,
            java.util.function.BiFunction<GeneratorUtils.ResolvedTableNames, String, DmlChainAndGuards> chainFn,
            String outputPackage) {
        var tablesOnly = GeneratorUtils.ResolvedTableNames.ofTable(tableRef);
        String tableLocal = tablesOnly.tableLocalName();
        var pkCols = tableRef.primaryKeyColumns();
        if (pkCols.isEmpty()) {
            throw new IllegalStateException(
                "Payload-returning DML fetcher '" + qualifiedName + "' references table '"
                + tableRef.tableName() + "' that has no primary key; admission requires PK columns");
        }
        TypeName payloadType = no.sikt.graphitron.rewrite.model.SourceKey.keyElementType(
            new no.sikt.graphitron.rewrite.model.SourceKey.Wrap.Record(), pkCols);

        var builder = MethodSpec.methodBuilder(field.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(payloadType))
            .addParameter(ENV, "env");
        builder.beginControlFlow("try");
        var tenantDsl = TenantDslEmitter.resolve(ctx, field, outputPackage);
        builder.addCode(tenantDsl.declaration());
        builder.addStatement("$T<?, ?> in = ($T<?, ?>) env.getArgument($S)", MAP, MAP, argName);
        builder.addStatement("$T $L = $T.$L",
            tablesOnly.jooqTableClass(), tableLocal, tablesOnly.tablesClass(), tableRef.javaFieldName());

        var chainAndGuards = chainFn.apply(tablesOnly, tableLocal);
        builder.addCode(chainAndGuards.preGuard());

        var dmlEmit = CodeBlock.builder()
            .add("$T payload = dsl.transactionResult(tx -> $T.using(tx)\n", payloadType, DSL).indent()
            .add(chainAndGuards.chain())
            .add(".returningResult(");
        for (int i = 0; i < pkCols.size(); i++) {
            if (i > 0) dmlEmit.add(", ");
            dmlEmit.add("$T.$L.$L", tablesOnly.tablesClass(), tableRef.javaFieldName(), pkCols.get(i).javaName());
        }
        dmlEmit.add(")\n")
            .add(".fetchOne());\n").unindent();
        builder.addCode(dmlEmit.build());

        builder.addCode(returnSyncSuccess(payloadType, "payload", tenantDsl.localContextTail()));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(catchArm(outputPackage, errorChannel,
            no.sikt.graphitron.render.RecordSentinel.single(tableRef, pkCols)));
        builder.endControlFlow();
        return builder.build();
    }

    /**
 * The carrier-driven single-row UPDATE chain for the payload-returning UPDATE fetcher.
     * Mirrors {@link #buildMutationUpdateFetcher}'s single-row body (the direct-return path): a
     * dynamic SET map built from the carrier's {@code setColumns()} so absent fields drop out
     * (PATCH semantics), a runtime empty-SET guard, and the lookup-WHERE built from the carrier's
     * {@code keyColumns()}. The enclosing {@link #buildSingleRecordTwoStepFetcher} appends
     * {@code .returningResult(pkCols).fetchOne()} inside {@code transactionResult}.
     */
    private static DmlChainAndGuards buildCarrierUpdateChainSingle(
            List<SetGroup> setGroups, List<InputColumnBindingGroup> keyGroups,
            TableRef tableRef, GeneratorUtils.ResolvedTableNames tablesOnly, String tableLocal) {
        var fieldClass = ClassName.get("org.jooq", "Field");
        var linkedHashMap = ClassName.get("java.util", "LinkedHashMap");
        var preGuard = CodeBlock.builder();
        preGuard.addStatement("$T<$T<?>, Object> sets = new $T<>()", MAP, fieldClass, linkedHashMap);
        emitSetMapPuts(preGuard, setGroups, "sets", "in", "in", "setKey", tablesOnly, tableRef);
        // Runtime PATCH guard: the carrier guarantees the schema has at least one settable column,
        // but a caller may omit every set-field value (sending only key columns); fail with a
        // friendly message rather than letting jOOQ reject an empty SET map.
        preGuard.beginControlFlow("if (sets.isEmpty())")
            .addStatement("throw new $T($S)", IllegalArgumentException.class,
                "@mutation(typeName: UPDATE) call has no settable fields present; "
                    + "only key fields were provided")
            .endControlFlow();
        var whereChunk = buildLookupWhereSingleRow(keyGroups, tablesOnly, tableRef, "in");
        preGuard.add(whereChunk.decodeLocals());
        var chain = CodeBlock.builder()
            .add(".update($L)\n", tableLocal)
            .add(".set(sets)\n")
            .add(".where(").add(whereChunk.whereExpr()).add(")\n")
            .build();
        return new DmlChainAndGuards(chain, preGuard.build());
    }

    /** Pair: the DML chain (everything from {@code .insertInto(...)} through {@code .doUpdate()....}) plus any pre-DML guard statements (e.g. dynamic SET-map construction). */
    private record DmlChainAndGuards(CodeBlock chain, CodeBlock preGuard) {}

    private static DmlChainAndGuards buildDmlChainForRecord(
            OperationMember.Write.Dml write,
            TableRef tableRef,
            GeneratorUtils.ResolvedTableNames tablesOnly,
            String tableLocal) {
        return switch (write) {
            case OperationMember.Write.Insert i -> buildRecordInsertChain(i.input(), tableRef, tablesOnly, tableLocal);
            case OperationMember.Write.Upsert u -> buildRecordUpsertChain(u.input(), tableRef, tablesOnly, tableLocal);
            // Unreachable: MutationDmlRecordField's compact constructor rejects these arms; the
            // payload-returning UPDATE / DELETE ride their walker-carrier leaves.
            case OperationMember.Write.Update _, OperationMember.Write.Delete _ ->
                throw new IllegalStateException(
                    "record-backed DML carrier holds a write arm its constructor rejects: "
                    + write.getClass().getSimpleName());
        };
    }

    /**
     * Single-row DELETE chain for the payload-returning Delete write arm on the record
     * carrier. Mirrors the direct-return DELETE chain ({@link #buildMutationDeleteFetcher}): same
     * WHERE shape, no SET clause. The WHERE columns are sourced from the
     * {@link no.sikt.graphitron.rewrite.model.DeleteRows} carrier's
     * {@code whereColumns()} (projected to {@link InputColumnBindingGroup}s via {@link #keyGroupsOf}),
     * not {@code tia.fieldBindings()}, so the payload DELETE does not depend on a {@code TableInputArg}.
     * The enclosing {@link #buildMutationDeletePayloadFetcher} adds {@code .returningResult(pkCols)} so
     * the fetcher's value (consumed by the per-field
     * {@link no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdFieldFromReturning}
     * carrier) is a PK-only RETURNING Record.
     */
    private static DmlChainAndGuards buildRecordDeleteChain(
            List<InputColumnBindingGroup> whereGroups,
            TableRef tableRef, GeneratorUtils.ResolvedTableNames tablesOnly, String tableLocal) {
        var whereChunk = buildLookupWhereSingleRow(whereGroups, tablesOnly, tableRef, "in");
        var preGuard = CodeBlock.builder().add(whereChunk.decodeLocals());
        var chain = CodeBlock.builder()
            .add(".deleteFrom($L)\n", tableLocal)
            .add(".where(").add(whereChunk.whereExpr()).add(")\n")
            .build();
        return new DmlChainAndGuards(chain, preGuard.build());
    }

    private static DmlChainAndGuards buildRecordInsertChain(
            no.sikt.graphitron.rewrite.ArgumentRef.InputTypeArg.TableInputArg tia,
            TableRef tableRef, GeneratorUtils.ResolvedTableNames tablesOnly, String tableLocal) {
        var fields = tia.fields();
        var colList = buildInsertColumnList(fields, tablesOnly, tableRef);
        var preGuard = CodeBlock.builder();
        var chain = CodeBlock.builder()
            .add(".insertInto($L, ", tableLocal).add(colList).add(")\n");
        if (tia.list()) {
            boolean hasDecodeLocals = anyNodeIdCarrier(fields);
            if (hasDecodeLocals) {
                chain.add(".valuesOfRows(in.stream()\n").indent()
                    .add(".map(row -> {\n").indent()
                    .add(buildInsertDecodeLocals(fields, "row", "insertKey", tablesOnly, tableRef))
                    .add("return $T.row(\n", DSL).indent()
                    .add(buildPerCellValueList(fields, tablesOnly, tableRef, "row", "insertKey")).unindent()
                    .add(");\n").unindent()
                    .add("})\n")
                    .add(".toList())\n").unindent();
            } else {
                chain.add(".valuesOfRows(in.stream()\n").indent()
                    .add(".map(row -> $T.row(\n", DSL).indent()
                    .add(buildPerCellValueList(fields, tablesOnly, tableRef, "row", "insertKey")).unindent()
                    .add("))\n")
                    .add(".toList())\n").unindent();
            }
        } else {
            preGuard.add(buildInsertDecodeLocals(fields, "in", "insertKey", tablesOnly, tableRef));
            chain.add(".values(\n").indent()
                .add(buildPerCellValueList(fields, tablesOnly, tableRef, "in", "insertKey")).unindent()
                .add(")\n");
        }
        return new DmlChainAndGuards(chain.build(), preGuard.build());
    }

    private static DmlChainAndGuards buildRecordUpsertChain(
            no.sikt.graphitron.rewrite.ArgumentRef.InputTypeArg.TableInputArg tia,
            TableRef tableRef, GeneratorUtils.ResolvedTableNames tablesOnly, String tableLocal) {
        if (tia.list()) {
            throw new UnsupportedOperationException(
                "Bulk UPSERT on MutationDmlRecordField is not yet implemented; use single-input "
                    + "UPSERT or open a follow-up for the bulk-conflict shape");
        }
        var fields = tia.fields();
        var colList = buildInsertColumnList(fields, tablesOnly, tableRef);
        var fieldClass = ClassName.get("org.jooq", "Field");
        var linkedHashMap = ClassName.get("java.util", "LinkedHashMap");
        var preGuard = CodeBlock.builder();
        if (!tia.setFields().isEmpty()) {
            preGuard.addStatement("$T<$T<?>, Object> setsUpdate = new $T<>()", MAP, fieldClass, linkedHashMap);
            emitSetExcludedPuts(preGuard, tia.setFields(), "setsUpdate", "in", "containsKey",
                tablesOnly, tableRef);
            preGuard.beginControlFlow("if (setsUpdate.isEmpty())")
                .addStatement("throw new $T($S)", IllegalArgumentException.class,
                    "@mutation(typeName: UPSERT) call has no settable fields present; "
                        + "only @lookupKey fields were provided")
                .endControlFlow();
        }
        preGuard.add(buildInsertDecodeLocals(fields, "in", "insertKey", tablesOnly, tableRef));
        var conflictCols = CodeBlock.builder();
        var conflictTargetColumns = new ArrayList<no.sikt.graphitron.rewrite.model.ColumnRef>();
        for (var g : tia.fieldBindings()) conflictTargetColumns.addAll(g.targetColumns());
        for (int i = 0; i < conflictTargetColumns.size(); i++) {
            if (i > 0) conflictCols.add(", ");
            conflictCols.add("$T.$L.$L",
                tablesOnly.tablesClass(), tableRef.javaFieldName(),
                conflictTargetColumns.get(i).javaName());
        }
        var chain = CodeBlock.builder()
            .add(".insertInto($L, ", tableLocal).add(colList).add(")\n")
            .add(".values(\n").indent()
            .add(buildPerCellValueList(fields, tablesOnly, tableRef, "in", "insertKey")).unindent()
            .add(")\n")
            .add(".onConflict(").add(conflictCols.build()).add(")\n");
        if (!tia.setFields().isEmpty()) {
            chain.add(".doUpdate()\n").add(".set(setsUpdate)\n");
        } else {
            chain.add(".doNothing()\n");
        }
        return new DmlChainAndGuards(chain.build(), preGuard.build());
    }

    /**
     * Emits the fetcher for a {@link MutationField.MutationBulkDmlRecordField}: a record-
     * returning DML mutation with bulk DML input and a list-shaped data field on the
     * carrier. The fetcher loops the input list, runs one DML per row inside
     * {@code dsl.transactionResult(...)}, collects the PK records into a typed
     * {@code Result<RecordN<...>>} in input order, and returns the accumulated Result. The
     * downstream data field's fetcher ({@link FetcherEmitter#buildSingleRecordTableFetcherValue}
     * with {@link no.sikt.graphitron.rewrite.model.Arity#MANY}) reads that Result
     * via {@code env.getSource()} and runs the bulk response SELECT outside the transaction.
     *
     * <p><b>Order preservation invariant.</b> {@code output.data[i]} corresponds to
     * {@code input[i]} for all {@code i ∈ [0, N)}. The Java for-each loop iterates the input
     * list in declaration order; {@code Result.add(record)} preserves insertion order; the
     * upstream {@code Result<RecordN<PK>>} therefore lands at the data-field fetcher with
     * PKs in input order. The downstream SELECT's {@code WHERE pk IN (...)} does not preserve
     * order, but {@link FetcherEmitter}'s {@code buildSingleRecordTableFetcherValue}
     * {@link no.sikt.graphitron.rewrite.model.Arity#MANY} arm re-keys the SELECT result into a PK-indexed map and walks
     * {@code source.getValues(PK)} to project rows in input order — input order is a property
     * of the emitted Java, not of the SQL planner's choice. The deliberately-non-PK-ordered
     * round-trip in {@code DmlBulkMutationsExecutionTest} is the runtime audit of this invariant.
     *
     * <p>Empty-list input: short-circuits before opening the transaction, returning an empty
     * typed {@code Result} (mirrors the empty-input short-circuit on the direct-{@code @table}
     * bulk arms).
     *
     * <p>DELETE-with-payload-return is rejected at the compact-constructor on
     * {@link MutationField.MutationBulkDmlRecordField}; UPSERT is deferred under the
     * cardinality-safety regime, also rejected at the compact-constructor.
     *
     * <p><b>Design decision: per-row {@code .returningResult(pkCols)} not {@code .returning(*)}.</b>
     * Same rationale as {@link #buildMutationDmlRecordFetcher}: minimise the transaction window
     * by returning only the PK echo, and project the data-field response in a separate read-only
     * SELECT after {@code transactionResult} returns. {@code .returning(*)} would multiply the
     * transaction's locking footprint per input row and still need the follow-up SELECT for any
     * field that joins or computes.
     *
     * @see MutationField.MutationBulkDmlRecordField
     */
    private static MethodSpec buildMutationBulkDmlRecordFetcher(
            TypeFetcherEmissionContext ctx, MutationField.MutationBulkDmlRecordField f, String outputPackage) {
        // One bulk skeleton, per-write-arm per-row body; the Upsert arm is rejected at the
        // leaf's compact constructor under the cardinality-safety regime.
        return switch (f.write()) {
            case OperationMember.Write.Insert _ -> {
                var input = recordCarrierInput(f.write());
                yield buildBulkRecordTwoStepFetcher(
                    ctx, f, input.name(), input.inputTable(), f.errorChannel(), f.qualifiedName(),
                    (tablesOnly, tableLocal, pkCols, recordRowType) ->
                        buildBulkRecordPerRowBody(f.write(), input.inputTable(), tablesOnly, tableLocal, pkCols, recordRowType),
                    outputPackage);
            }
            case OperationMember.Write.Update w -> {
                var setGroups = setGroupsOf(w.updateRows().setColumns());
                var keyGroups = keyGroupsOf(w.updateRows().keyColumns());
                yield buildBulkRecordTwoStepFetcher(
                    ctx, f, w.inputArg().name(), w.inputArg().table(), f.errorChannel(), f.qualifiedName(),
                    (tablesOnly, tableLocal, pkCols, recordRowType) -> buildCarrierBulkPerRowUpdateBody(
                        setGroups, keyGroups, w.inputArg().table(), tablesOnly, tableLocal, pkCols, recordRowType),
                    outputPackage);
            }
            case OperationMember.Write.Delete w -> {
                var whereGroups = keyGroupsOf(w.deleteRows().whereColumns());
                yield buildBulkRecordTwoStepFetcher(
                    ctx, f, w.inputArg().name(), w.inputArg().table(), f.errorChannel(), f.qualifiedName(),
                    (tablesOnly, tableLocal, pkCols, recordRowType) -> buildBulkRecordPerRowDeleteBody(
                        whereGroups, w.inputArg().table(), tablesOnly, tableLocal, pkCols, recordRowType),
                    outputPackage);
            }
            case OperationMember.Write.Upsert _ -> throw new IllegalStateException(
                "bulk record-backed DML carrier holds an Upsert write arm its constructor rejects");
        };
    }

    /** The seam for {@link #buildBulkRecordTwoStepFetcher}'s per-row DML body.*/
    @FunctionalInterface
    private interface BulkPerRowBodyFn {
        CodeBlock build(GeneratorUtils.ResolvedTableNames tablesOnly, String tableLocal,
                        List<no.sikt.graphitron.rewrite.model.ColumnRef> pkCols, TypeName recordRowType);
    }

    /**
 * The bulk two-step DML emit skeleton shared by {@link #buildMutationBulkDmlRecordFetcher}
     * (record-carrier INSERT/DELETE) and {@link #buildMutationBulkUpdatePayloadFetcher}
     * (payload-returning bulk UPDATE). The shape is invariant: an empty-list short-circuit, then a
     * per-row N+1 accumulator collecting PK echoes into a {@code Result<RecordN<PK>>} in input order
     * inside one {@code dsl.transactionResult(...)}, wrapped by {@link #returnSyncSuccess} /
     * {@link #catchArm}. Only the per-row body varies; {@code perRowBodyFn} is the seam, parameterised
     * on the resolved table names / PK columns rather than threading a {@code DmlKind} that
     * re-switches internally. The order-preservation invariant ({@code output.data[i]} corresponds
     * to {@code input[i]}) is a property of this skeleton's input-order loop and is audited at the
     * execution tier.
     */
    private static MethodSpec buildBulkRecordTwoStepFetcher(
            TypeFetcherEmissionContext ctx, no.sikt.graphitron.rewrite.model.OutputField field, String argName,
            TableRef tableRef, Optional<ErrorChannel.RouterDispatched> errorChannel, String qualifiedName,
            BulkPerRowBodyFn perRowBodyFn, String outputPackage) {
        var tablesOnly = GeneratorUtils.ResolvedTableNames.ofTable(tableRef);
        String tableLocal = tablesOnly.tableLocalName();
        var pkCols = tableRef.primaryKeyColumns();
        if (pkCols.isEmpty()) {
            throw new IllegalStateException(
                "Payload-returning bulk DML fetcher '" + qualifiedName + "' references table '"
                + tableRef.tableName() + "' that has no primary key; admission requires PK columns");
        }
        TypeName recordRowType = no.sikt.graphitron.rewrite.model.SourceKey.keyElementType(
            new no.sikt.graphitron.rewrite.model.SourceKey.Wrap.Record(), pkCols);
        var resultClass = ClassName.get("org.jooq", "Result");
        TypeName resultType = ParameterizedTypeName.get(resultClass, recordRowType);
        var dslContextClass = ClassName.get("org.jooq", "DSLContext");

        var builder = MethodSpec.methodBuilder(field.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(resultType))
            .addParameter(ENV, "env");
        builder.beginControlFlow("try");
        var tenantDsl = TenantDslEmitter.resolve(ctx, field, outputPackage);
        builder.addCode(tenantDsl.declaration());
        builder.addStatement("$T<$T<?, ?>> in = env.getArgument($S)",
            LIST, MAP, argName);
        builder.addStatement("$T $L = $T.$L",
            tablesOnly.jooqTableClass(), tableLocal, tablesOnly.tablesClass(), tableRef.javaFieldName());

        // Empty-list short-circuit: no DML, return empty Result. Mirrors the empty-input
        // short-circuit on the direct-@table bulk arms (no transaction opened, no rows touched).
        builder.beginControlFlow("if (in.isEmpty())")
            .addStatement("return $T.<$T>newResult().data(dsl.newResult($L)).build()",
                DATA_FETCHER_RESULT, resultType, buildPkFieldList(pkCols, tablesOnly, tableRef))
            .endControlFlow();

        // transactionResult: per-row DML inside one transaction. The lambda binds a transactional
        // DSLContext (txd), allocates a typed Result over the PK columns, iterates input rows
        // in declaration order, runs one DML per row with PK RETURNING, and appends the returned
        // RecordN to the Result. On any per-row throw (constraint violation, type mismatch, RLS
        // denial, ...), the transaction rolls back; the outer catch arm routes the exception
        // through ErrorRouter into the carrier's error channel (currently no-op for
        // DML carriers).
        builder.addCode(CodeBlock.builder()
            .add("$T payload = dsl.transactionResult(tx -> {\n", resultType).indent()
            .add("$T txd = $T.using(tx);\n", dslContextClass, DSL)
            .add("$T acc = txd.newResult($L);\n", resultType, buildPkFieldList(pkCols, tablesOnly, tableRef))
            .add("for ($T<?, ?> row : in) {\n", MAP).indent()
            .add(perRowBodyFn.build(tablesOnly, tableLocal, pkCols, recordRowType))
            .unindent().add("}\n")
            .add("return acc;\n")
            .unindent().add("});\n")
            .build());

        builder.addCode(returnSyncSuccess(resultType, "payload", tenantDsl.localContextTail()));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(catchArm(outputPackage, errorChannel,
            no.sikt.graphitron.render.RecordSentinel.bulk(tableRef, pkCols)));
        builder.endControlFlow();
        return builder.build();
    }

    /**
 * The carrier-driven per-row UPDATE body for the bulk payload-returning UPDATE: a dynamic
     * SET map from the carrier's {@code setColumns()} ({@link #setGroupsOf}) and the WHERE from
     * the carrier's {@code keyColumns()} ({@link #keyGroupsOf}); no {@code @value}-derived
     * {@code tia} read. The no-match throw preserves the order-preservation invariant.
     */
    private static CodeBlock buildCarrierBulkPerRowUpdateBody(
            List<SetGroup> setGroups, List<InputColumnBindingGroup> keyGroups,
            TableRef tableRef, GeneratorUtils.ResolvedTableNames tablesOnly, String tableLocal,
            List<no.sikt.graphitron.rewrite.model.ColumnRef> pkCols, TypeName recordRowType) {
        var fieldClass = ClassName.get("org.jooq", "Field");
        var linkedHashMap = ClassName.get("java.util", "LinkedHashMap");
        var body = CodeBlock.builder();
        body.addStatement("$T<$T<?>, Object> sets = new $T<>()", MAP, fieldClass, linkedHashMap);
        emitSetMapPuts(body, setGroups, "sets", "row", "row", "setKey", tablesOnly, tableRef);
        body.beginControlFlow("if (sets.isEmpty())")
            .addStatement("throw new $T($S)", IllegalArgumentException.class,
                "@mutation(typeName: UPDATE) call has no settable fields present; "
                    + "only key fields were provided")
            .endControlFlow();
        var whereChunk = buildLookupWhereSingleRow(keyGroups, tablesOnly, tableRef, "row");
        body.add(whereChunk.decodeLocals());
        body.add("$T rec = txd.update($L)\n", recordRowType, tableLocal)
            .add("    .set(sets)\n")
            .add("    .where(").add(whereChunk.whereExpr()).add(")\n")
            .add("    .returningResult(").add(buildPkFieldList(pkCols, tablesOnly, tableRef)).add(")\n")
            .add("    .fetchOne();\n");
        // UPDATE no-match preserves the order-preservation invariant by failing fast rather than
        // skewing acc.size() against in.size() with a silent skip; the catch arm routes the
        // exception through the carrier's error channel.
        body.beginControlFlow("if (rec == null)")
            .addStatement("throw new $T($S + row)", IllegalStateException.class,
                "@mutation(typeName: UPDATE) bulk row matched zero rows; key filter "
                    + "found no target for input row: ")
            .endControlFlow();
        body.add("acc.add(rec);\n");
        return body.build();
    }

    /**
     * Builds the per-row DML body for {@link #buildMutationBulkDmlRecordFetcher}, the code that
     * runs once per input row inside the transactionResult loop, dispatching on the carried
     * write arm. The live arm is Insert: per-row
     * {@code insertInto(table, cols).values(perCell).returningResult(PK).fetchOne()}. The other
     * arms are rejected at the leaf's compact constructor and never reach this dispatch; they
     * throw to guard against a future widening accident.
     */
    private static CodeBlock buildBulkRecordPerRowBody(
            OperationMember.Write.Dml write,
            TableRef tableRef, GeneratorUtils.ResolvedTableNames tablesOnly,
            String tableLocal,
            List<no.sikt.graphitron.rewrite.model.ColumnRef> pkCols,
            TypeName recordRowType) {
        return switch (write) {
            case OperationMember.Write.Insert i -> buildBulkRecordPerRowInsertBody(
                i.input(), tableRef, tablesOnly, tableLocal, pkCols, recordRowType);
            // Unreachable: MutationBulkDmlRecordField's compact constructor rejects these arms
            // (UPSERT under the cardinality-safety regime; UPDATE / DELETE ride their
            // walker-carrier leaves). Drift guards, not dispatch.
            case OperationMember.Write.Upsert _, OperationMember.Write.Update _,
                 OperationMember.Write.Delete _ ->
                throw new IllegalStateException(
                    "bulk record-backed DML carrier holds a write arm its constructor rejects: "
                    + write.getClass().getSimpleName());
        };
    }

    /**
     * Per-row DELETE body for the payload-returning Delete write arm on the bulk carrier
     * (driven from {@link #buildMutationBulkDeletePayloadFetcher}). Each input row builds a
     * {@code deleteFrom(table).where(<lookup>).returningResult(PK).fetchOne()} statement; the
     * returned PK-only {@code RecordN} is appended to the bulk accumulator in input order. A row that
     * matches no target raises {@link IllegalStateException} with the same shape as the UPDATE
     * no-match path — input-order preservation is a contract of the bulk-DML emit, and silent
     * skipping would break it. The per-row WHERE columns are sourced from the
     * {@link no.sikt.graphitron.rewrite.model.DeleteRows}
     * carrier ({@link #keyGroupsOf}) rather than {@code tia.fieldBindings()}.
     */
    private static CodeBlock buildBulkRecordPerRowDeleteBody(
            List<InputColumnBindingGroup> whereGroups,
            TableRef tableRef, GeneratorUtils.ResolvedTableNames tablesOnly,
            String tableLocal,
            List<no.sikt.graphitron.rewrite.model.ColumnRef> pkCols,
            TypeName recordRowType) {
        var body = CodeBlock.builder();
        var whereChunk = buildLookupWhereSingleRow(whereGroups, tablesOnly, tableRef, "row");
        body.add(whereChunk.decodeLocals());
        body.add("$T rec = txd.deleteFrom($L)\n", recordRowType, tableLocal)
            .add("    .where(").add(whereChunk.whereExpr()).add(")\n")
            .add("    .returningResult(").add(buildPkFieldList(pkCols, tablesOnly, tableRef)).add(")\n")
            .add("    .fetchOne();\n");
        body.beginControlFlow("if (rec == null)")
            .addStatement("throw new $T($S + row)", IllegalStateException.class,
                "@mutation(typeName: DELETE) bulk row matched zero rows; @lookupKey filter "
                    + "found no target for input row: ")
            .endControlFlow();
        body.add("acc.add(rec);\n");
        return body.build();
    }

    private static CodeBlock buildBulkRecordPerRowInsertBody(
            no.sikt.graphitron.rewrite.ArgumentRef.InputTypeArg.TableInputArg tia,
            TableRef tableRef, GeneratorUtils.ResolvedTableNames tablesOnly,
            String tableLocal,
            List<no.sikt.graphitron.rewrite.model.ColumnRef> pkCols,
            TypeName recordRowType) {
        var fields = tia.fields();
        var colList = buildInsertColumnList(fields, tablesOnly, tableRef);
        var body = CodeBlock.builder();
        body.add(buildInsertDecodeLocals(fields, "row", "insertKey", tablesOnly, tableRef));
        body.add("$T rec = txd.insertInto($L, ", recordRowType, tableLocal).add(colList).add(")\n")
            .add("    .values(\n").indent().indent()
            .add(buildPerCellValueList(fields, tablesOnly, tableRef, "row", "insertKey")).unindent().unindent()
            .add(")\n")
            .add("    .returningResult(").add(buildPkFieldList(pkCols, tablesOnly, tableRef)).add(")\n")
            .add("    .fetchOne();\n")
            .add("acc.add(rec);\n");
        return body.build();
    }

    /** Builds the comma-separated list of PK column references for a {@code returningResult(...)} call. */
    private static CodeBlock buildPkFieldList(
            List<no.sikt.graphitron.rewrite.model.ColumnRef> pkCols,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef) {
        var b = CodeBlock.builder();
        for (int i = 0; i < pkCols.size(); i++) {
            if (i > 0) b.add(", ");
            b.add("$T.$L.$L",
                tablesOnly.tablesClass(), tableRef.javaFieldName(), pkCols.get(i).javaName());
        }
        return b.build();
    }

    private static MethodSpec stub(GraphitronField field) {
        var reason = Objects.requireNonNull(
            STUBBED_VARIANTS.get(field.getClass()),
            () -> "No stub reason registered for " + field.getClass().getSimpleName()
                  + " — either implement a real generator branch or add an entry to STUBBED_VARIANTS").message();
        // Stubs are unreachable in practice: the validator rejects unimplemented variants at
        // build time. The throw is here only to make the gap loud if a stub ever does fire,
        // which would mean a validator gap. Routing through ErrorRouter.redact would mask that
        // bug as a UUID-keyed redaction; the privacy contract is for thrown exceptions inside
        // real fetcher bodies, not for "we forgot to wire the variant".
        return MethodSpec.methodBuilder(field.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(ENV, "env")
            .addStatement("throw new $T($S)", UnsupportedOperationException.class, reason)
            .build();
    }

    // -----------------------------------------------------------------------
    // ServiceTableField — DataLoader-based async fetcher + batch rows method
    // -----------------------------------------------------------------------

    /**
     * Generates a DataLoader-based async data fetcher for a {@link ChildField.ServiceTableField}.
     *
     * <p>The data fetcher's return type is {@code CompletableFuture<V>} regardless of
     * container kind: {@code loader.load(key, env)} returns {@code CompletableFuture<V>}
     * whether the underlying batch loader is positional ({@code List<V>}) or mapped
     * ({@code Map<K, V>}); the DataLoader unwraps both shapes internally and fulfills each
     * per-key promise.
     *
     * <p>List/connection: returns {@code CompletableFuture<List<V>>}. Single: returns
     * {@code CompletableFuture<V>}. {@code V} is the {@code perKeyType} the caller threads
     * through: {@code tb.table().recordClass()} for {@code ServiceTableField} and
     * {@code srf.elementType()} for {@code ServiceRecordField}.
     *
     * <p>{@code keySource} is the leaf's stored key source, and it replaces the parent table this
     * builder used to take: the batch key is the key owner's primary key rather than the parent's, so
     * a class-backed parent hosting a batched child has a table to read column constants through
     * where it had none. The three arms differ only in the source expression the read binds to; see
     * {@link GeneratorUtils#buildServiceKeyExtraction}.
     *
     * <p>Container axis ({@link LoaderRegistration#container()}):
     * <ul>
     *   <li>{@link LoaderRegistration.Container#POSITIONAL_LIST} → {@code newDataLoader(...)}
     *       binds to {@code BatchLoaderWithContext<K, V>}; lambda keys parameter is
     *       {@code List<KeyType>}.</li>
     *   <li>{@link LoaderRegistration.Container#MAPPED_SET} → {@code newMappedDataLoader(...)}
     *       binds to {@code MappedBatchLoaderWithContext<K, V>}; lambda keys parameter is
     *       {@code Set<KeyType>}.</li>
     * </ul>
     */
    private static MethodSpec buildServiceDataFetcher(
            TypeFetcherEmissionContext ctx,
            String fieldName,
            BatchKeyField bkf,
            ReturnTypeRef returnType,
            ServiceKeySource keySource,
            TypeName perKeyType,
            String outputPackage,
            Optional<ErrorChannel.RouterDispatched> errorChannel,
            String rowsMethodName) {

        boolean isList = returnType.wrapper().isList();
        TypeName valueType = isList ? ParameterizedTypeName.get(LIST, perKeyType) : perKeyType;

        SourceKey sourceKey = bkf.sourceKey();
        TypeName keyType = sourceKey.keyElementType();
        LoaderRegistration registration = bkf.loaderRegistration();

        return DataLoaderFetcherEmitter.build(
            fieldName,
            keyType, valueType, asyncResultType(valueType),
            registration,
            RowsMethodCall.batchLoaderLambda(rowsMethodName, keyType, registration),
            CodeBlock.of(""),
            GeneratorUtils.buildServiceKeyExtraction(sourceKey, keySource),
            asyncWrapTail(valueType, outputPackage, errorChannel),
            dataLoaderSyncCatchBody(valueType, outputPackage, errorChannel),
            TenantDslEmitter.loaderNameDeclaration(ctx, fieldName, "name", outputPackage));
    }

    // -----------------------------------------------------------------------
    // The batched leaf (BatchedTableField, lookup-keyed or not): one DataLoader-registering
    // fetcher builder for both source shapes; flat correlated-batch rows methods in
    // SplitRowsMethodEmitter.
    // -----------------------------------------------------------------------

    /**
     * The one batched-field DataFetcher builder: both source shapes of the merged
     * batched leaves share the framing — loader
     * registration, batch lambda, dispatch, async wrap/catch tails — and the stored source-shape
     * fact gates exactly the two facts it owns:
     *
     * <ul>
     *   <li><b>Key lift.</b> A {@link SourceShape#Table} parent holds its own projected table
     *       row; the key read is the wrap-driven column projection
     *       ({@link GeneratorUtils#buildKeyExtraction}), with the NULL-FK short-circuit on single
     *       cardinality (a {@code NULL} FK can never match under ANSI semantics — skip the
     *       loader round-trip). A {@link SourceShape#Record} parent holds a producer-handed
     *       backing object; the key read consumes the stored {@link KeyLift}
     *       ({@link GeneratorUtils#buildRecordParentKeyExtraction}).</li>
     *   <li><b>Prelude.</b> Only the Record arm participates in the LocalContext / Outcome
     *       transports: under a flipped Outcome payload the fetcher narrows
     *       {@code env.getSource()} to {@code Outcome.Success} before touching the loader
     *       registry, and otherwise short-circuits a null source (the LocalContext errors
     *       transport fires the data-channel fetcher with {@code data(null)}, so the merged
     *       arm must keep the null-source guard). A table
     *       parent's source is never null mid-query and never Outcome-wrapped: empty prelude.</li>
     * </ul>
     *
     * <p>The loader's per-key value is the row's per-key view
     * ({@link no.sikt.graphitron.render.BatchedRowsFragments#perKeyValueTypeOf}, whose
     * {@code List} lift is the launcher's own batch container, so the two ends cannot
     * disagree). The fetcher's overall result follows the field's GraphQL cardinality
     * regardless of dispatch, a different axis from the row's per-key shape and deliberately
     * entry-local; its fanned and connection legs read the same row facts as the per-key view.
     */
    private static <T extends ChildField & BatchKeyField> MethodSpec
            buildBatchedDataFetcher(TypeFetcherEmissionContext ctx, T field,
                    ReturnTypeRef.TableBoundReturnType returnType,
                    SourceKey sourceKey, KeyLift lift,
                    TableRef parentTable,
                    GraphitronType.ResultType resultType, boolean sourceIsOutcome,
                    String outputPackage, no.sikt.graphitron.command.LauncherCommand row) {

        boolean isList = returnType.wrapper().isList();
        // A fanned batched field's loader values are the merged marker-bearing element lists the
        // fanned rows method produces; the wrap tail collapses them per field invocation, where
        // each parent's own env yields the right per-element error paths. The fork is the row's
        // tenancy arm, the same fact the launcher renders under.
        boolean fanned = row.tenancy() instanceof no.sikt.graphitron.command.TenantStrategy.Fanned;
        String rowsMethodName = row.unit().methodName();

        TypeName valueType = no.sikt.graphitron.render.BatchedRowsFragments.perKeyValueTypeOf(row);
        TypeName resultValueType = fanned
            ? ParameterizedTypeName.get(LIST, ClassName.get(Object.class))
            : row.result() instanceof no.sikt.graphitron.command.ResultShape.Connection conn
                ? ClassName.get(conn.carrier().packageName(), conn.carrier().simpleName())
                : isList ? ParameterizedTypeName.get(LIST, RECORD) : RECORD;

        TypeName keyType = sourceKey.keyElementType();
        LoaderRegistration registration = field.loaderRegistration();

        CodeBlock prelude;
        CodeBlock keyExtraction;
        if (field.sourceShape() == no.sikt.graphitron.rewrite.model.SourceShape.Table) {
            prelude = CodeBlock.of("");
            keyExtraction = isList
                ? GeneratorUtils.buildKeyExtraction(sourceKey, parentTable)
                : GeneratorUtils.buildKeyExtractionWithNullCheck(sourceKey, parentTable);
        } else if (sourceIsOutcome) {
            // Outcome arm-switch: narrow Success ahead of the loader registration (returning
            // completedFuture(null) on the ErrorList arm) and read the key off success.value() —
            // the same backing object the non-wrapped source would have been (the
            // Success.value() invariant). Only the source binding moves; the key extraction is
            // the field's own.
            var successClass = ClassName.get(outputPackage + ".schema", "Outcome").nestedClass("Success");
            var completableFuture = ClassName.get("java.util.concurrent", "CompletableFuture");
            prelude = CodeBlock.builder()
                .beginControlFlow("if (!(env.getSource() instanceof $T<?> success))", successClass)
                .addStatement("return $T.completedFuture(null)", completableFuture)
                .endControlFlow()
                .build();
            keyExtraction = GeneratorUtils.buildRecordParentKeyExtraction(
                sourceKey, lift, returnType.table(), resultType, CodeBlock.of("success.value()"));
        } else {
            var completableFuture = ClassName.get("java.util.concurrent", "CompletableFuture");
            prelude = CodeBlock.builder()
                .beginControlFlow("if (env.getSource() == null)")
                .addStatement("return $T.completedFuture(null)", completableFuture)
                .endControlFlow()
                .build();
            keyExtraction = GeneratorUtils.buildRecordParentKeyExtraction(
                sourceKey, lift, returnType.table(), resultType);
        }

        return DataLoaderFetcherEmitter.build(
            field.name(),
            keyType, valueType, asyncResultType(resultValueType),
            registration,
            RowsMethodCall.batchLoaderLambda(rowsMethodName, keyType, registration),
            prelude,
            keyExtraction,
            fanned
                ? fannedAsyncWrapTail(outputPackage)
                : asyncWrapTail(resultValueType, outputPackage, Optional.empty()),
            dataLoaderSyncCatchBody(resultValueType, outputPackage, Optional.empty()),
            TenantDslEmitter.loaderNameDeclaration(ctx, field.name(), "name", outputPackage));
    }

    /**
     * The fanned batched fetcher's wrap tail: each parent's loaded value is a marker-bearing
     * element list from the fanned rows method, collapsed here per field invocation so the
     * per-element error paths are built against each parent's own {@code env}. The
     * {@code exceptionally} arm keeps the shared no-channel disposition.
     */
    private static CodeBlock fannedAsyncWrapTail(String outputPackage) {
        return CodeBlock.builder()
            .add(".thenApply(payload -> $T.collapseFanOut(env, payload))\n",
                TenantDslEmitter.tenantConnectionsClass(outputPackage))
            .add(".exceptionally(t -> ").add(asyncRouterCall(outputPackage, Optional.empty(), "t")).add(")")
            .build();
    }

    /**
     * The {@code @pivot} sibling of {@link #buildBatchedDataFetcher}, specialised to the pivot's
     * invariants: the parent is always table-backed (empty prelude), the loader value is always
     * one {@code Record} per key, and — unlike the single-cardinality table arm — the key read
     * deliberately skips the NULL-key short-circuit. One projection record exists per parent,
     * always: a parent whose key column is NULL simply matches no attribute rows, and the rows
     * method's key-preserving left join scatters it a record of null slots, matching inline
     * delivery instead of resolving the field to null.
     */
    private static MethodSpec buildPivotBatchedDataFetcher(TypeFetcherEmissionContext ctx,
            ChildField.BatchedPivotField field, TableRef parentTable, String outputPackage,
            String rowsMethodName) {
        TypeName keyType = field.sourceKey().keyElementType();
        LoaderRegistration registration = field.loaderRegistration();
        return DataLoaderFetcherEmitter.build(
            field.name(),
            keyType, RECORD, asyncResultType(RECORD),
            registration,
            RowsMethodCall.batchLoaderLambda(rowsMethodName, keyType, registration),
            CodeBlock.of(""),
            GeneratorUtils.buildKeyExtraction(field.sourceKey(), parentTable),
            asyncWrapTail(RECORD, outputPackage, Optional.empty()),
            dataLoaderSyncCatchBody(RECORD, outputPackage, Optional.empty()),
            TenantDslEmitter.loaderNameDeclaration(ctx, field.name(), "name", outputPackage));
    }

    // -----------------------------------------------------------------------
    // GraphitronContext helper
    // -----------------------------------------------------------------------

    private static MethodSpec buildGraphitronContextHelper(String outputPackage) {
        var ctxType = graphitronContext(outputPackage);
        return MethodSpec.methodBuilder("graphitronContext")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(ctxType)
            .addParameter(ENV, "env")
            .addStatement("return env.getGraphQlContext().get($T.class)", ctxType)
            .build();
    }

    private static String capitalize(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // -----------------------------------------------------------------------
    // Fetcher try/catch wrap helpers.
    //
    // Every emitted fetcher returns DataFetcherResult<P> (sync) or
    // CompletableFuture<DataFetcherResult<P>> (async). The success arm wraps the
    // produced payload; the catch arm forks on the field's ErrorChannel:
    //   - present  -> ErrorRouter.dispatch(e, ErrorMappings.<CONST>, env, factory)
    //   - empty    -> ErrorRouter.surfaceClientErrorOrRedact(e, env)
    // The no-channel disposition is uniform across sync catch arms and async
    // .exceptionally arms; its one definition lives on
    // ErrorRouterClassGenerator.noChannelRouterCall.
    // -----------------------------------------------------------------------

    /** See {@link no.sikt.graphitron.render.FetcherResult#boxed}. */
    private static TypeName boxed(TypeName valueType) {
        return no.sikt.graphitron.render.FetcherResult.boxed(valueType);
    }

    /** See {@link no.sikt.graphitron.render.FetcherResult#syncResultType}. */
    private static TypeName syncResultType(TypeName valueType) {
        return no.sikt.graphitron.render.FetcherResult.syncResultType(valueType);
    }

    /** {@code CompletableFuture<DataFetcherResult<P>>}; primitives box. */
    private static TypeName asyncResultType(TypeName valueType) {
        return ParameterizedTypeName.get(COMPLETABLE_FUTURE, syncResultType(valueType));
    }

    private static ClassName errorRouterClass(String outputPackage) {
        return ClassName.get(
            outputPackage + ".schema",
            no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator.CLASS_NAME);
    }

    private static ClassName errorMappingsClass(String outputPackage) {
        return ClassName.get(
            outputPackage + ".schema",
            ErrorMappingsClassGenerator.CLASS_NAME);
    }

    /**
     * Builds the catch arm for a synchronous fetcher. Forks on {@code errorChannel}: a present
     * channel emits {@code return ErrorRouter.dispatch(e, ErrorMappings.<CONST>, env, factory)}
     * with the channel's mapping table and the synthesized payload factory; an absent channel
     * emits {@code return ErrorRouter.redact(e, env)} (no-channel privacy disposition).
     *
     * <p>Used by every sync fetcher builder backing a {@link no.sikt.graphitron.rewrite.model.WithErrorChannel}
     * field after emitting the success-path
     * {@code return DataFetcherResult.<P>newResult().data(payload).build()}.
     */
    private static CodeBlock catchArm(String outputPackage,
                                      Optional<ErrorChannel.RouterDispatched> errorChannel) {
        return catchArm(outputPackage, errorChannel, null);
    }

    /**
     * Overload accepting the LocalContext sentinel source. Non-null sentinel is required for the
     * {@link ErrorChannel.LocalContext} arm because graphql-java's
     * {@code completeValueForObject} short-circuits children on a null parent value. Pass
     * {@code null} for the sentinel when the call site cannot reach a {@code LocalContext}
     * channel (every site reachable by today's classifier except
     * {@code buildMutationDmlRecordFetcher} and {@code buildMutationBulkDmlRecordFetcher} is in
     * this category).
     */
    private static CodeBlock catchArm(String outputPackage,
                                      Optional<ErrorChannel.RouterDispatched> errorChannel,
                                      CodeBlock localContextSentinel) {
        if (errorChannel.isEmpty()) {
            return noChannelCatchArm(outputPackage);
        }
        return switch (errorChannel.get()) {
            case ErrorChannel.PayloadClass pc -> dispatchCatchArm(outputPackage, pc);
            case ErrorChannel.LocalContext lc -> {
                if (localContextSentinel == null) {
                    throw new IllegalStateException(
                        "catchArm reached ErrorChannel.LocalContext without a sentinel source; "
                        + "every emitter that may produce a LocalContext-bound channel must call "
                        + "the 3-arg overload of catchArm");
                }
                yield dispatchToLocalContextCatchArm(outputPackage, lc, localContextSentinel);
            }
        };
    }

    /**
     * Builds the LocalContext-bound catch arm: routes the throw through
     * {@code ErrorRouter.dispatchToLocalContext} with this channel's mapping-table constant.
     * No payload-factory lambda is needed: the matched throwable is placed into
     * {@code DataFetcherResult.localContext}; the carrier's errors-field DataFetcher reads
     * it via {@code env.getLocalContext()}, and the data field's null-source guard
     * short-circuits the data side of the response.
     */
    private static CodeBlock dispatchToLocalContextCatchArm(String outputPackage,
            ErrorChannel.LocalContext channel, CodeBlock sentinel) {
        return no.sikt.graphitron.render.ErrorDispatchFragments.localContextArm(
            errorRouterClass(outputPackage), errorMappingsClass(outputPackage),
            channel.mappingsConstantName(), sentinel);
    }

    /**
     * Builds the standard catch arm for a synchronous fetcher without a typed-error channel: route
     * the throw through {@code ErrorRouter.surfaceClientErrorOrRedact} (emitted at
     * {@code <outputPackage>.schema.ErrorRouter}). A {@code GraphitronClientException} (e.g. a
     * malformed/wrong-type {@code @nodeId} filter id) surfaces its real message; every other
     * throwable redacts to a correlation id.
     */
    private static CodeBlock noChannelCatchArm(String outputPackage) {
        return CodeBlock.of("return $L;\n",
            no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator
                .noChannelRouterCall(outputPackage, "e"));
    }

    /**
     * Builds the channel-aware catch arm: routes the throw through {@code ErrorRouter.dispatch}
     * with this channel's mapping-table constant and a synthesized payload factory lambda
     * that binds the errors slot per the channel's {@link ErrorChannel#errorsSlot()} arm.
     */
    private static CodeBlock dispatchCatchArm(String outputPackage, ErrorChannel.PayloadClass channel) {
        return CodeBlock.builder()
            .add("return $T.dispatch(\n", errorRouterClass(outputPackage))
            .add("    e,\n")
            .add("    $T.$L,\n", errorMappingsClass(outputPackage), channel.mappingsConstantName())
            .add("    env,\n")
            .add("    ").add(payloadFactoryLambda(channel)).add(");\n")
            .build();
    }

    /**
     * Synthesizes the {@code (errors) -> new <PayloadClass>(...)} factory lambda. Dispatches on
     * the channel's {@link ErrorsSlot} arm: the all-fields-ctor arm walks the constructor's
     * parameter indices {@code 0..N-1} (where {@code N == 1 + defaultedSlots.size()}), printing
     * the lambda parameter at the errors-ctor-index and the pre-resolved
     * {@link no.sikt.graphitron.rewrite.model.DefaultedSlot#defaultLiteral()} otherwise. The
     * phase-2 setter arm lands as a new {@code case} that emits a lambda body of
     * {@code errors -> { var p = new Payload(); p.setX(...); p.setErrors(errors); ...; return p; }}.
     */
    private static CodeBlock payloadFactoryLambda(ErrorChannel.PayloadClass channel) {
        return switch (channel.errorsSlot()) {
            case no.sikt.graphitron.rewrite.model.ErrorsSlot.CtorParameterIndex cpi ->
                payloadFactoryLambdaCtor(channel, cpi.index());
            case no.sikt.graphitron.rewrite.model.ErrorsSlot.SetterMethod sm ->
                payloadFactoryLambdaSetters(channel, sm);
        };
    }

    /**
     * Mutable-bean variant of {@link #payloadFactoryLambda}: emits a multi-statement
     * {@code errors -> { var p = new Payload(); p.setA(...); ...; p.setErrors(errors); ...;
     * return p; }} lambda that invokes the bound errors setter with the runtime list and every
     * other setter with its language-default literal. Per
     * {@code development-principles.adoc} ("Readability rules") the bound setter is called first for diagnostic
     * clarity; semantic order doesn't matter (Java-bean setters are independent assignments).
     */
    private static CodeBlock payloadFactoryLambdaSetters(
            ErrorChannel.PayloadClass channel,
            no.sikt.graphitron.rewrite.model.ErrorsSlot.SetterMethod sm) {
        var b = CodeBlock.builder().add("errors -> {\n").indent();
        b.add("$T p = new $T();\n", channel.payloadClass(), channel.payloadClass());
        b.add("p.$L(errors);\n", sm.boundSetter().getName());
        for (var nbs : sm.nonBoundSetters()) {
            b.add("p.$L($L);\n", nbs.setter().getName(), nbs.defaultLiteral());
        }
        b.add("return p;\n").unindent().add("}");
        return b.build();
    }

    private static CodeBlock payloadFactoryLambdaCtor(ErrorChannel.PayloadClass channel, int errorsCtorIndex) {
        var args = CodeBlock.builder();
        int slotCount = 1 + channel.defaultedSlots().size();
        var defaultsByIndex = channel.defaultedSlots().stream()
            .collect(java.util.stream.Collectors.toMap(s -> s.index(), s -> s.defaultLiteral()));
        for (int i = 0; i < slotCount; i++) {
            if (i > 0) args.add(", ");
            if (i == errorsCtorIndex) {
                args.add("errors");
            } else {
                args.add(defaultsByIndex.get(i));
            }
        }
        return CodeBlock.of("errors -> new $T($L)", channel.payloadClass(), args.build());
    }

    /**
     * Builds the success-path return statement for a synchronous fetcher: wraps the named
     * payload local in a {@code DataFetcherResult<P>}. Caller is responsible for declaring
     * the local first.
     */
    private static CodeBlock returnSyncSuccess(TypeName valueType, String payloadLocal) {
        return returnSyncSuccess(valueType, payloadLocal, CodeBlock.of(""));
    }

    /**
     * {@link #returnSyncSuccess(TypeName, String)} with a builder tail: routed tenant sites pass
     * {@code TenantDslEmitter.Resolution#localContextTail()} so the divined tenant key rides down
     * the subtree as graphql-java {@code localContext} (empty everywhere else, keeping the
     * single-tenant form byte-identical).
     */
    private static CodeBlock returnSyncSuccess(TypeName valueType, String payloadLocal, CodeBlock builderTail) {
        return no.sikt.graphitron.render.FetcherResult.success(valueType, payloadLocal, builderTail);
    }

    /**
     * Async tail for fetchers whose body ends with a {@code CompletableFuture<P>} expression
     * (typically {@code loader.load(key, env)}). Adds {@code .thenApply(...)} to lift the
     * payload into a {@code DataFetcherResult<P>}, then {@code .exceptionally(...)} to route
     * any exception that escapes past the synchronous wrapper (DataLoader bookkeeping, etc.).
     * The {@code .exceptionally} arm forks on {@code errorChannel} the same way
     * {@link #catchArm} does for sync fetchers.
     */
    private static CodeBlock asyncWrapTail(TypeName valueType, String outputPackage,
                                           Optional<ErrorChannel.RouterDispatched> errorChannel) {
        return CodeBlock.builder()
            .add(".thenApply(payload -> $T.<$T>newResult().data(payload).build())\n",
                DATA_FETCHER_RESULT, boxed(valueType))
            .add(".exceptionally(t -> ").add(asyncRouterCall(outputPackage, errorChannel, "t")).add(")")
            .build();
    }

    /**
     * The ErrorRouter disposition for an async DataLoader-registering fetcher, as a bare expression
     * over the throwable local named {@code throwableVar}. One definition, shared by both arms that
     * can route an async fetcher's throw: the {@code .exceptionally(t -> …)}
     * tail in {@link #asyncWrapTail} (an escaped <em>async</em> throw) and the synchronous
     * {@code try}/{@code catch (Throwable e)} guard {@link DataLoaderFetcherEmitter} now wraps the
     * key extraction + dispatch in (a throw <em>before</em> loader dispatch, e.g. an
     * {@code into(...)} / accessor failure during key extraction). Threading the router call rather
     * than the whole tail keeps the disposition from drifting between the two arms. Forks on
     * {@code errorChannel} exactly as the sync {@link #catchArm} does; a no-channel fetcher redacts
     * via {@code surfaceClientErrorOrRedact}, which walks the cause chain so a DataLoader-wrapped
     * {@code CompletionException} still unwraps to the client-error marker.
     */
    private static CodeBlock asyncRouterCall(String outputPackage,
                                             Optional<ErrorChannel.RouterDispatched> errorChannel,
                                             String throwableVar) {
        if (errorChannel.isEmpty()) {
            return no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator
                .noChannelRouterCall(outputPackage, throwableVar);
        }
        return switch (errorChannel.get()) {
            case ErrorChannel.PayloadClass pc -> CodeBlock.builder()
                .add("$T.dispatch($L, $T.$L, env, ",
                    errorRouterClass(outputPackage), throwableVar,
                    errorMappingsClass(outputPackage),
                    pc.mappingsConstantName())
                .add(payloadFactoryLambda(pc))
                .add(")")
                .build();
            case ErrorChannel.LocalContext lc -> CodeBlock.of(
                "$T.dispatchToLocalContext($L, $T.$L, env)",
                errorRouterClass(outputPackage), throwableVar,
                errorMappingsClass(outputPackage),
                lc.mappingsConstantName());
        };
    }

    /**
     * The synchronous {@code catch (Throwable e)} body for a DataLoader-registering fetcher: routes
     * the pre-dispatch throw through the same {@link #asyncRouterCall} disposition the async
     * {@code .exceptionally} arm uses, then lifts the resulting {@code DataFetcherResult<P>} into a
     * completed future so it satisfies the fetcher's {@code CompletableFuture<DataFetcherResult<P>>}
     * return type. Built by the caller (which knows {@code valueType} and the field's
     * {@code errorChannel}) and threaded into {@link DataLoaderFetcherEmitter#build}, so the sync
     * catch and the async tail cannot disagree on the disposition.
     */
    private static CodeBlock dataLoaderSyncCatchBody(TypeName valueType, String outputPackage,
                                                     Optional<ErrorChannel.RouterDispatched> errorChannel) {
        return CodeBlock.builder()
            .add("return $T.<$T>completedFuture(", COMPLETABLE_FUTURE, syncResultType(valueType))
            .add(asyncRouterCall(outputPackage, errorChannel, "e"))
            .add(");\n")
            .build();
    }

}
