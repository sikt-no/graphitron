package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.diagnostics.ValidationFailedException;
import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static no.sikt.graphitron.model.Tables.DIAGNOSTIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That a connection default above the build's {@code <maxAllowedPageSize>} actually fails the
 * build, that an editor sees it, and that a default within the maximum does not, which are the
 * claims the tiers below this one cannot make: the model module's fact document proves the rule
 * finds the default and {@link no.sikt.graphitron.rewrite.derive.DefaultPageSizeDefectsTest} proves
 * the detection mints the violation, and neither says the violation reaches
 * {@link GraphQLRewriteGenerator#validate()}'s verdict.
 */
@PipelineTier
class DefaultPageSizeRejectionPipelineTest {

    private static final String FILM = """
        type Film @table(name: "film") { title: String }
        """;

    @Test
    void aDefaultFirstValueAboveTheMaximumFailsTheBuild(@TempDir Path tmp) {
        assertThatThrownBy(() -> validate(tmp, FILM + """
            type Query { films: [Film!]! @asConnection(defaultFirstValue: 50) }
            """, OptionalInt.of(10)))
            .isInstanceOf(ValidationFailedException.class)
            .satisfies(e -> assertThat(((ValidationFailedException) e).errors())
                .extracting(ValidationError::message)
                .anyMatch(m -> m.startsWith("Field 'Query.films': @asConnection(defaultFirstValue:"
                    + " 50) exceeds <maxAllowedPageSize> 10")));
    }

    @Test
    void anAuthoredFirstDefaultAboveTheMaximumFailsTheBuild(@TempDir Path tmp) {
        assertThatThrownBy(() -> validate(tmp, FILM + """
            type Query { films(first: Int = 50): [Film!]! @asConnection }
            """, OptionalInt.of(10)))
            .isInstanceOf(ValidationFailedException.class)
            .satisfies(e -> assertThat(((ValidationFailedException) e).errors())
                .extracting(ValidationError::message)
                .anyMatch(m -> m.startsWith("Field 'Query.films': the default of argument 'first'"
                    + " (50) exceeds <maxAllowedPageSize> 10")));
    }

    /** The negatives beside the positives, so the gate cannot pass because everything fails. */
    @Test
    void defaultsWithinTheMaximumBuild(@TempDir Path tmp) {
        assertThatCode(() -> validate(tmp, FILM + """
            type Query {
                atMaximum: [Film!]! @asConnection(defaultFirstValue: 10)
                bare: [Film!]! @asConnection
            }
            """, OptionalInt.of(10)))
            .doesNotThrowAnyException();
    }

    @Test
    void noMaximumBuilds(@TempDir Path tmp) {
        assertThatCode(() -> validate(tmp, FILM + """
            type Query { films: [Film!]! @asConnection(defaultFirstValue: 50) }
            """, OptionalInt.empty()))
            .doesNotThrowAnyException();
    }

    /**
     * What the editor reads: an actionable error at the written default, the remedy being a schema
     * or build change rather than a workaround.
     */
    @Test
    void theStoredDefectReachesTheDiagnosticSurface(@TempDir Path tmp) throws IOException {
        var ctx = context(tmp, FILM + """
            type Query { films: [Film!]! @asConnection(defaultFirstValue: 50) }
            """, OptionalInt.of(10));
        try (var store = GraphitronStore.captured(ctx)) {
            var rows = store.store().dsl().selectFrom(DIAGNOSTIC)
                .where(DIAGNOSTIC.GRAPH_NAME.eq(ctx.graphName()),
                    DIAGNOSTIC.LSP_CODE.eq("GRAPHITRON_DEFAULT_PAGE_SIZE_ABOVE_MAXIMUM"))
                .fetch();

            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(row.getSeverity()).isEqualTo("error");
                assertThat(row.getActionable()).isTrue();
                assertThat(row.getTypeName()).isEqualTo("Query");
                assertThat(row.getFieldName()).isEqualTo("films");
            });
        }
    }

    // ===== Helpers =====

    /** Runs the build-time validate pass over one SDL fixture, capture and detections included. */
    private static void validate(Path tmp, String sdl, OptionalInt max) throws IOException {
        var ctx = context(tmp, sdl, max);
        try (var store = GraphitronStore.captured(ctx)) {
            new GraphQLRewriteGenerator(ctx, store).validate();
        }
    }

    private static RunContext context(Path tmp, String sdl, OptionalInt max) throws IOException {
        Path schema = tmp.resolve("schema.graphqls");
        Files.writeString(schema, sdl);
        return new RunContext(
            List.of(new SchemaInput(SchemaSource.file(schema), Optional.empty(), Optional.empty())),
            tmp, "DefaultPageSizeRejectionPipelineTest",
            tmp,
            DEFAULT_OUTPUT_PACKAGE,
            DEFAULT_JOOQ_PACKAGE
        ).withMaxAllowedPageSize(max);
    }
}
