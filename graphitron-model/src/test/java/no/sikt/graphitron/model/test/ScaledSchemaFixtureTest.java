package no.sikt.graphitron.model.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scaled fixture is a schema the generator accepts, so every relation that records something
 * wrong with a schema holds nothing for it. A gate priced over this fixture prices well-formed
 * work; a defect here would be a row no consumer schema draws.
 */
class ScaledSchemaFixtureTest {

    private static final List<String> DEFECT_RELATIONS = List.of(
        "graphitron_entry_defect",
        "graphql_schema_problem",
        "graphitron_minted_conflict",
        "intent_authored_claim_rejection",
        "rejection_validation_error",
        "rejection_validation_error_directive",
        "intent_argmapping_projection_defect",
        "intent_authored_claim_conflict",
        "intent_node_id_decode_defect",
        "intent_node_id_decode_landing_defect",
        "intent_node_id_polymorphic_decode_defect",
        "intent_type_backing_conflict");

    @TempDir
    Path tmp;

    @Test
    @DisplayName("the scaled fixture draws no defect")
    void drawsNoDefect() {
        try (var store = CapturedStore.ownStoreOfCatalogWith(tmp, CapturedStore.GRAPH,
                ScaledSchemaFixture.scaledSdl(2), CatalogInput.of(TestRunContext.of()),
                ClasspathCorpus.entries())) {
            var dsl = store.dsl();
            Map<String, Integer> nonEmpty = new LinkedHashMap<>();
            for (String relation : DEFECT_RELATIONS) {
                int rows = ((Number) dsl.fetchValue("SELECT count(*) FROM " + relation
                    + " WHERE graph_name = ?", CapturedStore.GRAPH)).intValue();
                if (rows > 0) {
                    nonEmpty.put(relation, rows);
                }
            }
            assertThat(nonEmpty).as("defect relations holding rows for the scaled fixture").isEmpty();
        }
    }
}
