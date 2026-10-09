package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The generator classifies the schema capture assembled and reads no document itself, so the
 * schema it judges and the facts it reads come from one reading of the files.
 */
@PipelineTier
class CapturedSchemaPipelineTest {

    @Test
    void theGeneratorJudgesWhatCaptureRead(@TempDir Path tmp) throws IOException {
        Path schema = tmp.resolve("schema.graphqls");
        Files.writeString(schema, """
            type Film @table(name: "film") { title: String }
            type Query { films: [Film!]! }
            """);
        var ctx = new RunContext(
            List.of(SchemaInput.file(schema)),
            tmp, "CapturedSchemaPipelineTest",
            tmp.resolve("generated-sources"),
            TestConfiguration.DEFAULT_OUTPUT_PACKAGE,
            TestConfiguration.DEFAULT_JOOQ_PACKAGE);

        try (var captured = GraphitronStore.captured(ctx)) {
            Files.writeString(schema, "strayTokenHere");
            assertThatCode(() -> new GraphQLRewriteGenerator(ctx, captured).validate())
                .as("the file no longer parses, and a generator reading it would throw")
                .doesNotThrowAnyException();
        }
    }
}
