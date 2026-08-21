package no.sikt.graphitron.rewrite.walker;

import graphql.schema.GraphQLFieldDefinition;

import java.lang.reflect.Type;

/**
 * Seam for mapping one declared field of an {@code @error} type onto the
 * {@code java.lang.reflect.Type} the accessor-coverage check resolves against on the handler's
 * source class.
 *
 * <p>The mapping needs the build's classified type registry and codegen classloader, both of which
 * live on {@code BuildContext}; rather than leak that package-private substrate into the walker
 * package, {@code FieldBuilder} supplies its {@code mapGraphQLTypeToReflectType} as this seam,
 * and walker unit tests supply their own. This keeps the
 * walker a thin layer over an explicit substrate rather than over {@code BuildContext}.
 *
 * <p>The seam takes the field rather than its SDL type because for one family of fields the two
 * answers differ: an {@code ID} field carrying {@code @nodeId(typeName:)} is read as the node
 * type's key column and encoded afterwards, so the accessor has to yield the key column's type and
 * not {@code ID}'s. Resolving which node type that is needs the same substrate this seam exists to
 * keep out of the walker, so it belongs behind it.
 */
@FunctionalInterface
public interface ReflectTypeResolver {
    Type resolve(GraphQLFieldDefinition sdlField);
}
