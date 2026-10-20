package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.inlay.InlayHints;
import no.sikt.graphitron.model.grammar.InferredDirectiveArgs;
import org.junit.jupiter.api.Test;

import java.util.Set;

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

    /**
     * Entries whose omitted argument no stored relation answers yet, so a renderer for them would
     * have to read a view. {@code @reference(path:)} waits on the path discovery implies being a
     * row of {@code graphitron_field_path}. A renderer landing for one fails the second check, so
     * the entry leaves this set with it.
     */
    private static final Set<String> AWAITING_A_STORED_FACT = Set.of("reference");

    @Test
    void everyInferredDirectiveEntryHasAPresentArmRenderer() {
        for (var entry : InferredDirectiveArgs.ENTRIES) {
            if (AWAITING_A_STORED_FACT.contains(entry.directiveName())) continue;
            assertThat(InlayHints.renderedInferredDirectives())
                .as("inferred-directive entry '%s' must have a present-arm renderer in InlayHints",
                    entry.directiveName())
                .contains(entry.directiveName());
        }
    }

    @Test
    void anEntryAwaitingAStoredFactHasNoRendererYet() {
        assertThat(InlayHints.renderedInferredDirectives()).doesNotContainAnyElementsOf(AWAITING_A_STORED_FACT);
    }
}
