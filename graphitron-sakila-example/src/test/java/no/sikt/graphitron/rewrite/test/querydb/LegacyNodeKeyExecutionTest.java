package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.FetcherErrorLog;
import no.sikt.graphitron.generated.util.NodeIdEncoder;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier coverage for a node type whose key was narrowed: InventoryItem's current key is
 * {@code inventory_id} and its legacy key shape {@code (store_id, inventory_id, last_update)}, the
 * dropped columns on both sides of the kept one. An id in the legacy shape is read wherever an
 * InventoryItem id is, converted to the current shape before anything reads it, and every id in a
 * response is the current form. The legacy ids are minted here with {@link NodeIdEncoder#encode},
 * as a client holding one from before the narrowing would present it.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class LegacyNodeKeyExecutionTest {

    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        dsl = DSL.using(SakilaDatabase.url(), SakilaDatabase.user(), SakilaDatabase.password());
        graphql = Graphitron.newGraphQL().build();
    }

    private static final LocalDateTime MINTED_AT = LocalDateTime.of(2020, 1, 1, 12, 0);

    // inventory's serial lags the seed's explicit ids, so these rows take ids of their own.
    private static final AtomicInteger NEXT_INVENTORY_ID = new AtomicInteger(1_800_000_000);

    private static final class RollbackSentinel extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RollbackSentinel() { super(null, null, false, false); }
    }

    private static void inRolledBackTransaction(Consumer<DSLContext> body) {
        try {
            dsl.transaction(cfg -> {
                body.accept(DSL.using(cfg));
                throw new RollbackSentinel();
            });
        } catch (RollbackSentinel rolledBack) {
            // expected: every case ends here
        }
    }

    private static graphql.ExecutionResult run(DSLContext ctx, String query) {
        return run(ctx, query, null);
    }

    private static graphql.ExecutionResult run(DSLContext ctx, String query, FetcherErrorLog log) {
        var input = Graphitron.newExecutionInput(ctx, "{}", "test-user").query(query);
        if (log != null) {
            input.graphQLContext(b -> b.put(FetcherErrorLog.class, log));
        }
        return graphql.execute(input.build());
    }

    private static Map<String, Object> execute(DSLContext ctx, String query) {
        var result = run(ctx, query);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    private static int insertFilm(DSLContext tx) {
        return tx.insertInto(DSL.table("film"))
            .set(DSL.field("title"), "legacy-key-" + UUID.randomUUID())
            .set(DSL.field("language_id"), (short) 1)
            .returningResult(DSL.field("film_id", Integer.class))
            .fetchOne().value1();
    }

    /** An inventory row in store 1, stamped {@link #MINTED_AT}. */
    private static int insertInventory(DSLContext tx) {
        return tx.insertInto(DSL.table("inventory"))
            .set(DSL.field("inventory_id"), NEXT_INVENTORY_ID.incrementAndGet())
            .set(DSL.field("film_id"), insertFilm(tx))
            .set(DSL.field("store_id"), 1)
            .set(DSL.field("last_update"), MINTED_AT)
            .returningResult(DSL.field("inventory_id", Integer.class))
            .fetchOne().value1();
    }

    private static Integer storeOf(DSLContext tx, int inventoryId) {
        return tx.select(DSL.field("store_id", Integer.class)).from(DSL.table("inventory"))
            .where(DSL.field("inventory_id", Integer.class).eq(inventoryId))
            .fetchOne(0, Integer.class);
    }

    /** Moves the row and stamps it now, so an id minted before is stale on both dropped columns. */
    private static void touch(DSLContext tx, int inventoryId) {
        tx.update(DSL.table("inventory"))
            .set(DSL.field("store_id"), 2)
            .set(DSL.field("last_update"), LocalDateTime.of(2024, 1, 5, 9, 30))
            .where(DSL.field("inventory_id", Integer.class).eq(inventoryId)).execute();
    }

    private static String legacyId(int inventoryId) {
        return NodeIdEncoder.encode("InventoryItem", 1, inventoryId, MINTED_AT);
    }

    private static String currentId(int inventoryId) {
        return NodeIdEncoder.encodeInventoryItem(inventoryId);
    }

    // ===== The generated conversion itself =====

    @Test
    void toCurrentId_convertsALegacyIdToWhatTheCurrentEncodeProduces() {
        assertThat(NodeIdEncoder.toCurrentId(legacyId(7))).isEqualTo(currentId(7));
    }

    @Test
    void toCurrentId_leavesEveryOtherIdAsItIs() {
        assertThat(NodeIdEncoder.toCurrentId(currentId(7))).isEqualTo(currentId(7));
        assertThat(NodeIdEncoder.toCurrentId(null)).isNull();
        String unknownArity = NodeIdEncoder.encode("InventoryItem", 1, 7);
        assertThat(NodeIdEncoder.toCurrentId(unknownArity)).isEqualTo(unknownArity);
        String noLegacyShape = NodeIdEncoder.encodeInventoryUnit(7, 1);
        assertThat(NodeIdEncoder.toCurrentId(noLegacyShape)).isEqualTo(noLegacyShape);
        assertThat(NodeIdEncoder.toCurrentId("not base64!")).isEqualTo("not base64!");
    }

    @Test
    void toCurrentId_refusesALegacyIdWhoseDroppedValueIsNoValueOfItsColumn() {
        assertThat(NodeIdEncoder.toCurrentId(NodeIdEncoder.encode("InventoryItem", 1, 7, "yesterday")))
            .isNull();
        assertThat(NodeIdEncoder.toCurrentId(NodeIdEncoder.encode("InventoryItem", "one", 7, MINTED_AT)))
            .isNull();
    }

    // ===== Reads =====

    @Test
    void node_resolvesALegacyIdWhoseDroppedValuesHaveChanged_andAnswersTheCurrentId() {
        inRolledBackTransaction(tx -> {
            int inventoryId = insertInventory(tx);
            String legacy = legacyId(inventoryId);
            touch(tx, inventoryId);

            Map<String, Object> data = execute(tx, """
                { node(id: "%s") { id ... on InventoryItem { storeId } } }
                """.formatted(legacy));
            var node = (Map<String, Object>) data.get("node");
            assertThat(node.get("id")).isEqualTo(currentId(inventoryId));
            assertThat(node.get("storeId")).isEqualTo(2);

            // The contrast: InventoryUnit keeps store_id in its key, so its stale id names nothing.
            Map<String, Object> unit = execute(tx, """
                { node(id: "%s") { id } }
                """.formatted(NodeIdEncoder.encodeInventoryUnit(inventoryId, 1)));
            assertThat(unit.get("node")).isNull();
        });
    }

    @Test
    void nodes_resolvesALegacyIdAndTheCurrentIdOfOneRowAtTheirOwnPositions() {
        inRolledBackTransaction(tx -> {
            int inventoryId = insertInventory(tx);
            Map<String, Object> data = execute(tx, """
                { nodes(ids: ["%s", "%s"]) { id } }
                """.formatted(legacyId(inventoryId), currentId(inventoryId)));
            var nodes = (List<Map<String, Object>>) data.get("nodes");
            assertThat(nodes).extracting(n -> n.get("id"))
                .containsExactly(currentId(inventoryId), currentId(inventoryId));
        });
    }

    @Test
    void aNodeIdFilterArgument_readsALegacyId() {
        inRolledBackTransaction(tx -> {
            int inventoryId = insertInventory(tx);
            Map<String, Object> data = execute(tx, """
                { inventoryItemsByIds(ids: ["%s"]) { id } }
                """.formatted(legacyId(inventoryId)));
            assertThat((List<Map<String, Object>>) data.get("inventoryItemsByIds"))
                .extracting(i -> i.get("id")).containsExactly(currentId(inventoryId));
        });
    }

    @Test
    void aNodeIdReferenceInputField_readsALegacyId() {
        inRolledBackTransaction(tx -> {
            int inventoryId = insertInventory(tx);
            int rentalId = tx.insertInto(DSL.table("rental"))
                .set(DSL.field("rental_date"), MINTED_AT)
                .set(DSL.field("inventory_id"), inventoryId)
                .set(DSL.field("customer_id"), tx.select(DSL.min(DSL.field("customer_id", Integer.class)))
                    .from(DSL.table("customer")).fetchOne().value1())
                .set(DSL.field("staff_id"), tx.select(DSL.min(DSL.field("staff_id", Integer.class)))
                    .from(DSL.table("staff")).fetchOne().value1())
                .returningResult(DSL.field("rental_id", Integer.class))
                .fetchOne().value1();
            touch(tx, inventoryId);

            Map<String, Object> data = execute(tx, """
                { rentalsByInventoryItem(filter: { inventoryItem: "%s" }) { rentalId inventoryId } }
                """.formatted(legacyId(inventoryId)));
            var rentals = (List<Map<String, Object>>) data.get("rentalsByInventoryItem");
            assertThat(rentals).singleElement().satisfies(r -> {
                assertThat(r.get("rentalId")).isEqualTo(rentalId);
                assertThat(r.get("inventoryId")).isEqualTo(inventoryId);
            });
        });
    }

    @Test
    void aLegacyIdWithAnUnparseableDroppedValue_isRejectedAsMalformed() {
        inRolledBackTransaction(tx -> {
            int inventoryId = insertInventory(tx);
            String broken = NodeIdEncoder.encode("InventoryItem", 1, inventoryId, "yesterday");
            Map<String, Object> data = execute(tx, """
                { node(id: "%s") { id } }
                """.formatted(broken));
            assertThat(data.get("node")).isNull();
        });
    }

    // ===== Writes =====

    @Test
    void updateInventoryItem_byALegacyId_updatesTheRowAndAnswersTheCurrentId() {
        inRolledBackTransaction(tx -> {
            int inventoryId = insertInventory(tx);
            String legacy = legacyId(inventoryId);
            touch(tx, inventoryId);

            Map<String, Object> data = execute(tx, """
                mutation { updateInventoryItem(in: { id: "%s", storeId: 1 }) { inventoryItem { id storeId } } }
                """.formatted(legacy));
            var item = (Map<String, Object>) ((Map<String, Object>) data.get("updateInventoryItem")).get("inventoryItem");
            assertThat(item.get("id")).isEqualTo(currentId(inventoryId));
            assertThat(item.get("storeId")).isEqualTo(1);
            assertThat(storeOf(tx, inventoryId)).isEqualTo(1);
        });
    }

    @Test
    void updateInventoryItems_bulkByLegacyIds_updatesEveryRowAndAnswersCurrentIds() {
        inRolledBackTransaction(tx -> {
            int first = insertInventory(tx);
            int second = insertInventory(tx);
            Map<String, Object> data = execute(tx, """
                mutation {
                    updateInventoryItems(in: [{ id: "%s", storeId: 2 }, { id: "%s", storeId: 2 }]) {
                        inventoryItems { id }
                    }
                }
                """.formatted(legacyId(second), currentId(first)));
            var items = (List<Map<String, Object>>) ((Map<String, Object>) data.get("updateInventoryItems")).get("inventoryItems");
            assertThat(items).extracting(i -> i.get("id")).containsExactly(currentId(second), currentId(first));
            assertThat(storeOf(tx, first)).isEqualTo(2);
            assertThat(storeOf(tx, second)).isEqualTo(2);
        });
    }

    @Test
    void updateInventoryItemsDirect_aRowsLegacyAndCurrentIds_failOnTheDuplicateKeyGuard() {
        inRolledBackTransaction(tx -> {
            int inventoryId = insertInventory(tx);
            var logged = new CopyOnWriteArrayList<Throwable>();
            var result = run(tx, """
                mutation {
                    updateInventoryItemsDirect(in: [{ id: "%s", storeId: 2 }, { id: "%s", storeId: 2 }]) { id }
                }
                """.formatted(legacyId(inventoryId), currentId(inventoryId)),
                (thrown, reference, env) -> logged.add(thrown));
            assertThat(result.getErrors()).isNotEmpty();
            assertThat(logged).singleElement()
                .satisfies(t -> assertThat(t.getMessage()).contains("duplicate"));
            assertThat(storeOf(tx, inventoryId)).isEqualTo(1);
        });
    }

    @Test
    void deleteInventoryItem_byALegacyId_deletesTheRowAndAnswersTheCurrentId() {
        inRolledBackTransaction(tx -> {
            int inventoryId = insertInventory(tx);
            String legacy = legacyId(inventoryId);
            touch(tx, inventoryId);

            Map<String, Object> data = execute(tx, """
                mutation { deleteInventoryItem(in: { id: "%s" }) { deletedId } }
                """.formatted(legacy));
            assertThat(((Map<String, Object>) data.get("deleteInventoryItem")).get("deletedId"))
                .isEqualTo(currentId(inventoryId));
            assertThat(storeOf(tx, inventoryId)).isNull();
        });
    }

    @Test
    void deleteInventoryItems_bulkByLegacyIds_deletesEveryRowAndAnswersCurrentIds() {
        inRolledBackTransaction(tx -> {
            int first = insertInventory(tx);
            int second = insertInventory(tx);
            Map<String, Object> data = execute(tx, """
                mutation { deleteInventoryItems(in: [{ id: "%s" }, { id: "%s" }]) { deletedIds } }
                """.formatted(legacyId(first), legacyId(second)));
            assertThat((List<String>) ((Map<String, Object>) data.get("deleteInventoryItems")).get("deletedIds"))
                .containsExactlyInAnyOrder(currentId(first), currentId(second));
            assertThat(storeOf(tx, first)).isNull();
            assertThat(storeOf(tx, second)).isNull();
        });
    }

    @Test
    void deleteInventoryItemsMissingAsDeleted_echoesAMissingLegacyIdInTheCurrentShape() {
        inRolledBackTransaction(tx -> {
            int present = insertInventory(tx);
            int missing = NEXT_INVENTORY_ID.incrementAndGet();
            Map<String, Object> data = execute(tx, """
                mutation { deleteInventoryItemsMissingAsDeleted(in: [{ id: "%s" }, { id: "%s" }]) { deletedIds } }
                """.formatted(legacyId(present), legacyId(missing)));
            assertThat((List<String>) ((Map<String, Object>) data.get("deleteInventoryItemsMissingAsDeleted")).get("deletedIds"))
                .containsExactly(currentId(present), currentId(missing));
        });
    }

    // ===== The record path, which calls decodeColumns with no decode helper in front of it =====

    @Test
    void aJooqRecordServiceInput_decodesALegacyId() {
        Map<String, Object> data = execute(dsl, """
            mutation { assignInventoryRecord(in: { inventory: "%s" }) }
            """.formatted(legacyId(42)));
        assertThat(data.get("assignInventoryRecord")).isEqualTo("inventory:42");
    }
}
