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

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

/**
 * Writer-level unit tests: tamper-detection (content-idempotent write),
 * orphan sweep inside owned sub-packages, and scope preservation for
 * files outside owned sub-packages. Runs against a trivial two-type SDL
 * because the writer mechanics don't depend on emitter breadth. The
 * cross-cutting determinism and mtime-preservation ratchets live in
 * {@code graphitron-test/GeneratorDeterminismTest} against the
 * full fixture schema.
 */
@UnitTier
class IdempotentWriterTest {

    private static final String SCHEMA_SDL = """
        type Film @table(name: "film") { title: String }
        type Query { films: [Film] }
        """;

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

    @Test
    void tamperedFileIsOverwritten(@TempDir Path root) throws IOException {
        Path schemaFile = root.resolve("schema.graphqls");
        Files.writeString(schemaFile, SCHEMA_SDL, StandardCharsets.UTF_8);
        Path outDir = root.resolve("out");
        Files.createDirectories(outDir);

        var ctx = contextFor(schemaFile, outDir);
        new GraphQLRewriteGenerator(ctx).generate();

        Path anyFile;
        try (var walk = Files.walk(outDir)) {
            anyFile = walk.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        String original = Files.readString(anyFile, StandardCharsets.UTF_8);
        Files.writeString(anyFile, "// CORRUPTED\n" + original, StandardCharsets.UTF_8);

        new GraphQLRewriteGenerator(ctx).generate();

        assertThat(Files.readString(anyFile, StandardCharsets.UTF_8)).isEqualTo(original);
    }

    @Test
    void orphanFileInOwnedSubpackageIsDeleted(@TempDir Path root) throws IOException {
        Path schemaFile = root.resolve("schema.graphqls");
        Files.writeString(schemaFile, SCHEMA_SDL, StandardCharsets.UTF_8);
        Path outDir = root.resolve("out");
        Files.createDirectories(outDir);

        var ctx = contextFor(schemaFile, outDir);
        new GraphQLRewriteGenerator(ctx).generate();

        // Plant an orphan in "util" — always created, regardless of schema content
        Path utilDir = outDir;
        for (String seg : (DEFAULT_OUTPUT_PACKAGE + ".util").split("\\.")) {
            utilDir = utilDir.resolve(seg);
        }
        Path orphan = utilDir.resolve("StaleOrphan.java");
        Files.writeString(orphan, "// orphan", StandardCharsets.UTF_8);

        new GraphQLRewriteGenerator(ctx).generate();

        assertThat(orphan).doesNotExist();
    }

    @Test
    void sweepDoesNotDeleteFilesOutsideOwnedSubpackages(@TempDir Path root) throws IOException {
        Path schemaFile = root.resolve("schema.graphqls");
        Files.writeString(schemaFile, SCHEMA_SDL, StandardCharsets.UTF_8);
        Path outDir = root.resolve("out");
        Files.createDirectories(outDir);

        var ctx = contextFor(schemaFile, outDir);
        new GraphQLRewriteGenerator(ctx).generate();

        // Plant a file in a sub-package the generator does NOT own
        Path foreignDir = outDir;
        for (String seg : (DEFAULT_OUTPUT_PACKAGE + ".resolvers").split("\\.")) {
            foreignDir = foreignDir.resolve(seg);
        }
        Files.createDirectories(foreignDir);
        Path foreign = foreignDir.resolve("LegacyResolver.java");
        Files.writeString(foreign, "// not ours", StandardCharsets.UTF_8);

        new GraphQLRewriteGenerator(ctx).generate();

        assertThat(foreign).exists();
    }
}
