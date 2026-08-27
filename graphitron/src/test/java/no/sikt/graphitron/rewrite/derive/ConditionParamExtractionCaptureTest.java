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
import static no.sikt.graphitron.model.Tables.INTENT_CONDITION_PARAM_EXTRACTION;
import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a real capture populates {@code intent_condition_param_extraction}, and that the answer it
 * gives for a generated enum is the one the generator emits.
 *
 * <p>What the relation returns given rows is pinned where the SQL is declared, in
 * {@code no.sikt.graphitron.model.intent.ConditionParamExtractionTest}, against a store seeded row
 * by row. That tier cannot catch this one's subject, which is whether the two captures a real run
 * performs actually meet: the classpath census has to reach the condition class, the catalog walk
 * has to reach the enum the signature names, and the enum's spelling on the two sides has to be the
 * same string or the join finds nothing and the relation quietly answers {@code DIRECT}.
 *
 * <p>That last failure is the one worth a test of its own, because it is silent. Both spellings are
 * documented as the fully-qualified binary name, and a mismatch between them would look exactly
 * like a parameter that is honestly not an enum.
 */
@PipelineTier
class ConditionParamExtractionCaptureTest {

    private static final String ROUTES = "no.sikt.graphitron.rewrite.TestConditionRoutes";
    private static final String MPAA_RATING = "no.sikt.graphitron.rewrite.test.jooq.enums.MpaaRating";

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
     * The claim the catalog's enum relation was added for. The condition method's value parameter
     * is typed as a generated enum, which is in the package the classpath scan drops, so the census
     * says nothing about it at all; the extraction is nonetheless the enum one, because the catalog
     * side answered. The scalar parameter beside it is the control.
     */
    @Test
    @DisplayName("a generated enum parameter extracts by valueOf and a scalar one directly")
    void aGeneratedEnumParameterExtractsByValueOfOverARealCapture(@TempDir Path tmp) {
        withCatalogStore(tmp, dsl -> {
            assertThat(dsl.fetchExists(JVM_CLASS, JVM_CLASS.CLASS_NAME.eq(MPAA_RATING)))
                .as("the classpath census does not hold the generated enum, which is why the"
                    + " catalog arm has to answer for it")
                .isFalse();

            assertThat(extractions(dsl)).containsExactly(
                "0 table no.sikt.graphitron.rewrite.test.jooq.tables.Film DIRECT",
                "1 rating " + MPAA_RATING + " ENUM_VALUE_OF",
                "2 title java.lang.String DIRECT");
        });
    }

    /** Each row of the fixture's method as "position name javaType extractionKind". */
    private static List<String> extractions(DSLContext dsl) {
        var x = INTENT_CONDITION_PARAM_EXTRACTION;
        return dsl.select(x.fields())
            .from(x)
            .where(x.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .and(x.CLASS_NAME.eq(ROUTES))
            .and(x.METHOD_NAME.eq("filmByRating"))
            .orderBy(x.POSITION)
            .fetch(row -> row.get(x.POSITION) + " " + row.get(x.PARAM_NAME) + " "
                + row.get(x.JAVA_TYPE) + " " + row.get(x.EXTRACTION_KIND));
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
            return Path.of(ConditionParamExtractionCaptureTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the test classes are not on a file path", e);
        }
    }
}
