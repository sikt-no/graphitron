package no.sikt.graphitron.rewrite.catalog;

import java.util.List;
import java.util.Optional;

/**
 * Projection of a {@link graphql.language.DirectiveDefinition} for the LSP
 * snapshot side-channel. Carries the arg surface and description prose
 * phase-2 hover and arg-validation consumers will need; phase 1 uses only
 * the name. See {@link LspSchemaSnapshot}.
 */
public record DirectiveShape(
    String name,
    List<InputValueShape> args,
    Optional<String> description
) {
    public DirectiveShape {
        args = List.copyOf(args);
    }
}
