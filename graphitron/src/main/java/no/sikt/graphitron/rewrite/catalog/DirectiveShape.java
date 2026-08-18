package no.sikt.graphitron.rewrite.catalog;

import java.util.List;
import java.util.Optional;

/**
 * Projection of a {@link graphql.language.DirectiveDefinition} for the LSP
 * snapshot side-channel. Carries the arg surface and description prose, which
 * is what the hover, diagnostic and arg-completion readers ask of it. See
 * {@link LspSchemaSnapshot}.
 *
 * <p>Applicable locations are not projected. They were, for one reader that has
 * since stopped reading the snapshot and reads
 * {@code graphql_directive_location} instead; a permitted-location set is a
 * captured fact about the definition, and this projection is the LSP's own
 * working shape rather than a second place to hold one.
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
