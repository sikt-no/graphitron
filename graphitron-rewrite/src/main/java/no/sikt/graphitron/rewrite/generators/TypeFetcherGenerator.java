package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.util.ColumnFetcherClassGenerator;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.RewriteConfig;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import javax.lang.model.element.Modifier;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Generates a {@link TypeSpec} for one {@code <TypeName>Fetchers} class in {@code rewrite.types}.
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
 */
public class TypeFetcherGenerator {

    public static List<TypeSpec> generate(GraphitronSchema schema) {
        return schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof GraphitronType.TableType
                      || e.getValue() instanceof GraphitronType.NodeType
                      || e.getValue() instanceof GraphitronType.RootType)
            .map(Map.Entry::getKey)
            .sorted()
            .map(typeName -> generateForType(schema, typeName))
            .toList();
    }

    private static TypeSpec generateForType(GraphitronSchema schema, String typeName) {
        var type = schema.type(typeName);
        var fields = schema.fieldsOf(typeName).stream()
            .filter(f -> !(f instanceof GraphitronField.NotGeneratedField))
            .filter(f -> !(f instanceof GraphitronField.UnclassifiedField))
            .sorted(Comparator.comparing(GraphitronField::name))
            .toList();
        TableRef parentTable = type instanceof GraphitronType.TableBackedType tbt ? tbt.table() : null;
        return generateTypeSpec(typeName, parentTable, fields);
    }

    private static final ClassName ENV                  = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName SELECTED_FIELD       = ClassName.get("graphql.schema", "SelectedField");
    private static final ClassName TYPE_WIRING          = ClassName.get("graphql.schema.idl", "TypeRuntimeWiring");
    private static final ClassName WIRING_BUILDER       = ClassName.get("graphql.schema.idl", "TypeRuntimeWiring", "Builder");
    private static final ClassName COMPLETABLE_FUTURE   = ClassName.get("java.util.concurrent", "CompletableFuture");
    private static final ClassName LIST                 = ClassName.get("java.util", "List");
    private static final ClassName CONDITION             = ClassName.get("org.jooq", "Condition");
    private static final ClassName RECORD               = ClassName.get("org.jooq", "Record");
    private static final ClassName RESULT               = ClassName.get("org.jooq", "Result");
    private static final ClassName ROW                  = ClassName.get("org.jooq", "Row");
    private static final ClassName SORT_FIELD           = ClassName.get("org.jooq", "SortField");
    private static final ClassName DSL                  = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName DATA_LOADER          = ClassName.get("org.dataloader", "DataLoader");
    private static final ClassName DATA_LOADER_FACTORY  = ClassName.get("org.dataloader", "DataLoaderFactory");
    private static final ClassName GRAPHITRON_CONTEXT   = ClassName.get("no.sikt.graphql", "GraphitronContext");

    /**
     * Generates the {@code *Fetchers} class TypeSpec for the given GraphQL type.
     *
     * @param typeName    the GraphQL type name (e.g. {@code "Film"})
     * @param parentTable the resolved {@link TableRef} for the type, or {@code null} for root types
     * @param fields      the classified fields belonging to this type
     */
    static TypeSpec generateTypeSpec(String typeName, TableRef parentTable, List<GraphitronField> fields) {
        var className = typeName + "Fetchers";
        var builder = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC);

        boolean needsGraphitronContextHelper = false;

        for (var field : fields) {
            if (field instanceof ChildField.ColumnField && parentTable != null) {
                // Column fields with a parent table are handled directly in wiring via ColumnFetcher
            } else if (field instanceof QueryField.QueryLookupTableField qlf) {
                builder.addMethod(buildQueryLookupFetcher(qlf));
                builder.addMethod(buildQueryLookupRowsMethod(qlf));
            } else if (field instanceof QueryField.QueryTableField qtf) {
                builder.addMethod(buildQueryTableFetcher(qtf));
                if (hasContextArg(qtf)) needsGraphitronContextHelper = true;
            } else if (field instanceof ChildField.ServiceTableField sf && parentTable != null) {
                builder.addMethod(buildServiceDataFetcher(sf, sf.method(), sf.returnType(), parentTable, className));
                builder.addMethod(buildServiceRowsMethod(sf, sf.method(), sf.returnType(), sf.returnType().table(), parentTable));
                needsGraphitronContextHelper = true;
            } else if (field instanceof ChildField.SplitTableField stf) {
                builder.addMethod(buildSplitQueryDataFetcher(stf));
                builder.addMethod(buildSplitRowsMethod(stf));
            } else if (field instanceof ChildField.SplitLookupTableField slft) {
                builder.addMethod(buildSplitQueryDataFetcher(slft.name(), slft.batchKey()));
                builder.addMethod(buildSplitRowsMethod(slft.name(), slft.batchKey()));
            } else {
                builder.addMethod(buildStub(field.name()));
            }
        }

        if (needsGraphitronContextHelper) {
            builder.addMethod(buildGraphitronContextHelper());
        }

        builder.addMethod(buildWiringMethod(typeName, className, parentTable, fields));
        return builder.build();
    }

    /** Returns true if any filter on this field uses a {@link CallSiteExtraction.ContextArg}. */
    private static boolean hasContextArg(QueryField.QueryTableField qtf) {
        return qtf.filters().stream()
            .flatMap(f -> f.callParams().stream())
            .anyMatch(p -> p.extraction() instanceof CallSiteExtraction.ContextArg);
    }

    /**
     * Generates a fetcher for a root-query table field that builds the condition, optional
     * orderBy, and delegates to the table's {@code selectMany} or {@code selectOne}.
     *
     * <p>Generated code (list variant):
     * <pre>{@code
     * public static Result<Record> films(DataFetchingEnvironment env) {
     *     var condition = DSL.noCondition();
     *     List<SortField<?>> orderBy = List.of();
     *     return Film.selectMany(env, condition, orderBy);
     * }
     * }</pre>
     */
    private static MethodSpec buildQueryTableFetcher(QueryField.QueryTableField qtf) {
        var tableRef = qtf.returnType().table();
        var tableClass = ClassName.get(RewriteConfig.outputPackage() + ".rewrite.types", qtf.returnType().returnTypeName());
        var tablesClass = ClassName.get(RewriteConfig.getGeneratedJooqPackage(), "Tables");
        var jooqTableClass = ClassName.get(RewriteConfig.getGeneratedJooqPackage() + ".tables", tableRef.javaClassName());
        boolean isList = qtf.returnType().wrapper().isList();

        var returnType = isList
            ? ParameterizedTypeName.get(RESULT, RECORD)
            : RECORD;

        var builder = MethodSpec.methodBuilder(qtf.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(returnType)
            .addParameter(ENV, "env");

        builder.addStatement("$T table = $T.$L", jooqTableClass, tablesClass, tableRef.javaFieldName());
        builder.addCode(buildConditionCall(qtf));

        if (isList) {
            builder.addCode(buildOrderByCode(qtf.orderBy()));
            builder.addStatement("return $T.selectMany(env, condition, orderBy)", tableClass);
        } else {
            builder.addStatement("return $T.selectOne(env, condition)", tableClass);
        }

        return builder.build();
    }

    private static CodeBlock buildConditionCall(QueryField.QueryTableField qtf) {
        var code = CodeBlock.builder();
        code.addStatement("$T condition = $T.noCondition()", CONDITION, DSL);
        for (var filter : qtf.filters()) {
            var callArgs = buildCallArgs(filter);
            code.addStatement("condition = condition.and($T.$L($L))",
                ClassName.bestGuess(filter.className()), filter.methodName(), callArgs);
        }
        return code.build();
    }

    /** Builds the argument list for one condition method call: {@code table} first, then one arg per {@link CallParam}. */
    private static CodeBlock buildCallArgs(WhereFilter filter) {
        var args = CodeBlock.builder();
        args.add("table");
        for (var param : filter.callParams()) {
            args.add(", $L", buildArgExtraction(param, filter.className()));
        }
        return args.build();
    }

    private static CodeBlock buildArgExtraction(CallParam param, String conditionsClassName) {
        return switch (param.extraction()) {
            case CallSiteExtraction.Direct ignored ->
                CodeBlock.of("env.getArgument($S)", param.name());
            case CallSiteExtraction.EnumValueOf ev -> {
                var enumClass = ClassName.bestGuess(ev.enumClassName());
                yield CodeBlock.of(
                    "env.getArgument($S) != null ? $T.valueOf(env.<$T>getArgument($S)) : null",
                    param.name(), enumClass, String.class, param.name());
            }
            case CallSiteExtraction.TextMapLookup tl ->
                CodeBlock.of(
                    "env.getArgument($S) != null ? $T.$L.get(env.<$T>getArgument($S)) : null",
                    param.name(), ClassName.bestGuess(conditionsClassName), tl.mapFieldName(),
                    String.class, param.name());
            case CallSiteExtraction.ContextArg ignored ->
                CodeBlock.of("graphitronContext(env).getContextArgument(env, $S)", param.name());
        };
    }

    private static CodeBlock buildOrderByCode(OrderBySpec orderBy) {
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
                        parts.add("table.$L.$L()", col.column().javaName(), fixed.jooqMethodName());
                    }
                    code.addStatement("$T<$T<?>> orderBy = $T.of($L)", LIST, SORT_FIELD, LIST, parts.build());
                }
            }
            case OrderBySpec.Argument arg -> {
                if (arg.base() != null) {
                    code.add(buildOrderByCode(arg.base()));
                } else {
                    code.addStatement("$T<$T<?>> orderBy = $T.of()", LIST, SORT_FIELD, LIST);
                }
            }
            case OrderBySpec.None none ->
                code.addStatement("$T<$T<?>> orderBy = $T.of()", LIST, SORT_FIELD, LIST);
        }
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
    private static MethodSpec buildQueryLookupFetcher(QueryField.QueryLookupTableField field) {
        return MethodSpec.methodBuilder(field.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(RESULT, RECORD))
            .addParameter(ENV, "env")
            .addStatement("return $L(env)", field.lookupMethodName())
            .build();
    }

    /**
     * Generates the lookup rows method that builds the condition and delegates to the table's
     * {@code selectMany}. The method name comes from {@link QueryField.QueryLookupTableField#lookupMethodName()}.
     *
     * <p>List-keyed arguments (annotated {@code @lookupKey} on a {@code [T]} argument) become an
     * IN condition; scalar-keyed arguments become an EQ condition. Type conversion via
     * {@code getDataType().convert()} handles both {@code ID} (delivered as String) and numeric
     * GraphQL scalars transparently.
     *
     * <p>Generated code (single list key):
     * <pre>{@code
     * public static Result<Record> lookupFilmById(DataFetchingEnvironment env) {
     *     Film table = Tables.FILM;
     *     Condition condition = DSL.noCondition();
     *     List<String> filmIdKeys = env.getArgument("film_id");
     *     condition = condition.and(FilmConditions.filmByIdCondition(table, filmIdKeys.stream().map(table.FILM_ID.getDataType()::convert).toList()));
     *     List<SortField<?>> orderBy = List.of();
     *     return Film.selectMany(env, condition, orderBy);
     * }
     * }</pre>
     */
    private static MethodSpec buildQueryLookupRowsMethod(QueryField.QueryLookupTableField field) {
        var tableRef = field.returnType().table();
        var tableClass = ClassName.get(RewriteConfig.outputPackage() + ".rewrite.types", field.returnType().returnTypeName());
        var tablesClass = ClassName.get(RewriteConfig.getGeneratedJooqPackage(), "Tables");
        var jooqTableClass = ClassName.get(RewriteConfig.getGeneratedJooqPackage() + ".tables", tableRef.javaClassName());

        var builder = MethodSpec.methodBuilder(field.lookupMethodName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(RESULT, RECORD))
            .addParameter(ENV, "env");

        builder.addStatement("$T table = $T.$L", jooqTableClass, tablesClass, tableRef.javaFieldName());
        builder.addStatement("$T condition = $T.noCondition()", CONDITION, DSL);

        for (var filter : field.filters()) {
            if (!(filter instanceof GeneratedConditionFilter gcf)) continue;
            // Declare local variables for ID list keys (List<String> — typed to avoid convert() overload ambiguity)
            for (var bp : gcf.bodyParams()) {
                if (bp.list() && "ID".equals(bp.graphqlTypeName())) {
                    builder.addStatement("$T<$T> $L = env.getArgument($S)",
                        LIST, String.class, toCamelCase(bp.name()) + "Keys", bp.name());
                }
            }
            builder.addStatement("condition = condition.and($T.$L($L))",
                ClassName.bestGuess(gcf.className()), gcf.methodName(),
                buildLookupCallArgs(gcf));
        }

        builder.addCode(buildOrderByCode(field.orderBy()));
        builder.addStatement("return $T.selectMany(env, condition, orderBy)", tableClass);
        return builder.build();
    }

    /**
     * Generates a stub that throws {@link UnsupportedOperationException} for unhandled field types.
     *
     * <p>Generated code:
     * <pre>{@code
     * public static Object fieldName(DataFetchingEnvironment env) {
     *     throw new UnsupportedOperationException();
     * }
     * }</pre>
     */
    private static MethodSpec buildStub(String fieldName) {
        return MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(ENV, "env")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    // -----------------------------------------------------------------------
    // ServiceTableField — DataLoader-based async fetcher + batch rows method
    // -----------------------------------------------------------------------

    /**
     * Generates a DataLoader-based async data fetcher for a {@link ChildField.ServiceTableField}.
     *
     * <p>List/connection: returns {@code CompletableFuture<List<Record>>}.
     * Single: returns {@code CompletableFuture<Record>}.
     */
    private static MethodSpec buildServiceDataFetcher(
            ChildField.ServiceTableField sf,
            MethodRef smr,
            ReturnTypeRef.TableBoundReturnType tb,
            TableRef prt,
            String className) {

        boolean isList = tb.wrapper().isList();
        var valueType = isList ? ParameterizedTypeName.get(LIST, RECORD) : RECORD;
        var returnType = ParameterizedTypeName.get(COMPLETABLE_FUTURE, valueType);

        var sourcesParam = smr.params().stream()
            .filter(p -> p instanceof MethodRef.Param.Sourced)
            .map(p -> (MethodRef.Param.Sourced) p)
            .findFirst()
            .orElseThrow();
        var batchKey = sourcesParam.batchKey();
        TypeName keyType = keyElementType(batchKey);
        var loaderType = ParameterizedTypeName.get(DATA_LOADER, keyType, valueType);
        String rowsMethodName = sf.rowsMethodName();

        var lambdaBlock = CodeBlock.builder()
            .add("(keys, batchEnv) -> {\n")
            .indent()
            .addStatement("$T dfe = ($T) batchEnv.getKeyContextsList().get(0)", ENV, ENV)
            .addStatement("$T sel = dfe.getSelectionSet().getField($S)", SELECTED_FIELD, sf.name())
            .addStatement("return $T.completedFuture($L(keys, dfe, sel))", COMPLETABLE_FUTURE, rowsMethodName)
            .unindent()
            .add("}")
            .build();

        var methodBuilder = MethodSpec.methodBuilder(sf.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(returnType)
            .addParameter(ENV, "env")
            .addStatement("$T name = graphitronContext(env).getDataLoaderName(env)", String.class)
            .addCode(
                "$T loader = env.getDataLoaderRegistry()\n" +
                "    .computeIfAbsent(name, k -> $T.newDataLoaderWithContext($L));\n",
                loaderType, DATA_LOADER_FACTORY, lambdaBlock);

        var tablesClass = ClassName.get(RewriteConfig.getGeneratedJooqPackage(), "Tables");
        switch (batchKey) {
            case BatchKey.RowKeyed rk -> {
                String tableField = prt.javaFieldName();
                List<ColumnRef> pkCols = rk.keyColumns();
                var rowArgs = CodeBlock.builder();
                for (int i = 0; i < pkCols.size(); i++) {
                    if (i > 0) rowArgs.add(", ");
                    rowArgs.add("(($T) env.getSource()).get($T.$L.$L)",
                        RECORD, tablesClass, tableField, pkCols.get(i).javaName());
                }
                methodBuilder.addStatement("$T key = $T.row($L)", keyType, DSL, rowArgs.build());
            }
            case BatchKey.RecordKeyed rk -> {
                String tableField = prt.javaFieldName();
                List<ColumnRef> pkCols = rk.keyColumns();
                var intoArgs = CodeBlock.builder();
                for (int i = 0; i < pkCols.size(); i++) {
                    if (i > 0) intoArgs.add(", ");
                    intoArgs.add("$T.$L.$L", tablesClass, tableField, pkCols.get(i).javaName());
                }
                methodBuilder.addStatement("$T key = (($T) env.getSource()).into($L)",
                    keyType, RECORD, intoArgs.build());
            }
            case BatchKey.ObjectBased ob ->
                methodBuilder.addStatement("$T key = ($T) env.getSource()", keyType, keyType);
        }

        return methodBuilder.addStatement("return loader.load(key, env)").build();
    }

    /**
     * Generates the batch rows method for a {@link ChildField.ServiceTableField}.
     *
     * <p>The method extracts arguments from the DFE, calls the service method with the
     * batch {@code keys} as the sources parameter, and delegates to the table's
     * {@code selectMany} or {@code selectOne}.
     */
    private static MethodSpec buildServiceRowsMethod(
            ChildField.ServiceTableField sf,
            MethodRef smr,
            ReturnTypeRef.TableBoundReturnType tb,
            TableRef rt,
            TableRef prt) {

        boolean isList = tb.wrapper().isList();
        var listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        var returnType = isList ? ParameterizedTypeName.get(LIST, listOfRecord) : listOfRecord;

        var sourcesParam = smr.params().stream()
            .filter(p -> p instanceof MethodRef.Param.Sourced)
            .map(p -> (MethodRef.Param.Sourced) p)
            .findFirst()
            .orElseThrow();
        TypeName keysElementType = keyElementType(sourcesParam.batchKey());

        var builder = MethodSpec.methodBuilder(sf.rowsMethodName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(returnType)
            .addParameter(ParameterizedTypeName.get(LIST, keysElementType), "keys")
            .addParameter(ENV, "dfe")
            .addParameter(SELECTED_FIELD, "sel");

        for (var param : smr.params()) {
            if (param instanceof MethodRef.Param.Typed t) switch (t.source()) {
                case ParamSource.Arg a ->
                    builder.addStatement("$T $L = dfe.getArgument($S)", Object.class, t.name(), t.name());
                case ParamSource.Context c ->
                    builder.addStatement("$T $L = graphitronContext(dfe).getContextArgument(dfe, $S)", Object.class, t.name(), t.name());
                default -> {} // DslContext, Table, SourceTable: not applicable here
            }
        }

        var serviceCallArgs = smr.params().stream()
            .map(p -> p instanceof MethodRef.Param.Sourced ? "keys" : p.name())
            .toList();

        builder.addStatement("$T serviceResult = $T.$L($L)",
            Object.class,
            ClassName.bestGuess(smr.className()),
            smr.methodName(),
            String.join(", ", serviceCallArgs));

        var tableClass = ClassName.get(RewriteConfig.outputPackage() + ".rewrite.types", tb.returnTypeName());
        String selectManyName = sourcesParam.batchKey().selectManyMethodName();
        String selectOneName  = sourcesParam.batchKey().selectOneMethodName();
        if (isList) {
            builder.addStatement("return $T.$L(keys, dfe, sel, ($T<?>) serviceResult)",
                tableClass, selectManyName, List.class);
        } else {
            builder.addStatement("return $T.$L(keys, dfe, sel, serviceResult)", tableClass, selectOneName);
        }

        return builder.build();
    }

    // -----------------------------------------------------------------------
    // SplitTableField / SplitLookupTableField — CompletableFuture stubs
    // -----------------------------------------------------------------------

    private static MethodSpec buildSplitQueryDataFetcher(ChildField.SplitTableField field) {
        return buildSplitQueryDataFetcher(field.name(), field.batchKey());
    }

    private static MethodSpec buildSplitQueryDataFetcher(String fieldName, BatchKey batchKey) {
        var returnType = ParameterizedTypeName.get(COMPLETABLE_FUTURE, ParameterizedTypeName.get(LIST, RECORD));
        return MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(returnType)
            .addParameter(ENV, "env")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    private static MethodSpec buildSplitRowsMethod(ChildField.SplitTableField field) {
        return buildSplitRowsMethod(field.name(), field.batchKey());
    }

    private static MethodSpec buildSplitRowsMethod(String fieldName, BatchKey batchKey) {
        TypeName keysElementType = keyElementType(batchKey);
        var sourcesType = ParameterizedTypeName.get(LIST, keysElementType);
        return MethodSpec.methodBuilder("rows" + capitalize(fieldName))
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(sourcesType, "sources")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    // -----------------------------------------------------------------------
    // GraphitronContext helper
    // -----------------------------------------------------------------------

    private static MethodSpec buildGraphitronContextHelper() {
        return MethodSpec.methodBuilder("graphitronContext")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(GRAPHITRON_CONTEXT)
            .addParameter(ENV, "env")
            .addStatement("return env.getGraphQlContext().get($S)", "graphitronContext")
            .build();
    }

    // -----------------------------------------------------------------------
    // BatchKey helpers — key type construction for DataLoader generics
    // -----------------------------------------------------------------------

    private static TypeName keyElementType(BatchKey batchKey) {
        return switch (batchKey) {
            case BatchKey.RowKeyed rk    -> buildRowKeyType(rk.keyColumns());
            case BatchKey.RecordKeyed rk -> buildRecordNKeyType(rk.keyColumns());
            case BatchKey.ObjectBased ob -> ClassName.bestGuess(ob.fqClassName());
        };
    }

    private static TypeName buildRowKeyType(List<ColumnRef> keyColumns) {
        if (keyColumns.isEmpty()) return ROW;
        ClassName rowNClass = ClassName.get("org.jooq", "Row" + keyColumns.size());
        TypeName[] typeArgs = keyColumns.stream()
            .map(c -> (TypeName) ClassName.bestGuess(c.columnClass()))
            .toArray(TypeName[]::new);
        return ParameterizedTypeName.get(rowNClass, typeArgs);
    }

    private static TypeName buildRecordNKeyType(List<ColumnRef> keyColumns) {
        if (keyColumns.isEmpty()) return RECORD;
        ClassName recordNClass = ClassName.get("org.jooq", "Record" + keyColumns.size());
        TypeName[] typeArgs = keyColumns.stream()
            .map(c -> (TypeName) ClassName.bestGuess(c.columnClass()))
            .toArray(TypeName[]::new);
        return ParameterizedTypeName.get(recordNClass, typeArgs);
    }

    private static String capitalize(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * Builds the wiring entry for one field. Column fields with a parent table use
     * {@link no.sikt.graphitron.rewrite.generators.util.ColumnFetcherClassGenerator ColumnFetcher}
     * directly. All other fields use a plain method reference.
     */
    private static CodeBlock buildWiringEntry(GraphitronField field, String className, TableRef parentTable) {
        if (field instanceof ChildField.ColumnField cf && parentTable != null) {
            var columnFetcherClass = ClassName.get(RewriteConfig.outputPackage() + ".rewrite",
                ColumnFetcherClassGenerator.CLASS_NAME);
            var tablesClass = ClassName.get(RewriteConfig.getGeneratedJooqPackage(), "Tables");
            return CodeBlock.of("\n.dataFetcher($S, new $T<>($T.$L.$L))",
                field.name(), columnFetcherClass, tablesClass,
                parentTable.javaFieldName(), cf.column().javaName());
        }
        return CodeBlock.of("\n.dataFetcher($S, $L::$L)", field.name(), className, field.name());
    }

    private static MethodSpec buildWiringMethod(String typeName, String className,
            TableRef parentTable, List<GraphitronField> fields) {
        var body = CodeBlock.builder()
            .add("return $T.newTypeWiring($S)", TYPE_WIRING, typeName);

        if (fields.isEmpty()) {
            body.add(";\n");
        } else {
            body.indent();
            for (var field : fields) {
                body.add(buildWiringEntry(field, className, parentTable));
            }
            body.add(";\n");
            body.unindent();
        }

        return MethodSpec.methodBuilder("wiring")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(WIRING_BUILDER)
            .addCode(body.build())
            .build();
    }

    /**
     * Builds the argument list for a lookup conditions method call.
     *
     * <p>The first argument is always {@code table} (the local variable declared in the fetcher).
     * Subsequent arguments are extracted from {@code env} per param:
     * <ul>
     *   <li>List {@code ID} key — {@code <localVar>.stream().map(table.COL.getDataType()::convert).toList()},
     *       where {@code <localVar>} is the {@code List<String>} local variable declared before this call.</li>
     *   <li>List non-{@code ID} key — {@code env.getArgument("name")} (GraphQL-Java delivers the correct type).</li>
     *   <li>Scalar {@code ID} key — {@code table.COL.getDataType().convert((String) env.getArgument("name"))}.</li>
     *   <li>Scalar non-{@code ID} key — {@code env.getArgument("name")} directly.</li>
     * </ul>
     */
    private static CodeBlock buildLookupCallArgs(GeneratedConditionFilter gcf) {
        var args = CodeBlock.builder();
        args.add("table");
        for (var bp : gcf.bodyParams()) {
            var colName = bp.column().javaName();
            boolean idArg = "ID".equals(bp.graphqlTypeName());
            if (bp.list()) {
                if (idArg) {
                    args.add(", $L.stream().map(table.$L.getDataType()::convert).toList()",
                        toCamelCase(bp.name()) + "Keys", colName);
                } else {
                    args.add(", env.getArgument($S)", bp.name());
                }
            } else {
                if (idArg) {
                    args.add(", table.$L.getDataType().convert((String) env.getArgument($S))", colName, bp.name());
                } else {
                    args.add(", env.getArgument($S)", bp.name());
                }
            }
        }
        return args.build();
    }

    /** Converts a snake_case GraphQL argument name to lowerCamelCase for use as a Java local variable. */
    private static String toCamelCase(String snakeName) {
        var parts = snakeName.split("_");
        var sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            sb.append(Character.toUpperCase(parts[i].charAt(0)));
            sb.append(parts[i], 1, parts[i].length());
        }
        return sb.toString();
    }
}
