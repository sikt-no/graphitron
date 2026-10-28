package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.ExecutionResult;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.util.NodeIdEncoder;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code @nodeId} an {@code argMapping} opens, left out of the request: the projected key column
 * reaches its consumer as {@code null} and the request succeeds.
 *
 * <p>The defect this pins closed surfaced as a redacted internal error. The emitted SDL advertised
 * the field as optional, the decode helper returned {@code null} for a slot nobody filled, and the
 * generated fetcher read the key column straight off that null record. So every assertion here has
 * two halves: the rows are what the consumer decided absence means, and the response carries no
 * error at all, which is what says the {@code catch (Exception e)} around the field is not producing
 * one and merely wording it differently.
 *
 * <p>Three coordinates, because the optionality can sit in three places and the generator asks about
 * none of them. {@code filmsForOptionalActor} is nullable on the {@code @nodeId} leaf itself;
 * {@code filmsForOptionalActorFilter} has a non-null leaf under a nullable input object, so the
 * descent yields null one level above the node id; and {@code filmsByOptionalLanguage} hands the same
 * projected null to a {@code @condition} method rather than to a routine parameter. The supplied-id
 * case rides beside each, because a rule that returned every row would pass the absent half and mean
 * nothing.
 *
 * <p>Only absence changes. A malformed node id, and one encoded for another node type, still fail
 * the request before the routine or the condition runs, which the last two cases assert.
 *
 * <p>Every root field here reads the whole {@code film} table, which other classes write while this
 * one runs, so each case asserts over the seeded films only; the module's
 * {@code junit-platform.properties} says why the tier shares one database.
 */
@ExecutionTier
class OptionalNodeIdProjectionExecutionTest {

    /** The films {@code init.sql} seeds, in {@code film_id} order. */
    private static final List<Integer> SEEDED_FILM_IDS = List.of(1, 2, 3, 4, 5);

    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        dsl = SakilaDatabase.dsl();

