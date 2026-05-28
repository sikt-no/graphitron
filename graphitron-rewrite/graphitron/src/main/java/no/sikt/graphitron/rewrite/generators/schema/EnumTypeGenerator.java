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
 *
 * <p>The {@code type()} method body is statement-shaped; each enum value reaches the local
 * builder via a {@code private static} {@code enumValueDef_<sdlName>()} factory method
 * collected through {@link HelperMethodSink}.
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
        var sink = new HelperMethodSink();
        var body = CodeBlock.builder();
        body.addStatement("$T.Builder b = $T.newEnum()", ENUM_TYPE, ENUM_TYPE);
        body.addStatement("b.name($S)", et.name());
        var enumDescription = schemaType.getDescription();
        if (enumDescription != null && !enumDescription.isEmpty()) {
            body.addStatement("b.description($S)", enumDescription);
        }
        for (var value : et.values()) {
            String helper = sink.addEnumValueDef(value);
            body.addStatement("b.value($L())", helper);
        }
        AppliedDirectiveEmitter.emitApplications(body, "b", schemaType, sink);
        body.addStatement("return b.build()");

        var typeMethod = MethodSpec.methodBuilder("type")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ENUM_TYPE)
            .addCode(body.build())
            .build();

        var classBuilder = TypeSpec.classBuilder(et.name() + "Type")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(typeMethod);
        sink.contributeTo(classBuilder);
        return classBuilder.build();
    }

    /**
     * Builds a {@code private static GraphQLEnumValueDefinition <methodName>()} factory method
     * whose body is statement-flattened.
     */
    static MethodSpec buildValueDefinitionMethod(String methodName, EnumValueSpec value,
                                                  HelperMethodSink sink) {
        // Set the runtime value alongside the name. SchemaGenerator's SDL path defaults runtime
        // value to the name string; `newEnumValueDefinition().name(x).build()` alone leaves it
        // null, which makes every serialization of a "string-matching-enum-name" blow up with
        // "Unknown value 'X'" at the Coercing layer. The runtime string comes pre-resolved off
        // EnumValueSpec — @field(name:) when present, sdlName otherwise.
        var body = CodeBlock.builder();
        body.addStatement("$T.Builder b = $T.newEnumValueDefinition()", ENUM_VALUE, ENUM_VALUE);
        body.addStatement("b.name($S)", value.sdlName());
        body.addStatement("b.value($S)", value.runtimeValue());
        if (value.description() != null) {
            body.addStatement("b.description($S)", value.description());
        }
        if (value.deprecationReason() != null) {
            body.addStatement("b.deprecationReason($S)", value.deprecationReason());
        }
        AppliedDirectiveEmitter.emitApplications(body, "b", value.source(), sink);
        body.addStatement("return b.build()");
        return MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(ENUM_VALUE)
            .addCode(body.build())
            .build();
    }
}
