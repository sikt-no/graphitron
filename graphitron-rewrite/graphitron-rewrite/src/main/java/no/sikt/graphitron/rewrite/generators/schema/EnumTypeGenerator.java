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
 * programmatic graphql-java type at runtime. Introspection types
 * ({@code "__"}-prefixed) and federation-injected enums
 * ({@code "_"}-prefixed) are skipped; neither enters the user's schema surface.
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
        for (var applied : AppliedDirectiveEmitter.applicationsFor(enumType)) {
            body.add(applied);
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
        // Set the runtime value alongside the name. SchemaGenerator's SDL path defaults runtime
        // value to the name string; `newEnumValueDefinition().name(x).build()` alone leaves it
        // null, which makes every serialization of a "string-matching-enum-name" blow up with
        // "Unknown value 'X'" at the Coercing layer.
        var block = CodeBlock.builder()
            .add("$T.newEnumValueDefinition()", ENUM_VALUE)
            .add(".name($S)", value.getName())
            .add(".value($S)", value.getName());
        if (value.getDescription() != null && !value.getDescription().isEmpty()) {
            block.add(".description($S)", value.getDescription());
        }
        if (value.isDeprecated()) {
            block.add(".deprecationReason($S)", value.getDeprecationReason());
        }
        for (var applied : AppliedDirectiveEmitter.applicationsFor(value)) {
            block.add(applied);
        }
        block.add(".build()");
        return block.build();
    }
}
