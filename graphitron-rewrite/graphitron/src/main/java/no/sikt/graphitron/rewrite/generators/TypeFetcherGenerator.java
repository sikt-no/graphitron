package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.generators.schema.ConstraintViolationsClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.ErrorMappingsClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ConnectionHelperClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ConnectionResultClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.OrderByResultClassGenerator;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.BatchKeyField;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.RowsMethodBody;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.InputColumnBinding;
import no.sikt.graphitron.rewrite.model.InputColumnBindingGroup;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.MethodBackedField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.*;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Generates a {@link TypeSpec} for one {@code <TypeName>Fetchers} class in {@code rewrite.fetchers}.
 *
 * <ul>
 *   <li>{@link ChildField.ColumnField} — wired via {@code new ColumnFetcher<>(Tables.X.COLUMN)},
 *       no per-field method generated. {@code ColumnFetcher} implements
 *       {@link graphql.schema.LightDataFetcher} so the runtime uses the lighter call path.</li>
 *   <li>{@link QueryField.QueryTableField} — {@code public static} method taking
 *       {@code DataFetchingEnvironment}, returning {@code Result<Record>} or {@code Record},
 *       wired by method reference.</li>
 *   <li>{@link QueryField.QueryLookupTableField} — two methods: a thin data fetcher (named after
 *       the field, e.g. {@code filmById}) that delegates to a rows method (e.g.
 *       {@code lookupFilmById}) which performs the actual SQL. The rows method is callable
 *       independently (e.g. by Apollo Federation {@code _entities} resolution).</li>
 *   <li>All other field types — stub throwing {@link UnsupportedOperationException}.</li>
 * </ul>
 *
 * <p>Emitted Table-bound helpers ({@code <fieldName>OrderBy}) take the aliased {@code Table}
 * as a parameter — see "Helper-locality" in {@code docs/rewrite-design-principles.md}.
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
     * Full entry point. The {@code assembled} parameter is the graphql-java
     * {@link graphql.schema.GraphQLSchema} the rewrite is being generated against; the
     * validator pre-step reads it via {@link TypeFetcherEmissionContext#assembledSchema()} to
     * resolve each SDL arg's input-type-ness and switch input-typed args to the typed-record
     * walk target ({@code <InputName>.fromMap(...)}).
     */
    public static List<TypeSpec> generate(GraphitronSchema schema, graphql.schema.GraphQLSchema assembled, String outputPackage) {
        var result = new ArrayList<TypeSpec>(schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof GraphitronType.TableType
                      || e.getValue() instanceof GraphitronType.NodeType
                      || e.getValue() instanceof GraphitronType.RootType
                      || e.getValue() instanceof GraphitronType.ResultType)
            .map(Map.Entry::getKey)
            .sorted()
            .map(typeName -> generateForType(schema, typeName, assembled, outputPackage))
            .toList());

        // Walk NestingField descendants of TableBackedType roots; emit a narrow Fetchers class
        // for each nested plain-object type that contains at least one BatchKeyField leaf.
        // These types are absent from schema.types(), so they cannot reach the stream above.
        var seenNestedTypes = new java.util.LinkedHashSet<String>();
        schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof GraphitronType.TableBackedType)
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> schema.fieldsOf(e.getKey()).forEach(f -> {
                if (f instanceof ChildField.NestingField nf) {
                    collectNestedFetcherClasses(nf, seenNestedTypes, result, assembled, outputPackage);
                }
            }));
        return result;
    }

    private static void collectNestedFetcherClasses(ChildField.NestingField nf,
            Set<String> seen, List<TypeSpec> out, graphql.schema.GraphQLSchema assembled, String outputPackage) {
        var nestedTypeName = nf.returnType().returnTypeName();
        if (seen.add(nestedTypeName)) {
            var batchKeyFields = nf.nestedFields().stream()
                .filter(f -> f instanceof BatchKeyField)
                .map(f -> (GraphitronField) f)
                .sorted(Comparator.comparing(GraphitronField::name))
                .toList();
            if (!batchKeyFields.isEmpty()) {
                out.add(generateTypeSpec(nestedTypeName, nf.returnType().table(), null, batchKeyFields, assembled, outputPackage));
            }
        }
        for (var nested : nf.nestedFields()) {
            if (nested instanceof ChildField.NestingField innerNf) {
                collectNestedFetcherClasses(innerNf, seen, out, assembled, outputPackage);
            }
        }
    }

    private static TypeSpec generateForType(GraphitronSchema schema, String typeName, graphql.schema.GraphQLSchema assembled, String outputPackage) {
        var type = schema.type(typeName);
        var fields = schema.fieldsOf(typeName).stream()
            .filter(f -> !(f instanceof GraphitronField.UnclassifiedField))
            .sorted(Comparator.comparing(GraphitronField::name))
            .toList();
        TableRef parentTable = type instanceof GraphitronType.TableBackedType tbt ? tbt.table() : null;
        GraphitronType.ResultType resultType = type instanceof GraphitronType.ResultType rt ? rt : null;
        return generateTypeSpec(typeName, parentTable, resultType, fields, assembled, outputPackage);
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
     * Together with {@link #STUBBED_VARIANTS}{@code .keySet()} and {@link #NOT_DISPATCHED_LEAVES}
     * this forms an exhaustive, disjoint partition of every sealed leaf of {@link GraphitronField};
     * enforced by {@code GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus}.
     * Moving an entry from {@link #STUBBED_VARIANTS} to this set is the expected review signal
     * when a stub becomes a real implementation.
     */
    public static final Set<Class<? extends GraphitronField>> IMPLEMENTED_LEAVES = Set.of(
        ChildField.ColumnField.class,
        ChildField.ComputedField.class,
        QueryField.QueryNodeField.class,
        QueryField.QueryNodesField.class,
        QueryField.QueryLookupTableField.class,
        QueryField.QueryTableField.class,
        QueryField.QueryTableMethodTableField.class,
        QueryField.QueryServiceTableField.class,
        QueryField.QueryServiceRecordField.class,
        MutationField.MutationInsertTableField.class,
        MutationField.MutationUpdateTableField.class,
        MutationField.MutationDeleteTableField.class,
        MutationField.MutationUpsertTableField.class,
        MutationField.MutationDmlRecordField.class,
        MutationField.MutationBulkDmlRecordField.class,
        MutationField.MutationServiceTableField.class,
        MutationField.MutationServiceRecordField.class,
        ChildField.ServiceTableField.class,
        ChildField.ServiceRecordField.class,
        ChildField.SplitTableField.class,
        ChildField.SplitLookupTableField.class,
        ChildField.PropertyField.class,
        ChildField.RecordField.class,
        ChildField.RecordTableField.class,
        ChildField.RecordLookupTableField.class,
        ChildField.ConstructorField.class,
        ChildField.SingleRecordTableField.class,
        ChildField.SingleRecordIdFieldFromReturning.class,
        ChildField.SingleRecordTableFieldFromReturning.class,
        ChildField.TableMethodField.class,
        ChildField.RecordTableMethodField.class,
        QueryField.QueryTableInterfaceField.class,
        ChildField.TableInterfaceField.class,
        ChildField.ParticipantColumnReferenceField.class,
        QueryField.QueryInterfaceField.class,
        QueryField.QueryUnionField.class,
        ChildField.InterfaceField.class,
        ChildField.UnionField.class,
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
        InputField.ColumnField.class,
        InputField.ColumnReferenceField.class,
        InputField.CompositeColumnField.class,
        InputField.CompositeColumnReferenceField.class,
        InputField.NestingField.class);

    /**
     * Leaves whose SELECT projection is emitted inline by {@link TypeClassGenerator}'s
     * {@code $fields} method — no per-field fetcher method is generated. Together with
     * {@link #IMPLEMENTED_LEAVES}, {@link #NOT_DISPATCHED_LEAVES}, and
     * {@link #STUBBED_VARIANTS}{@code .keySet()}, this forms an exhaustive four-way
     * disjoint partition of every {@link GraphitronField} sealed leaf; enforced by
     * {@code GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus}.
     */
    public static final Set<Class<? extends GraphitronField>> PROJECTED_LEAVES = Set.of(
        ChildField.TableField.class,
        ChildField.LookupTableField.class,
        ChildField.ColumnReferenceField.class,
        ChildField.CompositeColumnField.class,
        ChildField.NestingField.class);

    /**
     * Maps each unimplemented field variant class to the {@link Rejection.Deferred} that both the
     * generated stub method ({@link #stub}) and {@code GraphitronSchemaValidator.validateVariantIsImplemented}
     * project. The deferred value carries a {@code summary}, a roadmap {@code planSlug}, and a
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
     *   <li>Together with {@link #IMPLEMENTED_LEAVES} and {@link #NOT_DISPATCHED_LEAVES} this forms
     *       a disjoint partition of every {@link GraphitronField} leaf.
     *       Enforced by {@code GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus}.</li>
     *   <li>Adding a case arm that calls {@link #stub} must also add the class here.
     *       Enforced at generator-run time via {@link Objects#requireNonNull} in {@link #stub} —
     *       fails the first time a schema triggers that variant.</li>
     *   <li>Removing the last {@code stub(f)} call for a class should remove its map entry (and
     *       typically move it to {@link #IMPLEMENTED_LEAVES} instead).
     *       The partition test catches an orphan entry as soon as any other set references it.</li>
     * </ul>
     */
    public static final Map<Class<? extends GraphitronField>, Rejection.Deferred> STUBBED_VARIANTS =
        Map.ofEntries(
            // ChildField stubs — TableTargetField sub-hierarchy
            // (ChildField.TableField and ChildField.LookupTableField are in PROJECTED_LEAVES —
            // inline emission via TypeClassGenerator.$fields)
            // ChildField stubs — remaining direct permits
            // (ChildField.ColumnReferenceField is in PROJECTED_LEAVES; per-shape deferrals carried
            // by @LoadBearingClassifierCheck on validateColumnReferenceField.)
            Map.entry(ChildField.CompositeColumnReferenceField.class,
                deferredFor(ChildField.CompositeColumnReferenceField.class,
                    "CompositeColumnReferenceField (rooted-at-parent NodeId reference) not yet implemented"
                    + " — requires JOIN-with-projection emission; rooted-at-parent fixture"
                    + " (parent_node + child_ref) is in nodeidfixture and ready to drive coverage",
                    "nodeidreferencefield-join-projection-form"))
        );

    private static Rejection.Deferred deferredFor(
            Class<? extends GraphitronField> fieldClass, String summary, String planSlug) {
        return new Rejection.Deferred(summary, planSlug, new Rejection.StubKey.VariantClass(fieldClass));
    }

    /**
     * Overload for tests and callers that don't need to specify a {@link GraphitronType.ResultType}.
     * Delegates to the 6-arg form with {@code resultType = null} and empty package strings.
     */
    static TypeSpec generateTypeSpec(String typeName, TableRef parentTable, List<GraphitronField> fields) {
        return generateTypeSpec(typeName, parentTable, null, fields, null, "");
    }

    /**
     * Backward-compat overload for unit-tier tests that built the model only (no assembled
     * schema) before R94. The validator pre-step falls back to its legacy Map-walk shape.
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
     * @param resultType  the resolved {@link GraphitronType.ResultType} for {@code @record} parents,
     *                    or {@code null} for table-backed and root types
     * @param fields      the classified fields belonging to this type
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "column-field-requires-table-backed-parent",
        reliesOn = "The case ChildField.ColumnField switch arm throws IllegalStateException on "
            + "parentTable == null rather than emitting a fallback path. The hard fail is the "
            + "form the load-bearing guarantee takes here: navigation and drift annunciation, "
            + "not guard elision.")
    static TypeSpec generateTypeSpec(String typeName, TableRef parentTable,
            GraphitronType.ResultType resultType, List<GraphitronField> fields,
            graphql.schema.GraphQLSchema assembled,
            String outputPackage) {
        var className = typeName + "Fetchers";
        var builder = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC);

        // Per-class scratchpad for deferred helper-method emission. Every emitter that writes a
        // graphitronContext(env) call obtains the CodeBlock through ctx.graphitronContextCall(),
        // which records the dependency; class assembly drains the set below to decide which
        // helper methods to materialise. Replaces a previous post-scan that string-grepped
        // method bodies for the literal "graphitronContext(env)".
        var ctx = new TypeFetcherEmissionContext(assembled, typeName);

        for (var field : fields) {
            switch (field) {
                case ChildField.ColumnField cf -> {
                    if (parentTable == null) {
                        // ColumnField requires a table-backed parent — classifier invariant.
                        // The validator rejects this before generation; treat as a bug if reached.
                        throw new IllegalStateException(
                            "ColumnField '" + cf.qualifiedName()
                            + "' classified on a non-table-backed parent — classifier invariant violated");
                    }
                    // handled in wiring via ColumnFetcher — no method emitted
                }
                case QueryField.QueryLookupTableField qlf -> {
                    var lookupTableRef = qlf.returnType().table();
                    var lookupTableClass = GeneratorUtils.ResolvedTableNames
                        .of(lookupTableRef, qlf.returnType().returnTypeName(), outputPackage).jooqTableClass();
                    builder.addMethod(buildQueryLookupFetcher(ctx, qlf, outputPackage));
                    builder.addMethod(buildQueryLookupRowsMethod(ctx, qlf, outputPackage));
                    builder.addMethod(LookupValuesJoinEmitter.buildInputRowsMethod(qlf, lookupTableClass));
                }
                case QueryField.QueryTableField qtf -> {
                    if (qtf.returnType().wrapper() instanceof FieldWrapper.Connection) {
                        builder.addMethod(buildQueryConnectionFetcher(ctx, qtf, outputPackage));
                    } else {
                        builder.addMethod(buildQueryTableFetcher(ctx, qtf, outputPackage));
                    }
                }
                case ChildField.ServiceTableField stf -> {
                    TypeName servicePerKeyType = stf.returnType().table().recordClass();
                    builder.addMethod(buildServiceDataFetcher(ctx, stf.name(), stf, stf.method(), stf.returnType(), parentTable, servicePerKeyType, className, outputPackage, stf.errorChannel()));
                    builder.addMethod(buildServiceRowsMethod(ctx, stf, stf.method(), stf.returnType(), servicePerKeyType, stf.parentTypeName(), outputPackage));
                }
                case ChildField.ServiceRecordField srf -> {
                    builder.addMethod(buildServiceDataFetcher(ctx, srf.name(), srf, srf.method(), srf.returnType(), parentTable, srf.elementType(), className, outputPackage, srf.errorChannel()));
                    builder.addMethod(buildServiceRowsMethod(ctx, srf, srf.method(), srf.returnType(), srf.elementType(), srf.parentTypeName(), outputPackage));
                }
                case ChildField.SplitTableField stf -> {
                    builder.addMethod(buildSplitQueryDataFetcher(ctx, stf, stf.returnType(), parentTable, outputPackage));
                    builder.addMethod(SplitRowsMethodEmitter.buildForSplitTable(ctx, stf, outputPackage));
                }
                case ChildField.SplitLookupTableField slf -> {
                    builder.addMethod(buildSplitQueryDataFetcher(ctx, slf, slf.returnType(), parentTable, outputPackage));
                    builder.addMethod(SplitRowsMethodEmitter.buildForSplitLookupTable(ctx, slf, outputPackage));
                    // Emit the VALUES-building input-rows helper alongside the rows method.
                    // The env-based variant (buildInputRowsMethod) reads args from
                    // env.getArgument(name) — correct for a Split* fetcher whose @lookupKey args
                    // live on the field itself (vs. the inline child-lookup path where
                    // args live on a parent's SelectedField).
                    if (slf.lookupMapping() instanceof no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) {
                        var lookupTableRef = slf.returnType().table();
                        var lookupTableClass = GeneratorUtils.ResolvedTableNames
                            .of(lookupTableRef, slf.returnType().returnTypeName(), outputPackage).jooqTableClass();
                        builder.addMethod(LookupValuesJoinEmitter.buildInputRowsMethod(slf, lookupTableClass));
                    }
                }
                case QueryField.QueryNodeField f              -> builder.addMethod(buildQueryNodeFetcher(ctx, f, outputPackage));
                case QueryField.QueryNodesField f             -> builder.addMethod(buildQueryNodesFetcher(ctx, f, outputPackage));
                case QueryField.QueryTableMethodTableField f  -> builder.addMethod(buildQueryTableMethodFetcher(ctx, f, outputPackage));
                case QueryField.QueryServiceTableField f      -> builder.addMethod(buildQueryServiceTableFetcher(ctx, f, outputPackage));
                case QueryField.QueryServiceRecordField f     -> builder.addMethod(buildQueryServiceRecordFetcher(ctx, f, outputPackage));
                // Stub variants — see STUBBED_VARIANTS
                case QueryField.QueryTableInterfaceField f    -> builder.addMethod(buildQueryTableInterfaceFieldFetcher(ctx, f, outputPackage));
                case QueryField.QueryInterfaceField f -> {
                    if (f.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn) {
                        MultiTablePolymorphicEmitter
                            .emitConnectionMethods(ctx, f.name(), f.participants(), Map.of(),
                                conn.defaultPageSize(), null, null, outputPackage)
                            .forEach(builder::addMethod);
                    } else {
                        MultiTablePolymorphicEmitter
                            .emitMethods(ctx, f.name(), f.participants(), f.returnType().wrapper().isList(), outputPackage)
                            .forEach(builder::addMethod);
                    }
                }
                case QueryField.QueryUnionField f -> {
                    if (f.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn) {
                        MultiTablePolymorphicEmitter
                            .emitConnectionMethods(ctx, f.name(), f.participants(), Map.of(),
                                conn.defaultPageSize(), null, null, outputPackage)
                            .forEach(builder::addMethod);
                    } else {
                        MultiTablePolymorphicEmitter
                            .emitMethods(ctx, f.name(), f.participants(), f.returnType().wrapper().isList(), outputPackage)
                            .forEach(builder::addMethod);
                    }
                }
                case MutationField.MutationInsertTableField f  -> builder.addMethod(buildMutationInsertFetcher(ctx, f, outputPackage));
                case MutationField.MutationUpdateTableField f  -> builder.addMethod(buildMutationUpdateFetcher(ctx, f, outputPackage));
                case MutationField.MutationDeleteTableField f  -> builder.addMethod(buildMutationDeleteFetcher(ctx, f, outputPackage));
                case MutationField.MutationUpsertTableField f  -> builder.addMethod(buildMutationUpsertFetcher(ctx, f, outputPackage));
                case MutationField.MutationServiceTableField f -> builder.addMethod(buildMutationServiceTableFetcher(ctx, f, outputPackage));
                case MutationField.MutationServiceRecordField f -> builder.addMethod(buildMutationServiceRecordFetcher(ctx, f, outputPackage));
                case MutationField.MutationDmlRecordField f    -> builder.addMethod(buildMutationDmlRecordFetcher(ctx, f, outputPackage));
                case MutationField.MutationBulkDmlRecordField f -> builder.addMethod(buildMutationBulkDmlRecordFetcher(ctx, f, outputPackage));
                // ColumnReferenceField has no fetcher method — inline projection via
                // TypeClassGenerator.$fields (Direct compaction) and a ColumnFetcher value emitted
                // by FetcherEmitter. The validator rejects the NodeIdEncodeKeys and ConditionJoin
                // shapes ahead of generation; no per-shape carve-out is needed here.
                case ChildField.ColumnReferenceField ignored    -> { }
                case ChildField.CompositeColumnReferenceField f -> builder.addMethod(stub(f));
                // ChildField.TableField / LookupTableField / CompositeColumnField have no fetcher
                // — inline projection via TypeClassGenerator.$fields plus a DataFetcher value
                // emitted by FetcherEmitter for the encode lambda (composite-key NodeId carriers).
                case ChildField.TableField ignored              -> { }
                case ChildField.LookupTableField ignored        -> { }
                case ChildField.CompositeColumnField ignored    -> { }
                case ChildField.TableInterfaceField f           -> builder.addMethod(buildTableInterfaceFieldFetcher(ctx, f, outputPackage));
                // No per-field fetcher method — the value is materialised in the parent record by
                // the enclosing TableInterfaceField fetcher's conditional LEFT JOIN, and the field
                // resolver reads it back via FetcherEmitter's ParticipantColumnReferenceField arm.
                case ChildField.ParticipantColumnReferenceField ignored -> { }
                case ChildField.RecordTableField rtf -> {
                    builder.addMethod(buildRecordBasedDataFetcher(ctx, rtf, rtf.returnType(), rtf.sourceKey(), resultType, outputPackage));
                    builder.addMethod(SplitRowsMethodEmitter.buildForRecordTable(ctx, rtf, outputPackage));
                }
                case ChildField.RecordLookupTableField rltf -> {
                    builder.addMethod(buildRecordBasedDataFetcher(ctx, rltf, rltf.returnType(), rltf.sourceKey(), resultType, outputPackage));
                    builder.addMethod(SplitRowsMethodEmitter.buildForRecordLookupTable(ctx, rltf, outputPackage));
                    // Input-rows helper identical in shape to SplitLookupTableField's — reads
                    // @lookupKey args from env.getArgument(name) and emits the typed Row<M+1>[].
                    if (rltf.lookupMapping() instanceof no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) {
                        var lookupTableRef = rltf.returnType().table();
                        var lookupTableClass = GeneratorUtils.ResolvedTableNames
                            .of(lookupTableRef, rltf.returnType().returnTypeName(), outputPackage).jooqTableClass();
                        builder.addMethod(LookupValuesJoinEmitter.buildInputRowsMethod(rltf, lookupTableClass));
                    }
                }
                case ChildField.TableMethodField f              -> builder.addMethod(buildChildTableMethodFetcher(ctx, f, outputPackage));
                case ChildField.RecordTableMethodField rtmf -> {
                    builder.addMethod(buildRecordBasedDataFetcher(ctx, rtmf, rtmf.returnType(), rtmf.sourceKey(), resultType, outputPackage));
                    builder.addMethod(SplitRowsMethodEmitter.buildForRecordTableMethod(ctx, rtmf, outputPackage));
                }
                // SingleRecordTableField has no per-field fetcher method — its DataFetcher
                // value is emitted inline by FetcherEmitter (env.getSource() typed cast +
                // response SELECT outside the DML transaction). The wiring happens in
                // FetcherRegistrationsEmitter.registrationEntry.
                case ChildField.SingleRecordTableField ignored  -> { }
                // R156 — both SingleRecordIdFieldFromReturning and SingleRecordTableFieldFromReturning
                // emit their DataFetcher value inline through FetcherEmitter (PK column read +
                // optional NodeId encode for Id; sealed switch over PkResolution arms for Table).
                // No per-field fetcher method is emitted here; wiring lands in
                // FetcherRegistrationsEmitter.
                case ChildField.SingleRecordIdFieldFromReturning ignored -> { }
                case ChildField.SingleRecordTableFieldFromReturning ignored -> { }
                case ChildField.InterfaceField f -> {
                    if (f.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn) {
                        MultiTablePolymorphicEmitter
                            .emitConnectionMethods(ctx, f.name(), f.participants(), f.participantJoinPaths(),
                                conn.defaultPageSize(), f.parentSourceKey(), f.parentResultType(), outputPackage)
                            .forEach(builder::addMethod);
                    } else {
                        MultiTablePolymorphicEmitter
                            .emitMethods(ctx, f.name(), f.participants(), f.participantJoinPaths(),
                                f.parentSourceKey(), f.parentResultType(),
                                f.returnType().wrapper().isList(), outputPackage)
                            .forEach(builder::addMethod);
                    }
                }
                case ChildField.UnionField f -> {
                    if (f.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn) {
                        MultiTablePolymorphicEmitter
                            .emitConnectionMethods(ctx, f.name(), f.participants(), f.participantJoinPaths(),
                                conn.defaultPageSize(), f.parentSourceKey(), f.parentResultType(), outputPackage)
                            .forEach(builder::addMethod);
                    } else {
                        MultiTablePolymorphicEmitter
                            .emitMethods(ctx, f.name(), f.participants(), f.participantJoinPaths(),
                                f.parentSourceKey(), f.parentResultType(),
                                f.returnType().wrapper().isList(), outputPackage)
                            .forEach(builder::addMethod);
                    }
                }
                case ChildField.NestingField ignored            -> { /* wired via FetcherRegistrationsEmitter: env -> env.getSource() */ }
                case ChildField.ConstructorField ignored        -> { /* wired via FetcherRegistrationsEmitter: env -> env.getSource() */ }
                // ServiceRecordField is dispatched alongside ServiceTableField above (shared
                // emitters parameterised by perKeyType). The "no-op" arm here keeps the switch
                // exhaustive without re-emitting; the variant has IMPLEMENTED_LEAVES membership.
                case ChildField.RecordField ignored             -> { /* wired via FetcherRegistrationsEmitter.propertyOrRecordValue */ }
                case ChildField.ComputedField ignored           -> { /* wired via FetcherEmitter (ColumnFetcher); projected via TypeClassGenerator.$fields() */ }
                case ChildField.PropertyField ignored           -> { /* wired via FetcherRegistrationsEmitter.propertyOrRecordValue */ }
                case ChildField.ErrorsField ignored             -> { /* wired via FetcherRegistrationsEmitter: PropertyDataFetcher.fetching(name) */ }
                // Cannot occur — filtered by generateForType before dispatch
                case InputField ignored ->
                    throw new AssertionError("InputField in type dispatch: " + ignored.qualifiedName());
                case GraphitronField.UnclassifiedField ignored ->
                    throw new AssertionError("UnclassifiedField in type dispatch: " + ignored.qualifiedName());
            }
        }

        if (ctx.isRequested(TypeFetcherEmissionContext.HelperKind.GRAPHITRON_CONTEXT)) {
            builder.addMethod(buildGraphitronContextHelper(outputPackage));
        }

        // Emit static map fields for any TextMapLookup extractions on method-backed fields.
        // The *Fetchers class is the home for these maps because service/table-method code
        // must not reference user class names or GraphQL enum names directly.
        fields.stream()
            .filter(f -> f instanceof MethodBackedField)
            .map(f -> (MethodBackedField) f)
            .flatMap(f -> f.method().callParams().stream())
            .filter(p -> p.extraction() instanceof CallSiteExtraction.TextMapLookup)
            .map(p -> (CallSiteExtraction.TextMapLookup) p.extraction())
            .collect(java.util.stream.Collectors.toMap(
                CallSiteExtraction.TextMapLookup::mapFieldName,
                tl -> tl,
                (a, b) -> a,
                java.util.LinkedHashMap::new))
            .values()
            .forEach(tl -> builder.addField(TypeConditionsGenerator.buildTextEnumMapField(tl)));

        // Emit per-bean instantiation helpers (createBean / createBeans) for any InputBean
        // extraction on method-backed fields. Dedup by bean class — nested beans are collected
        // transitively so a single bean class always emits exactly one pair of helpers per
        // *Fetchers class, regardless of how many distinct service methods reach it.
        var beanHelpers = new java.util.LinkedHashMap<no.sikt.graphitron.javapoet.ClassName,
            CallSiteExtraction.InputBean>();
        fields.stream()
            .filter(f -> f instanceof MethodBackedField)
            .map(f -> (MethodBackedField) f)
            .flatMap(f -> f.method().callParams().stream())
            .filter(p -> p.extraction() instanceof CallSiteExtraction.InputBean)
            .map(p -> (CallSiteExtraction.InputBean) p.extraction())
            .forEach(ib -> InputBeanInstantiationEmitter.collectTransitively(ib, beanHelpers));
        for (var ib : beanHelpers.values()) {
            builder.addMethod(InputBeanInstantiationEmitter.buildSingularHelper(ib));
            builder.addMethod(InputBeanInstantiationEmitter.buildPluralHelper(ib,
                no.sikt.graphitron.javapoet.ClassName.bestGuess(outputPackage + "." + className)));
        }

        // Emit orderBy helper methods for fields with a dynamic @orderBy argument. Covers
        // QueryTableField (root connection + list fetchers) and SplitTableField+Connection
        // (per-parent paginated rows method).
        for (var field : fields) {
            if (field instanceof QueryField.QueryTableField qtf
                    && qtf.orderBy() instanceof OrderBySpec.Argument arg) {
                var tableRef = qtf.returnType().table();
                var names = GeneratorUtils.ResolvedTableNames.of(tableRef, qtf.returnType().returnTypeName(), outputPackage);
                builder.addMethod(buildOrderByHelperMethod(qtf.name(), arg, names, tableRef, outputPackage));
            } else if (field instanceof ChildField.SplitTableField stf
                    && stf.returnType().wrapper() instanceof FieldWrapper.Connection
                    && stf.orderBy() instanceof OrderBySpec.Argument arg) {
                var tableRef = stf.returnType().table();
                var names = GeneratorUtils.ResolvedTableNames.of(tableRef, stf.returnType().returnTypeName(), outputPackage);
                builder.addMethod(buildOrderByHelperMethod(stf.name(), arg, names, tableRef, outputPackage));
            }
        }

        // Emit list-shape scatterByIdx helper whenever any plain-list-cardinality Split* or
        // record-backed batched field is present. Single-cardinality fields use
        // scatterSingleByIdx; Connection-cardinality fields use scatterConnectionByIdx.
        boolean hasListSplitField = fields.stream().anyMatch(f ->
            (f instanceof ChildField.SplitTableField stf
                && stf.returnType().wrapper() instanceof FieldWrapper.List)
            || (f instanceof ChildField.SplitLookupTableField slf
                && slf.returnType().wrapper() instanceof FieldWrapper.List)
            || (f instanceof ChildField.RecordTableField rtf && rtf.returnType().wrapper().isList())
            || f instanceof ChildField.RecordLookupTableField
            || (f instanceof ChildField.RecordTableMethodField rtmf && rtmf.returnType().wrapper().isList()
                && !rtmf.emitsSingleRecordPerKey()));
        if (hasListSplitField) {
            builder.addMethod(SplitRowsMethodEmitter.buildScatterByIdxHelper());
        }

        // Single-cardinality sibling: scatterSingleByIdx returns List<Record> (one slot per key,
        // null where no match) rather than List<List<Record>>. Gated on the BatchKeyField
        // capability emitsSingleRecordPerKey, which folds two structurally unrelated triggers
        // (single-cardinality Split* fields, RecordTableField with AccessorKeyedMany) onto
        // one uniform answer. A future variant whose rows-method emits 1 record per key
        // implements the capability and reaches this gate without a third disjunct here.
        boolean hasSingleRecordPerKeyField = fields.stream()
            .anyMatch(f -> f instanceof BatchKeyField bkf && bkf.emitsSingleRecordPerKey());
        if (hasSingleRecordPerKeyField) {
            builder.addMethod(SplitRowsMethodEmitter.buildScatterSingleByIdxHelper());
        }

        // Connection-cardinality sibling: scatterConnectionByIdx returns List<ConnectionResult>.
        // Each per-parent bucket wraps the over-fetch slice with the shared PageRequest from the
        // windowed rows-method invocation. See plan-split-query-connection.md §1.
        boolean hasConnectionSplitField = fields.stream().anyMatch(f ->
            f instanceof ChildField.SplitTableField stf
                && stf.returnType().wrapper() instanceof FieldWrapper.Connection);
        if (hasConnectionSplitField) {
            builder.addMethod(SplitRowsMethodEmitter.buildScatterConnectionByIdxHelper(outputPackage));
        }

        // emptyScatter is needed whenever @lookupKey input can be empty at request time — that is,
        // for SplitLookupTableField and RecordLookupTableField. Plain Split* / RecordTable fields
        // never use the empty-input short-circuit.
        boolean hasSplitLookupField = fields.stream().anyMatch(f ->
            f instanceof ChildField.SplitLookupTableField || f instanceof ChildField.RecordLookupTableField);
        if (hasSplitLookupField) {
            builder.addMethod(SplitRowsMethodEmitter.buildEmptyScatterHelper());
        }

        return builder.build();
    }

    /**
     * Generates a fetcher for a root-query table field that builds the condition, optional
     * orderBy, and executes inline SQL using {@code Type.$fields(sel, table, env)} for projection.
     *
     * <p>Generated code (list variant):
     * <pre>{@code
     * public static Result<Record> films(DataFetchingEnvironment env) {
     *     var dsl = graphitronContext(env).getDslContext(env);
     *     FilmTable table = Tables.FILM;
     *     var condition = DSL.noCondition();
     *     List<SortField<?>> orderBy = List.of();
     *     return dsl.select(Film.$fields(env.getSelectionSet(), table, env))
     *               .from(table).where(condition).orderBy(orderBy).fetch();
     * }
     * }</pre>
     */
    private static MethodSpec buildQueryTableFetcher(TypeFetcherEmissionContext ctx, QueryField.QueryTableField qtf, String outputPackage) {
        var tableRef = qtf.returnType().table();
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, qtf.returnType().returnTypeName(), outputPackage);
        boolean isList = qtf.returnType().wrapper().isList();

        var valueType = isList
            ? (TypeName) ParameterizedTypeName.get(RESULT, RECORD)
            : RECORD;

        var builder = MethodSpec.methodBuilder(qtf.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");

        builder.beginControlFlow("try");
        builder.addCode(GeneratorUtils.declareTableLocal(names, tableRef));
        String tableLocal = names.tableLocalName();
        builder.addCode(buildConditionCall(qtf, tableLocal, outputPackage));

        var dslContextClass = ClassName.get("org.jooq", "DSLContext");
        if (isList) {
            builder.addCode(buildOrderByCode(qtf.orderBy(), qtf.name(), tableLocal));
            builder.addStatement("$T dsl = $L.getDslContext(env)", dslContextClass, ctx.graphitronContextCall());
            builder.addCode(CodeBlock.builder()
                .add("$T payload = dsl\n", valueType)
                .indent()
                .add(".select($T.$$fields(env.getSelectionSet(), $L, env))\n", names.typeClass(), tableLocal)
                .add(".from($L)\n", tableLocal)
                .add(".where(condition)\n")
                .add(".orderBy(orderBy)\n")
                .add(".fetch();\n")
                .unindent()
                .build());
        } else {
            builder.addStatement("$T dsl = $L.getDslContext(env)", dslContextClass, ctx.graphitronContextCall());
            builder.addCode(CodeBlock.builder()
                .add("$T payload = dsl\n", valueType)
                .indent()
                .add(".select($T.$$fields(env.getSelectionSet(), $L, env))\n", names.typeClass(), tableLocal)
                .add(".from($L)\n", tableLocal)
                .add(".where(condition)\n")
                .add(".fetchOne();\n")
                .unindent()
                .build());
        }
        builder.addCode(returnSyncSuccess(valueType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(redactCatchArm(outputPackage));
        builder.endControlFlow();

        return builder.build();
    }

    /**
     * Generates the fetcher for a {@link QueryField.QueryTableInterfaceField}.
     *
     * <p>Mirrors {@link #buildQueryTableFetcher} exactly, with one addition: the discriminator
     * column is projected unconditionally alongside the selection-set columns so the
     * {@code TypeResolver} registered by {@code GraphitronSchemaClassGenerator} can route each
     * returned row to the correct concrete GraphQL type at runtime.
     *
     * <p>Generated code (list variant):
     * <pre>{@code
     * public static Result<Record> allContent(DataFetchingEnvironment env) {
     *     ContentTable table = Tables.CONTENT;
     *     Condition condition = QueryConditions.allContentCondition(table, env);
     *     List<SortField<?>> orderBy = List.of();
     *     DSLContext dsl = graphitronContext(env).getDslContext(env);
     *     return dsl
     *         .select(table.asterisk(), DSL.field(DSL.name("CONTENT_TYPE")))
     *         .from(table)
     *         .where(condition)
     *         .orderBy(orderBy)
     *         .fetch();
     * }
     * }</pre>
     */
    private static MethodSpec buildQueryTableInterfaceFieldFetcher(
            TypeFetcherEmissionContext ctx, QueryField.QueryTableInterfaceField qtif, String outputPackage) {
        var tableRef = qtif.returnType().table();
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, qtif.returnType().returnTypeName(), outputPackage);
        boolean isList = qtif.returnType().wrapper().isList();
        var valueType = isList ? (TypeName) ParameterizedTypeName.get(RESULT, RECORD) : RECORD;

        var builder = MethodSpec.methodBuilder(qtif.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");

        builder.beginControlFlow("try");
        builder.addCode(GeneratorUtils.declareTableLocal(names, tableRef));
        String tableLocal = names.tableLocalName();
        builder.addCode(buildConditionCall(qtif.parentTypeName(), qtif.name(), tableLocal, outputPackage));
        builder.addCode(buildDiscriminatorFilter(qtif.discriminatorColumn(), qtif.knownDiscriminatorValues()));
        builder.addCode(buildInterfaceFieldsList(qtif.participants(), qtif.discriminatorColumn(), tableLocal, outputPackage));
        builder.addCode(buildCrossTableAliasDeclarations(qtif.participants(), tableLocal));

        var dslContextClass = ClassName.get("org.jooq", "DSLContext");
        var selectJoinStepClass = ClassName.get("org.jooq", "SelectJoinStep");
        var selectJoinStepOfRecord = ParameterizedTypeName.get(selectJoinStepClass, RECORD);

        builder.addStatement("$T dsl = $L.getDslContext(env)", dslContextClass, ctx.graphitronContextCall());
        builder.addStatement("$T step = dsl.select(new $T<>(fields)).from($L)",
            selectJoinStepOfRecord, ArrayList.class, tableLocal);
        builder.addCode(buildCrossTableJoinChain(qtif.participants(), qtif.discriminatorColumn(), tableLocal));

        if (isList) {
            builder.addCode(buildOrderByCode(qtif.orderBy(), qtif.name(), tableLocal));
            builder.addStatement("$T payload = step.where(condition).orderBy(orderBy).fetch()", valueType);
        } else {
            builder.addStatement("$T payload = step.where(condition).fetchOne()", valueType);
        }
        builder.addCode(returnSyncSuccess(valueType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(redactCatchArm(outputPackage));
        builder.endControlFlow();

        return builder.build();
    }

    /**
     * Generates the fetcher for a {@link ChildField.TableInterfaceField}.
     *
     * <p>Executes a per-parent SQL query: conditions on the single-hop FK join path extracted
     * from {@code env.getSource()}, then projects all columns via {@code table.asterisk()} plus
     * the discriminator column so the {@code TypeResolver} can route the result to the correct
     * concrete type. The classifier guarantees a single-hop {@link JoinStep.FkJoin} path;
     * multi-hop and {@link JoinStep.ConditionJoin} paths are rejected at classification time.
     *
     * <p>Generated code (single-value variant, one-hop FK where child holds the FK):
     * <pre>{@code
     * public static Record filmContent(DataFetchingEnvironment env) {
     *     Record parentRecord = (Record) env.getSource();
     *     ContentTable table = Tables.CONTENT;
     *     DSLContext dsl = graphitronContext(env).getDslContext(env);
     *     Condition condition = DSL.field(DSL.name("FILM_ID")).eq(parentRecord.get(DSL.name("FILM_ID")));
     *     return dsl
     *         .select(table.asterisk(), DSL.field(DSL.name("CONTENT_TYPE")))
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

        var dslContextClass = ClassName.get("org.jooq", "DSLContext");
        builder.addStatement("$T dsl = $L.getDslContext(env)", dslContextClass, ctx.graphitronContextCall());

        // Build join-path condition. Only single-hop FkJoin is supported; multi-hop and
        // ConditionJoin paths are caught at classification time.
        builder.addCode(buildJoinPathCondition(tif.joinPath(), tableRef.tableName()));
        builder.addCode(buildDiscriminatorFilter(tif.discriminatorColumn(), tif.knownDiscriminatorValues()));
        builder.addCode(buildInterfaceFieldsList(tif.participants(), tif.discriminatorColumn(), tableLocal, outputPackage));
        builder.addCode(buildCrossTableAliasDeclarations(tif.participants(), tableLocal));

        var selectJoinStepClass = ClassName.get("org.jooq", "SelectJoinStep");
        var selectJoinStepOfRecord = ParameterizedTypeName.get(selectJoinStepClass, RECORD);
        builder.addStatement("$T step = dsl.select(new $T<>(fields)).from($L)",
            selectJoinStepOfRecord, ArrayList.class, tableLocal);
        builder.addCode(buildCrossTableJoinChain(tif.participants(), tif.discriminatorColumn(), tableLocal));

        if (isList) {
            builder.addCode(buildOrderByCode(tif.orderBy(), tif.name(), tableLocal));
            builder.addStatement("$T payload = step.where(condition).orderBy(orderBy).fetch()", valueType);
        } else {
            builder.addStatement("$T payload = step.where(condition).fetchOne()", valueType);
        }
        builder.addCode(returnSyncSuccess(valueType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(redactCatchArm(outputPackage));
        builder.endControlFlow();

        return builder.build();
    }

    /**
     * Builds the {@code Condition} declaration from a single-hop {@link JoinStep.FkJoin} path
     * for a {@link ChildField.TableInterfaceField} fetcher.
     *
     * <p>The hop's source side is the parent table, target side the child table — by synthesis-time
     * orientation. The FK-direction question (which end of the catalog FK lives on which side)
     * was answered once in {@code BuildContext.synthesizeFkJoin} and baked into the slot pair, so
     * the emitter reads {@code child.<targetSide> = parentRecord.<sourceSide>} uniformly without
     * re-deriving direction.
     *
     * <p>Precondition: the classifier guarantees exactly one {@link JoinStep.FkJoin} step
     * (multi-hop and {@link JoinStep.ConditionJoin} paths are rejected at classification time).
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "fk-join.slots-oriented-source-and-target",
        reliesOn = "Reads slot.targetSide() (child table) and slot.sourceSide() (parent table) "
            + "from the single FK slot to build the parentRecord-correlation condition without "
            + "the legacy parentHoldsFk derivation; depends on synthesis-time slot orientation.")
    private static CodeBlock buildJoinPathCondition(List<JoinStep> joinPath, String childTableName) {
        var fkJoin = (JoinStep.FkJoin) joinPath.get(0);
        var slot = fkJoin.slots().get(0);
        String childCol        = slot.targetSide().sqlName();
        String parentRecordCol = slot.sourceSide().sqlName();

        return CodeBlock.builder()
            .addStatement("$T condition = $T.field($T.name($S)).eq(parentRecord.get($T.name($S)))",
                CONDITION, DSL, DSL, childCol, DSL, parentRecordCol)
            .build();
    }

    /**
     * Emits a {@code LinkedHashSet<Field<?>> fields} declaration populated with the discriminator
     * column and each table-bound participant's {@code $fields} contribution.
     *
     * <p>The {@code LinkedHashSet} preserves insertion order and deduplicates field references so
     * shared columns (e.g. {@code title} declared on both {@code FilmContent} and
     * {@code ShortContent}) appear only once in the SELECT list.
     *
     * <p>The discriminator column is always included first, unconditionally, so the TypeResolver
     * can route each returned row to the correct concrete type even when the GraphQL selection set
     * does not explicitly request it.
     *
     * <p>The per-participant {@code $fields} call here passes {@code env.getSelectionSet()}
     * unfiltered — the shared-name over-selection that R108 closes at the multi-table Stage-2 site
     * is also present in shape here, but the {@code LinkedHashSet} above deduplicates field
     * references so shared columns collapse to one SELECT entry. Every fixture currently exercising
     * this path falls in the deduped shape (shared GraphQL field name backed by the same column on
     * the one underlying table). The shape that breaks the dedup — two participants of the same
     * {@code TableInterfaceType} declaring a shared GraphQL field name backed by <em>different</em>
     * columns on the same table — is not exercised by any fixture today. When a future fixture
     * lands the unmasked shape, fold in {@code PolymorphicSelectionSet.restrictTo} here; the helper
     * from R108 is reusable as-is.
     */
    private static CodeBlock buildInterfaceFieldsList(
            List<ParticipantRef> participants, String discriminatorColumn,
            String tableLocal, String outputPackage) {
        var b = CodeBlock.builder();
        var fieldType = ParameterizedTypeName.get(
            ClassName.get("org.jooq", "Field"),
            WildcardTypeName.subtypeOf(Object.class));
        var setType = ParameterizedTypeName.get(
            ClassName.get(LinkedHashSet.class), fieldType);
        b.addStatement("$T fields = new $T<>()", setType, LinkedHashSet.class);
        b.addStatement("fields.add($T.field($T.name($S)))", DSL, DSL, discriminatorColumn);
        for (var participant : participants) {
            if (!(participant instanceof ParticipantRef.TableBound tb)) continue;
            var typeClass = ClassName.get(outputPackage + ".types", tb.typeName());
            b.addStatement("fields.addAll($T.$$fields(env.getSelectionSet(), $L, env))",
                typeClass, tableLocal);
        }
        return b.build();
    }

    /**
     * Emits per-participant cross-table alias variable declarations together with the
     * selection-set-gated {@code fields.add(...)} call for each cross-table field. Each cross-table
     * field expands to:
     * <pre>{@code
     * Film FilmContent_rating_alias = null;
     * if (env.getSelectionSet().contains("FilmContent.rating")) {
     *     FilmContent_rating_alias = Tables.FILM.as("FilmContent_rating");
     *     fields.add(FilmContent_rating_alias.RATING.as("FilmContent_rating"));
     * }
     * }</pre>
     *
     * <p>The variable's null-default outside the {@code if} lets {@link #buildCrossTableJoinChain}
     * test for the field's presence later in the same method body to gate the LEFT JOIN.
     *
     * <p>The selection-set pattern uses {@code <Type>.<field>} (dot, not slash): graphql-java's
     * {@code DataFetchingFieldSelectionSet} flattens type-conditioned fields under inline fragments
     * as {@code "<Type>.<fieldName>"}, sitting in the same flattened set as the bare {@code <fieldName>}.
     * The slash separator is reserved for parent/child path nesting in the glob pattern.
     *
     * <p>Participants without a discriminator value (which would leave the JOIN unconstrained
     * across all rows) are skipped — a TableInterfaceType participant without {@code @discriminator}
     * is rejected upstream, but defensive filtering here keeps the emitter robust if that
     * invariant ever changes.
     */
    private static CodeBlock buildCrossTableAliasDeclarations(
            List<ParticipantRef> participants, String tableLocal) {
        var b = CodeBlock.builder();
        for (var participant : participants) {
            if (!(participant instanceof ParticipantRef.TableBound tb)) continue;
            if (tb.discriminatorValue() == null) continue;
            for (var ctf : tb.crossTableFields()) {
                var names = GeneratorUtils.ResolvedTableNames.ofTable(ctf.targetTable());
                String aliasVar = ctf.aliasVarName();
                b.addStatement("$T $L = null", names.jooqTableClass(), aliasVar);
                b.beginControlFlow("if (env.getSelectionSet().contains($S))",
                    tb.typeName() + "." + ctf.fieldName());
                b.addStatement("$L = $T.$L.as($S)", aliasVar, names.tablesClass(),
                    ctf.targetTable().javaFieldName(), ctf.aliasName());
                b.addStatement("fields.add($L.$L.as($S))", aliasVar,
                    ctf.column().javaName(), ctf.aliasName());
                b.endControlFlow();
            }
        }
        return b.build();
    }

    /**
     * Emits the conditional {@code step = step.leftJoin(...).on(...)} blocks for each participant
     * cross-table field, matched to the alias variables declared by
     * {@link #buildCrossTableAliasDeclarations}. The ON clause includes the FK equality plus
     * a discriminator equality so non-matching rows carry NULL through the join, which the
     * TypeResolver then routes back to the correct concrete type by reading the discriminator
     * off the interface table.
     *
     * <p>Caller declares {@code step} as {@code SelectJoinStep<Record>} initialised to
     * {@code dsl.select(...).from(<tableLocal>)}; this method appends LEFT JOINs and reassigns
     * {@code step} to the same variable so the trailing {@code .where().orderBy().fetch()} chain
     * continues to typecheck.
     *
     * <p>Multi-column FK paths are supported via per-column equalities chained with {@code .and(...)}
     * (positional pairing of {@code sourceColumns} / {@code targetColumns} per
     * {@link no.sikt.graphitron.rewrite.model.JoinStep.FkJoin}'s arity invariant).
     */
    private static CodeBlock buildCrossTableJoinChain(
            List<ParticipantRef> participants, String discriminatorColumn, String tableLocal) {
        var b = CodeBlock.builder();
        for (var participant : participants) {
            if (!(participant instanceof ParticipantRef.TableBound tb)) continue;
            if (tb.discriminatorValue() == null) continue;
            for (var ctf : tb.crossTableFields()) {
                String aliasVar = ctf.aliasVarName();
                // The @reference is parsed starting from the interface table, so the slot's
                // source side is on the parent (interface table = tableLocal) and target side on
                // the joined alias. Slot orientation is direction-blind; emitCorrelationWhere
                // reads target.<targetSide>.eq(parent.<sourceSide>) uniformly.
                var fkOn = JoinPathEmitter.emitCorrelationWhere(ctf.fkJoin(), aliasVar, tableLocal);
                var onCondition = CodeBlock.builder()
                    .add("$L.and($T.field($T.name($S)).eq($S))",
                        fkOn, DSL, DSL, discriminatorColumn, tb.discriminatorValue())
                    .build();
                b.beginControlFlow("if ($L != null)", aliasVar);
                b.addStatement("step = step.leftJoin($L).on($L)", aliasVar, onCondition);
                b.endControlFlow();
            }
        }
        return b.build();
    }

    /**
     * Emits {@code condition = condition.and(DSL.field(DSL.name(col)).in(val1, val2, ...))}
     * to restrict results to rows with a known discriminator value. Mirrors the legacy generator
     * which always emits {@code WHERE col IN (...known values...)}. When {@code knownValues} is
     * empty, emits nothing (no restriction added).
     */
    private static CodeBlock buildDiscriminatorFilter(String discriminatorColumn, List<String> knownValues) {
        if (knownValues.isEmpty()) return CodeBlock.of("");
        var inArgs = knownValues.stream()
            .map(v -> CodeBlock.of("$S", v))
            .collect(CodeBlock.joining(", "));
        return CodeBlock.builder()
            .addStatement("condition = condition.and($T.field($T.name($S)).in($L))",
                DSL, DSL, discriminatorColumn, inArgs)
            .build();
    }

    private static CodeBlock buildConditionCall(QueryField.QueryTableField qtf, String srcAlias, String outputPackage) {
        return buildConditionCall(qtf.parentTypeName(), qtf.name(), srcAlias, outputPackage);
    }

    private static CodeBlock buildConditionCall(String parentTypeName, String fieldName, String srcAlias, String outputPackage) {
        var queryConditionsClass = ClassName.get(
            outputPackage + ".conditions",
            parentTypeName + QueryConditionsGenerator.CLASS_NAME_SUFFIX);
        return CodeBlock.builder()
            .addStatement("$T condition = $T.$L($L, env)",
                CONDITION, queryConditionsClass,
                QueryConditionsGenerator.conditionMethodName(fieldName), srcAlias)
            .build();
    }

    /**
     * Emits the fetcher for a {@link QueryField.QueryTableMethodTableField}: declares the
     * developer-returned table local with the specific jOOQ table class (e.g. {@code Film}),
     * then projects via {@code $fields} over that table. The developer method's parameter
     * list is reproduced in declaration order via {@link ArgCallEmitter#buildMethodBackedCallArgs};
     * after R43 the method receives GraphQL field arguments and context values only, with no
     * leading Table parameter (graphitron derives the target table from the method's return type).
     *
     * <p>The local is declared with the specific table class (e.g. {@code Film}, not
     * {@code Table<?>}). Type-strictness is enforced at classifier time
     * (Invariants §3): {@link ServiceCatalog#reflectTableMethod} rejects developer
     * methods whose return type is wider than the generated jOOQ table class for the
     * field's {@code @table}-bound return type, so no downcast is needed in the emitter.
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "service-catalog-strict-tablemethod-return",
        reliesOn = "Declares <SpecificTable> table = Method.x(...) with no downcast and feeds "
            + "the local directly into <SpecificTable>Type.$fields(...). A wider return type "
            + "would require a cast or a wildcard local.")
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "service-catalog-tablemethod-must-be-static",
        reliesOn = "Emits 'ClassName.method(table, ...)' static-call shape unconditionally for "
            + "@tableMethod refs; the catalog rejects instance @tableMethod methods at classify "
            + "time so the emitter doesn't have to fork on call shape.")
    private static MethodSpec buildQueryTableMethodFetcher(TypeFetcherEmissionContext ctx, QueryField.QueryTableMethodTableField qtmtf,
                                                            String outputPackage) {
        var tableRef = qtmtf.returnType().table();
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, qtmtf.returnType().returnTypeName(), outputPackage);
        boolean isList = qtmtf.returnType().wrapper().isList();

        TypeName valueType = isList ? ParameterizedTypeName.get(RESULT, RECORD) : RECORD;
        var dslContextClass = ClassName.get("org.jooq", "DSLContext");

        var methodClass = ClassName.bestGuess(qtmtf.method().className());
        String conditionsClassName = outputPackage + ".conditions."
            + qtmtf.parentTypeName() + QueryConditionsGenerator.CLASS_NAME_SUFFIX;

        var builder = MethodSpec.methodBuilder(qtmtf.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");

        builder.beginControlFlow("try");
        // <SpecificTableClass> table = MethodClass.method(<args>);
        // No cast: classifier-time return-type check (Invariants §3) guarantees the developer's
        // method returns the specific table class. A wider return type fails classification.
        // No leading Table arg: after R43 @tableMethod methods are passed GraphQL field args
        // and context values only; graphitron derives the target table from the return type.
        builder.addStatement("$T table = $T.$L($L)",
            names.jooqTableClass(),
            methodClass,
            qtmtf.method().methodName(),
            ArgCallEmitter.buildMethodBackedCallArgs(ctx, qtmtf.method(), null, conditionsClassName));

        builder.addStatement("$T dsl = $L.getDslContext(env)", dslContextClass, ctx.graphitronContextCall());
        builder.addCode(CodeBlock.builder()
            .add("$T payload = dsl\n", valueType)
            .indent()
            .add(".select($T.$$fields(env.getSelectionSet(), table, env))\n", names.typeClass())
            .add(".from(table)\n")
            .add(isList ? ".fetch();\n" : ".fetchOne();\n")
            .unindent()
            .build());
        builder.addCode(returnSyncSuccess(valueType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(catchArm(outputPackage, qtmtf.errorChannel()));
        builder.endControlFlow();

        return builder.build();
    }

    /**
     * Emits the fetcher for a {@link ChildField.TableMethodField}: per-row call to the developer's
     * static {@code @tableMethod} returning the target table, joined back to the parent record via
     * the resolved {@link JoinStep} chain.
     *
     * <p>Modelled on {@link #buildQueryTableMethodFetcher} (the root-site cognate) with one
     * difference: a parent-correlation predicate built from {@code field.joinPath()} is added to
     * the WHERE so the child fetch is scoped to the parent row. Unlike {@code RecordTableField}
     * this is per-row, not DataLoader-keyed — {@code TableMethodField} carries no
     * {@code parentSourceKey} / {@code loaderRegistration}.
     *
     * <p>Single-hop {@link JoinStep.FkJoin} is the shipped emit shape — the common case in
     * practice and the only one exercised by the R43 commit 3 pipeline + execution coverage.
     * Multi-hop FK paths and {@link JoinStep.ConditionJoin} arms surface a runtime
     * {@link UnsupportedOperationException} so classification stays permissive (the schema is
     * still emittable) but the runtime gap is explicit.
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "tablemethod-resolver-return-is-table-bound",
        reliesOn = "Reads field.returnType().table() to declare the specific generated jOOQ table "
            + "local without a cast. The resolver's TableBoundReturnType invariant guarantees the "
            + "narrow return type at this site.")
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "service-catalog-strict-tablemethod-return",
        reliesOn = "Declares <SpecificTable> table = Method.x(...) with no downcast. The classifier "
            + "rejects wider Table<?> returns.")
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "service-catalog-tablemethod-must-be-static",
        reliesOn = "Emits 'ClassName.method(...)' static-call shape unconditionally. Instance "
            + "tableMethod methods are rejected at classification.")
    private static MethodSpec buildChildTableMethodFetcher(TypeFetcherEmissionContext ctx, ChildField.TableMethodField tmf,
                                                            String outputPackage) {
        var tableRef = tmf.returnType().table();
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, tmf.returnType().returnTypeName(), outputPackage);
        boolean isList = tmf.returnType().wrapper().isList();

        TypeName valueType = isList ? ParameterizedTypeName.get(RESULT, RECORD) : RECORD;
        var dslContextClass = ClassName.get("org.jooq", "DSLContext");

        var methodClass = ClassName.bestGuess(tmf.method().className());
        String conditionsClassName = outputPackage + ".conditions."
            + tmf.parentTypeName() + QueryConditionsGenerator.CLASS_NAME_SUFFIX;

        var builder = MethodSpec.methodBuilder(tmf.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");

        builder.beginControlFlow("try");

        List<JoinStep> path = tmf.joinPath();
        boolean unsupportedPath = path.isEmpty() || path.size() > 1
            || !(path.get(0) instanceof JoinStep.FkJoin);
        if (unsupportedPath) {
            // Multi-hop FK paths and ConditionJoin terminals are accepted by the classifier so the
            // schema remains well-formed, but R43 commit 3 ships only the single-hop FK emit shape
            // (the common case, and the one covered by the planned pipeline + execution tests).
            // Surfacing the gap as a runtime throw rather than an empty fetcher keeps the failure
            // mode loud and pointable.
            String shapeLabel = path.isEmpty()
                ? "empty joinPath"
                : path.size() > 1 ? "multi-hop join path" : "ConditionJoin path";
            builder.addStatement("throw new $T($S)",
                UnsupportedOperationException.class,
                "child @tableMethod with " + shapeLabel + " is not yet emitted — only single-hop FK "
                    + "paths ship in R43 commit 3 (multi-hop and condition-join emit are follow-ups)");
            builder.nextControlFlow("catch ($T e)", Exception.class);
            builder.addCode(catchArm(outputPackage, tmf.errorChannel()));
            builder.endControlFlow();
            return builder.build();
        }

        builder.addStatement("$T parentRecord = ($T) env.getSource()", RECORD, RECORD);
        // Developer-authored static method returning the specific generated jOOQ table class.
        // No cast: classifier-time return-type strictness (Invariants §3) guarantees the precise type.
        builder.addStatement("$T table = $T.$L($L)",
            names.jooqTableClass(),
            methodClass,
            tmf.method().methodName(),
            ArgCallEmitter.buildMethodBackedCallArgs(ctx, tmf.method(), null, conditionsClassName));

        builder.addStatement("$T dsl = $L.getDslContext(env)", dslContextClass, ctx.graphitronContextCall());

        // Parent-row correlation. For each slot in the single FK hop, target-side column on the
        // developer's table equals source-side column from the parent record. AND across composite FKs.
        var fkJoin = (JoinStep.FkJoin) path.get(0);
        builder.addCode(buildTableMethodParentCorrelation(fkJoin));

        builder.addCode(CodeBlock.builder()
            .add("$T payload = dsl\n", valueType)
            .indent()
            .add(".select($T.$$fields(env.getSelectionSet(), table, env))\n", names.typeClass())
            .add(".from(table)\n")
            .add(".where(condition)\n")
            .add(isList ? ".fetch();\n" : ".fetchOne();\n")
            .unindent()
            .build());
        builder.addCode(returnSyncSuccess(valueType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(catchArm(outputPackage, tmf.errorChannel()));
        builder.endControlFlow();

        return builder.build();
    }

    /**
     * Builds the {@code Condition condition = ...} declaration for a child {@code @tableMethod}
     * fetcher's parent-correlation WHERE clause. ANDs target-side-equals-source-side equality
     * predicates across every slot of the single FK hop. The target side reads off the
     * {@code table} local (the developer's returned table), the source side reads off
     * {@code parentRecord.get(...)} via the column's SQL name.
     *
     * <p>Composite FKs (more than one slot) are uncommon for {@code @tableMethod} fields in
     * practice, but the emitter handles them uniformly via {@code DSL.and(...)} composition.
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "fk-join.slots-oriented-source-and-target",
        reliesOn = "Reads slot.targetSide() (developer table's column) and slot.sourceSide() "
            + "(parent record's column) without re-deriving FK direction; depends on synthesis-time "
            + "slot orientation.")
    private static CodeBlock buildTableMethodParentCorrelation(JoinStep.FkJoin fkJoin) {
        var slots = fkJoin.slots();
        if (slots.isEmpty()) {
            // jOOQ catalog unavailable at build time — emit a runtime-throwing condition so the
            // mismatch surfaces at execution rather than silently producing broken SQL.
            return CodeBlock.builder()
                .addStatement("$T condition = $T.noCondition()", CONDITION, DSL)
                .build();
        }
        var code = CodeBlock.builder().add("$T condition = ", CONDITION);
        for (int i = 0; i < slots.size(); i++) {
            var slot = slots.get(i);
            ClassName columnType = ClassName.bestGuess(slot.sourceSide().columnClass());
            if (i > 0) code.add(".and(");
            code.add("table.$L.eq(parentRecord.get($T.name($S), $T.class))",
                slot.targetSide().javaName(), DSL, slot.sourceSide().sqlName(), columnType);
            if (i > 0) code.add(")");
        }
        code.add(";\n");
        return code.build();
    }

    /**
     * Emits the fetcher for a {@link QueryField.QueryServiceTableField}: a direct call to
     * the developer service method, with an optional {@code dsl} local declared first
     * if the method takes a {@link org.jooq.DSLContext}. No projection — graphql-java's
     * column fetchers traverse the service-returned {@code Record}/{@code Result<Record>}.
     *
     * <p>Return type is the specific {@code Result<<RecordClass>>} for List cardinality or
     * the specific {@code <RecordClass>} for Single. Type-strictness is enforced at classifier
     * time (Invariants §3): {@link ServiceCatalog#reflectServiceMethod} rejects methods whose
     * declared parameterized return type doesn't match the expected record class for the
     * field's {@code @table}-bound return type.
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "service-catalog-strict-service-return",
        reliesOn = "Declares the typed XRecord (Single arm) on the fetcher and lets graphql-java's "
            + "column fetchers traverse it directly. A wider service return would force Object on "
            + "the fetcher and lose static type safety. Note: the shared buildServiceFetcherCommon "
            + "helper is also reached from buildQueryServiceRecordFetcher, whose PojoResultType / "
            + "ScalarReturnType paths do not depend on this guarantee — annotating the helper "
            + "would overclaim.")
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "service-resolver-root-list-record-return-pair",
        reliesOn = "Reads MethodRef.returnType() to declare the typed local on the List arm "
            + "(either Result<XRecord> or List<XRecord>, whichever the developer wrote) without "
            + "a defensive cast or a wildcard. The resolver-side check rejects any other shape "
            + "at classify time, so the emitter can rely on the captured TypeName being one of "
            + "exactly those two.")
    private static MethodSpec buildQueryServiceTableFetcher(TypeFetcherEmissionContext ctx, QueryField.QueryServiceTableField qstf,
                                                             String outputPackage) {
        var tableRef = qstf.returnType().table();
        var recordClass = tableRef.recordClass();
        boolean isList = qstf.returnType().wrapper().isList();
        // For List cardinality, the developer's declared return type is either Result<XRecord>
        // or List<XRecord> (validated in ServiceDirectiveResolver.validateRootInvariants §3);
        // declare the local with whichever shape the developer chose so the generated
        // assignment compiles. graphql-java accepts either as a list value.
        TypeName returnType = isList ? qstf.method().returnType() : recordClass;
        return buildServiceFetcherCommon(ctx, qstf.name(), qstf.method(), qstf.parentTypeName(),
            returnType, qstf.errorChannel(), qstf.resultAssembly(), outputPackage);
    }

    /**
     * Emits the fetcher for a {@link QueryField.QueryServiceRecordField}: same body shape as
     * {@link #buildQueryServiceTableFetcher} but the declared return type covers two
     * sub-shapes:
     *
     * <ul>
     *   <li>{@code ResultReturnType} with non-null {@code fqClassName} (a Java {@code @record}
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
        return buildServiceFetcherCommon(ctx, qsrf.name(), qsrf.method(), qsrf.parentTypeName(),
            returnType, qsrf.errorChannel(), qsrf.resultAssembly(), outputPackage);
    }

    /**
     * Computes the emitter's declared return type for a {@link QueryField.QueryServiceRecordField}
     * based on the field's resolved {@link ReturnTypeRef} and (when needed) the method's actual
     * reflected return type. See {@link #buildQueryServiceRecordFetcher} for the policy.
     */
    private static TypeName computeServiceRecordReturnType(QueryField.QueryServiceRecordField qsrf) {
        boolean isList = qsrf.returnType().wrapper().isList();
        if (qsrf.returnType() instanceof ReturnTypeRef.ResultReturnType r && r.fqClassName() != null) {
            ClassName recordCls = ClassName.bestGuess(r.fqClassName());
            return isList ? ParameterizedTypeName.get(LIST, recordCls) : recordCls;
        }
        // PojoResultType (null fqClassName) or ScalarReturnType: faithfully reflect the
        // developer's declared return type. MethodRef.returnType is the structured TypeName
        // captured at reflection time, so the emitter declares the matching shape directly
        // without parsing a string.
        return qsrf.method().returnType();
    }

    /**
     * Emits the fetcher for a {@link MutationField.MutationServiceTableField}: identical body
     * shape to {@link #buildQueryServiceTableFetcher}. Root mutation fields have no parent table
     * and no parent-batching context, so the emission delegates to the shared
     * {@link #buildServiceFetcherCommon} helper without alteration. The shared helper handles
     * the pre-execution Jakarta validation pre-step, the try/catch wrapper, and the
     * {@code resultAssembly} success-arm payload assembly uniformly across query and
     * mutation services.
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "service-catalog-strict-service-return",
        reliesOn = "Inherits the Single-arm policy from buildQueryServiceTableFetcher: typed "
            + "XRecord local on the fetcher, fed by the catalog's strict TypeName.equals against "
            + "the expected record class. Mutation services share the same path through "
            + "ServiceCatalog.reflectServiceMethod.")
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "service-resolver-root-list-record-return-pair",
        reliesOn = "Inherits the List-arm policy from buildQueryServiceTableFetcher: reads "
            + "MethodRef.returnType() to declare the typed local as whichever of "
            + "Result<XRecord> / List<XRecord> the developer wrote, with the resolver-side "
            + "pair check rejecting any other shape at classify time.")
    private static MethodSpec buildMutationServiceTableFetcher(TypeFetcherEmissionContext ctx, MutationField.MutationServiceTableField mstf,
                                                                String outputPackage) {
        var tableRef = mstf.returnType().table();
        var recordClass = tableRef.recordClass();
        boolean isList = mstf.returnType().wrapper().isList();
        // See buildQueryServiceTableFetcher for the List-cardinality policy.
        TypeName returnType = isList ? mstf.method().returnType() : recordClass;
        return buildServiceFetcherCommon(ctx, mstf.name(), mstf.method(), mstf.parentTypeName(),
            returnType, mstf.errorChannel(), mstf.resultAssembly(), outputPackage);
    }

    /**
     * Emits the fetcher for a {@link MutationField.MutationServiceRecordField}: identical body
     * shape to {@link #buildQueryServiceRecordFetcher}. Both {@code ResultReturnType} (with or
     * without a {@code @record} backing class) and {@code ScalarReturnType} return shapes are
     * handled by {@link #computeMutationServiceRecordReturnType}, mirroring the query side.
     */
    private static MethodSpec buildMutationServiceRecordFetcher(TypeFetcherEmissionContext ctx, MutationField.MutationServiceRecordField msrf,
                                                                 String outputPackage) {
        TypeName returnType = computeMutationServiceRecordReturnType(msrf);
        return buildServiceFetcherCommon(ctx, msrf.name(), msrf.method(), msrf.parentTypeName(),
            returnType, msrf.errorChannel(), msrf.resultAssembly(), outputPackage);
    }

    /**
     * Mirrors {@link #computeServiceRecordReturnType} for the mutation side. Identical policy:
     * {@code ResultReturnType} with a non-null {@code fqClassName} produces a typed declaration;
     * everything else faithfully reflects the developer method's reflected return type.
     */
    private static TypeName computeMutationServiceRecordReturnType(MutationField.MutationServiceRecordField msrf) {
        boolean isList = msrf.returnType().wrapper().isList();
        if (msrf.returnType() instanceof ReturnTypeRef.ResultReturnType r && r.fqClassName() != null) {
            ClassName recordCls = ClassName.bestGuess(r.fqClassName());
            return isList ? ParameterizedTypeName.get(LIST, recordCls) : recordCls;
        }
        return msrf.method().returnType();
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
     * <p>When {@code resultAssembly} is present, the success arm assembles the payload around
     * the captured service-return local: the service method returns the domain object the
     * payload's result slot expects, and the emitter walks the constructor's slots positionally
     * (result slot &larr; service return, errors slot &larr; {@code List.of()} when a channel is
     * also present, every other slot &larr; its pre-resolved {@code defaultLiteral}). When
     * absent, the emitter falls back to the legacy passthrough shape
     * ({@code return service-value-as-payload}).
     *
     * <p>The catch arm forks on {@code errorChannel}: a present channel routes through
     * {@code ErrorRouter.dispatch} with the channel's mapping table and synthesized payload
     * factory; an absent channel routes through {@code ErrorRouter.redact}.
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "service-catalog-instance-service-holder-shape",
        reliesOn = "Emits `new ServiceClass(dsl).method(...)` for the InstanceWithDslHolder "
            + "CallShape arm without checking the holder shape at emit time. ServiceCatalog "
            + "rejects instance methods whose enclosing class is abstract / an interface or lacks "
            + "the required public single-arg DSLContext constructor, so the emitter can lean on "
            + "the existence of that constructor unconditionally.")
    private static MethodSpec buildServiceFetcherCommon(TypeFetcherEmissionContext ctx, String fieldName, MethodRef method,
                                                        String parentTypeName, TypeName valueType,
                                                        Optional<ErrorChannel> errorChannel,
                                                        Optional<no.sikt.graphitron.rewrite.model.ResultAssembly> resultAssembly,
                                                        String outputPackage) {
        var dslContextClass = ClassName.get("org.jooq", "DSLContext");
        var serviceClass = ClassName.bestGuess(method.className());
        String conditionsClassName = outputPackage + ".conditions."
            + parentTypeName + QueryConditionsGenerator.CLASS_NAME_SUFFIX;
        var service = (MethodRef.Service) method;
        boolean needsDsl = needsDsl(service.callShape());
        CodeBlock callTarget = serviceCallTarget(service, serviceClass);

        var builder = MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");

        // Pre-execution Jakarta validation. Emitted ahead of the try block so a Validator-side
        // throw still propagates to the wrapper's catch arm uniformly with the body's
        // exceptions; the body is never invoked when violations exist. Only the
        // PayloadClass arm supports the validator pre-step today: it materialises an early
        // payload class populated from the violations list, then short-circuits. The
        // LocalContext arm has no payload class to construct; if a LocalContext channel ever
        // declares a ValidationHandler the producer must wire a sibling pre-step that emits
        // {@code data(null).localContext(violations)} instead.
        if (errorChannel.isPresent()
                && errorChannel.get() instanceof ErrorChannel.PayloadClass payloadChannel
                && hasValidationHandler(payloadChannel)) {
            builder.addCode(validatorPreStep(ctx, method, fieldName, payloadChannel, valueType, outputPackage));
        }

        builder.beginControlFlow("try");
        if (needsDsl) {
            builder.addStatement("$T dsl = $L.getDslContext(env)", dslContextClass, ctx.graphitronContextCall());
        }
        if (resultAssembly.isPresent()) {
            // "Service returns the domain object" shape: capture the service return in a typed
            // local and assemble the payload around it via a positional constructor walk.
            var ra = resultAssembly.get();
            builder.addStatement("$T __row = $L.$L($L)",
                ra.resultSlotType(),
                callTarget,
                method.methodName(),
                ArgCallEmitter.buildMethodBackedCallArgs(ctx, method, null, conditionsClassName));
            builder.addCode(buildSuccessPayload(valueType, ra, errorChannel, "__row"));
        } else {
            // Legacy passthrough: service method returns the SDL payload class directly. The
            // emitter forwards the return value into the DataFetcherResult without assembly.
            builder.addStatement("$T payload = $L.$L($L)",
                valueType,
                callTarget,
                method.methodName(),
                ArgCallEmitter.buildMethodBackedCallArgs(ctx, method, null, conditionsClassName));
        }
        builder.addCode(returnSyncSuccess(valueType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(catchArm(outputPackage, errorChannel));
        builder.endControlFlow();

        return builder.build();
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
            case MethodRef.CallShape.InstanceWithDslHolder ignored -> CodeBlock.of("new $T(dsl)", serviceClass);
        };
    }

    /**
     * Decides whether the surrounding method needs a {@code DSLContext dsl} local. Single source
     * of truth for the static-vs-instance fork; both {@link #buildServiceFetcherCommon} and
     * {@link #buildServiceRowsMethod} route through here so the disjunction lives in one place.
     * The {@link MethodRef.CallShape.Static#needsDslLocal()} flag is computed once at classify
     * time inside {@code ServiceCatalog.reflectServiceMethod} (any param has
     * {@link no.sikt.graphitron.rewrite.model.ParamSource.DslContext}); the
     * {@link MethodRef.CallShape.InstanceWithDslHolder} arm always needs the local because the
     * holder ctor takes the {@code DSLContext} regardless of the method's param list.
     */
    static boolean needsDsl(MethodRef.CallShape callShape) {
        return switch (callShape) {
            case MethodRef.CallShape.Static s -> s.needsDslLocal();
            case MethodRef.CallShape.InstanceWithDslHolder ignored -> true;
        };
    }

    /**
     * Emits the success-arm payload-construction block when a {@code ResultAssembly} is present
     * on a service-backed fetcher. Dispatches on the assembly's {@link ResultSlot} arm:
     * the all-fields-ctor arm walks the constructor's slot indices {@code 0..N-1} (where
     * {@code N == 1 + ra.defaultedSlots().size()}) and prints, per slot: the row local at the
     * result-ctor-index, {@code List.of()} at the channel's errors-ctor-index when a channel
     * is also present, and the slot's pre-resolved {@code defaultLiteral} otherwise. The
     * phase-2 setter arm lands as a new {@code case} on this switch. The block declares a typed
     * {@code payload} local that the caller's {@link #returnSyncSuccess} subsequently wraps in
     * the {@link DataFetcherResult}.
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "payload-construction.shape-resolved",
        reliesOn = "switch over resultSlot is total because the classifier resolves "
            + "PayloadConstructionShape to either AllFieldsCtor or MutableBean, and each arm "
            + "produces the matching slot variant. The emitter never sees a third shape.")
    private static CodeBlock buildSuccessPayload(TypeName valueType,
                                                 no.sikt.graphitron.rewrite.model.ResultAssembly ra,
                                                 Optional<ErrorChannel> errorChannel,
                                                 String rowLocal) {
        // The errors-slot peek into the channel is PayloadClass-only: LocalContext channels
        // have no payload class to slot the errors list into. Drop straight to the
        // no-channel arm when the channel is LocalContext (none today; preemptive coverage).
        var payloadChannel = errorChannel
            .flatMap(c -> c instanceof ErrorChannel.PayloadClass pc ? Optional.of(pc) : Optional.<ErrorChannel.PayloadClass>empty());
        return switch (ra.resultSlot()) {
            case no.sikt.graphitron.rewrite.model.ResultSlot.CtorParameterIndex resultCpi ->
                buildSuccessPayloadCtor(valueType, ra, resultCpi.index(), payloadChannel, rowLocal);
            case no.sikt.graphitron.rewrite.model.ResultSlot.SetterMethod sm ->
                buildSuccessPayloadSetters(valueType, sm, payloadChannel, rowLocal);
        };
    }

    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "payload-construction.setter-name-matches-sdl-field",
        reliesOn = "calls boundSetter.getName() and nonBoundSetter.setter().getName() directly "
            + "into the generated source; the classifier guarantees each Method resolves to a "
            + "real public method on the payload class.")
    private static CodeBlock buildSuccessPayloadSetters(
            TypeName valueType,
            no.sikt.graphitron.rewrite.model.ResultSlot.SetterMethod sm,
            Optional<ErrorChannel.PayloadClass> errorChannel,
            String rowLocal) {
        var b = CodeBlock.builder().add("$T payload = new $T();\n", valueType, valueType);
        b.add("payload.$L($L);\n", sm.boundSetter().getName(), rowLocal);
        java.lang.reflect.Method errorsSetter = errorChannel
            .map(ErrorChannel.PayloadClass::errorsSlot)
            .filter(s -> s instanceof no.sikt.graphitron.rewrite.model.ErrorsSlot.SetterMethod)
            .map(s -> ((no.sikt.graphitron.rewrite.model.ErrorsSlot.SetterMethod) s).boundSetter())
            .orElse(null);
        for (var nbs : sm.nonBoundSetters()) {
            if (errorsSetter != null && nbs.setter().equals(errorsSetter)) {
                b.add("payload.$L($T.of());\n", nbs.setter().getName(), LIST);
            } else {
                b.add("payload.$L($L);\n", nbs.setter().getName(), nbs.defaultLiteral());
            }
        }
        return b.build();
    }

    private static CodeBlock buildSuccessPayloadCtor(TypeName valueType,
                                                     no.sikt.graphitron.rewrite.model.ResultAssembly ra,
                                                     int resultSlotIndex,
                                                     Optional<ErrorChannel.PayloadClass> errorChannel,
                                                     String rowLocal) {
        int slotCount = 1 + ra.defaultedSlots().size();
        var defaultsByIndex = ra.defaultedSlots().stream()
            .collect(java.util.stream.Collectors.toMap(s -> s.index(), s -> s.defaultLiteral()));
        Integer errorsCtorIndex = errorChannel
            .map(ErrorChannel.PayloadClass::errorsSlot)
            .filter(s -> s instanceof no.sikt.graphitron.rewrite.model.ErrorsSlot.CtorParameterIndex)
            .map(s -> ((no.sikt.graphitron.rewrite.model.ErrorsSlot.CtorParameterIndex) s).index())
            .orElse(null);

        var ctor = CodeBlock.builder().add("$T payload = new $T(", valueType, valueType);
        for (int i = 0; i < slotCount; i++) {
            if (i > 0) ctor.add(", ");
            if (i == resultSlotIndex) {
                ctor.add(rowLocal);
            } else if (errorsCtorIndex != null && i == errorsCtorIndex) {
                ctor.add("$T.of()", LIST);
            } else {
                ctor.add(defaultsByIndex.get(i));
            }
        }
        ctor.add(");\n");
        return ctor.build();
    }

    /** Whether any flattened handler on the channel is a {@code ValidationHandler}. */
    private static boolean hasValidationHandler(ErrorChannel.PayloadClass channel) {
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
     * <p>R94: input-typed SDL args materialise through the graphitron-emitted class's
     * {@code fromMap(Map<String,Object>)} factory before the validator walks them. The
     * fetcher boundary feeds the typed instance into
     * {@code validator.validate(<typed>)}; the empty walk produces zero violations until R98
     * attaches programmatic {@code ConstraintMapping} entries. Scalar / enum SDL args stay on
     * the raw value path. When the assembled schema is unavailable (some unit-tier tests
     * build the model only), the pre-step falls back to validating against the raw value for
     * every arg, mirroring pre-R94 behaviour.
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "input-record.shape-from-input-type",
        reliesOn = "Materialises the graphitron-emitted input class via <InputName>.fromMap(...) "
            + "without null-guarding the recordShape() return on the HasInputRecordShape "
            + "carrier; TypeBuilder.buildInputRecordShape's compact-ctor producer-side "
            + "rejection plus the UnclassifiedType routing guarantees every classified input "
            + "type carries a populated shape with a non-empty components list.")
    private static CodeBlock validatorPreStep(TypeFetcherEmissionContext ctx, MethodRef method,
                                              String fieldName,
                                              ErrorChannel.PayloadClass channel,
                                              TypeName valueType, String outputPackage) {
        var validator = ClassName.get("jakarta.validation", "Validator");
        var constraintViolation = ClassName.get("jakarta.validation", "ConstraintViolation");
        var graphQLError = ClassName.get("graphql", "GraphQLError");
        var listOfErrors = ParameterizedTypeName.get(LIST, graphQLError);
        var arrayList = ClassName.get("java.util", "ArrayList");
        var constraintViolations = ClassName.get(outputPackage + ".schema",
            ConstraintViolationsClassGenerator.CLASS_NAME);
        var violationWildcard = ParameterizedTypeName.get(constraintViolation,
            WildcardTypeName.subtypeOf(Object.class));
        var mapStringObject = ParameterizedTypeName.get(
            ClassName.get(Map.class), ClassName.get(String.class), ClassName.get(Object.class));

        var b = CodeBlock.builder();
        b.addStatement("$T __validator = $L.getValidator(env)", validator, ctx.graphitronContextCall());
        b.addStatement("$T __violations = new $T<>()", listOfErrors, arrayList);
        for (var p : method.params()) {
            if (!(p.source() instanceof ParamSource.Arg arg)) continue;
            String argName = arg.graphqlArgName();
            String local = "__arg_" + sanitizeIdent(argName);
            ClassName inputClass = resolveInputArgClass(ctx, fieldName, argName, outputPackage);
            if (inputClass != null) {
                // Input-typed SDL arg: materialise the graphitron-emitted class via fromMap
                // and walk the typed instance. The local is the validator's target (typed),
                // not the raw Map. The class goes out of scope after the pre-step; downstream
                // value reads route through R150's bean path or the existing Map.get pattern.
                b.addStatement("$T $L_raw = ($T) env.getArgument($S)",
                    mapStringObject, local, mapStringObject, argName);
                b.addStatement("$T $L = $L_raw == null ? null : $T.fromMap($L_raw)",
                    inputClass, local, local, inputClass, local);
            } else {
                b.addStatement("$T $L = env.getArgument($S)", Object.class, local, argName);
            }
            b.beginControlFlow("if ($L != null)", local);
            b.beginControlFlow("for ($T __v : __validator.validate($L))", violationWildcard, local);
            b.addStatement("__violations.add($T.toGraphQLError(__v, env, $S))",
                constraintViolations, argName);
            b.endControlFlow();
            b.endControlFlow();
        }
        b.beginControlFlow("if (!__violations.isEmpty())");
        b.add(declareEarlyPayloadFromErrors(channel, "__violations"));
        b.add("return $T.<$T>newResult()\n", DATA_FETCHER_RESULT, boxed(valueType));
        b.add("    .data(__earlyPayload)\n");
        b.addStatement("    .build()");
        b.endControlFlow();
        return b.build();
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
     * Emits the statements that declare a local {@code __earlyPayload} populated from the
     * validation-violations list. Used by the validator pre-step where the caller subsequently
     * references {@code __earlyPayload} inside {@code .data(...)}. Dispatches on the channel's
     * {@link ErrorsSlot} arm: the ctor arm emits a single
     * {@code <Payload> __earlyPayload = new <Payload>(...)} statement; the setter arm emits a
     * sequence of {@code __earlyPayload.setX(...)} statements after a no-arg construction.
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "payload-construction.shape-resolved",
        reliesOn = "switch over errorsSlot is total because the classifier resolves "
            + "PayloadConstructionShape to either AllFieldsCtor (CtorParameterIndex) or "
            + "MutableBean (SetterMethod). No third arm.")
    private static CodeBlock declareEarlyPayloadFromErrors(ErrorChannel.PayloadClass channel, String errorsLocal) {
        return switch (channel.errorsSlot()) {
            case no.sikt.graphitron.rewrite.model.ErrorsSlot.CtorParameterIndex cpi ->
                declareEarlyPayloadCtor(channel, cpi.index(), errorsLocal);
            case no.sikt.graphitron.rewrite.model.ErrorsSlot.SetterMethod sm ->
                declareEarlyPayloadSetters(channel, sm, errorsLocal);
        };
    }

    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "payload-construction.setter-name-matches-sdl-field",
        reliesOn = "prints setter names directly into the generated validator pre-step; the "
            + "classifier guarantees each name resolves to a real method on the payload class.")
    private static CodeBlock declareEarlyPayloadSetters(
            ErrorChannel.PayloadClass channel,
            no.sikt.graphitron.rewrite.model.ErrorsSlot.SetterMethod sm,
            String errorsLocal) {
        var b = CodeBlock.builder();
        b.add("$T __earlyPayload = new $T();\n", channel.payloadClass(), channel.payloadClass());
        b.add("__earlyPayload.$L($L);\n", sm.boundSetter().getName(), errorsLocal);
        for (var nbs : sm.nonBoundSetters()) {
            b.add("__earlyPayload.$L($L);\n", nbs.setter().getName(), nbs.defaultLiteral());
        }
        return b.build();
    }

    private static CodeBlock declareEarlyPayloadCtor(ErrorChannel.PayloadClass channel, int errorsCtorIndex,
                                                      String errorsLocal) {
        return CodeBlock.builder()
            .add("$T __earlyPayload = ", channel.payloadClass())
            .add(newPayloadFromErrorsCtor(channel, errorsCtorIndex, errorsLocal))
            .add(";\n")
            .build();
    }

    private static CodeBlock newPayloadFromErrorsCtor(ErrorChannel.PayloadClass channel, int errorsCtorIndex,
                                                      String errorsLocal) {
        var args = CodeBlock.builder();
        int slotCount = 1 + channel.defaultedSlots().size();
        var defaultsByIndex = channel.defaultedSlots().stream()
            .collect(java.util.stream.Collectors.toMap(s -> s.index(), s -> s.defaultLiteral()));
        for (int i = 0; i < slotCount; i++) {
            if (i > 0) args.add(", ");
            if (i == errorsCtorIndex) {
                args.add(errorsLocal);
            } else {
                args.add(defaultsByIndex.get(i));
            }
        }
        return CodeBlock.of("new $T($L)", channel.payloadClass(), args.build());
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
     * Emits a fetcher for {@link MutationField.MutationDeleteTableField}: a synchronous static
     * method that runs {@code dsl.deleteFrom(table).where(<lookupKey predicates>)
     * .returningResult(<keys or $fields>).fetchOne(...)}.
     *
     * <p>Empty-match semantics: {@code .fetchOne(...)} returns {@code null} when the WHERE clause
     * matches no row. graphql-java surfaces that as a GraphQL null on a nullable field, or a
     * non-null violation on {@code ID!}/{@code Type!}.
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "dml-mutation-shape-guarantees",
        reliesOn = "Pattern-matches f.returnExpression() with no instanceof / "
            + "Optional.orElseThrow / payloadAssembly().isPresent() guard; casts "
            + "env.getArgument(tia.name()) to Map<?,?> when tia.list() == false, "
            + "List<Map<?,?>> when tia.list() == true (Invariant #15 then guarantees the return "
            + "shape is list-cardinality, so the projection terminator binds the matching "
            + "*List arm); walks tia.fieldBindings() without an extraction-arm dispatch.")
    private static MethodSpec buildMutationDeleteFetcher(TypeFetcherEmissionContext ctx, MutationField.MutationDeleteTableField f,
                                                          String outputPackage) {
        var tia = f.tableInputArg();
        var tableRef = tia.inputTable();
        var tablesOnly = GeneratorUtils.ResolvedTableNames.ofTable(tableRef);
        String tableLocal = tablesOnly.tableLocalName();

        var dmlChain = CodeBlock.builder().add(".deleteFrom($L)\n", tableLocal);
        var postInGuard = CodeBlock.builder();
        if (tia.list()) {
            dmlChain.add(".where(").add(buildBulkLookupRowIn(tia, tablesOnly, tableRef)).add(")\n");
        } else {
            var chunk = buildLookupWhereSingleRow(tia, tablesOnly, tableRef);
            postInGuard.add(chunk.decodeLocals());
            dmlChain.add(".where(").add(chunk.whereExpr()).add(")\n");
        }

        return buildDmlFetcher(ctx, f.name(), f.returnExpression(), f.errorChannel(),
            tia.name(), tableRef, tablesOnly, tableLocal,
            outputPackage, dmlChain.build(),
            /*postDslGuard=*/ CodeBlock.of(""), postInGuard.build(), tia.list());
    }

    /**
     * Emits a fetcher for {@link MutationField.MutationInsertTableField}: a synchronous static
     * method that runs {@code dsl.insertInto(table, cols...).values(vals...)
     * .returningResult(<keys or $fields>).fetchOne(...)}.
     *
     * <p>Column list is every {@code InputField.ColumnField} in {@code tia.fields()} in
     * declaration order; values list is parallel, with each value bound via
     * {@code DSL.val(in.get("name"), Tables.T.COL.getDataType())} (the two-argument form
     * delegates coercion to the column's registered jOOQ {@code Converter}). {@code @lookupKey}
     * fields are included verbatim — INSERT does not treat them specially. The classifier
     * guarantees that every input field is a {@code Direct}-extracted {@code ColumnField},
     * which lets the loop walk {@code tia.fields()} with a single cast.
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "dml-mutation-shape-guarantees",
        reliesOn = "Pattern-matches f.returnExpression() with no instanceof / "
            + "Optional.orElseThrow / payloadAssembly().isPresent() guard; casts "
            + "env.getArgument(tia.name()) to Map<?,?> when tia.list() == false, "
            + "List<Map<?,?>> when tia.list() == true; walks tia.fields() as Direct-extracted "
            + "ColumnField with a single cast (no extraction-arm dispatch). Per-cell "
            + "missing-vs-null dispatch on map.containsKey(\"col\") emits "
            + "DSL.defaultValue(dataType) when absent and DSL.val(value, dataType) when present, "
            + "letting omitted columns take the DB-side default while explicit nulls bind typed null.")
    private static MethodSpec buildMutationInsertFetcher(TypeFetcherEmissionContext ctx, MutationField.MutationInsertTableField f,
                                                          String outputPackage) {
        var tia = f.tableInputArg();
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
                    .add(buildInsertDecodeLocals(fields, "row", "__insertKey"))
                    .add("return $T.row(\n", DSL).indent()
                    .add(buildPerCellValueList(fields, tablesOnly, tableRef, "row", "__insertKey")).unindent()
                    .add(");\n").unindent()
                    .add("})\n")
                    .add(".toList())\n").unindent();
            } else {
                dmlChain.add(".valuesOfRows(in.stream()\n").indent()
                    .add(".map(row -> $T.row(\n", DSL).indent()
                    .add(buildPerCellValueList(fields, tablesOnly, tableRef, "row", "__insertKey")).unindent()
                    .add("))\n")
                    .add(".toList())\n").unindent();
            }
        } else {
            postInGuard.add(buildInsertDecodeLocals(fields, "in", "__insertKey"));
            dmlChain.add(".values(\n").indent()
                .add(buildPerCellValueList(fields, tablesOnly, tableRef, "in", "__insertKey")).unindent()
                .add(")\n");
        }

        return buildDmlFetcher(ctx, f.name(), f.returnExpression(), f.errorChannel(),
            tia.name(), tableRef, tablesOnly, tableLocal,
            outputPackage, dmlChain.build(),
            /*postDslGuard=*/ CodeBlock.of(""), postInGuard.build(), tia.list());
    }

    /**
     * True iff any field on {@code fields} bears a {@link CallSiteExtraction.NodeIdDecodeKeys}
     * carrier (a {@link InputField.ColumnField} with NodeId extraction, or a
     * {@link InputField.CompositeColumnField}). Drives the bulk-INSERT / bulk-UPSERT lambda
     * shape choice (single-expression vs block-with-decode-locals).
     */
    private static boolean anyNodeIdCarrier(List<InputField> fields) {
        for (var f : fields) {
            if (f instanceof InputField.ColumnField cf
                && cf.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys) return true;
            if (f instanceof InputField.CompositeColumnField) return true;
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
     * <p>Dispatches on carrier identity (R130):
     * <ul>
     *   <li>{@link InputField.ColumnField} with {@link CallSiteExtraction.Direct} (or non-NodeId
     *       extraction) — one cell, value read directly from {@code mapLocal.get(name)}.</li>
     *   <li>{@link InputField.ColumnField} with {@link CallSiteExtraction.NodeIdDecodeKeys} —
     *       one cell, value read from the per-record decode local
     *       ({@code __insertKey_<fi>.value1()}). Caller must declare the decode local; see
     *       {@link #buildInsertDecodeLocals}.</li>
     *   <li>{@link InputField.CompositeColumnField} — N cells, values read from
     *       {@code __insertKey_<fi>.value1()..value<N>()}.</li>
     * </ul>
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "mutation-input.lookup-binding-decoded-record-arity-matches-carrier-columns",
        reliesOn = "Iterates CompositeColumnField.columns() positionally and reads "
            + "decodeLocal.value<i+1>() for each column slot; the load-bearing classifier "
            + "guarantee that the carrier's columns.size() equals the decoded record's arity "
            + "lets the emitter wire jOOQ's typed value1()..valueN() accessors per slot.")
    private static CodeBlock buildPerCellValueList(
            List<InputField> fields,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef,
            String mapLocal,
            String localPrefix) {
        var b = CodeBlock.builder();
        boolean first = true;
        for (int fi = 0; fi < fields.size(); fi++) {
            var f = fields.get(fi);
            switch (f) {
                case InputField.ColumnField cf -> {
                    if (!first) b.add(",\n");
                    first = false;
                    if (cf.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys) {
                        String recLocal = localPrefix + "_" + fi;
                        b.add("$L.containsKey($S) ? $T.val($L.value1(), $T.$L.$L.getDataType()) : $T.defaultValue($T.$L.$L.getDataType())",
                            mapLocal, cf.name(),
                            DSL, recLocal,
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName(),
                            DSL,
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName());
                    } else {
                        b.add("$L.containsKey($S) ? $T.val($L.get($S), $T.$L.$L.getDataType()) : $T.defaultValue($T.$L.$L.getDataType())",
                            mapLocal, cf.name(),
                            DSL, mapLocal, cf.name(),
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName(),
                            DSL,
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName());
                    }
                }
                case InputField.CompositeColumnField ccf -> {
                    String recLocal = localPrefix + "_" + fi;
                    for (int ci = 0; ci < ccf.columns().size(); ci++) {
                        var col = ccf.columns().get(ci);
                        if (!first) b.add(",\n");
                        first = false;
                        b.add("$L.containsKey($S) ? $T.val($L.value$L(), $T.$L.$L.getDataType()) : $T.defaultValue($T.$L.$L.getDataType())",
                            mapLocal, ccf.name(),
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
     * {@link InputField.ColumnField} contributes one column ref; {@link InputField.CompositeColumnField}
     * contributes N column refs (one per {@code columns()} slot, in declaration order).
     */
    private static CodeBlock buildInsertColumnList(
            List<InputField> fields,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef) {
        var b = CodeBlock.builder();
        boolean first = true;
        for (var f : fields) {
            switch (f) {
                case InputField.ColumnField cf -> {
                    if (!first) b.add(", ");
                    first = false;
                    b.add("$T.$L.$L",
                        tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName());
                }
                case InputField.CompositeColumnField ccf -> {
                    for (var col : ccf.columns()) {
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

    /**
     * Builds the per-record NodeId decode locals for an INSERT/UPSERT INSERT-arm. For each
     * NodeId-bearing carrier ({@link InputField.ColumnField} with
     * {@link CallSiteExtraction.NodeIdDecodeKeys}, {@link InputField.CompositeColumnField}),
     * emits one {@code Record<N> __insertKey_<fi> = ...} local reading from {@code mapLocal}.
     * Locals are conditional on the source key's presence so an absent key (DEFAULT-resolved
     * cell) does not force a decode; null returns on a present key throw
     * {@code GraphqlErrorException}, mirroring the lookup-WHERE null handling.
     */
    private static CodeBlock buildInsertDecodeLocals(
            List<InputField> fields,
            String mapLocal,
            String localPrefix) {
        var locals = CodeBlock.builder();
        ClassName graphqlErr = ClassName.get("graphql", "GraphqlErrorException");
        for (int fi = 0; fi < fields.size(); fi++) {
            var f = fields.get(fi);
            CallSiteExtraction.NodeIdDecodeKeys nidk = switch (f) {
                case InputField.ColumnField cf when cf.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys n -> n;
                case InputField.CompositeColumnField ccf -> ccf.extraction();
                default -> null;
            };
            if (nidk == null) continue;
            String sourceField = f.name();
            String recLocal = localPrefix + "_" + fi;
            ClassName encoderClass = nidk.decodeMethod().encoderClass();
            String methodName = nidk.decodeMethod().methodName();
            TypeName recordType = nidk.decodeMethod().returnType();
            locals.addStatement("$T $L = ($L.get($S) instanceof $T _s$L) ? $T.$L(_s$L) : null",
                recordType, recLocal, mapLocal, sourceField, String.class, recLocal, encoderClass, methodName, recLocal);
            locals.beginControlFlow("if ($L.containsKey($S) && $L == null)", mapLocal, sourceField, recLocal)
                .addStatement("throw $T.newErrorException().message($S).build()", graphqlErr,
                    "Decoded NodeId did not match the expected type for input field '" + sourceField + "'")
                .endControlFlow();
        }
        return locals.build();
    }

    /**
     * Emits a fetcher for {@link MutationField.MutationUpdateTableField}: a synchronous static
     * method that runs {@code dsl.update(table).set(col, val)... .where(<lookupKey predicates>)
     * .returningResult(<keys or $fields>).fetchOne(...)}.
     *
     * <p>SET clause is {@code tia.setFields()} (the typed non-{@code @lookupKey}
     * {@code ColumnField} projection on {@code TableInputArg}). Invariant #4 guarantees this
     * projection is non-empty. WHERE clause is the {@code @lookupKey} fieldBindings, chained
     * with {@code .and(...)} via the shared {@link #buildLookupWhere} helper. Empty-match
     * semantics: {@code .fetchOne(...)} returns {@code null} when the WHERE clause matches no
     * row, same as DELETE.
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "dml-mutation-shape-guarantees",
        reliesOn = "Pattern-matches f.returnExpression() with no instanceof / "
            + "Optional.orElseThrow / payloadAssembly().isPresent() guard; casts "
            + "env.getArgument(tia.name()) to Map<?,?> when tia.list() == false, "
            + "List<Map<?,?>> when tia.list() == true; walks tia.setFields() as the typed "
            + "non-@lookupKey ColumnField projection (no cast, no skip-during-walk). "
            + "Invariant #4 guarantees setFields() is non-empty as the codegen-time projection. "
            + "The SET clause is built at runtime from a presentKeys.contains(name) walk over "
            + "tia.setFields() (single-row reads in.keySet(); bulk reads firstKeys captured from "
            + "in.get(0).keySet() after the uniform-shape guard makes it a stable witness for "
            + "every row). The no-set-fields-present runtime check rejects when every "
            + "setField key is absent from presentKeys. The bulk arm's duplicate-lookup-key "
            + "guard (HashSet<List<Object>> over per-row tuples) rejects same-key inputs before "
            + "the chain executes; otherwise Postgres' implementation-defined join would "
            + "silently drop one row's data.")
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "mutation-input.update-set-fields-equal-value-marked",
        reliesOn = "Single-row UPDATE: walks tia.setFields() for the SET-map construction without "
            + "checking @value directive presence or DmlKind; R144's classifier guarantee that "
            + "setFields() is exactly the @value-marked admissible carriers on UPDATE.")
    private static MethodSpec buildMutationUpdateFetcher(TypeFetcherEmissionContext ctx, MutationField.MutationUpdateTableField f,
                                                          String outputPackage) {
        var tia = f.tableInputArg();
        var tableRef = tia.inputTable();
        var tablesOnly = GeneratorUtils.ResolvedTableNames.ofTable(tableRef);
        String tableLocal = tablesOnly.tableLocalName();

        if (tia.list()) {
            return buildBulkUpdateFetcher(ctx, f, outputPackage, tia, tableRef, tablesOnly, tableLocal);
        }

        // Single-row UPDATE: build the SET clause dynamically from the present-key set so absent
        // fields drop out (PATCH semantics) and explicit-null fields bind typed null. The map
        // is consumed by jOOQ's `.set(Map<? extends Field<?>, ?>)` overload, which preserves
        // the chain shape (`UpdateSetMoreStep<R>` → `.where(...).returningResult(...)`).
        var fieldClass = ClassName.get("org.jooq", "Field");
        var linkedHashMap = ClassName.get("java.util", "LinkedHashMap");
        var postInGuard = CodeBlock.builder();
        postInGuard.addStatement("$T<$T<?>, Object> sets = new $T<>()", MAP, fieldClass, linkedHashMap);
        for (var sf : tia.setFields()) {
            var cf = (InputField.ColumnField) sf;
            postInGuard.beginControlFlow("if (in.containsKey($S))", cf.name())
                .addStatement("sets.put($T.$L.$L, $T.val(in.get($S), $T.$L.$L.getDataType()))",
                    tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName(),
                    DSL, cf.name(),
                    tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName())
                .endControlFlow();
        }
        postInGuard.beginControlFlow("if (sets.isEmpty())")
            .addStatement("throw new $T($S)", IllegalArgumentException.class,
                "@mutation(typeName: UPDATE) call has no settable fields present; "
                    + "only @lookupKey fields were provided")
            .endControlFlow();

        var whereChunk = buildLookupWhereSingleRow(tia, tablesOnly, tableRef);
        postInGuard.add(whereChunk.decodeLocals());
        var dmlChain = CodeBlock.builder()
            .add(".update($L)\n", tableLocal)
            .add(".set(sets)\n")
            .add(".where(").add(whereChunk.whereExpr()).add(")\n")
            .build();

        return buildDmlFetcher(ctx, f.name(), f.returnExpression(), f.errorChannel(),
            tia.name(), tableRef, tablesOnly, tableLocal,
            outputPackage, dmlChain, /*postDslGuard=*/ CodeBlock.of(""), postInGuard.build(), tia.list());
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
     * A separate {@code postDslGuard} rejects non-Postgres dialects: only Postgres speaks the
     * {@code UPDATE … FROM (VALUES …)} form jOOQ renders here. R63 lifts both this guard and
     * UPSERT's existing Oracle-dialect guard onto typed {@code DialectRequirement} later.
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "mutation-input.update-set-fields-equal-value-marked",
        reliesOn = "Walks tia.setFields() for the bulk UPDATE's v-table column list and per-row "
            + "cell construction without checking @value directive presence or DmlKind; R144's "
            + "classifier guarantee that setFields() is exactly the @value-marked admissible "
            + "carriers (in SDL declaration order) on UPDATE.")
    private static MethodSpec buildBulkUpdateFetcher(TypeFetcherEmissionContext ctx,
                                                     MutationField.MutationUpdateTableField f,
                                                     String outputPackage,
                                                     no.sikt.graphitron.rewrite.ArgumentRef.InputTypeArg.TableInputArg tia,
                                                     TableRef tableRef,
                                                     GeneratorUtils.ResolvedTableNames tablesOnly,
                                                     String tableLocal) {
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
        var groups = tia.fieldBindings();
        // Flatten lookup-key target columns across groups for the join-on-column-names construction;
        // every column appears once at slot index i in vColNames / cells / WHERE.
        var lookupTargetColumns = new ArrayList<no.sikt.graphitron.rewrite.model.ColumnRef>();
        for (var g : groups) lookupTargetColumns.addAll(g.targetColumns());

        var postInGuard = CodeBlock.builder();
        postInGuard.addStatement("$T<?> firstKeys = in.get(0).keySet()", SET);
        postInGuard.add(buildUniformShapeGuard("UPDATE"));

        // Build v-table column-name list: lookup-key columns (unconditional) + set-field columns
        // present in firstKeys, in declaration order. Strings come from each Field's getName()
        // so jOOQ's typed v.field(Field<T>) overload returns the correctly typed v-column.
        postInGuard.addStatement("$T<String> vColNames = new $T<>()",
            ClassName.get(List.class), arrayList);
        for (var col : lookupTargetColumns) {
            postInGuard.addStatement("vColNames.add($T.$L.$L.getName())",
                tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
        }
        for (var sf : tia.setFields()) {
            var cf = (InputField.ColumnField) sf;
            postInGuard.beginControlFlow("if (firstKeys.contains($S))", cf.name())
                .addStatement("vColNames.add($T.$L.$L.getName())",
                    tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName())
                .endControlFlow();
        }

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
        for (var sf : tia.setFields()) {
            var cf = (InputField.ColumnField) sf;
            postInGuard.beginControlFlow("if (firstKeys.contains($S))", cf.name())
                .addStatement("cells.add($T.val(row.get($S), $T.$L.$L.getDataType()))",
                    DSL, cf.name(),
                    tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName())
                .endControlFlow();
        }
        postInGuard.addStatement("vRows.add($T.row(cells.toArray(new $T<?>[0])))", DSL, fieldClass);
        postInGuard.endControlFlow();
        postInGuard.addStatement("$T<?> v = $T.values(vRows.toArray(new $T[0])).as($S, vColNames.toArray(new String[0]))",
            tableClass, DSL, rowClass, "v");

        // SET map: same firstKeys-conditional walk over setFields, producing
        // { t.col -> v.field(t.col) } entries. The typed Table.field(Field<T>) overload returns
        // the matching v-column with the target column's type, so no cast is needed at the
        // .set(Map<? extends Field<?>, ?>) call site.
        postInGuard.addStatement("$T<$T<?>, Object> sets = new $T<>()", MAP, fieldClass, linkedHashMap);
        for (var sf : tia.setFields()) {
            var cf = (InputField.ColumnField) sf;
            postInGuard.beginControlFlow("if (firstKeys.contains($S))", cf.name())
                .addStatement("sets.put($T.$L.$L, v.field($T.$L.$L))",
                    tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName(),
                    tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName())
                .endControlFlow();
        }
        postInGuard.beginControlFlow("if (sets.isEmpty())")
            .addStatement("throw new $T($S)", IllegalArgumentException.class,
                "@mutation(typeName: UPDATE) bulk call has no settable fields present in the input rows; "
                    + "only @lookupKey fields were provided")
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
                        lookupKeyTuple.add("row.get($S)", binding.fieldName());
                    }
                }
                case InputColumnBindingGroup.DecodedRecordGroup drg -> {
                    if (!firstTupleSlot) lookupKeyTuple.add(", ");
                    firstTupleSlot = false;
                    lookupKeyTuple.add("row.get($S)", drg.sourceFieldName());
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

        // Postgres-only dialect guard: UPDATE ... FROM (VALUES ...) is a Postgres extension.
        // family() collapses POSTGRES, POSTGRESPLUS, YUGABYTEDB into POSTGRES.
        var postDslGuard = CodeBlock.builder()
            .beginControlFlow("if (!$S.equals(dsl.dialect().family().name()))", "POSTGRES")
            .addStatement("throw new $T($S)", UnsupportedOperationException.class,
                "@mutation(typeName: UPDATE) with a listed @table input requires PostgreSQL; "
                    + "the UPDATE ... FROM (VALUES ...) form is a Postgres extension. "
                    + "Use a single-row input for portability.")
            .endControlFlow()
            .build();

        return buildDmlFetcher(ctx, f.name(), f.returnExpression(), f.errorChannel(),
            tia.name(), tableRef, tablesOnly, tableLocal,
            outputPackage, dmlChain, postDslGuard, postInGuard.build(), tia.list());
    }

    /**
     * Emits a fetcher for {@link MutationField.MutationUpsertTableField}: a synchronous static
     * method that runs {@code dsl.insertInto(table, cols...).values(vals...).onConflict(<keys>)
     * .doUpdate().set(col, val)... .returningResult(<keys or $fields>).fetchOne(...)}.
     *
     * <p>Column/values lists are identical to INSERT (every {@code InputField.ColumnField} in
     * declaration order, {@code @lookupKey} fields included so the user-supplied PK lands on the
     * insert branch). Conflict keys come from {@code tia.fieldBindings()}. Conflict action: when
     * {@code tia.setFields()} is non-empty, emit {@code .doUpdate().set(...)} over those fields;
     * otherwise emit {@code .doNothing()} (jOOQ rejects {@code .doUpdate()} with an empty SET).
     *
     * <p>PostgreSQL-only: {@code ON CONFLICT} is a Postgres extension.
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "dml-mutation-shape-guarantees",
        reliesOn = "Pattern-matches f.returnExpression() with no instanceof / "
            + "Optional.orElseThrow / payloadAssembly().isPresent() guard; casts "
            + "env.getArgument(tia.name()) to Map<?,?> when tia.list() == false, "
            + "List<Map<?,?>> when tia.list() == true; walks tia.fields() as Direct-extracted "
            + "ColumnField with a single cast for the col list and per-row VALUES cells, "
            + "and tia.setFields() (typed non-@lookupKey ColumnField projection) for the "
            + "DO UPDATE SET clause and the .doUpdate()/.doNothing() dispatch. Invariant #3 "
            + "guarantees fieldBindings is non-empty (the ON CONFLICT key). Per-cell "
            + "missing-vs-null dispatch on map.containsKey(\"col\") emits "
            + "DSL.defaultValue(dataType) when absent and DSL.val(value, dataType) when present "
            + "on insert-side cells. When tia.setFields() is non-empty (.doUpdate() mode), "
            + "the DO UPDATE SET map is built from a presentKeys.contains(name) walk so an "
            + "omitted column drops out of SET (PATCH semantics on the conflict branch); "
            + "single-row reads in.keySet(), bulk reads firstKeys captured from in.get(0) "
            + "after the uniform-shape guard. The no-set-fields-present check rejects when "
            + "every setField key is absent from presentKeys. .doNothing() mode skips both "
            + "walks. Duplicate-key detection is delegated to PostgreSQL: the engine "
            + "hard-errors on duplicate ON CONFLICT keys (\"command cannot affect row a "
            + "second time\"), so client-side detection would only duplicate the engine's check.")
    private static MethodSpec buildMutationUpsertFetcher(TypeFetcherEmissionContext ctx, MutationField.MutationUpsertTableField f,
                                                          String outputPackage) {
        var tia = f.tableInputArg();
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
            for (var sf : tia.setFields()) {
                var cf = (InputField.ColumnField) sf;
                postInGuard.beginControlFlow("if ($L.contains($S))", presentKeysLocal, cf.name())
                    .addStatement("setsUpdate.put($T.$L.$L, $T.excluded($T.$L.$L))",
                        tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName(),
                        DSL,
                        tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName())
                    .endControlFlow();
            }
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
                    .add(buildInsertDecodeLocals(fields, "row", "__insertKey"))
                    .add("return $T.row(\n", DSL).indent()
                    .add(buildPerCellValueList(fields, tablesOnly, tableRef, "row", "__insertKey")).unindent()
                    .add(");\n").unindent()
                    .add("})\n")
                    .add(".toList())\n").unindent();
            } else {
                dmlChain.add(".valuesOfRows(in.stream()\n").indent()
                    .add(".map(row -> $T.row(\n", DSL).indent()
                    .add(buildPerCellValueList(fields, tablesOnly, tableRef, "row", "__insertKey")).unindent()
                    .add("))\n")
                    .add(".toList())\n").unindent();
            }
        } else {
            // Single-row decode locals lift into postInGuard. The if-not-empty block above
            // already wrote setsUpdate-side guards; appending the decode locals here keeps the
            // statement order (uniform-shape guard → setsUpdate construction → decode locals).
            postInGuard.add(buildInsertDecodeLocals(fields, "in", "__insertKey"));
            dmlChain.add(".values(\n").indent()
                .add(buildPerCellValueList(fields, tablesOnly, tableRef, "in", "__insertKey")).unindent()
                .add(")\n");
        }
        dmlChain.add(".onConflict(").add(conflictCols.build()).add(")\n");
        if (!tia.setFields().isEmpty()) {
            dmlChain.add(".doUpdate()\n").add(".set(setsUpdate)\n");
        } else {
            dmlChain.add(".doNothing()\n");
        }

        // jOOQ silently translates `.onConflict(...).doUpdate()` (and `.doNothing()`) to an
        // Oracle `MERGE INTO ... WHEN MATCHED THEN UPDATE ... WHEN NOT MATCHED THEN INSERT`
        // statement. Concurrency, conflict-key matching, and `RETURNING` semantics differ from
        // PostgreSQL `ON CONFLICT`; jOOQ exposes no setting to disable the emulation. Reject at
        // runtime on the Oracle dialect family rather than letting the silent translation ship.
        // Name-prefix check (rather than `SQLDialect.ORACLE`) because the OSS jOOQ distribution
        // omits commercial-only dialect enum values; the prefix covers ORACLE, ORACLE11G,
        // ORACLE12C, ORACLE18C, ORACLE19C, ORACLE21C, ORACLE23AI.
        var postDslGuard = CodeBlock.builder()
            .beginControlFlow("if (dsl.dialect().name().startsWith($S))", "ORACLE")
            .addStatement("throw new $T($S)", UnsupportedOperationException.class,
                "@mutation(typeName: UPSERT) is not supported on Oracle: jOOQ would translate "
                    + "INSERT ... ON CONFLICT to MERGE INTO, whose concurrency and RETURNING "
                    + "semantics differ from PostgreSQL. Graphitron targets PostgreSQL.")
            .endControlFlow()
            .build();

        return buildDmlFetcher(ctx, f.name(), f.returnExpression(), f.errorChannel(),
            tia.name(), tableRef, tablesOnly, tableLocal,
            outputPackage, dmlChain.build(), postDslGuard, postInGuard.build(), tia.list());
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
     * Single-row lookup-WHERE chunk: a CodeBlock to drop into {@code postInGuard} declaring any
     * per-NodeId decode locals (for {@link InputColumnBindingGroup.DecodedRecordGroup} and for
     * arity-1 NodeId-decoded {@link InputColumnBinding.MapBinding}), plus the WHERE expression
     * that reads the typed slot values out of those locals.
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "mutation-input.lookup-binding-decoded-record-arity-matches-carrier-columns",
        reliesOn = "Iterates DecodedRecordGroup.bindings() positionally and reads "
            + "decodeLocal.value<i+1>() for each binding's slot; the load-bearing classifier "
            + "guarantee that bindings.size() equals the carrier's columns.size() lets this "
            + "emit jOOQ's typed value1()..valueN() accessors without re-checking arity.")
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "mutation-input.lookup-binding-honors-carrier-extraction",
        reliesOn = "Reads each MapBinding.extraction() and emits a decode<TypeName> call for "
            + "NodeIdDecodeKeys, raw read otherwise. The classifier guarantee that the binding's "
            + "extraction matches the carrier's wire-format intent lets the emitter pick the "
            + "right form without consulting the SDL or re-deriving from raw column metadata.")
    private record LookupWhereChunk(CodeBlock decodeLocals, CodeBlock whereExpr) {}

    /**
     * Builds the single-row lookup-WHERE chunk: decode locals lifted to {@code postInGuard}, plus
     * the WHERE expression chained with {@code .and(...)} per slot. Shared by DELETE and UPDATE.
     *
     * <ul>
     *   <li>{@link InputColumnBindingGroup.MapGroup} — per binding, emits
     *       {@code t.col.eq(DSL.val(in.get(name), t.col.getDataType()))}. When the binding's
     *       extraction is {@link CallSiteExtraction.NodeIdDecodeKeys}, the value source becomes
     *       {@code __lookupKey<i>.value1()} (the per-row decode local, declared above) and the
     *       wrapping {@code DSL.val} keeps the typed column-data-type binding.</li>
     *   <li>{@link InputColumnBindingGroup.DecodedRecordGroup} — emits one decode local
     *       (with {@code ThrowOnMismatch} / {@code SkipMismatchedElement} null handling) above,
     *       and N {@code t.col_k.eq(__lookupKey<i>.value<k+1>())} chained equalities into the
     *       WHERE expression.</li>
     * </ul>
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "mutation-input.where-columns-cover-pk",
        reliesOn = "Emits the WHERE predicate as the AND of every contributed filter column "
            + "without inspecting whether the union of columns is unique against the table. "
            + "R144's PK-coverage classifier guarantee lets the emitter treat the WHERE clause "
            + "as matching at most one row per input row when `multiRow: true` is absent. With "
            + "`multiRow: true` the guarantee is intentionally not enforced and the broadcast "
            + "is the documented opt-out semantics.")
    private static LookupWhereChunk buildLookupWhereSingleRow(
            no.sikt.graphitron.rewrite.ArgumentRef.InputTypeArg.TableInputArg tia,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef) {
        return buildLookupWhereSingleRow(tia, tablesOnly, tableRef, "in");
    }

    /**
     * R141 extension: same lookup-WHERE construction as the no-arg overload but reading from a
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
        var locals = CodeBlock.builder();
        var whereExpr = CodeBlock.builder();
        var groups = tia.fieldBindings();
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
                    String recLocal = "__lookupKey" + gi;
                    appendDecodeLocal(locals, recLocal, drg.extraction(), mapLocal, drg.sourceFieldName());
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
     * Emits a value expression for one {@link InputColumnBinding.MapBinding} reading
     * {@code mapLocal.get(fieldName)}. For {@link CallSiteExtraction.NodeIdDecodeKeys} extractions,
     * lifts the per-binding decode call to a local (declared into {@code locals}) and emits
     * {@code DSL.val(decoded.value1(), t.col.getDataType())}. For all other extractions, emits
     * {@code DSL.val(mapLocal.get(name), t.col.getDataType())} verbatim.
     */
    private static void appendMapBindingValueExpr(
            CodeBlock.Builder whereExpr,
            CodeBlock.Builder locals,
            InputColumnBinding.MapBinding binding,
            String mapLocal,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef,
            int groupIndex) {
        if (binding.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys nidk) {
            String recLocal = "__lookupKey" + groupIndex;
            appendDecodeLocal(locals, recLocal, nidk, mapLocal, binding.fieldName());
            whereExpr.add("$T.val($L.value1(), $T.$L.$L.getDataType())",
                DSL, recLocal,
                tablesOnly.tablesClass(), tableRef.javaFieldName(), binding.targetColumn().javaName());
        } else {
            whereExpr.add("$T.val($L.get($S), $T.$L.$L.getDataType())",
                DSL, mapLocal, binding.fieldName(),
                tablesOnly.tablesClass(), tableRef.javaFieldName(), binding.targetColumn().javaName());
        }
    }

    /**
     * Declares a per-row decode local {@code recLocal} reading {@code mapLocal.get(sourceField)},
     * with {@code ThrowOnMismatch} producing a {@code GraphqlErrorException} on a null decode
     * return (lookup-key paths: wrong-type id is an authored-input contract violation) or
     * {@code SkipMismatchedElement} dropping silently via "no row matches" semantics — at the
     * single-row mutation site, a malformed id surfaces as a runtime failure regardless of arm,
     * because there is no per-row "skip" semantics in the SET / DELETE WHERE shape.
     */
    private static void appendDecodeLocal(
            CodeBlock.Builder locals,
            String recLocal,
            CallSiteExtraction.NodeIdDecodeKeys nidk,
            String mapLocal,
            String sourceField) {
        ClassName encoderClass = nidk.decodeMethod().encoderClass();
        String methodName = nidk.decodeMethod().methodName();
        TypeName recordType = nidk.decodeMethod().returnType();
        ClassName graphqlErr = ClassName.get("graphql", "GraphqlErrorException");
        locals.addStatement("$T $L = ($L.get($S) instanceof $T _s$L) ? $T.$L(_s$L) : null",
            recordType, recLocal, mapLocal, sourceField, String.class, recLocal, encoderClass, methodName, recLocal);
        locals.beginControlFlow("if ($L == null)", recLocal)
            .addStatement("throw $T.newErrorException().message($S).build()", graphqlErr,
                "Decoded NodeId did not match the expected type for input field '" + sourceField + "'")
            .endControlFlow();
    }

    /**
     * Emits per-row decode locals for every NodeId-decoded lookup-key group on the TIA. One
     * {@code Record<N>} local per {@link InputColumnBindingGroup.DecodedRecordGroup} or per
     * NodeIdDecodeKeys-extracted {@link InputColumnBinding.MapBinding}, named
     * {@code __bulkKey<gi>} (composite) / {@code __bulkKey<gi>_<bi>} (per-binding). Reads from
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
                        if (binding.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys nidk) {
                            appendDecodeLocal(block, "__bulkKey" + gi + "_" + bi, nidk, mapLocal, binding.fieldName());
                        }
                    }
                }
                case InputColumnBindingGroup.DecodedRecordGroup drg ->
                    appendDecodeLocal(block, "__bulkKey" + gi, drg.extraction(), mapLocal, drg.sourceFieldName());
            }
        }
    }

    /**
     * Emits per-row {@code cells.add($T.val(...))} statements for one lookup-key group.
     * MapBinding entries with NodeIdDecodeKeys read from the matching {@code __bulkKey<gi>_<bi>}
     * local declared by {@link #emitLookupKeyDecodeLocals}; DecodedRecordGroup entries read
     * {@code __bulkKey<gi>.value<k+1>()} per slot. Direct-extracted MapBindings read raw
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
                    if (binding.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys) {
                        String recLocal = "__bulkKey" + groupIndex + "_" + bi;
                        block.addStatement("cells.add($T.val($L.value1(), $T.$L.$L.getDataType()))",
                            DSL, recLocal,
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), binding.targetColumn().javaName());
                    } else {
                        block.addStatement("cells.add($T.val($L.get($S), $T.$L.$L.getDataType()))",
                            DSL, mapLocal, binding.fieldName(),
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), binding.targetColumn().javaName());
                    }
                }
            }
            case InputColumnBindingGroup.DecodedRecordGroup drg -> {
                String recLocal = "__bulkKey" + groupIndex;
                for (var binding : drg.bindings()) {
                    block.addStatement("cells.add($T.val($L.value$L(), $T.$L.$L.getDataType()))",
                        DSL, recLocal, binding.index() + 1,
                        tablesOnly.tablesClass(), tableRef.javaFieldName(), binding.targetColumn().javaName());
                }
            }
        }
    }

    /**
     * Builds a bulk lookup-key row-tuple {@code IN} predicate from the TIA's
     * {@code @lookupKey} groups: emits
     * {@code DSL.row(t.k1, ...).in(in.stream().map(row -> DSL.row(<per-slot value expr>)).toList())}.
     * Per-row decode for {@link InputColumnBindingGroup.DecodedRecordGroup} and
     * NodeIdDecodeKeys-extracted {@link InputColumnBinding.MapBinding} lives inside the stream
     * lambda (one decode call per arg per row). One shape regardless of key arity (PostgreSQL
     * renders 1-key {@code (col) IN ((v))} identically to {@code col IN (v)}).
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "mutation-input.where-columns-cover-pk",
        reliesOn = "Builds the bulk row-tuple IN predicate from the filter-column union without "
            + "verifying that the union is unique against the table. R144's PK-coverage "
            + "classifier guarantee lets the emitter treat each input row as matching at most "
            + "one database row; `multiRow: true` intentionally opts out of the guarantee.")
    private static CodeBlock buildBulkLookupRowIn(
            no.sikt.graphitron.rewrite.ArgumentRef.InputTypeArg.TableInputArg tia,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef) {
        var groups = tia.fieldBindings();
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
                            if (binding.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys nidk) {
                                String recLocal = "__bulkKey" + gi + "_" + bi;
                                appendDecodeLocal(lambdaLocals, recLocal, nidk, "row", binding.fieldName());
                            }
                        }
                    }
                    case InputColumnBindingGroup.DecodedRecordGroup drg -> {
                        String recLocal = "__bulkKey" + gi;
                        appendDecodeLocal(lambdaLocals, recLocal, drg.extraction(), "row", drg.sourceFieldName());
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
                            b.add("$T.val(row.get($S), $T.$L.$L.getDataType())",
                                DSL, binding.fieldName(),
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
                        if (binding.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys) return true;
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
                    if (binding.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys) {
                        String recLocal = "__bulkKey" + groupIndex + "_" + bi;
                        b.add("$T.val($L.value1(), $T.$L.$L.getDataType())",
                            DSL, recLocal,
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), binding.targetColumn().javaName());
                    } else {
                        b.add("$T.val(row.get($S), $T.$L.$L.getDataType())",
                            DSL, binding.fieldName(),
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), binding.targetColumn().javaName());
                    }
                }
            }
            case InputColumnBindingGroup.DecodedRecordGroup drg -> {
                String recLocal = "__bulkKey" + groupIndex;
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
            String fetcherName,
            no.sikt.graphitron.rewrite.model.DmlReturnExpression rex,
            Optional<ErrorChannel> errorChannel,
            String inputArgName,
            TableRef tableRef,
            GeneratorUtils.ResolvedTableNames tablesOnly,
            String tableLocal,
            String outputPackage,
            CodeBlock dmlChain,
            boolean listInput) {
        return buildDmlFetcher(ctx, fetcherName, rex, errorChannel, inputArgName, tableRef,
            tablesOnly, tableLocal, outputPackage, dmlChain,
            /*postDslGuard=*/ CodeBlock.of(""), /*postInGuard=*/ CodeBlock.of(""), listInput);
    }

    private static MethodSpec buildDmlFetcher(
            TypeFetcherEmissionContext ctx,
            String fetcherName,
            no.sikt.graphitron.rewrite.model.DmlReturnExpression rex,
            Optional<ErrorChannel> errorChannel,
            String inputArgName,
            TableRef tableRef,
            GeneratorUtils.ResolvedTableNames tablesOnly,
            String tableLocal,
            String outputPackage,
            CodeBlock dmlChain,
            CodeBlock postDslGuard,
            boolean listInput) {
        return buildDmlFetcher(ctx, fetcherName, rex, errorChannel, inputArgName, tableRef,
            tablesOnly, tableLocal, outputPackage, dmlChain,
            postDslGuard, /*postInGuard=*/ CodeBlock.of(""), listInput);
    }

    /**
     * Two optional {@link CodeBlock} slots and the bulk-input cardinality bit:
     * <ul>
     *   <li>{@code postDslGuard} — emitted immediately after the {@code dsl} local is bound,
     *       before the {@code in} cast. Used by UPSERT to gate the Oracle dialect (jOOQ silently
     *       translates {@code .onConflict(...)} to {@code MERGE INTO} on Oracle, with semantics
     *       drift), and by bulk UPDATE to gate non-PostgreSQL dialects on the
     *       {@code UPDATE ... FROM (VALUES ...)} form.</li>
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
            String fetcherName,
            no.sikt.graphitron.rewrite.model.DmlReturnExpression rex,
            Optional<ErrorChannel> errorChannel,
            String inputArgName,
            TableRef tableRef,
            GeneratorUtils.ResolvedTableNames tablesOnly,
            String tableLocal,
            String outputPackage,
            CodeBlock dmlChain,
            CodeBlock postDslGuard,
            CodeBlock postInGuard,
            boolean listInput) {
        var dslContextClass = ClassName.get("org.jooq", "DSLContext");
        TypeName valueType = switch (rex) {
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.EncodedSingle es -> ClassName.get(String.class);
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.EncodedList el ->
                ParameterizedTypeName.get(ClassName.get(List.class), ClassName.get(String.class));
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedSingle ps -> RECORD;
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedList pl ->
                ParameterizedTypeName.get(ClassName.get(List.class), RECORD);
        };
        var builder = MethodSpec.methodBuilder(fetcherName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");

        builder.beginControlFlow("try");
        builder.addStatement("$T dsl = $L.getDslContext(env)", dslContextClass, ctx.graphitronContextCall());
        if (!postDslGuard.isEmpty()) {
            builder.addCode(postDslGuard);
        }
        if (listInput) {
            builder.addStatement("$T<$T<?, ?>> in = ($T<$T<?, ?>>) env.getArgument($S)",
                ClassName.get(List.class), MAP, ClassName.get(List.class), MAP, inputArgName);
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

        builder.addCode(emitDmlReturnExpression(rex, valueType, tableRef, tablesOnly,
            outputPackage, tableLocal, dmlChain));
        builder.addCode(returnSyncSuccess(valueType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(catchArm(outputPackage, errorChannel));
        builder.endControlFlow();
        return builder.build();
    }

    /**
     * Emits the projection terminator and the {@code payload} local declaration, dispatched on
     * the pre-resolved {@link no.sikt.graphitron.rewrite.model.DmlReturnExpression} arm. Verb-
     * neutral: takes a pre-built {@code dmlChain} (e.g. {@code .deleteFrom(filmTable).where(...)}
     * or {@code .insertInto(filmTable, cols...).values(...)}) and appends
     * {@code .returningResult(...).fetchOne(...)}.
     */
    private static CodeBlock emitDmlReturnExpression(
            no.sikt.graphitron.rewrite.model.DmlReturnExpression rex,
            TypeName valueType,
            TableRef tableRef,
            GeneratorUtils.ResolvedTableNames tablesOnly,
            String outputPackage,
            String tableLocal,
            CodeBlock dmlChain) {
        return switch (rex) {
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.EncodedSingle es ->
                emitEncoded(es.encode(), valueType, tableRef, tablesOnly, dmlChain, /*isList=*/ false);
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.EncodedList el ->
                emitEncoded(el.encode(), valueType, tableRef, tablesOnly, dmlChain, /*isList=*/ true);
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedSingle ps ->
                emitProjected(ps.returnTypeName(), valueType, tableRef, tablesOnly, outputPackage,
                    tableLocal, dmlChain, /*isList=*/ false);
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedList pl ->
                emitProjected(pl.returnTypeName(), valueType, tableRef, tablesOnly, outputPackage,
                    tableLocal, dmlChain, /*isList=*/ true);
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
     * R75 Phase 1: TableBoundReturnType direct-{@code @table} return — two-step emit. The DML
     * runs inside {@code dsl.transactionResult(tx -> ...)} with a PK-only {@code RETURNING}
     * clause; the transaction commits when the lambda returns and yields a {@code Result} (list
     * shape) or a single {@code RecordN<...>} (single shape) of PK keys. The response SELECT
     * then runs against those keys outside the transaction, projecting
     * {@code Type.$fields(env.getSelectionSet(), table, env)} so graphql-java's per-field
     * fetchers see the full row record. Read errors during the SELECT or during nested
     * traversal propagate as field errors and cannot undo the DML.
     *
     * <p>Mirror of the carrier path's two-step shape in
     * {@link #buildMutationDmlRecordFetcher} and the data-field fetcher emitted by
     * {@code FetcherEmitter.buildSingleRecordTableFetcherValue}; the difference is that the
     * direct-{@code @table} path keeps the follow-up SELECT inside the same fetcher and returns
     * a {@code Record} (or {@code List<Record>}) directly, where the carrier path hands the
     * key Result to the data field's fetcher and lets it run the SELECT.
     */
    private static CodeBlock emitProjected(
            String returnTypeName, TypeName valueType,
            TableRef tableRef,
            GeneratorUtils.ResolvedTableNames tablesOnly,
            String outputPackage, String tableLocal,
            CodeBlock dmlChain, boolean isList) {
        var typeClass = ClassName.get(outputPackage + ".types", returnTypeName);
        var pkCols = tableRef.primaryKeyColumns();
        if (pkCols.isEmpty()) {
            // Fallback: no PK metadata, can't do two-step. The classifier rejects DML on
            // pkless tables ahead of emit, so this branch only runs in degenerate fixtures.
            var body = CodeBlock.builder()
                .add("$T payload = dsl\n", valueType).indent()
                .add(dmlChain)
                .add(".returningResult($T.$$fields(env.getSelectionSet(), $L, env))\n",
                    typeClass, tableLocal)
                .add(isList ? ".fetch(r -> r);\n" : ".fetchOne(r -> r);\n").unindent();
            return body.build();
        }
        var keyRowType = no.sikt.graphitron.rewrite.model.SourceKey.keyElementType(
            new no.sikt.graphitron.rewrite.model.SourceKey.Wrap.Record(), pkCols);
        TypeName keysType = isList
            ? ParameterizedTypeName.get(ClassName.get("org.jooq", "Result"), keyRowType)
            : keyRowType;

        var body = CodeBlock.builder()
            .add("$T keys = dsl.transactionResult(tx -> $T.using(tx)\n", keysType, DSL).indent()
            .add(dmlChain)
            .add(".returningResult(");
        for (int i = 0; i < pkCols.size(); i++) {
            if (i > 0) body.add(", ");
            var col = pkCols.get(i);
            body.add("$T.$L.$L", tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
        }
        body.add(")\n")
            .add(isList ? ".fetch());\n" : ".fetchOne());\n").unindent();

        if (!isList) {
            // Single-row UPDATE / DELETE with no match: keys is null. Skip the follow-up SELECT
            // and return null; matches the pre-two-step .fetchOne(r -> r) contract.
            body.add("if (keys == null) return $T.<$T>newResult().data(null).build();\n",
                DATA_FETCHER_RESULT, valueType);
        }

        body.add("$T payload = dsl.select($T.$$fields(env.getSelectionSet(), $L, env))\n",
            valueType, typeClass, tableLocal)
            .add("    .from($L)\n", tableLocal)
            .add("    .where(");
        if (isList) {
            if (pkCols.size() == 1) {
                var col = pkCols.get(0);
                body.add("$T.$L.$L.in(keys.getValues($T.$L.$L))",
                    tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName(),
                    tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
            } else {
                body.add("$T.row(", DSL);
                for (int i = 0; i < pkCols.size(); i++) {
                    if (i > 0) body.add(", ");
                    var col = pkCols.get(i);
                    body.add("$T.$L.$L", tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
                }
                body.add(").in(keys.stream().map(r -> $T.row(", DSL);
                for (int i = 0; i < pkCols.size(); i++) {
                    if (i > 0) body.add(", ");
                    var col = pkCols.get(i);
                    body.add("r.get($T.$L.$L)", tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
                }
                body.add(")).toList())");
            }
            body.add(")\n").add("    .fetch(r -> r);\n");
        } else {
            if (pkCols.size() == 1) {
                var col = pkCols.get(0);
                body.add("$T.$L.$L.eq(keys.value1())",
                    tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
            } else {
                body.add("$T.row(", DSL);
                for (int i = 0; i < pkCols.size(); i++) {
                    if (i > 0) body.add(", ");
                    var col = pkCols.get(i);
                    body.add("$T.$L.$L", tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
                }
                body.add(").eq($T.row(", DSL);
                for (int i = 0; i < pkCols.size(); i++) {
                    if (i > 0) body.add(", ");
                    var col = pkCols.get(i);
                    body.add("keys.get($T.$L.$L)", tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
                }
                body.add("))");
            }
            body.add(")\n").add("    .fetchOne(r -> r);\n");
        }
        return body.build();
    }

    /**
     * Generates a connection field fetcher that returns a {@code ConnectionResult}.
     *
     * <p>Extracts all four Relay pagination args, validates that {@code first} and {@code last}
     * are not both supplied, decodes cursor using column metadata, builds condition and orderBy,
     * reverses ordering for backward pagination, executes inline paginated SQL with
     * name-based extra-field deduplication, and wraps the result in a {@code ConnectionResult}.
     */
    private static MethodSpec buildQueryConnectionFetcher(TypeFetcherEmissionContext ctx, QueryField.QueryTableField qtf, String outputPackage) {
        var tableRef = qtf.returnType().table();
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, qtf.returnType().returnTypeName(), outputPackage);
        var connectionResultClass = ClassName.get(
            outputPackage + ".util", ConnectionResultClassGenerator.CLASS_NAME);
        var connectionHelperClass = ClassName.get(
            outputPackage + ".util", ConnectionHelperClassGenerator.CLASS_NAME);
        var conn = (FieldWrapper.Connection) qtf.returnType().wrapper();
        TypeName valueType = connectionResultClass;

        var builder = MethodSpec.methodBuilder(qtf.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");

        builder.beginControlFlow("try");
        String tableLocal = names.tableLocalName();
        builder.addStatement("$T $L = $T.$L",
            names.jooqTableClass(), tableLocal, names.tablesClass(), tableRef.javaFieldName());
        builder.addCode(buildConditionCall(qtf, tableLocal, outputPackage));
        // Single dispatch produces both orderBy (for SQL) and extraFields (for cursor columns),
        // keeping them in sync when the client picks a dynamic named order.
        builder.addCode(buildConnectionOrderingBlock(qtf.orderBy(), qtf.name(), names, tableLocal, outputPackage));

        // Pagination arg names are fixed by the slot (classifier rejects custom names).
        builder.addStatement("Integer first = env.getArgument($S)", "first");
        builder.addStatement("Integer last = env.getArgument($S)", "last");
        builder.addStatement("String after = env.getArgument($S)", "after");
        builder.addStatement("String before = env.getArgument($S)", "before");

        // Pagination resolved in one call : first/last guard, backward/pageSize/cursor derivation,
        // cursor decode, backward-ordering reversal, and name-based selection+extraFields merge
        // all live inside ConnectionHelper.pageRequest. The fetcher keeps the four env.getArgument
        // calls above so pageRequest itself has no graphql-java dependency.
        var pageRequestClass = ClassName.get(
            outputPackage + ".util", "ConnectionHelper", "PageRequest");
        builder.addStatement(
            "$T page = $T.pageRequest(first, last, after, before, $L, orderBy, extraFields, "
                + "$T.$$fields(env.getSelectionSet(), $L, env))",
            pageRequestClass, connectionHelperClass, conn.defaultPageSize(), names.typeClass(), tableLocal);

        var dslContextClass = ClassName.get("org.jooq", "DSLContext");
        builder.addStatement("$T dsl = $L.getDslContext(env)", dslContextClass, ctx.graphitronContextCall());

        // Single-expression paginated query : seek is a no-op when page.seekFields() are noField()
        var resultOfRecord = ParameterizedTypeName.get(
            ClassName.get("org.jooq", "Result"), ClassName.get("org.jooq", "Record"));
        builder.addCode(CodeBlock.builder()
            .add("$T result = dsl\n", resultOfRecord)
            .indent()
            .add(".select(page.selectFields())\n")
            .add(".from($L)\n", tableLocal)
            .add(".where(condition)\n")
            .add(".orderBy(page.effectiveOrderBy())\n")
            .add(".seek(page.seekFields())\n")
            .add(".limit(page.limit())\n")
            .add(".fetch();\n")
            .unindent()
            .build());

        // Bind (table, condition) onto ConnectionResult so ConnectionHelper.totalCount can issue
        // SELECT count(*) using the same source and predicate as the page query. Lazy-on-selection:
        // the totalCount resolver only runs when the client selects the field.
        builder.addStatement("$T payload = new $T(result, page, $L, condition)",
            valueType, connectionResultClass, tableLocal);
        builder.addCode(returnSyncSuccess(valueType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(redactCatchArm(outputPackage));
        builder.endControlFlow();

        return builder.build();
    }

    /**
     * Generates both the {@code orderBy} and {@code extraFields} variable declarations for a
     * connection fetcher body. The two variables are always derived from the same source, keeping
     * the cursor columns in sync with the SQL ordering.
     *
     * <ul>
     *   <li>{@link OrderBySpec.Fixed} — inlines both lists directly from the fixed columns.</li>
     *   <li>{@link OrderBySpec.Argument} — delegates to the {@code <fieldName>OrderBy(env)} helper
     *       (which returns an {@code OrderByResult}) and extracts {@code .sortFields()} and
     *       {@code .columns()} from the single result object.</li>
     *   <li>{@link OrderBySpec.None} — emits two empty lists.</li>
     * </ul>
     */
    private static CodeBlock buildConnectionOrderingBlock(
            OrderBySpec orderBy, String fieldName, GeneratorUtils.ResolvedTableNames names, String srcAlias, String outputPackage) {
        var code = CodeBlock.builder();
        var JOOQ_FIELD = ClassName.get("org.jooq", "Field");
        var WILDCARD_FIELD = ParameterizedTypeName.get(JOOQ_FIELD,
            no.sikt.graphitron.javapoet.WildcardTypeName.subtypeOf(Object.class));
        var listOfField = ParameterizedTypeName.get(LIST, WILDCARD_FIELD);

        switch (orderBy) {
            case OrderBySpec.Fixed fixed -> {
                if (fixed.columns().isEmpty()) {
                    code.addStatement("$T orderBy = $T.of()", SORT_FIELD_LIST, LIST);
                    code.addStatement("$T extraFields = $T.of()", listOfField, LIST);
                } else {
                    var sortParts = CodeBlock.builder();
                    var colParts = CodeBlock.builder();
                    for (int i = 0; i < fixed.columns().size(); i++) {
                        var col = fixed.columns().get(i);
                        if (i > 0) { sortParts.add(", "); colParts.add(", "); }
                        sortParts.add("$L.$L.$L()", srcAlias, col.column().javaName(), fixed.jooqMethodName());
                        colParts.add("$L.$L", srcAlias, col.column().javaName());
                    }
                    code.addStatement("$T orderBy = $T.of($L)", SORT_FIELD_LIST, LIST, sortParts.build());
                    code.addStatement("$T extraFields = $T.of($L)", listOfField, LIST, colParts.build());
                }
            }
            case OrderBySpec.Argument arg -> {
                // Single dispatch: OrderByResult carries both sort fields and cursor columns.
                // Pass the caller's aliased Table so the helper's column refs resolve to the
                // right jOOQ instance (canonical tableLocal for root, FK-chain terminal alias
                // for Split+Connection).
                var orderByResultClass = ClassName.get(
                    outputPackage + ".util", OrderByResultClassGenerator.CLASS_NAME);
                code.addStatement("$T ordering = $LOrderBy(env, $L)", orderByResultClass, fieldName, srcAlias);
                code.addStatement("$T orderBy = ordering.sortFields()", SORT_FIELD_LIST);
                code.addStatement("$T extraFields = ordering.columns()", listOfField);
            }
            case OrderBySpec.None ignored -> {
                code.addStatement("$T orderBy = $T.of()", SORT_FIELD_LIST, LIST);
                code.addStatement("$T extraFields = $T.of()", listOfField, LIST);
            }
        }
        return code.build();
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
                    var parts = CodeBlock.builder();
                    for (int i = 0; i < fixed.columns().size(); i++) {
                        var col = fixed.columns().get(i);
                        if (i > 0) parts.add(", ");
                        parts.add("$L.$L.$L()", srcAlias, col.column().javaName(), fixed.jooqMethodName());
                    }
                    code.addStatement("$T<$T<?>> orderBy = $T.of($L)", LIST, SORT_FIELD, LIST, parts.build());
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
    // OrderBy helper method generation (Steps 3+4 of orderby-implementation.md)
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
     * See {@code plan-split-query-connection.md} §2.
     */
    private static MethodSpec buildOrderByHelperMethod(
            String fieldName,
            OrderBySpec.Argument arg,
            GeneratorUtils.ResolvedTableNames names,
            TableRef tableRef, String outputPackage) {

        var orderByResultClass = ClassName.get(
            outputPackage + ".util", OrderByResultClassGenerator.CLASS_NAME);

        String tableLocal = names.tableLocalName();
        var builder = MethodSpec.methodBuilder(fieldName + "OrderBy")
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
            case OrderBySpec.Fixed fixed when !fixed.columns().isEmpty() -> {
                var sortParts = CodeBlock.builder();
                var colParts = CodeBlock.builder();
                for (int i = 0; i < fixed.columns().size(); i++) {
                    if (i > 0) { sortParts.add(", "); colParts.add(", "); }
                    var col = fixed.columns().get(i);
                    sortParts.add("$L.$L.$L()", srcAlias, col.column().javaName(), fixed.jooqMethodName());
                    colParts.add("$L.$L", srcAlias, col.column().javaName());
                }
                yield CodeBlock.of("new $T($T.of($L), $T.of($L))",
                    orderByResultClass, LIST, sortParts.build(), LIST, colParts.build());
            }
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
                sortParts.add("$S.equals(dir) ? $L.$L.desc() : $L.$L.$L()",
                    "DESC", srcAlias, col.column().javaName(), srcAlias, col.column().javaName(),
                    namedOrder.order().jooqMethodName());
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
                code.addStatement("sortParts.add($S.equals(d) ? $L.$L.desc() : $L.$L.$L())",
                    "DESC", srcAlias, col.column().javaName(), srcAlias, col.column().javaName(),
                    namedOrder.order().jooqMethodName());
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

    /**
     * Generates a thin data fetcher for a lookup query field that delegates to the rows method.
     *
     * <p>Generated code:
     * <pre>{@code
     * public static Result<Record> filmById(DataFetchingEnvironment env) {
     *     return lookupFilmById(env);
     * }
     * }</pre>
     *
     * <p>The split between this thin entry point and {@link #buildQueryLookupRowsMethod} allows the
     * rows method to be called independently (e.g. by an Apollo Federation {@code _entities}
     * resolver) without going through the GraphQL data fetcher path.
     */
    private static MethodSpec buildQueryLookupFetcher(TypeFetcherEmissionContext ctx, QueryField.QueryLookupTableField field, String outputPackage) {
        TypeName valueType = ParameterizedTypeName.get(RESULT, RECORD);
        var builder = MethodSpec.methodBuilder(field.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");
        builder.beginControlFlow("try");
        builder.addStatement("$T payload = $L(env)", valueType, field.lookupMethodName());
        builder.addCode(returnSyncSuccess(valueType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(redactCatchArm(outputPackage));
        builder.endControlFlow();
        return builder.build();
    }

    /**
     * Generates the lookup rows method for a {@link QueryField.QueryLookupTableField}.
     *
     * <p>The body is emitted via {@link LookupValuesJoinEmitter}: a typed {@code Row[]} is
     * constructed by a companion helper, then the VALUES derived table is joined to the target
     * via {@code USING (…)} and ordered by the derived table's {@code idx} column to preserve
     * input ordering. See {@code docs/argument-resolution.adoc} for design rationale.
     *
     * <p>Generated code (single list key):
     * <pre>{@code
     * public static Result<Record> lookupFilmById(DataFetchingEnvironment env) {
     *     Film table = Tables.FILM;
     *     Row[] rows = filmByIdInputRows(env, table);
     *     var dsl = graphitronContext(env).getDslContext(env);
     *     if (rows.length == 0) return dsl.newResult();
     *     Table<?> input = DSL.values(rows).as("filmByIdInput", "idx", "FILM_ID");
     *     return dsl.select(Film.$fields(env.getSelectionSet(), table, env))
     *               .from(table)
     *               .join(input).using(table.FILM_ID)
     *               .orderBy(input.field("idx"))
     *               .fetch();
     * }
     * }</pre>
     */
    private static MethodSpec buildQueryLookupRowsMethod(TypeFetcherEmissionContext ctx, QueryField.QueryLookupTableField field, String outputPackage) {
        var tableRef = field.returnType().table();
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, field.returnType().returnTypeName(), outputPackage);

        var builder = MethodSpec.methodBuilder(field.lookupMethodName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(RESULT, RECORD))
            .addParameter(ENV, "env");

        builder.addCode(GeneratorUtils.declareTableLocal(names, tableRef));
        String tableLocal = names.tableLocalName();

        // Declare the WHERE condition for non-key filters (field-level @condition or per-arg
        // @condition). Post-C1 lookup-key args flow through LookupValuesJoinEmitter and never
        // appear here; the loop iterates only ConditionFilter / non-lookup GeneratedConditionFilter
        // entries. For pure-@lookupKey fields (the common case) filters() is empty and this is a
        // no-op that jOOQ elides.
        builder.addStatement("$T condition = $T.noCondition()", CONDITION, DSL);
        for (var filter : field.filters()) {
            for (var param : filter.callParams()) {
                if (param.extraction() instanceof CallSiteExtraction.JooqConvert && param.list()) {
                    builder.addStatement("$T<$T> $L = env.getArgument($S)",
                        LIST, String.class, toCamelCase(param.name()) + "Keys", param.name());
                }
            }
            var callArgs = ArgCallEmitter.buildCallArgs(ctx, filter.callParams(), filter.className(), tableLocal);
            builder.addStatement("condition = condition.and($T.$L($L))",
                ClassName.bestGuess(filter.className()), filter.methodName(), callArgs);
        }

        var typeFieldsCall = CodeBlock.of("$T.$$fields(env.getSelectionSet(), $L, env)",
            names.typeClass(), tableLocal);
        builder.addCode(LookupValuesJoinEmitter.buildFetcherBody(ctx, field, typeFieldsCall, tableLocal));
        return builder.build();
    }

    /**
     * Generates a stub method that throws {@link UnsupportedOperationException} with the
     * reason string rendered from {@link #STUBBED_VARIANTS}. Fails fast with
     * {@link AssertionError} if the class is not in the map, which means the switch arm
     * is missing a map entry.
     */
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
        builder.addCode(redactCatchArm(outputPackage));
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
     * R75 Phase 1: emits the fetcher for a {@link MutationField.MutationDmlRecordField} — the
     * record-returning DML mutation. Body is two-step: the DML chain (per-kind) runs inside
     * {@code dsl.transactionResult(tx -> DSL.using(tx)....)}, projects the input table's PK
     * columns via {@code .returningResult(PK1, PK2, ...)}, and returns a single
     * {@code RecordN<...>} via {@code .fetchOne()}. The transaction commits when
     * {@code transactionResult} returns; the materialised key Record outlives it, and the
     * response SELECT happens later in the data field's
     * {@link ChildField.SingleRecordTableField} fetcher — outside the transaction, so read
     * errors during traversal cannot undo the DML.
     *
     * <p>DML chain construction reuses the existing per-kind helpers
     * ({@link #buildPerCellValueList}, {@link #buildLookupWhere}) so the SET / WHERE /
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
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "mutation-dml-record-field.data-table-equals-input-table",
        reliesOn = "The two-step body's RETURNING clause projects "
            + "tableInputArg.inputTable().primaryKeyColumns(); the data field's response "
            + "SELECT reads the same columns off env.getSource(). Without table-equality the "
            + "two halves would reference different column sets, and jOOQ would reject the "
            + "data-field WHERE predicate at runtime.")
    private static MethodSpec buildMutationDmlRecordFetcher(
            TypeFetcherEmissionContext ctx, MutationField.MutationDmlRecordField f, String outputPackage) {
        var tia = f.tableInputArg();
        var tableRef = tia.inputTable();
        var tablesOnly = GeneratorUtils.ResolvedTableNames.ofTable(tableRef);
        String tableLocal = tablesOnly.tableLocalName();
        var pkCols = tableRef.primaryKeyColumns();
        if (pkCols.isEmpty()) {
            throw new IllegalStateException(
                "MutationDmlRecordField '" + f.qualifiedName() + "' references table '"
                + tableRef.tableName() + "' that has no primary key; admission requires PK columns");
        }
        TypeName payloadType = no.sikt.graphitron.rewrite.model.SourceKey.keyElementType(
            new no.sikt.graphitron.rewrite.model.SourceKey.Wrap.Record(), pkCols);
        var dslContextClass = ClassName.get("org.jooq", "DSLContext");

        var builder = MethodSpec.methodBuilder(f.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(payloadType))
            .addParameter(ENV, "env");
        builder.beginControlFlow("try");
        builder.addStatement("$T dsl = $L.getDslContext(env)", dslContextClass, ctx.graphitronContextCall());
        builder.addStatement("$T<?, ?> in = ($T<?, ?>) env.getArgument($S)", MAP, MAP, tia.name());
        builder.addStatement("$T $L = $T.$L",
            tablesOnly.jooqTableClass(), tableLocal, tablesOnly.tablesClass(), tableRef.javaFieldName());

        // DML chain per kind. Each branch produces a CodeBlock starting with `.<verb>(...)`
        // suitable for chaining off `DSL.using(tx)` inside transactionResult.
        var chainAndGuards = buildDmlChainForRecord(f, tia, tableRef, tablesOnly, tableLocal);
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

        builder.addCode(returnSyncSuccess(payloadType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(catchArm(outputPackage, f.errorChannel(),
            singleRecordSentinelFor(tableRef, tablesOnly, pkCols)));
        builder.endControlFlow();
        return builder.build();
    }

    /**
     * Builds the {@code DSL.using(SQLDialect.DEFAULT).newRecord(<pk fields>)} CodeBlock used as
     * the non-null sentinel source for the {@link ErrorChannel.LocalContext} catch arm of a
     * {@link MutationField.MutationDmlRecordField} fetcher. The sentinel is a structurally-valid
     * {@code Record1}/{@code Record2}/... whose every column is null, so the data field's
     * null-PK SELECT short-circuits to {@code null} (jOOQ's {@code WHERE pk = null} resolves to
     * no row) and graphql-java traverses the carrier into the errors field. The sentinel never
     * touches a real connection: {@link org.jooq.SQLDialect#DEFAULT} keeps construction pure.
     */
    private static CodeBlock singleRecordSentinelFor(no.sikt.graphitron.rewrite.model.TableRef tableRef,
            GeneratorUtils.ResolvedTableNames tablesOnly,
            java.util.List<no.sikt.graphitron.rewrite.model.ColumnRef> pkCols) {
        var sql = ClassName.get("org.jooq", "SQLDialect");
        var b = CodeBlock.builder().add("$T.using($T.DEFAULT).newRecord(", GeneratorUtils.DSL, sql);
        for (int i = 0; i < pkCols.size(); i++) {
            if (i > 0) b.add(", ");
            b.add("$T.$L.$L", tablesOnly.tablesClass(), tableRef.javaFieldName(), pkCols.get(i).javaName());
        }
        b.add(")");
        return b.build();
    }

    /**
     * Bulk variant of {@link #singleRecordSentinelFor}: emits
     * {@code DSL.using(SQLDialect.DEFAULT).newResult(<pk fields>)}. The empty {@code Result}
     * source feeds the {@code SourceKey.Cardinality.MANY} data fetcher, which projects no rows
     * and renders the SDL data field as an empty list. The errors field reads localContext.
     */
    private static CodeBlock bulkRecordSentinelFor(no.sikt.graphitron.rewrite.model.TableRef tableRef,
            GeneratorUtils.ResolvedTableNames tablesOnly,
            java.util.List<no.sikt.graphitron.rewrite.model.ColumnRef> pkCols) {
        var sql = ClassName.get("org.jooq", "SQLDialect");
        var b = CodeBlock.builder().add("$T.using($T.DEFAULT).newResult(", GeneratorUtils.DSL, sql);
        for (int i = 0; i < pkCols.size(); i++) {
            if (i > 0) b.add(", ");
            b.add("$T.$L.$L", tablesOnly.tablesClass(), tableRef.javaFieldName(), pkCols.get(i).javaName());
        }
        b.add(")");
        return b.build();
    }

    /** Pair: the DML chain (everything from {@code .insertInto(...)} through {@code .doUpdate()....}) plus any pre-DML guard statements (e.g. dynamic SET-map construction). */
    private record DmlChainAndGuards(CodeBlock chain, CodeBlock preGuard) {}

    private static DmlChainAndGuards buildDmlChainForRecord(
            MutationField.MutationDmlRecordField f,
            no.sikt.graphitron.rewrite.ArgumentRef.InputTypeArg.TableInputArg tia,
            TableRef tableRef,
            GeneratorUtils.ResolvedTableNames tablesOnly,
            String tableLocal) {
        return switch (f.kind()) {
            case INSERT -> buildRecordInsertChain(tia, tableRef, tablesOnly, tableLocal);
            case UPDATE -> buildRecordUpdateChain(tia, tableRef, tablesOnly, tableLocal);
            case UPSERT -> buildRecordUpsertChain(tia, tableRef, tablesOnly, tableLocal);
            case DELETE -> buildRecordDeleteChain(tia, tableRef, tablesOnly, tableLocal);
        };
    }

    /**
     * R156 — DELETE chain for a single-input {@link MutationField.MutationDmlRecordField} carrier.
     * Mirrors the direct-return DELETE chain ({@link #buildMutationDeleteFetcher}): same WHERE
     * shape from the input @table's PK / @lookupKey columns, no SET clause. The enclosing
     * {@link #buildMutationDmlRecordFetcher} adds {@code .returningResult(pkCols)} so the
     * fetcher's value (consumed by the per-field
     * {@link no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdFieldFromReturning}
     * or {@link no.sikt.graphitron.rewrite.model.ChildField.SingleRecordTableFieldFromReturning}
     * carrier) is a PK-only RETURNING Record.
     */
    private static DmlChainAndGuards buildRecordDeleteChain(
            no.sikt.graphitron.rewrite.ArgumentRef.InputTypeArg.TableInputArg tia,
            TableRef tableRef, GeneratorUtils.ResolvedTableNames tablesOnly, String tableLocal) {
        var whereChunk = buildLookupWhereSingleRow(tia, tablesOnly, tableRef);
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
                    .add(buildInsertDecodeLocals(fields, "row", "__insertKey"))
                    .add("return $T.row(\n", DSL).indent()
                    .add(buildPerCellValueList(fields, tablesOnly, tableRef, "row", "__insertKey")).unindent()
                    .add(");\n").unindent()
                    .add("})\n")
                    .add(".toList())\n").unindent();
            } else {
                chain.add(".valuesOfRows(in.stream()\n").indent()
                    .add(".map(row -> $T.row(\n", DSL).indent()
                    .add(buildPerCellValueList(fields, tablesOnly, tableRef, "row", "__insertKey")).unindent()
                    .add("))\n")
                    .add(".toList())\n").unindent();
            }
        } else {
            preGuard.add(buildInsertDecodeLocals(fields, "in", "__insertKey"));
            chain.add(".values(\n").indent()
                .add(buildPerCellValueList(fields, tablesOnly, tableRef, "in", "__insertKey")).unindent()
                .add(")\n");
        }
        return new DmlChainAndGuards(chain.build(), preGuard.build());
    }

    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "mutation-input.update-set-fields-equal-value-marked",
        reliesOn = "MutationDmlRecordField UPDATE arm: walks tia.setFields() for the SET map "
            + "without checking @value directive presence or DmlKind; R144's classifier "
            + "guarantee that setFields() is exactly the @value-marked admissible carriers on "
            + "UPDATE.")
    private static DmlChainAndGuards buildRecordUpdateChain(
            no.sikt.graphitron.rewrite.ArgumentRef.InputTypeArg.TableInputArg tia,
            TableRef tableRef, GeneratorUtils.ResolvedTableNames tablesOnly, String tableLocal) {
        if (tia.list()) {
            throw new UnsupportedOperationException(
                "Bulk UPDATE on MutationDmlRecordField is not yet implemented; use single-input "
                    + "UPDATE or open a follow-up for the VALUES-join shape");
        }
        var fieldClass = ClassName.get("org.jooq", "Field");
        var linkedHashMap = ClassName.get("java.util", "LinkedHashMap");
        var preGuard = CodeBlock.builder();
        preGuard.addStatement("$T<$T<?>, Object> sets = new $T<>()", MAP, fieldClass, linkedHashMap);
        for (var sf : tia.setFields()) {
            var cf = (InputField.ColumnField) sf;
            preGuard.beginControlFlow("if (in.containsKey($S))", cf.name())
                .addStatement("sets.put($T.$L.$L, $T.val(in.get($S), $T.$L.$L.getDataType()))",
                    tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName(),
                    DSL, cf.name(),
                    tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName())
                .endControlFlow();
        }
        preGuard.beginControlFlow("if (sets.isEmpty())")
            .addStatement("throw new $T($S)", IllegalArgumentException.class,
                "@mutation(typeName: UPDATE) call has no settable fields present; "
                    + "only @lookupKey fields were provided")
            .endControlFlow();
        var whereChunk = buildLookupWhereSingleRow(tia, tablesOnly, tableRef);
        preGuard.add(whereChunk.decodeLocals());
        var chain = CodeBlock.builder()
            .add(".update($L)\n", tableLocal)
            .add(".set(sets)\n")
            .add(".where(").add(whereChunk.whereExpr()).add(")\n")
            .build();
        return new DmlChainAndGuards(chain, preGuard.build());
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
            for (var sf : tia.setFields()) {
                var cf = (InputField.ColumnField) sf;
                preGuard.beginControlFlow("if (in.containsKey($S))", cf.name())
                    .addStatement("setsUpdate.put($T.$L.$L, $T.excluded($T.$L.$L))",
                        tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName(),
                        DSL,
                        tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName())
                    .endControlFlow();
            }
            preGuard.beginControlFlow("if (setsUpdate.isEmpty())")
                .addStatement("throw new $T($S)", IllegalArgumentException.class,
                    "@mutation(typeName: UPSERT) call has no settable fields present; "
                        + "only @lookupKey fields were provided")
                .endControlFlow();
        }
        preGuard.add(buildInsertDecodeLocals(fields, "in", "__insertKey"));
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
            .add(buildPerCellValueList(fields, tablesOnly, tableRef, "in", "__insertKey")).unindent()
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
     * R141: emits the fetcher for a {@link MutationField.MutationBulkDmlRecordField} — a record-
     * returning DML mutation with bulk {@code @table} input and a list-shaped data field on the
     * carrier. The fetcher loops the input list, runs one DML per row inside
     * {@code dsl.transactionResult(...)}, collects the PK records into a typed
     * {@code Result<RecordN<...>>} in input order, and returns the accumulated Result. The
     * downstream data field's fetcher ({@link FetcherEmitter#buildSingleRecordTableFetcherValue}
     * with {@link no.sikt.graphitron.rewrite.model.SourceKey.Cardinality#MANY}) reads that Result
     * via {@code env.getSource()} and runs the bulk response SELECT outside the transaction.
     *
     * <p><b>Order preservation invariant.</b> {@code output.data[i]} corresponds to
     * {@code input[i]} for all {@code i ∈ [0, N)}. The Java for-each loop iterates the input
     * list in declaration order; {@code Result.add(record)} preserves insertion order; the
     * upstream {@code Result<RecordN<PK>>} therefore lands at the data-field fetcher with
     * PKs in input order. The downstream SELECT's {@code WHERE pk IN (...)} does not preserve
     * order, but {@link FetcherEmitter}'s {@code buildSingleRecordTableFetcherValue}
     * {@code Cardinality.MANY} arm re-keys the SELECT result into a PK-indexed map and walks
     * {@code source.getValues(PK)} to project rows in input order — input order is a property
     * of the emitted Java, not of the SQL planner's choice. The deliberately-non-PK-ordered
     * round-trip in {@code DmlBulkMutationsExecutionTest} is the runtime audit; any future
     * single-statement emit refinement (e.g. an ordinal-preserving Postgres contract) must
     * preserve the same input-order assertion that round-trip makes.
     *
     * <p>Empty-list input: short-circuits before opening the transaction, returning an empty
     * typed {@code Result} (mirrors R134's empty-input short-circuit on the direct-{@code @table}
     * bulk arms).
     *
     * <p>DELETE-with-payload-return is rejected at the compact-constructor on
     * {@link MutationField.MutationBulkDmlRecordField}; UPSERT is deferred to R145 under R144's
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
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "mutation-dml-record-field.data-table-equals-input-table",
        reliesOn = "The per-row RETURNING clause projects "
            + "tableInputArg.inputTable().primaryKeyColumns(); the data field's bulk response "
            + "SELECT reads the same PK columns off env.getSource() (Result<RecordN<...>>). "
            + "Without table-equality the two halves would reference different column sets, "
            + "and jOOQ would reject the data-field WHERE predicate at runtime.")
    private static MethodSpec buildMutationBulkDmlRecordFetcher(
            TypeFetcherEmissionContext ctx, MutationField.MutationBulkDmlRecordField f, String outputPackage) {
        var tia = f.tableInputArg();
        var tableRef = tia.inputTable();
        var tablesOnly = GeneratorUtils.ResolvedTableNames.ofTable(tableRef);
        String tableLocal = tablesOnly.tableLocalName();
        var pkCols = tableRef.primaryKeyColumns();
        if (pkCols.isEmpty()) {
            throw new IllegalStateException(
                "MutationBulkDmlRecordField '" + f.qualifiedName() + "' references table '"
                + tableRef.tableName() + "' that has no primary key; admission requires PK columns");
        }
        TypeName recordRowType = no.sikt.graphitron.rewrite.model.SourceKey.keyElementType(
            new no.sikt.graphitron.rewrite.model.SourceKey.Wrap.Record(), pkCols);
        var resultClass = ClassName.get("org.jooq", "Result");
        TypeName resultType = ParameterizedTypeName.get(resultClass, recordRowType);
        var dslContextClass = ClassName.get("org.jooq", "DSLContext");

        var builder = MethodSpec.methodBuilder(f.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(resultType))
            .addParameter(ENV, "env");
        builder.beginControlFlow("try");
        builder.addStatement("$T dsl = $L.getDslContext(env)", dslContextClass, ctx.graphitronContextCall());
        builder.addStatement("$T<$T<?, ?>> in = ($T<$T<?, ?>>) env.getArgument($S)",
            LIST, MAP, LIST, MAP, tia.name());
        builder.addStatement("$T $L = $T.$L",
            tablesOnly.jooqTableClass(), tableLocal, tablesOnly.tablesClass(), tableRef.javaFieldName());

        // Empty-list short-circuit: no DML, return empty Result. Mirrors R134's empty-input
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
        // through ErrorRouter into the carrier's error channel (R12 wiring, currently no-op for
        // NoBacking carriers).
        builder.addCode(CodeBlock.builder()
            .add("$T payload = dsl.transactionResult(tx -> {\n", resultType).indent()
            .add("$T txd = $T.using(tx);\n", dslContextClass, DSL)
            .add("$T acc = txd.newResult($L);\n", resultType, buildPkFieldList(pkCols, tablesOnly, tableRef))
            .add("for ($T<?, ?> row : in) {\n", MAP).indent()
            .add(buildBulkRecordPerRowBody(f, tia, tableRef, tablesOnly, tableLocal, pkCols, recordRowType))
            .unindent().add("}\n")
            .add("return acc;\n")
            .unindent().add("});\n")
            .build());

        builder.addCode(returnSyncSuccess(resultType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(catchArm(outputPackage, f.errorChannel(),
            bulkRecordSentinelFor(tableRef, tablesOnly, pkCols)));
        builder.endControlFlow();
        return builder.build();
    }

    /**
     * Builds the per-row DML body for {@link #buildMutationBulkDmlRecordFetcher} — the code that
     * runs once per input row inside the transactionResult loop. Dispatches on
     * {@link MutationField.MutationBulkDmlRecordField#kind()}:
     *
     * <ul>
     *   <li>{@code INSERT}: per-row {@code insertInto(table, cols).values(perCell).returningResult(PK).fetchOne()}.</li>
     *   <li>{@code UPDATE}: per-row dynamic SET-map (R134 contains-key dispatch), lookup-WHERE
     *       via {@link #buildLookupWhereSingleRow}'s {@code mapLocal="row"} overload, then
     *       {@code update(table).set(sets).where(...).returningResult(PK).fetchOne()}.</li>
     * </ul>
     *
     * <p>{@code UPDATE} no-match (zero rows updated) throws {@link IllegalStateException} to
     * preserve the order-preservation invariant: a silent no-match would skew {@code output.data[i]}
     * away from {@code input[i]}. Authors get a typed exception that flows through the catch arm.
     * The {@code DELETE} / {@code UPSERT} cases are rejected at the compact-constructor and never
     * reach this dispatch; the {@code default} arm guards against a future widening accident.
     */
    private static CodeBlock buildBulkRecordPerRowBody(
            MutationField.MutationBulkDmlRecordField f,
            no.sikt.graphitron.rewrite.ArgumentRef.InputTypeArg.TableInputArg tia,
            TableRef tableRef, GeneratorUtils.ResolvedTableNames tablesOnly,
            String tableLocal,
            List<no.sikt.graphitron.rewrite.model.ColumnRef> pkCols,
            TypeName recordRowType) {
        return switch (f.kind()) {
            case INSERT -> buildBulkRecordPerRowInsertBody(
                tia, tableRef, tablesOnly, tableLocal, pkCols, recordRowType);
            case UPDATE -> buildBulkRecordPerRowUpdateBody(
                tia, tableRef, tablesOnly, tableLocal, pkCols, recordRowType);
            case UPSERT -> throw new IllegalStateException(
                "MutationBulkDmlRecordField with DmlKind.UPSERT — compact-constructor should "
                + "have rejected this; UPSERT is deferred to R145 under R144's cardinality-"
                + "safety regime");
            case DELETE -> buildBulkRecordPerRowDeleteBody(
                tia, tableRef, tablesOnly, tableLocal, pkCols, recordRowType);
        };
    }

    /**
     * R156 — per-row DELETE body for {@link #buildBulkRecordPerRowBody}. Each input row builds a
     * {@code deleteFrom(table).where(<lookup>).returningResult(PK).fetchOne()} statement; the
     * returned PK-only {@code RecordN} is appended to the bulk accumulator in input order. A
     * row that matches no target raises {@link IllegalStateException} with the same shape as the
     * UPDATE no-match path — input-order preservation is a contract of the bulk-DML emit, and
     * silent skipping would break it.
     */
    private static CodeBlock buildBulkRecordPerRowDeleteBody(
            no.sikt.graphitron.rewrite.ArgumentRef.InputTypeArg.TableInputArg tia,
            TableRef tableRef, GeneratorUtils.ResolvedTableNames tablesOnly,
            String tableLocal,
            List<no.sikt.graphitron.rewrite.model.ColumnRef> pkCols,
            TypeName recordRowType) {
        var body = CodeBlock.builder();
        var whereChunk = buildLookupWhereSingleRow(tia, tablesOnly, tableRef, "row");
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
        body.add(buildInsertDecodeLocals(fields, "row", "__insertKey"));
        body.add("$T rec = txd.insertInto($L, ", recordRowType, tableLocal).add(colList).add(")\n")
            .add("    .values(\n").indent().indent()
            .add(buildPerCellValueList(fields, tablesOnly, tableRef, "row", "__insertKey")).unindent().unindent()
            .add(")\n")
            .add("    .returningResult(").add(buildPkFieldList(pkCols, tablesOnly, tableRef)).add(")\n")
            .add("    .fetchOne();\n")
            .add("acc.add(rec);\n");
        return body.build();
    }

    private static CodeBlock buildBulkRecordPerRowUpdateBody(
            no.sikt.graphitron.rewrite.ArgumentRef.InputTypeArg.TableInputArg tia,
            TableRef tableRef, GeneratorUtils.ResolvedTableNames tablesOnly,
            String tableLocal,
            List<no.sikt.graphitron.rewrite.model.ColumnRef> pkCols,
            TypeName recordRowType) {
        var fieldClass = ClassName.get("org.jooq", "Field");
        var linkedHashMap = ClassName.get("java.util", "LinkedHashMap");
        var body = CodeBlock.builder();
        body.addStatement("$T<$T<?>, Object> sets = new $T<>()", MAP, fieldClass, linkedHashMap);
        for (var sf : tia.setFields()) {
            var cf = (InputField.ColumnField) sf;
            body.beginControlFlow("if (row.containsKey($S))", cf.name())
                .addStatement("sets.put($T.$L.$L, $T.val(row.get($S), $T.$L.$L.getDataType()))",
                    tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName(),
                    DSL, cf.name(),
                    tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName())
                .endControlFlow();
        }
        body.beginControlFlow("if (sets.isEmpty())")
            .addStatement("throw new $T($S)", IllegalArgumentException.class,
                "@mutation(typeName: UPDATE) call has no settable fields present; "
                    + "only @lookupKey fields were provided")
            .endControlFlow();
        var whereChunk = buildLookupWhereSingleRow(tia, tablesOnly, tableRef, "row");
        body.add(whereChunk.decodeLocals());
        body.add("$T rec = txd.update($L)\n", recordRowType, tableLocal)
            .add("    .set(sets)\n")
            .add("    .where(").add(whereChunk.whereExpr()).add(")\n")
            .add("    .returningResult(").add(buildPkFieldList(pkCols, tablesOnly, tableRef)).add(")\n")
            .add("    .fetchOne();\n");
        // UPDATE no-match preserves the order-preservation invariant by failing fast rather
        // than skewing acc.size() against in.size() with a silent skip; the catch arm routes
        // the exception through the carrier's error channel (R12 wiring).
        body.beginControlFlow("if (rec == null)")
            .addStatement("throw new $T($S + row)", IllegalStateException.class,
                "@mutation(typeName: UPDATE) bulk row matched zero rows; @lookupKey filter "
                    + "found no target for input row: ")
            .endControlFlow();
        body.add("acc.add(rec);\n");
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
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "service-directive-resolver-strict-child-service-return",
        reliesOn = "Types the DataLoader as DataLoader<K, V> (via the loaderType assembled in "
            + "DataLoaderFetcherEmitter#build: ParameterizedTypeName.get(DATA_LOADER, keyType, "
            + "loaderValueType)) where V is the perKeyType the caller threads through "
            + "(tb.table().recordClass() for ServiceTableField, srf.elementType() for "
            + "ServiceRecordField). The BatchLoader lambda is built from the rows-method's "
            + "name (RowsMethodCall.batchLoaderLambda), so `newMappedDataLoader` / "
            + "`newDataLoader` overload resolution against the lambda's return type sees the "
            + "rows-method's declared `Map<K, V>` / `List<List<V>>` / `List<V>`. "
            + "ServiceDirectiveResolver's strict-return check rejects developer methods "
            + "whose declared return type doesn't match that exact shape, so the typed "
            + "loader compiles without a wildcard or defensive cast. Post-R177 the strict "
            + "TypeName.equals check is load-bearing for the typed loader, not just for the "
            + "rows method's `.returns(...)`.")
    private static MethodSpec buildServiceDataFetcher(
            TypeFetcherEmissionContext ctx,
            String fieldName,
            BatchKeyField bkf,
            MethodRef smr,
            ReturnTypeRef returnType,
            TableRef prt,
            TypeName perKeyType,
            String className,
            String outputPackage,
            Optional<ErrorChannel> errorChannel) {

        boolean isList = returnType.wrapper().isList();
        TypeName valueType = isList ? ParameterizedTypeName.get(LIST, perKeyType) : perKeyType;

        SourceKey sourceKey = bkf.sourceKey();
        TypeName keyType = sourceKey.keyElementType();
        LoaderRegistration registration = bkf.loaderRegistration();

        return DataLoaderFetcherEmitter.build(
            fieldName,
            keyType, valueType, asyncResultType(valueType),
            registration,
            ctx.graphitronContextCall(),
            RowsMethodCall.batchLoaderLambda(bkf.rowsMethodName(), keyType, registration),
            GeneratorUtils.buildKeyExtraction(sourceKey, prt),
            asyncWrapTail(valueType, outputPackage, errorChannel));
    }

    /**
     * Emits the rows method backing a {@code ServiceTableField} or {@code ServiceRecordField}
     * DataLoader. The body shapes as {@code [DSLContext dsl = ...; ] return ServiceClass.method(<args>);}
     * — argument assembly walks {@link MethodRef#params()} via
     * {@link ArgCallEmitter#buildMethodBackedCallArgs}, with {@code Sources → keys},
     * {@code DslContext → dsl} local, {@code Arg}/{@code Context} via the existing extraction
     * path. The developer's method returns the loader's expected {@code Map}/{@code List}
     * shape directly; graphql-java resolves columns off whatever records or values the developer
     * returns, so no per-record projection step is needed.
     *
     * <p>Signature follows the batch-loader contract:
     * <ul>
     *   <li>{@link LoaderRegistration.Container#POSITIONAL_LIST}: {@code keys} is
     *       {@code List<KeyType>}; return is {@code List<List<V>>} (list field) or
     *       {@code List<V>} (single).</li>
     *   <li>{@link LoaderRegistration.Container#MAPPED_SET}: {@code keys} is
     *       {@code Set<KeyType>}; return is {@code Map<KeyType, List<V>>} (list field) or
     *       {@code Map<KeyType, V>} (single).</li>
     * </ul>
     *
     * <p>{@code V} is {@code tb.table().recordClass()} for {@code ServiceTableField} (the
     * jOOQ-generated {@code XRecord} class for the field's bound table) and the per-key
     * element type for {@code ServiceRecordField} (caller passes {@code srf.elementType()}).
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "service-directive-resolver-strict-child-service-return",
        reliesOn = "Emits `return ServiceClass.method(<args>);` against a structurally-typed "
            + "rows-method return (Map<K, V>/List<List<V>>/List<V>) without a defensive cast or "
            + "wildcard local. ServiceDirectiveResolver's child-only strict-return check "
            + "rejects developer methods whose declared return type doesn't match this exact "
            + "outer shape (V = tb.table().recordClass() for TableBoundReturnType, the "
            + "elementType for ServiceRecordField), so any mismatch surfaces at classify time "
            + "rather than as a javac error on the generated source. Covers all (wrap, "
            + "container) combinations, including the typed-TableRecord wrap for jOOQ "
            + "TableRecord sources.")
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "service-catalog-instance-service-holder-shape",
        reliesOn = "Same guarantee as buildServiceFetcherCommon's static-vs-instance fork.")
    private static MethodSpec buildServiceRowsMethod(
            TypeFetcherEmissionContext ctx,
            BatchKeyField bkf,
            MethodRef method,
            ReturnTypeRef schemaReturnType,
            TypeName perKeyType,
            String parentTypeName,
            String outputPackage) {

        SourceKey sourceKey = bkf.sourceKey();
        LoaderRegistration registration = bkf.loaderRegistration();
        boolean isMapped = registration.container() == LoaderRegistration.Container.MAPPED_SET;
        ClassName containerClass = isMapped ? SET : LIST;
        TypeName keysContainerType = ParameterizedTypeName.get(containerClass, sourceKey.keyElementType());
        TypeName returnType = no.sikt.graphitron.rewrite.model.RowsMethodShape
            .outerRowsReturnType(perKeyType, schemaReturnType, sourceKey.keyElementType(), isMapped);

        var serviceClass = ClassName.bestGuess(method.className());
        String conditionsClassName = outputPackage + ".conditions."
            + parentTypeName + QueryConditionsGenerator.CLASS_NAME_SUFFIX;
        var service = (MethodRef.Service) method;
        boolean needsDsl = needsDsl(service.callShape());
        CodeBlock callTarget = serviceCallTarget(service, serviceClass);

        CodeBlock body = CodeBlock.builder()
            .addStatement("return $L.$L($L)",
                callTarget,
                method.methodName(),
                ArgCallEmitter.buildMethodBackedCallArgs(ctx, method, null, CodeBlock.of("keys"), conditionsClassName))
            .build();

        return RowsMethodSkeleton.build(
            bkf.rowsMethodName(),
            returnType,
            keysContainerType,
            ctx.graphitronContextCall(),
            new RowsMethodBody.Service(body, needsDsl));
    }

    // -----------------------------------------------------------------------
    // SplitTableField / SplitLookupTableField — DataLoader-registering fetcher + flat
    // correlated-batch rows method. See SplitRowsMethodEmitter for the body shape.
    // -----------------------------------------------------------------------

    /**
     * Generates a DataLoader-registering fetcher for a Split* field. Shape mirrors the intended
     * {@link #buildServiceDataFetcher} pattern — extract the per-parent batch key, register/lookup
     * the DataLoader, delegate to the rows method via the batch lambda.
     *
     * <p>List cardinality: returns {@code CompletableFuture<List<Record>>}. Single: returns
     * {@code CompletableFuture<Record>}.
     *
     * <p>The batch lambda uses {@code DataLoaderFactory.newDataLoader(BatchLoaderWithContext)} —
     * picked by overload resolution from the {@code (keys, env) -> …} lambda shape. The older
     * service-stub template cited {@code newDataLoaderWithContext} which does not exist on
     * {@code DataLoaderFactory}; the rows-method-takes-SelectedField shape was based on a similar
     * mis-cited {@code DataFetchingFieldSelectionSet.getField(String)} API — also absent.
     * Both are dropped here: the rows method takes only
     * {@code (List<KeyType>, DataFetchingEnvironment)}, and uses {@code env.getSelectionSet()}
     * directly for projection (which is semantically identical to {@code sel.getSelectionSet()}
     * when {@code sel} is the field being fetched).
     */
    private static MethodSpec buildSplitQueryDataFetcher(
            TypeFetcherEmissionContext ctx,
            BatchKeyField bkf,
            ReturnTypeRef.TableBoundReturnType tb,
            TableRef parentTable, String outputPackage) {

        boolean isList = tb.wrapper().isList();
        boolean isConnection = tb.wrapper() instanceof FieldWrapper.Connection;
        TypeName valueType;
        if (isConnection) {
            valueType = ClassName.get(
                outputPackage + ".util", ConnectionResultClassGenerator.CLASS_NAME);
        } else if (isList) {
            valueType = ParameterizedTypeName.get(LIST, RECORD);
        } else {
            valueType = RECORD;
        }

        SourceKey sourceKey = bkf.sourceKey();
        TypeName keyType = sourceKey.keyElementType();
        String fieldName = bkfFieldName(bkf);
        LoaderRegistration registration = bkf.loaderRegistration();

        // Single cardinality: NULL-FK short-circuit (the parent row's FK column may be nullable
        // and no `terminal.pk = parentInput.fk_value` match can exist under ANSI NULL semantics —
        // skip the DataLoader round-trip and return null directly). The unified emitter accepts
        // a keyExtraction CodeBlock that may short-circuit before reaching the dispatch line.
        CodeBlock keyExtraction = isList
            ? GeneratorUtils.buildKeyExtraction(sourceKey, parentTable)
            : GeneratorUtils.buildKeyExtractionWithNullCheck(sourceKey, parentTable);

        return DataLoaderFetcherEmitter.build(
            fieldName,
            keyType, valueType, asyncResultType(valueType),
            registration,
            ctx.graphitronContextCall(),
            RowsMethodCall.batchLoaderLambda(bkf.rowsMethodName(), keyType, registration),
            keyExtraction,
            asyncWrapTail(valueType, outputPackage, Optional.empty()));
    }

    /**
     * Emits the DataLoader name construction for the rewrite emitter. The name is built from
     * {@code GraphitronContext.getTenantId(env)} + {@code "/"} +
     * {@code env.getExecutionStepInfo().getPath().getKeysOnly()} joined by {@code "/"} —
     * tenant-scoped and path-unique. The path is Graphitron-controlled; only the tenant prefix
     * is pluggable via {@code GraphitronContext#getTenantId} (emitted per app), so implementers
     * cannot accidentally produce a colliding name.
     *
     * <p>{@code ResultPath.getKeysOnly()} returns named segments only (list indices stripped),
     * so {@code /films/0/actors} and {@code /films/1/actors} map to the same
     * {@code [films, actors]} key list — the correct batching scope for a per-parent
     * DataLoader. Aliased uses of the same field get different path segments because
     * graphql-java records aliases as path keys, so {@code heroes: actors} and
     * {@code villains: actors} end up in different DataLoaders.
     */
    private static CodeBlock buildDataLoaderName(TypeFetcherEmissionContext ctx) {
        return CodeBlock.builder()
            .addStatement("$T name = $L.getTenantId(env) + $S + $T.join($S, env.getExecutionStepInfo().getPath().getKeysOnly())",
                String.class, ctx.graphitronContextCall(), "/", String.class, "/")
            .build();
    }

    /**
     * Builds the DataFetcher method for a record-parent batched field
     * ({@link ChildField.RecordTableField}, {@link ChildField.RecordLookupTableField}). Shape is
     * identical to {@link #buildSplitQueryDataFetcher} except key extraction uses
     * {@link GeneratorUtils#buildRecordParentKeyExtraction} (backing-object or lifter-call
     * accessor) instead of {@link GeneratorUtils#buildKeyExtraction} (jOOQ table-row accessor).
     *
     * @param <T> the concrete field type — must implement both {@link ChildField.TableTargetField}
     *            (for {@code returnType()} and {@code name()}) and {@link BatchKeyField} (for
     *            {@code sourceKey()}, {@code loaderRegistration()}, and {@code rowsMethodName()}).
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "accessor-rowkey-cardinality-matches-field",
        reliesOn = "FieldBuilder.deriveAccessorRecordParentSource produces Cardinality.MANY + "
            + "Dispatch.LOAD_MANY only on list fields and Cardinality.ONE + Dispatch.LOAD_ONE "
            + "only on single fields. The valueType rule below "
            + "(field.emitsSingleRecordPerKey() → Record else List<Record>) folds the two cases "
            + "that emit a per-key value of Record (LOAD_MANY's loadMany contract; "
            + "single-cardinality LOAD_ONE). A LOAD_MANY accessor on a non-list field would "
            + "emit code expecting List<Record> from a loadMany that supplies Record, "
            + "miscompiling generated *Fetchers.")
    private static <T extends GraphitronField & BatchKeyField> MethodSpec
            buildRecordBasedDataFetcher(TypeFetcherEmissionContext ctx, T field,
                    ReturnTypeRef.TableBoundReturnType returnType,
                    SourceKey sourceKey,
                    GraphitronType.ResultType resultType, String outputPackage) {

        boolean isList = returnType.wrapper().isList();

        // The loader's per-key value is `Record` whenever the rows-method emits one record per
        // key (single-cardinality fields, or the LOAD_MANY loadMany contract on list fields);
        // otherwise `List<Record>`. Single source of truth lives on the field as
        // {@link BatchKeyField#emitsSingleRecordPerKey} — the same predicate the rows-method
        // router and the scatterSingleByIdx helper-emission gate consult.
        TypeName valueType = field.emitsSingleRecordPerKey()
            ? RECORD
            : ParameterizedTypeName.get(LIST, RECORD);
        // The fetcher's overall result follows the field's cardinality regardless of dispatch.
        TypeName resultValueType = isList ? ParameterizedTypeName.get(LIST, RECORD) : RECORD;

        TypeName keyType = sourceKey.keyElementType();
        LoaderRegistration registration = field.loaderRegistration();

        return DataLoaderFetcherEmitter.build(
            field.name(),
            keyType, valueType, asyncResultType(resultValueType),
            registration,
            ctx.graphitronContextCall(),
            RowsMethodCall.batchLoaderLambda(field.rowsMethodName(), keyType, registration),
            GeneratorUtils.buildRecordParentKeyExtraction(sourceKey, resultType),
            asyncWrapTail(resultValueType, outputPackage, Optional.empty()));
    }

    private static String bkfFieldName(BatchKeyField bkf) {
        if (bkf instanceof ChildField.SplitTableField stf) return stf.name();
        if (bkf instanceof ChildField.SplitLookupTableField slf) return slf.name();
        throw new IllegalArgumentException(
            "buildSplitQueryDataFetcher does not handle " + bkf.getClass().getSimpleName());
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
    //   - empty    -> ErrorRouter.redact(e, env)
    // Async paths today are all DataLoader-based child fields (BatchKeyField); none
    // implement WithErrorChannel, so they remain on the redact branch. When the
    // mutation-service builders (today still stubs) become live emitters, the same
    // fork applies on the .exceptionally(...) arm via asyncWrapTail's channel param.
    // -----------------------------------------------------------------------

    /** Box primitive value types so they can sit inside {@code DataFetcherResult<P>}. */
    private static TypeName boxed(TypeName valueType) {
        return valueType.isPrimitive() ? valueType.box() : valueType;
    }

    /** {@code DataFetcherResult<P>}; primitives box to their wrapper. */
    private static TypeName syncResultType(TypeName valueType) {
        return ParameterizedTypeName.get(DATA_FETCHER_RESULT, boxed(valueType));
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
    private static CodeBlock catchArm(String outputPackage, Optional<ErrorChannel> errorChannel) {
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
    private static CodeBlock catchArm(String outputPackage, Optional<ErrorChannel> errorChannel,
                                      CodeBlock localContextSentinel) {
        if (errorChannel.isEmpty()) {
            return redactCatchArm(outputPackage);
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
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "error-channel.local-context-transport",
        reliesOn = "FieldBuilder.detectStructuralDmlErrorChannel only emits ErrorChannel.LocalContext "
            + "when the carrier's data field's fetcher honors the null-source short-circuit "
            + "guard at FetcherEmitter.java:273. The emitted dispatchToLocalContext call sets "
            + "data=null on match, relying on that guard to keep the data side of the response "
            + "render coherent.")
    private static CodeBlock dispatchToLocalContextCatchArm(String outputPackage,
            ErrorChannel.LocalContext channel, CodeBlock sentinel) {
        return CodeBlock.of("return $T.dispatchToLocalContext(e, $T.$L, env, $L);\n",
            errorRouterClass(outputPackage),
            errorMappingsClass(outputPackage),
            channel.mappingsConstantName(),
            sentinel);
    }

    /**
     * Builds the standard catch arm for a synchronous fetcher without a typed-error channel:
     * redact the throw via the {@code ErrorRouter} emitted at {@code <outputPackage>.schema.ErrorRouter}.
     */
    private static CodeBlock redactCatchArm(String outputPackage) {
        return CodeBlock.of("return $T.redact(e, env);\n", errorRouterClass(outputPackage));
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
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "payload-construction.shape-resolved",
        reliesOn = "switch over errorsSlot is total. CtorParameterIndex emits the positional "
            + "lambda; SetterMethod emits the no-arg-ctor + setters lambda body.")
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
     * {@code rewrite-design-principles.adoc} the bound setter is called first for diagnostic
     * clarity; semantic order doesn't matter (Java-bean setters are independent assignments).
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "payload-construction.setter-name-matches-sdl-field",
        reliesOn = "prints boundSetter.getName() and each nonBoundSetter.setter().getName() "
            + "into the generated lambda body; the classifier guarantees each name resolves.")
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
        return CodeBlock.of("return $T.<$T>newResult().data($L).build();\n",
            DATA_FETCHER_RESULT, boxed(valueType), payloadLocal);
    }

    /**
     * Async tail for fetchers whose body ends with a {@code CompletableFuture<P>} expression
     * (typically {@code loader.load(key, env)}). Adds {@code .thenApply(...)} to lift the
     * payload into a {@code DataFetcherResult<P>}, then {@code .exceptionally(...)} to route
     * any exception that escapes past the synchronous wrapper (DataLoader bookkeeping, etc.).
     * The {@code .exceptionally} arm forks on {@code errorChannel} the same way
     * {@link #catchArm} does for sync fetchers.
     *
     * <p>Spec: §3 "CompletionException unwrap and async fetcher path".
     */
    private static CodeBlock asyncWrapTail(TypeName valueType, String outputPackage,
                                           Optional<ErrorChannel> errorChannel) {
        CodeBlock routerCall;
        if (errorChannel.isEmpty()) {
            routerCall = CodeBlock.of("$T.redact(t, env)", errorRouterClass(outputPackage));
        } else {
            routerCall = switch (errorChannel.get()) {
                case ErrorChannel.PayloadClass pc -> CodeBlock.builder()
                    .add("$T.dispatch(t, $T.$L, env, ",
                        errorRouterClass(outputPackage),
                        errorMappingsClass(outputPackage),
                        pc.mappingsConstantName())
                    .add(payloadFactoryLambda(pc))
                    .add(")")
                    .build();
                case ErrorChannel.LocalContext lc -> CodeBlock.of(
                    "$T.dispatchToLocalContext(t, $T.$L, env)",
                    errorRouterClass(outputPackage),
                    errorMappingsClass(outputPackage),
                    lc.mappingsConstantName());
            };
        }
        return CodeBlock.builder()
            .add(".thenApply(payload -> $T.<$T>newResult().data(payload).build())\n",
                DATA_FETCHER_RESULT, boxed(valueType))
            .add(".exceptionally(t -> ").add(routerCall).add(")")
            .build();
    }

}
