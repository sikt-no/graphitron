package no.sikt.graphitron.rewrite.generators.schema;

import graphql.language.ArrayValue;
import graphql.language.BooleanValue;
import graphql.language.EnumValue;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.NullValue;
import graphql.language.StringValue;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLScalarType;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.generators.util.SchemaDirectiveRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Emits {@code .withAppliedDirective(GraphQLAppliedDirective.newDirective()...)} blocks for
 * every survivor directive application on a {@link GraphQLDirectiveContainer}. Used by the
 * per-type emitters ({@link ObjectTypeGenerator}, {@link InputTypeGenerator},
 * {@link EnumTypeGenerator}) to preserve federation directives (and user-declared custom
 * directives like {@code @deprecated} when it reaches a schema element not handled via the
 * dedicated {@code .deprecate(...)} builder method) in the programmatic schema.
 *
 * <p>Filtering uses {@link SchemaDirectiveRegistry#isSurvivor(String)}: generator-only
 * directives (Graphitron's own build-time directives) are skipped; everything else survives.
 */
public final class AppliedDirectiveEmitter {

    private static final ClassName APPLIED_DIRECTIVE =
        ClassName.get("graphql.schema", "GraphQLAppliedDirective");
    private static final ClassName APPLIED_DIRECTIVE_ARG =
        ClassName.get("graphql.schema", "GraphQLAppliedDirectiveArgument");
    private static final ClassName SCALARS =
        ClassName.get("graphql", "Scalars");
    private static final ClassName CN_NON_NULL =
        ClassName.get("graphql.schema", "GraphQLNonNull");
    private static final ClassName CN_LIST =
        ClassName.get("graphql.schema", "GraphQLList");
    private static final ClassName CN_TYPE_REF =
        ClassName.get("graphql.schema", "GraphQLTypeReference");

    private static final ClassName CN_STRING_VAL  = ClassName.get("graphql.language", "StringValue");
    private static final ClassName CN_BOOL_VAL    = ClassName.get("graphql.language", "BooleanValue");
    private static final ClassName CN_INT_VAL     = ClassName.get("graphql.language", "IntValue");
    private static final ClassName CN_NULL_VAL    = ClassName.get("graphql.language", "NullValue");
    private static final ClassName CN_ENUM_VAL    = ClassName.get("graphql.language", "EnumValue");
    private static final ClassName CN_ARRAY_VAL   = ClassName.get("graphql.language", "ArrayValue");

    private AppliedDirectiveEmitter() {}

    /**
     * Returns a list of {@code .withAppliedDirective(...)} CodeBlocks, one per survivor applied
     * directive on {@code container}. Each block starts with {@code \n} so the caller can
     * concatenate onto an already-indented builder chain.
     *
     * <p>The list is empty when the container has no survivor applications; callers can emit
     * without checking.
     */
    public static List<CodeBlock> applicationsFor(GraphQLDirectiveContainer container) {
        var blocks = new ArrayList<CodeBlock>();
        for (var applied : container.getAppliedDirectives()) {
            if (!SchemaDirectiveRegistry.isSurvivor(applied.getName())) continue;
            blocks.add(CodeBlock.builder()
                .add("\n.withAppliedDirective(")
                .add(buildApplication(applied))
                .add(")")
                .build());
        }
        return blocks;
    }

    private static CodeBlock buildApplication(GraphQLAppliedDirective applied) {
        var block = CodeBlock.builder()
            .add("$T.newDirective()", APPLIED_DIRECTIVE)
            .add(".name($S)", applied.getName());
        for (var arg : applied.getArguments()) {
            block.add(".argument(")
                .add("$T.newArgument()", APPLIED_DIRECTIVE_ARG)
                .add(".name($S)", arg.getName())
                .add(".type(").add(emitInputType(arg.getType())).add(")")
                .add(".valueLiteral(")
                .add(emitAstLiteralValue(arg))
                .add(")")
                .add(".build())");
        }
        block.add(".build()");
        return block.build();
    }

    /**
     * Emits code that reconstructs the argument's value as a {@link graphql.language.Value}
     * AST node via {@code valueLiteral(...)}.
     *
     * <p>Federation-jvm reads directive argument values as AST literals (e.g. it casts the
     * {@code resolvable} argument value to {@link BooleanValue}). We must therefore preserve the
     * AST shape, not coerce to a Java primitive via {@code valueProgrammatic}.
     *
     * <p>When graphql-java has already coerced the value to a Java type (internal state, e.g.
     * Boolean for standard scalars), we reconstruct the equivalent AST node. For literal state
     * (custom scalars like {@code FieldSet} that graphql-java cannot coerce), the stored value
     * is already a {@link graphql.language.Value} and we emit it directly.
     */
    private static CodeBlock emitAstLiteralValue(GraphQLAppliedDirectiveArgument arg) {
        var ivs = arg.getArgumentValue();
        if (ivs.isLiteral()) {
            return emitAstNode((graphql.language.Value<?>) ivs.getValue());
        }
        return javaValueToAstNode(ivs.getValue());
    }

    private static CodeBlock emitAstNode(graphql.language.Value<?> value) {
        if (value instanceof StringValue sv)  return CodeBlock.of("$T.of($S)", CN_STRING_VAL, sv.getValue());
        if (value instanceof BooleanValue bv) return CodeBlock.of("$T.of($L)", CN_BOOL_VAL, bv.isValue());
        if (value instanceof IntValue iv)     return CodeBlock.of("$T.of($L)", CN_INT_VAL, iv.getValue().intValueExact());
        if (value instanceof NullValue)       return CodeBlock.of("$T.of()", CN_NULL_VAL);
        if (value instanceof EnumValue ev)    return CodeBlock.of("$T.of($S)", CN_ENUM_VAL, ev.getName());
        if (value instanceof FloatValue fv)   return CodeBlock.of("$T.of($S)", CN_STRING_VAL, fv.getValue().toPlainString());
        if (value instanceof ArrayValue av)   return emitArrayAstNode(av);
        return CodeBlock.of("$T.of($S)", CN_STRING_VAL, value.toString());
    }

    private static CodeBlock emitArrayAstNode(ArrayValue av) {
        var b = CodeBlock.builder().add("$T.newArrayValue()", CN_ARRAY_VAL);
        for (var v : av.getValues()) {
            b.add(".value(").add(emitAstNode(v)).add(")");
        }
        return b.add(".build()").build();
    }

    private static CodeBlock javaValueToAstNode(Object value) {
        if (value == null)              return CodeBlock.of("$T.of()", CN_NULL_VAL);
        if (value instanceof Boolean b) return CodeBlock.of("$T.of($L)", CN_BOOL_VAL, b);
        if (value instanceof String s)  return CodeBlock.of("$T.of($S)", CN_STRING_VAL, s);
        if (value instanceof Integer i) return CodeBlock.of("$T.of($L)", CN_INT_VAL, i);
        if (value instanceof Long l)    return CodeBlock.of("$T.of($L)", CN_INT_VAL, l.intValue());
        if (value instanceof List<?> list) {
            var b = CodeBlock.builder().add("$T.newArrayValue()", CN_ARRAY_VAL);
            for (var elem : list) b.add(".value(").add(javaValueToAstNode(elem)).add(")");
            return b.add(".build()").build();
        }
        return CodeBlock.of("$T.of($S)", CN_STRING_VAL, value.toString());
    }

    /**
     * Emits a {@link CodeBlock} that reconstructs a {@link GraphQLInputType} at runtime.
     *
     * <p>Standard scalars are emitted as {@code Scalars.GraphQL<Name>}. Custom scalars
     * (including federation-namespaced types like {@code federation__FieldSet}) are emitted as
     * {@code Scalars.GraphQLString} — they are String-like and may reference types only added
     * by the federation-jvm transform, which runs after the base schema is built. Other named
     * types (enums, input objects) use {@code GraphQLTypeReference.typeRef("<name>")} since
     * those types are present in the base schema.
     */
    static CodeBlock emitInputType(GraphQLInputType type) {
        if (type == null) return CodeBlock.of("$T.typeRef($S)", CN_TYPE_REF, "String");
        if (type instanceof GraphQLNonNull nn) {
            return CodeBlock.builder()
                .add("$T.nonNull(", CN_NON_NULL)
                .add(emitInputType((GraphQLInputType) nn.getWrappedType()))
                .add(")")
                .build();
        }
        if (type instanceof GraphQLList list) {
            return CodeBlock.builder()
                .add("$T.list(", CN_LIST)
                .add(emitInputType((GraphQLInputType) list.getWrappedType()))
                .add(")")
                .build();
        }
        if (type instanceof GraphQLScalarType scalar) {
            return switch (scalar.getName()) {
                case "String" -> CodeBlock.of("$T.GraphQLString", SCALARS);
                case "Boolean" -> CodeBlock.of("$T.GraphQLBoolean", SCALARS);
                case "Int" -> CodeBlock.of("$T.GraphQLInt", SCALARS);
                case "Float" -> CodeBlock.of("$T.GraphQLFloat", SCALARS);
                case "ID" -> CodeBlock.of("$T.GraphQLID", SCALARS);
                // Custom scalars in applied directives are typically String-like (FieldSet, URL,
                // JSON, etc.) and may be federation-namespaced types added only by the
                // federation-jvm transform. Using GraphQLString keeps the base schema buildable.
                default -> CodeBlock.of("$T.GraphQLString", SCALARS);
            };
        }
        if (type instanceof GraphQLNamedType named) {
            return CodeBlock.of("$T.typeRef($S)", CN_TYPE_REF, named.getName());
        }
        return CodeBlock.of("$T.typeRef($S)", CN_TYPE_REF, type.toString());
    }
}
