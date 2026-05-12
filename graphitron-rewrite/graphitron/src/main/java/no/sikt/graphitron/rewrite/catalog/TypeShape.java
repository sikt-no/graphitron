package no.sikt.graphitron.rewrite.catalog;

/**
 * Sealed projection of a GraphQL type reference (the type half of an
 * {@link graphql.language.InputValueDefinition}). Sealed rather than a
 * rendered SDL string so phase-2 consumers (arg-completion,
 * arg-validation) discriminate list-vs-named and nullable-vs-non-null
 * without re-parsing.
 */
public sealed interface TypeShape permits TypeShape.Named, TypeShape.List {

    boolean nonNull();

    record Named(String typeName, boolean nonNull) implements TypeShape {}

    record List(TypeShape inner, boolean nonNull) implements TypeShape {}
}
