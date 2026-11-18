package no.sikt.graphitron.rewrite.generated;

import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
import no.sikt.graphitron.generated.facts.connection_shared_facets.Graphitron;
import no.sikt.graphitron.generated.facts.connection_shared_facets.fetchers.QueryFetchers;
import no.sikt.graphitron.generated.facts.connection_shared_facets.util.Connections;
import no.sikt.graphitron.generated.facts.connection_shared_facets.util.Facets;
import no.sikt.graphitron.rewrite.test.db.TestDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two root producers of the fact scenario {@code connection-shared-facets.graphqls}, called
 * directly against the shared database: both build the one class their connection type names, and
 * each fills its count and its facets from its own arguments. Query.films counts and buckets every
 * film; Query.filmsById those it is handed. Every read is over the seeded films 1 to 5, whose
 * original languages are 3, 2, 2, none and 1.
 */
@ExecutionTier
class SharedFacetedConnectionExecutionTest {

    /** A selection whose immediate fields are the given names, which is all the page and the facets read. */
    private static DataFetchingFieldSelectionSet selecting(String... names) {
        List<SelectedField> selected = Arrays.stream(names)
            .map(name -> (SelectedField) Proxy.newProxyInstance(SelectedField.class.getClassLoader(),
                new Class<?>[] {SelectedField.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                }))
            .toList();
        return (DataFetchingFieldSelectionSet) Proxy.newProxyInstance(
            DataFetchingFieldSelectionSet.class.getClassLoader(), new Class<?>[] {DataFetchingFieldSelectionSet.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getImmediateFields" -> selected;
                case "getFields" -> List.of();
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static DataFetchingEnvironment env(DSLContext dsl, Map<String, Object> arguments, String... selected) {
        var input = Graphitron.newExecutionInput(dsl).query("{ films { totalCount } }").build();
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .graphQLContext(input.getGraphQLContext())
            .dataLoaderRegistry(input.getDataLoaderRegistry())
            .arguments(arguments)
            .selectionSet(selecting(selected))
            .build();
    }

    /** A facet's buckets as value and count pairs, in the order they came. */
    private static List<List<Object>> buckets(Facets.FilmsByLanguageConnectionFacets facets) {
        return facets.originalLanguageId().stream()
            .map(bucket -> Arrays.asList(bucket.value(), (Object) bucket.count()))
            .toList();
    }

    @Test
    void twoProducersOfOneConnectionType_fillItsCountAndFacetsFromTheirOwnArguments() {
        var dsl = TestDatabase.dsl();
        var seeded = new HashMap<String, Object>();
        seeded.put("filmIds", List.of(1, 2, 3, 4, 5));
        seeded.put("first", 10);

        Connections.FilmsByLanguageConnection handed = QueryFetchers.rowsFilmsById(dsl, env(dsl, seeded));
        var facetsEnv = env(dsl, seeded, "originalLanguageId");
        assertThat(handed.totalCount(facetsEnv)).isEqualTo(5);
        // Languages 2 twice, then 1 and 3 once, the NULL bucket last.
        assertThat(buckets(handed.facets(facetsEnv))).containsExactly(
            List.of(2, 2), List.of(1, 1), List.of(3, 1), Arrays.asList(null, 1));

        var two = new HashMap<String, Object>();
        two.put("filmIds", List.of(2, 3));
        two.put("first", 10);
        Connections.FilmsByLanguageConnection fewer = QueryFetchers.rowsFilmsById(dsl, env(dsl, two));
        var fewerEnv = env(dsl, two, "originalLanguageId");
        assertThat(fewer.totalCount(fewerEnv)).isEqualTo(2);
        assertThat(buckets(fewer.facets(fewerEnv))).containsExactly(List.of(2, 2));

        var all = new HashMap<String, Object>();
        all.put("first", 10);
        Connections.FilmsByLanguageConnection every = QueryFetchers.rowsFilms(dsl, env(dsl, all));
        var everyEnv = env(dsl, all, "originalLanguageId");
        assertThat(every.totalCount(everyEnv))
            .as("the other producer counts under its own arguments, every film")
            .isGreaterThanOrEqualTo(5)
            .isNotEqualTo(fewer.totalCount(fewerEnv));
    }
}
