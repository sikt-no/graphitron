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
 * scenario {@code generated-scalar-call.graphqls} against the shared database: what needs a request.
 * A root and a child {@code @service} and a routine write each receive the components their
 * argMapping names, decoded against the schema the facade serves, a null value as nulls, and a wrong
 * value is answered as an error on the field it was given on with nothing called. A value read out of
 * an input object, as opptak's {@code input.mottakersti} is, is decoded, checked and refused at its
 * own path, one input object down as well as at the first. The services hand back what they were
 * given ({@link GeneratedScalarService}), and the routine writes it into an endorsement's note.
 *
 * <p>What a decoder accepts and refuses, and what each {@code @assertType} operator holds for, is
 * {@link no.sikt.graphitron.emit.GeneratedScalarDecodeEmitterTest}'s, which runs the generated
 * decoders and checks without a request. The one path case here steps through the connection the
 * facade generates for {@code @asConnection}, which only the served schema has.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class GeneratedScalarCallExecutionTest {

    private static final GraphQL GRAPHQL = Graphitron.newGraphQL().build();

    /** The notes the routine write cases leave behind, removed when they are done. */
    @AfterAll
    static void removeTheEndorsementsWritten() {
        TestDatabase.dsl().deleteFrom(DSL.table("film_endorsement"))
            .where(DSL.field("note", String.class).in("Film.title|Film|title|String", "<null>|<null>|<null>|<null>",
                "Query.films|Actor|films|String", "Film|<null>||<null>"))
            .execute();
    }

    @Test
    void aPathReachesTheServiceAsItsComponents() {
        assertThat(data("{ along(path: \"Film.title\") }").get("along")).isEqualTo("Film.title|Film|title|String");
    }

    @Test
    void aPathStepsThroughTheConnectionTheFacadeGenerates() {
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

    @Test
    void componentsInsideAnInputObjectReachTheServiceFromTheirOwnPaths() {
        assertThat(data("{ alongInput(input: {p: \"Query.films\", t: \"Actor\", inner: {p: \"Film.title\"}}) }")
            .get("alongInput")).isEqualTo("Query.films|Query|films|Film|Actor|String");
    }

    @Test
    void anAssertTypeOnAnInputFieldAcceptsAndRefusesNamingTheInputPath() {
        assertThat(data("{ alongInput(input: {p: \"Film\"}) }").get("alongInput"))
            .isEqualTo("Film|Film||Film|null|null");
        assertRefused("{ alongInput(input: {p: \"Film.title\"}) }", "alongInput",
            "input.p: the path's endType `String` is not `Film`");
    }

    @Test
    void aWrongValueInsideAnInputObjectIsRefusedAtItsOwnPath() {
        assertRefused("{ alongInput(input: {p: \"Film\", t: \"Nope\"}) }", "alongInput",
            "input.t: Nope is no object type of this schema");
        assertRefused("{ alongInput(input: {p: \"Film\", inner: {p: \"Film.nope\"}}) }", "alongInput",
            "input.inner.p: Film has no field nope");
    }

    @Test
    void aNullInputObjectOrANullFieldInsideOneHandsOverNulls() {
        assertThat(data("{ alongInput(input: null) }").get("alongInput")).isEqualTo("null|null|null|null|null|null");
        assertThat(data("{ alongInput(input: {p: null, t: null, inner: null}) }").get("alongInput"))
            .isEqualTo("null|null|null|null|null|null");
        assertThat(data("{ alongInput(input: {inner: {p: null}}) }").get("alongInput"))
            .isEqualTo("null|null|null|null|null|null");
        assertThat(data("{ alongInput(input: {inner: {}}) }").get("alongInput"))
            .isEqualTo("null|null|null|null|null|null");
    }

    @Test
    void aRoutineWriteReceivesComponentsReadOutOfAnInputObject() {
        var data = data("mutation { endorseAlongInput(film: 1, input: {p: \"Query.films\", t: \"Actor\","
            + " inner: {p: \"Film.title\"}}) { note } }");

        assertThat((List<Map<String, Object>>) data.get("endorseAlongInput"))
            .singleElement()
            .satisfies(endorsement -> assertThat(endorsement.get("note")).isEqualTo("Query.films|Actor|films|String"));
    }

    @Test
    void aRoutineWriteReceivesNullsForANullInputObjectOrANullFieldInsideOne() {
        var whole = data("mutation { endorseAlongInput(film: 1, input: null) { note } }");
        assertThat((List<Map<String, Object>>) whole.get("endorseAlongInput"))
            .singleElement()
            .satisfies(endorsement -> assertThat(endorsement.get("note")).isEqualTo("<null>|<null>|<null>|<null>"));

        var inner = data("mutation { endorseAlongInput(film: 1, input: {p: \"Film\", inner: null}) { note } }");
        assertThat((List<Map<String, Object>>) inner.get("endorseAlongInput"))
            .singleElement()
            .satisfies(endorsement -> assertThat(endorsement.get("note")).isEqualTo("Film|<null>||<null>"));
    }

    @Test
    void aRoutineWriteIsNotCalledWithAValueAnInputFieldsAssertTypeRefuses() {
        int before = endorsements();

        var result = execute("mutation { endorseAlongInput(film: 1, input: {p: \"Film.title\"}) { note } }");

        assertThat(result.getErrors()).anySatisfy(error ->
            assertThat(error.getMessage()).contains("input.p: the path's endType `String` is not `Film`"));
        assertThat(endorsements()).as("nothing was written").isEqualTo(before);
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
