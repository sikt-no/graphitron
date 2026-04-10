package no.sikt.graphitron.rewrite.generators.fields;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.field.ChildField;
import no.sikt.graphitron.rewrite.field.FieldWrapper;
import no.sikt.graphitron.rewrite.field.GraphitronField;
import no.sikt.graphitron.rewrite.field.QueryField;
import no.sikt.graphitron.rewrite.field.ReturnTypeRef;
import no.sikt.graphitron.rewrite.field.ServiceMethodRef;
import no.sikt.graphitron.rewrite.type.TableRef;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates a {@link TypeSpec} for one {@code <TypeName>Fields} class in {@code rewrite.types}.
 *
 * <p>The class is named after the GraphQL type (e.g. {@code FilmFields} for GraphQL type
 * {@code Film}). This is distinct from the SQL-scope class in {@code rewrite.tables}, which is
 * named after the jOOQ table class.
 *
 * <p>Each class contains:
 * <ul>
 *   <li>For most fields: one {@code public static Object fieldName(DataFetchingEnvironment env)}
 *       stub throwing {@link UnsupportedOperationException}.</li>
 *   <li>For {@link QueryField.LookupQueryField}: an async data fetcher stub returning
 *       {@code CompletableFuture<List<Record>>} and a bespoke synchronous
 *       {@code lookupFieldName(DataFetchingEnvironment env, SelectedField sel)} stub.</li>
 *   <li>For {@link ChildField.ServiceField} with a table-bound return type and a resolved service
 *       method reference: an async DataLoader-based data fetcher and a rows method
 *       {@code loadFieldName(List<Row>, DataFetchingEnvironment, SelectedField)} that extracts
 *       arguments, calls the service, and delegates to the table's {@code selectMany}/
 *       {@code selectOne}.</li>
 *   <li>A {@code wiring()} method that registers each data fetcher by method reference.</li>
 * </ul>
 */
public class FieldsCodeGenerator {

    private static final ClassName ENV               = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName SELECTED_FIELD    = ClassName.get("graphql.schema", "SelectedField");
    private static final ClassName TYPE_WIRING       = ClassName.get("graphql.schema.idl", "TypeRuntimeWiring");
    private static final ClassName WIRING_BUILDER    = ClassName.get("graphql.schema.idl", "TypeRuntimeWiring", "Builder");
    private static final ClassName COMPLETABLE_FUTURE = ClassName.get("java.util.concurrent", "CompletableFuture");
    private static final ClassName LIST              = ClassName.get("java.util", "List");
    private static final ClassName RECORD            = ClassName.get("org.jooq", "Record");
    private static final ClassName ROW               = ClassName.get("org.jooq", "Row");
    private static final ClassName DSL               = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName DATA_LOADER       = ClassName.get("org.dataloader", "DataLoader");
    private static final ClassName DATA_LOADER_FACTORY = ClassName.get("org.dataloader", "DataLoaderFactory");
    private static final ClassName GRAPHITRON_CONTEXT = ClassName.get("no.sikt.graphql", "GraphitronContext");

    /**
     * Generates the {@code *Fields} class TypeSpec for the given GraphQL type.
     *
     * @param typeName    the GraphQL type name (e.g. {@code "Language"})
     * @param parentTable the resolved {@link TableRef} for the type, or {@code null} for root types
     * @param fields      the classified fields belonging to this type
     */
    public TypeSpec generate(String typeName, TableRef parentTable, List<GraphitronField> fields) {
        var className = typeName + "Fields";
        var builder = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC);

        boolean needsGraphitronContextHelper = false;

