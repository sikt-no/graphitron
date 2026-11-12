package no.sikt.graphitron.rewrite.test.querydb;

import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
import no.sikt.graphitron.generated.util.ConnectionHelper;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.jooq.public_.enums.MpaaRating;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.Condition;
import org.jooq.Field;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.test.jooq.public_.Tables.FILM;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generated {@code ConnectionHelper.facetBuckets} called directly against the seeded films,
 * the bucketing a faceted connection's facets closure runs: each facet counts under the base
 * condition and every other facet's condition but not its own, the buckets come back count first
 * and then in the value's own order with the {@code NULL} bucket last, and a facet whose value is
 * not nullable drops that bucket. Every statement is restricted to the seeded films 1 to 5, whose
 * ratings are PG, G, NC-17, G, PG and whose original languages are 3, 2, 2, none and 1.
 */
@ExecutionTier
class FacetBucketsExecutionTest {

    private static final Condition SEEDED = FILM.FILM_ID.between(1, 5);

    private static ConnectionHelper.FacetSpec facet(String label, Field<?> column, boolean valueNullable) {
        return new ConnectionHelper.FacetSpec(label, FILM, column, valueNullable, new Field<?>[0]);
    }

    /** An environment whose selection holds the given immediate fields, which is all the bucketing reads. */
    private static DataFetchingEnvironment selecting(String... fields) {
        List<SelectedField> selected = Arrays.stream(fields)
            .map(name -> (SelectedField) Proxy.newProxyInstance(SelectedField.class.getClassLoader(),
                new Class<?>[] {SelectedField.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                }))
            .toList();
        var selectionSet = (DataFetchingFieldSelectionSet) Proxy.newProxyInstance(
            DataFetchingFieldSelectionSet.class.getClassLoader(), new Class<?>[] {DataFetchingFieldSelectionSet.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getImmediateFields" -> selected;
                default -> throw new UnsupportedOperationException(method.getName());
            });
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment().selectionSet(selectionSet).build();
    }

    private static List<List<Object>> buckets(Map<String, List<Map<String, Object>>> out, String label) {
        return out.get(label).stream().map(e -> Arrays.asList(e.get("value"), e.get("count"))).toList();
    }

    @Test
    void bucketsComeCountFirstThenInTheValuesOrder() {
        var out = ConnectionHelper.facetBuckets(SakilaDatabase.dsl(), selecting("rating"), SEEDED,
            Map.of(), List.of(facet("rating", FILM.RATING, false)));

        // G and PG tie on two; the enum's declaration order puts G first.
        assertThat(buckets(out, "rating")).containsExactly(
            List.of(MpaaRating.G, 2), List.of(MpaaRating.PG, 2), List.of(MpaaRating.NC_17, 1));
    }

    @Test
    void eachFacetCountsUnderEveryOtherFacetsConditionButNotItsOwn() {
        var conditions = new LinkedHashMap<String, Condition>();
        conditions.put("rating", FILM.RATING.in(MpaaRating.G, MpaaRating.PG));
        conditions.put("originalLanguage", FILM.ORIGINAL_LANGUAGE_ID.eq(2));
        var out = ConnectionHelper.facetBuckets(SakilaDatabase.dsl(), selecting("rating", "originalLanguage"),
            SEEDED, conditions, List.of(
                facet("rating", FILM.RATING, false),
                facet("originalLanguage", FILM.ORIGINAL_LANGUAGE_ID, true)));

        // rating, under original language 2 only: films 2 (G) and 3 (NC-17).
        assertThat(buckets(out, "rating")).containsExactly(
            List.of(MpaaRating.G, 1), List.of(MpaaRating.NC_17, 1));
        // original language, under ratings G and PG only: films 1, 2, 4 and 5, the NULL bucket last.
        assertThat(buckets(out, "originalLanguage")).containsExactly(
            List.of(1, 1), List.of(2, 1), List.of(3, 1), Arrays.asList(null, 1));
    }

    @Test
    void aNonNullableFacetDropsTheNullBucket_andAnUnselectedFacetHasNoArm() {
        var out = ConnectionHelper.facetBuckets(SakilaDatabase.dsl(), selecting("originalLanguage"),
            SEEDED, Map.of(), List.of(
                facet("rating", FILM.RATING, false),
                facet("originalLanguage", FILM.ORIGINAL_LANGUAGE_ID, false)));

        assertThat(out).containsOnlyKeys("originalLanguage");
        // Films 2 and 3 share language 2; film 4's NULL is scrubbed.
        assertThat(buckets(out, "originalLanguage")).containsExactly(
            List.of(2, 2), List.of(1, 1), List.of(3, 1));
    }
}
