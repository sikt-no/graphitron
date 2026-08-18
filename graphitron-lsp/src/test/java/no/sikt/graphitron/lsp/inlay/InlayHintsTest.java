package no.sikt.graphitron.lsp.inlay;

import no.sikt.graphitron.lsp.state.InlayHintConfig;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code compute} answers before any arm gets a say: nothing at all with every toggle off, and
 * nothing at all with no store, whatever the toggles say. Both are properties of the provider rather
 * than of any one arm, which is why they are not in the per-arm classes
 * ({@code ClassificationHintsTest}, {@code SeparateFetchHintsTest}, and
 * {@code InferredTableHintsTest} / {@code InferredFieldHintsTest} /
 * {@code InferredReferenceHintsTest} for the three inferred-directive passes).
 *
 * <p>This class used to hold the one renderer that read the schema snapshot, and with it the cases
 * asserting that the store-backed arms did not. Both are gone: the provider takes no snapshot, so
 * there is no longer a source for such a case to distinguish, and the signature says what the
 * assertions used to.
 */
class InlayHintsTest {

    @Test
    void noHintsWhenAllConfigOff() {
        var file = file("""
            type Film @table(name: "film") {
                title: String @field
            }
            """);
        assertThat(InlayHints.compute(
            InlayHintConfig.defaults(), file, Optional.empty(), fullRange())).isEmpty();
    }

    @Test
    void noHintsWithoutAStore() {
        // Every arm reads the store now, so a session with none is silent across the whole provider
        // rather than per renderer, and that is one policy instead of a branch per arm.
        var file = file("""
            type Film @table(name: "film") {
                title: String @field
                languageName: String @reference
            }
            """);
        assertThat(InlayHints.compute(
            new InlayHintConfig(true, true, true, true), file, Optional.empty(), fullRange()))
            .isEmpty();
    }

    // ===== Test helpers =====

    private static FileSnapshot file(String source) {
        return WorkspaceFileTestSupport.snapshot(source);
    }

    private static Range fullRange() {
        return new Range(new Position(0, 0), new Position(10_000, 0));
    }
}
