package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.util.NodeIdEncoder;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier coverage for a {@code @nodeId} leaf naming a {@code @table} type that is not a node
 * type. {@code StudyLevel} has no {@code implements Node}, the shape of a federation stub owned by
 * another subgraph, but its table {@code study_level} carries catalog node metadata (type id
 * {@code StudyLevel}, key {@code code}), so the id decodes against that metadata through the
 * generated {@code NodeIdEncoder.decodeStudyLevel}.
 *
 * <p>Seed data ({@code init.sql}): intervals {@code 1} (BA to MA), {@code 2} (BA to PHD) and
 * {@code 3} (MA to PHD). The update cases write interval {@code 1} and restore it afterwards.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class TableOnlyNodeIdLeafExecutionTest {

    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        dsl = SakilaDatabase.dsl();

        graphql = Graphitron.newGraphQL().build();
    }


    @AfterEach
    void restoreSeededInterval() {
        dsl.update(DSL.table("level_interval"))
            .set(DSL.field("from_level", String.class), "BA")
            .set(DSL.field("to_level", String.class), "MA")
            .where(DSL.field("interval_id", Integer.class).eq(1))
            .execute();
    }

    private ExecutionResult run(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        return graphql.execute(input);
    }

    private Map<String, Object> execute(String query) {
        var result = run(query);
        assertThat(result.getErrors()).as("graphql errors: " + result.getErrors()).isEmpty();
        return result.getData();
    }

    private static String level(String code) {
        return NodeIdEncoder.encode("StudyLevel", code);
    }

    private List<String> levelsOf(int intervalId) {
        var row = dsl.select(DSL.field("from_level", String.class), DSL.field("to_level", String.class))
            .from(DSL.table("level_interval"))
            .where(DSL.field("interval_id", Integer.class).eq(intervalId))
            .fetchOne();
        return List.of(row.value1(), row.value2());
    }

    @Test
    void update_setsBothForeignKeysFromDecodedIds() {
        Map<String, Object> data = execute("""
            mutation { updateLevelInterval(in: {intervalId: 1, fromLevelId: "%s", toLevelId: "%s"}) {
                intervalId fromLevel toLevel
            } }
            """.formatted(level("MA"), level("PHD")));

        var updated = (Map<String, Object>) data.get("updateLevelInterval");
        assertThat(updated).containsEntry("fromLevel", "MA").containsEntry("toLevel", "PHD");
        assertThat(levelsOf(1)).containsExactly("MA", "PHD");
    }

    @Test
    void update_withAWrongTypeId_failsAndWritesNothing() {
        // The same id fails the filter below with the same text: the statement fails rather than
        // writing the column or matching nothing, and the client reads why.
        var result = run("""
            mutation { updateLevelInterval(in: {intervalId: 1, fromLevelId: "%s"}) { intervalId } }
            """.formatted(NodeIdEncoder.encode("XlatParent", "MA")));

        assertThat(result.getErrors())
            .as("a wrong-type id is a malformed request, not a silent no-match")
            .anySatisfy(e -> assertThat(e.getMessage())
                .contains("Invalid node id")
                .contains("expected a StudyLevel id"));
        assertThat(levelsOf(1)).containsExactly("BA", "MA");
    }

    @Test
    void filter_returnsTheIntervalsStartingAtTheGivenLevels() {
        Map<String, Object> data = execute("""
            { levelIntervals(filter: {fromLevelIds: ["%s"]}) { intervalId } }
            """.formatted(level("BA")));

        assertThat((List<Map<String, Object>>) data.get("levelIntervals"))
            .extracting(r -> r.get("intervalId"))
            .containsExactly(1, 2);
    }

    @Test
    void filter_withAWrongTypeId_failsWithAClientError() {
        var result = run("""
            { levelIntervals(filter: {fromLevelIds: ["%s"]}) { intervalId } }
            """.formatted(NodeIdEncoder.encode("XlatParent", "BA")));

        assertThat(result.getErrors())
            .as("a wrong-type id is a malformed request, not a silent no-match")
            .anySatisfy(e -> assertThat(e.getMessage())
                .contains("Invalid node id")
                .contains("expected a StudyLevel id"));
    }
}
