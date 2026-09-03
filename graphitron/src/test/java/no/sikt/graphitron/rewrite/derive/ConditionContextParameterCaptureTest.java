package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.classpath.ClasspathScanner;
import no.sikt.graphitron.model.classpath.CompletionData;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_CONDITION_CONTEXT_ARG;
import static no.sikt.graphitron.model.Tables.INTENT_CONDITION_CONTEXT_PARAMETER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a real capture populates {@code intent_condition_context_parameter}, and that the three
 * readings the relation has to combine agree when each comes from its own capture.
 *
 * <p>What the relation returns given rows is pinned where the SQL is declared, in
 * {@code no.sikt.graphitron.model.intent.ConditionContextParameterTest}, against a store seeded row
 * by row. What that tier cannot say is whether the strings a real run writes line up: the context
 * key comes from the directive text, the parameter name from the classpath census, and the slot that
 * competes with it from the GraphQL document, and any two of those disagreeing on a spelling would
 * look exactly like a key that names no parameter.
 *
 * <p>One signature carries all three answers, which is why the expectation is a whole list rather
 * than a membership: the method's first parameter is the source table, its second is named after an
 * argument the field declares, and only its third is left for the context key the directive wrote.
 * Asserting the list is what makes the two exclusions visible here rather than only in the seeded
 * tier, since a relation that had lost either would answer with more positions and not with none.
 */
@PipelineTier
class ConditionContextParameterCaptureTest {

    private static final String ROUTES = "no.sikt.graphitron.rewrite.TestConditionRoutes";

    private static final String SDL = """
        type Query {
          films(rating: String): [Film!]!
            @condition(condition: {
              className: "no.sikt.graphitron.rewrite.TestConditionRoutes",
              method: "filmByRating"
            }, contextArguments: ["title"])
        }

        type Film @table(name: "film") {
          id: ID!
        }
        """;

    @Test
    @DisplayName("the context key reaches the one parameter neither the table nor a slot claims")
    void theContextKeyReachesItsParameterOverARealCapture(@TempDir Path tmp) {
        withCatalogStore(tmp, dsl -> {
            assertThat(dsl.fetchExists(GRAPHITRON_FIELD_CONDITION_CONTEXT_ARG,
                    GRAPHITRON_FIELD_CONDITION_CONTEXT_ARG.NAME.eq("title")))
                .as("capture writes the directive's context key, which is the reading the relation"
                    + " starts from")
                .isTrue();

            assertThat(contextParameters(dsl))
                .as("position 0 receives the table and position 1 is named after the field's own"
                    + " argument, so only position 2 is the context key's")
                .containsExactly(2);
        });
    }

    /** The positions of the fixture's method that receive a context value. */
    private static List<Integer> contextParameters(DSLContext dsl) {
        var t = INTENT_CONDITION_CONTEXT_PARAMETER;
        return dsl.select(t.POSITION)
            .from(t)
            .where(t.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .and(t.USE_SITE.eq("Query.films"))
            .orderBy(t.POSITION)
            .fetch(t.POSITION);
    }

    private static void withCatalogStore(Path tmp, Consumer<DSLContext> body) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var captured = CapturedStore.ofCatalog(tmp, CapturedStore.GRAPH, SDL, jooq, census())) {
            body.accept(captured.dsl());
        }
    }

    /**
     * The real scan over the test classes. Without it the census holds no method at all, the
     * parameter names the key matches against do not exist, and the case passes on an empty store.
     */
    private static List<CompletionData.ExternalReference> census() {
        return ClasspathScanner.scan(testClassRoot(), testContext().jooqPackage());
    }

    private static Path testClassRoot() {
        try {
            return Path.of(ConditionContextParameterCaptureTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the test classes are not on a file path", e);
        }
    }
}
