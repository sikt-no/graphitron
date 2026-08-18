package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_DECLARED_TYPE_ELEMENT;
import static no.sikt.graphitron.model.Tables.INTENT_PRODUCER_CARDINALITY_CONFLICT;
import static no.sikt.graphitron.model.test.SeededStore.seedClass;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedMethod;
import static no.sikt.graphitron.model.test.SeededStore.seedService;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_producer_cardinality_conflict} returns: where a field and the method producing
 * its value disagree about how many.
 *
 * <p>Every case is a pair, one coordinate that disagrees beside one that agrees over the same
 * producer or the same SDL cardinality. A detection is only as good as its silence, and a rule that
 * reported every coordinate would pass any test that only looked at the rows it produced.
 *
 * <p>The comparison existed before this relation did, as a clause inside the walk whose result was
 * a decision to stop. What is new is that it is observable, so the cases here pin both the rows and
 * which way each disagreement runs.
 *
 * <p>Both sides of the comparison are stated as rows: a field's cardinality is one column, and a
 * producer's is the peel over the classes its declared return names, which is a handful of paths.
 * Neither needs a compiler or a schema loader to arrive at, and stating them directly is what lets
 * one fixture hold every delivery shape the peel distinguishes, including the raw container and the
 * primitive return that the two edges of the rule turn on.
 */
class ProducerCardinalityTest {

    // ===== The two directions of disagreement =====

    /** A single-valued field whose producer hands back a collection. */
    @Test
    void aSingleFieldProducedByManyDisagrees() {
        withProducers(dsl ->
            assertThat(conflict(dsl, "one")).containsExactly("SERVICE list=false many=true"));
    }

    /** The same disagreement the other way, which a rule comparing one direction would miss. */
    @Test
    void aListFieldProducedByOneDisagrees() {
        withProducers(dsl ->
            assertThat(conflict(dsl, "many")).containsExactly("SERVICE list=true many=false"));
    }

    // ===== Agreement is silence =====

    /** Both ways of agreeing, over the same two producers the disagreements above are built on. */
    @Test
    void agreementProducesNoRow() {
        withProducers(dsl -> {
            assertThat(conflict(dsl, "films")).as("a list from a collection").isEmpty();
            assertThat(conflict(dsl, "solo")).as("a single from a single").isEmpty();
        });
    }

    /**
     * A map follows its value rather than counting as a collection itself. Both coordinates are
     * single-valued and both producers return a map, so the map is not what decides either.
     */
    @Test
    void aMapFollowsItsValue() {
        withProducers(dsl -> {
            assertThat(conflict(dsl, "mapped")).as("a map to one value delivers one").isEmpty();
            assertThat(conflict(dsl, "mappedMany"))
                .containsExactly("SERVICE list=false many=true");
        });
    }

    /**
     * A raw container delivers one of itself, so a single-valued field standing on it agrees. The
     * descent never happened and there is nothing to multiply.
     */
    @Test
    void aRawContainerAgreesWithASingleField() {
        withProducers(dsl -> assertThat(conflict(dsl, "raw")).isEmpty());
    }

    // ===== Where the comparison cannot be made =====

    /**
     * A producer whose declared return names no class at its root has no row, rather than a row
     * asserting agreement. There is no peel to compare against, and a primitive return is a
     * different complaint from a cardinality one.
     */
    @Test
    void aProducerNamingNoClassHasNoRowEitherWay() {
        withProducers(dsl -> {
            assertThat(conflict(dsl, "counted")).isEmpty();
            assertThat(dsl.fetchCount(INTENT_DECLARED_TYPE_ELEMENT,
                INTENT_DECLARED_TYPE_ELEMENT.CLASS_NAME.eq(SERVICE)
                    .and(INTENT_DECLARED_TYPE_ELEMENT.OWNER_NAME.eq("count"))))
                .as("and the absence is the peel's, not this relation's")
                .isZero();
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String APP = "app/target/classes";
    private static final String SERVICE = "app.FilmService";

    private static final String LIST = "()Ljava/util/List;";
    private static final String MAP = "()Ljava/util/Map;";

    /**
     * One producer per delivery shape, each read at both SDL cardinalities where the pairing is
     * what the case turns on: a collection, a single value, a map to one, a map to many, a raw
     * container and a primitive.
     *
     * <p>The classes a declared return names are stated at the positions the peel reads them at,
     * which is the whole of what the peel has to work from. {@code app.FilmRecord} is a landing
     * name and nothing joins it, so no census row declares it.
     */
    private static void withProducers(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, APP, "DIRECTORY");
            seedGraphSource(dsl, GRAPH, APP);
            seedType(dsl, GRAPH, "Film", "OBJECT");
            seedType(dsl, GRAPH, "Int", "SCALAR");

            seedClass(dsl, APP, SERVICE, "CLASS");
            seedMethod(dsl, APP, SERVICE, "findAll", LIST,
                Map.of("", "java.util.List", "0", "app.FilmRecord"));
            seedMethod(dsl, APP, SERVICE, "findOne", "()Lapp/FilmRecord;",
                Map.of("", "app.FilmRecord"));
            seedMethod(dsl, APP, SERVICE, "byKey", MAP,
                Map.of("", "java.util.Map", "0", "java.lang.String", "1", "app.FilmRecord"));
            seedMethod(dsl, APP, SERVICE, "byKeyMany", MAP,
                Map.of("", "java.util.Map", "0", "java.lang.String",
                    "1", "java.util.List", "1.0", "app.FilmRecord"));
            seedMethod(dsl, APP, SERVICE, "raw", LIST, Map.of("", "java.util.List"));
            seedMethod(dsl, APP, SERVICE, "count", "()I");

            produces(dsl, "films", "Film", true, "findAll");
            produces(dsl, "one", "Film", false, "findAll");
            produces(dsl, "solo", "Film", false, "findOne");
            produces(dsl, "many", "Film", true, "findOne");
            produces(dsl, "mapped", "Film", false, "byKey");
            produces(dsl, "mappedMany", "Film", false, "byKeyMany");
            produces(dsl, "raw", "Film", false, "raw");
            produces(dsl, "counted", "Int", false, "count");

            body.accept(dsl);
        });
    }

    /** A {@code Query} field of the stated cardinality, and the service that produces its value. */
    private static void produces(DSLContext dsl, String fieldName, String namedType, boolean isList,
                                 String method) {
        seedField(dsl, GRAPH, "Query", fieldName, namedType, isList);
        seedService(dsl, GRAPH, "Query", fieldName, SERVICE, method);
    }

    /** The row with both halves of the disagreement, so a case states which way it runs. */
    private static List<String> conflict(DSLContext dsl, String fieldName) {
        var c = INTENT_PRODUCER_CARDINALITY_CONFLICT;
        return dsl.select(c.DECLARED_VIA, c.FIELD_IS_LIST, c.PRODUCER_DELIVERS_MANY)
            .from(c)
            .where(c.GRAPH_NAME.eq(GRAPH).and(c.TYPE_NAME.eq("Query"))
                .and(c.FIELD_NAME.eq(fieldName)))
            .fetch(r -> r.value1() + " list=" + r.value2() + " many=" + r.value3());
    }
}
