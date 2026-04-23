package no.sikt.graphitron.rewrite.generators.schema;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Emits one {@code <TypeName>Type} class per GraphQL enum type into
 * {@code <outputPackage>.rewrite.schema}. Each class exposes a single
 * {@code public static GraphQLEnumType type()} method that rebuilds the enum as a
 * programmatic graphql-java type at runtime.
 *
 * <p>First of the Commit B leaf-type emitters. Introspection types (those whose names start
 * with {@code "__"}) and federation-injected enums (names starting with {@code "_"}) are
 * skipped; neither enters the user's schema surface.
 *
 * <p>Descriptions and deprecation reasons are preserved so the programmatic schema round-trips
 * to SDL with the same informational payload. Directive applications are not yet translated;
 * they will be added in a follow-up step within Commit B per §Directive emission strategy of
 * {@code plan-graphitron-prebuilt-schema.md}.
 */
public final class EnumTypeGenerator {

    private static final ClassName ENUM_TYPE     = ClassName.get("graphql.schema", "GraphQLEnumType");
    private static final ClassName ENUM_VALUE    = ClassName.get("graphql.schema", "GraphQLEnumValueDefinition");

    private EnumTypeGenerator() {}

    public static List<TypeSpec> generate(GraphQLSchema assembled) {
        var result = new ArrayList<TypeSpec>();
        assembled.getAllTypesAsList().stream()
            .filter(t -> t instanceof GraphQLEnumType)
            .map(t -> (GraphQLEnumType) t)
            .filter(t -> !t.getName().startsWith("_"))
            .sorted(Comparator.comparing(GraphQLEnumType::getName))
            .forEach(enumType -> result.add(buildEnumTypeSpec(enumType)));
        return result;
    }

    private static TypeSpec buildEnumTypeSpec(GraphQLEnumType enumType) {
        var body = CodeBlock.builder()
            .add("return $T.newEnum()", ENUM_TYPE)
            .indent()
            .add("\n.name($S)", enumType.getName());
        if (enumType.getDescription() != null && !enumType.getDescription().isEmpty()) {
            body.add("\n.description($S)", enumType.getDescription());
        }
        for (var value : enumType.getValues()) {
            body.add("\n.value(").add(buildValueDefinition(value)).add(")");
        }
        body.add("\n.build();\n").unindent();

        var typeMethod = MethodSpec.methodBuilder("type")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ENUM_TYPE)
            .addCode(body.build())
            .build();

        return TypeSpec.classBuilder(enumType.getName() + "Type")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(typeMethod)
            .build();
    }

    private static CodeBlock buildValueDefinition(graphql.schema.GraphQLEnumValueDefinition value) {
        var block = CodeBlock.builder()
            .add("$T.newEnumValueDefinition()", ENUM_VALUE)
            .add(".name($S)", value.getName());
        if (value.getDescription() != null && !value.getDescription().isEmpty()) {
            block.add(".description($S)", value.getDescription());
        }
        if (value.isDeprecated()) {
            block.add(".deprecationReason($S)", value.getDeprecationReason());
        }
        block.add(".build()");
        return block.build();
    }
}
