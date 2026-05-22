package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.EnumValueSpec;
import no.sikt.graphitron.rewrite.model.GraphitronType;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Emits one {@code <TypeName>Type} class per GraphQL enum type into
 * {@code <outputPackage>.rewrite.schema}. Each class exposes a single
 * {@code public static GraphQLEnumType type()} method that rebuilds the enum as a
 * programmatic graphql-java type at runtime. Reads from
 * {@link GraphitronSchema#types()} only — introspection and federation-injected enums don't
 * appear there because the classifier skips them.
 */
public final class EnumTypeGenerator {

    private static final ClassName ENUM_TYPE     = ClassName.get("graphql.schema", "GraphQLEnumType");
    private static final ClassName ENUM_VALUE    = ClassName.get("graphql.schema", "GraphQLEnumValueDefinition");

    private EnumTypeGenerator() {}

    public static List<TypeSpec> generate(GraphitronSchema schema) {
        var result = new ArrayList<TypeSpec>();
        for (var entry : schema.types().entrySet()) {
            if (entry.getKey().startsWith("_")) continue;
            if (entry.getValue() instanceof GraphitronType.EnumType et) {
                result.add(buildEnumTypeSpec(et));
            }
        }
        result.sort(Comparator.comparing(TypeSpec::name));
        return result;
    }

    private static TypeSpec buildEnumTypeSpec(GraphitronType.EnumType et) {
        var schemaType = et.schemaType();
        var body = CodeBlock.builder()
            .add("return $T.newEnum()", ENUM_TYPE)
            .indent()
            .add("\n.name($S)", et.name());
        var enumDescription = schemaType.getDescription();
        if (enumDescription != null && !enumDescription.isEmpty()) {
            body.add("\n.description($S)", enumDescription);
        }
        for (var value : et.values()) {
            body.add("\n.value(").add(buildValueDefinition(value)).add(")");
        }
        for (var applied : AppliedDirectiveEmitter.applicationsFor(schemaType)) {
            body.add(applied);
        }
        body.add("\n.build();\n").unindent();

        var typeMethod = MethodSpec.methodBuilder("type")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ENUM_TYPE)
            .addCode(body.build())
            .build();

        return TypeSpec.classBuilder(et.name() + "Type")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(typeMethod)
            .build();
    }

    private static CodeBlock buildValueDefinition(EnumValueSpec value) {
        // Set the runtime value alongside the name. SchemaGenerator's SDL path defaults runtime
        // value to the name string; `newEnumValueDefinition().name(x).build()` alone leaves it
        // null, which makes every serialization of a "string-matching-enum-name" blow up with
        // "Unknown value 'X'" at the Coercing layer. The runtime string comes pre-resolved off
        // EnumValueSpec — @field(name:) when present, sdlName otherwise.
        var block = CodeBlock.builder()
            .add("$T.newEnumValueDefinition()", ENUM_VALUE)
            .add(".name($S)", value.sdlName())
            .add(".value($S)", value.runtimeValue());
        if (value.description() != null) {
            block.add(".description($S)", value.description());
        }
        if (value.deprecationReason() != null) {
            block.add(".deprecationReason($S)", value.deprecationReason());
        }
        for (var applied : AppliedDirectiveEmitter.applicationsFor(value.source())) {
            block.add(applied);
        }
        block.add(".build()");
        return block.build();
    }
}
