package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.diagnostics.DeleteRowsError;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.model.DeleteRows;
import no.sikt.graphitron.rewrite.model.DmlWriteField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.KeyColumn;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.Returning;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.rewrite.DmlWriteReads.deleteRowsOf;
import static no.sikt.graphitron.rewrite.DmlWriteReads.updateRowsOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Classification of DML over a node whose key is the primary key plus more columns (a superkey),
 * on the shapes {@code graphitron-sakila-example}'s execution fixtures run: what the statement
 * filters on, what it drops, and what its {@code RETURNING} captures for the payload ids.
 */
@PipelineTier
class SuperkeyNodeIdDmlClassificationTest {

    private static final String SDL = """
        type Film implements Node @table(name: "film") @node { id: ID! @nodeId title: String }
        type InventoryUnit implements Node @table(name: "inventory") @node(keyColumns: ["inventory_id", "store_id"]) {
            id: ID! @nodeId
            storeId: Int! @field(name: "store_id")
        }
        type FilmCategoryNode implements Node @table(name: "film_category")
                @node(keyColumns: ["film_id", "category_id", "last_update"]) {
            id: ID! @nodeId
        }
        input InventoryUnitUpdateInput { id: ID! @nodeId(typeName: "InventoryUnit") storeId: Int @field(name: "store_id") }
        input InventoryUnitCreateInput { filmId: Int! @field(name: "film_id") storeId: Int! @field(name: "store_id") }
        input FilmCategoryNodeDeleteInput { id: ID! @nodeId(typeName: "FilmCategoryNode") }
        input FilmCategoryKeyInput { filmId: Int! @field(name: "film_id") categoryId: Int! @field(name: "category_id") }
        input FilmDeleteInput { filmId: Int! @field(name: "film_id") }
        type InventoryUnitsPayload { inventoryUnits: [InventoryUnit!] }
        type DeletedFilmCategoryNodesPayload { deletedIds: [ID!] }
        type DeletedFilmsPayload { deletedIds: [ID!] }
        type Query { x: String }
        type Mutation {
            updateInventoryUnits(in: [InventoryUnitUpdateInput!]!): InventoryUnitsPayload @mutation(typeName: UPDATE)
            createInventoryUnits(in: [InventoryUnitCreateInput!]!): InventoryUnitsPayload @mutation(typeName: INSERT)
            deleteFilmCategoryNodes(in: [FilmCategoryNodeDeleteInput!]!): DeletedFilmCategoryNodesPayload
                @mutation(typeName: DELETE, table: "film_category")
            deleteFilmCategoriesByKey(in: [FilmCategoryKeyInput!]!): DeletedFilmCategoryNodesPayload
                @mutation(typeName: DELETE, table: "film_category")
            deleteFilmCategoriesByKeyFailOnMissing(in: [FilmCategoryKeyInput!]!): DeletedFilmCategoryNodesPayload
                @mutation(typeName: DELETE, table: "film_category", failOnMissing: true)
            deleteFilms(in: [FilmDeleteInput!]!): DeletedFilmsPayload @mutation(typeName: DELETE, table: "film")
        }
        """;

    private static final GraphitronSchema SCHEMA = TestSchemaHelper.buildSchema(SDL);

    private static Returning returningOf(String field) {
        return switch (SCHEMA.field("Mutation", field)) {
            case MutationField.MutationDmlRecordField f -> f.returning();
            case MutationField.MutationBulkDmlRecordField f -> f.returning();
            default -> throw new AssertionError("not a record carrier: " + field);
        };
    }

    private static List<String> sqlNames(List<ColumnRef> columns) {
        return columns.stream().map(ColumnRef::sqlName).toList();
    }

    @Test
    void superkeyDeletePayload_capturesTheNodeKeyBeyondThePrimaryKey() {
        var returning = returningOf("deleteFilmCategoryNodes");
        assertThat(sqlNames(returning.primaryKey())).containsExactly("film_id", "category_id");
        assertThat(sqlNames(returning.nodeKeyTail())).containsExactly("last_update");

        var deleteRows = deleteRowsOf((DmlWriteField) SCHEMA.field("Mutation", "deleteFilmCategoryNodes"));
        assertThat(deleteRows.whereColumns()).extracting(k -> k.targetColumn().sqlName())
            .containsExactly("film_id", "category_id");
        assertThat(deleteRows.droppedColumns()).extracting(KeyColumn::decodeSlot).containsExactly(2);
    }

    @Test
    void superkeyDelete_isIdempotentByDefault_echoingTheDroppedColumnTheReturningCaptures() {
        var deleteRows = deleteRowsOf((DmlWriteField) SCHEMA.field("Mutation", "deleteFilmCategoryNodes"));
        var idem = (DeleteRows.IdempotentByPrimaryKey) deleteRows;
        assertThat(idem.echoBinding()).extracting(k -> k.targetColumn().sqlName()).containsExactly("last_update");
        assertThat(sqlNames(returningOf("deleteFilmCategoryNodes").nodeKeyTail()))
            .containsExactly("last_update");
    }

    @Test
    void nonSuperkeyPayloadAndEveryInsertAndUpdate_captureThePrimaryKeyAlone() {
        assertThat(returningOf("deleteFilms").nodeKeyTail()).isEmpty();
        assertThat(sqlNames(returningOf("deleteFilms").primaryKey())).containsExactly("film_id");
        // InventoryUnit's id needs store_id, but an INSERT or UPDATE payload re-reads its rows by
        // primary key, so neither captures more.
        assertThat(returningOf("updateInventoryUnits").nodeKeyTail()).isEmpty();
        assertThat(returningOf("createInventoryUnits").nodeKeyTail()).isEmpty();
    }

    @Test
    void superkeyUpdate_filtersOnThePrimaryKeyAndDropsTheRest() {
        var updateRows = updateRowsOf((DmlWriteField) SCHEMA.field("Mutation", "updateInventoryUnits"));
        assertThat(updateRows.keyColumns()).extracting(k -> k.targetColumn().sqlName()).containsExactly("inventory_id");
        assertThat(updateRows.droppedColumns()).singleElement().satisfies(kc -> {
            assertThat(kc.targetColumn().sqlName()).isEqualTo("store_id");
            assertThat(kc.decodeSlot()).isEqualTo(1);
        });
        assertThat(updateRows.setColumns()).extracting(s -> s.sdlFieldName()).containsExactly("storeId");
    }

    @Test
    void plainPrimaryKeyInputUnderASuperkeyPayload_isEchoUnencodableByDefault() {
        // A missing row's id would need last_update, which only the node id supplies.
        var f = (UnclassifiedField) SCHEMA.field("Mutation", "deleteFilmCategoriesByKey");
        assertThat(f.rejection()).isInstanceOfSatisfying(DeleteRowsError.IdempotentDeleteEchoUnencodable.class, r -> {
            assertThat(r.nodeTypeName()).isEqualTo("FilmCategoryNode");
            assertThat(sqlNames(r.unsuppliedColumns())).containsExactly("last_update");
        });
    }

    @Test
    void plainPrimaryKeyInputUnderASuperkeyPayload_failOnMissing_readsTheTailBackFromTheDeletedRow() {
        var deleteRows = deleteRowsOf((DmlWriteField) SCHEMA.field("Mutation", "deleteFilmCategoriesByKeyFailOnMissing"));
        assertThat(deleteRows).isInstanceOf(DeleteRows.FailOnMissing.class);
        assertThat(sqlNames(returningOf("deleteFilmCategoriesByKeyFailOnMissing").nodeKeyTail()))
            .containsExactly("last_update");
    }
}
