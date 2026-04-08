package no.sikt.graphitron.rewrite.generators.fields;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;

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
 *   <li>One {@code public static Object fieldName(DataFetchingEnvironment env)} stub per GraphQL
 *       field, satisfying the {@link graphql.schema.DataFetcher} functional interface by
 *       signature. These stubs throw {@link UnsupportedOperationException} until filled in by
 *       subsequent deliverables.</li>
 *   <li>A {@code wiring()} method that registers each field stub by method reference, e.g.
 *       {@code .dataFetcher("title", FilmFields::title)}. The wiring method is a pure manifest —
 *       no logic, no lambdas.</li>
 * </ul>
 */
public class FieldsCodeGenerator {

    private static final ClassName ENV             = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName TYPE_WIRING     = ClassName.get("graphql.schema.idl", "TypeRuntimeWiring");
    private static final ClassName WIRING_BUILDER  = ClassName.get("graphql.schema.idl", "TypeRuntimeWiring", "Builder");

    public TypeSpec generate(String typeName, List<String> fieldNames) {
        var className = typeName + "Fields";
        var builder = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC);

        for (var fieldName : fieldNames) {
            builder.addMethod(buildFieldMethod(fieldName));
        }
        builder.addMethod(buildWiringMethod(typeName, className, fieldNames));

        return builder.build();
    }

    private MethodSpec buildFieldMethod(String fieldName) {
        return MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(ENV, "env")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    private MethodSpec buildWiringMethod(String typeName, String className, List<String> fieldNames) {
        var body = CodeBlock.builder()
            .add("return $T.newTypeWiring($S)", TYPE_WIRING, typeName);

        if (fieldNames.isEmpty()) {
            body.add(";\n");
        } else {
            body.indent();
            for (var fieldName : fieldNames) {
                body.add("\n.dataFetcher($S, $L::$L)", fieldName, className, fieldName);
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
