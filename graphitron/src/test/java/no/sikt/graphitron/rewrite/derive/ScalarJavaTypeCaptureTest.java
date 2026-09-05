package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.classpath.ClasspathScanner;
import no.sikt.graphitron.model.classpath.CompletionData;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.ScalarTypeResolver;
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
import static no.sikt.graphitron.model.Tables.INTENT_SCALAR_JAVA_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a real capture populates {@code intent_scalar_java_type}, and that the Java type it gives a
 * consumer scalar is the one the generator resolves for the same constant.
 *
 * <p>What the relation returns given rows is pinned where the SQL is declared, in
 * {@code no.sikt.graphitron.model.intent.ScalarJavaTypeTest}. What that tier cannot reach is the
 * reading itself: the census records a constant by parsing a classfile, and the type it coerces to
 * is only readable off a loaded class, so the two have to agree on the constant's identity before
 * the relation has anything to join. A disagreement there answers silence, which reads exactly like
 * a scalar that honestly resolves to nothing.
 *
 * <p>The three erased constants are here as the negative half. They are the shapes the generator
 * refuses, and the census has to come to the same conclusion rather than inventing a type for them.
 */
@PipelineTier
class ScalarJavaTypeCaptureTest {

    private static final String FIXTURES = "no.sikt.graphitron.rewrite.scalarfixture.ScalarConstants";
    private static final String MONEY = "no.sikt.graphitron.rewrite.scalarfixture.Money";

    private static final String SDL = """
        scalar Money @scalarType(scalar: "%1$s.MONEY")
        scalar AnonMoney @scalarType(scalar: "%1$s.ANONYMOUS_MONEY")
        scalar RawMoney @scalarType(scalar: "%1$s.RAW_MONEY")
        scalar ErasedMoney @scalarType(scalar: "%1$s.ERASED_NAMED_MONEY")

        type Query {
          films(price: Money, other: AnonMoney): [Film!]!
        }

        type Film @table(name: "film") {
          id: ID!
          raw: RawMoney
          erased: ErasedMoney
        }
        """.formatted(FIXTURES);

    /**
     * The consumer arm over a real capture. Money resolves because its coercing declares its input
     * type; the three erased constants resolve to nothing and are absent rather than null.
     */
    @Test
    @DisplayName("a declared scalar's Java type is the one its coercing declares")
    void aDeclaredScalarResolvesThroughItsConstant(@TempDir Path tmp) {
        withCatalogStore(tmp, dsl -> assertThat(consumerScalars(dsl))
            .containsExactly("Money " + MONEY));
    }

    /** The engine arm over the same capture: the built-ins a schema uses are rows without asking. */
    @Test
    void theEngineScalarsAreRowsOverARealCapture(@TempDir Path tmp) {
        withCatalogStore(tmp, dsl -> assertThat(scalar(dsl, "ID")).isEqualTo("java.lang.String"));
    }

    /** The store's answer for the fixture constant is the generator's answer for it. */
    @Test
    @DisplayName("the store and the resolver name the same Java type for one constant")
    void theStoreAgreesWithTheResolver(@TempDir Path tmp) {
        var resolution = ScalarTypeResolver.resolveFromConstantFqn(
            FIXTURES, "MONEY", testContext().codegenLoader());
        assertThat(resolution)
            .isInstanceOf(no.sikt.graphitron.rewrite.model.ScalarResolution.Resolved.class);
        var resolved = (no.sikt.graphitron.rewrite.model.ScalarResolution.Resolved) resolution;

        withCatalogStore(tmp, dsl ->
            assertThat(scalar(dsl, "Money")).isEqualTo(resolved.javaType().toString()));
    }

    /** Every consumer scalar the store resolved, as "name javaType". */
    private static List<String> consumerScalars(DSLContext dsl) {
        var t = INTENT_SCALAR_JAVA_TYPE;
        return dsl.select(t.fields())
            .from(t)
            .where(t.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .and(t.TYPE_NAME.like("%Money"))
            .orderBy(t.TYPE_NAME)
            .fetch(row -> row.get(t.TYPE_NAME) + " " + row.get(t.JAVA_TYPE));
    }

    private static String scalar(DSLContext dsl, String typeName) {
        var t = INTENT_SCALAR_JAVA_TYPE;
        return dsl.select(t.JAVA_TYPE)
            .from(t)
            .where(t.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .and(t.TYPE_NAME.eq(typeName))
            .fetchOne(t.JAVA_TYPE);
    }

    private static void withCatalogStore(Path tmp, Consumer<DSLContext> body) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var captured = CapturedStore.ofCatalog(tmp, CapturedStore.GRAPH, SDL, jooq, census())) {
            body.accept(captured.dsl());
        }
    }

    /** The real scan over the test classes, which is where the fixture constants live. */
    private static List<CompletionData.ExternalReference> census() {
        return ClasspathScanner.scan(testClassRoot(), testContext().jooqPackage());
    }

    private static Path testClassRoot() {
        try {
            return Path.of(ScalarJavaTypeCaptureTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the test classes are not on a file path", e);
        }
    }
}
