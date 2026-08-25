package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_CONNECTION_FACET;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedConnection;
import static no.sikt.graphitron.model.test.SeededStore.seedFacet;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldSynthesis;
import static no.sikt.graphitron.model.test.SeededStore.seedInputField;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_connection_facet} resolves: which facets one connection carrier surfaces, and
 * in which order. The use-keyed half of the facet reading, so every case here is about a carrier
 * and the applications it does or does not reach, never about whether an application is well
 * formed; that is {@code FacetBindingTest}'s subject and this relation inherits it.
 *
 * <p>Order is asserted as a value rather than as a read order. A consumer folds these rows into an
 * emitted file, so their order is output, and a case reading them back in the order the query
 * happened to return would pin nothing.
 *
 * <p>The carrier population is the one thing here that is easy to state wrongly, so it gets its own
 * cases from both sides. The rows come from the macro's own expansion and not from the directive
 * that asked for it, which is what makes an {@code @asConnection} the macro declined and a
 * structural Connection return type equally silent.
 */
class ConnectionFacetTest {

    private static final String GRAPH = "g";

    // ===== What a carrier surfaces =====

    /**
     * The ordinary case, read column by column: the carrier's coordinate, the argument the facet's
     * binding rides in, the application's own coordinate, and what it binds.
     */
    @Test
    void theRowNamesTheCarrierTheArgumentAndTheApplication() {
        withStore(dsl -> {
            carrier(dsl, "films", "filter", "FilmFilter", 0);
            facet(dsl, "FilmFilter", "rating", 0, "rating");

            var row = rows(dsl).getFirst();
            assertThat(row.get(INTENT_CONNECTION_FACET.TYPE_NAME)).isEqualTo("Query");
            assertThat(row.get(INTENT_CONNECTION_FACET.FIELD_NAME)).isEqualTo("films");
            assertThat(row.get(INTENT_CONNECTION_FACET.POSITION)).isEqualTo(1);
            assertThat(row.get(INTENT_CONNECTION_FACET.FILTER_ARGUMENT_NAME)).isEqualTo("filter");
            assertThat(row.get(INTENT_CONNECTION_FACET.FACET_TYPE_NAME)).isEqualTo("FilmFilter");
            assertThat(row.get(INTENT_CONNECTION_FACET.FACET_FIELD_NAME)).isEqualTo("rating");
            assertThat(row.get(INTENT_CONNECTION_FACET.COLUMN_NAME)).isEqualTo("rating");
            assertThat(row.get(INTENT_CONNECTION_FACET.VALUE_TYPE_NAME)).isEqualTo("String");
            assertThat(row.get(INTENT_CONNECTION_FACET.VALUE_NULLABLE)).isTrue();
        });
    }

    /**
     * The order is the carrier's arguments in declaration order, then each input type's facets in
     * theirs, numbered densely from one. Both halves are needed to pin it: a fixture whose second
     * argument carried the earlier-declared facet passes either way if only one is read.
     */
    @Test
    void facetsSurfaceInArgumentThenApplicationOrder() {
        withStore(dsl -> {
            carrier(dsl, "films", "filter", "FilmFilter", 0);
            argument(dsl, "films", "shape", "ShapeFilter", 1);
            facet(dsl, "FilmFilter", "language", 1, "language");
            facet(dsl, "FilmFilter", "rating", 0, "rating");
            facet(dsl, "ShapeFilter", "length", 0, "length");

            assertThat(rows(dsl).map(ConnectionFacetTest::render))
                .containsExactly("1 filter.rating", "2 filter.language", "3 shape.length");
        });
    }

    /** Two carriers over one filter input each surface its facets; the input type is not the grain. */
    @Test
    void twoCarriersOverOneFilterInputEachSurfaceItsFacets() {
        withStore(dsl -> {
            carrier(dsl, "films", "filter", "FilmFilter", 0);
            carrier(dsl, "archivedFilms", "filter", "FilmFilter", 0);
            facet(dsl, "FilmFilter", "rating", 0, "rating");

            assertThat(rows(dsl).map(r -> r.get(INTENT_CONNECTION_FACET.FIELD_NAME)
                    + ":" + render(r)))
                .containsExactlyInAnyOrder("films:1 filter.rating",
                                           "archivedFilms:1 filter.rating");
        });
    }

    // ===== Which carriers are carriers =====

