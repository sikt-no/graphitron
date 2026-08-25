package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_FACET_BINDING;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedFacet;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedInputField;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_facet_binding} states: for one {@code @asFacet} application, the column its
 * counts group by, the type those counts are keyed on, and whether a key may be null. The
 * definition-keyed half of the facet reading, so every case here is about one application and none
 * of them seeds a carrier.
 *
 * <p>The relation is a gate as much as a projection, and the two are asserted separately. A case
 * about what a well-formed application binds reads every column, because a row naming the right
 * field with the wrong column is a row a consumer would emit wrongly. A case about an arm of the
 * gate seeds an application that differs from the well-formed one in exactly that arm and asserts
 * the absence, so what the case demonstrates is the arm and not the fixture.
 *
 * <p>Every absence here is a misuse the build rejects with a named diagnostic, which is why they
 * are declines rather than defects: this relation's silence is the population a detection over the
 * same rows reports on, and the two are the same reading taken from opposite sides.
 */
class FacetBindingTest {

    private static final String GRAPH = "g";

    // ===== What a well-formed application binds =====

    /**
     * The ordinary case, read column by column: the application's coordinate, its place in the
     * input type, the bound column, the key's type and its nullability, and the position a
     * diagnostic about the facet would carry.
     */
    @Test
    void theRowNamesTheColumnTheKeyTypeAndItsNullability() {
        withStore(dsl -> {
            facet(dsl, "FilmFilter", "rating", "String", 0, "rating");

            var row = rows(dsl).getFirst();
            assertThat(row.get(INTENT_FACET_BINDING.TYPE_NAME)).isEqualTo("FilmFilter");
            assertThat(row.get(INTENT_FACET_BINDING.FIELD_NAME)).isEqualTo("rating");
            assertThat(row.get(INTENT_FACET_BINDING.ORDINAL)).isZero();
            assertThat(row.get(INTENT_FACET_BINDING.COLUMN_NAME)).isEqualTo("rating");
            assertThat(row.get(INTENT_FACET_BINDING.VALUE_TYPE_NAME)).isEqualTo("String");
            assertThat(row.get(INTENT_FACET_BINDING.VALUE_NULLABLE)).isTrue();
            assertThat(row.get(INTENT_FACET_BINDING.SOURCE_LINE)).isEqualTo(2);
        });
    }

    /**
     * The bound column is the {@code @field(name:)} spelling and not the field's own name. The two
     * agree at most sites, so a case letting them agree would pass with the wrong one read.
     */
    @Test
    void theBoundColumnIsTheWrittenNameAndNotTheFieldsOwn() {
        withStore(dsl -> {
            facet(dsl, "FilmFilter", "rating", "String", 0, "mpaa_rating");

            assertThat(rows(dsl).map(FacetBindingTest::render))
                .containsExactly("FilmFilter.rating -> mpaa_rating String?");
        });
    }

    /**
     * A list-valued filter takes its key nullability from the element, which is what a client feeds
     * back into the filter. The outer wrapper is not read here because a non-null outer wrapper has
     * already declined below.
     */
    @Test
    void aListFacetTakesItsKeyNullabilityFromTheElement() {
        withStore(dsl -> {
            seedType(dsl, GRAPH, "String", "SCALAR");
            seedInputField(dsl, GRAPH, "FilmFilter", "ratings", "String", 0, false, true, true);
            seedFieldBinding(dsl, GRAPH, "FilmFilter", "ratings", "rating");
            seedFacet(dsl, GRAPH, "FilmFilter", "ratings");
            seedInputField(dsl, GRAPH, "FilmFilter", "languages", "String", 1, false, true, false);
            seedFieldBinding(dsl, GRAPH, "FilmFilter", "languages", "language");
            seedFacet(dsl, GRAPH, "FilmFilter", "languages");

            assertThat(rows(dsl).map(FacetBindingTest::render))
                .containsExactly("FilmFilter.ratings -> rating String",
                                 "FilmFilter.languages -> language String?");
        });
    }

