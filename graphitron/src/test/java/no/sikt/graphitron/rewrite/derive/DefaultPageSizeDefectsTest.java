package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.config.SessionStateConfig;
import no.sikt.graphitron.model.derive.DefaultPageSizeDefects;
import no.sikt.graphitron.model.diagnostics.RejectionKind;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.lint.LintConfig;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The build error for {@code DEFAULT_PAGE_SIZE_ABOVE_MAXIMUM}: real SDL captured into a fact store
 * under a {@code <maxAllowedPageSize>}, and the violations {@link DefaultPageSizeDefects} mints from
 * the stored entry defects. Which defaults are defects is the fact document
 * {@code default-page-size-above-maximum}; what stands here is the decode: the prose each one
 * carries, the location it points at, and that the maximum is what makes a default a defect.
 */
@PipelineTier
class DefaultPageSizeDefectsTest {

    private static final String GRAPH = CapturedStore.GRAPH;

    @TempDir
    Path tmp;

    private static final String FILM = """
        type Film @table(name: "film") { title: String }
        """;

    /**
     * The directive's default above the maximum, named with both numbers and both remedies, at the
     * defaultFirstValue argument the author wrote.
     */
    @Test
    void aDefaultFirstValueAboveTheMaximumIsRejected() {
        var violations = detect(FILM + """
            type Query {
                films: [Film!]!
                    @asConnection(defaultFirstValue: 50)
            }
            """, OptionalInt.of(10));

        assertThat(violations).extracting(ValidationError::message).containsExactly(
            "Field 'Query.films': @asConnection(defaultFirstValue: 50) exceeds <maxAllowedPageSize>"
            + " 10; no request can receive a page that large. Lower the default to at most 10, or"
            + " raise <maxAllowedPageSize>.");
        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
            assertThat(v.location().getLine())
                .as("the @asConnection application's line, one below the field's")
                .isEqualTo(4);
        });
    }

    /** A first argument the carrier declares itself, its default above the maximum. */
    @Test
    void anAuthoredFirstDefaultAboveTheMaximumIsRejected() {
        var violations = detect(FILM + """
            type Query { films(first: Int = 50): [Film!]! @asConnection }
            """, OptionalInt.of(10));

        assertThat(violations).extracting(ValidationError::message).containsExactly(
            "Field 'Query.films': the default of argument 'first' (50) exceeds <maxAllowedPageSize>"
            + " 10; no request can receive a page that large. Lower the default to at most 10, or"
            + " raise <maxAllowedPageSize>.");
    }

    /**
     * A default at the maximum, and the built-in default lowered to it, are no defects: the
     * maximum is a bound a default may reach.
     */
    @Test
    void aDefaultAtTheMaximumAndTheMintedDefaultAreAccepted() {
        assertThat(detect(FILM + """
            type Query {
                atMaximum: [Film!]! @asConnection(defaultFirstValue: 10)
                bare: [Film!]! @asConnection
            }
            """, OptionalInt.of(10))).isEmpty();
    }

    /** Without a maximum no default is too large. */
    @Test
    void noMaximumNoDefect() {
        assertThat(detect(FILM + """
            type Query { films: [Film!]! @asConnection(defaultFirstValue: 50) }
            """, OptionalInt.empty())).isEmpty();
    }

    /** Captures {@code sdl} under {@code max} and runs the build-error consumer's detection. */
    private List<ValidationError> detect(String sdl, OptionalInt max) {
        var config = new SubjectConfig(Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), LintConfig.empty(), SessionStateConfig.none(), max);
        try (var store = CapturedStore.ofCatalogWith(tmp, GRAPH, sdl, jooq(), List.of(), config)) {
            return DefaultPageSizeDefects.detect(store.dsl(), GRAPH).violations();
        }
    }

    private static CatalogInput jooq() {
        return CatalogInput.of(testContext());
    }
}