    /**
     * The population is the expansion and not the directive. An {@code @asConnection} the macro
     * declined to expand rewrote no type and mints no facets object, so it surfaces nothing, and
     * neither does an ordinary field returning a Connection the author declared: that shape is the
     * author's and the promoter appends no facets to it.
     */
    @Test
    void onlyTheCarriersTheMacroExpandedSurfaceFacets() {
        withStore(dsl -> {
            seedType(dsl, GRAPH, "FilmConnection", "OBJECT");
            seedField(dsl, GRAPH, "Query", "films", "FilmConnection", false);
            seedArgument(dsl, GRAPH, "Query", "films", "filter", "FilmFilter", 0, 2);
            seedConnection(dsl, GRAPH, "Query", "films");
            seedField(dsl, GRAPH, "Query", "declared", "FilmConnection", false);
            seedArgument(dsl, GRAPH, "Query", "declared", "filter", "FilmFilter", 0, 2);
            facet(dsl, "FilmFilter", "rating", 0, "rating");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * An input type no carrier names surfaces nothing, which is the dead-schema decline: the facets
     * object such an application would fill has no field to hang off.
     */
    @Test
    void anInputTypeNoCarrierNamesSurfacesNoFacets() {
        withStore(dsl -> {
            carrier(dsl, "films", "filter", "FilmFilter", 0);
            facet(dsl, "OtherFilter", "rating", 0, "rating");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * Reachability is one hop. A facet on a type nested inside the carrier's filter input is not
     * this carrier's: the walk this relation transcribes reads the argument's own type's fields,
     * and a transitive closure would surface facets no facets object has a field for.
     */
    @Test
    void aFacetOnANestedInputTypeIsNotReached() {
        withStore(dsl -> {
            carrier(dsl, "films", "filter", "FilmFilter", 0);
            seedType(dsl, GRAPH, "NestedFilter", "INPUT_OBJECT");
            seedInputField(dsl, GRAPH, "FilmFilter", "nested", "NestedFilter", 0, false, false, null);
            facet(dsl, "NestedFilter", "rating", 0, "rating");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    // ===== The first-wins collapse =====

    /**
     * A facet name written on two of one carrier's filter inputs collapses to the earlier argument.
     * The collapse transcribes the promoter's own dedup, which exists so synthesis cannot try to
     * build a facets object with two same-named fields; the duplicate itself is a rejection, so on
     * a schema that assembles this never fires and a case is the only place it can be read.
     */
    @Test
    void aNameRepeatedAcrossFilterInputsCollapsesToItsFirstArgument() {
        withStore(dsl -> {
            carrier(dsl, "films", "filter", "FilmFilter", 0);
            argument(dsl, "films", "shape", "ShapeFilter", 1);
            facet(dsl, "FilmFilter", "rating", 0, "rating");
            facet(dsl, "ShapeFilter", "rating", 0, "certificate");

            assertThat(rows(dsl).map(ConnectionFacetTest::render))
                .containsExactly("1 filter.rating");
        });
    }

    /**
     * The collapse sits after the gate, so a malformed application never consumes the name a
     * well-formed one would take. Collapsing first would leave this carrier with no {@code rating}
     * facet at all, which is a different answer from the one the promoter reaches.
     */
    @Test
    void aMalformedApplicationDoesNotConsumeTheNameFromAWellFormedTwin() {
        withStore(dsl -> {
            carrier(dsl, "films", "filter", "FilmFilter", 0);
            argument(dsl, "films", "shape", "ShapeFilter", 1);
            seedType(dsl, GRAPH, "String", "SCALAR");
            seedInputField(dsl, GRAPH, "FilmFilter", "rating", "String", 0, false, false, null);
            seedFacet(dsl, GRAPH, "FilmFilter", "rating");
            facet(dsl, "ShapeFilter", "rating", 0, "certificate");

            assertThat(rows(dsl).map(ConnectionFacetTest::render))
                .containsExactly("1 shape.rating");
        });
    }

    /** The graph partition holds: a sibling graph's carriers surface none of these facets. */
    @Test
    void aSiblingGraphSurfacesNoneOfTheseFacets() {
        withStore(dsl -> {
            carrier(dsl, "films", "filter", "FilmFilter", 0);
            facet(dsl, "FilmFilter", "rating", 0, "rating");

            assertThat(rowsIn(dsl, "other")).isEmpty();
        });
    }

    // ===== Fixture =====

    private static void withStore(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, body);
    }

    /**
     * One carrier the macro expanded: the rewritten field, its first filter argument, the directive
     * row and the synthesis row the expansion left behind. Capture writes the last two together, so
     * the fixture does too; the case about the population is where they come apart.
     */
    private static void carrier(DSLContext dsl, String fieldName, String argumentName,
                                String inputTypeName, int ordinal) {
        seedType(dsl, GRAPH, "FilmConnection", "OBJECT");
        seedField(dsl, GRAPH, "Query", fieldName, "FilmConnection", false);
        seedConnection(dsl, GRAPH, "Query", fieldName);
        seedFieldSynthesis(dsl, GRAPH, "Query", fieldName, "CONNECTION", "[Film!]!");
        argument(dsl, fieldName, argumentName, inputTypeName, ordinal);
    }

    /** One more filter argument on a carrier that already exists. */
    private static void argument(DSLContext dsl, String fieldName, String argumentName,
                                 String inputTypeName, int ordinal) {
        seedArgument(dsl, GRAPH, "Query", fieldName, argumentName, inputTypeName, ordinal, 2);
    }

    /** One well-formed {@code @asFacet} application, on {@code FacetBindingTest}'s terms. */
    private static void facet(DSLContext dsl, String inputTypeName, String fieldName,
                              int ordinal, String columnName) {
        seedType(dsl, GRAPH, "String", "SCALAR");
        seedInputField(dsl, GRAPH, inputTypeName, fieldName, "String", ordinal, false, false, null);
        seedFieldBinding(dsl, GRAPH, inputTypeName, fieldName, columnName);
        seedFacet(dsl, GRAPH, inputTypeName, fieldName);
    }

    private static Result<Record> rows(DSLContext dsl) {
        return rowsIn(dsl, GRAPH);
    }

    private static Result<Record> rowsIn(DSLContext dsl, String graphName) {
        derive(dsl);
        return dsl.select().from(INTENT_CONNECTION_FACET)
            .where(INTENT_CONNECTION_FACET.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_CONNECTION_FACET.FIELD_NAME, INTENT_CONNECTION_FACET.POSITION)
            .fetch();
    }

    /** {@code position argument.facet}: the order and the pair that identifies a binding. */
    private static String render(Record row) {
        return row.get(INTENT_CONNECTION_FACET.POSITION)
            + " " + row.get(INTENT_CONNECTION_FACET.FILTER_ARGUMENT_NAME)
            + "." + row.get(INTENT_CONNECTION_FACET.FACET_FIELD_NAME);
    }
}
