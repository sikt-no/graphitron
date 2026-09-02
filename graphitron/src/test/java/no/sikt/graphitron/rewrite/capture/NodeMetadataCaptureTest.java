package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.SQL_NODE_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_NODE_METADATA;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the catalog walk writes for the node-identity constants a generated table class publishes,
 * over the fixture catalog whose classes real jOOQ codegen produced. The unit cases beside
 * {@code JooqCatalogNodeIdMetadataTest} pin the reduction over stated values; this pins that the
 * walk reaches those values from generated classes at all, and puts them at the right coordinate in
 * the right order.
 *
 * <p>The fixture catalog is the one the metadata generator decorates, so the cases here are the
 * shapes it produces: a composite key whose order is the constant's, a type id that is not the type
 * name, and a stock table beside them. What it does not produce is a malformed class, which is the
 * whole reason the defect derivation is pinned against stated rows instead.
 */
@PipelineTier
class NodeMetadataCaptureTest {

    private static final String FIXTURE_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.nodeidfixture";

    private static final String SDL = """
        type Query {
          films: [Film!]!
        }

        type Film {
          id: ID!
        }
        """;

    /**
     * The composite case, and the one property of it that cannot be recovered from anywhere else:
     * the entries are in the constant's declared order, which is the order encoded ids are built in.
     * A reader that took the table's own column order or its primary key instead would encode
     * different ids than the ones already issued.
     */
    @Test
    @DisplayName("a decorated table's constants land as a metadata row and its ordered entries")
    void aDecoratedTableStatesItsTypeIdAndItsEntriesInOrder(@TempDir Path tmp) {
        withFixtureCatalog(tmp, dsl -> {
            var row = dsl.selectFrom(SQL_NODE_METADATA)
                .where(SQL_NODE_METADATA.TABLE_NAME.eq("bar"))
                .fetchOne();
            assertThat(row).isNotNull();
            assertThat(row.getTypeIdForm()).isEqualTo("STRING");
            assertThat(row.getTypeId()).isEqualTo("Bar");
            assertThat(row.getTypeIdClass()).isNull();
            assertThat(row.getKeyColumnsForm()).isEqualTo("FIELD_ARRAY");
            assertThat(row.getKeyColumnsClass()).isNull();
            assertThat(entriesOf(dsl, "bar")).containsExactly("id_1", "id_2");
        });
    }

    /**
     * A type id the generator chose rather than derived, which is what makes the column a fact
     * about the class instead of something a reader could recompute from the table name.
     */
    @Test
    @DisplayName("a custom type id is stored as the class states it")
    void aCustomTypeIdIsStoredAsStated(@TempDir Path tmp) {
        withFixtureCatalog(tmp, dsl -> {
            assertThat(dsl.select(SQL_NODE_METADATA.TYPE_ID)
                .from(SQL_NODE_METADATA)
                .where(SQL_NODE_METADATA.TABLE_NAME.eq("shared_node"))
                .fetchOne(0, String.class))
                .as("a numeric type id distinct from any type name over this table")
                .isEqualTo("10154");
            assertThat(entriesOf(dsl, "shared_node")).containsExactly("id");
        });
    }

    /** No row means the class published nothing, which is the reading the relation's key rests on. */
    @Test
    @DisplayName("a stock table produces no metadata row at all")
    void aStockTableProducesNoRow(@TempDir Path tmp) {
        withFixtureCatalog(tmp, dsl -> {
            assertThat(dsl.fetchExists(SQL_NODE_METADATA,
                SQL_NODE_METADATA.TABLE_NAME.eq("qux"))).isFalse();
            assertThat(dsl.fetchCount(SQL_NODE_METADATA))
                .as("the fixture catalog decorates several tables and leaves others stock")
                .isPositive();
        });
    }

    /**
     * A second walk over the same coordinate refreshes rather than collides: the clearing round
     * drops the entries before their parent and both before the table they hang off, and the walk
     * re-states them. A capture that had left either relation out of the round would fail here on a
     * foreign key or a duplicate key rather than on an assertion.
     */
    @Test
    @DisplayName("a second capture over the same source restates the rows rather than colliding")
    void aSecondCaptureRestatesTheRows(@TempDir Path tmp) {
        var jooq = new JooqCatalog(FIXTURE_JOOQ_PACKAGE);
        try (var store = CapturedStore.ofCatalog(tmp, SDL, jooq)) {
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

    private static void withFixtureCatalog(Path tmp, java.util.function.Consumer<DSLContext> body) {
        try (var store = CapturedStore.ofCatalog(tmp, SDL, new JooqCatalog(FIXTURE_JOOQ_PACKAGE))) {
            body.accept(store.dsl());
        }
    }
}
