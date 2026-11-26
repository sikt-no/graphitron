package no.sikt.graphitron.model.capture;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.TestRunContext;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_EXTRACTION;
import static no.sikt.graphitron.model.test.TestRunContext.JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A node id's argMapping edited from naming its key column to naming none, read again warm into the
 * store the first reading filled. The build defect rule refuses a call parameter a node id reaches no
 * way by its missing graphitron_occurrence_binding_extraction row, so the second reading has to sweep
 * the row the first one wrote before the rule runs, or the stale row hides the refusal: the schema is
 * valid, then broken, and the defect must appear.
 *
 * <p>A fact document is read once, and the failure lives only in a second reading of a filled store,
 * the dev loop's case; so this captures twice, under a graph name of its own, into the thread's
 * shared store.
 */
class NodeIdExtractionRecaptureTest {

    private static final String GRAPH_NAME = "NodeIdExtractionRecaptureTest";

    private static final String NAMED = """
        interface Node { id: ID! }
        type Customer implements Node @table(name: "customer") @node { id: ID! }
        type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
        type Query { rental: Rental }
        type Mutation {
          rentFilm(inventoryId: Int!, customerId: ID! @nodeId(typeName: "Customer")): [Rental!]!
            @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId.customer_id")
            @reference(path: [{table: "rental"}])
        }
        """;

    private static final String UNNAMED = NAMED.replace("customerId.customer_id\"", "customerId\"");

    @Test
    @DisplayName("an argMapping edited to name no key column is refused when read again warm")
    void anEditToNameNoColumnIsRefusedWhenReadAgainWarm(@TempDir Path tmp) {
        var jooq = new CatalogInput(JOOQ_PACKAGE, TestRunContext.of().codegenLoader());
        try (var captured = CapturedStore.ofCatalog(tmp, GRAPH_NAME, NAMED, jooq)) {
            assertThat(extractions(captured.dsl()))
                .as("the named column reaches the parameter off the decoded record")
                .containsExactly("NODE_ID_RECORD customer_id");
            assertThat(codes(captured.dsl())).as("the valid schema draws no refusal").isEmpty();

            captured.recaptureCatalog(UNNAMED, jooq);
            assertThat(extractions(captured.dsl()))
                .as("the edit's binding reaches its parameter no way, the first reading's row swept")
                .isEmpty();
            assertThat(codes(captured.dsl()))
                .as("the refusal the stale row would have hidden")
                .containsExactly("NODE_ID_COLUMN_UNNAMED");
        }
    }

    /** The customer id's extraction at its parameter, the kind and component. */
    private static List<String> extractions(DSLContext dsl) {
        var e = GRAPHITRON_OCCURRENCE_BINDING_EXTRACTION;
        return dsl.select(e.EXTRACTION, e.COMPONENT).from(e)
            .where(e.GRAPH_NAME.eq(GRAPH_NAME), e.COORDINATE.eq("Mutation.rentFilm(customerId:)"))
            .fetch(r -> r.value1() + " " + r.value2());
    }

    /** The node-id refusals the graph holds. */
    private static List<String> codes(DSLContext dsl) {
        var d = GRAPHITRON_ENTRY_DEFECT;
        return dsl.select(d.CODE).from(d)
            .where(d.GRAPH_NAME.eq(GRAPH_NAME), d.CODE.like("NODE_ID%"))
            .fetch(d.CODE);
    }
}
