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
 * Pipeline coverage that pins the source/build decoupling at the build boundary:
 * {@link CatalogBuilder} no longer walks {@code .java} sources at all. It
 * carries the generated table / {@code Keys} class FQNs (the join keys the LSP
 * uses) and only the build-derivable {@code description} (the jOOQ table's SQL
 * comment; empty for columns and services). The source-derived Javadoc and
 * positions are surfaced at the LSP tier from the source index on the
 * {@code .java} cadence; that end-to-end behaviour is covered in
 * {@code SourceCadenceHoverAndDefinitionTest} (LSP module) and the
 * {@code DefinitionsTest} arms. These tests deliberately place real, documented
 * sources on the build and assert the build does <em>not</em> lift them, so a
 * regression that re-introduces a build-cadence walk fails here.
 */
@PipelineTier
class CatalogBuilderSourceTest {

    @Test
    void tableCarriesGeneratedClassFqnAndBuildDoesNotLiftColumnJavadoc(@TempDir Path srcRoot) throws IOException {
        // A documented stand-in for the generated jOOQ table source, on the build.
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

        var film = data.getTable("film").orElseThrow();
        // The catalog carries the generated table class FQN the LSP joins on.
        assertThat(film.classFqn()).endsWith(".tables.Film");
        var filmId = film.columns().stream()
            .filter(c -> c.name().equals("FILM_ID")).findFirst().orElseThrow();
        // Decoupling: even with the documented source on the build, the build does
        // not lift its Javadoc. The column description is the build-derivable
        // fallback (empty); the Javadoc surfaces at the LSP tier from the index.
        assertThat(filmId.description()).isEmpty();
    }

    @Test
    void buildDoesNotLiftServiceOrMethodJavadocEvenWithSourceOnBuild(
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
        // Bytecode-derived structure only: the scan finds the class and method
        // (completion works), but their Javadoc is not lifted at build time. The
        // LSP overlays it from the source index at request time.
        assertThat(ref.description()).isEmpty();
        var method = ref.methods().stream()
            .filter(m -> m.name().equals("price")).findFirst().orElseThrow();
        assertThat(method.description()).isEmpty();
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
