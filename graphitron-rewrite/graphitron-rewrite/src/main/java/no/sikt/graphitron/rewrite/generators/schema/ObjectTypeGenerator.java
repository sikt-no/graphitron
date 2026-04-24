package no.sikt.graphitron.rewrite.generators.schema;

import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLUnionType;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Emits one {@code <TypeName>Type} class per GraphQL object, interface, or union type into
 * {@code <outputPackage>.rewrite.schema}. Each class exposes a single
 * {@code public static <GraphQLObjectType|Interface|Union> type()} method that rebuilds the
 * type as a programmatic graphql-java value at runtime.
 *
 * <p>Third of the Commit B leaf-type emitters (after
 * {@link EnumTypeGenerator} and {@link InputTypeGenerator}). Root operation types (Query /
 * Mutation / Subscription) are included in the output; the assembler routes them to the
 * corresponding {@code schemaBuilder.query(...)} / {@code .mutation(...)} /
 * {@code .subscription(...)} entry points rather than {@code additionalType(...)}.
 *
 * <p>Cross-type references use {@code GraphQLTypeReference.typeRef(name)} so emission order
 * is irrelevant. Field argument types are rendered by {@link #buildInputTypeRef} which mirrors
 * {@link InputTypeGenerator#buildInputTypeRef} structurally; keeping them separate so a later
 * directive-translation pass can diverge the two without cross-contamination.
 *
 * <p>For every type whose name is a key in the {@code fetcherBodies} map, the emitted class also
 * exposes {@code public static void registerFetchers(GraphQLCodeRegistry.Builder codeRegistry)}.
 * The body comes from {@link FetcherRegistrationsEmitter} and attaches each fetcher to the
 * shared code registry by {@link graphql.schema.FieldCoordinates}. The assembler
 * ({@link GraphitronSchemaClassGenerator}) invokes {@code registerFetchers} for every such type
 * before sealing the registry into the schema.
 *
 * <p>Introspection types (names starting with {@code __}) and federation-injected types
 * (names starting with {@code _}) are skipped — neither is part of the user surface.
 */
public final class ObjectTypeGenerator {

    private static final ClassName OBJECT_TYPE      = ClassName.get("graphql.schema", "GraphQLObjectType");
    private static final ClassName INTERFACE_TYPE   = ClassName.get("graphql.schema", "GraphQLInterfaceType");
    private static final ClassName UNION_TYPE       = ClassName.get("graphql.schema", "GraphQLUnionType");
    private static final ClassName FIELD_DEF        = ClassName.get("graphql.schema", "GraphQLFieldDefinition");
    private static final ClassName ARGUMENT         = ClassName.get("graphql.schema", "GraphQLArgument");
    private static final ClassName TYPE_REF         = ClassName.get("graphql.schema", "GraphQLTypeReference");
    private static final ClassName NON_NULL         = ClassName.get("graphql.schema", "GraphQLNonNull");
    private static final ClassName LIST             = ClassName.get("graphql.schema", "GraphQLList");
    private static final ClassName CODE_REGISTRY_BLDR = ClassName.get("graphql.schema", "GraphQLCodeRegistry", "Builder");

    private ObjectTypeGenerator() {}

    /**
     * Emits {@code <TypeName>Type} classes. {@code fetcherBodies} maps each type name to the
     * body of its {@code registerFetchers(GraphQLCodeRegistry.Builder)} method; types not present
     * in the map do not get the method. {@link FetcherRegistrationsEmitter} produces the map from
     * the classifier model.
     */
    public static List<TypeSpec> generate(GraphQLSchema assembled, Map<String, CodeBlock> fetcherBodies) {
        var result = new ArrayList<TypeSpec>();
        assembled.getAllTypesAsList().stream()
            .filter(t -> !t.getName().startsWith("_"))
            .forEach(t -> {
                if (t instanceof GraphQLObjectType obj) {
                    result.add(buildObjectTypeSpec(obj, fetcherBodies.get(obj.getName())));
                } else if (t instanceof GraphQLInterfaceType it) {
                    result.add(buildInterfaceTypeSpec(it));
                } else if (t instanceof GraphQLUnionType un) {
                    result.add(buildUnionTypeSpec(un));
                }
            });
        result.sort(Comparator.comparing(TypeSpec::name));
        return result;
    }

    /**
     * Convenience overload for tests that don't need {@code registerFetchers} emission.
     */
    public static List<TypeSpec> generate(GraphQLSchema assembled) {
        return generate(assembled, Map.of());
    }

    // ===== Object =====

    private static TypeSpec buildObjectTypeSpec(GraphQLObjectType objectType, CodeBlock fetcherBody) {
        var body = CodeBlock.builder()
            .add("return $T.newObject()", OBJECT_TYPE)
            .indent()
            .add("\n.name($S)", objectType.getName());
        if (objectType.getDescription() != null && !objectType.getDescription().isEmpty()) {
            body.add("\n.description($S)", objectType.getDescription());
        }
        for (var iface : objectType.getInterfaces()) {
            body.add("\n.withInterface($T.typeRef($S))", TYPE_REF, iface.getName());
        }
        for (var field : objectType.getFieldDefinitions()) {
            body.add("\n.field(").add(buildFieldDefinition(field)).add(")");
        }
        for (var applied : AppliedDirectiveEmitter.applicationsFor(objectType)) {
            body.add(applied);
        }
        body.add("\n.build();\n").unindent();

        var classBuilder = TypeSpec.classBuilder(objectType.getName() + "Type")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(MethodSpec.methodBuilder("type")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(OBJECT_TYPE)
                .addCode(body.build())
                .build());

        if (fetcherBody != null && !fetcherBody.isEmpty()) {
            classBuilder.addMethod(MethodSpec.methodBuilder("registerFetchers")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(CODE_REGISTRY_BLDR, "codeRegistry")
                .addCode(fetcherBody)
                .build());
        }
        return classBuilder.build();
    }

    // ===== Interface =====

    private static TypeSpec buildInterfaceTypeSpec(GraphQLInterfaceType interfaceType) {
        var body = CodeBlock.builder()
            .add("return $T.newInterface()", INTERFACE_TYPE)
            .indent()
            .add("\n.name($S)", interfaceType.getName());
        if (interfaceType.getDescription() != null && !interfaceType.getDescription().isEmpty()) {
            body.add("\n.description($S)", interfaceType.getDescription());
        }
        for (var field : interfaceType.getFieldDefinitions()) {
            body.add("\n.field(").add(buildFieldDefinition(field)).add(")");
        }
        for (var applied : AppliedDirectiveEmitter.applicationsFor(interfaceType)) {
            body.add(applied);
        }
        body.add("\n.build();\n").unindent();

        return TypeSpec.classBuilder(interfaceType.getName() + "Type")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(MethodSpec.methodBuilder("type")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(INTERFACE_TYPE)
                .addCode(body.build())
                .build())
            .build();
    }

    // ===== Union =====

    private static TypeSpec buildUnionTypeSpec(GraphQLUnionType unionType) {
        var body = CodeBlock.builder()
            .add("return $T.newUnionType()", UNION_TYPE)
            .indent()
            .add("\n.name($S)", unionType.getName());
        if (unionType.getDescription() != null && !unionType.getDescription().isEmpty()) {
            body.add("\n.description($S)", unionType.getDescription());
        }
        for (var member : unionType.getTypes()) {
            body.add("\n.possibleType($T.typeRef($S))", TYPE_REF, member.getName());
        }
        for (var applied : AppliedDirectiveEmitter.applicationsFor(unionType)) {
            body.add(applied);
        }
        body.add("\n.build();\n").unindent();

        return TypeSpec.classBuilder(unionType.getName() + "Type")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(MethodSpec.methodBuilder("type")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(UNION_TYPE)
                .addCode(body.build())
                .build())
            .build();
    }

    // ===== Field / argument / type-ref =====

    private static CodeBlock buildFieldDefinition(graphql.schema.GraphQLFieldDefinition field) {
        var block = CodeBlock.builder()
            .add("$T.newFieldDefinition()", FIELD_DEF)
            .add(".name($S)", field.getName())
            .add(".type(").add(buildOutputTypeRef(field.getType())).add(")");
        if (field.getDescription() != null && !field.getDescription().isEmpty()) {
            block.add(".description($S)", field.getDescription());
        }
        if (field.isDeprecated()) {
            block.add(".deprecate($S)", field.getDeprecationReason());
        }
        for (var arg : field.getArguments()) {
            block.add(".argument(").add(buildArgument(arg)).add(")");
        }
        for (var applied : AppliedDirectiveEmitter.applicationsFor(field)) {
            block.add(applied);
        }
        block.add(".build()");
        return block.build();
    }

    private static CodeBlock buildArgument(graphql.schema.GraphQLArgument arg) {
        var block = CodeBlock.builder()
            .add("$T.newArgument()", ARGUMENT)
            .add(".name($S)", arg.getName())
            .add(".type(").add(buildInputTypeRef((graphql.schema.GraphQLInputType) arg.getType())).add(")");
        if (arg.getDescription() != null && !arg.getDescription().isEmpty()) {
            block.add(".description($S)", arg.getDescription());
        }
        if (arg.hasSetDefaultValue()) {
            Object defaultValue = graphql.schema.GraphQLArgument.getArgumentDefaultValue(arg);
            block.add(".defaultValueProgrammatic(").add(GraphQLValueEmitter.emit(defaultValue)).add(")");
        }
        if (arg.isDeprecated()) {
            block.add(".deprecate($S)", arg.getDeprecationReason());
        }
        for (var applied : AppliedDirectiveEmitter.applicationsFor(arg)) {
            block.add(applied);
        }
        block.add(".build()");
        return block.build();
    }

    static CodeBlock buildOutputTypeRef(GraphQLOutputType type) {
        return buildTypeRef(type);
    }

    static CodeBlock buildInputTypeRef(graphql.schema.GraphQLInputType type) {
        return buildTypeRef(type);
    }

    private static CodeBlock buildTypeRef(GraphQLType type) {
        if (type instanceof GraphQLNonNull nn) {
            return CodeBlock.builder()
                .add("$T.nonNull(", NON_NULL)
                .add(buildTypeRef(nn.getWrappedType()))
                .add(")")
                .build();
        }
        if (type instanceof GraphQLList list) {
            return CodeBlock.builder()
                .add("$T.list(", LIST)
                .add(buildTypeRef(list.getWrappedType()))
                .add(")")
                .build();
        }
        var name = ((GraphQLNamedType) type).getName();
        return CodeBlock.of("$T.typeRef($S)", TYPE_REF, name);
    }
}
