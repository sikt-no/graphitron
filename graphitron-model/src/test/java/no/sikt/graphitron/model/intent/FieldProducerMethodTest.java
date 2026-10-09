package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.model.test.TestRunContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.INTENT_FIELD_PRODUCER_METHOD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two graphs whose classpaths differ, and a reference that resolves only through its own.
 *
 * <p>The census join runs through {@code store_graph_source}. Without that hop a coordinate would
 * match a method row under any entry the store holds, including one only a sibling graph claims,
 * and report an ambiguity neither graph has. So the discriminating shape is one class declared by
 * two entries, which is what the second root here is: the same compiled class, copied, read again.
 *
 * <p>Java rather than a fact document because a document cannot vary its classpath. Every document
 * in the corpus is captured against one entry list, which is what makes that reading affordable,
 * and this case needs two that differ. Capture takes the classpath as an argument either way, so
 * the gatherers run here exactly as they do there. The rest of what this relation answers is stated
 * in {@code facts/producer-method-resolution.graphqls}.
 */
class FieldProducerMethodTest {

    @TempDir
    Path tmp;

    private static final String OWN = "own";
    private static final String SIBLING = "sibling";

    private static final String SERVICE = "no.sikt.graphitron.rewrite.test.services.FilmService";

    /** One reference, at the same coordinate in both graphs, naming a method both entries declare. */
    private static final String SDL = """
        type Film @table(name: "film") { filmId: ID }

        extend type Query {
            films: [Film!]!
                @service(service: {className: "%s", method: "titleUppercase"})
        }
        """.formatted(SERVICE);

    @Test
    @DisplayName("a reference resolves through its own graph's entry, not a sibling's")
    void siblingGraphsResolveThroughTheirOwnMembership() throws IOException {
        var ctx = TestRunContext.of();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        var mine = ClasspathCorpus.entries();
        var theirs = List.of(new ClasspathEntry(copyOfTheServiceClass(),
            ClasspathEntry.Origin.REACTOR, "no.sikt:graphitron-sakila-service-copy", null));

        try (var captured = CapturedStore.ofCatalogWith(tmp.resolve("store"), OWN, SDL,
                jooq, mine)) {
            captured.andCatalogGraphWith(SIBLING, SDL, jooq, theirs);

            var rows = captured.dsl()
                .select(INTENT_FIELD_PRODUCER_METHOD.GRAPH_NAME,
                    INTENT_FIELD_PRODUCER_METHOD.SOURCE_NAME,
                    INTENT_FIELD_PRODUCER_METHOD.CANDIDATES)
                .from(INTENT_FIELD_PRODUCER_METHOD)
                .where(INTENT_FIELD_PRODUCER_METHOD.TYPE_NAME.eq("Query")
                    .and(INTENT_FIELD_PRODUCER_METHOD.FIELD_NAME.eq("films")))
                .fetch();

            assertThat(rows)
                .as("one row per graph, each an unambiguous resolution against its own entry")
                .hasSize(2);
            assertThat(rows).extracting(r -> r.value1() + " " + r.value3())
                .containsExactlyInAnyOrder(OWN + " 1", SIBLING + " 1");
            assertThat(rows).extracting(r -> r.value2())
                .as("and the two entries differ, so the partition is what kept them apart")
                .doesNotHaveDuplicates();
        }
    }

    /**
     * The service class again, under a root of its own.
     *
     * <p>Read off the classloader rather than from a path under the corpus root, which is what
     * makes it work whether the corpus is a directory or a jar.
     */
    private Path copyOfTheServiceClass() throws IOException {
        Path root = Files.createDirectories(tmp.resolve("other"));
        String resource = "/" + SERVICE.replace('.', '/') + ".class";
        Path target = root.resolve(resource.substring(1));
        Files.createDirectories(target.getParent());
        try (InputStream bytes = FieldProducerMethodTest.class.getResourceAsStream(resource)) {
            if (bytes == null) {
                throw new IllegalStateException("the service corpus declares no " + resource);
            }
            Files.copy(bytes, target);
        }
        return root;
    }
}
