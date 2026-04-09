package no.sikt.graphitron.rewrite.generators.fields;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.field.ChildField;
import no.sikt.graphitron.rewrite.field.GraphitronField;
import no.sikt.graphitron.rewrite.field.QueryField;

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
 *   <li>A {@code wiring()} method that registers each data fetcher by method reference.</li>
 * </ul>
 */
public class FieldsCodeGenerator {

    private static final ClassName ENV              = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName SELECTED_FIELD   = ClassName.get("graphql.schema", "SelectedField");
    private static final ClassName TYPE_WIRING      = ClassName.get("graphql.schema.idl", "TypeRuntimeWiring");
    private static final ClassName WIRING_BUILDER   = ClassName.get("graphql.schema.idl", "TypeRuntimeWiring", "Builder");
    private static final ClassName COMPLETABLE_FUTURE = ClassName.get("java.util.concurrent", "CompletableFuture");
    private static final ClassName LIST             = ClassName.get("java.util", "List");
    private static final ClassName RECORD           = ClassName.get("org.jooq", "Record");

    public TypeSpec generate(String typeName, List<GraphitronField> fields) {
        var className = typeName + "Fields";
        var builder = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC);

        for (var field : fields) {
            if (field instanceof QueryField.LookupQueryField lookup) {
                builder.addMethod(buildLookupDataFetcher(lookup));
                builder.addMethod(buildLookupMethod(lookup));
            } else if (field instanceof ChildField.TableField tf && tf.splitQuery()) {
                builder.addMethod(buildSplitQueryDataFetcher(tf));
                builder.addMethod(buildSplitRowsMethod(tf));
            } else {
                builder.addMethod(buildFieldStub(field.name()));
            }
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
