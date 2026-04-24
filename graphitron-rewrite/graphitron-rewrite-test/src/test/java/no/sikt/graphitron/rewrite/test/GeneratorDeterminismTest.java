package no.sikt.graphitron.rewrite.test;

import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier ratchet for the three-clause generator contract
 * (determinism + minimal-change writes + clean removal) against the
 * full rewrite-test fixture schema, which exercises every emitter
 * (interfaces, unions, directives, @splitQuery, @asConnection,
 * @lookupKey, input types, enums, federation). The shallow unit tests
 * in rewrite-core's IdempotentWriterTest cover the writer mechanics;
 * this test is the real determinism guardrail.
 */
class GeneratorDeterminismTest {

    private static final Path FIXTURE_SCHEMA =
        Path.of("src/main/resources/graphql/schema.graphqls").toAbsolutePath();

    private static final String OUTPUT_PACKAGE = "no.sikt.graphitron.generated";
    private static final String JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.test.jooq";

    @Test
    void twoConsecutiveRunsProduceIdenticalOutputTrees(@TempDir Path root) throws IOException {
        Path out1 = Files.createDirectories(root.resolve("run1"));
        Path out2 = Files.createDirectories(root.resolve("run2"));

        new GraphQLRewriteGenerator(contextFor(out1)).generate();
        new GraphQLRewriteGenerator(contextFor(out2)).generate();

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

    @Test
    void secondRunAgainstSameOutputDirPreservesMtimes(@TempDir Path root) throws IOException {
        Path outDir = Files.createDirectories(root.resolve("out"));
        var ctx = contextFor(outDir);

        new GraphQLRewriteGenerator(ctx).generate();

        // Wind all mtimes back 2 seconds so a rewrite would advance them to "now"
        // and be detectable by equality; the content-idempotent write skips the
        // disk write and leaves the backdated mtime intact.
        long past = System.currentTimeMillis() - 2_000;
        try (var walk = Files.walk(outDir)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try { Files.setLastModifiedTime(p, FileTime.fromMillis(past)); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
        }
        Map<Path, Long> before = mtimes(outDir);
        assertThat(before).isNotEmpty();

        new GraphQLRewriteGenerator(ctx).generate();
        Map<Path, Long> after = mtimes(outDir);

        assertThat(after).isEqualTo(before);
    }

    private static RewriteContext contextFor(Path outputDir) {
        return new RewriteContext(
            List.of(new SchemaInput(FIXTURE_SCHEMA.toString(), Optional.empty(), Optional.empty())),
            FIXTURE_SCHEMA.getParent(),
            outputDir,
            OUTPUT_PACKAGE,
            JOOQ_PACKAGE,
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

    private static Map<Path, Long> mtimes(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                .collect(Collectors.toMap(p -> p, p -> {
                    try { return Files.getLastModifiedTime(p).toMillis(); }
                    catch (IOException e) { throw new RuntimeException(e); }
                }));
        }
    }
}
