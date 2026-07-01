package no.sikt.graphitron.rewrite.catalog;

import java.util.Optional;

/**
 * Projection of a {@link graphql.language.InputValueDefinition} (directive
 * arg or input-object field) for the LSP snapshot side-channel. See
 * {@link LspSchemaSnapshot}.
 */
public record InputValueShape(
    String name,
    TypeShape type,
    Optional<String> description
) {}
