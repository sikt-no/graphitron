package no.sikt.graphitron.rewrite.generated;

import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.facts.generated_scalar_output.Graphitron;
import no.sikt.graphitron.rewrite.test.db.TestDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.test.jooq.public_.Tables.FILM;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A generated scalar as output, through the generated facade of the fact scenario
 * {@code generated-scalar-output.graphqls} against the shared database: a field of one returns what it
 * is handed as stored, whether a string column or a {@code @service} returning a {@code String}, and
 * the scalar is registered under the author's name by the store-rendered unit.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class GeneratedScalarExecutionTest {

    private static final GraphQL GRAPHQL = Graphitron.newGraphQL().build();

    @Test
    void aStringColumnIsReturnedAsStored() {
        String title = TestDatabase.dsl().select(FILM.TITLE).from(FILM).where(FILM.FILM_ID.eq(1))
            .fetchOne(FILM.TITLE);
        assertThat(title).as("the seeded catalog holds film 1").isNotNull();

        var films = (List<Map<String, Object>>) execute("{ films { id title } }").get("films");

        assertThat(films).filteredOn(film -> Integer.valueOf(1).equals(film.get("id")))
            .singleElement()
            .satisfies(film -> assertThat(film.get("title")).isEqualTo(title));
    }

    @Test
    void aServiceReturningAStringIsReturnedAsIs() {
        var data = execute("{ echo(marker: \"Soknad.saker.nodes\") }");

        assertThat(data.get("echo")).isEqualTo("Soknad.saker.nodes");
    }

    @Test
    void theScalarIsServedUnderTheAuthorsName() {
        var data = execute("{ __type(name: \"Path\") { kind name } }");

        assertThat((Map<String, Object>) data.get("__type"))
            .containsEntry("kind", "SCALAR")
            .containsEntry("name", "Path");
    }

    private static Map<String, Object> execute(String query) {
        ExecutionResult result = GRAPHQL.execute(Graphitron.newExecutionInput(TestDatabase.dsl()).query(query).build());
        assertThat(result.getErrors()).as(query).isEmpty();
        return result.getData();
    }
}
