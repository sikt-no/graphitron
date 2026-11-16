package no.sikt.graphitron.model.capture;

import no.sikt.graphitron.model.test.CatalogInput;

import no.sikt.graphitron.model.test.CapturedStore;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.SQL_NODE_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_NODE_METADATA;
import static org.assertj.core.api.Assertions.assertThat;
import static no.sikt.graphitron.model.test.TestRunContext.JOOQ_PACKAGE;

/**
 * What a second walk over one catalog source does to the node metadata it read: restates it. What
 * the walk writes, a decorated table's type id and its key columns in the constant's order, a type id
 * the generator chose, and no row for a stock table, is stated in the
 * {@code scenarios/catalog-node-metadata} fact document.
 *
 * <p>On a store of its own, since its subject is two captures meeting one catalog source: in a
 * store another graph had read the catalog into first, the first capture would already be a second.
 */
class NodeMetadataCaptureTest {

    private static final String SDL = """
        type Query {
          films: [Film!]!
        }

        type Film {
          id: ID!
        }
        """;

    /**
     * A second walk over the same coordinate refreshes rather than collides: the clearing round
     * drops the entries before their parent and both before the table they hang off, and the walk
     * re-states them. A capture that had left either relation out of the round would fail here on a
     * foreign key or a duplicate key rather than on an assertion.
     */
    @Test
    @DisplayName("a second capture over the same source restates the rows rather than colliding")
    void aSecondCaptureRestatesTheRows(@TempDir Path tmp) {
        var jooq = CatalogInput.of(JOOQ_PACKAGE);
        try (var store = CapturedStore.ownStoreOfCatalog(tmp, SDL, jooq)) {
            int metadata = store.dsl().fetchCount(SQL_NODE_METADATA);
            int entries = store.dsl().fetchCount(SQL_NODE_KEY_COLUMN);
            store.andCatalogGraph("second", SDL, jooq);

            assertThat(store.dsl().fetchCount(SQL_NODE_METADATA)).isEqualTo(metadata);
            assertThat(store.dsl().fetchCount(SQL_NODE_KEY_COLUMN)).isEqualTo(entries);
            assertThat(entriesOf(store.dsl(), "bar")).containsExactly("id_1", "id_2");
        }
    }

    private static List<String> entriesOf(DSLContext dsl, String tableName) {
        return dsl.select(SQL_NODE_KEY_COLUMN.COLUMN_NAME)
            .from(SQL_NODE_KEY_COLUMN)
            .where(SQL_NODE_KEY_COLUMN.TABLE_NAME.eq(tableName))
            .orderBy(SQL_NODE_KEY_COLUMN.POSITION)
            .fetch(0, String.class);
    }
}