        graphql = Graphitron.newGraphQL().build();
    }

    /** The nullable leaf: the filter is required, the {@code @nodeId} inside it is not. */
    @Test
    void anOmittedNullableNodeIdProjectsNullIntoARoutineParameter() {
        var omitted = filmIds(execute("""
            { filmsForOptionalActor(filter: {}, minLength: 1) { filmId } }
            """), "filmsForOptionalActor");
        var supplied = filmIds(execute("""
            { filmsForOptionalActor(filter: {actorRef: "%s"}, minLength: 1) { filmId } }
            """.formatted(actorNodeId(1))), "filmsForOptionalActor");

        assertThat(omitted)
            .as("null reaches the routine and its NULL-tolerant body reads it as every actor")
            .containsAll(supplied)
            .hasSizeGreaterThan(supplied.size());
        assertThat(supplied)
            .as("and supplying the id still narrows, so the absent case is not vacuously everything")
            .isNotEmpty();
    }

    /** Explicit null is the same absence as omission, which is what the wire-shape guard says. */
    @Test
    void anExplicitlyNullNodeIdProjectsNullToo() {
        assertThat(filmIds(execute("""
            { filmsForOptionalActor(filter: {actorRef: null}, minLength: 1) { filmId } }
            """), "filmsForOptionalActor"))
            .isEqualTo(filmIds(execute("""
                { filmsForOptionalActor(filter: {}, minLength: 1) { filmId } }
                """), "filmsForOptionalActor"));
    }

    /**
     * The nullable ancestor: the {@code @nodeId} is {@code ID!} and the input object holding it is
     * optional. Omitting the object leaves the descent with nothing at a level above the node id,
     * and the same null reaches the same parameter, which is the shape a guard conditioned on the
     * leaf's own nullability would have missed.
     */
    @Test
    void anOmittedAncestorOfANonNullNodeIdProjectsNull() {
        var omitted = filmIds(execute("""
            { filmsForOptionalActorFilter(minLength: 1) { filmId } }
            """), "filmsForOptionalActorFilter");
        var supplied = filmIds(execute("""
            { filmsForOptionalActorFilter(filter: {actorRef: "%s"}, minLength: 1) { filmId } }
            """.formatted(actorNodeId(1))), "filmsForOptionalActorFilter");

        assertThat(omitted).containsAll(supplied).hasSizeGreaterThan(supplied.size());
        assertThat(supplied).isNotEmpty();
    }

    /**
     * The {@code @condition} consumer. A field-level method is bound to the whole field, so it is
     * called once per statement whatever the client sent and the projected null is its own to read;
     * the fixture reads absence as unconstrained and returns no condition. The coordinate carries
     * {@code override: true}, so the method's answer is the only thing narrowing the rows and the
     * two cases below read it rather than an implicit predicate that would agree with it.
     */
    @Test
    void anOmittedNodeIdReachesAConditionMethodAsNull() {
        var omitted = filmIds(execute("""
            { filmsByOptionalLanguage(pick: {}) { filmId } }
            """), "filmsByOptionalLanguage");
        var english = filmIds(execute("""
            { filmsByOptionalLanguage(pick: {languageRef: "%s"}) { filmId } }
            """.formatted(languageNodeId(1))), "filmsByOptionalLanguage");
        var italian = filmIds(execute("""
            { filmsByOptionalLanguage(pick: {languageRef: "%s"}) { filmId } }
            """.formatted(languageNodeId(2))), "filmsByOptionalLanguage");

        assertThat(omitted)
            .as("the method received null and constrained nothing")
            .isEqualTo(SEEDED_FILM_IDS)
            .isEqualTo(english);
        assertThat(italian)
            .as("and it does constrain when it is handed a key, so the null arm is the method's own"
                + " answer rather than a predicate that never fired")
            .isEmpty();
    }

    /** A malformed id is still a client error: only absence changed. */
    @Test
    void aMalformedNodeIdStillFailsTheRequest() {
        assertThat(errorsOf("""
            { filmsForOptionalActor(filter: {actorRef: "not-a-node-id"}, minLength: 1) { filmId } }
            """))
            .as("the decode's own guard is untouched by the absence guard beside it")
            .isNotEmpty();
    }

    /** An id encoded for another node type is still a client error. */
    @Test
    void aForeignTypeNodeIdStillFailsTheRequest() {
        assertThat(errorsOf("""
            { filmsForOptionalActor(filter: {actorRef: "%s"}, minLength: 1) { filmId } }
            """.formatted(NodeIdEncoder.encode("LanguageNode", 1))))
            .as("the guard cannot swallow a well-formed id of the wrong type")
            .isNotEmpty();
    }

    private static String actorNodeId(int actorId) {
        return NodeIdEncoder.encode("ActorNode", actorId);
    }

    private static String languageNodeId(int languageId) {
        return NodeIdEncoder.encode("LanguageNode", languageId);
    }

    /**
     * The seeded film ids the root field returned, in the order it returned them. The fields read the
     * whole {@code film} table and other classes insert and delete films concurrently, so a row
     * outside the seed is another class's and says nothing about this one.
     */
    @SuppressWarnings("unchecked")
    private static List<Integer> filmIds(Map<String, Object> data, String field) {
        return ((List<Map<String, Object>>) data.get(field)).stream()
            .map(row -> (Integer) row.get("filmId"))
            .filter(SEEDED_FILM_IDS::contains)
            .toList();
    }

    /** Executes and asserts the response carries no error, which half of every case above is. */
    private Map<String, Object> execute(String query) {
        ExecutionResult result = run(query);
        assertThat(result.getErrors())
            .as("the redacted internal error the read used to raise is gone, not reworded")
            .isEmpty();
        return result.getData();
    }

    private List<graphql.GraphQLError> errorsOf(String query) {
        return run(query).getErrors();
    }

    private ExecutionResult run(String query) {
        ExecutionInput input =
            Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        return graphql.execute(input);
    }
}