    /** An enum-valued facet is as ordinary as a scalar one; only an input object leaf declines. */
    @Test
    void anEnumValuedFacetBindsLikeAScalarOne() {
        withStore(dsl -> {
            seedType(dsl, GRAPH, "Rating", "ENUM");
            seedInputField(dsl, GRAPH, "FilmFilter", "rating", "Rating", 0, false, false, null);
            seedFieldBinding(dsl, GRAPH, "FilmFilter", "rating", "rating");
            seedFacet(dsl, GRAPH, "FilmFilter", "rating");

            assertThat(rows(dsl).map(FacetBindingTest::render))
                .containsExactly("FilmFilter.rating -> rating Rating?");
        });
    }

    /**
     * Applications keep their declaration order within the type, which is the inner half of the
     * order a carrier's facets surface in. Two fields sharing one ordinal would leave that order
     * unstated, so the case reads it off two.
     */
    @Test
    void eachApplicationKeepsItsPlaceInTheInputType() {
        withStore(dsl -> {
            facet(dsl, "FilmFilter", "rating", "String", 0, "rating");
            facet(dsl, "FilmFilter", "language", "String", 1, "language");

            assertThat(rows(dsl).map(r -> r.get(INTENT_FACET_BINDING.FIELD_NAME)
                    + "@" + r.get(INTENT_FACET_BINDING.ORDINAL)))
                .containsExactlyInAnyOrder("rating@0", "language@1");
        });
    }

    // ===== The arms of the gate =====

