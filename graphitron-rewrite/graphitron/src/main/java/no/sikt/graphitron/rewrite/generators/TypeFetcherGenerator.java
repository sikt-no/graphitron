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
import no.sikt.graphitron.rewrite.generators.schema.OutcomeClassGenerator;
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
import no.sikt.graphitron.rewrite.model.DialectRequirement;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.SqlDialectFamily;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.InputColumnBinding;
import no.sikt.graphitron.rewrite.model.InputColumnBindingGroup;
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
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
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
 *   <li>{@link ChildField.ColumnField} — a reified {@code public static} source-only read method
 *       (collected from {@link FetcherEmitter#bind}), registered wrapped in
 *       {@code new LightFetcher<>(<Type>Fetchers::column)}. {@code LightFetcher} implements
 *       {@link graphql.schema.LightDataFetcher} so the runtime uses the lighter call path while
 *       the read stays a findable per-field symbol.</li>
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
            // R303: a nested type that owns any fetcher gets a <Type>Fetchers class carrying every
            // field's method (the method-backed ones the switch emits, plus the reads bind()
            // reifies). The gate is shared with FetcherRegistrationsEmitter.nestedBody via
            // FetcherEmitter.nestedTypeOwnsFetchers so the reference site and the emit site agree.
            var nestedFields = nf.nestedFields().stream()
                .map(f -> (GraphitronField) f)
                .sorted(Comparator.comparing(GraphitronField::name))
                .toList();
            if (FetcherEmitter.nestedTypeOwnsFetchers(nestedFields)) {
                out.add(generateTypeSpec(nestedTypeName, nf.returnType().table(), null, nestedFields, assembled, outputPackage));
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
        return generateTypeSpec(typeName, parentTable, resultType, fields, assembled, outputPackage, schema);
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
        QueryField.QueryRoutineTableField.class,
        QueryField.QueryServiceTableField.class,
        QueryField.QueryServiceRecordField.class,
        QueryField.QueryServicePolymorphicField.class,
        MutationField.MutationInsertTableField.class,
        MutationField.MutationUpdateTableField.class,
        MutationField.MutationDeleteTableField.class,
        MutationField.MutationUpsertTableField.class,
        MutationField.MutationDmlRecordField.class,
        MutationField.MutationBulkDmlRecordField.class,
        MutationField.MutationUpdatePayloadField.class,
        MutationField.MutationBulkUpdatePayloadField.class,
        MutationField.MutationDeletePayloadField.class,
        MutationField.MutationBulkDeletePayloadField.class,
        MutationField.MutationServiceTableField.class,
        MutationField.MutationServiceRecordField.class,
        MutationField.MutationServicePolymorphicField.class,
        ChildField.ServiceTableField.class,
        ChildField.ServiceRecordField.class,
        ChildField.SplitTableField.class,
        ChildField.SplitLookupTableField.class,
        ChildField.PropertyField.class,
        ChildField.RecordField.class,
        ChildField.RecordCompositeField.class,
        ChildField.RecordTableField.class,
        ChildField.RecordLookupTableField.class,
        ChildField.SingleRecordIdField.class,
        ChildField.SingleRecordIdFieldFromReturning.class,
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
        InputField.NestingField.class,
        InputField.UnboundField.class);

    /**
     * Leaves whose SELECT projection is emitted inline by {@link TypeClassGenerator}'s
     * {@code $fields} method, so the dispatch switch emits no fetcher method for them (post-R303
     * the read of the projected value is reified by {@code FetcherEmitter.bind} and collected
     * below the switch). Together with
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
            // (ChildField.ColumnReferenceField is in PROJECTED_LEAVES; per-shape deferrals enforced
            // by validateColumnReferenceField.)
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
     * @param resultType  the resolved {@link GraphitronType.ResultType} for class-backed parents,
     *                    or {@code null} for table-backed and root types
     * @param fields      the classified fields belonging to this type
     */
    static TypeSpec generateTypeSpec(String typeName, TableRef parentTable,
            GraphitronType.ResultType resultType, List<GraphitronField> fields,
            graphql.schema.GraphQLSchema assembled,
            String outputPackage) {
        return generateTypeSpec(typeName, parentTable, resultType, fields, assembled, outputPackage, null);
    }

    /**
     * Canonical form. {@code graphitronSchema} is the classified schema, threaded so the R389
     * joined-table interface fetcher can read each participant's classified fields; {@code null}
     * for unit-tier model-only and nested-type callers (which never emit a joined-table interface).
     */
    static TypeSpec generateTypeSpec(String typeName, TableRef parentTable,
            GraphitronType.ResultType resultType, List<GraphitronField> fields,
            graphql.schema.GraphQLSchema assembled,
            String outputPackage,
            GraphitronSchema graphitronSchema) {
        var className = typeName + "Fetchers";
        var builder = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC);
        // R303: the class this type's reified fetcher reads are referenced through (e.g.
        // FilmFetchers::title). Only the reified method is collected below; the registration value
        // FetcherEmitter pairs with it is emitted by FetcherRegistrationsEmitter, not here.
        var reifiedFetchersClass = ClassName.get(outputPackage + ".fetchers", className);

        // Per-class scratchpad for deferred helper-method emission. Every emitter that writes a
        // graphitronContext(env) call obtains the CodeBlock through ctx.graphitronContextCall(),
        // which records the dependency; class assembly drains the set below to decide which
        // helper methods to materialise. Replaces a previous post-scan that string-grepped
        // method bodies for the literal "graphitronContext(env)".
        var ctx = new TypeFetcherEmissionContext(assembled, typeName, graphitronSchema);

        // R268: when this type is a flipped Outcome payload (it owns a WrapperArm errors field), its
        // children receive a non-null Outcome as env.getSource(). DataLoader-backed data fields
        // (RecordTableField / RecordLookupTableField / RecordTableMethodField) arm-switch inside
        // their generated fetcher method: narrow Success, read the key off success.value(), and
        // return completedFuture(null) on the ErrorList arm. The same predicate drives the
        // registration-site routing in FetcherRegistrationsEmitter; FetcherEmitter.hasWrapperArmErrors
        // is the single home so the two sites cannot drift.
        boolean sourceIsOutcome = FetcherEmitter.hasWrapperArmErrors(fields);

        // One decode-helper registry per <Type>Fetchers class: split rows-method and lookup-rows
        // filter sites that decode a @nodeId argument lift a per-class private static helper through
        // it. collectInto co-locates construct and drain onto this class's builder so a lifted
        // helper can never be silently dropped.
        CompositeDecodeHelperRegistry.collectInto(builder, outputPackage, registry -> {
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
                    // The reified source-only read is collected below via FetcherEmitter.bind
                    // (registered wrapped in LightFetcher); this arm emits no method itself.
                }
                case QueryField.QueryLookupTableField qlf -> {
                    var lookupTableRef = qlf.returnType().table();
                    var lookupTableClass = GeneratorUtils.ResolvedTableNames
                        .of(lookupTableRef, qlf.returnType().returnTypeName(), outputPackage).jooqTableClass();
                    builder.addMethod(buildQueryLookupFetcher(ctx, qlf, outputPackage));
                    builder.addMethod(buildQueryLookupRowsMethod(ctx, qlf, outputPackage, registry));
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
                    // R285: lift-back projection. The loader value is the projected Record (carrying
                    // the multiset @reference columns), not the developer-returned XRecord; the lift
                    // rows-method calls the service, then re-projects the returned records by identity
                    // through Type.$fields(...). See SplitRowsMethodEmitter.buildServiceTableLift.
                    var stfService = (MethodRef.Service) stf.method();
                    String stfConditionsClass = outputPackage + ".conditions."
                        + stf.parentTypeName() + QueryConditionsGenerator.CLASS_NAME_SUFFIX;
                    CodeBlock stfServiceCall = CodeBlock.of("$L.$L($L)",
                        serviceCallTarget(stfService, ClassName.bestGuess(stf.method().className())),
                        stf.method().methodName(),
                        ArgCallEmitter.buildMethodBackedCallArgs(ctx, stf.method(), null, CodeBlock.of("keys"), stfConditionsClass));
                    builder.addMethod(buildServiceDataFetcher(ctx, stf.name(), stf, stf.method(), stf.returnType(), parentTable, RECORD, className, outputPackage, stf.errorChannel()));
                    builder.addMethod(SplitRowsMethodEmitter.buildServiceTableLift(ctx, stf, stfServiceCall, outputPackage));
                }
                case ChildField.ServiceRecordField srf -> {
                    builder.addMethod(buildServiceDataFetcher(ctx, srf.name(), srf, srf.method(), srf.returnType(), parentTable, srf.elementType(), className, outputPackage, srf.errorChannel()));
                    builder.addMethod(buildServiceRowsMethod(ctx, srf, srf.method(), srf.returnType(), srf.elementType(), srf.parentTypeName(), outputPackage));
                }
                case ChildField.SplitTableField stf -> {
                    builder.addMethod(buildSplitQueryDataFetcher(ctx, stf, stf.returnType(), parentTable, outputPackage));
                    builder.addMethod(SplitRowsMethodEmitter.buildForSplitTable(ctx, stf, outputPackage, registry));
                }
                case ChildField.SplitLookupTableField slf -> {
                    builder.addMethod(buildSplitQueryDataFetcher(ctx, slf, slf.returnType(), parentTable, outputPackage));
                    builder.addMethod(SplitRowsMethodEmitter.buildForSplitLookupTable(ctx, slf, outputPackage, registry));
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
                case QueryField.QueryRoutineTableField f      -> builder.addMethod(buildQueryRoutineFetcher(ctx, f, outputPackage));
                case QueryField.QueryServiceTableField f      -> builder.addMethod(buildQueryServiceTableFetcher(ctx, f, outputPackage));
                case QueryField.QueryServiceRecordField f     -> builder.addMethod(buildQueryServiceRecordFetcher(ctx, f, outputPackage));
                case QueryField.QueryServicePolymorphicField f ->
                    MultiTablePolymorphicEmitter
                        .emitServiceMethods(ctx, f.name(), f.serviceMethodCall(), f.participants(),
                            f.returnType().wrapper().isList(), outputPackage)
                        .forEach(builder::addMethod);
                // Stub variants — see STUBBED_VARIANTS
                case QueryField.QueryTableInterfaceField f    -> builder.addMethod(buildQueryTableInterfaceFieldFetcher(ctx, f, outputPackage));
                case QueryField.QueryInterfaceField f -> {
                    var participantFilters = participantFiltersByTypename(f.participantFilters());
                    if (f.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn) {
                        MultiTablePolymorphicEmitter
                            .emitConnectionMethods(ctx, f.name(), f.participants(), participantFilters, Map.of(),
                                conn.defaultPageSize(), null, null, outputPackage)
                            .forEach(builder::addMethod);
                    } else {
                        MultiTablePolymorphicEmitter
                            .emitMethods(ctx, f.name(), f.participants(), participantFilters, f.returnType().wrapper().isList(), outputPackage)
                            .forEach(builder::addMethod);
                    }
                }
                case QueryField.QueryUnionField f -> {
                    var participantFilters = participantFiltersByTypename(f.participantFilters());
                    if (f.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn) {
                        MultiTablePolymorphicEmitter
                            .emitConnectionMethods(ctx, f.name(), f.participants(), participantFilters, Map.of(),
                                conn.defaultPageSize(), null, null, outputPackage)
                            .forEach(builder::addMethod);
                    } else {
                        MultiTablePolymorphicEmitter
                            .emitMethods(ctx, f.name(), f.participants(), participantFilters, f.returnType().wrapper().isList(), outputPackage)
                            .forEach(builder::addMethod);
                    }
                }
                case MutationField.MutationInsertTableField f  -> builder.addMethod(buildMutationInsertFetcher(ctx, f, outputPackage));
                case MutationField.MutationUpdateTableField f  -> builder.addMethod(buildMutationUpdateFetcher(ctx, f, outputPackage));
                case MutationField.MutationDeleteTableField f  -> builder.addMethod(buildMutationDeleteFetcher(ctx, f, outputPackage));
                case MutationField.MutationUpsertTableField f  -> builder.addMethod(buildMutationUpsertFetcher(ctx, f, outputPackage));
                case MutationField.MutationServiceTableField f -> builder.addMethod(buildMutationServiceTableFetcher(ctx, f, outputPackage));
                case MutationField.MutationServiceRecordField f -> builder.addMethod(buildMutationServiceRecordFetcher(ctx, f, outputPackage));
                case MutationField.MutationServicePolymorphicField f ->
                    MultiTablePolymorphicEmitter
                        .emitServiceMethods(ctx, f.name(), f.serviceMethodCall(), f.participants(),
                            f.returnType().wrapper().isList(), outputPackage)
                        .forEach(builder::addMethod);
                case MutationField.MutationDmlRecordField f    -> builder.addMethod(buildMutationDmlRecordFetcher(ctx, f, outputPackage));
                case MutationField.MutationBulkDmlRecordField f -> builder.addMethod(buildMutationBulkDmlRecordFetcher(ctx, f, outputPackage));
                case MutationField.MutationUpdatePayloadField f -> builder.addMethod(buildMutationUpdatePayloadFetcher(ctx, f, outputPackage));
                case MutationField.MutationBulkUpdatePayloadField f -> builder.addMethod(buildMutationBulkUpdatePayloadFetcher(ctx, f, outputPackage));
                case MutationField.MutationDeletePayloadField f -> builder.addMethod(buildMutationDeletePayloadFetcher(ctx, f, outputPackage));
                case MutationField.MutationBulkDeletePayloadField f -> builder.addMethod(buildMutationBulkDeletePayloadFetcher(ctx, f, outputPackage));
                // ColumnReferenceField: inline projection via TypeClassGenerator.$fields (Direct
                // compaction); the read of that aliased projection is reified by FetcherEmitter.bind
                // and collected below. The validator rejects the NodeIdEncodeKeys and ConditionJoin
                // shapes ahead of generation; no per-shape carve-out is needed here.
                case ChildField.ColumnReferenceField ignored    -> { }
                case ChildField.CompositeColumnReferenceField f -> builder.addMethod(stub(f));
                // ChildField.TableField / LookupTableField / CompositeColumnField: inline projection
                // via TypeClassGenerator.$fields; the read (alias pickup, or composite-key NodeId
                // encode) is reified by FetcherEmitter.bind and collected below.
                case ChildField.TableField ignored              -> { }
                case ChildField.LookupTableField ignored        -> { }
                case ChildField.CompositeColumnField ignored    -> { }
                case ChildField.TableInterfaceField f           -> builder.addMethod(buildTableInterfaceFieldFetcher(ctx, f, outputPackage));
                // ParticipantColumnReferenceField: the value is materialised in the parent record by
                // the enclosing TableInterfaceField fetcher's conditional LEFT JOIN; the read of it
                // back is reified by FetcherEmitter.bind into a named source-only method (wrapped in
                // LightFetcher), collected below. No-op arm here.
                case ChildField.ParticipantColumnReferenceField ignored -> { }
                case ChildField.RecordTableField rtf -> {
                    builder.addMethod(buildRecordBasedDataFetcher(ctx, rtf, rtf.returnType(), rtf.sourceKey(), resultType, sourceIsOutcome, outputPackage));
                    builder.addMethod(SplitRowsMethodEmitter.buildForRecordTable(ctx, rtf, outputPackage, registry));
                }
                case ChildField.RecordLookupTableField rltf -> {
                    builder.addMethod(buildRecordBasedDataFetcher(ctx, rltf, rltf.returnType(), rltf.sourceKey(), resultType, sourceIsOutcome, outputPackage));
                    builder.addMethod(SplitRowsMethodEmitter.buildForRecordLookupTable(ctx, rltf, outputPackage, registry));
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
                    builder.addMethod(buildRecordBasedDataFetcher(ctx, rtmf, rtmf.returnType(), rtmf.sourceKey(), resultType, sourceIsOutcome, outputPackage));
                    builder.addMethod(SplitRowsMethodEmitter.buildForRecordTableMethod(ctx, rtmf, outputPackage));
                }
                // R156 — SingleRecordIdFieldFromReturning: the PK column read (+ optional NodeId
                // encode) is reified by FetcherEmitter.bind into a named (DataFetchingEnvironment
                // env) method, collected below. No-op arm here.
                case ChildField.SingleRecordIdFieldFromReturning ignored -> { }
                // R275 — the @service-carrier ID sibling: the Outcome/source narrowing + node-key
                // read + NodeId encode is likewise reified by FetcherEmitter.bind into a named env
                // method, collected below. No-op arm here.
                case ChildField.SingleRecordIdField ignored -> { }
                case ChildField.InterfaceField f -> {
                    if (f.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn) {
                        MultiTablePolymorphicEmitter
                            .emitConnectionMethods(ctx, f.name(), f.participants(), Map.of(), f.participantJoinPaths(),
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
                            .emitConnectionMethods(ctx, f.name(), f.participants(), Map.of(), f.participantJoinPaths(),
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
                case ChildField.NestingField ignored            -> { /* source passthrough reified by FetcherEmitter.bind, collected below */ }
                // ServiceRecordField is dispatched alongside ServiceTableField above (shared
                // emitters parameterised by perKeyType). The "no-op" arm here keeps the switch
                // exhaustive without re-emitting; the variant has IMPLEMENTED_LEAVES membership.
                case ChildField.RecordField ignored             -> { /* accessor / column read reified by FetcherEmitter.bind, collected below */ }
                // R329 — the @service record-composite carrier's data field: the Outcome/source
                // narrowing + verbatim projection of the producer's composite record(s) is reified by
                // FetcherEmitter.bind into a named (DataFetchingEnvironment env) method, collected below.
                case ChildField.RecordCompositeField ignored    -> { /* source passthrough reified by FetcherEmitter.bind, collected below */ }
                case ChildField.ComputedField ignored           -> { /* alias-pickup read reified by FetcherEmitter.bind; projected via TypeClassGenerator.$fields() */ }
                case ChildField.PropertyField ignored           -> { /* accessor / column read reified by FetcherEmitter.bind, collected below */ }
                case ChildField.ErrorsField ignored             -> { /* LocalContext / WrapperArm reified by FetcherEmitter.bind; PayloadAccessor still PropertyDataFetcher.fetching */ }
                // Cannot occur — filtered by generateForType before dispatch
                case InputField ignored ->
                    throw new AssertionError("InputField in type dispatch: " + ignored.qualifiedName());
                case GraphitronField.UnclassifiedField ignored ->
                    throw new AssertionError("UnclassifiedField in type dispatch: " + ignored.qualifiedName());
            }
            // R303: reify the inline / light reads onto this class. bind() returns Reified for
            // exactly the variants the switch above handles with a no-method arm (column reads,
            // source passthroughs, the errors transports, the single-record carriers); the
            // method-backed variants return Inline, so there is no double-emission.
            if (FetcherEmitter.bind(field, reifiedFetchersClass, parentTable, resultType, outputPackage, sourceIsOutcome)
                    instanceof FetcherEmitter.FetcherBinding.Reified reified) {
                builder.addMethod(reified.method());
            }
        }
        });

        if (ctx.isRequested(TypeFetcherEmissionContext.HelperKind.GRAPHITRON_CONTEXT)) {
            builder.addMethod(buildGraphitronContextHelper(outputPackage));
        }

        // Emit per-bean instantiation helpers (createBean / createBeans) for any InputBean
        // extraction on method-backed fields. Dedup by bean class — nested beans are collected
        // transitively so a single bean class always emits exactly one pair of helpers per
        // *Fetchers class, regardless of how many distinct service methods reach it. R238 added
        // a sibling walk over ServiceField permits whose carrier holds the equivalent
        // RecordInput / JavaBeanInput shapes; both walks feed the same dedup map.
        var beanHelpers = new java.util.LinkedHashMap<no.sikt.graphitron.javapoet.ClassName,
            CallSiteExtraction.InputBean>();
        // R311: a sibling dedup queue for jOOQ TableRecord params, keyed by record class, fed by the
        // same dual walk. A record reached by either coordinate (child via callParams, root via the
        // ServiceField carrier) emits its create<Record> pair exactly once. No transitive collection:
        // a jOOQ record param never nests another.
        var jooqRecordHelpers = new java.util.LinkedHashMap<no.sikt.graphitron.javapoet.ClassName,
            CallSiteExtraction.JooqRecord>();
        fields.stream()
            .filter(f -> f instanceof MethodBackedField)
            .map(f -> (MethodBackedField) f)
            .flatMap(f -> f.method().callParams().stream())
            .filter(p -> p.extraction() instanceof CallSiteExtraction.InputBean)
            .map(p -> (CallSiteExtraction.InputBean) p.extraction())
            .forEach(ib -> InputBeanInstantiationEmitter.collectTransitively(ib, beanHelpers));
        fields.stream()
            .filter(f -> f instanceof MethodBackedField)
            .map(f -> (MethodBackedField) f)
            .flatMap(f -> f.method().callParams().stream())
            .filter(p -> p.extraction() instanceof CallSiteExtraction.JooqRecord)
            .map(p -> (CallSiteExtraction.JooqRecord) p.extraction())
            .forEach(jr -> jooqRecordHelpers.putIfAbsent(jr.table().recordClass(), jr));
        // R238: walk the four service permits' carriers for composite ValueShape arms (and R311's
        // JooqRecordInput leaf).
        for (var field : fields) {
            if (field instanceof no.sikt.graphitron.rewrite.model.ServiceField sf) {
                collectBeanHelpersFromCarrier(sf.serviceMethodCall(), beanHelpers, jooqRecordHelpers);
            }
        }
        for (var ib : beanHelpers.values()) {
            builder.addMethod(InputBeanInstantiationEmitter.buildSingularHelper(ib));
            builder.addMethod(InputBeanInstantiationEmitter.buildPluralHelper(ib,
                no.sikt.graphitron.javapoet.ClassName.bestGuess(outputPackage + "." + className)));
        }
        for (var jr : jooqRecordHelpers.values()) {
            builder.addMethod(JooqRecordInstantiationEmitter.buildSingularHelper(jr));
            builder.addMethod(JooqRecordInstantiationEmitter.buildPluralHelper(jr));
        }

        // R195: emit one decode<RecordType>Record helper per jOOQ-record-typed @nodeId input-bean
        // member reached by the collected beans, plus a decode<RecordType>RecordList variant for
        // list-valued members (which delegates to the scalar helper per element). The create<Bean>
        // helper bodies call these by name. Scalar and list variants dedup independently by record
        // type; the scalar helper is always emitted because the list variant delegates to it.
        var scalarDecoders = new java.util.LinkedHashMap<no.sikt.graphitron.javapoet.ClassName,
            CallSiteExtraction.NodeIdDecodeRecord>();
        var listDecoders = new java.util.LinkedHashMap<no.sikt.graphitron.javapoet.ClassName,
            CallSiteExtraction.NodeIdDecodeRecord>();
        InputBeanInstantiationEmitter.collectRecordDecoders(beanHelpers.values(),
            scalarDecoders, listDecoders);
        for (var rec : scalarDecoders.values()) {
            builder.addMethod(InputBeanInstantiationEmitter.buildRecordDecodeHelper(rec));
        }
        for (var rec : listDecoders.values()) {
            builder.addMethod(InputBeanInstantiationEmitter.buildRecordDecodeHelperList(rec));
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
        builder.addCode(noChannelCatchArm(outputPackage));
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
     *         .select(table.asterisk(), DSL.field(table.getQualifiedName().append(DSL.name("CONTENT_TYPE")), Object.class))
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
        builder.addCode(buildDiscriminatorFilter(qtif.discriminatorColumn(), qtif.knownDiscriminatorValues(), tableLocal));
        builder.addCode(buildInterfaceFieldsList(ctx, qtif.participants(), qtif.discriminatorColumn(), tableLocal, outputPackage));
        builder.addCode(buildCrossTableAliasDeclarations(qtif.participants(), tableLocal));
        builder.addCode(buildJoinedDetailAliasDeclarations(ctx, qtif.participants(), tableLocal));

        var dslContextClass = ClassName.get("org.jooq", "DSLContext");
        var selectJoinStepClass = ClassName.get("org.jooq", "SelectJoinStep");
        var selectJoinStepOfRecord = ParameterizedTypeName.get(selectJoinStepClass, RECORD);

        builder.addStatement("$T dsl = $L.getDslContext(env)", dslContextClass, ctx.graphitronContextCall());
        builder.addStatement("$T step = dsl.select(new $T<>(fields)).from($L)",
            selectJoinStepOfRecord, ArrayList.class, tableLocal);
        builder.addCode(buildCrossTableJoinChain(qtif.participants(), qtif.discriminatorColumn(), tableLocal));
        builder.addCode(buildJoinedDetailJoinChain(ctx, qtif.participants(), qtif.discriminatorColumn(), tableLocal));

        if (isList) {
            builder.addCode(buildOrderByCode(qtif.orderBy(), qtif.name(), tableLocal));
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

        var dslContextClass = ClassName.get("org.jooq", "DSLContext");
        builder.addStatement("$T dsl = $L.getDslContext(env)", dslContextClass, ctx.graphitronContextCall());

        // Build join-path condition. Only single-hop FkJoin is supported; multi-hop and
        // ConditionJoin paths are caught at classification time.
        builder.addCode(buildJoinPathCondition(tif.joinPath(), tableRef.tableName()));
        builder.addCode(buildDiscriminatorFilter(tif.discriminatorColumn(), tif.knownDiscriminatorValues(), tableLocal));
        builder.addCode(buildInterfaceFieldsList(ctx, tif.participants(), tif.discriminatorColumn(), tableLocal, outputPackage));
        builder.addCode(buildCrossTableAliasDeclarations(tif.participants(), tableLocal));
        builder.addCode(buildJoinedDetailAliasDeclarations(ctx, tif.participants(), tableLocal));

        var selectJoinStepClass = ClassName.get("org.jooq", "SelectJoinStep");
        var selectJoinStepOfRecord = ParameterizedTypeName.get(selectJoinStepClass, RECORD);
        builder.addStatement("$T step = dsl.select(new $T<>(fields)).from($L)",
            selectJoinStepOfRecord, ArrayList.class, tableLocal);
        builder.addCode(buildCrossTableJoinChain(tif.participants(), tif.discriminatorColumn(), tableLocal));
        builder.addCode(buildJoinedDetailJoinChain(ctx, tif.participants(), tif.discriminatorColumn(), tableLocal));

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
            TypeFetcherEmissionContext ctx,
            List<ParticipantRef> participants, String discriminatorColumn,
            String tableLocal, String outputPackage) {
        var b = CodeBlock.builder();
        var fieldType = ParameterizedTypeName.get(
            ClassName.get("org.jooq", "Field"),
            WildcardTypeName.subtypeOf(Object.class));
        var setType = ParameterizedTypeName.get(
            ClassName.get(LinkedHashSet.class), fieldType);
        b.addStatement("$T fields = new $T<>()", setType, LinkedHashSet.class);
        // Project the discriminator under a synthetic alias for the TypeResolver to route off. Two
        // reasons. (1) Qualification: the discriminator lives on the base table, and a participant's
        // FK-target detail table can re-declare it (composite FK), so a bare DSL.name(col) is ambiguous
        // once a participant join is present. We qualify off the FROM table's own jOOQ instance via
        // <tableLocal>.getQualifiedName(): jOOQ's table renderer produces the exact qualifier that
        // appears in the FROM clause (no schema part for a default-schema table, "schema"."table" for a
        // named-schema table), so the reference matches FROM by construction. The earlier two-part
        // DSL.name(@table-directive, col) instead built the qualifier from the verbatim @table(name:)
        // string, which diverges from the rendered FROM token whenever the directive name differs in
        // case or schema (R395). (2) De-duplication: when the interface also exposes the discriminator
        // as a queryable field, the participant $fields below projects the real catalog column too
        // (rendered three-part, "schema"."base"."col"); a TypeResolver reading the bare column name would
        // match both projections ambiguously. Aliasing the routing copy to a synthetic name distinct from
        // any real column (see MultiTablePolymorphicEmitter.DISCRIMINATOR_COLUMN, mirroring the
        // multi-table __typename convention) makes the routing read unambiguous and leaves the
        // user-facing field projected once under its own name. The WHERE filter and LEFT JOIN ON-clause
        // keep referencing the real qualified column (they cannot read a SELECT alias); only this routing
        // projection is aliased. The explicit Object.class yields the Field<Object> the .as(...) carries.
        b.addStatement("fields.add($T.field($L.getQualifiedName().append($T.name($S)), $T.class).as($S))",
            DSL, tableLocal, DSL, discriminatorColumn, Object.class, MultiTablePolymorphicEmitter.DISCRIMINATOR_COLUMN);
        for (var participant : participants) {
            if (!(participant instanceof ParticipantRef.TableBound tb)) continue;
            var typeClass = ClassName.get(outputPackage + ".types", tb.typeName());
            b.addStatement("fields.addAll($T.$$fields(env.getSelectionSet(), $L, env))",
                typeClass, tableLocal);
        }
        // R389 joined-table participants: their data splits across the base and their own detail
        // table, so we cannot call their $fields against the base (its parameter is typed as the
        // detail table). Instead project the base-resident slice off the base here, reading each
        // participant's classified fields (the emitter reads the field variant, never the catalog):
        //   - an inherited field is a ColumnReferenceField whose column resolves on the base; project
        //     that base column aliased as the GraphQL field name, matching the alias the standalone
        //     correlated-subquery projection uses, so the one registered fetcher reads it in both
        //     queries (FetcherEmitter reads a Direct ColumnReferenceField by field-name alias);
        //   - a shared-key field is a ColumnField whose column is one of the child->parent hop's
        //     columns (the join key, present on both base and detail); project the paired base column
        //     so NULL-through rows (base present, detail absent) still resolve it, aliased to the
        //     detail column's name (a no-op when the FK and PK columns share a name) so the
        //     participant's ColumnField fetcher reads it back by that name even when the two differ.
        // Detail-exclusive ColumnFields (column not in the hop) are projected against the detail alias
        // by buildJoinedDetailAliasDeclarations. A field projected by more than one participant (every
        // shared/inherited field is) is emitted once: the .as(...) aliases produce fresh Field objects
        // the LinkedHashSet would not dedupe, so we dedupe by output alias explicitly.
        var schema = ctx.graphitronSchema();
        if (schema != null) {
            var seenAliases = new java.util.HashSet<String>();
            for (var participant : participants) {
                if (!(participant instanceof ParticipantRef.JoinedTableBound jtb)) continue;
                for (var f : schema.fieldsOf(jtb.typeName())) {
                    if (f instanceof ChildField.ColumnReferenceField crf) {
                        if (seenAliases.add(crf.name())) {
                            b.addStatement("fields.add($L.$L.as($S))", tableLocal, crf.column().javaName(), crf.name());
                        }
                    } else if (f instanceof ChildField.ColumnField cf) {
                        var baseCol = sharedKeyBaseColumn(jtb, cf.column());
                        if (baseCol != null && seenAliases.add(cf.column().sqlName())) {
                            b.addStatement("fields.add($L.$L.as($S))", tableLocal, baseCol.javaName(), cf.column().sqlName());
                        }
                    }
                }
            }
        }
        return b.build();
    }

    /**
     * For a participant {@link ChildField.ColumnField} whose column is a child-&gt;parent hop column
     * (a shared-key column present on both base and detail), returns the paired base-side column (the
     * hop slot's target side); {@code null} when the field's column is detail-exclusive. The base side
     * may differ in name from the detail side, so the caller projects {@code base.<returned>} aliased
     * to the detail column's name.
     */
    private static ColumnRef sharedKeyBaseColumn(ParticipantRef.JoinedTableBound jtb, ColumnRef detailColumn) {
        for (var slot : jtb.childToParent().slots()) {
            if (slot.sourceSide().sqlName().equalsIgnoreCase(detailColumn.sqlName())) {
                return slot.targetSide();
            }
        }
        return null;
    }

    /**
     * The participant's detail-exclusive fields: classified {@link ChildField.ColumnField}s whose
     * column is not part of the child->parent hop (i.e. not a shared-key column, which lives on the
     * base). These are projected against the participant's detail alias behind a discriminator-gated
     * LEFT JOIN. Empty when no schema is threaded (unit-tier model-only callers).
     */
    private static List<ChildField.ColumnField> detailExclusiveFields(
            TypeFetcherEmissionContext ctx, ParticipantRef.JoinedTableBound jtb) {
        var schema = ctx.graphitronSchema();
        if (schema == null) return List.of();
        var out = new ArrayList<ChildField.ColumnField>();
        for (var f : schema.fieldsOf(jtb.typeName())) {
            if (f instanceof ChildField.ColumnField cf && sharedKeyBaseColumn(jtb, cf.column()) == null) {
                out.add(cf);
            }
        }
        return out;
    }

    /**
     * Emits per-joined-table-participant detail-alias declarations plus the selection-set-gated
     * {@code fields.add(detailAlias.<col>)} for each detail-exclusive field, mirroring
     * {@link #buildCrossTableAliasDeclarations} but joining the whole detail table once per
     * participant rather than one aliased table per cross-table field. The column is projected under
     * its natural name (no {@code .as(...)}) so the participant's plain {@code ColumnField} fetcher
     * reads it back by column name.
     */
    private static CodeBlock buildJoinedDetailAliasDeclarations(
            TypeFetcherEmissionContext ctx, List<ParticipantRef> participants, String tableLocal) {
        var b = CodeBlock.builder();
        for (var participant : participants) {
            if (!(participant instanceof ParticipantRef.JoinedTableBound jtb)) continue;
            if (jtb.discriminatorValue() == null) continue;
            var detailExclusive = detailExclusiveFields(ctx, jtb);
            if (detailExclusive.isEmpty()) continue;
            var names = GeneratorUtils.ResolvedTableNames.ofTable(jtb.detailTable());
            String aliasVar = jtb.detailAliasVarName();
            b.addStatement("$T $L = null", names.jooqTableClass(), aliasVar);
            for (var cf : detailExclusive) {
                b.beginControlFlow("if (env.getSelectionSet().contains($S))",
                    jtb.typeName() + "." + cf.name());
                b.addStatement("$L = $T.$L.as($S)", aliasVar, names.tablesClass(),
                    jtb.detailTable().javaFieldName(), jtb.detailAliasName());
                b.addStatement("fields.add($L.$L)", aliasVar, cf.column().javaName());
                b.endControlFlow();
            }
        }
        return b.build();
    }

    /**
     * Emits the conditional {@code step = step.leftJoin(detailAlias).on(...)} block for each
     * joined-table participant whose detail alias was declared by
     * {@link #buildJoinedDetailAliasDeclarations}. The ON clause equates the child->parent hop
     * (direction-blind: {@code detailAlias.<sourceSide>} on the detail/FK side equals
     * {@code base.<targetSide>} on the base/PK side, AND-chained across composite slots) plus the
     * participant's discriminator value, so non-matching rows carry NULL through the join.
     */
    private static CodeBlock buildJoinedDetailJoinChain(
            TypeFetcherEmissionContext ctx, List<ParticipantRef> participants,
            String discriminatorColumn, String tableLocal) {
        var b = CodeBlock.builder();
        for (var participant : participants) {
            if (!(participant instanceof ParticipantRef.JoinedTableBound jtb)) continue;
            if (jtb.discriminatorValue() == null) continue;
            if (detailExclusiveFields(ctx, jtb).isEmpty()) continue;
            String aliasVar = jtb.detailAliasVarName();
            CodeBlock keyOn = null;
            for (var slot : jtb.childToParent().slots()) {
                var eq = CodeBlock.of("$L.$L.eq($L.$L)",
                    aliasVar, slot.sourceSide().javaName(), tableLocal, slot.targetSide().javaName());
                keyOn = keyOn == null ? eq : CodeBlock.of("$L.and($L)", keyOn, eq);
            }
            var onCondition = CodeBlock.builder()
                .add("$L.and($T.field($L.getQualifiedName().append($T.name($S)), $T.class).eq($S))",
                    keyOn, DSL, tableLocal, DSL, discriminatorColumn, Object.class, jtb.discriminatorValue())
                .build();
            b.beginControlFlow("if ($L != null)", aliasVar);
            b.addStatement("step = step.leftJoin($L).on($L)", aliasVar, onCondition);
            b.endControlFlow();
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
            List<ParticipantRef> participants, String discriminatorColumn,
            String tableLocal) {
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
                // Qualify the discriminator predicate to the base table; see buildInterfaceFieldsList
                // for why a bare reference is ambiguous and why we qualify off the FROM table's own
                // jOOQ instance (<tableLocal>.getQualifiedName()) rather than the @table-directive string.
                var onCondition = CodeBlock.builder()
                    .add("$L.and($T.field($L.getQualifiedName().append($T.name($S)), $T.class).eq($S))",
                        fkOn, DSL, tableLocal, DSL, discriminatorColumn, Object.class, tb.discriminatorValue())
                    .build();
                b.beginControlFlow("if ($L != null)", aliasVar);
                b.addStatement("step = step.leftJoin($L).on($L)", aliasVar, onCondition);
                b.endControlFlow();
            }
        }
        return b.build();
    }

    /**
     * Emits {@code condition = condition.and(<base>.field(<col>).in(val1, val2, ...))}
     * to restrict results to rows with a known discriminator value. Mirrors the legacy generator
     * which always emits {@code WHERE col IN (...known values...)}. When {@code knownValues} is
     * empty, emits nothing (no restriction added).
     *
     * <p>The discriminator column is qualified to the FROM table via its own jOOQ instance
     * ({@code <tableLocal>.getQualifiedName()}): once any participant cross-table join is present,
     * the joined detail table can re-declare the discriminator column, making a bare reference
     * ambiguous. Qualifying off the table instance produces the exact qualifier jOOQ renders in the
     * FROM clause, matching it by construction. See {@link #buildInterfaceFieldsList}.
     */
    private static CodeBlock buildDiscriminatorFilter(String discriminatorColumn, List<String> knownValues, String tableLocal) {
        if (knownValues.isEmpty()) return CodeBlock.of("");
        var inArgs = knownValues.stream()
            .map(v -> CodeBlock.of("$S", v))
            .collect(CodeBlock.joining(", "));
        return CodeBlock.builder()
            .addStatement("condition = condition.and($T.field($L.getQualifiedName().append($T.name($S)), $T.class).in($L))",
                DSL, tableLocal, DSL, discriminatorColumn, Object.class, inArgs)
            .build();
    }

    /**
     * Projects a multi-table polymorphic field's per-participant filter carriers into the
     * typename-keyed map the {@link MultiTablePolymorphicEmitter} branch loops consume, mirroring the
     * typename-keyed {@code participantJoinPaths} the same loops already take (R363).
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
     * Generates a fetcher for a root-query {@code @routine} table field (R300). Mirrors
     * {@link #buildQueryTableFetcher}, with one difference: the {@code FROM} source is the schema's
     * global {@code Routines} convenience method (which returns the configured table-valued-function
     * table) rather than the bare {@code Tables.X} singleton, with the routine's IN parameters bound
     * from GraphQL field arguments. Selection narrowing via {@code Type.$fields(...)} is unchanged,
     * so the {@code SELECT} projects only the routine-result columns the query selected.
     *
     * <p>Generated code (list variant):
     * <pre>{@code
     * public static Result<Record> tilganger(DataFetchingEnvironment env) {
     *     TilgangerForFeidebrukerMedFsFiktivtFnr table =
     *         Routines.tilgangerForFeidebrukerMedFsFiktivtFnr(
     *             env.<String>getArgument("env"), env.<String>getArgument("serviceId"), env.<String>getArgument("feideId"));
     *     DSLContext dsl = graphitronContext(env).getDslContext(env);
     *     Result<Record> payload = dsl
     *         .select(Tilgang.$fields(env.getSelectionSet(), table, env))
     *         .from(table)
     *         .fetch();
     *     ...
     * }
     * }</pre>
     */
    private static MethodSpec buildQueryRoutineFetcher(TypeFetcherEmissionContext ctx,
            QueryField.QueryRoutineTableField qrtf, String outputPackage) {
        var tableRef = qrtf.returnType().table();
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, qrtf.returnType().returnTypeName(), outputPackage);
        boolean isList = qrtf.returnType().wrapper().isList();
        var valueType = isList ? (TypeName) ParameterizedTypeName.get(RESULT, RECORD) : RECORD;

        var builder = MethodSpec.methodBuilder(qrtf.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");

        // Routines.<method>(<bound IN params>) returns the configured table-valued-function table.
        var routine = qrtf.routine();
        CodeBlock args = CodeBlock.join(routine.argBindings().stream()
            .map(b -> CodeBlock.of("env.<$T>getArgument($S)", b.paramType(), b.graphqlArgName()))
            .toList(), ", ");
        String tableLocal = names.tableLocalName();

        builder.beginControlFlow("try");
        builder.addStatement("$T $L = $T.$L($L)",
            names.jooqTableClass(), tableLocal, routine.routinesClass(), routine.methodName(), args);

        var dslContextClass = ClassName.get("org.jooq", "DSLContext");
        builder.addStatement("$T dsl = $L.getDslContext(env)", dslContextClass, ctx.graphitronContextCall());
        builder.addCode(CodeBlock.builder()
            .add("$T payload = dsl\n", valueType)
            .indent()
            .add(".select($T.$$fields(env.getSelectionSet(), $L, env))\n", names.typeClass(), tableLocal)
            .add(".from($L)\n", tableLocal)
            .add(isList ? ".fetch();\n" : ".fetchOne();\n")
            .unindent()
            .build());
        builder.addCode(returnSyncSuccess(valueType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(noChannelCatchArm(outputPackage));
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
    private static MethodSpec buildQueryServiceTableFetcher(TypeFetcherEmissionContext ctx, QueryField.QueryServiceTableField qstf,
                                                             String outputPackage) {
        var tableRef = qstf.returnType().table();
        var recordClass = tableRef.recordClass();
        boolean isList = qstf.returnType().wrapper().isList();
        // For List cardinality, the developer's declared return type is either Result<XRecord>
        // or List<XRecord> (validated in ServiceDirectiveResolver.validateRootInvariants §3);
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
        boolean isList = qsrf.returnType().wrapper().isList();
        if (qsrf.returnType() instanceof ReturnTypeRef.ResultReturnType r && r.fqClassName() != null) {
            ClassName recordCls = ClassName.bestGuess(r.fqClassName());
            return isList ? ParameterizedTypeName.get(LIST, recordCls) : recordCls;
        }
        // PojoResultType (null fqClassName) or ScalarReturnType: faithfully reflect the
        // developer's declared return type. ServiceMethodCall.javaReturnType is the structured
        // TypeName captured at walk time, so the emitter declares the matching shape directly
        // without parsing a string.
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
     * {@code ResultReturnType} with a non-null {@code fqClassName} produces a typed declaration;
     * everything else faithfully reflects the developer method's reflected return type.
     */
    private static TypeName computeMutationServiceRecordReturnType(MutationField.MutationServiceRecordField msrf) {
        if (msrf.returnType() instanceof ReturnTypeRef.ResultReturnType r && r.fqClassName() != null) {
            ClassName recordCls = ClassName.bestGuess(r.fqClassName());
            // R329: read the arrival cardinality from the reflected method return, not the SDL payload
            // wrapper. A two-level record-composite carrier has a single payload wrapper but a
            // List<composite> producer return (the per-element composite is the fqClassName, with the
            // list cardinality on the data field); the SDL wrapper would understate it and the fetcher
            // would declare `composite` for a `List<composite>` value. The reflected return is the exact
            // type the service yields, so the fetcher and its Outcome wrapping must match it. For the
            // single-level / plain-DTO payloads the two agree (single wrapper, single return).
            boolean methodReturnsList =
                msrf.serviceMethodCall().javaReturnType() instanceof ParameterizedTypeName p
                    && p.rawType().equals(LIST);
            boolean isList = methodReturnsList || msrf.returnType().wrapper().isList();
            return isList ? ParameterizedTypeName.get(LIST, recordCls) : recordCls;
        }
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
                                                        Optional<ErrorChannel> errorChannel,
                                                        String outputPackage) {
        // R244: an @service outcome field (Mapped channel) hands graphql-java a typed Outcome<X>
        // source. The DataFetcherResult payload type becomes Outcome<X>; the inner method result
        // local stays X (the service's return), wrapped in Success on the happy path and replaced by
        // ErrorList on the mapped-error path. A channel-less @service field (no errors field) keeps
        // the bare X payload and the redact-only catch arm.
        boolean wrap = errorChannel.isPresent() && errorChannel.get() instanceof ErrorChannel.Mapped;
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
        ServiceMethodCallEmitter.emit(carrier, outputPackage, valueType)
            .forEach(builder::addStatement);
        if (wrap) {
            builder.addCode(returnSyncSuccessWrapped(payloadType, outputPackage, "result"));
        } else {
            builder.addCode(returnSyncSuccess(valueType, "result"));
        }
        builder.nextControlFlow("catch ($T e)", Exception.class);
        if (wrap) {
            builder.addCode(ChannelCatchArmEmitter.emit(errorChannel, payloadType, outputPackage, null));
        } else {
            builder.addCode(catchArm(outputPackage, errorChannel));
        }
        builder.endControlFlow();

        return builder.build();
    }

    /** {@code Outcome<X>} in the run's schema-support package (R244), boxing primitive {@code X}. */
    private static TypeName outcomeOf(TypeName valueType, String outputPackage) {
        return ParameterizedTypeName.get(
            ClassName.get(outputPackage + ".schema", OutcomeClassGenerator.CLASS_NAME), boxed(valueType));
    }

    /** Success-path return wrapping the method result in {@code Outcome.Success} (R244). */
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
     * Renders the holder constructor's actual-argument list (R256). A {@link ParamSource.DslContext}
     * ctor parameter reads the surrounding {@code dsl} local; a {@link ParamSource.Context}
     * parameter extracts inline via the {@code graphitronContext(env)} helper, mirroring
     * {@code ServiceMethodCallEmitter}'s {@code FromContext} emit. The legacy single-{@code DSLContext}
     * holder renders as {@code dsl}, identical to the pre-R256 {@code new ClassName(dsl)} form.
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
            case MethodRef.CallShape.InstanceWithDslHolder holder ->
                holder.ctorParams().stream().anyMatch(p -> p.source() instanceof ParamSource.DslContext);
        };
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
     * <p>R94: input-typed SDL args materialise through the graphitron-emitted class's
     * {@code fromMap(Map<String,Object>)} factory before the validator walks them. The
     * fetcher boundary feeds the typed instance into
     * {@code validator.validate(<typed>)}; the empty walk produces zero violations until R98
     * attaches programmatic {@code ConstraintMapping} entries. Scalar / enum SDL args stay on
     * the raw value path. When the assembled schema is unavailable (some unit-tier tests
     * build the model only), the pre-step falls back to validating against the raw value for
     * every arg, mirroring pre-R94 behaviour.
     *
     * <p>R238: walks {@link ServiceMethodCall#methodArgs()} for {@link MappingEntry.FromArg}
     * entries. The {@link no.sikt.graphitron.rewrite.model.ValueShape} carries each top-level
     * arg's outer arg name on its data-bearing leaves; {@link #outerArgOfValueShape} descends
     * to the first available leaf to extract it. Ctor args are not walked (the walker forbids
     * {@code FromArg} entries in {@code ctorArgs} structurally), matching the legacy behaviour
     * that only iterated method params.
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
                // value reads route through R150's bean path or the existing Map.get pattern.
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
            java.util.Map<no.sikt.graphitron.javapoet.ClassName, CallSiteExtraction.InputBean> out,
            java.util.Map<no.sikt.graphitron.javapoet.ClassName, CallSiteExtraction.JooqRecord> jooqOut) {
        if (carrier instanceof ServiceMethodCall.Instance inst) {
            for (var e : inst.ctorArgs()) collectFromMappingEntry(e, out, jooqOut);
        }
        for (var e : carrier.methodArgs()) collectFromMappingEntry(e, out, jooqOut);
    }

    private static void collectFromMappingEntry(
            no.sikt.graphitron.rewrite.model.MappingEntry entry,
            java.util.Map<no.sikt.graphitron.javapoet.ClassName, CallSiteExtraction.InputBean> out,
            java.util.Map<no.sikt.graphitron.javapoet.ClassName, CallSiteExtraction.JooqRecord> jooqOut) {
        if (entry instanceof no.sikt.graphitron.rewrite.model.MappingEntry.FromArg fromArg) {
            collectFromValueShape(fromArg.shape(), out, jooqOut);
        }
    }

    private static void collectFromValueShape(
            no.sikt.graphitron.rewrite.model.ValueShape shape,
            java.util.Map<no.sikt.graphitron.javapoet.ClassName, CallSiteExtraction.InputBean> out,
            java.util.Map<no.sikt.graphitron.javapoet.ClassName, CallSiteExtraction.JooqRecord> jooqOut) {
        switch (shape) {
            case no.sikt.graphitron.rewrite.model.ValueShape.Scalar ignored -> { /* leaf */ }
            case no.sikt.graphitron.rewrite.model.ValueShape.ListOf l -> collectFromValueShape(l.elementShape(), out, jooqOut);
            case no.sikt.graphitron.rewrite.model.ValueShape.RecordInput rec ->
                registerBeanHelper(rec.javaClass(), CallSiteExtraction.InputBean.Target.RECORD, rec.fields(), out, jooqOut);
            case no.sikt.graphitron.rewrite.model.ValueShape.JavaBeanInput jb ->
                registerBeanHelper(jb.javaClass(), CallSiteExtraction.InputBean.Target.JAVA_BEAN, jb.fields(), out, jooqOut);
            // R311: the root-coordinate JooqRecordInput leaf carries its own construction carrier, so
            // the helper registers from the ValueShape alone — true dual-walk parity with InputBean.
            case no.sikt.graphitron.rewrite.model.ValueShape.JooqRecordInput jr ->
                jooqOut.putIfAbsent(jr.carrier().table().recordClass(), jr.carrier());
        }
    }

    private static void registerBeanHelper(
            no.sikt.graphitron.javapoet.ClassName beanClass,
            CallSiteExtraction.InputBean.Target target,
            List<no.sikt.graphitron.rewrite.model.ValueShape.FieldBinding> vsFields,
            java.util.Map<no.sikt.graphitron.javapoet.ClassName, CallSiteExtraction.InputBean> out,
            java.util.Map<no.sikt.graphitron.javapoet.ClassName, CallSiteExtraction.JooqRecord> jooqOut) {
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
                vfb.sdlFieldName(), vfb.javaFieldName(), leafCarrier, isList, javaElementTypeName));
        }
        var ib = new CallSiteExtraction.InputBean(beanClass, target, fieldBindings);
        out.put(beanClass, ib);
        // Recurse so nested beans register their own helper. (A jOOQ record never nests inside a bean,
        // so jooqOut is threaded only to satisfy the shared collectFromValueShape signature.)
        for (var vfb : vsFields) {
            collectFromValueShape(vfb.shape(), out, jooqOut);
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
            // R311: forced by the sealed addition, unreachable here — a JooqRecordInput is never an
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
                vfb.sdlFieldName(), vfb.javaFieldName(),
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
            // R311: forced by the sealed addition; a JooqRecordInput is never an InputBean field shape,
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
            // R311: a JooqRecordInput carries its own path; defensive read for the forced arm.
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
     * Emits a fetcher for {@link MutationField.MutationDeleteTableField}: a synchronous static
     * method that runs {@code dsl.deleteFrom(table).where(<lookupKey predicates>)
     * .returningResult(<keys or $fields>).fetchOne(...)}.
     *
     * <p>Empty-match semantics: {@code .fetchOne(...)} returns {@code null} when the WHERE clause
     * matches no row. graphql-java surfaces that as a GraphQL null on a nullable field, or a
     * non-null violation on {@code ID!}/{@code Type!}.
     */
    private static MethodSpec buildMutationDeleteFetcher(TypeFetcherEmissionContext ctx, MutationField.MutationDeleteTableField f,
                                                          String outputPackage) {
        // R266: the WHERE columns come off the DeleteRows carrier (deleteRows().whereColumns()) and
        // the slim arg surface (inputArg) instead of a TableInputArg. The emitted SQL is structurally
        // identical to the legacy shape; only the source of the WHERE columns changed. The carrier's
        // KeyColumn list projects back into the InputColumnBindingGroup shape the shared lookup-WHERE
        // emitters consume via keyGroupsOf.
        var inputArg = f.inputArg();
        var tableRef = inputArg.table();
        var tablesOnly = GeneratorUtils.ResolvedTableNames.ofTable(tableRef);
        String tableLocal = tablesOnly.tableLocalName();
        var whereGroups = keyGroupsOf(f.deleteRows().whereColumns());

        var dmlChain = CodeBlock.builder().add(".deleteFrom($L)\n", tableLocal);
        var postInGuard = CodeBlock.builder();
        if (inputArg.list()) {
            dmlChain.add(".where(").add(buildBulkLookupRowIn(whereGroups, tablesOnly, tableRef)).add(")\n");
        } else {
            var chunk = buildLookupWhereSingleRow(whereGroups, tablesOnly, tableRef, "in");
            postInGuard.add(chunk.decodeLocals());
            dmlChain.add(".where(").add(chunk.whereExpr()).add(")\n");
        }

        return buildDmlFetcher(ctx, f.name(), f.returnExpression(), f.errorChannel(),
            inputArg.name(), tableRef, tablesOnly, tableLocal,
            outputPackage, dmlChain.build(),
            f.dialectRequirement(), postInGuard.build(), inputArg.list());
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

        return buildDmlFetcher(ctx, f.name(), f.returnExpression(), f.errorChannel(),
            tia.name(), tableRef, tablesOnly, tableLocal,
            outputPackage, dmlChain.build(),
            f.dialectRequirement(), postInGuard.build(), tia.list());
    }

    /**
     * True iff any field on {@code fields} bears a {@link CallSiteExtraction.NodeIdDecodeKeys}
     * carrier: a {@link InputField.ColumnField} with NodeId extraction, a
     * {@link InputField.CompositeColumnField}, or either of the FK-target reference carriers
     * ({@link InputField.ColumnReferenceField} with NodeId extraction,
     * {@link InputField.CompositeColumnReferenceField}). Drives the bulk-INSERT / bulk-UPSERT
     * lambda shape choice (single-expression vs block-with-decode-locals).
     */
    private static boolean anyNodeIdCarrier(List<InputField> fields) {
        // R186: descend nested grouping inputs; a NodeId leaf anywhere drives the block-lambda shape.
        for (var leaf : flattenInsertLeaves(fields, List.of())) {
            var f = leaf.field();
            if (f instanceof InputField.ColumnField cf
                && cf.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys) return true;
            if (f instanceof InputField.CompositeColumnField) return true;
            if (f instanceof InputField.ColumnReferenceField crf
                && crf.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys) return true;
            if (f instanceof InputField.CompositeColumnReferenceField) return true;
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
     *       ({@code insertKey_<fi>.value1()}). Caller must declare the decode local; see
     *       {@link #buildInsertDecodeLocals}.</li>
     *   <li>{@link InputField.CompositeColumnField} — N cells, values read from
     *       {@code insertKey_<fi>.value1()..value<N>()}.</li>
     * </ul>
     */
    private static CodeBlock buildPerCellValueList(
            List<InputField> fields,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef,
            String mapLocal,
            String localPrefix) {
        // R322: an INSERT with a column written by more than one carrier dedups to one cell per column,
        // coalescing over the present writers via the per-column cell local emitted in the decode-locals
        // prep. A non-overlapping INSERT keeps the one-leaf-one-cell walk below (byte-identical).
        if (hasInsertOverlap(fields)) {
            return buildPerCellValueListDeduped(fields, tablesOnly, tableRef, mapLocal, localPrefix);
        }
        var b = CodeBlock.builder();
        boolean first = true;
        // R186: descend nested grouping inputs to a flat leaf list; each leaf carries its wire access
        // path (a single name for a top-level field, byte-identical to before R186). The presence
        // test and value read use the path, honoring the absent-vs-null contract: an absent leaf (or
        // an absent / non-Map outer level) resolves to DEFAULT; a present leaf (including explicit
        // null) binds the typed value.
        var leaves = flattenInsertLeaves(fields, List.of());
        for (int fi = 0; fi < leaves.size(); fi++) {
            var f = leaves.get(fi).field();
            var path = leaves.get(fi).path();
            var presence = nestedContainsKeyExpr(mapLocal, path, "ic" + fi);
            switch (f) {
                case InputField.ColumnField cf -> {
                    if (!first) b.add(",\n");
                    first = false;
                    if (cf.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys) {
                        String recLocal = localPrefix + "_" + fi;
                        b.add("$L ? $T.val($L.value1(), $T.$L.$L.getDataType()) : $T.defaultValue($T.$L.$L.getDataType())",
                            presence,
                            DSL, recLocal,
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName(),
                            DSL,
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), cf.column().javaName());
                    } else {
                        b.add("$L ? $T.val($L, $T.$L.$L.getDataType()) : $T.defaultValue($T.$L.$L.getDataType())",
                            presence,
                            DSL, ArgCallEmitter.nestedMapValueExpr(mapLocal, path),
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
                        b.add("$L ? $T.val($L.value$L(), $T.$L.$L.getDataType()) : $T.defaultValue($T.$L.$L.getDataType())",
                            presence,
                            DSL, recLocal, ci + 1,
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName(),
                            DSL,
                            tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
                    }
                }
                case InputField.ColumnReferenceField crf -> {
                    // R189: FK-target arity-1 reference; same single-cell shape as a NodeId-
                    // decoded ColumnField, but writes the lifted FK column on the input's own
                    // table from the decoded record's value1() accessor.
                    if (!first) b.add(",\n");
                    first = false;
                    String recLocal = localPrefix + "_" + fi;
                    var col = crf.liftedSourceColumns().get(0);
                    b.add("$L ? $T.val($L.value1(), $T.$L.$L.getDataType()) : $T.defaultValue($T.$L.$L.getDataType())",
                        presence,
                        DSL, recLocal,
                        tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName(),
                        DSL,
                        tablesOnly.tablesClass(), tableRef.javaFieldName(), col.javaName());
                }
                case InputField.CompositeColumnReferenceField ccrf -> {
                    // R189: FK-target arity >= 2 reference; same per-slot shape as
                    // CompositeColumnField, but walks liftedSourceColumns() (input's own
                    // FK columns, permuted into NodeType key order) instead of columns().
                    String recLocal = localPrefix + "_" + fi;
                    for (int ci = 0; ci < ccrf.liftedSourceColumns().size(); ci++) {
                        var col = ccrf.liftedSourceColumns().get(ci);
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
     * {@link InputField.ColumnField} contributes one column ref; {@link InputField.CompositeColumnField}
     * contributes N column refs (one per {@code columns()} slot, in declaration order).
     */
    private static CodeBlock buildInsertColumnList(
            List<InputField> fields,
            GeneratorUtils.ResolvedTableNames tablesOnly, TableRef tableRef) {
        // R322: an INSERT with an overlapping column emits that column once (the dedup that turns the
        // Postgres "column specified more than once" crash into one column + one coalesced cell). A
        // non-overlapping INSERT keeps the one-leaf-one-column walk below (byte-identical).
        if (hasInsertOverlap(fields)) {
            return buildInsertColumnListDeduped(fields, tablesOnly, tableRef);
        }
        var b = CodeBlock.builder();
        boolean first = true;
        // R186: nested grouping inputs flatten in place; the column order matches the flattened
        // VALUES cell order in buildPerCellValueList by construction (both walk flattenInsertLeaves).
        for (var leaf : flattenInsertLeaves(fields, List.of())) {
            var f = leaf.field();
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
                case InputField.ColumnReferenceField crf -> {
                    if (!first) b.add(", ");
                    first = false;
                    b.add("$T.$L.$L",
                        tablesOnly.tablesClass(), tableRef.javaFieldName(),
                        crf.liftedSourceColumns().get(0).javaName());
                }
                case InputField.CompositeColumnReferenceField ccrf -> {
                    for (var col : ccrf.liftedSourceColumns()) {
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

    /** R322: the INSERT column list driven off {@link #insertColumnPlan}, one entry per distinct column
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

    /** R322: the VALUES cell list driven off {@link #insertColumnPlan}, one cell per distinct column. A
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

    /** R322: emits one disjoint-column VALUES cell, reproducing the per-carrier shape of
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
     * NodeId-bearing carrier ({@link InputField.ColumnField} with
     * {@link CallSiteExtraction.NodeIdDecodeKeys}, {@link InputField.CompositeColumnField}),
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
        // R186: flat leaf order matches buildPerCellValueList so the decode-local index lines up; a
        // nested NodeId leaf reads its wire value via the null-safe descent over its access path.
        var leaves = flattenInsertLeaves(fields, List.of());
        for (int fi = 0; fi < leaves.size(); fi++) {
            var f = leaves.get(fi).field();
            var path = leaves.get(fi).path();
            CallSiteExtraction.NodeIdDecodeKeys nidk = switch (f) {
                case InputField.ColumnField cf when cf.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys n -> n;
                case InputField.CompositeColumnField ccf -> ccf.extraction();
                case InputField.ColumnReferenceField crf when crf.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys n -> n;
                case InputField.CompositeColumnReferenceField ccrf -> ccrf.extraction();
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
        // R322: for any column written by more than one carrier, emit the value-agreement prep here (in
        // the same scope as the decode locals, before the VALUES cells read them). Empty when there is no
        // overlap, so a non-overlapping INSERT's prep is byte-identical to the decode-locals-only form.
        emitInsertAgreementPrep(locals, fields, mapLocal, localPrefix, tablesOnly, tableRef);
        return locals.build();
    }

    /**
     * R322 (D1 + D5) / R356: the per-column overlap plan for an INSERT. Adapts each {@code SetField} leaf
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

    /** True when some backing column on the INSERT plan is written by more than one carrier (R322). */
    private static boolean hasInsertOverlap(List<InputField> fields) {
        return insertColumnPlan(fields).stream().anyMatch(OverlapColumn::shared);
    }

    /**
     * R322 (D5): emits the agreement prep for every shared column on the INSERT plan. For each, a
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
            // R356: the present-writer gather flows through the shared value-read seam
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
            var cellType = ParameterizedTypeName.get(fieldCn, ClassName.bestGuess(col.columnClass()));
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
     * R189: target columns a {@code SetField} carrier writes to on the input's own table. The
     * walk is uniform across all four admissible SetField shapes: value carriers source from
     * {@code column() / columns()}, reference carriers from {@code liftedSourceColumns()}.
     */
    private static List<no.sikt.graphitron.rewrite.model.ColumnRef> setFieldColumns(InputField.SetField sf) {
        return switch (sf) {
            case InputField.ColumnField cf -> List.of(cf.column());
            case InputField.CompositeColumnField ccf -> ccf.columns();
            case InputField.ColumnReferenceField crf -> crf.liftedSourceColumns();
            case InputField.CompositeColumnReferenceField ccrf -> ccrf.liftedSourceColumns();
        };
    }

    /**
     * R189: NodeId decode extraction for a {@code SetField} carrier, or {@code null} when the
     * value is read raw from the input map. Drives whether the SET-emitter site declares a
     * per-field decode local and reads {@code .value<i+1>()} for each slot, or reads
     * {@code map.get(name)} verbatim.
     */
    private static CallSiteExtraction.NodeIdDecodeKeys setFieldNodeIdExtraction(InputField.SetField sf) {
        return switch (sf) {
            case InputField.ColumnField cf
                when cf.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys n -> n;
            case InputField.CompositeColumnField ccf -> ccf.extraction();
            case InputField.ColumnReferenceField crf
                when crf.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys n -> n;
            case InputField.CompositeColumnReferenceField ccrf -> ccrf.extraction();
            default -> null;
        };
    }

    /**
     * R246: a SET-side input field reduced to what the SET emitter needs — the SDL field name (the
     * leaf Map key), its target columns on the input's own table, the NodeId decode extraction (or
     * {@code null} for a raw map read), and the R186 wire access path. This is the carrier-driven
     * analogue of an {@code InputField.SetField}; the UPDATE walker carrier names the partition
     * directly, so the SET emitters consume these groups rather than re-deriving columns from
     * {@code InputField}.
     *
     * <p>{@code accessPath} is the SDL key chain from the argument-value root map to the leaf:
     * {@code [name]} for a top-level field (the emit reads / presence-checks {@code map.get(name)},
     * byte-identical to before R186) or a multi-segment path for a leaf in a nested grouping input
     * (the emit descends the wire map, honoring absent-vs-null at every layer).
     */
    private record SetGroup(String name, List<ColumnRef> columns,
                            CallSiteExtraction.NodeIdDecodeKeys nidk, List<String> accessPath) {}

    /**
     * R356: adapts a {@link SetGroup} (with its stable {@code index}, used to name the per-group decode
     * local) into the shared {@link ColumnOverlap.ColumnWriter} view. The INSERT plan (site 2) and the two
     * UPDATE-SET plan sites (sites 4 and 6), and the cross-partition site 5, build these and feed them to
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

    /** R356: the {@link SetGroupWriter} views for an UPDATE-SET plan, one per {@link SetGroup}, the view's
     *  {@code index} being its position in {@code setGroups} (the decode-local suffix sites 4 / 5 / 6 use). */
    private static List<SetGroupWriter> setGroupWriters(List<SetGroup> setGroups) {
        var out = new ArrayList<SetGroupWriter>();
        for (int gi = 0; gi < setGroups.size(); gi++) {
            out.add(new SetGroupWriter(gi, setGroups.get(gi)));
        }
        return out;
    }

    /**
     * R246: adapt a legacy {@code List<InputField.SetField>} (the payload-returning DML record
     * fetchers, which still carry a {@code TableInputArg}) into the {@link SetGroup} shape the SET
     * emitters now consume. The carrier-driven UPDATE path uses {@link #setGroupsOf} instead.
     * {@code tia.setFields()} is always empty post-R266, so this never sees nested input.
     */
    private static List<SetGroup> setGroupsOfFields(List<InputField.SetField> setFields) {
        var out = new ArrayList<SetGroup>();
        for (var sf : setFields) {
            out.add(new SetGroup(sf.name(), setFieldColumns(sf), setFieldNodeIdExtraction(sf), List.of(sf.name())));
        }
        return out;
    }

    // ---- R186 nested-input wire-access helpers ----------------------------------------------
    //
    // A leaf flattened out of a NestingField carries a CallSiteExtraction.NestedInputField whose
    // path() is the SDL key chain from the @table argument root to the leaf. A top-level leaf
    // carries its plain extraction (Direct / NodeIdDecodeKeys) and the access path is just its own
    // SDL field name, so every emit site below collapses to byte-identical pre-R186 output for the
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
     * pre-R186 SET put). Deeper → nested {@code if (containsKey) { var o = get; if (o instanceof
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

    /** R186: a leaf carrier flattened out of (possibly) a {@link InputField.NestingField}, paired
     *  with its wire access path. Produced by {@link #flattenInsertLeaves} so the INSERT emitters
     *  walk a flat leaf list (never a {@code NestingField}) with a per-leaf descent path. */
    private record InsertLeaf(InputField field, List<String> path) {}

    /**
     * Flatten {@code fields} into leaf carriers in declaration order, descending into any
     * {@link InputField.NestingField} grouping input (R186) and accumulating the SDL access path.
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
     * R246: project the UPDATE carrier's flat {@link SetColumn} list back into per-field
     * {@link SetGroup}s, grouping by wire access path in encounter order. A composite-NodeId field
     * contributes several {@code SetColumn}s sharing one path; they regroup into one
     * {@code SetGroup} whose columns line up positionally with the decode {@code Record<N>} slots.
     *
     * <p>R186: grouping is by access path (the leaf's {@code NestedInputField.path()} or
     * {@code [sdlFieldName]} for a top-level leaf) rather than by SDL field name, so two leaves with
     * the same local name under different nested groups stay distinct; the leaf extraction is peeled
     * out of the {@code NestedInputField} envelope before the NodeId check. For top-level leaves the
     * path is {@code [name]}, so the grouping and the resulting {@code SetGroup}s are byte-identical
     * to the pre-R186 by-name grouping.
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
     * R246: project the UPDATE carrier's flat {@link KeyColumn} list into the
     * {@link InputColumnBindingGroup}s the lookup-WHERE emitters consume, grouping by wire access
     * path in encounter order. A single-column field becomes a {@code MapGroup} (carrying its
     * extraction so an arity-1 NodeId still routes through the decode local); a multi-column field
     * becomes a {@code DecodedRecordGroup} whose positional {@code RecordBinding}s mirror the decode
     * {@code Record<N>} slots. This reconstructs exactly the {@code fieldBindings()} shape the legacy
     * {@code TableInputArg} produced for the WHERE half.
     *
     * <p>R186: grouping is by access path (same rationale as {@link #setGroupsOf}). A
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
     * R189: emits {@code Map<Field<?>, Object>} {@code .put(t.col, DSL.val(value, t.col.getDataType()))}
     * statements for each {@code SetField} on {@code setFields}, guarded by a presence check on
     * the SDL field name. The walk is uniform across the four admissible SetField shapes:
     *
     * <ul>
     *   <li>Direct ColumnField — one {@code put}; value reads {@code mapLocal.get(name)}.</li>
     *   <li>NodeId-decoded ColumnField — one {@code put}; declares a per-field decode local
     *       inside the conditional and reads {@code decodeLocal.value1()}.</li>
     *   <li>CompositeColumnField — N {@code put}s (one per slot); declares a per-field decode
     *       local and reads {@code decodeLocal.value<i+1>()} for slot i.</li>
     *   <li>ColumnReferenceField / CompositeColumnReferenceField — same as the same-table NodeId
     *       arms but target columns are {@code liftedSourceColumns()} (FK columns on the input's
     *       own table) rather than {@code column() / columns()}.</li>
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
        // per-row "row" Map); the R186 nested-descent walk uses that one Map local as both the
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
     * R322 (D3) on the single-row UPDATE SET path: emits the value-agreement preamble for every SET
     * column written by more than one carrier where at least one is a {@code @nodeId} decode (the
     * all-plain SET overlap is the validate-time {@link UpdateRowsWalker} reject, so it never reaches
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
        // R356: the per-column grouping is the shared ColumnOverlap.groupByColumn; this site forks on
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
     * R354: cross-partition (WHERE∩SET) value-agreement preamble for the single-row UPDATE. A
     * self-FK {@code @reference} routes its lifted columns wholly to SET ({@code UpdateRowsWalker})
     * while the row identity comes from the WHERE (matched-key) partition, so a column the self-FK
     * shares with the identity field (e.g. {@code email.mailbox_id}: the FK
     * {@code email_in_reply_to_fk} shares {@code mailbox_id} with the PK) appears in BOTH partitions.
     * The FK constraint forces the two equal for any well-formed input, but a malformed input could
     * disagree, so this checks agreement before the DML runs. This is the WHERE↔SET boundary R322's
     * same-clause {@link #emitSetAgreementPreamble} never crossed; it is a different operation from the
     * within-clause grouping, intersecting two partitions rather than grouping one, so R356 leaves it a
     * named sibling: it adopts the shared {@link SetGroupWriter} leaf view and the value-read seam
     * ({@link #emitAgreementDecodeLocal} / {@link #appendAgreementValue}, which R356 generalized to also
     * serve sites 2 and 4) but keeps its bespoke intersection walk, since a single site with no drift
     * partner does not earn an extracted {@code intersectByColumn} primitive (and routing it through
     * {@code groupByColumn} would either widen the contributor with a partition tag the within-clause
     * sites never set, or fold each partition twice and intersect, which is not what it does).
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
        // R356: both partitions adapt into the shared SetGroupWriter leaf view; the bespoke intersection
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

    /** R354: re-decode a key/set group's {@code @nodeId} wire value into a preamble-local record,
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

    /** R354: append one side's present value for the shared column to the agreement list. A decode
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
     * R189: emits the UPSERT DO-UPDATE {@code setsUpdate.put(t.col, DSL.excluded(t.col))} statements
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
     * R342 / R356: the per-column plan for the bulk UPDATE SET clause, the SET analogue of
     * {@link #insertColumnPlan}, now the shared {@link ColumnOverlap#groupByColumn} over {@link SetGroupWriter}
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
     * R342: the first-row presence gate for a bulk-SET plan column. A disjoint column keeps its single
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
     * R189 / R342: appends {@code vColNames.add(t.col.getName())} for each distinct bulk-SET plan column,
     * gated on the column's first-row presence ({@link #setColumnPresenceGate}). One entry per distinct
     * column (was: one per group-column). A column already supplied by the WHERE side
     * ({@code lookupSqlNames}, the self-FK cross-partition case) is skipped here — the lookup-key v-column
     * already carries it, and re-adding would reintroduce the duplicate-{@code v}-column crash R342 removes.
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
     * row? Single segment → {@code firstKeys.contains(name)} (byte-identical). Nested (R186) →
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
     * R189 / R342: emits the bulk-UPDATE per-row {@code cells.add(...)} list off the {@link #setColumnPlan}
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
     *       R354's {@link #appendAgreementValue}), pairwise-checks them through
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
     * R342: emits the per-row decode locals for the bulk SET clause — one {@code Record<N>} per
     * {@link SetGroup} carrying a {@code @nodeId}, declared once per row. Mirrors
     * {@link #buildInsertDecodeLocals}: the local is declared unconditionally with an {@code instanceof
     * String} guard (absent / non-string wire value → {@code null}) and a presence-gated null-check throw
     * (a present-but-mismatched id surfaces the same {@code GraphqlErrorException} as the single-row path).
     * Declaring it once at the top of the row body — rather than inside each cell's presence gate as the
     * pre-R342 emitter did — is what lets a composite group's several cells and a shared column's gather
     * all read one decode without re-decoding per writer.
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
     * R189 / R342: emits {@code sets.put(t.col, v.field(t.col))} off the {@link #setColumnPlan} (one put
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
     * R342: the bulk-path WHERE∩SET value-agreement, the per-row analogue of the single-row
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
    private static MethodSpec buildMutationUpdateFetcher(TypeFetcherEmissionContext ctx, MutationField.MutationUpdateTableField f,
                                                          String outputPackage) {
        // R246: SET / WHERE partition and the matched-key identity come off the UpdateRows carrier
        // (updateRows.setColumns() / keyColumns()) and the slim arg surface (inputArg) instead of
        // a TableInputArg. The emitted SQL is structurally identical to the legacy shape; only the
        // source of the partition changed. Carrier slots project back into the SetGroup /
        // InputColumnBindingGroup shapes the shared SET / lookup-WHERE emitters consume.
        var inputArg = f.inputArg();
        var tableRef = inputArg.table();
        var tablesOnly = GeneratorUtils.ResolvedTableNames.ofTable(tableRef);
        String tableLocal = tablesOnly.tableLocalName();
        var setGroups = setGroupsOf(f.updateRows().setColumns());
        var keyGroups = keyGroupsOf(f.updateRows().keyColumns());

        if (inputArg.list()) {
            return buildBulkUpdateFetcher(ctx, f, outputPackage, inputArg, tableRef, tablesOnly, tableLocal,
                setGroups, keyGroups);
        }

        // Single-row UPDATE: build the SET clause dynamically from the present-key set so absent
        // fields drop out (PATCH semantics) and explicit-null fields bind typed null. The map
        // is consumed by jOOQ's `.set(Map<? extends Field<?>, ?>)` overload, which preserves
        // the chain shape (`UpdateSetMoreStep<R>` → `.where(...).returningResult(...)`).
        var fieldClass = ClassName.get("org.jooq", "Field");
        var linkedHashMap = ClassName.get("java.util", "LinkedHashMap");
        var postInGuard = CodeBlock.builder();
        postInGuard.addStatement("$T<$T<?>, Object> sets = new $T<>()", MAP, fieldClass, linkedHashMap);
        // R322 (D3): value-agreement preamble for any SET column written by more than one carrier with a
        // @nodeId decode among them; the silent last-write-wins the Map.put below would otherwise allow.
        // No-op (byte-identical) when there is no such overlap.
        emitSetAgreementPreamble(postInGuard, setGroups, "in", "setKey", tablesOnly, tableRef);
        // R354: cross-partition (WHERE∩SET) value-agreement preamble. A self-FK @reference routes its
        // lifted columns wholly to SET while the row identity comes from the WHERE key, so a column the
        // self-FK shares with the identity field (e.g. email.mailbox_id) sits in both partitions; the FK
        // forces them equal, this checks it before the DML. Key-side groups are projected into the
        // SetGroup shape (by access path, nidk peeled) so the preamble reads each side's slot uniformly.
        // No-op (byte-identical) when there is no key∩set overlap.
        var keySetGroups = setGroupsOf(f.updateRows().keyColumns().stream()
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

        return buildDmlFetcher(ctx, f.name(), f.returnExpression(), f.errorChannel(),
            inputArg.name(), tableRef, tablesOnly, tableLocal,
            outputPackage, dmlChain, f.dialectRequirement(), postInGuard.build(), inputArg.list());
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
     * jOOQ renders here. R63 lifted both this guard and UPSERT's Oracle-dialect guard off inline
     * {@code postDslGuard} {@link CodeBlock}s onto typed {@code DialectRequirement}, rendered by
     * {@link #emitDialectGuard}.
     */
    private static MethodSpec buildBulkUpdateFetcher(TypeFetcherEmissionContext ctx,
                                                     MutationField.MutationUpdateTableField f,
                                                     String outputPackage,
                                                     no.sikt.graphitron.rewrite.model.InputArgRef inputArg,
                                                     TableRef tableRef,
                                                     GeneratorUtils.ResolvedTableNames tablesOnly,
                                                     String tableLocal,
                                                     List<SetGroup> setGroups,
                                                     List<InputColumnBindingGroup> keyGroups) {
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
        // R342: SET columns whose backing column is already a WHERE/lookup-key v-column (the self-FK
        // cross-partition overlap). The two v-populating SET emitters skip these so the column appears in
        // v once; emitBulkKeySetAgreement checks the WHERE and SET writers agreed.
        var lookupSqlNames = new LinkedHashSet<String>();
        for (var col : lookupTargetColumns) lookupSqlNames.add(col.sqlName());

        var postInGuard = CodeBlock.builder();
        postInGuard.addStatement("$T<?> firstKeys = in.get(0).keySet()", SET);
        postInGuard.add(buildUniformShapeGuard("UPDATE"));
        // R186: a nested SET leaf is in the column list iff present in the first row; every row must
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
        // R342: WHERE∩SET per-row value agreement (self-FK shared column), reusing the bulkKey / bulkSetKey
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

        // R63: the Postgres-only dialect guard (UPDATE ... FROM (VALUES ...) is a Postgres
        // extension) is now a typed DialectRequirement.RequiresFamily(POSTGRES) on the model,
        // rendered by emitDialectGuard; the inline postDslGuard CodeBlock is gone.
        return buildDmlFetcher(ctx, f.name(), f.returnExpression(), f.errorChannel(),
            inputArg.name(), tableRef, tablesOnly, tableLocal,
            outputPackage, dmlChain, f.dialectRequirement(), postInGuard.build(), inputArg.list());
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

        // R63: jOOQ silently translates `.onConflict(...).doUpdate()` (and `.doNothing()`) to an
        // Oracle `MERGE INTO ...` statement whose concurrency, conflict-key matching, and
        // `RETURNING` semantics differ from PostgreSQL `ON CONFLICT`, and it exposes no setting to
        // disable the emulation. The Oracle rejection is now a typed
        // DialectRequirement.RejectsFamily(ORACLE) on the model, rendered by emitDialectGuard as a
        // self-contained `dsl.dialect().family().name().equals("ORACLE")` check (jOOQ's family()
        // folds every commercial ORACLE* version to ORACLE, so the guard still gates them all); the
        // inline postDslGuard CodeBlock is gone.
        return buildDmlFetcher(ctx, f.name(), f.returnExpression(), f.errorChannel(),
            tia.name(), tableRef, tablesOnly, tableLocal,
            outputPackage, dmlChain.build(), f.dialectRequirement(), postInGuard.build(), tia.list());
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
     * R186: per-nesting-node shape guards for bulk UPDATE. For each distinct nesting prefix that a
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
     *       (with {@code ThrowOnMismatch} / {@code SkipMismatchedElement} null handling) above,
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
        return buildLookupWhereSingleRow(tia.fieldBindings(), tablesOnly, tableRef, mapLocal);
    }

    /**
     * R246: the lookup-WHERE chunk built from already-projected {@link InputColumnBindingGroup}s
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
     * via {@link ArgCallEmitter#nestedMapValueExpr} from the binding's R186 access path (peeled from
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
        appendDecodeLocal(locals, recLocal, nidk, CodeBlock.of("$L.get($S)", mapLocal, sourceField), sourceField);
    }

    /**
     * R186 overload: declare the decode local from an arbitrary wire-value expression rather than a
     * plain {@code mapLocal.get(sourceField)}, so a NodeId leaf buried in a nested grouping input
     * reads via the null-safe descent ({@link ArgCallEmitter#nestedMapValueExpr}). {@code sourceField}
     * names the leaf for the error message only. For a top-level leaf the convenience overload above
     * passes {@code mapLocal.get(sourceField)}, byte-identical to before R186.
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
     * <p>R266: the direct-return bulk DELETE projects its {@link DeleteRows} carrier's
     * {@code whereColumns()} into these groups via {@link #keyGroupsOf} and calls this directly,
     * so there is no longer a {@code TableInputArg}-taking overload.
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
            String fetcherName,
            no.sikt.graphitron.rewrite.model.DmlReturnExpression rex,
            Optional<ErrorChannel> errorChannel,
            String inputArgName,
            TableRef tableRef,
            GeneratorUtils.ResolvedTableNames tablesOnly,
            String tableLocal,
            String outputPackage,
            CodeBlock dmlChain,
            DialectRequirement dialectRequirement,
            boolean listInput) {
        return buildDmlFetcher(ctx, fetcherName, rex, errorChannel, inputArgName, tableRef,
            tablesOnly, tableLocal, outputPackage, dmlChain,
            dialectRequirement, /*postInGuard=*/ CodeBlock.of(""), listInput);
    }

    /**
     * The typed dialect guard plus the optional {@code postInGuard} {@link CodeBlock} and the
     * bulk-input cardinality bit:
     * <ul>
     *   <li>{@code dialectRequirement} — R63: the verb's typed dialect constraint, always present
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
            String fetcherName,
            no.sikt.graphitron.rewrite.model.DmlReturnExpression rex,
            Optional<ErrorChannel> errorChannel,
            String inputArgName,
            TableRef tableRef,
            GeneratorUtils.ResolvedTableNames tablesOnly,
            String tableLocal,
            String outputPackage,
            CodeBlock dmlChain,
            DialectRequirement dialectRequirement,
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

        builder.addCode(emitDmlReturnExpression(rex, valueType, tableRef, tablesOnly,
            outputPackage, tableLocal, dmlChain));
        builder.addCode(returnSyncSuccess(valueType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(catchArm(outputPackage, errorChannel));
        builder.endControlFlow();
        return builder.build();
    }

    /**
     * R63: renders the request-time dialect guard from the model's typed
     * {@link DialectRequirement}, replacing the two hand-built {@code postDslGuard}
     * {@link CodeBlock}s (UPSERT's Oracle gate, bulk UPDATE's Postgres gate) with a single helper
     * keyed on the typed arm. Both the {@code RequiresFamily} and {@code RejectsFamily} arms compare
     * {@code dsl.dialect().family().name()} against the family's {@link SqlDialectFamily#jooqFamilyName()}
     * and throw an {@link UnsupportedOperationException} carrying the model's {@code reason()}.
     *
     * <p>The check uses jOOQ's own {@code SQLDialect.family()} rather than
     * {@link SqlDialectFamily#fromDialectName(String)} because emitted code cannot reference the
     * generator-internal {@code SqlDialectFamily} enum: the {@code graphitron} artifact is on a
     * consumer's <em>test</em> classpath only, while these fetchers compile as the consumer's main
     * sources (the generate mojo adds them via {@code addCompileSourceRoot}); generated code sees
     * only its own output package plus jOOQ. jOOQ's {@code family()} collapses every versioned
     * dialect spelling onto its family constant, so the check gates every version of the family:
     * it matches the intent of UPSERT's original {@code name().startsWith("ORACLE")} (all Oracle
     * versions fold to {@code ORACLE}) and reproduces bulk UPDATE's original
     * {@code family().name().equals("POSTGRES")} byte-for-byte. The {@link DialectRequirement.None}
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
        builder.addCode(noChannelCatchArm(outputPackage));
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
                        sortParts.add("$L.$L.$L()", srcAlias, col.column().javaName(), col.direction().jooqMethodName());
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
                        parts.add("$L.$L.$L()", srcAlias, col.column().javaName(), col.direction().jooqMethodName());
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
                    sortParts.add("$L.$L.$L()", srcAlias, col.column().javaName(), col.direction().jooqMethodName());
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
        builder.addCode(noChannelCatchArm(outputPackage));
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
    private static MethodSpec buildQueryLookupRowsMethod(TypeFetcherEmissionContext ctx, QueryField.QueryLookupTableField field, String outputPackage,
            CompositeDecodeHelperRegistry registry) {
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
        // R330: declare an aliased FK-target table local per join hop for every FK-target @nodeId
        // override @condition, so each is emitted as a correlated EXISTS rather than mis-passing the
        // lookup's own table. Top-level (non-recursive) method, so static SQL aliases suffice.
        var fkDeclarations = CodeBlock.builder();
        var fkTargetAliases = FkTargetConditionEmitter.declareAliases(fkDeclarations, field.filters(), tableLocal, false);
        builder.addCode(fkDeclarations.build());
        for (var filter : field.filters()) {
            for (var param : filter.callParams()) {
                if (param.extraction() instanceof CallSiteExtraction.JooqConvert && param.list()) {
                    builder.addStatement("$T<$T> $L = env.getArgument($S)",
                        LIST, String.class, toCamelCase(param.name()) + "Keys", param.name());
                }
            }
            builder.addStatement("condition = condition.and($L)",
                FkTargetConditionEmitter.emitTerm(ctx, filter, tableLocal, registry, null, fkTargetAliases));
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
     * R75 Phase 1: emits the fetcher for a {@link MutationField.MutationDmlRecordField} — the
     * record-returning DML mutation. Body is two-step: the DML chain (per-kind) runs inside
     * {@code dsl.transactionResult(tx -> DSL.using(tx)....)}, projects the input table's PK
     * columns via {@code .returningResult(PK1, PK2, ...)}, and returns a single
     * {@code RecordN<...>} via {@code .fetchOne()}. The transaction commits when
     * {@code transactionResult} returns; the materialised key Record outlives it, and the
     * response SELECT happens later in the data field's
     * {@link ChildField.RecordTableField} fetcher — outside the transaction, so read
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
    private static MethodSpec buildMutationDmlRecordFetcher(
            TypeFetcherEmissionContext ctx, MutationField.MutationDmlRecordField f, String outputPackage) {
        var tia = f.tableInputArg();
        // DML chain per kind. Each branch produces a CodeBlock starting with `.<verb>(...)`
        // suitable for chaining off `DSL.using(tx)` inside transactionResult.
        return buildSingleRecordTwoStepFetcher(
            ctx, f.name(), tia.name(), tia.inputTable(), f.errorChannel(), f.qualifiedName(),
            (tablesOnly, tableLocal) -> buildDmlChainForRecord(f, tia, tia.inputTable(), tablesOnly, tableLocal),
            outputPackage);
    }

    /**
     * R258: the two-step single-record DML emit skeleton shared by
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
            TypeFetcherEmissionContext ctx, String fieldName, String argName,
            TableRef tableRef, Optional<ErrorChannel> errorChannel, String qualifiedName,
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
        var dslContextClass = ClassName.get("org.jooq", "DSLContext");

        var builder = MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(payloadType))
            .addParameter(ENV, "env");
        builder.beginControlFlow("try");
        builder.addStatement("$T dsl = $L.getDslContext(env)", dslContextClass, ctx.graphitronContextCall());
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

        builder.addCode(returnSyncSuccess(payloadType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(catchArm(outputPackage, errorChannel,
            singleRecordSentinelFor(tableRef, tablesOnly, pkCols)));
        builder.endControlFlow();
        return builder.build();
    }

    /**
     * R258: emits the fetcher for a {@link MutationField.MutationUpdatePayloadField} — the
     * payload-returning single UPDATE. Reuses {@link #buildSingleRecordTwoStepFetcher}'s skeleton;
     * the SET / WHERE partition is sourced from the {@link UpdateRows} carrier via the shared
     * {@link #setGroupsOf} / {@link #keyGroupsOf} projections (the same source R246's direct-return
     * {@link #buildMutationUpdateFetcher} reads), never from {@code tia.setFields()} /
     * {@code tia.fieldBindings()}, so the payload UPDATE no longer depends on {@code @value}.
     */
    private static MethodSpec buildMutationUpdatePayloadFetcher(
            TypeFetcherEmissionContext ctx, MutationField.MutationUpdatePayloadField f, String outputPackage) {
        var inputArg = f.inputArg();
        var setGroups = setGroupsOf(f.updateRows().setColumns());
        var keyGroups = keyGroupsOf(f.updateRows().keyColumns());
        return buildSingleRecordTwoStepFetcher(
            ctx, f.name(), inputArg.name(), inputArg.table(), f.errorChannel(), f.qualifiedName(),
            (tablesOnly, tableLocal) -> buildCarrierUpdateChainSingle(
                setGroups, keyGroups, inputArg.table(), tablesOnly, tableLocal),
            outputPackage);
    }

    /**
     * R266: emits the fetcher for a {@link MutationField.MutationDeletePayloadField} — the
     * payload-returning single DELETE. Reuses {@link #buildSingleRecordTwoStepFetcher}'s skeleton;
     * the WHERE columns are sourced from the {@link DeleteRows} carrier via {@link #keyGroupsOf}
     * (never from {@code tia.fieldBindings()}) and fed to the carrier-driven
     * {@link #buildRecordDeleteChain}. The enclosing skeleton appends
     * {@code .returningResult(pkCols).fetchOne()} inside {@code transactionResult}.
     */
    private static MethodSpec buildMutationDeletePayloadFetcher(
            TypeFetcherEmissionContext ctx, MutationField.MutationDeletePayloadField f, String outputPackage) {
        var inputArg = f.inputArg();
        var whereGroups = keyGroupsOf(f.deleteRows().whereColumns());
        return buildSingleRecordTwoStepFetcher(
            ctx, f.name(), inputArg.name(), inputArg.table(), f.errorChannel(), f.qualifiedName(),
            (tablesOnly, tableLocal) -> buildRecordDeleteChain(
                whereGroups, inputArg.table(), tablesOnly, tableLocal),
            outputPackage);
    }

    /**
     * R258: the carrier-driven single-row UPDATE chain for the payload-returning UPDATE fetcher.
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
            // R266: DELETE is carved off onto MutationDeletePayloadField (its own fetcher calls
            // buildRecordDeleteChain with the carrier's WHERE groups); the compact-constructor on
            // MutationDmlRecordField rejects DELETE, so this arm is unreachable.
            case DELETE -> throw new IllegalStateException(
                "MutationDmlRecordField cannot carry DmlKind.DELETE — R266 routes the payload-"
                + "returning DELETE onto MutationDeletePayloadField; this leaf carries {INSERT, UPSERT}");
        };
    }

    /**
     * R266 — single-row DELETE chain for the payload-returning {@link MutationField.MutationDeletePayloadField}
     * carrier. Mirrors the direct-return DELETE chain ({@link #buildMutationDeleteFetcher}): same
     * WHERE shape, no SET clause. The WHERE columns are sourced from the {@link DeleteRows} carrier's
     * {@code whereColumns()} (projected to {@link InputColumnBindingGroup}s via {@link #keyGroupsOf}),
     * not {@code tia.fieldBindings()}, so the payload DELETE no longer depends on a {@code TableInputArg}.
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
        emitSetMapPuts(preGuard, setGroupsOfFields(tia.setFields()), "sets", "in", "in",
            "setKey", tablesOnly, tableRef);
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
    private static MethodSpec buildMutationBulkDmlRecordFetcher(
            TypeFetcherEmissionContext ctx, MutationField.MutationBulkDmlRecordField f, String outputPackage) {
        var tia = f.tableInputArg();
        return buildBulkRecordTwoStepFetcher(
            ctx, f.name(), tia.name(), tia.inputTable(), f.errorChannel(), f.qualifiedName(),
            (tablesOnly, tableLocal, pkCols, recordRowType) ->
                buildBulkRecordPerRowBody(f, tia, tia.inputTable(), tablesOnly, tableLocal, pkCols, recordRowType),
            outputPackage);
    }

    /** R258: the seam for {@link #buildBulkRecordTwoStepFetcher}'s per-row DML body. */
    @FunctionalInterface
    private interface BulkPerRowBodyFn {
        CodeBlock build(GeneratorUtils.ResolvedTableNames tablesOnly, String tableLocal,
                        List<no.sikt.graphitron.rewrite.model.ColumnRef> pkCols, TypeName recordRowType);
    }

    /**
     * R258: the bulk two-step DML emit skeleton shared by {@link #buildMutationBulkDmlRecordFetcher}
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
            TypeFetcherEmissionContext ctx, String fieldName, String argName,
            TableRef tableRef, Optional<ErrorChannel> errorChannel, String qualifiedName,
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

        var builder = MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(resultType))
            .addParameter(ENV, "env");
        builder.beginControlFlow("try");
        builder.addStatement("$T dsl = $L.getDslContext(env)", dslContextClass, ctx.graphitronContextCall());
        builder.addStatement("$T<$T<?, ?>> in = env.getArgument($S)",
            LIST, MAP, argName);
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

        builder.addCode(returnSyncSuccess(resultType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(catchArm(outputPackage, errorChannel,
            bulkRecordSentinelFor(tableRef, tablesOnly, pkCols)));
        builder.endControlFlow();
        return builder.build();
    }

    /**
     * R258: emits the fetcher for a {@link MutationField.MutationBulkUpdatePayloadField} — the
     * payload-returning bulk UPDATE. Reuses {@link #buildBulkRecordTwoStepFetcher}'s skeleton; the
     * per-row SET / WHERE partition is sourced from the {@link UpdateRows} carrier (not
     * {@code tia.setFields()} / {@code tia.fieldBindings()}), so the bulk payload UPDATE no longer
     * depends on {@code @value}. Reusing the legacy {@link #buildBulkRecordPerRowUpdateBody} would
     * keep the partition on {@code @value} and break exactly the path this slice fixes once
     * {@code @value} leaves the schema.
     */
    private static MethodSpec buildMutationBulkUpdatePayloadFetcher(
            TypeFetcherEmissionContext ctx, MutationField.MutationBulkUpdatePayloadField f, String outputPackage) {
        var inputArg = f.inputArg();
        var setGroups = setGroupsOf(f.updateRows().setColumns());
        var keyGroups = keyGroupsOf(f.updateRows().keyColumns());
        return buildBulkRecordTwoStepFetcher(
            ctx, f.name(), inputArg.name(), inputArg.table(), f.errorChannel(), f.qualifiedName(),
            (tablesOnly, tableLocal, pkCols, recordRowType) -> buildCarrierBulkPerRowUpdateBody(
                setGroups, keyGroups, inputArg.table(), tablesOnly, tableLocal, pkCols, recordRowType),
            outputPackage);
    }

    /**
     * R266: emits the fetcher for a {@link MutationField.MutationBulkDeletePayloadField} — the
     * payload-returning bulk DELETE. Reuses {@link #buildBulkRecordTwoStepFetcher}'s skeleton; the
     * per-row WHERE columns are sourced from the {@link DeleteRows} carrier ({@link #keyGroupsOf}),
     * not {@code tia.fieldBindings()}, and fed to the carrier-driven
     * {@link #buildBulkRecordPerRowDeleteBody}. The order-preservation invariant the skeleton's
     * input-order loop provides is audited at the execution tier.
     */
    private static MethodSpec buildMutationBulkDeletePayloadFetcher(
            TypeFetcherEmissionContext ctx, MutationField.MutationBulkDeletePayloadField f, String outputPackage) {
        var inputArg = f.inputArg();
        var whereGroups = keyGroupsOf(f.deleteRows().whereColumns());
        return buildBulkRecordTwoStepFetcher(
            ctx, f.name(), inputArg.name(), inputArg.table(), f.errorChannel(), f.qualifiedName(),
            (tablesOnly, tableLocal, pkCols, recordRowType) -> buildBulkRecordPerRowDeleteBody(
                whereGroups, inputArg.table(), tablesOnly, tableLocal, pkCols, recordRowType),
            outputPackage);
    }

    /**
     * R258: the carrier-driven per-row UPDATE body for the bulk payload-returning UPDATE. Mirrors
     * {@link #buildBulkRecordPerRowUpdateBody} but sources the SET map from the carrier's
     * {@code setColumns()} ({@link #setGroupsOf}) and the WHERE from the carrier's {@code keyColumns()}
     * ({@link #keyGroupsOf}) rather than the {@code @value}-derived {@code tia.setFields()} /
     * {@code tia.fieldBindings()}. The no-match throw preserves the order-preservation invariant.
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
        // exception through the carrier's error channel (R12 wiring).
        body.beginControlFlow("if (rec == null)")
            .addStatement("throw new $T($S + row)", IllegalStateException.class,
                "@mutation(typeName: UPDATE) bulk row matched zero rows; key filter "
                    + "found no target for input row: ")
            .endControlFlow();
        body.add("acc.add(rec);\n");
        return body.build();
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
     * The {@code UPSERT} / {@code DELETE} cases are rejected at the compact-constructor and never
     * reach this dispatch (DELETE is carved off onto {@link MutationField.MutationBulkDeletePayloadField}
     * by R266); both arms throw to guard against a future widening accident.
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
            // R266: DELETE is carved off onto MutationBulkDeletePayloadField (its own fetcher calls
            // buildBulkRecordPerRowDeleteBody with the carrier's WHERE groups); the compact-
            // constructor on MutationBulkDmlRecordField rejects DELETE, so this arm is unreachable.
            case DELETE -> throw new IllegalStateException(
                "MutationBulkDmlRecordField cannot carry DmlKind.DELETE — R266 routes the payload-"
                + "returning bulk DELETE onto MutationBulkDeletePayloadField; this leaf carries {INSERT}");
        };
    }

    /**
     * R266 — per-row DELETE body for the payload-returning {@link MutationField.MutationBulkDeletePayloadField}
     * (driven from {@link #buildMutationBulkDeletePayloadFetcher}). Each input row builds a
     * {@code deleteFrom(table).where(<lookup>).returningResult(PK).fetchOne()} statement; the
     * returned PK-only {@code RecordN} is appended to the bulk accumulator in input order. A row that
     * matches no target raises {@link IllegalStateException} with the same shape as the UPDATE
     * no-match path — input-order preservation is a contract of the bulk-DML emit, and silent
     * skipping would break it. The per-row WHERE columns are sourced from the {@link DeleteRows}
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
        emitSetMapPuts(body, setGroupsOfFields(tia.setFields()), "sets", "row", "row",
            "setKey", tablesOnly, tableRef);
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
            RowsMethodCall.batchLoaderLambda(bkf.rowsMethodName(), keyType, registration),
            keyExtraction,
            asyncWrapTail(valueType, outputPackage, Optional.empty()));
    }

    /**
     * Emits the DataLoader name construction for the rewrite emitter. The name is path-only —
     * {@code env.getExecutionStepInfo().getPath().getKeysOnly()} joined by {@code "/"}. The path
     * is Graphitron-controlled; implementers cannot accidentally produce a colliding name.
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
            .addStatement("$T name = $T.join($S, env.getExecutionStepInfo().getPath().getKeysOnly())",
                String.class, String.class, "/")
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
    private static <T extends GraphitronField & BatchKeyField> MethodSpec
            buildRecordBasedDataFetcher(TypeFetcherEmissionContext ctx, T field,
                    ReturnTypeRef.TableBoundReturnType returnType,
                    SourceKey sourceKey,
                    GraphitronType.ResultType resultType, boolean sourceIsOutcome,
                    String outputPackage) {

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

        // R268 arm-switch: under a flipped Outcome payload the source is a non-null Outcome, so
        // narrow Success ahead of the loader registration (returning completedFuture(null) on the
        // ErrorList arm) and read the key off success.value() — the same backing object the
        // non-wrapped source would have been (R244's Success.value() invariant). The key-extraction
        // logic is reused verbatim; only its source binding moves from env.getSource() to
        // success.value().
        CodeBlock prelude;
        CodeBlock keyExtraction;
        if (sourceIsOutcome) {
            var successClass = ClassName.get(outputPackage + ".schema", "Outcome").nestedClass("Success");
            var completableFuture = ClassName.get("java.util.concurrent", "CompletableFuture");
            prelude = CodeBlock.builder()
                .beginControlFlow("if (!(env.getSource() instanceof $T<?> success))", successClass)
                .addStatement("return $T.completedFuture(null)", completableFuture)
                .endControlFlow()
                .build();
            keyExtraction = GeneratorUtils.buildRecordParentKeyExtraction(
                sourceKey, resultType, CodeBlock.of("success.value()"));
        } else {
            // R305: short-circuit on a null source. The LocalContext errors transport fires the
            // data-channel fetcher with a null source (data(null).localContext(errors)); the former
            // SingleRecordTableField carrier guarded this explicitly, and RecordTableField (its
            // collapse successor) must too. Harmless for the ordinary record-source case, where the
            // parent record is never null.
            var completableFuture = ClassName.get("java.util.concurrent", "CompletableFuture");
            prelude = CodeBlock.builder()
                .beginControlFlow("if (env.getSource() == null)")
                .addStatement("return $T.completedFuture(null)", completableFuture)
                .endControlFlow()
                .build();
            keyExtraction = GeneratorUtils.buildRecordParentKeyExtraction(sourceKey, resultType);
        }

        return DataLoaderFetcherEmitter.build(
            field.name(),
            keyType, valueType, asyncResultType(resultValueType),
            registration,
            RowsMethodCall.batchLoaderLambda(field.rowsMethodName(), keyType, registration),
            prelude,
            keyExtraction,
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
            return noChannelCatchArm(outputPackage);
        }
        return switch (errorChannel.get()) {
            // R244 additive window: Mapped is not produced yet (the Outcome-wrapper emit seam,
            // ChannelCatchArmEmitter, lands in a later slice-1 commit). Handle the arm so the
            // sealed switch compiles; it is unreachable until the in-scope fields flip.
            case ErrorChannel.Mapped m -> throw new IllegalStateException(
                "catchArm reached ErrorChannel.Mapped before the Outcome-wrapper emit seam landed");
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
        return CodeBlock.of("return $T.dispatchToLocalContext(e, $T.$L, env, $L);\n",
            errorRouterClass(outputPackage),
            errorMappingsClass(outputPackage),
            channel.mappingsConstantName(),
            sentinel);
    }

    /**
     * Builds the standard catch arm for a synchronous fetcher without a typed-error channel: route
     * the throw through {@code ErrorRouter.surfaceClientErrorOrRedact} (emitted at
     * {@code <outputPackage>.schema.ErrorRouter}). A {@code GraphitronClientException} (e.g. a
     * malformed/wrong-type {@code @nodeId} filter id) surfaces its real message; every other
     * throwable still redacts to a correlation id (R378). Behaviour changes only for the
     * client-error marker type, so the blast radius is bounded to those throws.
     */
    private static CodeBlock noChannelCatchArm(String outputPackage) {
        return CodeBlock.of("return $T.surfaceClientErrorOrRedact(e, env);\n", errorRouterClass(outputPackage));
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
     * {@code rewrite-design-principles.adoc} the bound setter is called first for diagnostic
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
                // R244 additive window: Mapped is not produced yet; the async Outcome-wrapper tail
                // lands with the in-scope flip. Handle the arm so the sealed switch compiles.
                case ErrorChannel.Mapped m -> throw new IllegalStateException(
                    "asyncWrapTail reached ErrorChannel.Mapped before the Outcome-wrapper emit seam landed");
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
