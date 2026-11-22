package no.sikt.graphitron.rewrite.generated;

import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import no.sikt.graphitron.generated.facts.generated_scalar_call.Graphitron;
import no.sikt.graphitron.rewrite.test.db.TestDatabase;
import no.sikt.graphitron.rewrite.test.services.GeneratedScalarService;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A generated scalar taken at a call by its components, through the generated facade of the fact
 * scenario {@code generated-scalar-call.graphqls} against the shared database: the value is decoded
 * against the schema the facade serves and checked against the argument's requirements before the
 * call, a root and a child {@code @service} and a routine write each receive the components their
 * argMapping names, and a wrong value is refused at the argument with nothing called. The services
 * hand back what they were given ({@link GeneratedScalarService}), and the routine writes it into an
 * endorsement's note.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class GeneratedScalarCallExecutionTest {

    private static final GraphQL GRAPHQL = Graphitron.newGraphQL().build();

    /** The notes the routine write cases leave behind, removed when they are done. */
    @AfterAll
    static void removeTheEndorsementsWritten() {
        TestDatabase.dsl().deleteFrom(DSL.table("film_endorsement"))
            .where(DSL.field("note", String.class).in("Film.title|Film|title|String", "<null>|<null>|<null>|<null>"))
            .execute();
    }

    @Test
    void aPathReachesTheServiceAsItsComponents() {
        assertThat(data("{ along(path: \"Film.title\") }").get("along")).isEqualTo("Film.title|Film|title|String");
    }

    @Test
    void aPathOfNoStepsHandsOverTheEmptyStepsAndEndsWhereItStarts() {
        assertThat(data("{ along(path: \"Film\") }").get("along")).isEqualTo("Film|Film||Film");
    }

    @Test
    void aPathStepsThroughAConnectionAndMayEndAtAUnion() {
        assertThat(data("{ along(path: \"Query.occupants.edges.node\") }").get("along"))
            .isEqualTo("Query.occupants.edges.node|Query|occupants.edges.node|Occupant");
    }

    @Test
    void aNullPathHandsOverNullInEveryComponent() {
        assertThat(data("{ along(path: null) }").get("along")).isEqualTo("null|null|null|null");
    }

    @Test
    void anotherComponentReachesAnotherService() {
        assertThat(data("{ startOf(path: \"Actor.firstName\") }").get("startOf")).isEqualTo("Actor");
    }

    @Test
    void aMalformedPathIsRefusedAtTheArgument() {
        assertRefused("{ along(path: \"Nope.title\") }", "along",
            "path: Nope is no object or interface type of this schema");
        assertRefused("{ along(path: \"Film.nope\") }", "along", "path: Film has no field nope");
        assertRefused("{ along(path: \"Query.occupants.edges.node.firstName\") }", "along",
            "path: Occupant has no fields, so the path cannot step to firstName");
        assertRefused("{ along(path: \"Film..title\") }", "along", "is not a path");
    }

    @Test
    void anImplementsRequirementAcceptsAndRefuses() {
        assertThat(data("{ alongNode(path: \"Film.title\") }").get("alongNode")).isEqualTo("Film.title|Film|title|String");
        assertRefused("{ alongNode(path: \"Actor.firstName\") }", "alongNode",
            "path: the path's startType `Actor` does not implement `Node`");
    }

    @Test
    void anIsRequirementAcceptsAndRefuses() {
        assertThat(data("{ alongFilm(path: \"Film\") }").get("alongFilm")).isEqualTo("Film|Film||Film");
        assertRefused("{ alongFilm(path: \"Actor\") }", "alongFilm", "path: the path's endType `Actor` is not `Film`");
    }

    @Test
    void anAnyOfRequirementAcceptsAndRefuses() {
        assertThat(data("{ alongEither(path: \"Actor\") }").get("alongEither")).isEqualTo("Actor|Actor||Actor");
        assertRefused("{ alongEither(path: \"Film.title\") }", "alongEither",
            "path: the path's endType `String` does not meet {anyOf: [{is: \"Film\"}, {is: \"Actor\"}]}");
    }

    @Test
    void aNotRequirementAcceptsAndRefuses() {
        assertThat(data("{ alongNotNode(path: \"Actor\") }").get("alongNotNode")).isEqualTo("Actor|Actor||Actor");
        assertRefused("{ alongNotNode(path: \"Film\") }", "alongNotNode",
            "path: the path's startType `Film` does not meet {not: {implements: \"Node\"}}");
    }

    @Test
    void anAnyOfInsideAnAllOfAcceptsAndRefuses() {
        assertThat(data("{ alongNested(path: \"Film\") }").get("alongNested")).isEqualTo("Film|Film||Film");
        assertRefused("{ alongNested(path: \"Actor\") }", "alongNested", "path: the path's endType `Actor` does not meet");
    }

    @Test
    void aTypeNameReachesTheServiceAsItsName() {
        assertThat(data("{ named(type: \"Film\") }").get("named")).isEqualTo("Film");
        assertRefused("{ named(type: \"Nope\") }", "named", "type: Nope is no object type of this schema");
    }

    @Test
    void aRoutineWriteReceivesTheComponents() {
        var data = data("mutation { endorseAlong(film: 1, path: \"Film.title\") { note } }");

        assertThat((List<Map<String, Object>>) data.get("endorseAlong"))
            .singleElement()
            .satisfies(endorsement -> assertThat(endorsement.get("note")).isEqualTo("Film.title|Film|title|String"));
    }

    @Test
    void aRoutineWriteReceivesNullComponentsForANullPath() {
        var data = data("mutation { endorseAlong(film: 1, path: null) { note } }");

        assertThat((List<Map<String, Object>>) data.get("endorseAlong"))
            .singleElement()
            .satisfies(endorsement -> assertThat(endorsement.get("note")).isEqualTo("<null>|<null>|<null>|<null>"));
    }

    @Test
    void aRoutineWriteIsNotCalledWithAWrongPath() {
        int before = endorsements();

        var result = execute("mutation { endorseAlong(film: 1, path: \"Film.nope\") { note } }");

        assertThat(result.getErrors())
            .anySatisfy(error -> assertThat(error.getMessage()).contains("path: Film has no field nope"));
        assertThat(endorsements()).as("nothing was written").isEqualTo(before);
    }

    @Test
    void aChildServiceRefusesTheFieldItWasGivenAWrongValueOnAndServesTheOther() {
        var result = execute("{ films(filmId: 1) { served: ends(path: \"Film\") refused: ends(path: \"Actor\") } }");

        var films = (List<Map<String, Object>>) ((Map<String, Object>) result.getData()).get("films");
        assertThat(films).singleElement().satisfies(film -> {
            assertThat(film.get("served")).isEqualTo("Film->Film");
            assertThat(film.get("refused")).isNull();
        });
        assertThat(result.getErrors()).singleElement().satisfies(error -> {
            assertThat(error.getMessage()).contains("path: the path's endType `Actor` is not `Film`");
            assertThat(error.getPath()).containsExactly("films", 0, "refused");
        });
    }

    private static void assertRefused(String query, String field, String message) {
        var result = execute(query);
        assertThat(((Map<String, Object>) result.getData()).get(field)).as(query).isNull();
        assertThat(result.getErrors()).as(query).singleElement()
            .satisfies(error -> {
                assertThat(error.getMessage()).contains(message);
                assertThat(error.getPath()).containsExactly(field);
            });
    }

    private static int endorsements() {
        return TestDatabase.dsl().fetchCount(DSL.table("film_endorsement"));
    }

    private static Map<String, Object> data(String query) {
        var result = execute(query);
        assertThat(result.getErrors()).as(query).extracting(GraphQLError::getMessage).isEmpty();
        return result.getData();
    }

    private static ExecutionResult execute(String query) {
        return GRAPHQL.execute(Graphitron.newExecutionInput(TestDatabase.dsl()).query(query).build());
    }
}
