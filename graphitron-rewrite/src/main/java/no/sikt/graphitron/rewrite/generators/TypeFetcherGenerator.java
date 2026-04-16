package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.generators.util.ColumnFetcherClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ConnectionHelperClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ConnectionResultClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.OrderByResultClassGenerator;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.RewriteConfig;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.BatchKeyField;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.MethodBackedField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.*;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
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

    // Fetcher-specific constants (cross-generator constants come from GeneratorUtils via static import)
    private static final ClassName TYPE_WIRING          = ClassName.get("graphql.schema.idl", "TypeRuntimeWiring");
    private static final ClassName WIRING_BUILDER       = ClassName.get("graphql.schema.idl", "TypeRuntimeWiring", "Builder");
    private static final ClassName COMPLETABLE_FUTURE   = ClassName.get("java.util.concurrent", "CompletableFuture");
    private static final ClassName DATA_LOADER          = ClassName.get("org.dataloader", "DataLoader");
    private static final ClassName DATA_LOADER_FACTORY  = ClassName.get("org.dataloader", "DataLoaderFactory");
    private static final ClassName ARRAY_LIST           = ClassName.get("java.util", "ArrayList");
    private static final ClassName MAP                  = ClassName.get("java.util", "Map");
    /** {@code List<SortField<?>>} — the return type of every {@code *OrderBy} helper method. */
    private static final TypeName SORT_FIELD_LIST       = ParameterizedTypeName.get(LIST,
        ParameterizedTypeName.get(SORT_FIELD, WildcardTypeName.subtypeOf(Object.class)));

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

        // Emit the graphitronContext() helper whenever any field executes inline SQL —
        // i.e., whenever any SqlGeneratingField is present in this fetchers class.
        boolean needsGraphitronContextHelper = fields.stream().anyMatch(f -> f instanceof SqlGeneratingField);

        for (var field : fields) {
            if (field instanceof ChildField.ColumnField && parentTable != null) {
                // Column fields with a parent table are handled directly in wiring via ColumnFetcher
            } else if (field instanceof QueryField.QueryLookupTableField qlf) {
                builder.addMethod(buildQueryLookupFetcher(qlf));
                builder.addMethod(buildQueryLookupRowsMethod(qlf));
            } else if (field instanceof QueryField.QueryTableField qtf
                    && qtf.returnType().wrapper() instanceof FieldWrapper.Connection) {
                builder.addMethod(buildQueryConnectionFetcher(qtf));
            } else if (field instanceof QueryField.QueryTableField qtf) {
                builder.addMethod(buildQueryTableFetcher(qtf));
            } else if (field instanceof BatchKeyField bkf) {
                if (field instanceof MethodBackedField mbf && field instanceof SqlGeneratingField sgf && parentTable != null) {
                    builder.addMethod(buildServiceDataFetcher(field.name(), bkf, mbf.method(), sgf.returnType(), parentTable, className));
                    builder.addMethod(buildServiceRowsMethod(bkf, mbf.method(), sgf.returnType(), sgf.returnType().table(), parentTable, className));
                } else {
                    builder.addMethod(buildSplitQueryDataFetcher(field.name(), bkf.batchKey()));
                    builder.addMethod(buildSplitRowsMethod(bkf));
                }
            } else {
                builder.addMethod(buildStub(field.name()));
            }
        }

        if (needsGraphitronContextHelper) {
            builder.addMethod(buildGraphitronContextHelper());
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

        // Emit orderBy helper methods for QueryTableFields with a dynamic @orderBy argument
        for (var field : fields) {
            if (field instanceof QueryField.QueryTableField qtf
                    && qtf.orderBy() instanceof OrderBySpec.Argument arg) {
                var tableRef = qtf.returnType().table();
                var names = GeneratorUtils.ResolvedTableNames.of(tableRef, qtf.returnType().returnTypeName());
                builder.addMethod(buildOrderByHelperMethod(qtf.name(), arg, names, tableRef));
            }
        }

        builder.addMethod(buildWiringMethod(typeName, className, parentTable, fields));
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
    private static MethodSpec buildQueryTableFetcher(QueryField.QueryTableField qtf) {
        var tableRef = qtf.returnType().table();
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, qtf.returnType().returnTypeName());
        boolean isList = qtf.returnType().wrapper().isList();

        var returnType = isList
            ? ParameterizedTypeName.get(RESULT, RECORD)
            : RECORD;

        var builder = MethodSpec.methodBuilder(qtf.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(returnType)
            .addParameter(ENV, "env");

        builder.addStatement("var dsl = graphitronContext(env).getDslContext(env)");
        builder.addCode(GeneratorUtils.declareTableLocal(names, tableRef));
        builder.addCode(buildConditionCall(qtf));

        if (isList) {
            builder.addCode(buildOrderByCode(qtf.orderBy(), qtf.name()));
            builder.addCode(CodeBlock.builder()
                .add("return dsl\n")
                .indent()
                .add(".select($T.$$fields(env.getSelectionSet(), table, env))\n", names.typeClass())
                .add(".from(table)\n")
                .add(".where(condition)\n")
                .add(".orderBy(orderBy)\n")
                .add(".fetch();\n")
                .unindent()
                .build());
        } else {
            builder.addCode(CodeBlock.builder()
                .add("return dsl\n")
                .indent()
                .add(".select($T.$$fields(env.getSelectionSet(), table, env))\n", names.typeClass())
                .add(".from(table)\n")
                .add(".where(condition)\n")
                .add(".fetchOne();\n")
                .unindent()
                .build());
        }

        return builder.build();
    }

    private static CodeBlock buildConditionCall(QueryField.QueryTableField qtf) {
        var code = CodeBlock.builder();
        code.addStatement("$T condition = $T.noCondition()", CONDITION, DSL);
        for (var filter : qtf.filters()) {
            for (var param : filter.callParams()) {
                if (param.extraction() instanceof CallSiteExtraction.JooqConvert && param.list()) {
                    code.addStatement("$T<$T> $L = env.getArgument($S)",
                        LIST, String.class, toCamelCase(param.name()) + "Keys", param.name());
                }
            }
            var callArgs = buildCallArgs(filter.callParams(), filter.className());
            code.addStatement("condition = condition.and($T.$L($L))",
                ClassName.bestGuess(filter.className()), filter.methodName(), callArgs);
        }
        return code.build();
    }

    /**
     * Generates a connection field fetcher that returns a {@code ConnectionResult}.
     *
     * <p>Extracts pagination args, decodes cursor, builds condition and orderBy, executes inline
     * paginated SQL with name-based extra-field deduplication, and wraps the result in
     * a {@code ConnectionResult}.
     */
    private static MethodSpec buildQueryConnectionFetcher(QueryField.QueryTableField qtf) {
        var tableRef = qtf.returnType().table();
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, qtf.returnType().returnTypeName());
        var connectionResultClass = ClassName.get(
            RewriteConfig.outputPackage() + ".rewrite", ConnectionResultClassGenerator.CLASS_NAME);
        var conn = (FieldWrapper.Connection) qtf.returnType().wrapper();
        var JOOQ_FIELD = ClassName.get("org.jooq", "Field");
        var WILDCARD_FIELD = ParameterizedTypeName.get(JOOQ_FIELD,
            no.sikt.graphitron.javapoet.WildcardTypeName.subtypeOf(Object.class));
        var HASH_SET = ClassName.get("java.util", "HashSet");

        var builder = MethodSpec.methodBuilder(qtf.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(connectionResultClass)
            .addParameter(ENV, "env");

        builder.addStatement("var dsl = graphitronContext(env).getDslContext(env)");
        builder.addStatement("$T table = $T.$L", names.jooqTableClass(), names.tablesClass(), tableRef.javaFieldName());
        builder.addCode(buildConditionCall(qtf));
        // Single dispatch produces both orderBy (for SQL) and extraFields (for cursor columns),
        // keeping them in sync when the client picks a dynamic named order.
        builder.addCode(buildConnectionOrderingBlock(qtf.orderBy(), qtf.name(), names));

        // Extract pagination arguments using arg names from the model
        var pagination = qtf.pagination();
        var firstArgName = pagination != null && pagination.first() != null ? pagination.first().name() : "first";
        var afterArgName = pagination != null && pagination.after() != null ? pagination.after().name() : "after";
        builder.addStatement("Integer first = env.getArgument($S)", firstArgName);
        builder.addStatement("String after = env.getArgument($S)", afterArgName);
        builder.addStatement("int pageSize = first != null ? first : $L", conn.defaultPageSize());

        // Decode cursor to seek values (null if no cursor)
        var connectionHelperClass = ClassName.get(
            RewriteConfig.outputPackage() + ".rewrite", ConnectionHelperClassGenerator.CLASS_NAME);
        builder.addStatement("Object[] seekValues = after != null ? $T.decodeCursor(after) : null",
            connectionHelperClass);

        // Build select list: $fields + extra fields not already present (by name)
        builder.addStatement("var fields = new $T<>($T.$$fields(env.getSelectionSet(), table, env))",
            ARRAY_LIST, names.typeClass());
        builder.addStatement("var selectedNames = new $T<$T>()", HASH_SET, String.class);
        builder.addCode("for (var f : fields) selectedNames.add(f.getName());\n");
        builder.addCode("for (var extra : extraFields) {\n");
        builder.addCode("    if (!selectedNames.contains(extra.getName())) fields.add(extra);\n");
        builder.addCode("}\n");

        // Build and execute paginated query inline
        builder.addCode(CodeBlock.builder()
            .add("var query = dsl\n")
            .indent()
            .add(".select(fields)\n")
            .add(".from(table)\n")
            .add(".where(condition)\n")
            .add(".orderBy(orderBy);\n")
            .unindent()
            .add("if (seekValues != null && seekValues.length > 0) query = query.seek(seekValues);\n")
            .build());

        builder.addStatement("return new $T(query.limit(pageSize + 1).fetch(), pageSize, after, extraFields)",
            connectionResultClass);

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
            OrderBySpec orderBy, String fieldName, GeneratorUtils.ResolvedTableNames names) {
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
                        sortParts.add("table.$L.$L()", col.column().javaName(), fixed.jooqMethodName());
                        colParts.add("table.$L", col.column().javaName());
                    }
                    code.addStatement("$T orderBy = $T.of($L)", SORT_FIELD_LIST, LIST, sortParts.build());
                    code.addStatement("$T extraFields = $T.of($L)", listOfField, LIST, colParts.build());
                }
            }
            case OrderBySpec.Argument arg -> {
                // Single dispatch: OrderByResult carries both sort fields and cursor columns.
                code.addStatement("var ordering = $LOrderBy(env)", fieldName);
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
     * Builds the argument list for one condition method call: {@code table} first, then one arg
     * per {@link CallParam}. The {@code conditionsClassName} is used by
     * {@link CallSiteExtraction.TextMapLookup} to reference a static map field on the class.
     */
    private static CodeBlock buildCallArgs(List<CallParam> params, String conditionsClassName) {
        var args = CodeBlock.builder();
        args.add("table");
        for (var param : params) {
            args.add(", $L", buildArgExtraction(param, conditionsClassName));
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
            case CallSiteExtraction.JooqConvert jc -> param.list()
                ? CodeBlock.of("$L.stream().map(table.$L.getDataType()::convert).toList()",
                    toCamelCase(param.name()) + "Keys", jc.columnJavaName())
                : CodeBlock.of("table.$L.getDataType().convert((String) env.getArgument($S))",
                    jc.columnJavaName(), param.name());
        };
    }

    /**
     * Builds the {@code orderBy} variable declaration for a fetcher body.
     *
     * <p>When {@code fieldName} is non-null and {@code orderBy} is an {@link OrderBySpec.Argument},
     * emits a call to the {@code <fieldName>OrderBy} helper method. Otherwise, inlines the
     * fixed or empty list.
     */
    private static CodeBlock buildOrderByCode(OrderBySpec orderBy, String fieldName) {
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
                if (fieldName != null) {
                    // Helper now returns OrderByResult; extract just the sort fields for non-connection fetchers
                    code.addStatement("$T orderBy = $LOrderBy(env).sortFields()", SORT_FIELD_LIST, fieldName);
                } else {
                    code.add(buildOrderByCode(arg.base(), null));
                }
            }
            case OrderBySpec.None none ->
                code.addStatement("$T<$T<?>> orderBy = $T.of()", LIST, SORT_FIELD, LIST);
        }
        return code.build();
    }

    /** Convenience overload — no helper method available; falls back to base for {@link OrderBySpec.Argument}. */
    private static CodeBlock buildOrderByCode(OrderBySpec orderBy) {
        return buildOrderByCode(orderBy, null);
    }

    // -----------------------------------------------------------------------
    // OrderBy helper method generation (Steps 3+4 of orderby-implementation.md)
    // -----------------------------------------------------------------------

    /**
     * Generates the private static {@code <fieldName>OrderBy(DataFetchingEnvironment env)} helper.
     *
     * <p>The helper reads the {@code @orderBy} argument from {@code env}, dispatches over the
     * sort-field name via a switch expression (single arg) or accumulates into a list (list arg),
     * and returns a {@code List<SortField<?>>}. Fetcher bodies call this helper instead of
     * inlining the dispatch logic.
     */
    private static MethodSpec buildOrderByHelperMethod(
            String fieldName,
            OrderBySpec.Argument arg,
            GeneratorUtils.ResolvedTableNames names,
            TableRef tableRef) {

        var orderByResultClass = ClassName.get(
            RewriteConfig.outputPackage() + ".rewrite", OrderByResultClassGenerator.CLASS_NAME);

        var builder = MethodSpec.methodBuilder(fieldName + "OrderBy")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(orderByResultClass)
            .addParameter(ENV, "env");

        builder.addCode(GeneratorUtils.declareTableLocal(names, tableRef));

        var baseExpr = buildBaseReturnExpr(arg.base());
        if (arg.list()) {
            builder.addCode(buildListArgOrderByBody(arg, baseExpr));
        } else {
            builder.addCode(buildSingleArgOrderByBody(arg, baseExpr));
        }

        return builder.build();
    }

    /**
     * Returns the fallback expression ({@code new OrderByResult(List.of(table.COL.asc()), List.of(table.COL))}
     * or {@code new OrderByResult(List.of(), List.of())}) used when no {@code @orderBy} argument is
     * supplied at runtime.
     */
    private static CodeBlock buildBaseReturnExpr(OrderBySpec base) {
        var orderByResultClass = ClassName.get(
            RewriteConfig.outputPackage() + ".rewrite", OrderByResultClassGenerator.CLASS_NAME);
        return switch (base) {
            case OrderBySpec.Fixed fixed when !fixed.columns().isEmpty() -> {
                var sortParts = CodeBlock.builder();
                var colParts = CodeBlock.builder();
                for (int i = 0; i < fixed.columns().size(); i++) {
                    if (i > 0) { sortParts.add(", "); colParts.add(", "); }
                    var col = fixed.columns().get(i);
                    sortParts.add("table.$L.$L()", col.column().javaName(), fixed.jooqMethodName());
                    colParts.add("table.$L", col.column().javaName());
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
    private static CodeBlock buildSingleArgOrderByBody(OrderBySpec.Argument arg, CodeBlock baseExpr) {
        var code = CodeBlock.builder();
        var orderByResultClass = ClassName.get(
            RewriteConfig.outputPackage() + ".rewrite", OrderByResultClassGenerator.CLASS_NAME);
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
                sortParts.add("$S.equals(dir) ? table.$L.desc() : table.$L.$L()",
                    "DESC", col.column().javaName(), col.column().javaName(),
                    namedOrder.order().jooqMethodName());
                colParts.add("table.$L", col.column().javaName());
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
    private static CodeBlock buildListArgOrderByBody(OrderBySpec.Argument arg, CodeBlock baseExpr) {
        var code = CodeBlock.builder();
        var JOOQ_FIELD = ClassName.get("org.jooq", "Field");
        var WILDCARD_FIELD = ParameterizedTypeName.get(JOOQ_FIELD,
            no.sikt.graphitron.javapoet.WildcardTypeName.subtypeOf(Object.class));
        var orderByResultClass = ClassName.get(
            RewriteConfig.outputPackage() + ".rewrite", OrderByResultClassGenerator.CLASS_NAME);
        code.addStatement("$T<$T<$T, $T>> orderArgs = env.getArgument($S)",
            LIST, MAP, String.class, Object.class, arg.name());
        code.add("if (orderArgs == null || orderArgs.isEmpty()) return $L;\n", baseExpr);
        code.addStatement("var sortParts = new $T<$T<?>>()", ARRAY_LIST, SORT_FIELD);
        code.addStatement("var colParts = new $T<$T>()", ARRAY_LIST, WILDCARD_FIELD);
        code.add("for (var entry : orderArgs) {\n");
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
                code.addStatement("sortParts.add($S.equals(d) ? table.$L.desc() : table.$L.$L())",
                    "DESC", col.column().javaName(), col.column().javaName(),
                    namedOrder.order().jooqMethodName());
                code.addStatement("colParts.add(table.$L)", col.column().javaName());
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
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, field.returnType().returnTypeName());

        var builder = MethodSpec.methodBuilder(field.lookupMethodName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(RESULT, RECORD))
            .addParameter(ENV, "env");

        builder.addStatement("var dsl = graphitronContext(env).getDslContext(env)");
        builder.addCode(GeneratorUtils.declareTableLocal(names, tableRef));
        builder.addStatement("$T condition = $T.noCondition()", CONDITION, DSL);

        for (var filter : field.filters()) {
            if (!(filter instanceof GeneratedConditionFilter gcf)) continue;
            // Declare local variables for list JooqConvert keys (List<String> — avoids convert() overload ambiguity)
            for (var bp : gcf.bodyParams()) {
                if (bp.extraction() instanceof CallSiteExtraction.JooqConvert && bp.list()) {
                    builder.addStatement("$T<$T> $L = env.getArgument($S)",
                        LIST, String.class, toCamelCase(bp.name()) + "Keys", bp.name());
                }
            }
            var callParams = gcf.bodyParams().stream()
                .map(bp -> new CallParam(bp.name(), bp.extraction(), bp.list(), bp.javaType()))
                .toList();
            builder.addStatement("condition = condition.and($T.$L($L))",
                ClassName.bestGuess(gcf.className()), gcf.methodName(),
                buildCallArgs(callParams, gcf.className()));
        }

        builder.addCode(buildOrderByCode(field.orderBy()));
        builder.addCode(CodeBlock.builder()
            .add("return dsl\n")
            .indent()
            .add(".select($T.$$fields(env.getSelectionSet(), table, env))\n", names.typeClass())
            .add(".from(table)\n")
            .add(".where(condition)\n")
            .add(".orderBy(orderBy)\n")
            .add(".fetch();\n")
            .unindent()
            .build());
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
            String fieldName,
            BatchKeyField bkf,
            MethodRef smr,
            ReturnTypeRef.TableBoundReturnType tb,
            TableRef prt,
            String className) {

        boolean isList = tb.wrapper().isList();
        var valueType = isList ? ParameterizedTypeName.get(LIST, RECORD) : RECORD;
        var returnType = ParameterizedTypeName.get(COMPLETABLE_FUTURE, valueType);

        var batchKey = bkf.batchKey();
        TypeName keyType = GeneratorUtils.keyElementType(batchKey);
        var loaderType = ParameterizedTypeName.get(DATA_LOADER, keyType, valueType);
        String rowsMethodName = bkf.rowsMethodName();

        var lambdaBlock = CodeBlock.builder()
            .add("(keys, batchEnv) -> {\n")
            .indent()
            .addStatement("$T dfe = ($T) batchEnv.getKeyContextsList().get(0)", ENV, ENV)
            .addStatement("$T sel = dfe.getSelectionSet().getField($S)", SELECTED_FIELD, fieldName)
            .addStatement("return $T.completedFuture($L(keys, dfe, sel))", COMPLETABLE_FUTURE, rowsMethodName)
            .unindent()
            .add("}")
            .build();

        var methodBuilder = MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(returnType)
            .addParameter(ENV, "env")
            .addStatement("$T name = graphitronContext(env).getDataLoaderName(env)", String.class)
            .addCode(
                "$T loader = env.getDataLoaderRegistry()\n" +
                "    .computeIfAbsent(name, k -> $T.newDataLoaderWithContext($L));\n",
                loaderType, DATA_LOADER_FACTORY, lambdaBlock);

        methodBuilder.addCode(GeneratorUtils.buildKeyExtraction(batchKey, prt));
        return methodBuilder.addStatement("return loader.load(key, env)").build();
    }

    /**
     * Generates a stub rows method for a {@link ChildField.ServiceTableField}.
     *
     * <p>Throws {@link UnsupportedOperationException} directly — no longer delegates to
     * batch-key method names on the type class (which no longer exist). The eventual
     * implemented body will call {@code Type.$fields(sel.getSelectionSet(), table, env)}
     * for projection, but that work is out of scope here.
     */
    private static MethodSpec buildServiceRowsMethod(
            BatchKeyField bkf,
            MethodRef smr,
            ReturnTypeRef.TableBoundReturnType tb,
            TableRef rt,
            TableRef prt,
            String fetchersClassName) {

        boolean isList = tb.wrapper().isList();
        var listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        var returnType = isList ? ParameterizedTypeName.get(LIST, listOfRecord) : listOfRecord;

        TypeName keysElementType = GeneratorUtils.keyElementType(bkf.batchKey());

        return MethodSpec.methodBuilder(bkf.rowsMethodName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(returnType)
            .addParameter(ParameterizedTypeName.get(LIST, keysElementType), "keys")
            .addParameter(ENV, "env")
            .addParameter(SELECTED_FIELD, "sel")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    // -----------------------------------------------------------------------
    // SplitTableField / SplitLookupTableField — CompletableFuture stubs
    // -----------------------------------------------------------------------

    private static MethodSpec buildSplitQueryDataFetcher(String fieldName, BatchKey batchKey) {
        var returnType = ParameterizedTypeName.get(COMPLETABLE_FUTURE, ParameterizedTypeName.get(LIST, RECORD));
        return MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(returnType)
            .addParameter(ENV, "env")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    private static MethodSpec buildSplitRowsMethod(BatchKeyField bkf) {
        TypeName keysElementType = GeneratorUtils.keyElementType(bkf.batchKey());
        var sourcesType = ParameterizedTypeName.get(LIST, keysElementType);
        return MethodSpec.methodBuilder(bkf.rowsMethodName())
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
