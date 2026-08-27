package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.catalog.ClasspathScanner;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
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
import static no.sikt.graphitron.model.Tables.INTENT_CONDITION_TABLE_PARAMETER;
import static no.sikt.graphitron.model.Tables.INTENT_JVM_ANCESTOR;
import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a real capture populates {@code intent_condition_table_parameter}, and that the arm it has to
 * answer through for a generated table class is the one that actually answers.
 *
 * <p>What the relation returns given rows is pinned where the SQL is declared, in
 * {@code no.sikt.graphitron.model.intent.ConditionTableParameterTest}, against a store seeded row by
 * row. That tier cannot catch this one's subject, which is whether the two captures a real run
 * performs meet: the census has to spell the parameter's declared class exactly as the catalog
 * spells the class it generated for the table, and a mismatch between those two strings would look
 * exactly like a parameter that is honestly not a table.
 *
 * <p>The case below states the whole shape rather than only the answer, because the answer alone
 * would pass for the wrong reason if the closure arm had somehow reached the generated class. So it
 * pins that the classpath census does not hold it, that the ancestor closure therefore carries no
 * path from it to the jOOQ table interface, and that the catalog nonetheless names it.
 */
@PipelineTier
class ConditionTableParameterCaptureTest {

    private static final String ROUTES = "no.sikt.graphitron.rewrite.TestConditionRoutes";
    private static final String FILM_TABLE = "no.sikt.graphitron.rewrite.test.jooq.tables.Film";
    private static final String JOOQ_TABLE = "org.jooq.Table";

    private static final String SDL = """
        type Query {
          films(rating: String, title: String): [Film!]!
            @condition(condition: {
              className: "no.sikt.graphitron.rewrite.TestConditionRoutes",
              method: "filmByRating"
            })
        }

        type Film @table(name: "film") {
          id: ID!
        }
        """;

    /**
     * The table parameter of a real condition method, resolved through the catalog because nothing
     * else can resolve it. The two value parameters beside it are the control: whatever role they
     * play, it is not this one, and the relation says so by not naming them.
     */
    @Test
    @DisplayName("the generated table parameter resolves through the catalog and nothing else does")
    void theTableParameterResolvesOverARealCapture(@TempDir Path tmp) {
        withCatalogStore(tmp, dsl -> {
            assertThat(dsl.fetchExists(JVM_CLASS, JVM_CLASS.CLASS_NAME.eq(FILM_TABLE)))
                .as("the classpath census drops the generated package, so the closure arm has"
                    + " nothing to climb from")
                .isFalse();
            assertThat(reachesJooqTable(dsl))
                .as("and so carries no path from the generated class to the jOOQ table interface")
                .isFalse();
            assertThat(dsl.fetchExists(SQL_TABLE, SQL_TABLE.CLASS_FQN.eq(FILM_TABLE)))
                .as("the catalog is what spells the generated class, and the census's own spelling"
                    + " of the parameter's declared type has to be the same string")
                .isTrue();

            assertThat(tableParameters(dsl)).containsExactly(0);
        });
    }

    /** Whether the closure connects the generated table class to the interface it implements. */
    private static boolean reachesJooqTable(DSLContext dsl) {
        var a = INTENT_JVM_ANCESTOR;
        return dsl.fetchExists(a, a.GRAPH_NAME.eq(CapturedStore.GRAPH)
            .and(a.CLASS_NAME.eq(FILM_TABLE)).and(a.ANCESTOR_NAME.eq(JOOQ_TABLE)));
    }

    /** The positions of the fixture's method that receive the table. */
    private static List<Integer> tableParameters(DSLContext dsl) {
        var t = INTENT_CONDITION_TABLE_PARAMETER;
        return dsl.select(t.POSITION)
            .from(t)
            .where(t.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .and(t.CLASS_NAME.eq(ROUTES))
            .and(t.METHOD_NAME.eq("filmByRating"))
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
     * The real scan over the test classes. Without it the census holds no method at all and the
     * relation is empty, which is a fixture too thin to fail rather than a passing claim.
     */
    private static List<CompletionData.ExternalReference> census() {
        return ClasspathScanner.scan(testClassRoot(), testContext().jooqPackage());
    }

    private static Path testClassRoot() {
        try {
            return Path.of(ConditionTableParameterCaptureTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the test classes are not on a file path", e);
        }
    }
}
