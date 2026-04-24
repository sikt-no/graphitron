package no.sikt.graphitron.rewrite.generators.schema;

import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLDirectiveContainer;
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
                .add(".valueProgrammatic(")
                .add(GraphQLValueEmitter.emit(arg.getValue()))
                .add(")")
                .add(".build())");
        }
        block.add(".build()");
        return block.build();
    }
}
