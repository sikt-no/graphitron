package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.generatedscalar.Graphitron;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A wrong generated scalar value routed into a payload through {@code @error}, against PostgreSQL:
 * the decode at the call refuses the value with the generated client exception, its message naming
 * the argument, and the payload's {@code GENERIC} handler naming that class claims it, so the field
 * answers the refusal as data rather than as a field error, and the service is not called. Generated
 * from {@code generated-scalar.graphqls} by its own execution. A value read out of an input object is
 * refused naming the input field's path.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class GeneratedScalarErrorRoutingExecutionTest {

    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void start() {
        dsl = SakilaDatabase.dsl();
        graphql = Graphitron.newGraphQL().build();
    }

    private Map<String, Object> payload(String query) {
        return payload(query, "endType");
    }

    private Map<String, Object> payload(String query, String field) {
        var result = graphql.execute(Graphitron.newExecutionInput(dsl).query(query).build());
        assertThat(result.getErrors()).as("a claimed refusal is data, not a field error").isEmpty();
        return (Map<String, Object>) ((Map<String, Object>) result.getData()).get(field);
    }

    @Test
    void aValidPathReachesTheService() {
        var payload = payload("{ endType(path: \"Film\") { endType errors { ... on PathRefused { message } } } }");

        assertThat(payload.get("endType")).isEqualTo("Film");
        assertThat((List<Object>) payload.get("errors")).isNullOrEmpty();
    }

    @Test
    void aRefusedPathIsRoutedIntoThePayloadNamingTheArgument() {
        var payload = payload("{ endType(path: \"Film.title\") { endType errors { ... on PathRefused { path message } } } }");

        assertThat(payload.get("endType")).isNull();
        assertThat((List<Map<String, Object>>) payload.get("errors")).singleElement().satisfies(error ->
            assertThat(error.get("message")).isEqualTo("path: the path's endType `String` is not `Film`"));
    }

    @Test
    void aMalformedPathIsRoutedIntoThePayloadNamingTheArgument() {
        var payload = payload("{ endType(path: \"Film.nope\") { errors { ... on PathRefused { message } } } }");

        assertThat((List<Map<String, Object>>) payload.get("errors")).singleElement().satisfies(error ->
            assertThat(error.get("message")).isEqualTo("path: Film has no field nope"));
    }

    @Test
    void aValidPathInsideAnInputObjectReachesTheService() {
        var payload = payload("{ endTypeIn(input: {p: \"Film\"}) { endType errors { ... on PathRefused { message } } } }",
            "endTypeIn");

        assertThat(payload.get("endType")).isEqualTo("Film");
        assertThat((List<Object>) payload.get("errors")).isNullOrEmpty();
    }

    @Test
    void aRefusedPathInsideAnInputObjectIsRoutedIntoThePayloadNamingTheInputPath() {
        var payload = payload("{ endTypeIn(input: {p: \"Film.title\"}) { endType errors { ... on PathRefused { message } } } }",
            "endTypeIn");

        assertThat(payload.get("endType")).isNull();
        assertThat((List<Map<String, Object>>) payload.get("errors")).singleElement().satisfies(error ->
            assertThat(error.get("message")).isEqualTo("input.p: the path's endType `String` is not `Film`"));
    }
}
