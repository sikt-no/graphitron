package no.sikt.graphitron.rewrite.generators.schema;

import graphql.GraphQLContext;
import graphql.execution.ValuesResolver;
import graphql.language.AstPrinter;
import graphql.language.Value;
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
import java.util.Locale;

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
    private static final ClassName PARSER =
        ClassName.get("graphql.parser", "Parser");

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
     * Re-renders the argument's value as an AST literal that the consumer-side schema builder
     * passes to {@code valueLiteral(...)}.
     *
     * <p>Federation-jvm reads directive argument values as AST literals (e.g. it casts the
     * {@code resolvable} argument value to {@link graphql.language.BooleanValue}). We must
     * therefore preserve the AST shape, not coerce to a Java primitive via
     * {@code valueProgrammatic}.
     *
     * <p>Delegates the AST shape decision to graphql-java's
     * {@link ValuesResolver#valueToLiteral}, which dispatches through each scalar's
     * {@code Coercing#valueToLiteral}. Custom scalars (federation-namespaced {@code FieldSet},
     * {@code Policy}, {@code Scope}, plus user-defined scalars), {@code Float},
     * {@code BigDecimal}, input objects, and internally-coerced enums all get the right shape
     * without per-type enumeration here. {@link AstPrinter#printAst} then renders the
     * {@link Value}, and the emitted code re-parses it at consumer class-init time via
     * {@link graphql.parser.Parser#parseValue}. Cost: one {@code parseValue} call per
     * applied-directive argument at class-init, negligible against schema build.
     */
    private static CodeBlock emitAstLiteralValue(GraphQLAppliedDirectiveArgument arg) {
        Value<?> ast = ValuesResolver.valueToLiteral(
            arg.getArgumentValue(),
            arg.getType(),
            GraphQLContext.getDefault(),
            Locale.getDefault());
        return CodeBlock.of("$T.parseValue($S)", PARSER, AstPrinter.printAst(ast));
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
