package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.TestRunContext;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_OBJECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_TABLE;
import static no.sikt.graphitron.model.test.CapturedStore.GRAPH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The source relations refuse a subtype row of the wrong kind by their own keys and checks.
 *
 * <p>A fact document states what the writer writes, and the writer never writes these rows, so a
 * document cannot show that the database would refuse them; that is all this pins. The rows are the
 * test's own, written over a captured store whose occurrence the writer gave a table source.
 */
class OccurrenceSourceConstraintTest {

    private static final String SDL = """
        type Query { customers: [Customer!]! }
        type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
        """;

    private static final String COORDINATE = "Customer.firstName";
    private static final LocalDateTime NOW = LocalDateTime.now();

    @Test
    @DisplayName("an object row under a table source and a table row of kind OBJECT are refused")
    void aSubtypeOfTheWrongKindIsRefused(@TempDir Path tmp) {
        try (var store = CapturedStore.ownStoreOfCatalog(tmp, SDL, CatalogInput.of(TestRunContext.of()))) {
            var dsl = store.dsl();
            var t = GRAPHITRON_OCCURRENCE_SOURCE_TABLE;
            var table = dsl.select(t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME).from(t)
                .where(t.GRAPH_NAME.eq(GRAPH), t.COORDINATE.eq(COORDINATE)).fetchSingle();
            assertThat(table.value3()).as("the writer's table source the rows hang under").isEqualTo("customer");

            var b = GRAPHITRON_OCCURRENCE_SOURCE_OBJECT;
            assertRefused("Referential integrity", () -> dsl.insertInto(b)
                .set(b.GRAPH_NAME, GRAPH).set(b.COORDINATE, COORDINATE).set(b.KIND, "OBJECT")
                .set(b.CLASS_NAME, "java.lang.Object").set(b.TOUCHED_AT, NOW).execute());

            assertRefused("Check constraint", () -> dsl.insertInto(t)
                .set(t.GRAPH_NAME, GRAPH).set(t.COORDINATE, COORDINATE)
                .set(t.TABLE_SOURCE_NAME, table.value1()).set(t.TABLE_SCHEMA, table.value2())
                .set(t.TABLE_NAME, "address").set(t.KIND, "OBJECT").set(t.TOUCHED_AT, NOW).execute());
        }
    }

    private static void assertRefused(String constraint, ThrowingCallable insert) {
        assertThatThrownBy(insert)
            .isInstanceOf(IntegrityConstraintViolationException.class)
            .hasMessageContaining(constraint);
    }
}
