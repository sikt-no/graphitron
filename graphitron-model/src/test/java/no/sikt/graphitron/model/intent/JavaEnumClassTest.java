package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_JAVA_ENUM_CLASS;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedClass;
import static no.sikt.graphitron.model.test.SeededStore.seedEnumBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_java_enum_class} returns: which classes a graph can see are enums, over the
 * two censuses that each answer for half the question.
 *
 * <p>The cases are shaped by why there are two arms at all. The classpath census carries a class
 * kind and answers for an author's own enum; it excludes the generated jOOQ package, so a generated
 * enum reaches this relation only through the catalog arm, which walks the columns that bind to
 * one. Each arm therefore has a case that would fail if the other were the only one, and a third
 * case says what happens where both answer.
 */
class JavaEnumClassTest {

    // ===== The classpath arm =====

    /** The census's own answer: an ENUM kind is an enum here and every other kind is not. */
    @Test
    void theClasspathCensusAnswersByClassKind() {
        withSources(dsl -> {
            seedClass(dsl, JAR, "com.example.Rating", "ENUM");
            seedClass(dsl, JAR, "com.example.Conditions", "CLASS");
            seedClass(dsl, JAR, "com.example.Filter", "INTERFACE");
            seedClass(dsl, JAR, "com.example.Pair", "RECORD");

            assertThat(enums(dsl, GRAPH)).containsExactly("com.example.Rating");
        });
    }

    // ===== The catalog arm =====

    /**
     * The arm this relation exists for. A generated enum is in the package the classpath scan
     * deliberately drops, so it has no census row of any kind, and reading the census alone would
     * answer no for exactly the classes the generator's own predicate answers yes for.
     */
    @Test
    void aColumnBoundEnumIsAnEnumThoughTheClasspathCensusNeverSawIt() {
        withSources(dsl -> {
            seedEnumBinding(dsl, PKG, "pkg.enums.MpaaRating", PUBLIC, "mpaa_rating");

            assertThat(enums(dsl, GRAPH)).containsExactly("pkg.enums.MpaaRating");
        });
    }

    /**
     * The converter-bound half of the catalog arm, which names no catalog type at all. It answers
     * here on the same terms as the generated half: this relation is about the Java class, and the
     * database coordinate beside it is the binding relation's own column.
     */
    @Test
    void aConverterBoundEnumNamingNoCatalogTypeAnswersTheSame() {
        withSources(dsl -> {
            seedEnumBinding(dsl, PKG, "com.example.Rating", null, null);

            assertThat(enums(dsl, GRAPH)).containsExactly("com.example.Rating");
        });
    }

    // ===== Where both arms answer =====

    /**
     * An author's own enum that a column also binds to is in both censuses, and that is one fact
     * about one class. UNION rather than UNION ALL, so no reader deduplicates for itself.
     */
    @Test
    void aClassBothCensusesNameIsOneRow() {
        withSources(dsl -> {
            seedClass(dsl, JAR, "com.example.Rating", "ENUM");
            seedEnumBinding(dsl, PKG, "com.example.Rating", null, null);

            assertThat(enums(dsl, GRAPH)).containsExactly("com.example.Rating");
        });
    }

    // ===== The partition =====

    /** Both arms scope through the graph's own sources, so a sibling graph reads neither. */
    @Test
    void aSiblingGraphSeesNeitherArm() {
        withSources(dsl -> {
            seedGraph(dsl, "other");
            seedClass(dsl, JAR, "com.example.Rating", "ENUM");
            seedEnumBinding(dsl, PKG, "pkg.enums.MpaaRating", PUBLIC, "mpaa_rating");

            assertThat(enums(dsl, GRAPH)).hasSize(2);
            assertThat(enums(dsl, "other")).isEmpty();
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String PKG = "pkg";
    private static final String JAR = "conditions.jar";
    private static final String PUBLIC = "public";

    /** One classpath entry and one generated package, so either arm can be seeded on its own. */
    private static void withSources(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedSource(dsl, JAR, "JAR");
            seedGraphSource(dsl, GRAPH, PKG);
            seedGraphSource(dsl, GRAPH, JAR);
            body.accept(dsl);
        });
    }

    private static List<String> enums(DSLContext dsl, String graphName) {
        derive(dsl);
        var e = INTENT_JAVA_ENUM_CLASS;
        return dsl.select(e.CLASS_FQN)
            .from(e)
            .where(e.GRAPH_NAME.eq(graphName))
            .orderBy(e.CLASS_FQN)
            .fetch(e.CLASS_FQN);
    }
}
