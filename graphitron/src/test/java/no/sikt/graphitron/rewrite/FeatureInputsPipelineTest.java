package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.diagnostics.ValidationFailedException;
import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.schema.SchemaAssembly;
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

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end coverage of {@code <schemaInput feature>}: which elements of a run's files take the
 * configured {@code @feature}, read off the post-synthesis schema a capture builds, and what a run
 * whose schema cannot take it does. The rule has a test of its own in the model tier; this pins the
 * file split and the order of the run, which only a run over real files and the generator's own
 * validate pass can show.
 */
@PipelineTier
class FeatureInputsPipelineTest {

    private static final String DEFINITION = "directive @feature(flags: [String!]!) on FIELD_DEFINITION"
        + " | ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION | ENUM_VALUE\n";

    private static final String FLAGGED = """
        type Query { films: [Film!]! @asConnection }
        type Film @table(name: "film") { title: String }
        """;

    @Test
    void onlyTheFlaggedFilesElementsTakeTheFeature(@TempDir Path tmp) throws IOException {
        Path flagged = write(tmp, "flagged.graphqls", DEFINITION + FLAGGED);
        Path plain = write(tmp, "plain.graphqls", """
            extend type Film { length: Int }
            """);
        GraphQLSchema emitted;
        try (var captured = GraphitronStore.captured(context(tmp, List.of(
                featured(flagged, "beta"), SchemaInput.file(plain))))) {
            emitted = ((SchemaAssembly.Assembled) captured.schema().synthesised().orElseThrow())
                .schema();
        }

        var query = (GraphQLObjectType) emitted.getType("Query");
        assertThat(flags(query.getFieldDefinition("films"))).containsExactly(List.of("beta"));
        assertThat(flags(query.getFieldDefinition("films").getArgument("first")))
            .as("a minted pagination argument").isEmpty();
        var film = (GraphQLObjectType) emitted.getType("Film");
        assertThat(flags(film.getFieldDefinition("title"))).containsExactly(List.of("beta"));
        assertThat(flags(film.getFieldDefinition("length")))
            .as("added by an extension in a file no feature reaches").isEmpty();
        var connection = (GraphQLObjectType) emitted.getType("QueryFilmsConnection");
        connection.getFieldDefinitions().forEach(field ->
            assertThat(flags(field)).as("a field the carrier minted").isEmpty());
    }

    /**
     * A schema with no {@code @feature} definition withholds every landing, so the emitted schema
     * still assembles, and the build fails on the stored defect: one error for the flag, carrying
     * the definition to paste, rather than the generator's own failure to assemble what it emits.
     */
    @Test
    void anUndefinedFeatureFailsTheBuildWithTheLineToPaste(@TempDir Path tmp) throws IOException {
        Path flagged = write(tmp, "flagged.graphqls", FLAGGED);
        var ctx = context(tmp, List.of(featured(flagged, "beta")));
        try (var captured = GraphitronStore.captured(ctx)) {
            assertThat(captured.schema().synthesised().orElseThrow())
                .isInstanceOf(SchemaAssembly.Assembled.class);
            assertThatThrownBy(() -> new GraphQLRewriteGenerator(ctx, captured).validate())
                .isInstanceOf(ValidationFailedException.class)
                .satisfies(e -> assertThat(((ValidationFailedException) e).errors())
                    .extracting(ValidationError::message)
                    .filteredOn(m -> m.contains("@feature"))
                    .singleElement()
                    .satisfies(m -> assertThat(m)
                        .contains("directive @feature(flags: [String!]!) on FIELD_DEFINITION"
                            + " | ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION | ENUM_VALUE")
                        .contains("feature beta")
                        .contains("and 1 more element in 1 file")));
        }
    }

    private static SchemaInput featured(Path file, String feature) {
        return new SchemaInput(SchemaSource.file(file), Optional.empty(), Optional.empty(),
            Optional.of(feature));
    }

    private static Path write(Path dir, String name, String sdl) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, sdl);
        return file;
    }

    private static RunContext context(Path tmp, List<SchemaInput> inputs) {
        return new RunContext(inputs, tmp, "FeatureInputsPipelineTest", tmp,
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> flags(GraphQLDirectiveContainer element) {
        return element.getAppliedDirectives("feature").stream()
            .map(d -> (List<String>) d.getArgument("flags").getValue())
            .toList();
    }
}
