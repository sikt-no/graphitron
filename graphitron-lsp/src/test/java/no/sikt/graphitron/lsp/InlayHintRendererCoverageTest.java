package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.inlay.InlayHints;
import no.sikt.graphitron.rewrite.catalog.InferredDirectiveArgs;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage oracle for the inferred-directive inlay arm. The
 * present-arm renderers in {@code InlayHints} are a registry keyed by directive
 * name ({@code InlayHints.renderedInferredDirectives()}), replacing the old
 * {@code switch(directiveName)} whose {@code default} silently dropped any
 * {@link InferredDirectiveArgs.Entry} that lacked a renderer. This test fails
 * the build the moment a new inference entry lands in
 * {@link InferredDirectiveArgs#ENTRIES} without a matching LSP renderer, so the
 * silent no-op cannot reappear.
 */
class InlayHintRendererCoverageTest {

    @Test
    void everyInferredDirectiveEntryHasAPresentArmRenderer() {
        for (var entry : InferredDirectiveArgs.ENTRIES) {
            assertThat(InlayHints.renderedInferredDirectives())
                .as("inferred-directive entry '%s' must have a present-arm renderer in InlayHints",
                    entry.directiveName())
                .contains(entry.directiveName());
        }
    }
}
