package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
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
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;
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

    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage, String jooqPackage) {
        var result = new ArrayList<TypeSpec>(schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof GraphitronType.TableType
                      || e.getValue() instanceof GraphitronType.NodeType
                      || e.getValue() instanceof GraphitronType.RootType
                      || e.getValue() instanceof GraphitronType.ResultType)
            .map(Map.Entry::getKey)
            .sorted()
            .map(typeName -> generateForType(schema, typeName, outputPackage, jooqPackage))
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
                    collectNestedFetcherClasses(nf, seenNestedTypes, result, outputPackage, jooqPackage);
                }
            }));
        return result;
    }

    private static void collectNestedFetcherClasses(ChildField.NestingField nf,
            Set<String> seen, List<TypeSpec> out, String outputPackage, String jooqPackage) {
        var nestedTypeName = nf.returnType().returnTypeName();
        if (seen.add(nestedTypeName)) {
            var batchKeyFields = nf.nestedFields().stream()
                .filter(f -> f instanceof BatchKeyField)
                .map(f -> (GraphitronField) f)
                .sorted(Comparator.comparing(GraphitronField::name))
                .toList();
            if (!batchKeyFields.isEmpty()) {
                out.add(generateTypeSpec(nestedTypeName, nf.returnType().table(), null, batchKeyFields, outputPackage, jooqPackage));
            }
        }
        for (var nested : nf.nestedFields()) {
            if (nested instanceof ChildField.NestingField innerNf) {
                collectNestedFetcherClasses(innerNf, seen, out, outputPackage, jooqPackage);
            }
        }
    }

    private static TypeSpec generateForType(GraphitronSchema schema, String typeName, String outputPackage, String jooqPackage) {
        var type = schema.type(typeName);
        var fields = schema.fieldsOf(typeName).stream()
            .filter(f -> !(f instanceof GraphitronField.UnclassifiedField))
            .sorted(Comparator.comparing(GraphitronField::name))
            .toList();
        TableRef parentTable = type instanceof GraphitronType.TableBackedType tbt ? tbt.table() : null;
        GraphitronType.ResultType resultType = type instanceof GraphitronType.ResultType rt ? rt : null;
        return generateTypeSpec(typeName, parentTable, resultType, fields, outputPackage, jooqPackage);
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
     * Together with {@link #NOT_IMPLEMENTED_REASONS}{@code .keySet()} and {@link #NOT_DISPATCHED_LEAVES}
     * this forms an exhaustive, disjoint partition of every sealed leaf of {@link GraphitronField};
     * enforced by {@code GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus}.
     * Moving an entry from {@link #NOT_IMPLEMENTED_REASONS} to this set is the expected review signal
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
        MutationField.MutationDeleteTableField.class,
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
     * {@link #NOT_IMPLEMENTED_REASONS}{@code .keySet()}, this forms an exhaustive four-way
     * disjoint partition of every {@link GraphitronField} sealed leaf; enforced by
     * {@code GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus}.
     */
    public static final Set<Class<? extends GraphitronField>> PROJECTED_LEAVES = Set.of(
        ChildField.TableField.class,
        ChildField.LookupTableField.class,
        ChildField.CompositeColumnField.class,
        ChildField.NestingField.class);

    /**
     * Maps each unimplemented field variant class to the reason string that the generated stub
     * includes in its {@link UnsupportedOperationException} message.
     *
     * <p>Consumed by {@code GraphitronSchemaValidator} (P2 #4) via
     * {@code NOT_IMPLEMENTED_REASONS.keySet()} to produce a build-time error rather than a
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
    public static final Map<Class<? extends GraphitronField>, String> NOT_IMPLEMENTED_REASONS =
        Map.ofEntries(
            // QueryField stubs
            Map.entry(QueryField.QueryInterfaceField.class,
                "QueryInterfaceField not yet implemented — see graphitron-rewrite/roadmap/stub-interface-union-fetchers.md"),
            Map.entry(QueryField.QueryUnionField.class,
                "QueryUnionField not yet implemented — see graphitron-rewrite/roadmap/stub-interface-union-fetchers.md"),
            // MutationField stubs — see graphitron-rewrite/roadmap/mutations.md
            Map.entry(MutationField.MutationInsertTableField.class,
                "Mutation insert not yet implemented — see graphitron-rewrite/roadmap/mutations.md"),
            Map.entry(MutationField.MutationUpdateTableField.class,
                "Mutation update not yet implemented — see graphitron-rewrite/roadmap/mutations.md"),
            Map.entry(MutationField.MutationUpsertTableField.class,
                "Mutation upsert not yet implemented — see graphitron-rewrite/roadmap/mutations.md"),
            Map.entry(MutationField.MutationServiceTableField.class,
                "MutationServiceTableField not yet implemented — see graphitron-rewrite/roadmap/mutations.md"),
            Map.entry(MutationField.MutationServiceRecordField.class,
                "MutationServiceRecordField not yet implemented — see graphitron-rewrite/roadmap/mutations.md"),
            // ChildField stubs — TableTargetField sub-hierarchy
            // (ChildField.TableField and ChildField.LookupTableField are in PROJECTED_LEAVES —
            // inline emission via TypeClassGenerator.$fields; see G5 and argres Phase 2a)
            // ChildField stubs — remaining direct permits
            Map.entry(ChildField.ColumnReferenceField.class,
                "ColumnReferenceField not yet implemented — see graphitron-rewrite/roadmap/column-reference-on-scalar-field.md"),
            Map.entry(ChildField.CompositeColumnReferenceField.class,
                "CompositeColumnReferenceField (rooted-at-parent NodeId reference) not yet implemented — JOIN-with-projection emission lands in R50 phase b2b paired with the rooted-at-parent fixture in phase g; see graphitron-rewrite/roadmap/lift-nodeid-out-of-model.md"),
            Map.entry(ChildField.TableMethodField.class,
                "TableMethodField not yet implemented — see graphitron-rewrite/roadmap/tablemethod-scalar-return.md"),
            Map.entry(ChildField.InterfaceField.class,
                "InterfaceField not yet implemented — see graphitron-rewrite/roadmap/stub-interface-union-fetchers.md"),
            Map.entry(ChildField.UnionField.class,
                "UnionField not yet implemented — see graphitron-rewrite/roadmap/stub-interface-union-fetchers.md"),
            Map.entry(ChildField.MultitableReferenceField.class,
                "MultitableReferenceField not yet implemented — see graphitron-rewrite/roadmap/multitable-reference-on-scalar.md")
        );

    /**
     * Overload for tests and callers that don't need to specify a {@link GraphitronType.ResultType}.
     * Delegates to the 6-arg form with {@code resultType = null} and empty package strings.
     */
    static TypeSpec generateTypeSpec(String typeName, TableRef parentTable, List<GraphitronField> fields) {
        return generateTypeSpec(typeName, parentTable, null, fields, "", "");
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
            String outputPackage, String jooqPackage) {
        var className = typeName + "Fetchers";
        var builder = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC);

        // Emit the graphitronContext() helper whenever any field executes inline SQL —
        // i.e., whenever any SqlGeneratingField is present, or a DML mutation that emits
        // its own jOOQ statement inline (e.g. MutationDeleteTableField).
        boolean needsGraphitronContextHelper = fields.stream().anyMatch(f ->
            f instanceof SqlGeneratingField
            || f instanceof MutationField.MutationDeleteTableField);

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
                        .of(lookupTableRef, qlf.returnType().returnTypeName(), outputPackage, jooqPackage).jooqTableClass();
                    builder.addMethod(buildQueryLookupFetcher(qlf, outputPackage));
                    builder.addMethod(buildQueryLookupRowsMethod(qlf, outputPackage, jooqPackage));
                    builder.addMethod(LookupValuesJoinEmitter.buildInputRowsMethod(qlf, lookupTableClass));
                }
                case QueryField.QueryTableField qtf -> {
                    if (qtf.returnType().wrapper() instanceof FieldWrapper.Connection) {
                        builder.addMethod(buildQueryConnectionFetcher(qtf, outputPackage, jooqPackage));
                    } else {
                        builder.addMethod(buildQueryTableFetcher(qtf, outputPackage, jooqPackage));
                    }
                }
                case ChildField.ServiceTableField stf -> {
                    builder.addMethod(buildServiceDataFetcher(stf.name(), stf, stf.method(), stf.returnType(), parentTable, RECORD, className, jooqPackage, outputPackage));
                    builder.addMethod(buildServiceRowsMethod(stf, stf.method(), stf.returnType(), RECORD, stf.parentTypeName(), outputPackage));
                }
                case ChildField.ServiceRecordField srf -> {
                    builder.addMethod(buildServiceDataFetcher(srf.name(), srf, srf.method(), srf.returnType(), parentTable, srf.elementType(), className, jooqPackage, outputPackage));
                    builder.addMethod(buildServiceRowsMethod(srf, srf.method(), srf.returnType(), srf.elementType(), srf.parentTypeName(), outputPackage));
                }
                case ChildField.SplitTableField stf -> {
                    builder.addMethod(buildSplitQueryDataFetcher(stf, stf.returnType(), parentTable, outputPackage, jooqPackage));
                    builder.addMethod(SplitRowsMethodEmitter.buildRowsMethod(stf, outputPackage, jooqPackage));
                }
                case ChildField.SplitLookupTableField slf -> {
                    builder.addMethod(buildSplitQueryDataFetcher(slf, slf.returnType(), parentTable, outputPackage, jooqPackage));
                    builder.addMethod(SplitRowsMethodEmitter.buildRowsMethod(slf, outputPackage, jooqPackage));
                    // Emit the VALUES-building input-rows helper alongside the rows method.
                    // Phase 2a's env-based variant (buildInputRowsMethod) reads args from
                    // env.getArgument(name) — correct for a Split* fetcher whose @lookupKey args
                    // live on the field itself (vs. Phase 2a's inline child-lookup path where
                    // args live on a parent's SelectedField).
                    if (slf.lookupMapping() instanceof no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) {
                        var lookupTableRef = slf.returnType().table();
                        var lookupTableClass = GeneratorUtils.ResolvedTableNames
                            .of(lookupTableRef, slf.returnType().returnTypeName(), outputPackage, jooqPackage).jooqTableClass();
                        builder.addMethod(LookupValuesJoinEmitter.buildInputRowsMethod(slf, lookupTableClass));
                    }
                }
                case QueryField.QueryNodeField f              -> builder.addMethod(buildQueryNodeFetcher(f, outputPackage));
                case QueryField.QueryNodesField f             -> builder.addMethod(buildQueryNodesFetcher(f, outputPackage));
                case QueryField.QueryTableMethodTableField f  -> builder.addMethod(buildQueryTableMethodFetcher(f, outputPackage, jooqPackage));
                case QueryField.QueryServiceTableField f      -> builder.addMethod(buildQueryServiceTableFetcher(f, outputPackage, jooqPackage));
                case QueryField.QueryServiceRecordField f     -> builder.addMethod(buildQueryServiceRecordFetcher(f, outputPackage));
                // Stub variants — see NOT_IMPLEMENTED_REASONS
                case QueryField.QueryTableInterfaceField f    -> builder.addMethod(buildQueryTableInterfaceFieldFetcher(f, outputPackage, jooqPackage));
                case QueryField.QueryInterfaceField f         -> builder.addMethod(stub(f));
                case QueryField.QueryUnionField f             -> builder.addMethod(stub(f));
                case MutationField.MutationInsertTableField f  -> builder.addMethod(stub(f));
                case MutationField.MutationUpdateTableField f  -> builder.addMethod(stub(f));
                case MutationField.MutationDeleteTableField f  -> builder.addMethod(buildMutationDeleteFetcher(f, outputPackage, jooqPackage));
                case MutationField.MutationUpsertTableField f  -> builder.addMethod(stub(f));
                case MutationField.MutationServiceTableField f -> builder.addMethod(stub(f));
                case MutationField.MutationServiceRecordField f -> builder.addMethod(stub(f));
                case ChildField.ColumnReferenceField f          -> {
                    if (f.compaction() instanceof CallSiteCompaction.NodeIdEncodeKeys) {
                        // Reference-side NodeId carrier: no fetcher method. The DataFetcher value
                        // is the runtime stub emitted by FetcherEmitter (rooted-at-parent emission
                        // ships in R50 phase b2b).
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
                case ChildField.TableInterfaceField f           -> builder.addMethod(buildTableInterfaceFieldFetcher(f, outputPackage, jooqPackage));
                case ChildField.RecordTableField rtf -> {
                    builder.addMethod(buildRecordBasedDataFetcher(rtf, resultType, jooqPackage, outputPackage));
                    builder.addMethod(SplitRowsMethodEmitter.buildRowsMethod(rtf, outputPackage, jooqPackage));
                }
                case ChildField.RecordLookupTableField rltf -> {
                    builder.addMethod(buildRecordBasedDataFetcher(rltf, resultType, jooqPackage, outputPackage));
                    builder.addMethod(SplitRowsMethodEmitter.buildRowsMethod(rltf, outputPackage, jooqPackage));
                    // Input-rows helper identical in shape to SplitLookupTableField's — reads
                    // @lookupKey args from env.getArgument(name) and emits the typed Row<M+1>[].
                    if (rltf.lookupMapping() instanceof no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) {
                        var lookupTableRef = rltf.returnType().table();
                        var lookupTableClass = GeneratorUtils.ResolvedTableNames
                            .of(lookupTableRef, rltf.returnType().returnTypeName(), outputPackage, jooqPackage).jooqTableClass();
                        builder.addMethod(LookupValuesJoinEmitter.buildInputRowsMethod(rltf, lookupTableClass));
                    }
                }
                case ChildField.TableMethodField f              -> builder.addMethod(stub(f));
                case ChildField.InterfaceField f                -> builder.addMethod(stub(f));
                case ChildField.UnionField f                    -> builder.addMethod(stub(f));
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

        if (needsGraphitronContextHelper) {
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
                var names = GeneratorUtils.ResolvedTableNames.of(tableRef, qtf.returnType().returnTypeName(), outputPackage, jooqPackage);
                builder.addMethod(buildOrderByHelperMethod(qtf.name(), arg, names, tableRef, outputPackage));
            } else if (field instanceof ChildField.SplitTableField stf
                    && stf.returnType().wrapper() instanceof FieldWrapper.Connection
                    && stf.orderBy() instanceof OrderBySpec.Argument arg) {
                var tableRef = stf.returnType().table();
                var names = GeneratorUtils.ResolvedTableNames.of(tableRef, stf.returnType().returnTypeName(), outputPackage, jooqPackage);
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
        // null where no match) rather than List<List<Record>>. Gated on any single-cardinality
        // Split* field in the class.
        boolean hasSingleSplitField = fields.stream().anyMatch(f ->
            f instanceof ChildField.SplitTableField stf
                && !stf.returnType().wrapper().isList());
        if (hasSingleSplitField) {
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
    private static MethodSpec buildQueryTableFetcher(QueryField.QueryTableField qtf, String outputPackage, String jooqPackage) {
        var tableRef = qtf.returnType().table();
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, qtf.returnType().returnTypeName(), outputPackage, jooqPackage);
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
            builder.addStatement("$T dsl = graphitronContext(env).getDslContext(env)", dslContextClass);
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
            builder.addStatement("$T dsl = graphitronContext(env).getDslContext(env)", dslContextClass);
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
            QueryField.QueryTableInterfaceField qtif, String outputPackage, String jooqPackage) {
        var tableRef = qtif.returnType().table();
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, qtif.returnType().returnTypeName(), outputPackage, jooqPackage);
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

        var dslContextClass = ClassName.get("org.jooq", "DSLContext");

        if (isList) {
            builder.addCode(buildOrderByCode(qtif.orderBy(), qtif.name(), tableLocal));
            builder.addStatement("$T dsl = graphitronContext(env).getDslContext(env)", dslContextClass);
            builder.addCode(CodeBlock.builder()
                .add("$T payload = dsl\n", valueType)
                .indent()
                .add(".select(new $T<>(fields))\n", ArrayList.class)
                .add(".from($L)\n", tableLocal)
                .add(".where(condition)\n")
                .add(".orderBy(orderBy)\n")
                .add(".fetch();\n")
                .unindent()
                .build());
        } else {
            builder.addStatement("$T dsl = graphitronContext(env).getDslContext(env)", dslContextClass);
            builder.addCode(CodeBlock.builder()
                .add("$T payload = dsl\n", valueType)
                .indent()
                .add(".select(new $T<>(fields))\n", ArrayList.class)
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
            ChildField.TableInterfaceField tif, String outputPackage, String jooqPackage) {
        var tableRef = tif.returnType().table();
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, tif.returnType().returnTypeName(), outputPackage, jooqPackage);
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
        builder.addStatement("$T dsl = graphitronContext(env).getDslContext(env)", dslContextClass);

        // Build join-path condition. Only single-hop FkJoin is supported for now; multi-hop and
        // ConditionJoin paths are caught at classification time.
        builder.addCode(buildJoinPathCondition(tif.joinPath(), tableRef.tableName()));
        builder.addCode(buildDiscriminatorFilter(tif.discriminatorColumn(), tif.knownDiscriminatorValues()));
        builder.addCode(buildInterfaceFieldsList(tif.participants(), tif.discriminatorColumn(), tableLocal, outputPackage));

        if (isList) {
            builder.addCode(buildOrderByCode(tif.orderBy(), tif.name(), tableLocal));
            builder.addCode(CodeBlock.builder()
                .add("$T payload = dsl\n", valueType)
                .indent()
                .add(".select(new $T<>(fields))\n", ArrayList.class)
                .add(".from($L)\n", tableLocal)
                .add(".where(condition)\n")
                .add(".orderBy(orderBy)\n")
                .add(".fetch();\n")
                .unindent()
                .build());
        } else {
            builder.addCode(CodeBlock.builder()
                .add("$T payload = dsl\n", valueType)
                .indent()
                .add(".select(new $T<>(fields))\n", ArrayList.class)
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
    private static MethodSpec buildQueryTableMethodFetcher(QueryField.QueryTableMethodTableField qtmtf,
                                                            String outputPackage, String jooqPackage) {
        var tableRef = qtmtf.returnType().table();
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, qtmtf.returnType().returnTypeName(), outputPackage, jooqPackage);
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
            ArgCallEmitter.buildMethodBackedCallArgs(qtmtf.method(), tableExpression, conditionsClassName));

        builder.addStatement("$T dsl = graphitronContext(env).getDslContext(env)", dslContextClass);
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
    private static MethodSpec buildQueryServiceTableFetcher(QueryField.QueryServiceTableField qstf,
                                                             String outputPackage, String jooqPackage) {
        var tableRef = qstf.returnType().table();
        var recordClass = ClassName.get(jooqPackage + ".tables.records",
            tableRef.javaClassName() + "Record");
        boolean isList = qstf.returnType().wrapper().isList();
        TypeName returnType = isList
            ? ParameterizedTypeName.get(RESULT, recordClass)
            : recordClass;
        return buildServiceFetcherCommon(qstf.name(), qstf.method(), qstf.parentTypeName(),
            returnType, outputPackage);
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
    private static MethodSpec buildQueryServiceRecordFetcher(QueryField.QueryServiceRecordField qsrf,
                                                              String outputPackage) {
        TypeName returnType = computeServiceRecordReturnType(qsrf);
        return buildServiceFetcherCommon(qsrf.name(), qsrf.method(), qsrf.parentTypeName(),
            returnType, outputPackage);
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
     * Shared body shape for {@link #buildQueryServiceTableFetcher} and
     * {@link #buildQueryServiceRecordFetcher}: optional {@code dsl} local + direct
     * {@code return ServiceClass.method(<args>);}.
     */
    private static MethodSpec buildServiceFetcherCommon(String fieldName, MethodRef method,
                                                        String parentTypeName, TypeName valueType,
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

        builder.beginControlFlow("try");
        if (needsDsl) {
            builder.addStatement("$T dsl = graphitronContext(env).getDslContext(env)", dslContextClass);
        }
        builder.addStatement("$T payload = $T.$L($L)",
            valueType,
            serviceClass,
            method.methodName(),
            ArgCallEmitter.buildMethodBackedCallArgs(method, null, conditionsClassName));
        builder.addCode(returnSyncSuccess(valueType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(redactCatchArm(outputPackage));
        builder.endControlFlow();

        return builder.build();
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
    private static MethodSpec buildMutationDeleteFetcher(MutationField.MutationDeleteTableField f,
                                                          String outputPackage, String jooqPackage) {
        var tia = f.tableInputArg();
        var tableRef = tia.inputTable();
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, f.returnType().returnTypeName(), outputPackage, jooqPackage);
        var dslContextClass = ClassName.get("org.jooq", "DSLContext");

        TypeName valueType = ClassName.OBJECT;
        var builder = MethodSpec.methodBuilder(f.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");

        builder.beginControlFlow("try");
        builder.addStatement("$T dsl = graphitronContext(env).getDslContext(env)", dslContextClass);
        builder.addStatement("$T<?, ?> in = ($T<?, ?>) env.getArgument($S)", MAP, MAP, tia.name());

        // WHERE predicate: chain bindings with .and(...)
        var whereExpr = CodeBlock.builder();
        var bindings = tia.fieldBindings();
        for (int i = 0; i < bindings.size(); i++) {
            var binding = bindings.get(i);
            if (i > 0) whereExpr.add(".and(");
            whereExpr.add("$T.$L.eq($T.val(in.get($S), $T.$L.getDataType()))",
                names.tablesClass(), tableRef.javaFieldName() + "." + binding.targetColumn().javaName(),
                DSL,
                binding.fieldName(),
                names.tablesClass(), tableRef.javaFieldName() + "." + binding.targetColumn().javaName());
            if (i > 0) whereExpr.add(")");
        }

        var body = CodeBlock.builder()
            .add("$T payload = dsl\n", valueType).indent()
            .add(".deleteFrom($T.$L)\n", names.tablesClass(), tableRef.javaFieldName())
            .add(".where(").add(whereExpr.build()).add(")\n");

        boolean isList = f.returnType().wrapper().isList();
        if (f.returnType() instanceof ReturnTypeRef.ScalarReturnType) {
            // ID return: project the NodeType's key columns and call the per-type encoder helper
            // resolved by the classifier (encode<TypeName>(v0, v1, ...)). The typeId is baked into
            // the method name; no generic encode(typeId, ...) call is emitted from the rewrite.
            var encode = f.encodeReturn().orElseThrow();
            var keyCols = encode.paramSignature();

            body.add(".returningResult(");
            for (int i = 0; i < keyCols.size(); i++) {
                if (i > 0) body.add(", ");
                body.add("$T.$L.$L", names.tablesClass(), tableRef.javaFieldName(), keyCols.get(i).javaName());
            }
            body.add(")\n");

            var lambda = CodeBlock.builder().add("r -> $T.$L(", encode.encoderClass(), encode.methodName());
            for (int i = 0; i < keyCols.size(); i++) {
                if (i > 0) lambda.add(", ");
                var col = keyCols.get(i);
                lambda.add("r.get($T.$L.$L)", names.tablesClass(), tableRef.javaFieldName(), col.javaName());
            }
            lambda.add(")");
            body.add(isList ? ".fetch(" : ".fetchOne(").add(lambda.build()).add(");\n");
        } else {
            // TableBoundReturnType: use Type.$fields(env.getSelectionSet(), table, env) and
            // return the row record. graphql-java's column fetchers walk it.
            String tableLocal = names.tableLocalName();
            // Need a local variable for the table to pass to $fields(...). Inject above the
            // dsl.deleteFrom(...) chain : but we've already started the chain. Re-build the body.
            body = CodeBlock.builder();
            body.add("$T $L = $T.$L;\n", names.jooqTableClass(), tableLocal, names.tablesClass(), tableRef.javaFieldName());
            body.add("$T payload = dsl\n", valueType).indent()
                .add(".deleteFrom($L)\n", tableLocal)
                .add(".where(").add(whereExpr.build()).add(")\n")
                .add(".returningResult($T.$$fields(env.getSelectionSet(), $L, env))\n",
                    names.typeClass(), tableLocal)
                .add(isList ? ".fetch(r -> r);\n" : ".fetchOne(r -> r);\n");
        }
        body.unindent();
        builder.addCode(body.build());
        builder.addCode(returnSyncSuccess(valueType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(redactCatchArm(outputPackage));
        builder.endControlFlow();
        return builder.build();
    }

    /**
     * Generates a connection field fetcher that returns a {@code ConnectionResult}.
     *
     * <p>Extracts all four Relay pagination args, validates that {@code first} and {@code last}
     * are not both supplied, decodes cursor using column metadata, builds condition and orderBy,
     * reverses ordering for backward pagination, executes inline paginated SQL with
     * name-based extra-field deduplication, and wraps the result in a {@code ConnectionResult}.
     */
    private static MethodSpec buildQueryConnectionFetcher(QueryField.QueryTableField qtf, String outputPackage, String jooqPackage) {
        var tableRef = qtf.returnType().table();
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, qtf.returnType().returnTypeName(), outputPackage, jooqPackage);
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
        builder.addStatement("$T dsl = graphitronContext(env).getDslContext(env)", dslContextClass);

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
    private static MethodSpec buildQueryLookupFetcher(QueryField.QueryLookupTableField field, String outputPackage) {
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
    private static MethodSpec buildQueryLookupRowsMethod(QueryField.QueryLookupTableField field, String outputPackage, String jooqPackage) {
        var tableRef = field.returnType().table();
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, field.returnType().returnTypeName(), outputPackage, jooqPackage);

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
            var callArgs = ArgCallEmitter.buildCallArgs(filter.callParams(), filter.className(), tableLocal);
            builder.addStatement("condition = condition.and($T.$L($L))",
                ClassName.bestGuess(filter.className()), filter.methodName(), callArgs);
        }

        var typeFieldsCall = CodeBlock.of("$T.$$fields(env.getSelectionSet(), $L, env)",
            names.typeClass(), tableLocal);
        builder.addCode(LookupValuesJoinEmitter.buildFetcherBody(field, typeFieldsCall, tableLocal));
        return builder.build();
    }

    /**
     * Generates a stub method that throws {@link UnsupportedOperationException} with the
     * reason string from {@link #NOT_IMPLEMENTED_REASONS}. Fails fast with
     * {@link AssertionError} if the class is not in the map, which means the switch arm
     * is missing a map entry.
     */
    private static MethodSpec buildQueryNodeFetcher(QueryField.QueryNodeField field, String outputPackage) {
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

    private static MethodSpec buildQueryNodesFetcher(QueryField.QueryNodesField field, String outputPackage) {
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
                .add("    ").add(asyncWrapTail(valueType, outputPackage)).add(";\n")
                .build())
            .build();
    }

    private static MethodSpec stub(GraphitronField field) {
        var reason = Objects.requireNonNull(
            NOT_IMPLEMENTED_REASONS.get(field.getClass()),
            () -> "No stub reason registered for " + field.getClass().getSimpleName()
                  + " — either implement a real generator branch or add an entry to NOT_IMPLEMENTED_REASONS");
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
     *   <li>{@link BatchKey.MappedRowKeyed}/{@link BatchKey.MappedRecordKeyed} →
     *       {@code newMappedDataLoader(...)} binds to {@code MappedBatchLoaderWithContext<K, V>};
     *       lambda keys parameter is {@code Set<KeyType>}.</li>
     * </ul>
     */
    private static MethodSpec buildServiceDataFetcher(
            String fieldName,
            BatchKeyField bkf,
            MethodRef smr,
            ReturnTypeRef returnType,
            TableRef prt,
            TypeName perKeyType,
            String className, String jooqPackage,
            String outputPackage) {

        boolean isList = returnType.wrapper().isList();
        TypeName valueType = isList ? ParameterizedTypeName.get(LIST, perKeyType) : perKeyType;

        var batchKey = bkf.batchKey();
        boolean isMapped = batchKey instanceof BatchKey.MappedRowKeyed
                        || batchKey instanceof BatchKey.MappedRecordKeyed;
        TypeName keyType = GeneratorUtils.keyElementType(batchKey);
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
            .addCode(buildDataLoaderName())
            .addCode(
                "$T loader = env.getDataLoaderRegistry()\n" +
                "    .computeIfAbsent(name, k -> $T.$L($L));\n",
                loaderType, DATA_LOADER_FACTORY, factoryMethod, lambdaBlock);

        methodBuilder.addCode(GeneratorUtils.buildKeyExtraction(batchKey, prt, jooqPackage));
        return methodBuilder
            .addCode(CodeBlock.builder()
                .add("return loader.load(key, env)\n")
                .add("    ").add(asyncWrapTail(valueType, outputPackage)).add(";\n")
                .build())
            .build();
    }

    /**
     * Generates a stub rows method for a {@link ChildField.ServiceTableField}.
     *
     * <p>Currently a stub — throws {@link UnsupportedOperationException}. The signature
     * ({@code keys, env, sel}) is correct and matches the DataLoader lambda that calls it.
     * The body will be filled in when service field execution is implemented; it will call
     * the service method and then {@code Type.$fields(sel.getSelectionSet(), table, env)}
     * for projection.
     *
     * <p>Signature follows the batch-loader contract:
     * <ul>
     *   <li>{@link BatchKey.RowKeyed}/{@link BatchKey.RecordKeyed}: {@code keys} is
     *       {@code List<KeyType>}; return is {@code List<List<Record>>} (list field) or
     *       {@code List<Record>} (single).</li>
     *   <li>{@link BatchKey.MappedRowKeyed}/{@link BatchKey.MappedRecordKeyed}: {@code keys}
     *       is {@code Set<KeyType>}; return is {@code Map<KeyType, List<Record>>} (list field)
     *       or {@code Map<KeyType, Record>} (single).</li>
     * </ul>
     */
    private static MethodSpec buildServiceRowsMethod(
            BatchKeyField bkf,
            MethodRef method,
            ReturnTypeRef schemaReturnType,
            TypeName perKeyType,
            String parentTypeName,
            String outputPackage) {

        boolean isList = schemaReturnType.wrapper().isList();
        boolean isMapped = bkf.batchKey() instanceof BatchKey.MappedRowKeyed
                        || bkf.batchKey() instanceof BatchKey.MappedRecordKeyed;
        TypeName keysElementType = GeneratorUtils.keyElementType(bkf.batchKey());

        TypeName keysContainerType = ParameterizedTypeName.get(isMapped ? SET : LIST, keysElementType);

        TypeName valuePerKey = isList ? ParameterizedTypeName.get(LIST, perKeyType) : perKeyType;
        TypeName returnType = isMapped
            ? ParameterizedTypeName.get(MAP, keysElementType, valuePerKey)
            : (isList ? ParameterizedTypeName.get(LIST, ParameterizedTypeName.get(LIST, perKeyType)) : ParameterizedTypeName.get(LIST, perKeyType));

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
            builder.addStatement("$T dsl = graphitronContext(env).getDslContext(env)", dslContextClass);
        }
        // Sources param passes through `keys` directly. Element-shape conversion (RowN -> TableRecord
        // when the developer's signature takes Set<TableRecord>/List<TableRecord>) is deferred —
        // the classifier accepts both shapes today, but the conversion path is a separate emitter
        // concern (R32 §2). Until that lands, signatures using TableRecord as the Sources element
        // type compile against `keys` only when the lambda key type matches; mismatches surface
        // as javac errors at the call site.
        builder.addStatement("return $T.$L($L)",
            serviceClass,
            method.methodName(),
            ArgCallEmitter.buildMethodBackedCallArgs(method, null, CodeBlock.of("keys"), conditionsClassName));

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
            BatchKeyField bkf,
            ReturnTypeRef.TableBoundReturnType tb,
            TableRef parentTable, String outputPackage, String jooqPackage) {

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

        var batchKey = bkf.batchKey();
        TypeName keyType = GeneratorUtils.keyElementType(batchKey);
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
            .addCode(buildDataLoaderName())
            .addCode(
                "$T loader = env.getDataLoaderRegistry()\n" +
                "    .computeIfAbsent(name, k -> $T.newDataLoader($L));\n",
                loaderType, DATA_LOADER_FACTORY, lambdaBlock);

        // Single cardinality: NULL-FK short-circuit. The parent row's FK column may be nullable,
        // and no `terminal.pk = parentInput.fk_value` match can exist under ANSI NULL semantics —
        // skip the DataLoader round-trip and return null directly.
        if (isList) {
            methodBuilder.addCode(GeneratorUtils.buildKeyExtraction(batchKey, parentTable, jooqPackage));
        } else {
            methodBuilder.addCode(GeneratorUtils.buildKeyExtractionWithNullCheck(batchKey, parentTable, jooqPackage));
        }
        return methodBuilder
            .addCode(CodeBlock.builder()
                .add("return loader.load(key, env)\n")
                .add("    ").add(asyncWrapTail(valueType, outputPackage)).add(";\n")
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
    private static CodeBlock buildDataLoaderName() {
        return CodeBlock.builder()
            .addStatement("$T name = graphitronContext(env).getTenantId(env) + $S + $T.join($S, env.getExecutionStepInfo().getPath().getKeysOnly())",
                String.class, "/", String.class, "/")
            .build();
    }

    /**
     * Builds the DataFetcher method for a record-parent batched field
     * ({@link ChildField.RecordTableField}, {@link ChildField.RecordLookupTableField}). Shape is
     * identical to {@link #buildSplitQueryDataFetcher} except key extraction uses
     * {@link GeneratorUtils#buildRecordKeyExtraction} (backing-object accessor) instead of
     * {@link GeneratorUtils#buildKeyExtraction} (jOOQ table-row accessor).
     *
     * @param <T> the concrete field type — must implement both {@link ChildField.TableTargetField}
     *            (for {@code returnType()} and {@code name()}) and {@link BatchKeyField} (for
     *            {@code batchKey()} and {@code rowsMethodName()}).
     */
    private static <T extends ChildField.TableTargetField & BatchKeyField> MethodSpec
            buildRecordBasedDataFetcher(T field, GraphitronType.ResultType resultType, String jooqPackage,
                    String outputPackage) {

        boolean isList = field.returnType().wrapper().isList();
        TypeName valueType = isList ? ParameterizedTypeName.get(LIST, RECORD) : RECORD;

        var batchKey = field.batchKey();
        TypeName keyType = GeneratorUtils.keyElementType(batchKey);
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

        return MethodSpec.methodBuilder(field.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(asyncResultType(valueType))
            .addParameter(ENV, "env")
            .addCode(buildDataLoaderName())
            .addCode(
                "$T loader = env.getDataLoaderRegistry()\n" +
                "    .computeIfAbsent(name, k -> $T.newDataLoader($L));\n",
                loaderType, DATA_LOADER_FACTORY, lambdaBlock)
            .addCode(GeneratorUtils.buildRecordKeyExtraction((BatchKey.RowKeyed) batchKey, resultType, jooqPackage))
            .addCode(CodeBlock.builder()
                .add("return loader.load(key, env)\n")
                .add("    ").add(asyncWrapTail(valueType, outputPackage)).add(";\n")
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
    // R12 §3 fetcher try/catch wrap helpers (C3d).
    //
    // Every emitted fetcher returns DataFetcherResult<P> (sync) or
    // CompletableFuture<DataFetcherResult<P>> (async). The success arm wraps the
    // produced payload; the catch arm funnels thrown exceptions through
    // ErrorRouter.redact (no-channel disposition; C3g will swap to dispatch when
    // the field carries an Optional<ErrorChannel>).
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

    /**
     * Builds the standard catch arm for a synchronous fetcher: redact the throw via the
     * {@code ErrorRouter} emitted at {@code <outputPackage>.schema.ErrorRouter}.
     *
     * <p>Used by every sync fetcher builder after emitting the success-path
     * {@code return DataFetcherResult.<P>newResult().data(payload).build()}.
     */
    private static CodeBlock redactCatchArm(String outputPackage) {
        return CodeBlock.of("return $T.redact(e, env);\n", errorRouterClass(outputPackage));
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
     * payload into a {@code DataFetcherResult<P>}, then {@code .exceptionally(...)} to redact
     * any exception that escapes past the synchronous wrapper (DataLoader bookkeeping, etc.).
     *
     * <p>Spec: §3 "CompletionException unwrap and async fetcher path".
     */
    private static CodeBlock asyncWrapTail(TypeName valueType, String outputPackage) {
        return CodeBlock.builder()
            .add(".thenApply(payload -> $T.<$T>newResult().data(payload).build())\n",
                DATA_FETCHER_RESULT, boxed(valueType))
            .add(".exceptionally(t -> $T.redact(t, env))",
                errorRouterClass(outputPackage))
            .build();
    }

}
