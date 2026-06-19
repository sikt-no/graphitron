package no.sikt.graphitron.rewrite.catalog;

import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline coverage for the {@link CatalogBuilder} ↔ {@link SourceWalker}
 * join: per-line refinement and Javadoc lift on the jOOQ half (columns) and
 * the service half (external references + methods), plus the
 * source-roots-absent fallback to file-level / {@code UNKNOWN} positions.
 */
@PipelineTier
class CatalogBuilderSourceTest {

    // ---- jOOQ half: column refinement + Javadoc from a synthetic source root ----

    @Test
    void columnPositionRefinedAndJavadocLiftedFromSourceRoot(@TempDir Path srcRoot) throws IOException {
        // A stand-in for the generated jOOQ table source whose FQN matches the
        // real compiled Film class the fixture catalog resolves.
        writeJava(srcRoot, "no/sikt/graphitron/rewrite/test/jooq/tables/Film.java", """
            package no.sikt.graphitron.rewrite.test.jooq.tables;
            public class Film {
                /** The film identifier column. */
                public final Object FILM_ID = null;
            }
            """);

        var data = CatalogBuilder.build(
            new JooqCatalog(DEFAULT_JOOQ_PACKAGE),
            TestSchemaHelper.buildBundle("type Query { x: Int }").assembled(),
            contextWith(srcRoot, List.of(), List.of(srcRoot)));

        var filmId = data.getTable("film").orElseThrow().columns().stream()
            .filter(c -> c.name().equals("FILM_ID")).findFirst().orElseThrow();
        assertThat(filmId.definition().line()).isGreaterThan(0);
        assertThat(filmId.definition().uri()).endsWith("Film.java");
        assertThat(filmId.description()).isEqualTo("The film identifier column.");
    }

    @Test
    void columnPositionStaysFileLevelWhenNoSourceRoots() {
        var data = CatalogBuilder.build(
            new JooqCatalog(DEFAULT_JOOQ_PACKAGE),
            TestSchemaHelper.buildBundle("type Query { x: Int }").assembled(),
            no.sikt.graphitron.common.configuration.TestConfiguration.testContext());

        var filmId = data.getTable("film").orElseThrow().columns().stream()
            .filter(c -> c.name().equals("FILM_ID")).findFirst().orElseThrow();
        // No walk: the per-line refinement never runs, so the column carries the
        // table's file-level / UNKNOWN location and no source-derived Javadoc.
        assertThat(filmId.definition().line()).isZero();
        assertThat(filmId.description()).isEmpty();
    }

    // ---- service half: external-reference + method location/Javadoc ----

    @Test
    void externalReferenceAndMethodGetLocationAndJavadoc(
        @TempDir Path srcRoot, @TempDir Path classesRoot
    ) throws IOException {
        Path source = writeJava(srcRoot, "com/example/PriceService.java", """
            package com.example;
            /** Computes prices. */
            public class PriceService {
                /** Looks up a price. */
                public Object price(Object table) { return null; }
            }
            """);
        compile(source, classesRoot);

        var data = CatalogBuilder.build(
            new JooqCatalog(DEFAULT_JOOQ_PACKAGE),
            TestSchemaHelper.buildBundle("type Query { x: Int }").assembled(),
            contextWith(srcRoot, List.of(classesRoot), List.of(srcRoot)));

        var ref = data.externalReferences().stream()
            .filter(r -> r.className().equals("com.example.PriceService"))
            .findFirst().orElseThrow();
        assertThat(ref.definition().line()).isGreaterThan(0);
        assertThat(ref.definition().uri()).endsWith("PriceService.java");
        assertThat(ref.description()).isEqualTo("Computes prices.");

        var method = ref.methods().stream()
            .filter(m -> m.name().equals("price")).findFirst().orElseThrow();
        assertThat(method.definition().line()).isGreaterThan(0);
        assertThat(method.description()).isEqualTo("Looks up a price.");
    }

    @Test
    void externalReferenceLocationUnknownWhenNoSourceRoots(@TempDir Path classesRoot) throws IOException {
        // Compile from a throwaway source dir but do NOT pass it as a source
        // root: the bytecode scan still finds the class, but with no walk its
        // location stays UNKNOWN.
        Path srcRoot = Files.createTempDirectory("svc-src");
        Path source = writeJava(srcRoot, "com/example/NoSourceService.java", """
            package com.example;
            public class NoSourceService {
                public Object run() { return null; }
            }
            """);
        compile(source, classesRoot);

        var data = CatalogBuilder.build(
            new JooqCatalog(DEFAULT_JOOQ_PACKAGE),
            TestSchemaHelper.buildBundle("type Query { x: Int }").assembled(),
            contextWith(classesRoot, List.of(classesRoot), List.of()));

        var ref = data.externalReferences().stream()
            .filter(r -> r.className().equals("com.example.NoSourceService"))
            .findFirst().orElseThrow();
        assertThat(ref.definition()).isEqualTo(CompletionData.SourceLocation.UNKNOWN);
    }

    private static RewriteContext contextWith(
        Path basedir, List<Path> classpathRoots, List<Path> compileSourceRoots
    ) {
        return new RewriteContext(
            List.of(),
            RewriteContext.DEFAULT_SCHEMA_FILE_EXTENSIONS,
            basedir,
            basedir.resolve("out"),
            basedir.resolve("res"),
            "fake.output",
            DEFAULT_JOOQ_PACKAGE,
            Map.of(),
            classpathRoots,
            Thread.currentThread().getContextClassLoader(),
            compileSourceRoots
        );
    }

    private static Path writeJava(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static void compile(Path source, Path classesRoot) throws IOException {
        Files.createDirectories(classesRoot);
        var compiler = ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, null,
            "-d", classesRoot.toString(), source.toString());
        if (rc != 0) {
            throw new IllegalStateException("fixture compile failed for " + source);
        }
    }
}