    /**
     * A facet with no {@code @field(name:)} names no column, and a facet value is a grouping key on
     * one column with nothing else to name which.
     */
    @Test
    void anApplicationBindingNoColumnResolvesToNothing() {
        withStore(dsl -> {
            seedType(dsl, GRAPH, "String", "SCALAR");
            seedInputField(dsl, GRAPH, "FilmFilter", "rating", "String", 0, false, false, null);
            seedFacet(dsl, GRAPH, "FilmFilter", "rating");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * A non-null filter field is always active, so its facet could never show counts the filter is
     * not already narrowing. The decline is on the outer wrapper, which is why the list case above
     * reads only the element.
     */
    @Test
    void anAlwaysActiveFilterResolvesToNothing() {
        withStore(dsl -> {
            seedType(dsl, GRAPH, "String", "SCALAR");
            seedInputField(dsl, GRAPH, "FilmFilter", "rating", "String", 0, true, false, null);
            seedFieldBinding(dsl, GRAPH, "FilmFilter", "rating", "rating");
            seedFacet(dsl, GRAPH, "FilmFilter", "rating");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * An {@code ID} field declines on its type alone rather than on a co-occurring directive: it is
     * where a node-id reading can arise with nothing to point at, and a rule reading the directive
     * would have to re-derive which readings those are.
     */
    @Test
    void anIdFieldResolvesToNothingWithNoDirectiveToPointAt() {
        withStore(dsl -> {
            seedType(dsl, GRAPH, "ID", "SCALAR");
            seedInputField(dsl, GRAPH, "FilmFilter", "id", "ID", 0, false, false, null);
            seedFieldBinding(dsl, GRAPH, "FilmFilter", "id", "film_id");
            seedFacet(dsl, GRAPH, "FilmFilter", "id");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /** An input object leaf is not a grouping key, for the same reason a missing column is not. */
    @Test
    void anInputObjectLeafResolvesToNothing() {
        withStore(dsl -> {
            seedType(dsl, GRAPH, "RangeInput", "INPUT_OBJECT");
            seedInputField(dsl, GRAPH, "FilmFilter", "length", "RangeInput", 0, false, false, null);
            seedFieldBinding(dsl, GRAPH, "FilmFilter", "length", "length");
            seedFacet(dsl, GRAPH, "FilmFilter", "length");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /** A {@code @reference}-bound facet is join-mediated, which the direct-column reading does not serve. */
    @Test
    void aReferenceBoundFacetResolvesToNothing() {
        withStore(dsl -> {
            facet(dsl, "FilmFilter", "rating", "String", 0, "rating");
            seedFieldReference(dsl, GRAPH, "FilmFilter", "rating", 0);

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /** A {@code @condition}-bound facet declines for the same reason, its predicate being authored. */
    @Test
    void aConditionBoundFacetResolvesToNothing() {
        withStore(dsl -> {
            facet(dsl, "FilmFilter", "rating", "String", 0, "rating");
            seedFieldCondition(dsl, GRAPH, "FilmFilter", "rating", null);

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /** A {@code @nodeId}-bound facet routes through the node-id machinery rather than a column. */
    @Test
    void aNodeIdBoundFacetResolvesToNothing() {
        withStore(dsl -> {
            facet(dsl, "FilmFilter", "rating", "String", 0, "rating");
            seedFieldNodeId(dsl, GRAPH, "FilmFilter", "rating", null);

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * The application's owning type is an input object, which is this predicate's domain rather
     * than an arm of it. An {@code @asFacet} written on an output field is a use-keyed misuse, and
     * it declines here by having no filter input to be reached through.
     */
    @Test
    void anApplicationOnAnOutputFieldResolvesToNothing() {
        withStore(dsl -> {
            seedField(dsl, GRAPH, "Film", "rating");
            seedFieldBinding(dsl, GRAPH, "Film", "rating", "rating");
            seedFacet(dsl, GRAPH, "Film", "rating");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /** The graph partition holds: a sibling graph's applications are none of this one's. */
    @Test
    void aSiblingGraphResolvesNoneOfTheseApplications() {
        withStore(dsl -> {
            facet(dsl, "FilmFilter", "rating", "String", 0, "rating");

            assertThat(rowsIn(dsl, "other")).isEmpty();
        });
    }

    // ===== Fixture =====

    private static void withStore(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, body);
    }

    /**
     * One well-formed application: the field, its {@code @field(name:)} binding and the marker. The
     * three are separate rows in the store, and a case about an arm of the gate spells them itself
     * so it can leave exactly one of them out or change exactly one of them.
     */
    private static void facet(DSLContext dsl, String inputTypeName, String fieldName,
                              String namedType, int ordinal, String columnName) {
        seedType(dsl, GRAPH, namedType, "SCALAR");
        seedInputField(dsl, GRAPH, inputTypeName, fieldName, namedType, ordinal, false, false, null);
        seedFieldBinding(dsl, GRAPH, inputTypeName, fieldName, columnName);
        seedFacet(dsl, GRAPH, inputTypeName, fieldName);
    }

    private static Result<Record> rows(DSLContext dsl) {
        return rowsIn(dsl, GRAPH);
    }

    private static Result<Record> rowsIn(DSLContext dsl, String graphName) {
        derive(dsl);
        return dsl.select().from(INTENT_FACET_BINDING)
            .where(INTENT_FACET_BINDING.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_FACET_BINDING.TYPE_NAME, INTENT_FACET_BINDING.ORDINAL)
            .fetch();
    }

    /** {@code Type.field -> column KeyType}, a trailing {@code ?} where a key may be null. */
    private static String render(Record row) {
        return row.get(INTENT_FACET_BINDING.TYPE_NAME) + "." + row.get(INTENT_FACET_BINDING.FIELD_NAME)
            + " -> " + row.get(INTENT_FACET_BINDING.COLUMN_NAME)
            + " " + row.get(INTENT_FACET_BINDING.VALUE_TYPE_NAME)
            + (Boolean.TRUE.equals(row.get(INTENT_FACET_BINDING.VALUE_NULLABLE)) ? "?" : "");
    }
}
