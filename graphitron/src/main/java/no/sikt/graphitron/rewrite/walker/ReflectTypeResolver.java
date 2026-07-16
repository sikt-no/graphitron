package no.sikt.graphitron.rewrite.walker;

import graphql.schema.GraphQLType;

import java.lang.reflect.Type;

/**
 * Seam for mapping an {@code @error} type's SDL field type onto the {@code java.lang.reflect.Type}
 * the accessor-coverage check resolves against on the handler's source class.
 *
 * <p>The mapping needs the build's classified type registry and codegen classloader, both of which
 * live on {@code BuildContext}; rather than leak that package-private substrate into the walker
 * package, {@code FieldBuilder} supplies its {@code mapGraphQLTypeToReflectType} as this seam at
 * the in-scope flip (slice-1 commit 3), and walker unit tests supply their own. This keeps the
 * walker a thin layer over an explicit substrate rather than over {@code BuildContext}.
 */
@FunctionalInterface
public interface ReflectTypeResolver {
    Type resolve(GraphQLType sdlFieldType);
}
