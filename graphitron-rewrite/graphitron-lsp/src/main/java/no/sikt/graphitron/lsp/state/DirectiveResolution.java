package no.sikt.graphitron.lsp.state;

import graphql.language.DirectiveDefinition;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.rewrite.catalog.DirectiveShape;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;

/**
 * Sealed result of looking up a directive by name across the bundled SDL
 * (graphitron's {@code directives.graphqls}) and the user-schema snapshot
 * shipped through the dev pipeline. Encodes bundled-shadows-snapshot
 * precedence: a name that appears in the bundled SDL always resolves to
 * {@link Bundled}, even if the user's schema redeclares it (the LSP's
 * overlay-driven validation is keyed to graphitron's shape).
 *
 * <p>The exhaustive-switch shape lets consumers respond to all three
 * outcomes (bundled / user-declared / unknown) without re-checking the
 * precedence inline; a new resolution arm in the future surfaces here as a
 * compile error on every consumer.
 */
public sealed interface DirectiveResolution {

    record Bundled(DirectiveDefinition def) implements DirectiveResolution {}

    record User(DirectiveShape shape) implements DirectiveResolution {}

    record Unknown() implements DirectiveResolution {}

    /**
     * Resolves {@code name} against the bundled SDL first; on miss, consults
     * the snapshot. Freshness ({@link LspSchemaSnapshot.Built.Current} vs.
     * {@link LspSchemaSnapshot.Built.Previous}) is orthogonal here — callers
     * that need the freshness gate inspect the snapshot variant directly.
     */
    static DirectiveResolution resolve(LspVocabulary vocabulary, LspSchemaSnapshot snapshot, String name) {
        var bundled = vocabulary.registry().getDirectiveDefinition(name);
        if (bundled.isPresent()) {
            return new Bundled(bundled.get());
        }
        return switch (snapshot) {
            case LspSchemaSnapshot.Unavailable ignored -> new Unknown();
            case LspSchemaSnapshot.Built b -> b.directive(name)
                .<DirectiveResolution>map(User::new)
                .orElseGet(Unknown::new);
        };
    }
}
