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
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.BatchKeyField;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.JoinStep;
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

    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage) {
        var result = new ArrayList<TypeSpec>(schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof GraphitronType.TableType
                      || e.getValue() instanceof GraphitronType.NodeType
                      || e.getValue() instanceof GraphitronType.RootType
                      || e.getValue() instanceof GraphitronType.ResultType)
            .map(Map.Entry::getKey)
            .sorted()
            .map(typeName -> generateForType(schema, typeName, outputPackage))
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
                    collectNestedFetcherClasses(nf, seenNestedTypes, result, outputPackage);
                }
            }));
        return result;
    }

    private static void collectNestedFetcherClasses(ChildField.NestingField nf,
            Set<String> seen, List<TypeSpec> out, String outputPackage) {
        var nestedTypeName = nf.returnType().returnTypeName();
        if (seen.add(nestedTypeName)) {
            var batchKeyFields = nf.nestedFields().stream()
                .filter(f -> f instanceof BatchKeyField)
                .map(f -> (GraphitronField) f)
                .sorted(Comparator.comparing(GraphitronField::name))
                .toList();
            if (!batchKeyFields.isEmpty()) {
                out.add(generateTypeSpec(nestedTypeName, nf.returnType().table(), null, batchKeyFields, outputPackage));
            }
        }
        for (var nested : nf.nestedFields()) {
            if (nested instanceof ChildField.NestingField innerNf) {
                collectNestedFetcherClasses(innerNf, seen, out, outputPackage);
            }
        }
    }

    private static TypeSpec generateForType(GraphitronSchema schema, String typeName, String outputPackage) {
        var type = schema.type(typeName);
        var fields = schema.fieldsOf(typeName).stream()
            .filter(f -> !(f instanceof GraphitronField.UnclassifiedField))
            .sorted(Comparator.comparing(GraphitronField::name))
            .toList();
        TableRef parentTable = type instanceof GraphitronType.TableBackedType tbt ? tbt.table() : null;
        GraphitronType.ResultType resultType = type instanceof GraphitronType.ResultType rt ? rt : null;
        return generateTypeSpec(typeName, parentTable, resultType, fields, outputPackage);
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
            // inline emission via TypeClassGenerator.$fields; see G5 and argres Phase 2a)
            // ChildField stubs — remaining direct permits
            Map.entry(ChildField.ColumnReferenceField.class,
                deferredFor(ChildField.ColumnReferenceField.class,
                    "ColumnReferenceField not yet implemented", "column-reference-on-scalar-field")),
            Map.entry(ChildField.CompositeColumnReferenceField.class,
                deferredFor(ChildField.CompositeColumnReferenceField.class,
                    "CompositeColumnReferenceField (rooted-at-parent NodeId reference) not yet implemented"
                    + " — JOIN-with-projection emission tracked in R24; rooted-at-parent fixture"
                    + " (parent_node + child_ref) is in nodeidfixture and ready to drive coverage",
                    "nodeidreferencefield-join-projection-form")),
            Map.entry(ChildField.TableMethodField.class,
                deferredFor(ChildField.TableMethodField.class,
                    "TableMethodField not yet implemented", "tablemethod-scalar-return")),
            Map.entry(ChildField.MultitableReferenceField.class,
                deferredFor(ChildField.MultitableReferenceField.class,
                    "MultitableReferenceField not yet implemented", "multitable-reference-on-scalar"))
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
        return generateTypeSpec(typeName, parentTable, null, fields, "");
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
            String outputPackage) {
        var className = typeName + "Fetchers";
        var builder = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC);

        // Per-class scratchpad for deferred helper-method emission. Every emitter that writes a
        // graphitronContext(env) call obtains the CodeBlock through ctx.graphitronContextCall(),
        // which records the dependency; class assembly drains the set below to decide which
        // helper methods to materialise. Replaces a previous post-scan that string-grepped
        // method bodies for the literal "graphitronContext(env)".
        var ctx = new TypeFetcherEmissionContext();

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
                    builder.addMethod(buildServiceDataFetcher(ctx, stf.name(), stf, stf.method(), stf.returnType(), parentTable, RECORD, className, outputPackage));
                    builder.addMethod(buildServiceRowsMethod(ctx, stf, stf.method(), stf.returnType(), RECORD, stf.parentTypeName(), outputPackage));
                }
                case ChildField.ServiceRecordField srf -> {
                    builder.addMethod(buildServiceDataFetcher(ctx, srf.name(), srf, srf.method(), srf.returnType(), parentTable, srf.elementType(), className, outputPackage));
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
                    // Phase 2a's env-based variant (buildInputRowsMethod) reads args from
                    // env.getArgument(name) — correct for a Split* fetcher whose @lookupKey args
                    // live on the field itself (vs. Phase 2a's inline child-lookup path where
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
                                conn.defaultPageSize(), null, outputPackage)
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
                                conn.defaultPageSize(), null, outputPackage)
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
                case ChildField.ColumnReferenceField f          -> {
                    if (f.compaction() instanceof CallSiteCompaction.NodeIdEncodeKeys) {
                        // Reference-side NodeId carrier: no fetcher method. The DataFetcher value
                        // is the runtime stub emitted by FetcherEmitter (rooted-at-parent emission
                        // tracked in R24).
                    } else {
                        builder.addMethod(stub(f));
                    }
                }
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
                    builder.addMethod(buildRecordBasedDataFetcher(ctx, rtf, rtf.batchKey(), resultType, outputPackage));
                    builder.addMethod(SplitRowsMethodEmitter.buildForRecordTable(ctx, rtf, outputPackage));
                }
                case ChildField.RecordLookupTableField rltf -> {
                    builder.addMethod(buildRecordBasedDataFetcher(ctx, rltf, rltf.batchKey(), resultType, outputPackage));
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
                case ChildField.TableMethodField f              -> builder.addMethod(stub(f));
                case ChildField.InterfaceField f -> {
                    if (f.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn) {
                        MultiTablePolymorphicEmitter
                            .emitConnectionMethods(ctx, f.name(), f.participants(), f.participantJoinPaths(),
                                conn.defaultPageSize(), parentTable, outputPackage)
                            .forEach(builder::addMethod);
                    } else {
                        MultiTablePolymorphicEmitter
                            .emitMethods(ctx, f.name(), f.participants(), f.participantJoinPaths(),
                                f.returnType().wrapper().isList(), outputPackage)
                            .forEach(builder::addMethod);
                    }
                }
                case ChildField.UnionField f -> {
                    if (f.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn) {
                        MultiTablePolymorphicEmitter
                            .emitConnectionMethods(ctx, f.name(), f.participants(), f.participantJoinPaths(),
                                conn.defaultPageSize(), parentTable, outputPackage)
                            .forEach(builder::addMethod);
                    } else {
                        MultiTablePolymorphicEmitter
                            .emitMethods(ctx, f.name(), f.participants(), f.participantJoinPaths(),
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
                case ChildField.MultitableReferenceField f      -> builder.addMethod(stub(f));
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
            || f instanceof ChildField.RecordLookupTableField);
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
     * <p>FK direction is inferred from {@code targetTable}: if {@code fkJoin.targetTable()}
     * matches the child table name, the parent holds the FK (parent.fk → child.pk); otherwise
     * the child holds the FK (child.fk → parent.pk). {@code ColumnRef} carries no table reference,
     * so {@code targetTable} is the only available signal.
     *
     * <p>Precondition: the classifier guarantees exactly one {@link JoinStep.FkJoin} step
     * (multi-hop and {@link JoinStep.ConditionJoin} paths are rejected at classification time).
     */
    private static CodeBlock buildJoinPathCondition(List<JoinStep> joinPath, String childTableName) {
        var fkJoin = (JoinStep.FkJoin) joinPath.get(0);
        String fkHolderCol = fkJoin.sourceColumns().get(0).sqlName();
        String pkCol       = fkJoin.targetColumns().get(0).sqlName();

        // targetTable is the PK side (the table the FK points to).
        // If it matches the child table, the parent holds the FK: parent.fk → child.pk.
        // Otherwise the child holds the FK: child.fk → parent.pk.
        boolean parentHoldsFk = fkJoin.targetTable().tableName().equals(childTableName);

        String childCol        = parentHoldsFk ? pkCol        : fkHolderCol;
        String parentRecordCol = parentHoldsFk ? fkHolderCol  : pkCol;

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
                // FK direction: the @reference is parsed starting from the interface table, so
                // sourceColumns sit on the parent (interface table = tableLocal) and targetColumns
                // sit on the joined alias — i.e. the parent holds the FK.
                var fkOn = JoinPathEmitter.emitCorrelationWhere(ctf.fkJoin(), aliasVar, tableLocal, true);
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
     * list is reproduced in declaration order via
     * {@link ArgCallEmitter#buildMethodBackedCallArgs}; the {@link ParamSource.Table} slot
     * resolves to {@code Tables.<NAME>} wherever the user declared it.
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
    private static MethodSpec buildQueryTableMethodFetcher(TypeFetcherEmissionContext ctx, QueryField.QueryTableMethodTableField qtmtf,
                                                            String outputPackage) {
        var tableRef = qtmtf.returnType().table();
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, qtmtf.returnType().returnTypeName(), outputPackage);
        boolean isList = qtmtf.returnType().wrapper().isList();

        TypeName valueType = isList ? ParameterizedTypeName.get(RESULT, RECORD) : RECORD;
        var dslContextClass = ClassName.get("org.jooq", "DSLContext");

        var methodClass = ClassName.bestGuess(qtmtf.method().className());
        var tableExpression = CodeBlock.of("$T.$L", names.tablesClass(), tableRef.javaFieldName());
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
        builder.addStatement("$T table = $T.$L($L)",
            names.jooqTableClass(),
            methodClass,
            qtmtf.method().methodName(),
            ArgCallEmitter.buildMethodBackedCallArgs(ctx, qtmtf.method(), tableExpression, conditionsClassName));

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
        builder.addCode(redactCatchArm(outputPackage));
        builder.endControlFlow();

        return builder.build();
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
        reliesOn = "Declares the typed Result<XRecord> (or XRecord) return on the fetcher and "
            + "lets graphql-java's column fetchers traverse it directly. A wider service "
            + "return would force Object on the fetcher and lose static type safety. Note: the "
            + "shared buildServiceFetcherCommon helper is also reached from "
            + "buildQueryServiceRecordFetcher, whose PojoResultType / ScalarReturnType paths "
            + "do not depend on this guarantee — annotating the helper would overclaim.")
    private static MethodSpec buildQueryServiceTableFetcher(TypeFetcherEmissionContext ctx, QueryField.QueryServiceTableField qstf,
                                                             String outputPackage) {
        var tableRef = qstf.returnType().table();
        var recordClass = tableRef.recordClass();
        boolean isList = qstf.returnType().wrapper().isList();
        TypeName returnType = isList
            ? ParameterizedTypeName.get(RESULT, recordClass)
            : recordClass;
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
     * §2c {@code resultAssembly} success-arm payload assembly uniformly across query and
     * mutation services. Phase 6 of mutations.md.
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "service-catalog-strict-service-return",
        reliesOn = "Declares the typed Result<XRecord> (or XRecord) return on the fetcher and "
            + "lets graphql-java's column fetchers traverse it directly. Inherits the same "
            + "service-catalog strictness as buildQueryServiceTableFetcher; mutation services "
            + "share the same MethodRef strictness path through ServiceCatalog.reflectServiceMethod.")
    private static MethodSpec buildMutationServiceTableFetcher(TypeFetcherEmissionContext ctx, MutationField.MutationServiceTableField mstf,
                                                                String outputPackage) {
        var tableRef = mstf.returnType().table();
        var recordClass = tableRef.recordClass();
        boolean isList = mstf.returnType().wrapper().isList();
        TypeName returnType = isList
            ? ParameterizedTypeName.get(RESULT, recordClass)
            : recordClass;
        return buildServiceFetcherCommon(ctx, mstf.name(), mstf.method(), mstf.parentTypeName(),
            returnType, mstf.errorChannel(), mstf.resultAssembly(), outputPackage);
    }

    /**
     * Emits the fetcher for a {@link MutationField.MutationServiceRecordField}: identical body
     * shape to {@link #buildQueryServiceRecordFetcher}. Both {@code ResultReturnType} (with or
     * without a {@code @record} backing class) and {@code ScalarReturnType} return shapes are
     * handled by {@link #computeMutationServiceRecordReturnType}, mirroring the query side.
     * Phase 6 of mutations.md.
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
    private static MethodSpec buildServiceFetcherCommon(TypeFetcherEmissionContext ctx, String fieldName, MethodRef method,
                                                        String parentTypeName, TypeName valueType,
                                                        Optional<ErrorChannel> errorChannel,
                                                        Optional<no.sikt.graphitron.rewrite.model.ResultAssembly> resultAssembly,
                                                        String outputPackage) {
        var dslContextClass = ClassName.get("org.jooq", "DSLContext");
        var serviceClass = ClassName.bestGuess(method.className());
        String conditionsClassName = outputPackage + ".conditions."
            + parentTypeName + QueryConditionsGenerator.CLASS_NAME_SUFFIX;
        boolean needsDsl = method.params().stream()
            .anyMatch(p -> p.source() instanceof ParamSource.DslContext);

        var builder = MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");

        // Pre-execution Jakarta validation. Emitted ahead of the try block so a Validator-side
        // throw still propagates to the wrapper's catch arm uniformly with the body's
        // exceptions; the body is never invoked when violations exist.
        if (errorChannel.isPresent() && hasValidationHandler(errorChannel.get())) {
            builder.addCode(validatorPreStep(ctx, method, errorChannel.get(), valueType, outputPackage));
        }

        builder.beginControlFlow("try");
        if (needsDsl) {
            builder.addStatement("$T dsl = $L.getDslContext(env)", dslContextClass, ctx.graphitronContextCall());
        }
        if (resultAssembly.isPresent()) {
            // "Service returns the domain object" shape: capture the service return in a typed
            // local and assemble the payload around it via a positional constructor walk.
            var ra = resultAssembly.get();
            builder.addStatement("$T __row = $T.$L($L)",
                ra.resultSlotType(),
                serviceClass,
                method.methodName(),
                ArgCallEmitter.buildMethodBackedCallArgs(ctx, method, null, conditionsClassName));
            builder.addCode(buildSuccessPayload(valueType, ra, errorChannel, "__row"));
        } else {
            // Legacy passthrough: service method returns the SDL payload class directly. The
            // emitter forwards the return value into the DataFetcherResult without assembly.
            builder.addStatement("$T payload = $T.$L($L)",
                valueType,
                serviceClass,
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
     * Emits the success-arm payload-construction block when a {@code ResultAssembly} is present
     * on a service-backed fetcher. Walks the constructor's slot indices
     * {@code 0..N-1} (where {@code N == 1 + ra.defaultedSlots().size()}) and prints, per slot:
     * the row local at {@code resultSlotIndex}, {@code List.of()} at the channel's
     * {@code errorsSlotIndex} when a channel is also present, and the slot's pre-resolved
     * {@code defaultLiteral} otherwise. The block declares a typed {@code payload} local that
     * the caller's {@link #returnSyncSuccess} subsequently wraps in the {@link DataFetcherResult}.
     */
    private static CodeBlock buildSuccessPayload(TypeName valueType,
                                                 no.sikt.graphitron.rewrite.model.ResultAssembly ra,
                                                 Optional<ErrorChannel> errorChannel,
                                                 String rowLocal) {
        int slotCount = 1 + ra.defaultedSlots().size();
        var defaultsByIndex = ra.defaultedSlots().stream()
            .collect(java.util.stream.Collectors.toMap(s -> s.index(), s -> s.defaultLiteral()));
        Integer errorsSlot = errorChannel.map(ErrorChannel::errorsSlotIndex).orElse(null);

        var ctor = CodeBlock.builder().add("$T payload = new $T(", valueType, valueType);
        for (int i = 0; i < slotCount; i++) {
            if (i > 0) ctor.add(", ");
            if (i == ra.resultSlotIndex()) {
                ctor.add(rowLocal);
            } else if (errorsSlot != null && i == errorsSlot) {
                ctor.add("$T.of()", LIST);
            } else {
                ctor.add(defaultsByIndex.get(i));
            }
        }
        ctor.add(");\n");
        return ctor.build();
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
     */
    private static CodeBlock validatorPreStep(TypeFetcherEmissionContext ctx, MethodRef method, ErrorChannel channel,
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

        var b = CodeBlock.builder();
        b.addStatement("$T __validator = $L.getValidator(env)", validator, ctx.graphitronContextCall());
        b.addStatement("$T __violations = new $T<>()", listOfErrors, arrayList);
        for (var p : method.params()) {
            if (!(p.source() instanceof ParamSource.Arg arg)) continue;
            String argName = arg.graphqlArgName();
            String local = "__arg_" + sanitizeIdent(argName);
            b.addStatement("$T $L = env.getArgument($S)", Object.class, local, argName);
            b.beginControlFlow("if ($L != null)", local);
            b.beginControlFlow("for ($T __v : __validator.validate($L))", violationWildcard, local);
            b.addStatement("__violations.add($T.toGraphQLError(__v, env, $S))",
                constraintViolations, argName);
            b.endControlFlow();
            b.endControlFlow();
        }
        b.beginControlFlow("if (!__violations.isEmpty())");
        b.add("return $T.<$T>newResult()\n", DATA_FETCHER_RESULT, boxed(valueType));
        b.add("    .data(").add(newPayloadFromErrors(channel, "__violations")).add(")\n");
        b.addStatement("    .build()");
        b.endControlFlow();
        return b.build();
    }

    /**
     * Synthesizes a direct {@code new <PayloadClass>(...)} expression where the errors slot is
     * bound to {@code errorsLocal} and every other slot prints its pre-resolved
     * {@link no.sikt.graphitron.rewrite.model.DefaultedSlot#defaultLiteral()}. Mirrors the
     * shape of {@link #payloadFactoryLambda} without wrapping in a lambda; used by the
     * validator pre-step where the violations list is already in scope.
     */
    private static CodeBlock newPayloadFromErrors(ErrorChannel channel, String errorsLocal) {
        var args = CodeBlock.builder();
        int slotCount = 1 + channel.defaultedSlots().size();
        var defaultsByIndex = channel.defaultedSlots().stream()
            .collect(java.util.stream.Collectors.toMap(s -> s.index(), s -> s.defaultLiteral()));
        for (int i = 0; i < slotCount; i++) {
            if (i > 0) args.add(", ");
            if (i == channel.errorsSlotIndex()) {
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
     * .returningResult(<keys or $fields>).fetchOne(...)}. See
     * {@code graphitron-rewrite/roadmap/mutations.md} Phase 3.
     *
     * <p>Empty-match semantics: {@code .fetchOne(...)} returns {@code null} when the WHERE clause
     * matches no row. graphql-java surfaces that as a GraphQL null on a nullable field, or a
     * non-null violation on {@code ID!}/{@code Type!}.
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "dml-mutation-shape-guarantees",
        reliesOn = "Pattern-matches f.returnExpression() with no instanceof / "
            + "Optional.orElseThrow / payloadAssembly().isPresent() guard; casts "
            + "env.getArgument(tia.name()) to Map<?,?> with no guard; walks tia.fields() "
            + "without an extraction-arm dispatch.")
    private static MethodSpec buildMutationDeleteFetcher(TypeFetcherEmissionContext ctx, MutationField.MutationDeleteTableField f,
                                                          String outputPackage) {
        var tia = f.tableInputArg();
        var tableRef = tia.inputTable();
        var tablesOnly = GeneratorUtils.ResolvedTableNames.ofTable(tableRef);
        String tableLocal = tablesOnly.tableLocalName();

        var dmlChain = CodeBlock.builder()
            .add(".deleteFrom($L)\n", tableLocal)
            .add(".where(").add(buildLookupWhere(tia, tablesOnly, tableRef)).add(")\n")
            .build();

        return buildDmlFetcher(ctx, f.name(), f.returnExpression(), f.errorChannel(),
            tia.name(), tableRef, tablesOnly, tableLocal,
            outputPackage, dmlChain);
    }

    /**
     * Emits a fetcher for {@link MutationField.MutationInsertTableField}: a synchronous static
     * method that runs {@code dsl.insertInto(table, cols...).values(vals...)
     * .returningResult(<keys or $fields>).fetchOne(...)}. See
     * {@code graphitron-rewrite/roadmap/mutations.md} Phase 2.
     *
     * <p>Column list is every {@code InputField.ColumnField} in {@code tia.fields()} in
     * declaration order; values list is parallel, with each value bound via
     * {@code DSL.val(in.get("name"), Tables.T.COL.getDataType())} (the two-argument form
     * delegates coercion to the column's registered jOOQ {@code Converter}). {@code @lookupKey}
     * fields are included verbatim — INSERT does not treat them specially. Phase 1B's
     * load-bearing guarantee that every input field is a {@code Direct}-extracted
     * {@code ColumnField} lets the loop walk {@code tia.fields()} with a single cast.
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "dml-mutation-shape-guarantees",
        reliesOn = "Pattern-matches f.returnExpression() with no instanceof / "
            + "Optional.orElseThrow / payloadAssembly().isPresent() guard; casts "
            + "env.getArgument(tia.name()) to Map<?,?> with no guard; walks tia.fields() "
            + "as Direct-extracted ColumnField with a single cast (no extraction-arm dispatch).")
    private static MethodSpec buildMutationInsertFetcher(TypeFetcherEmissionContext ctx, MutationField.MutationInsertTableField f,
                                                          String outputPackage) {
        var tia = f.tableInputArg();
        var tableRef = tia.inputTable();
        var tablesOnly = GeneratorUtils.ResolvedTableNames.ofTable(tableRef);
        String tableLocal = tablesOnly.tableLocalName();

        var fields = tia.fields();
        var colList = CodeBlock.builder();
        var valList = CodeBlock.builder();
        for (int i = 0; i < fields.size(); i++) {
            var cf = (InputField.ColumnField) fields.get(i);
            if (i > 0) {
                colList.add(", ");
                valList.add(",\n");
            }
            colList.add("$T.$L.$L",
                tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName());
            valList.add("$T.val(in.get($S), $T.$L.$L.getDataType())",
                DSL, cf.name(),
                tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName());
        }

        var dmlChain = CodeBlock.builder()
            .add(".insertInto($L, ", tableLocal).add(colList.build()).add(")\n")
            .add(".values(\n").indent().add(valList.build()).add(")\n").unindent()
            .build();

        return buildDmlFetcher(ctx, f.name(), f.returnExpression(), f.errorChannel(),
            tia.name(), tableRef, tablesOnly, tableLocal,
            outputPackage, dmlChain);
    }

    /**
     * Emits a fetcher for {@link MutationField.MutationUpdateTableField}: a synchronous static
     * method that runs {@code dsl.update(table).set(col, val)... .where(<lookupKey predicates>)
     * .returningResult(<keys or $fields>).fetchOne(...)}. See
     * {@code graphitron-rewrite/roadmap/mutations.md} Phase 4.
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
            + "env.getArgument(tia.name()) to Map<?,?> with no guard; walks tia.setFields() "
            + "as the typed non-@lookupKey ColumnField projection (no cast, no skip-during-walk). "
            + "Invariant #4 guarantees setFields() is non-empty for the SET clause.")
    private static MethodSpec buildMutationUpdateFetcher(TypeFetcherEmissionContext ctx, MutationField.MutationUpdateTableField f,
                                                          String outputPackage) {
        var tia = f.tableInputArg();
        var tableRef = tia.inputTable();
        var tablesOnly = GeneratorUtils.ResolvedTableNames.ofTable(tableRef);
        String tableLocal = tablesOnly.tableLocalName();

        var setClause = CodeBlock.builder();
        for (var cf : tia.setFields()) {
            setClause.add(".set($T.$L.$L, $T.val(in.get($S), $T.$L.$L.getDataType()))\n",
                tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName(),
                DSL, cf.name(),
                tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName());
        }

        var dmlChain = CodeBlock.builder()
            .add(".update($L)\n", tableLocal)
            .add(setClause.build())
            .add(".where(").add(buildLookupWhere(tia, tablesOnly, tableRef)).add(")\n")
            .build();

        return buildDmlFetcher(ctx, f.name(), f.returnExpression(), f.errorChannel(),
            tia.name(), tableRef, tablesOnly, tableLocal,
            outputPackage, dmlChain);
    }

    /**
     * Emits a fetcher for {@link MutationField.MutationUpsertTableField}: a synchronous static
     * method that runs {@code dsl.insertInto(table, cols...).values(vals...).onConflict(<keys>)
     * .doUpdate().set(col, val)... .returningResult(<keys or $fields>).fetchOne(...)}. See
     * {@code graphitron-rewrite/roadmap/mutations.md} Phase 5.
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
            + "env.getArgument(tia.name()) to Map<?,?> with no guard; walks tia.fields() "
            + "as Direct-extracted ColumnField with a single cast for the col/val lists, "
            + "and tia.setFields() (typed non-@lookupKey ColumnField projection) for the SET "
            + "clause and the .doUpdate()/.doNothing() dispatch. Invariant #3 guarantees "
            + "fieldBindings is non-empty (the ON CONFLICT key).")
    private static MethodSpec buildMutationUpsertFetcher(TypeFetcherEmissionContext ctx, MutationField.MutationUpsertTableField f,
                                                          String outputPackage) {
        var tia = f.tableInputArg();
        var tableRef = tia.inputTable();
        var tablesOnly = GeneratorUtils.ResolvedTableNames.ofTable(tableRef);
        String tableLocal = tablesOnly.tableLocalName();

        var fields = tia.fields();
        var colList = CodeBlock.builder();
        var valList = CodeBlock.builder();
        for (int i = 0; i < fields.size(); i++) {
            var cf = (InputField.ColumnField) fields.get(i);
            if (i > 0) {
                colList.add(", ");
                valList.add(",\n");
            }
            colList.add("$T.$L.$L",
                tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName());
            valList.add("$T.val(in.get($S), $T.$L.$L.getDataType())",
                DSL, cf.name(),
                tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName());
        }

        var setClause = CodeBlock.builder();
        for (var cf : tia.setFields()) {
            setClause.add(".set($T.$L.$L, $T.val(in.get($S), $T.$L.$L.getDataType()))\n",
                tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName(),
                DSL, cf.name(),
                tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName());
        }

        var conflictCols = CodeBlock.builder();
        var bindings = tia.fieldBindings();
        for (int i = 0; i < bindings.size(); i++) {
            if (i > 0) conflictCols.add(", ");
            conflictCols.add("$T.$L.$L",
                tablesOnly.tablesClass(), tableRef.javaFieldName(),
                bindings.get(i).targetColumn().javaName());
        }

        var dmlChain = CodeBlock.builder()
            .add(".insertInto($L, ", tableLocal).add(colList.build()).add(")\n")
            .add(".values(\n").indent().add(valList.build()).add(")\n").unindent()
            .add(".onConflict(").add(conflictCols.build()).add(")\n");
        if (!tia.setFields().isEmpty()) {
            dmlChain.add(".doUpdate()\n").add(setClause.build());
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
            outputPackage, dmlChain.build(), postDslGuard);
    }

    /**
     * Builds the WHERE clause from the TIA's {@code @lookupKey} {@code fieldBindings}, chaining
     * each binding's {@code .eq(DSL.val(...))} with {@code .and(...)}. Shared between DELETE
     * and UPDATE.
     */
    private static CodeBlock buildLookupWhere(
            no.sikt.graphitron.rewrite.ArgumentRef.InputTypeArg.TableInputArg tia,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef) {
        var whereExpr = CodeBlock.builder();
        var bindings = tia.fieldBindings();
        for (int i = 0; i < bindings.size(); i++) {
            var binding = bindings.get(i);
            if (i > 0) whereExpr.add(".and(");
            whereExpr.add("$T.$L.eq($T.val(in.get($S), $T.$L.getDataType()))",
                tablesOnly.tablesClass(), tableRef.javaFieldName() + "." + binding.targetColumn().javaName(),
                DSL,
                binding.fieldName(),
                tablesOnly.tablesClass(), tableRef.javaFieldName() + "." + binding.targetColumn().javaName());
            if (i > 0) whereExpr.add(")");
        }
        return whereExpr.build();
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
            CodeBlock dmlChain) {
        return buildDmlFetcher(ctx, fetcherName, rex, errorChannel, inputArgName, tableRef,
            tablesOnly, tableLocal, outputPackage, dmlChain, /*postDslGuard=*/ CodeBlock.of(""));
    }

    /**
     * Six-arg overload that admits an optional {@code postDslGuard} {@link CodeBlock} emitted
     * immediately after the {@code dsl} local is bound. Used by UPSERT to gate the Oracle
     * dialect (jOOQ silently translates {@code .onConflict(...)} to {@code MERGE INTO} on
     * Oracle, with semantics drift; see Phase 5 in {@code roadmap/mutations.md}).
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
            CodeBlock postDslGuard) {
        var dslContextClass = ClassName.get("org.jooq", "DSLContext");
        TypeName valueType = switch (rex) {
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.Payload p -> p.assembly().payloadClass();
            default -> ClassName.OBJECT;
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
        builder.addStatement("$T<?, ?> in = ($T<?, ?>) env.getArgument($S)", MAP, MAP, inputArgName);
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
     * {@code .returningResult(...).fetchOne(...)} (or {@code .returning().fetchOne()} +
     * payload-class constructor for the {@code Payload} arm).
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
                emitProjected(ps.returnTypeName(), valueType, outputPackage, tableLocal,
                    dmlChain, /*isList=*/ false);
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedList pl ->
                emitProjected(pl.returnTypeName(), valueType, outputPackage, tableLocal,
                    dmlChain, /*isList=*/ true);
            case no.sikt.graphitron.rewrite.model.DmlReturnExpression.Payload p ->
                emitPayload(p.assembly(), valueType, dmlChain);
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

    private static CodeBlock emitProjected(
            String returnTypeName, TypeName valueType,
            String outputPackage, String tableLocal,
            CodeBlock dmlChain, boolean isList) {
        // TableBoundReturnType: use Type.$fields(env.getSelectionSet(), table, env) and
        // return the row record. graphql-java's column fetchers walk it.
        var typeClass = ClassName.get(outputPackage + ".types", returnTypeName);
        var body = CodeBlock.builder()
            .add("$T payload = dsl\n", valueType).indent()
            .add(dmlChain)
            .add(".returningResult($T.$$fields(env.getSelectionSet(), $L, env))\n",
                typeClass, tableLocal)
            .add(isList ? ".fetch(r -> r);\n" : ".fetchOne(r -> r);\n").unindent();
        return body.build();
    }

    private static CodeBlock emitPayload(
            no.sikt.graphitron.rewrite.model.PayloadAssembly assembly,
            TypeName valueType,
            CodeBlock dmlChain) {
        // ResultReturnType payload: capture the row record from .returning().fetchOne(),
        // then construct the payload by walking constructor slots positionally (row local at
        // rowSlotIndex, defaultLiteral at every defaultedSlot; the errors slot, if any, appears
        // in defaultedSlots with a "null" literal). The payload-shape classifier guarantees one
        // row slot exists and rejects list-payload returns at validateReturnType, so
        // .fetchOne() is the only shape here.
        var dml = CodeBlock.builder()
            .add("$T row = dsl\n", assembly.rowSlotType()).indent()
            .add(dmlChain)
            .add(".returning()\n")
            .add(".fetchOne();\n").unindent();

        var ctor = CodeBlock.builder().add("$T payload = new $T(", valueType, valueType);
        int slotCount = 1 + assembly.defaultedSlots().size();
        var defaultsByIndex = assembly.defaultedSlots().stream()
            .collect(java.util.stream.Collectors.toMap(s -> s.index(), s -> s.defaultLiteral()));
        for (int i = 0; i < slotCount; i++) {
            if (i > 0) ctor.add(", ");
            if (i == assembly.rowSlotIndex()) {
                ctor.add("row");
            } else {
                ctor.add(defaultsByIndex.get(i));
            }
        }
        ctor.add(");\n");
        return dml.add(ctor.build()).build();
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
     * input ordering. See {@code docs/argument-resolution.md} Phase 1 for design rationale.
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
     * <p>The data fetcher's return type is {@code CompletableFuture<V>} for all four
     * {@link BatchKey} variants: {@code loader.load(key, env)} returns
     * {@code CompletableFuture<V>} regardless of whether the underlying batch loader is
     * positional ({@code List<V>}) or mapped ({@code Map<K, V>}); the DataLoader unwraps both
     * shapes internally and fulfills each per-key promise.
     *
     * <p>List/connection: returns {@code CompletableFuture<List<Record>>}. Single: returns
     * {@code CompletableFuture<Record>}.
     *
     * <p>Variant axis:
     * <ul>
     *   <li>{@link BatchKey.RowKeyed}/{@link BatchKey.RecordKeyed} → {@code newDataLoader(...)}
     *       binds to {@code BatchLoaderWithContext<K, V>}; lambda keys parameter is
     *       {@code List<KeyType>}.</li>
     *   <li>{@link BatchKey.MappedRowKeyed}/{@link BatchKey.MappedRecordKeyed}/{@link
     *       BatchKey.MappedTableRecordKeyed} → {@code newMappedDataLoader(...)} binds to
     *       {@code MappedBatchLoaderWithContext<K, V>}; lambda keys parameter is
     *       {@code Set<KeyType>}.</li>
     * </ul>
     */
    private static MethodSpec buildServiceDataFetcher(
            TypeFetcherEmissionContext ctx,
            String fieldName,
            BatchKeyField bkf,
            MethodRef smr,
            ReturnTypeRef returnType,
            TableRef prt,
            TypeName perKeyType,
            String className,
            String outputPackage) {

        boolean isList = returnType.wrapper().isList();
        TypeName valueType = isList ? ParameterizedTypeName.get(LIST, perKeyType) : perKeyType;

        var batchKey = (BatchKey.ParentKeyed) bkf.batchKey();
        boolean isMapped = batchKey instanceof BatchKey.MappedRowKeyed
                        || batchKey instanceof BatchKey.MappedRecordKeyed
                        || batchKey instanceof BatchKey.MappedTableRecordKeyed;
        TypeName keyType = batchKey.keyElementType();
        var loaderType = ParameterizedTypeName.get(DATA_LOADER, keyType, valueType);
        String rowsMethodName = bkf.rowsMethodName();

        String factoryMethod = isMapped ? "newMappedDataLoader" : "newDataLoader";
        TypeName lambdaKeysType = ParameterizedTypeName.get(isMapped ? SET : LIST, keyType);
        var lambdaBlock = CodeBlock.builder()
            .add("($T keys, $T batchEnv) -> {\n", lambdaKeysType, BATCH_LOADER_ENV)
            .indent()
            .addStatement("$T dfe = ($T) batchEnv.getKeyContextsList().get(0)", ENV, ENV)
            .addStatement("return $T.completedFuture($L(keys, dfe))", COMPLETABLE_FUTURE, rowsMethodName)
            .unindent()
            .add("}")
            .build();

        var methodBuilder = MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(asyncResultType(valueType))
            .addParameter(ENV, "env")
            .addCode(buildDataLoaderName(ctx))
            .addCode(
                "$T loader = env.getDataLoaderRegistry()\n" +
                "    .computeIfAbsent(name, k -> $T.$L($L));\n",
                loaderType, DATA_LOADER_FACTORY, factoryMethod, lambdaBlock);

        methodBuilder.addCode(GeneratorUtils.buildKeyExtraction(batchKey, prt));
        return methodBuilder
            .addCode(CodeBlock.builder()
                .add("return loader.load(key, env)\n")
                .add("    ").add(asyncWrapTail(valueType, outputPackage, Optional.empty())).add(";\n")
                .build())
            .build();
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
     *   <li>{@link BatchKey.RowKeyed}/{@link BatchKey.RecordKeyed}/{@link BatchKey.TableRecordKeyed}:
     *       {@code keys} is {@code List<KeyType>}; return is {@code List<List<V>>} (list field) or
     *       {@code List<V>} (single).</li>
     *   <li>{@link BatchKey.MappedRowKeyed}/{@link BatchKey.MappedRecordKeyed}/{@link
     *       BatchKey.MappedTableRecordKeyed}: {@code keys} is {@code Set<KeyType>}; return is
     *       {@code Map<KeyType, List<V>>} (list field) or {@code Map<KeyType, V>} (single).</li>
     * </ul>
     *
     * <p>{@code V} is {@code org.jooq.Record} for {@code ServiceTableField} (caller passes
     * {@code RECORD}) and the per-key element type for {@code ServiceRecordField} (caller
     * passes {@code srf.elementType()}).
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "service-directive-resolver-strict-child-service-return",
        reliesOn = "Emits `return ServiceClass.method(<args>);` against a structurally-typed "
            + "rows-method return (Map<K, V>/List<List<V>>/List<V>) without a defensive cast or "
            + "wildcard local. ServiceDirectiveResolver's child-only strict-return check "
            + "rejects developer methods whose declared return type doesn't match this exact "
            + "outer shape, so any mismatch surfaces at classify time rather than as a javac "
            + "error on the generated source. Covers RowKeyed / RecordKeyed / MappedRowKeyed / "
            + "MappedRecordKeyed and TableRecordKeyed / MappedTableRecordKeyed for typed jOOQ "
            + "TableRecord sources.")
    private static MethodSpec buildServiceRowsMethod(
            TypeFetcherEmissionContext ctx,
            BatchKeyField bkf,
            MethodRef method,
            ReturnTypeRef schemaReturnType,
            TypeName perKeyType,
            String parentTypeName,
            String outputPackage) {

        var batchKey = (BatchKey.ParentKeyed) bkf.batchKey();
        boolean isMapped = batchKey instanceof BatchKey.MappedRowKeyed
                        || batchKey instanceof BatchKey.MappedRecordKeyed
                        || batchKey instanceof BatchKey.MappedTableRecordKeyed;
        TypeName keysContainerType = ParameterizedTypeName.get(isMapped ? SET : LIST, batchKey.keyElementType());
        TypeName returnType = no.sikt.graphitron.rewrite.model.RowsMethodShape
            .outerRowsReturnType(perKeyType, schemaReturnType, batchKey);

        var dslContextClass = ClassName.get("org.jooq", "DSLContext");
        var serviceClass = ClassName.bestGuess(method.className());
        String conditionsClassName = outputPackage + ".conditions."
            + parentTypeName + QueryConditionsGenerator.CLASS_NAME_SUFFIX;
        boolean needsDsl = method.params().stream()
            .anyMatch(p -> p.source() instanceof ParamSource.DslContext);

        var builder = MethodSpec.methodBuilder(bkf.rowsMethodName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(returnType)
            .addParameter(keysContainerType, "keys")
            .addParameter(ENV, "env");

        if (needsDsl) {
            builder.addStatement("$T dsl = $L.getDslContext(env)", dslContextClass, ctx.graphitronContextCall());
        }
        builder.addStatement("return $T.$L($L)",
            serviceClass,
            method.methodName(),
            ArgCallEmitter.buildMethodBackedCallArgs(ctx, method, null, CodeBlock.of("keys"), conditionsClassName));

        return builder.build();
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
     * mis-cited {@code DataFetchingFieldSelectionSet.getField(String)} API — also absent. Phase 2b
     * drops both: the rows method takes only {@code (List<KeyType>, DataFetchingEnvironment)}, and
     * uses {@code env.getSelectionSet()} directly for projection (which is semantically identical
     * to {@code sel.getSelectionSet()} when {@code sel} is the field being fetched).
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
        var returnType = ParameterizedTypeName.get(COMPLETABLE_FUTURE, valueType);

        var batchKey = (BatchKey.ParentKeyed) bkf.batchKey();
        TypeName keyType = batchKey.keyElementType();
        var loaderType = ParameterizedTypeName.get(DATA_LOADER, keyType, valueType);
        String rowsMethodName = bkf.rowsMethodName();
        String fieldName = bkfFieldName(bkf);

        // Lambda parameters are explicitly typed. The target-typed inference otherwise picks
        // `List<Object>` — the call `rowsMethodName(keys, dfe)` then can't narrow to
        // `List<RowN<...>>`. Typing one lambda parameter requires typing both per Java lambda
        // syntax rules.
        TypeName lambdaKeysType = ParameterizedTypeName.get(LIST, keyType);
        var lambdaBlock = CodeBlock.builder()
            .add("($T keys, $T batchEnv) -> {\n", lambdaKeysType, BATCH_LOADER_ENV)
            .indent()
            .addStatement("$T dfe = ($T) batchEnv.getKeyContextsList().get(0)", ENV, ENV)
            .addStatement("return $T.completedFuture($L(keys, dfe))", COMPLETABLE_FUTURE, rowsMethodName)
            .unindent()
            .add("}")
            .build();

        var methodBuilder = MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(asyncResultType(valueType))
            .addParameter(ENV, "env")
            .addCode(buildDataLoaderName(ctx))
            .addCode(
                "$T loader = env.getDataLoaderRegistry()\n" +
                "    .computeIfAbsent(name, k -> $T.newDataLoader($L));\n",
                loaderType, DATA_LOADER_FACTORY, lambdaBlock);

        // Single cardinality: NULL-FK short-circuit. The parent row's FK column may be nullable,
        // and no `terminal.pk = parentInput.fk_value` match can exist under ANSI NULL semantics —
        // skip the DataLoader round-trip and return null directly.
        if (isList) {
            methodBuilder.addCode(GeneratorUtils.buildKeyExtraction(batchKey, parentTable));
        } else {
            methodBuilder.addCode(GeneratorUtils.buildKeyExtractionWithNullCheck(batchKey, parentTable));
        }
        return methodBuilder
            .addCode(CodeBlock.builder()
                .add("return loader.load(key, env)\n")
                .add("    ").add(asyncWrapTail(valueType, outputPackage, Optional.empty())).add(";\n")
                .build())
            .build();
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
     *            {@code batchKey()} and {@code rowsMethodName()}).
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "accessor-rowkey-cardinality-matches-field",
        reliesOn = "FieldBuilder.deriveBatchKeyFromTypedAccessor produces AccessorKeyedMany "
            + "only on list fields and AccessorKeyedSingle only on single fields. The valueType "
            + "rule below (field.emitsSingleRecordPerKey() → Record else List<Record>) folds "
            + "the two cases that emit a per-key value of Record (LOAD_MANY's loadMany "
            + "contract; single-cardinality LOAD_ONE). An AccessorKeyedMany on a non-list "
            + "field would emit code expecting List<Record> from a loadMany that supplies "
            + "Record, miscompiling generated *Fetchers.")
    private static <T extends ChildField.TableTargetField & BatchKeyField> MethodSpec
            buildRecordBasedDataFetcher(TypeFetcherEmissionContext ctx, T field, BatchKey.RecordParentBatchKey batchKey,
                    GraphitronType.ResultType resultType, String outputPackage) {

        boolean isList = field.returnType().wrapper().isList();
        BatchKey.LoaderDispatch dispatch = batchKey.dispatch();

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

        TypeName keyType = batchKey.keyElementType();
        var loaderType = ParameterizedTypeName.get(DATA_LOADER, keyType, valueType);
        String rowsMethodName = field.rowsMethodName();

        TypeName lambdaKeysType = ParameterizedTypeName.get(LIST, keyType);
        var lambdaBlock = CodeBlock.builder()
            .add("($T keys, $T batchEnv) -> {\n", lambdaKeysType, BATCH_LOADER_ENV)
            .indent()
            .addStatement("$T dfe = ($T) batchEnv.getKeyContextsList().get(0)", ENV, ENV)
            .addStatement("return $T.completedFuture($L(keys, dfe))", COMPLETABLE_FUTURE, rowsMethodName)
            .unindent()
            .add("}")
            .build();

        // Dispatch: LOAD_ONE emits load(key, env); LOAD_MANY emits loadMany(keys, ...). The
        // matching key local is emitted by buildRecordParentKeyExtraction (which also reads
        // the dispatch projection so the local name and the dispatch shape agree).
        //
        // DataLoader.loadMany overload that takes key contexts requires a List<Object> of the
        // same arity as the keys list; the batch loader only ever reads keyContexts[0] (see
        // the lambda above), so duplicating env across all positions is the cheapest way to
        // wire the env through. load(key, env) takes a single Object keyContext directly, no
        // list-wrapping needed.
        String dispatchCall = switch (dispatch) {
            case LOAD_ONE  -> "return loader.load(key, env)\n";
            case LOAD_MANY -> "return loader.loadMany(keys, java.util.Collections.nCopies(keys.size(), env))\n";
        };

        return MethodSpec.methodBuilder(field.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(asyncResultType(resultValueType))
            .addParameter(ENV, "env")
            .addCode(buildDataLoaderName(ctx))
            .addCode(
                "$T loader = env.getDataLoaderRegistry()\n" +
                "    .computeIfAbsent(name, k -> $T.newDataLoader($L));\n",
                loaderType, DATA_LOADER_FACTORY, lambdaBlock)
            .addCode(GeneratorUtils.buildRecordParentKeyExtraction(batchKey, resultType))
            .addCode(CodeBlock.builder()
                .add(dispatchCall)
                .add("    ").add(asyncWrapTail(resultValueType, outputPackage, Optional.empty())).add(";\n")
                .build())
            .build();
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
        return errorChannel
            .map(channel -> dispatchCatchArm(outputPackage, channel))
            .orElseGet(() -> redactCatchArm(outputPackage));
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
     * that binds the errors slot at {@link ErrorChannel#errorsSlotIndex()}.
     */
    private static CodeBlock dispatchCatchArm(String outputPackage, ErrorChannel channel) {
        return CodeBlock.builder()
            .add("return $T.dispatch(\n", errorRouterClass(outputPackage))
            .add("    e,\n")
            .add("    $T.$L,\n", errorMappingsClass(outputPackage), channel.mappingsConstantName())
            .add("    env,\n")
            .add("    ").add(payloadFactoryLambda(channel)).add(");\n")
            .build();
    }

    /**
     * Synthesizes the {@code (errors) -> new <PayloadClass>(...)} factory lambda. Walks the
     * constructor's parameter indices {@code 0..N-1} (where {@code N == 1 + defaultedSlots.size()}):
     * at {@link ErrorChannel#errorsSlotIndex()} prints the lambda parameter, at every other
     * slot prints the pre-resolved {@link no.sikt.graphitron.rewrite.model.DefaultedSlot#defaultLiteral()}.
     */
    private static CodeBlock payloadFactoryLambda(ErrorChannel channel) {
        var args = CodeBlock.builder();
        int slotCount = 1 + channel.defaultedSlots().size();
        var defaultsByIndex = channel.defaultedSlots().stream()
            .collect(java.util.stream.Collectors.toMap(s -> s.index(), s -> s.defaultLiteral()));
        for (int i = 0; i < slotCount; i++) {
            if (i > 0) args.add(", ");
            if (i == channel.errorsSlotIndex()) {
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
        var routerCall = errorChannel
            .map(channel -> CodeBlock.builder()
                .add("$T.dispatch(t, $T.$L, env, ",
                    errorRouterClass(outputPackage),
                    errorMappingsClass(outputPackage),
                    channel.mappingsConstantName())
                .add(payloadFactoryLambda(channel))
                .add(")")
                .build())
            .orElseGet(() -> CodeBlock.of("$T.redact(t, env)", errorRouterClass(outputPackage)));
        return CodeBlock.builder()
            .add(".thenApply(payload -> $T.<$T>newResult().data(payload).build())\n",
                DATA_FETCHER_RESULT, boxed(valueType))
            .add(".exceptionally(t -> ").add(routerCall).add(")")
            .build();
    }

}
