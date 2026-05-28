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

import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ConnectionType;
import no.sikt.graphitron.rewrite.model.GraphitronType.EdgeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.PageInfoType;

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
 * <p>The {@code type()} method body is statement-shaped (one local builder variable, one
 * statement per element). Each non-trivial sub-value (per field, per applied directive)
 * routes through {@link HelperMethodSink} as its own {@code private static} factory method
 * on the same emitted class, so no single expression-statement carries a fluent chain whose
 * depth scales with type-element count.
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
     *
     * <p>Directive-driven {@code @asConnection} fields (bare-list return type with no pre-existing
     * Connection type in the schema) are rewritten at classifier time in the assembled schema
     * on the carrier field. For these fields the emitted return type reference is substituted with
     * the synthesised Connection name and the standard {@code first} / {@code after} pagination
     * arguments are appended.
     */
    public static List<TypeSpec> generate(GraphitronSchema schema, GraphQLSchema assembled,
                                          Map<String, CodeBlock> fetcherBodies) {
        var result = new ArrayList<TypeSpec>();
        for (var entry : schema.types().entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("_")) continue;
            var graphqlType = graphqlTypeFor(entry.getValue(), name, assembled);
            if (graphqlType instanceof GraphQLObjectType obj) {
                result.add(buildObjectTypeSpec(obj, fetcherBodies.get(name), schema));
            } else if (graphqlType instanceof GraphQLInterfaceType it) {
                result.add(buildInterfaceTypeSpec(it, schema));
            } else if (graphqlType instanceof GraphQLUnionType un) {
                result.add(buildUnionTypeSpec(un));
            }
            // Input / enum / scalar / unclassified entries are handled elsewhere or skipped.
        }
        result.sort(Comparator.comparing(TypeSpec::name));
        return result;
    }

    /**
     * Convenience overload for tests that don't need {@code registerFetchers} emission.
     */
    public static List<TypeSpec> generate(GraphitronSchema schema, GraphQLSchema assembled) {
        return generate(schema, assembled, Map.of());
    }

    /**
     * Resolves the graphql-java type form for a classified {@link GraphitronType} entry.
     * Synthesised and plain variants carry their form directly; variants classified by domain
     * (TableType, NodeType, …) look up via the assembled schema.
     */
    private static GraphQLNamedType graphqlTypeFor(GraphitronType variant, String name, GraphQLSchema assembled) {
        if (variant instanceof ConnectionType ct) return ct.schemaType();
        if (variant instanceof EdgeType et) return et.schemaType();
        if (variant instanceof PageInfoType pi) return pi.schemaType();
        if (variant instanceof GraphitronType.PlainObjectType pot) return pot.schemaType();
        var t = assembled.getType(name);
        return t instanceof GraphQLNamedType named ? named : null;
    }

    // ===== Object =====

    private static TypeSpec buildObjectTypeSpec(GraphQLObjectType objectType, CodeBlock fetcherBody,
                                               GraphitronSchema schema) {
        var sink = new HelperMethodSink();
        var body = CodeBlock.builder();
        body.addStatement("$T.Builder b = $T.newObject()", OBJECT_TYPE, OBJECT_TYPE);
        body.addStatement("b.name($S)", objectType.getName());
        if (objectType.getDescription() != null && !objectType.getDescription().isEmpty()) {
            body.addStatement("b.description($S)", objectType.getDescription());
        }
        for (var iface : objectType.getInterfaces()) {
            body.addStatement("b.withInterface($T.typeRef($S))", TYPE_REF, iface.getName());
        }
        for (var field : objectType.getFieldDefinitions()) {
            String helper = sink.addObjectFieldDef(objectType.getName(), field, schema);
            body.addStatement("b.field($L())", helper);
        }
        AppliedDirectiveEmitter.emitApplications(body, "b", objectType, sink);
        body.addStatement("return b.build()");

        var classBuilder = TypeSpec.classBuilder(objectType.getName() + "Type")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(MethodSpec.methodBuilder("type")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(OBJECT_TYPE)
                .addCode(body.build())
                .build());

        if (fetcherBody != null) {
            classBuilder.addMethod(MethodSpec.methodBuilder("registerFetchers")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(CODE_REGISTRY_BLDR, "codeRegistry")
                .addCode(fetcherBody)
                .build());
        }
        sink.contributeTo(classBuilder);
        return classBuilder.build();
    }

    // ===== Interface =====

    private static TypeSpec buildInterfaceTypeSpec(GraphQLInterfaceType interfaceType,
                                                   GraphitronSchema schema) {
        var sink = new HelperMethodSink();
        var body = CodeBlock.builder();
        body.addStatement("$T.Builder b = $T.newInterface()", INTERFACE_TYPE, INTERFACE_TYPE);
        body.addStatement("b.name($S)", interfaceType.getName());
        if (interfaceType.getDescription() != null && !interfaceType.getDescription().isEmpty()) {
            body.addStatement("b.description($S)", interfaceType.getDescription());
        }
        for (var field : interfaceType.getFieldDefinitions()) {
            String helper = sink.addObjectFieldDef(interfaceType.getName(), field, schema);
            body.addStatement("b.field($L())", helper);
        }
        AppliedDirectiveEmitter.emitApplications(body, "b", interfaceType, sink);
        body.addStatement("return b.build()");

        var classBuilder = TypeSpec.classBuilder(interfaceType.getName() + "Type")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(MethodSpec.methodBuilder("type")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(INTERFACE_TYPE)
                .addCode(body.build())
                .build());
        sink.contributeTo(classBuilder);
        return classBuilder.build();
    }

    // ===== Union =====

    private static TypeSpec buildUnionTypeSpec(GraphQLUnionType unionType) {
        var sink = new HelperMethodSink();
        var body = CodeBlock.builder();
        body.addStatement("$T.Builder b = $T.newUnionType()", UNION_TYPE, UNION_TYPE);
        body.addStatement("b.name($S)", unionType.getName());
        if (unionType.getDescription() != null && !unionType.getDescription().isEmpty()) {
            body.addStatement("b.description($S)", unionType.getDescription());
        }
        for (var member : unionType.getTypes()) {
            body.addStatement("b.possibleType($T.typeRef($S))", TYPE_REF, member.getName());
        }
        AppliedDirectiveEmitter.emitApplications(body, "b", unionType, sink);
        body.addStatement("return b.build()");

        var classBuilder = TypeSpec.classBuilder(unionType.getName() + "Type")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(MethodSpec.methodBuilder("type")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(UNION_TYPE)
                .addCode(body.build())
                .build());
        sink.contributeTo(classBuilder);
        return classBuilder.build();
    }

    // ===== Field / argument / type-ref =====

    /**
     * Builds a {@code private static GraphQLFieldDefinition <methodName>()} factory method.
     * Each per-arg sub-builder is also statement-flattened on a local variable so the chain
     * depth on every emitted statement is bounded.
     */
    static MethodSpec buildFieldDefinitionMethod(String methodName, String parentTypeName,
                                                  graphql.schema.GraphQLFieldDefinition field,
                                                  GraphitronSchema schema, HelperMethodSink sink) {
        // Carrier-field rewriting for directive-driven @asConnection has already happened in the
        // classifier (GraphitronSchemaBuilder.rebuildAssembledForConnections): the field arrives
        // with its return type pointing at the Connection and `first` / `after` arguments
        // appended. Emission reads the field as-is — no probe, no directive inspection.
        var body = CodeBlock.builder();
        body.addStatement("$T.Builder b = $T.newFieldDefinition()", FIELD_DEF, FIELD_DEF);
        body.addStatement("b.name($S)", field.getName());
        body.addStatement("b.type($L)", buildOutputTypeRef(field.getType()));
        if (field.getDescription() != null && !field.getDescription().isEmpty()) {
            body.addStatement("b.description($S)", field.getDescription());
        }
        if (field.isDeprecated()) {
            body.addStatement("b.deprecate($S)", field.getDeprecationReason());
        }
        int argIdx = 0;
        for (var arg : field.getArguments()) {
            String argVar = "a" + argIdx++;
            body.addStatement("$T.Builder $L = $T.newArgument()", ARGUMENT, argVar, ARGUMENT);
            body.addStatement("$L.name($S)", argVar, arg.getName());
            body.addStatement("$L.type($L)", argVar, buildInputTypeRef(arg.getType()));
            if (arg.getDescription() != null && !arg.getDescription().isEmpty()) {
                body.addStatement("$L.description($S)", argVar, arg.getDescription());
            }
            if (arg.hasSetDefaultValue()) {
                Object defaultValue = graphql.schema.GraphQLArgument.getArgumentDefaultValue(arg);
                body.addStatement("$L.defaultValueProgrammatic($L)", argVar, GraphQLValueEmitter.emit(defaultValue));
            }
            if (arg.isDeprecated()) {
                body.addStatement("$L.deprecate($S)", argVar, arg.getDeprecationReason());
            }
            AppliedDirectiveEmitter.emitApplications(body, argVar, arg, sink);
            body.addStatement("b.argument($L.build())", argVar);
        }
        AppliedDirectiveEmitter.emitApplications(body, "b", field, sink);
        body.addStatement("return b.build()");
        return MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(FIELD_DEF)
            .addCode(body.build())
            .build();
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
