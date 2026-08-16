package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.capture.FactCapture;
import no.sikt.graphitron.rewrite.catalog.ClasspathScanner;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_CLASS;
import static no.sikt.graphitron.model.Tables.WALK_TYPE_BACKING_CLASS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The differential the walk-shadow family exists for, run: the same schema over the same classes,
 * answered once by the reflective walk and once by the store's derivation, compared as two
 * relations in one store.
 *
 * <p>This is not an agreement test and the walk is not the specification. What it pins is that the
 * derivation reproduces the walk where the two are meant to agree, and that where they part the
 * difference is the one the derivation set out to make rather than an accident. The classes are
 * public fixtures because both sides have to see them: the walk resolves through a classloader,
 * which does not care, and the census keeps public top-level classes only, which does.
 */
@PipelineTier
class TypeBackingShadowTest {

    @TempDir
    Path tmp;

    /**
     * The agreement case: a producer, a hop, a hop through a container and a hop past that. Both
     * sides are run for real, so an empty answer on either would fail here rather than pass
     * vacuously, which is what the two counts assert before the sets are compared.
     */
    @Test
    void theDerivationReproducesTheWalkWhereTheyAgree() {
        String sdl = """
            type Query {
                films: [Film] @service(service: {className: "%s", method: "films"})
            }
            type Film {
                title: String
                language: Language
                actors: [Actor]
            }
            type Language {
                name: String
                country: Country
            }
            type Country { code: String }
            type Actor { name: String }
            """.formatted(SERVICE);

        withBothSides(sdl, dsl -> {
            assertThat(walk(dsl)).as("the walk answered something").isNotEmpty();
            assertThat(derived(dsl)).as("the derivation answered something").isNotEmpty();
            assertThat(derived(dsl)).containsExactlyInAnyOrderElementsOf(walk(dsl));
            assertThat(derived(dsl)).contains(
                "Film=" + PKG + "TestBackingFilm",
                "Language=" + PKG + "TestBackingLanguage",
                "Country=" + PKG + "TestBackingCountry",
                "Actor=" + PKG + "TestBackingActor");
        });
    }

    /**
     * The recorded difference, in the direction the decomposition intended. Two producers naming
     * different classes make the walk refuse to bind at all, so its answer for the type is silence;
     * the derivation carries both and a conflict row names the contest. The walk's silence is not
     * a smaller answer than the derivation's, it is a different one, and this is where a reader
     * comparing the two learns which.
     */
    @Test
    void aDisagreementIsTwoRowsHereAndSilenceInTheWalk() {
        String sdl = """
            type Query {
                films: [Film] @service(service: {className: "%s", method: "films"})
                other: Film @service(service: {className: "%s", method: "other"})
            }
            type Film { title: String }
            """.formatted(SERVICE, SERVICE);

        withBothSides(sdl, dsl -> {
            assertThat(walk(dsl)).doesNotContain(
                "Film=" + PKG + "TestBackingFilm", "Film=" + PKG + "TestBackingOther");
            assertThat(derived(dsl)).containsExactlyInAnyOrder(
                "Film=" + PKG + "TestBackingFilm", "Film=" + PKG + "TestBackingOther");
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "TypeBackingShadowTest";
    private static final String PKG = "no.sikt.graphitron.rewrite.derive.";
    private static final String SERVICE = PKG + "TestBackingService";

    /**
     * Capture writes the derivation; the walk's own row is written beside it from a bundle built
     * over the same text, which is how the walk-reach family is written in production too.
     */
    private void withBothSides(String sdl, java.util.function.Consumer<DSLContext> body) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            var schemaFile = write(tmp, sdl);
            var registry = RewriteSchemaLoader.load(List.of(SchemaSource.file(schemaFile)));
            FactCapture.capture(store.dsl(), new FactCapture.GraphIdentity(GRAPH, tmp),
                FactCapture.SubjectConfig.none(), registry,
                TestSchemaHelper.attribution(schemaFile), jooq, census(),
                TestSchemaHelper.nodeDeclaration());
            var bundle = TestSchemaHelper.buildBundle(sdl);
            TypeBackingClassRows.write(store.dsl(), GRAPH, TypeBackingClasses.of(bundle.model()));
            body.accept(store.dsl());
        }
    }

    /**
     * The real scan over the test classes, so the census the derivation reads is the one a build
     * would produce rather than a reference list written to make it pass.
     */
    private static List<CompletionData.ExternalReference> census() {
        return ClasspathScanner.scan(testClassRoot(), testContext().jooqPackage());
    }

    private static Path testClassRoot() {
        try {
            return Path.of(TestBackingService.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the test classes are not on a file path", e);
        }
    }

    private static List<String> walk(DSLContext dsl) {
        return dsl.select(WALK_TYPE_BACKING_CLASS.TYPE_NAME, WALK_TYPE_BACKING_CLASS.CLASS_NAME)
            .from(WALK_TYPE_BACKING_CLASS)
            .where(WALK_TYPE_BACKING_CLASS.GRAPH_NAME.eq(GRAPH))
            .fetch(r -> r.value1() + "=" + r.value2());
    }

    private static List<String> derived(DSLContext dsl) {
        return dsl.select(INTENT_TYPE_BACKING_CLASS.TYPE_NAME, INTENT_TYPE_BACKING_CLASS.CLASS_NAME)
            .from(INTENT_TYPE_BACKING_CLASS)
            .where(INTENT_TYPE_BACKING_CLASS.GRAPH_NAME.eq(GRAPH))
            .fetch(r -> r.value1() + "=" + r.value2());
    }

    private static Path write(Path directory, String sdl) {
        Path file = directory.resolve("fixture.graphqls");
        try {
            Files.createDirectories(directory);
            Files.writeString(file, sdl);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }
}