        for (var field : fields) {
            if (field instanceof QueryField.LookupQueryField lookup) {
                builder.addMethod(buildLookupDataFetcher(lookup));
                builder.addMethod(buildLookupMethod(lookup));
            } else if (field instanceof ChildField.ServiceField sf
                    && sf.serviceMethodRef() instanceof ServiceMethodRef.Resolved smr
                    && sf.returnType() instanceof ReturnTypeRef.TableBoundReturnType tb
                    && tb.table() instanceof TableRef.ResolvedTable rt
                    && parentTable instanceof TableRef.ResolvedTable prt) {
                builder.addMethod(buildServiceDataFetcher(sf, smr, tb, prt, className));
                builder.addMethod(buildServiceRowsMethod(sf, smr, tb, rt));
                needsGraphitronContextHelper = true;
            } else if (field instanceof ChildField.TableField tf && tf.splitQuery()) {
                builder.addMethod(buildSplitQueryDataFetcher(tf));
                builder.addMethod(buildSplitRowsMethod(tf));
            } else {
                builder.addMethod(buildFieldStub(field.name()));
            }
        }

        if (needsGraphitronContextHelper) {
            builder.addMethod(buildGraphitronContextHelper());
        }

        builder.addMethod(buildWiringMethod(typeName, className, fields));

