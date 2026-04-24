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
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ConnectionType;
import no.sikt.graphitron.rewrite.model.GraphitronType.EdgeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.PageInfoType;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;

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
     *
     * <p>Directive-driven {@code @asConnection} fields (bare-list return type with no pre-existing
     * Connection type in the schema) are detected via the classifier's {@link FieldWrapper.Connection}
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
            body.add("\n.field(").add(buildFieldDefinition(objectType.getName(), field, schema)).add(")");
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

    private static TypeSpec buildInterfaceTypeSpec(GraphQLInterfaceType interfaceType,
                                                   GraphitronSchema schema) {
        var body = CodeBlock.builder()
            .add("return $T.newInterface()", INTERFACE_TYPE)
            .indent()
            .add("\n.name($S)", interfaceType.getName());
        if (interfaceType.getDescription() != null && !interfaceType.getDescription().isEmpty()) {
            body.add("\n.description($S)", interfaceType.getDescription());
        }
        for (var field : interfaceType.getFieldDefinitions()) {
            body.add("\n.field(").add(buildFieldDefinition(interfaceType.getName(), field, schema)).add(")");
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

    private static CodeBlock buildFieldDefinition(String parentTypeName,
                                                   graphql.schema.GraphQLFieldDefinition field,
                                                   GraphitronSchema schema) {
        // Detect directive-driven @asConnection carriers. The classifier registered a
        // ConnectionType in schema.types() at Phase 1; here we just look up that entry via the
        // resolved connection name and rewrite the bare-list return type to reference it.
        // Structural carriers (SDL return type already names the Connection) need no rewrite.
        String synthConnName = null;
        int synthDefaultPageSize = FieldWrapper.DEFAULT_PAGE_SIZE;
        if (field.hasAppliedDirective("asConnection")) {
            String resolved = resolveConnectionName(parentTypeName, field);
            if (schema.type(resolved) instanceof GraphitronType.ConnectionType
                    && !resolved.equals(baseTypeName(field.getType()))) {
                synthConnName = resolved;
                synthDefaultPageSize = defaultPageSizeFor(field, schema, parentTypeName);
            }
        }

        var block = CodeBlock.builder()
            .add("$T.newFieldDefinition()", FIELD_DEF)
            .add(".name($S)", field.getName());

        if (synthConnName != null) {
            // Replace bare-list return type with the synthesised Connection type reference.
            boolean nonNull = field.getType() instanceof GraphQLNonNull;
            if (nonNull) {
                block.add(".type($T.nonNull($T.typeRef($S)))", NON_NULL, TYPE_REF, synthConnName);
            } else {
                block.add(".type($T.typeRef($S))", TYPE_REF, synthConnName);
            }
        } else {
            block.add(".type(").add(buildOutputTypeRef(field.getType())).add(")");
        }

        if (field.getDescription() != null && !field.getDescription().isEmpty()) {
            block.add(".description($S)", field.getDescription());
        }
        if (field.isDeprecated()) {
            block.add(".deprecate($S)", field.getDeprecationReason());
        }
        for (var arg : field.getArguments()) {
            block.add(".argument(").add(buildArgument(arg)).add(")");
        }
        if (synthConnName != null) {
            // Append standard pagination arguments after any existing arguments.
            block.add(".argument($T.newArgument().name($S).type($T.typeRef($S)).defaultValueProgrammatic($L).build())",
                ARGUMENT, "first", TYPE_REF, "Int", synthDefaultPageSize);
            block.add(".argument($T.newArgument().name($S).type($T.typeRef($S)).build())",
                ARGUMENT, "after", TYPE_REF, "String");
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

    /**
     * Resolves the Connection type name for a carrier field carrying {@code @asConnection}:
     * explicit {@code connectionName:} argument wins; otherwise derived as
     * {@code <ParentType><FieldName>Connection}.
     */
    private static String resolveConnectionName(String parentTypeName,
                                                 graphql.schema.GraphQLFieldDefinition field) {
        var applied = field.getAppliedDirective("asConnection");
        if (applied != null) {
            var arg = applied.getArgument("connectionName");
            if (arg != null && arg.getValue() instanceof String s && !s.isEmpty()) return s;
        }
        return parentTypeName + capitalize(field.getName()) + "Connection";
    }

    /**
     * Returns the per-site default page size: the classifier's {@link FieldWrapper.Connection}
     * if the field has one, else the fallback in {@link FieldWrapper#DEFAULT_PAGE_SIZE}.
     * The directive's own {@code defaultFirstValue:} argument is the authoritative source;
     * the classifier already read it into the wrapper. Fields that don't classify as
     * {@link SqlGeneratingField} (e.g. carriers on unclassified element types) fall back
     * to the default.
     */
    private static int defaultPageSizeFor(graphql.schema.GraphQLFieldDefinition field,
                                           GraphitronSchema schema, String parent) {
        GraphitronField f = schema.field(parent, field.getName());
        if (f instanceof SqlGeneratingField sgf
                && sgf.returnType().wrapper() instanceof FieldWrapper.Connection c) {
            return c.defaultPageSize();
        }
        var applied = field.getAppliedDirective("asConnection");
        if (applied != null) {
            var arg = applied.getArgument("defaultFirstValue");
            if (arg != null && arg.getValue() instanceof Integer i) return i;
            if (arg != null && arg.getValue() instanceof Number n) return n.intValue();
        }
        return FieldWrapper.DEFAULT_PAGE_SIZE;
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Unwraps non-null + list layers to return the innermost named type's name. */
    private static String baseTypeName(GraphQLType type) {
        GraphQLType cur = type;
        while (cur instanceof GraphQLNonNull nn) cur = nn.getWrappedType();
        while (cur instanceof GraphQLList list) cur = list.getWrappedType();
        while (cur instanceof GraphQLNonNull nn) cur = nn.getWrappedType();
        return cur instanceof GraphQLNamedType named ? named.getName() : null;
    }
}
