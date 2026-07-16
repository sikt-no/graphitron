package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The drift-detection guard: builds the production
 * {@link LspVocabulary} against the bundled {@code directives.graphqls}
 * on the runtime classpath and asserts every overlay coordinate resolves.
 *
 * <p>This is the test that fails when the SDL drifts away from the
 * canonical overlay. It complements
 * {@code LspVocabularyTest.structuralInvariant_*} by running against the
 * real SDL rather than synthetic fixtures: a coordinate the overlay
 * names but the SDL no longer declares (directive drift) is a hard
 * startup failure here, before any IDE session runs.
 *
 * <p>The constructor of {@link LspVocabulary} performs the structural
 * resolution on every overlay key; if a coordinate fails to resolve,
 * the constructor throws and {@link LspVocabulary#load()} propagates.
 * "Build the vocabulary" is the assertion.
 */
class DriftDetectionTest {

    @Test
    void productionOverlayResolvesAgainstBundledDirectivesSdl() {
        assertThatCode(LspVocabulary::load).doesNotThrowAnyException();
    }

    @Test
    void productionOverlayContainsExpectedCanonicalEntries() {
        var vocab = LspVocabulary.load();

        // Spot-check a representative subset of the canonical overlay
        // (the full table lives in the spec; this guards against silent
        // shrinkage of the binding set).
        assertThat(vocab.overlay()).containsKeys(
            new no.sikt.graphitron.lsp.parsing.SchemaCoordinate.InputField(
                "ExternalCodeReference", "className"),
            new no.sikt.graphitron.lsp.parsing.SchemaCoordinate.InputField(
                "ExternalCodeReference", "method"),
            new no.sikt.graphitron.lsp.parsing.SchemaCoordinate.DirectiveArg(
                "sourceRow", "className"),
            new no.sikt.graphitron.lsp.parsing.SchemaCoordinate.DirectiveArg(
                "table", "name"),
            new no.sikt.graphitron.lsp.parsing.SchemaCoordinate.DirectiveArg(
                "field", "name")
        );
    }

    @Test
    void fieldSortNameResolvesAndBindsToColumnBehavior() {
        // The @defaultOrder(fields: [{name: ...}]) coordinate. Resolving against the bundled
        // directives.graphqls (input FieldSort { name: String! ... }) is guarded by the startup
        // invariant in productionOverlayResolvesAgainstBundledDirectivesSdl; this pins the binding.
        var vocab = LspVocabulary.load();
        var coord = new no.sikt.graphitron.lsp.parsing.SchemaCoordinate.InputField("FieldSort", "name");
        assertThat(vocab.overlay()).containsKey(coord);
        assertThat(vocab.behaviorAt(coord))
            .containsInstanceOf(no.sikt.graphitron.lsp.parsing.Behavior.CatalogColumnBinding.class);
    }
}
