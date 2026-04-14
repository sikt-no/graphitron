package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.RewriteConfig;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ChildField;
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
 * <p>Unlike {@link TypeFieldsGenerator}, where methods <em>are</em> the data fetcher
 * (they take a {@code DataFetchingEnvironment} parameter and implement the interface directly),
 * this generator uses the <em>factory pattern</em>: each method <em>returns</em> a
 * {@code DataFetcher<T>} instance. The wiring step therefore calls {@code ClassName.fieldName()}
 * rather than passing a method reference {@code ClassName::fieldName}.
 *
 * <p>Generated wiring pattern:
 * <pre>{@code
 * .dataFetcher("title", FilmFetchers.title())
 * }</pre>
 * vs the fields pattern:
 * <pre>{@code
 * .dataFetcher("title", FilmFields::title)
 * }</pre>
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

    private static final ClassName DATA_FETCHER   = ClassName.get("graphql.schema", "DataFetcher");
    private static final ClassName TYPE_WIRING    = ClassName.get("graphql.schema.idl", "TypeRuntimeWiring");
    private static final ClassName WIRING_BUILDER = ClassName.get("graphql.schema.idl", "TypeRuntimeWiring", "Builder");
    private static final ClassName RECORD         = ClassName.get("org.jooq", "Record");
    private static final ClassName RESULT         = ClassName.get("org.jooq", "Result");
    private static final ClassName SORT_FIELD     = ClassName.get("org.jooq", "SortField");
    private static final ClassName DSL            = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName LIST           = ClassName.get("java.util", "List");

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
            if (field instanceof ChildField.ColumnField cf && parentTable != null) {
                builder.addMethod(buildColumnFetcher(cf, parentTable));
            } else if (field instanceof QueryField.QueryTableField qtf) {
                builder.addMethod(buildQueryTableFetcher(qtf));
            } else {
                builder.addMethod(buildStub(field.name()));
            }
        }

        builder.addMethod(buildWiringMethod(typeName, className, fields));
        return builder.build();
    }

    /**
     * Generates a {@code DataFetcher<Object>} factory method for a column field.
     *
     * <p>Generated code:
     * <pre>{@code
     * public static DataFetcher<Object> title() {
     *     return env -> ((Record) env.getSource()).get(Tables.FILM.TITLE);
     * }
     * }</pre>
     */
    private static MethodSpec buildColumnFetcher(ChildField.ColumnField cf, TableRef parentTable) {
        var tablesClass = ClassName.get(RewriteConfig.getGeneratedJooqPackage(), "Tables");
        return MethodSpec.methodBuilder(cf.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(DATA_FETCHER, TypeName.OBJECT))
            .addStatement("return env -> (($T) env.getSource()).get($T.$L.$L)",
                RECORD, tablesClass, parentTable.javaFieldName(), cf.column().javaName())
            .build();
    }

    /**
     * Generates a {@code DataFetcher<Result<Record>>} or {@code DataFetcher<Record>} factory method
     * for a root-query table field.
     *
     * <p>Generated code (list variant):
     * <pre>{@code
     * public static DataFetcher<Result<Record>> films() {
     *     return env -> {
     *         var condition = DSL.noCondition();
     *         List<SortField<?>> orderBy = List.of();
     *         return Film.selectMany(env, condition, orderBy);
     *     };
     * }
     * }</pre>
     */
    private static MethodSpec buildQueryTableFetcher(QueryField.QueryTableField qtf) {
        var tableRef = qtf.returnType().table();
        var tableClass = ClassName.get(RewriteConfig.outputPackage() + ".rewrite.types", qtf.returnType().returnTypeName());
        var tablesClass = ClassName.get(RewriteConfig.getGeneratedJooqPackage(), "Tables");
        boolean isList = qtf.returnType().wrapper().isList();

        TypeName fetcherValueType = isList
            ? ParameterizedTypeName.get(RESULT, RECORD)
            : RECORD;

        var lambdaBody = CodeBlock.builder();
        lambdaBody.add("env -> {\n");
        lambdaBody.indent();
        lambdaBody.add(buildConditionCall(qtf, tablesClass, tableRef));
        if (isList) {
            lambdaBody.add(buildOrderByCode(qtf.orderBy(), tablesClass, tableRef));
            lambdaBody.addStatement("return $T.selectMany(env, condition, orderBy)", tableClass);
        } else {
            lambdaBody.addStatement("return $T.selectOne(env, condition)", tableClass);
        }
        lambdaBody.unindent();
        lambdaBody.add("}");

        return MethodSpec.methodBuilder(qtf.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(DATA_FETCHER, fetcherValueType))
            .addCode(CodeBlock.of("return $L;\n", lambdaBody.build()))
            .build();
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
     * Generates a stub {@code DataFetcher<Object>} factory method for unhandled field types.
     *
     * <p>Generated code:
     * <pre>{@code
     * public static DataFetcher<Object> fieldName() {
     *     return env -> { throw new UnsupportedOperationException(); };
     * }
     * }</pre>
     */
    private static MethodSpec buildStub(String fieldName) {
        return MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(DATA_FETCHER, TypeName.OBJECT))
            .addStatement("return env -> { throw new $T(); }", UnsupportedOperationException.class)
            .build();
    }

    /**
     * Generates the {@code wiring()} method using factory method calls (not method references).
     *
     * <p>Generated code:
     * <pre>{@code
     * .dataFetcher("title", FilmFetchers.title())
     * }</pre>
     */
    private static MethodSpec buildWiringMethod(String typeName, String className, List<GraphitronField> fields) {
        var body = CodeBlock.builder()
            .add("return $T.newTypeWiring($S)", TYPE_WIRING, typeName);

        if (fields.isEmpty()) {
            body.add(";\n");
        } else {
            body.indent();
            for (var field : fields) {
                body.add("\n.dataFetcher($S, $L.$L())", field.name(), className, field.name());
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
}
