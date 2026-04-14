package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.util.ColumnFetcherClassGenerator;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.RewriteConfig;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.QueryField;
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
 *   <li>All other field types — stub throwing {@link UnsupportedOperationException}.</li>
 * </ul>
 */
public class TypeFetcherClassGenerator {

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

    private static final ClassName ENV                = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName TYPE_WIRING        = ClassName.get("graphql.schema.idl", "TypeRuntimeWiring");
    private static final ClassName WIRING_BUILDER     = ClassName.get("graphql.schema.idl", "TypeRuntimeWiring", "Builder");
    private static final ClassName RECORD             = ClassName.get("org.jooq", "Record");
    private static final ClassName RESULT             = ClassName.get("org.jooq", "Result");
    private static final ClassName SORT_FIELD         = ClassName.get("org.jooq", "SortField");
    private static final ClassName DSL                = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName LIST               = ClassName.get("java.util", "List");

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

        for (var field : fields) {
            if (field instanceof ChildField.ColumnField && parentTable != null) {
                // Column fields with a parent table are handled directly in wiring via ColumnFetcher
            } else if (field instanceof QueryField.QueryLookupTableField qlf) {
                builder.addMethod(buildQueryLookupFetcher(qlf));
            } else if (field instanceof QueryField.QueryTableField qtf) {
                builder.addMethod(buildQueryTableFetcher(qtf));
            } else {
                builder.addMethod(buildStub(field.name()));
            }
        }

        builder.addMethod(buildWiringMethod(typeName, className, parentTable, fields));
        return builder.build();
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
        boolean isList = qtf.returnType().wrapper().isList();

        var returnType = isList
            ? ParameterizedTypeName.get(RESULT, RECORD)
            : RECORD;

        var builder = MethodSpec.methodBuilder(qtf.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(returnType)
            .addParameter(ENV, "env");

        builder.addCode(buildConditionCall(qtf, tablesClass, tableRef));

        if (isList) {
            builder.addCode(buildOrderByCode(qtf.orderBy(), tablesClass, tableRef));
            builder.addStatement("return $T.selectMany(env, condition, orderBy)", tableClass);
        } else {
            builder.addStatement("return $T.selectOne(env, condition)", tableClass);
        }

        return builder.build();
    }

    private static CodeBlock buildConditionCall(QueryField.QueryTableField qtf,
            ClassName tablesClass, TableRef tableRef) {
        var code = CodeBlock.builder();
        code.addStatement("var condition = $T.noCondition()", DSL);
        for (var filter : qtf.filters()) {
            var callArgs = buildCallArgs(filter, tablesClass, tableRef);
            code.addStatement("condition = condition.and($T.$L($L))",
                ClassName.bestGuess(filter.className()), filter.methodName(), callArgs);
        }
        return code.build();
    }

    private static CodeBlock buildCallArgs(WhereFilter filter, ClassName tablesClass, TableRef tableRef) {
        var args = CodeBlock.builder();
        args.add("$T.$L", tablesClass, tableRef.javaFieldName());
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

    private static CodeBlock buildOrderByCode(OrderBySpec orderBy, ClassName tablesClass, TableRef tableRef) {
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
                        parts.add("$T.$L.$L.$L()", tablesClass, tableRef.javaFieldName(),
                            col.column().javaName(), fixed.jooqMethodName());
                    }
                    code.addStatement("$T<$T<?>> orderBy = $T.of($L)", LIST, SORT_FIELD, LIST, parts.build());
                }
            }
            case OrderBySpec.Argument arg -> {
                if (arg.base() != null) {
                    code.add(buildOrderByCode(arg.base(), tablesClass, tableRef));
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
     * Generates a fetcher for a lookup query field. Lookup fields receive a list of primary-key
     * values from the GraphQL client (DataLoader pattern) and return all matching rows in a single
     * SQL query using a WHERE IN (or WHERE IN + WHERE EQ) condition.
     *
     * <p>List-keyed arguments (annotated {@code @lookupKey} on a {@code [T]} argument) become an
     * IN condition; scalar-keyed arguments become an EQ condition. Type conversion via
     * {@code Type.valueOf(String.valueOf(...))} handles both {@code ID} (delivered as String) and
     * numeric GraphQL scalars transparently.
     *
     * <p>Generated code (single list key):
     * <pre>{@code
     * public static Result<Record> filmById(DataFetchingEnvironment env) {
     *     var table = Tables.FILM;
     *     var condition = DSL.noCondition();
     *     List<?> filmIdKeys = env.getArgument("film_id");
     *     condition = condition.and(table.FILM_ID.in(filmIdKeys.stream().map(k -> Integer.valueOf(String.valueOf(k))).toList()));
     *     List<SortField<?>> orderBy = List.of();
     *     return Film.selectMany(env, condition, orderBy);
     * }
     * }</pre>
     */
    private static MethodSpec buildQueryLookupFetcher(QueryField.QueryLookupTableField field) {
        var tableRef = field.returnType().table();
        var tableClass = ClassName.get(RewriteConfig.outputPackage() + ".rewrite.types", field.returnType().returnTypeName());
        var tablesClass = ClassName.get(RewriteConfig.getGeneratedJooqPackage(), "Tables");

        var builder = MethodSpec.methodBuilder(field.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(RESULT, RECORD))
            .addParameter(ENV, "env");

        builder.addStatement("var table = $T.$L", tablesClass, tableRef.javaFieldName());
        builder.addStatement("var condition = $T.noCondition()", DSL);

        for (var filter : field.filters()) {
            if (!(filter instanceof GeneratedConditionFilter gcf)) continue;
            for (var bp : gcf.bodyParams()) {
                var colName = bp.column().javaName();
                var typeClass = ClassName.bestGuess(bp.javaType());
                // GraphQL ID scalars are delivered as String by GraphQL-Java; all other scalars
                // (Int, Boolean, String, …) are delivered as their natural Java counterpart,
                // which already matches the jOOQ column type stored in bp.javaType().
                boolean idScalar = "ID".equals(bp.graphqlTypeName());
                if (bp.list()) {
                    var listVarName = toCamelCase(bp.name()) + "Keys";
                    if (idScalar && !"java.lang.String".equals(bp.javaType())) {
                        // ID → non-String column: parse each String element
                        builder.addStatement("$T<$T> $L = env.getArgument($S)", LIST, String.class, listVarName, bp.name());
                        builder.addStatement("condition = condition.and(table.$L.in($L.stream().map($T::valueOf).toList()))",
                            colName, listVarName, typeClass);
                    } else {
                        // Native match (or String column): GraphQL-Java delivers the right type directly
                        builder.addStatement("$T<$T> $L = env.getArgument($S)", LIST, typeClass, listVarName, bp.name());
                        builder.addStatement("condition = condition.and(table.$L.in($L))", colName, listVarName);
                    }
                } else {
                    var keyVarName = toCamelCase(bp.name());
                    if (idScalar && !"java.lang.String".equals(bp.javaType())) {
                        // ID → non-String column: parse the String
                        builder.addStatement("$T $L = $T.valueOf(env.<$T>getArgument($S))",
                            typeClass, keyVarName, typeClass, String.class, bp.name());
                    } else {
                        // Native match: direct assignment
                        builder.addStatement("$T $L = env.getArgument($S)", typeClass, keyVarName, bp.name());
                    }
                    builder.addStatement("condition = condition.and(table.$L.eq($L))", colName, keyVarName);
                }
            }
        }

        builder.addCode(buildOrderByCode(field.orderBy(), tablesClass, tableRef));
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