        return builder.build();
    }

    private MethodSpec buildFieldStub(String fieldName) {
        return MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(ENV, "env")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    private MethodSpec buildLookupDataFetcher(QueryField.LookupQueryField field) {
        var returnType = ParameterizedTypeName.get(COMPLETABLE_FUTURE, ParameterizedTypeName.get(LIST, RECORD));
        return MethodSpec.methodBuilder(field.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(returnType)
            .addParameter(ENV, "env")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    private MethodSpec buildLookupMethod(QueryField.LookupQueryField field) {
        var methodName = "lookup" + capitalize(field.name());
        return MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(LIST, RECORD))
            .addParameter(ENV, "env")
            .addParameter(SELECTED_FIELD, "sel")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    /**
     * Builds the DataLoader-based async data fetcher for a {@link ChildField.ServiceField} with a
     * table-bound return type and a resolved service method.
     *
     * <p>List/connection: returns {@code CompletableFuture<List<Record>>}.
     * Single: returns {@code CompletableFuture<Record>}.
     */
    private MethodSpec buildServiceDataFetcher(
            ChildField.ServiceField sf,
            ServiceMethodRef.Resolved smr,
            ReturnTypeRef.TableBoundReturnType tb,
            TableRef.ResolvedTable prt,
            String className) {

        boolean isList = !(tb.wrapper() instanceof FieldWrapper.Single);
        var valueType = isList ? ParameterizedTypeName.get(LIST, RECORD) : RECORD;
        var returnType = ParameterizedTypeName.get(COMPLETABLE_FUTURE, valueType);
        var loaderType = ParameterizedTypeName.get(DATA_LOADER, ROW, valueType);

        // Reference to the parent table constant, e.g. Tables.LANGUAGE
        var tablesClass = ClassName.get(GeneratorConfig.outputPackage() + ".tables", "Tables");
        String tableField = prt.javaFieldName();
        String pkColumn = prt.primaryKeyColumnSqlName().toUpperCase();
        String rowsMethodName = "load" + capitalize(sf.name());

        // Build the inner DataLoader lambda as a code block
        var lambdaBlock = CodeBlock.builder()
            .add("(keys, batchEnv) -> {\n")
            .indent()
            .addStatement("$T dfe = ($T) batchEnv.getKeyContextsList().get(0)", ENV, ENV)
            .addStatement("$T sel = dfe.getSelectionSet().getField($S)", SELECTED_FIELD, sf.name())
            .addStatement("return $T.completedFuture($L(keys, dfe, sel))", COMPLETABLE_FUTURE, rowsMethodName)
            .unindent()
            .add("}")
            .build();

        return MethodSpec.methodBuilder(sf.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(returnType)
            .addParameter(ENV, "env")
            .addStatement("$T name = graphitronContext(env).getDataLoaderName(env)", String.class)
            .addCode(
                "$T loader = env.getDataLoaderRegistry()\n" +
                "    .computeIfAbsent(name, k -> $T.newDataLoaderWithContext($L));\n",
                loaderType, DATA_LOADER_FACTORY, lambdaBlock)
            .addStatement(
                "$T key = $T.row((($T) env.getSource()).get($T.$L.$L))",
                ROW, DSL, RECORD, tablesClass, tableField, pkColumn)
            .addStatement("return loader.load(key, env)")
            .build();
    }

    /**
     * Builds the rows method for a {@link ChildField.ServiceField}. The rows method:
     * <ol>
     *   <li>Extracts GraphQL arguments from the DFE.</li>
     *   <li>Extracts context values via {@code GraphitronContext.getContextArgument}.</li>
     *   <li>Calls the service method with {@code keys} as the sources parameter.</li>
     *   <li>Returns via the table's {@code selectMany} or {@code selectOne}.</li>
     * </ol>
     */
    private MethodSpec buildServiceRowsMethod(
            ChildField.ServiceField sf,
            ServiceMethodRef.Resolved smr,
            ReturnTypeRef.TableBoundReturnType tb,
            TableRef.ResolvedTable rt) {

        boolean isList = !(tb.wrapper() instanceof FieldWrapper.Single);
        var listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        var returnType = isList ? ParameterizedTypeName.get(LIST, listOfRecord) : listOfRecord;

        var builder = MethodSpec.methodBuilder("load" + capitalize(sf.name()))
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(returnType)
            .addParameter(ParameterizedTypeName.get(LIST, ROW), "keys")
            .addParameter(ENV, "dfe")
            .addParameter(SELECTED_FIELD, "sel");

        // Emit arg and context extraction statements
        for (var param : smr.params()) {
            switch (param.kind()) {
                case ARG -> builder.addStatement(
                    "$T $L = dfe.getArgument($S)",
                    Object.class, param.name(), param.name());
                case CONTEXT -> builder.addStatement(
                    "$T $L = graphitronContext(dfe).getContextArgument(dfe, $S)",
                    Object.class, param.name(), param.name());
                case SOURCES -> {} // 'keys' is passed directly
            }
        }

        // Build service call argument list
        var serviceCallArgs = smr.params().stream()
            .map(p -> p.kind() == ServiceMethodRef.ParamKind.SOURCES ? "keys" : p.name())
            .toList();

        builder.addStatement(
            "$T serviceResult = $L.$L($L)",
            Object.class,
            sf.serviceRef().className(),
            sf.serviceRef().methodName(),
            String.join(", ", serviceCallArgs));

        // Return via table selectMany / selectOne
        var tableClass = ClassName.get(GeneratorConfig.outputPackage() + ".tables", rt.javaClassName());
        if (isList) {
            builder.addStatement(
                "return $T.selectMany(keys, sel, ($T<?>) serviceResult)",
                tableClass, List.class);
        } else {
            builder.addStatement("return $T.selectOne(keys, sel, serviceResult)", tableClass);
        }

        return builder.build();
    }

    /**
     * Private static helper added once per {@code *Fields} class that uses service-field DataLoader
     * generation. Retrieves the {@link no.sikt.graphql.GraphitronContext} from the GraphQL context.
     */
    private MethodSpec buildGraphitronContextHelper() {
        return MethodSpec.methodBuilder("graphitronContext")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(GRAPHITRON_CONTEXT)
            .addParameter(ENV, "env")
            .addStatement("return env.getGraphQlContext().get($S)", "graphitronContext")
            .build();
    }

    private MethodSpec buildSplitQueryDataFetcher(ChildField.TableField field) {
        var returnType = ParameterizedTypeName.get(COMPLETABLE_FUTURE, ParameterizedTypeName.get(LIST, RECORD));
        return MethodSpec.methodBuilder(field.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(returnType)
            .addParameter(ENV, "env")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    private MethodSpec buildSplitRowsMethod(ChildField.TableField field) {
        var sourcesType = ParameterizedTypeName.get(LIST, RECORD);
        return MethodSpec.methodBuilder("rows" + capitalize(field.name()))
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(sourcesType, "sources")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    private MethodSpec buildWiringMethod(String typeName, String className, List<GraphitronField> fields) {
        var body = CodeBlock.builder()
            .add("return $T.newTypeWiring($S)", TYPE_WIRING, typeName);

        if (fields.isEmpty()) {
            body.add(";\n");
        } else {
            body.indent();
            for (var field : fields) {
                body.add("\n.dataFetcher($S, $L::$L)", field.name(), className, field.name());
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

    private static String capitalize(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
