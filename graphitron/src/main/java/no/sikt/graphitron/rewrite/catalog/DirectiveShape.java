package no.sikt.graphitron.rewrite.catalog;

import java.util.List;
import java.util.Optional;

/**
 * Projection of a {@link graphql.language.DirectiveDefinition} for the LSP
 * snapshot side-channel. Carries the arg surface and description prose
 * phase-2 hover and arg-validation consumers will need; phase 1 uses only
 * the name. See {@link LspSchemaSnapshot}.
 *
 * <p>{@code locations} carries the directive's applicable locations as
 * renderable strings (e.g. {@code "OBJECT"}, {@code "FIELD_DEFINITION"}),
 * projected from {@code DirectiveDefinition.getDirectiveLocations()} at the
 * one production construction site ({@code CatalogBuilder.buildSnapshot}).
 * Added for R368's {@code directives} MCP resource, which shows applicable
 * locations uniformly for both bundled and user-declared directives; the
 * user-declared half reaches the resource only through this projection, which
 * had previously thrown the locations away. The existing hover / diagnostic /
 * arg-completion readers (which read {@code name} / {@code args} /
 * {@code description}) are untouched.
 */
public record DirectiveShape(
    String name,
    List<InputValueShape> args,
    Optional<String> description,
    List<String> locations
) {
    public DirectiveShape {
        args = List.copyOf(args);
        locations = List.copyOf(locations);
    }

    /**
     * Back-compat constructor defaulting {@code locations} to empty. Keeps the
     * LSP / snapshot test fixtures and any caller that does not supply
 * applicable locations compiling unchanged.
     */
    public DirectiveShape(String name, List<InputValueShape> args, Optional<String> description) {
        this(name, args, description, List.of());
    }
}
