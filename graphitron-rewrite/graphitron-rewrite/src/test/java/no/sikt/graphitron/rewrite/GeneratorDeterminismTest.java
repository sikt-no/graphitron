package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Determinism ratchet: two consecutive generator runs over identical inputs must
 * produce byte-identical output trees. Catches any emitter that introduces
 * non-deterministic ordering (HashMap iteration, clock reads, etc.) — a
 * prerequisite for content-idempotent writes to actually suppress mtime churn.
 */
class GeneratorDeterminismTest {

    private static final String SCHEMA_SDL = """
        type Film @table(name: "film") { title: String }
        type Query { films: [Film] }
        """;

    @Test
    void twoConsecutiveRunsProduceIdenticalOutputTrees(@TempDir Path root) throws IOException {
        Path schemaFile = root.resolve("schema.graphqls");
        Files.writeString(schemaFile, SCHEMA_SDL, StandardCharsets.UTF_8);

        Path out1 = root.resolve("run1");
        Path out2 = root.resolve("run2");
        Files.createDirectories(out1);
        Files.createDirectories(out2);

        new GraphQLRewriteGenerator(contextFor(schemaFile, out1)).generate();
        new GraphQLRewriteGenerator(contextFor(schemaFile, out2)).generate();

        Map<String, String> tree1 = readAll(out1);
        Map<String, String> tree2 = readAll(out2);

        assertThat(tree1).isNotEmpty();
        assertThat(tree2.keySet()).isEqualTo(tree1.keySet());
        for (var entry : tree1.entrySet()) {
            assertThat(tree2.get(entry.getKey()))
                .as("File %s differs between runs", entry.getKey())
                .isEqualTo(entry.getValue());
        }
    }

    private static RewriteContext contextFor(Path schemaFile, Path outputDir) {
        return new RewriteContext(
            List.of(new SchemaInput(schemaFile.toString(), Optional.empty(), Optional.empty())),
            schemaFile.getParent(),
            outputDir,
            DEFAULT_OUTPUT_PACKAGE,
            DEFAULT_JOOQ_PACKAGE,
            Map.of()
        );
    }

    private static Map<String, String> readAll(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                .collect(Collectors.toMap(
                    p -> root.relativize(p).toString(),
                    p -> {
                        try { return Files.readString(p, StandardCharsets.UTF_8); }
                        catch (IOException e) { throw new RuntimeException(e); }
                    }
                ));
        }
    }
}
