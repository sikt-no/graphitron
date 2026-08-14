package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLFieldDefinition;

import java.util.Optional;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_CLASS_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_METHOD;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_SOURCE_ROW;

/**
 * A {@code @sourceRow} application decoded off the SDL field, carried inward so the batched child
 * {@code @service} route can read the author's key-producer declaration without the SDL node
 * travelling with it.
 *
 * <p>The decode happens where the field definition is still in hand ({@code FieldBuilder}'s
 * child-classify site) and the reduced fact rides on
 * {@link ServiceDirectiveResolver.ParentContext.RecordParent} from there. Every other input the
 * classify chain takes is a reduced fact of this kind; pushing the field definition three frames
 * deeper would move the decode past the boundary that already owns it.
 *
 * <p>{@code className} and {@code methodName} are the arguments as written, and are {@code null}
 * when the argument is absent. The SDL declares both non-null, so a null here means a schema the
 * parser did not have to reject; the consumer rejects it by name rather than dereferencing it.
 */
record SourceRowDeclaration(String fieldName, String className, String methodName) {

    /**
     * Reads the directive off {@code fieldDef}, or returns {@code null} when the field does not
     * carry one.
     */
    static SourceRowDeclaration read(GraphQLFieldDefinition fieldDef) {
        if (!fieldDef.hasAppliedDirective(DIR_SOURCE_ROW)) return null;
        var dir = fieldDef.getAppliedDirective(DIR_SOURCE_ROW);
        return new SourceRowDeclaration(fieldDef.getName(), argOf(dir, ARG_CLASS_NAME), argOf(dir, ARG_METHOD));
    }

    private static String argOf(graphql.schema.GraphQLAppliedDirective dir, String argName) {
        return Optional.ofNullable(dir.getArgument(argName))
            .map(a -> a.getValue())
            .map(Object::toString)
            .orElse(null);
    }
}
