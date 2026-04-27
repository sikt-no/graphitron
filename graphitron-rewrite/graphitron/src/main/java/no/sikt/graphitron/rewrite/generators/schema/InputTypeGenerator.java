package no.sikt.graphitron.rewrite.generators.schema;

import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.GraphitronType;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Emits one {@code <TypeName>Type} class per GraphQL input object type into
 * {@code <outputPackage>.rewrite.schema}. Each class exposes a single
 * {@code public static GraphQLInputObjectType type()} method that rebuilds the input object
 * as a programmatic graphql-java type at runtime.
 *
 * <p>Second of the Commit B leaf-type emitters. Introspection (names starting with {@code __})
 * and federation-injected (names starting with {@code _}) types are skipped. Directive-
 * declaration input-object types (those declared directly in {@code directives.graphqls} such
 * as {@code ErrorHandler}, {@code ReferenceElement}, {@code ExternalCodeReference}) are also
 * skipped: they exist only to give Graphitron's own build-time directives argument shapes and
 * must not reach the user-facing schema. The set is derived from
 * {@link InputDirectiveInputTypes#NAMES}.
 *
 * <p>Cross-type references are emitted as {@code GraphQLTypeReference.typeRef(name)} so the
 * generator can emit types in any order and trust graphql-java's {@code .build()} call to
 * resolve each reference against the registered scalars and types.
 *
 * <p>Descriptions and deprecation reasons are preserved. Default values and directive
 * applications are not yet translated; these are follow-up steps within Commit B.
 */
public final class InputTypeGenerator {

    private static final ClassName INPUT_TYPE      = ClassName.get("graphql.schema", "GraphQLInputObjectType");
    private static final ClassName INPUT_FIELD     = ClassName.get("graphql.schema", "GraphQLInputObjectField");
    private static final ClassName TYPE_REF        = ClassName.get("graphql.schema", "GraphQLTypeReference");
    private static final ClassName NON_NULL        = ClassName.get("graphql.schema", "GraphQLNonNull");
    private static final ClassName LIST            = ClassName.get("graphql.schema", "GraphQLList");

    private InputTypeGenerator() {}

    public static List<TypeSpec> generate(GraphitronSchema schema) {
        var result = new ArrayList<TypeSpec>();
        for (var entry : schema.types().entrySet()) {
            if (entry.getKey().startsWith("_")) continue;
            GraphQLInputObjectType inputType = switch (entry.getValue()) {
                case GraphitronType.InputType it -> it.schemaType();
                case GraphitronType.TableInputType tit -> tit.schemaType();
                default -> null;
            };
            if (inputType != null) {
                result.add(buildInputTypeSpec(inputType));
            }
        }
        result.sort(Comparator.comparing(TypeSpec::name));
        return result;
    }

    private static TypeSpec buildInputTypeSpec(GraphQLInputObjectType inputType) {
        var body = CodeBlock.builder()
            .add("return $T.newInputObject()", INPUT_TYPE)
            .indent()
            .add("\n.name($S)", inputType.getName());
        if (inputType.getDescription() != null && !inputType.getDescription().isEmpty()) {
            body.add("\n.description($S)", inputType.getDescription());
        }
        for (var field : inputType.getFieldDefinitions()) {
            body.add("\n.field(").add(buildFieldDefinition(field)).add(")");
        }
        for (var applied : AppliedDirectiveEmitter.applicationsFor(inputType)) {
            body.add(applied);
        }
        body.add("\n.build();\n").unindent();

        var typeMethod = MethodSpec.methodBuilder("type")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(INPUT_TYPE)
            .addCode(body.build())
            .build();

        return TypeSpec.classBuilder(inputType.getName() + "Type")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(typeMethod)
            .build();
    }

    private static CodeBlock buildFieldDefinition(graphql.schema.GraphQLInputObjectField field) {
        var block = CodeBlock.builder()
            .add("$T.newInputObjectField()", INPUT_FIELD)
            .add(".name($S)", field.getName())
            .add(".type(").add(buildInputTypeRef(field.getType())).add(")");
        if (field.getDescription() != null && !field.getDescription().isEmpty()) {
            block.add(".description($S)", field.getDescription());
        }
        if (field.hasSetDefaultValue()) {
            Object defaultValue = graphql.schema.GraphQLInputObjectField.getInputFieldDefaultValue(field);
            block.add(".defaultValueProgrammatic(").add(GraphQLValueEmitter.emit(defaultValue)).add(")");
        }
        if (field.isDeprecated()) {
            block.add(".deprecate($S)", field.getDeprecationReason());
        }
        for (var applied : AppliedDirectiveEmitter.applicationsFor(field)) {
            block.add(applied);
        }
        block.add(".build()");
        return block.build();
    }

    /**
     * Translates a graphql-java {@link GraphQLInputType} into a CodeBlock that reconstructs it
     * via {@code typeRef}/{@code nonNull}/{@code list}. Non-null and list wrappers nest; named
     * types always emit as {@code GraphQLTypeReference.typeRef("Name")} so the emitter can run
     * without a topological sort. The generated code resolves the reference at
     * {@code .build()} time.
     */
    static CodeBlock buildInputTypeRef(GraphQLInputType type) {
        if (type instanceof GraphQLNonNull nn) {
            return CodeBlock.builder()
                .add("$T.nonNull(", NON_NULL)
                .add(buildInputTypeRef((GraphQLInputType) nn.getWrappedType()))
                .add(")")
                .build();
        }
        if (type instanceof GraphQLList list) {
            return CodeBlock.builder()
                .add("$T.list(", LIST)
                .add(buildInputTypeRef((GraphQLInputType) list.getWrappedType()))
                .add(")")
                .build();
        }
        var name = ((GraphQLNamedType) type).getName();
        return CodeBlock.of("$T.typeRef($S)", TYPE_REF, name);
    }
}
