package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.util.NodeIdEncoder;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier coverage: a <em>straddling</em> cross-table {@code @nodeId} reference on a
 * Graphitron-owned UPDATE. Sibling to {@link SelfFkNodeIdUpdateExecutionTest}, which covers the
 * shape where the overlapping column is written; here it is identity and is not.
 *
 * <p>{@code catalogue_item} is keyed {@code (tenant_id, item_no)} and its foreign key to
 * {@code catalogue} lifts {@code (tenant_id, catalog_code)}, because a catalogue code is only unique
 * within a tenant. {@code UpdateCatalogueItemInput} carries the item's own {@code id} (covering the
 * key) plus {@code catalogueId}, whose lifted columns therefore fall on both sides of the WHERE/SET
 * boundary. The reference partitions per column: {@code catalog_code} is the only write,
 * {@code tenant_id} stays identity and is agreement-checked against the id's decoded tenant.
 *
 * <p>The pair is deliberately hostile to positional slot inference: {@code catalog_code} is the
 * <em>second</em> column of the {@code Catalogue} decode record, so an emitter reading the slot from
 * the column's position in the one-element SET partition would write the decoded tenant id into
 * {@code catalog_code}. A same-tenant re-point therefore fails loudly here without the carried slot,
 * rather than producing a subtly wrong row.
 *
 * <p>Four cases across both arms: a same-tenant re-point writes exactly {@code catalog_code} and
 * leaves {@code tenant_id} alone, and a cross-tenant input throws before any write, on the
 * single-row arm and on the bulk arm alike.
 *
 * <p>The same table carries a second straddling reference over the same tenant column,
 * {@code catalogue_item_shelf_fk} lifting {@code (tenant_id, shelf_code)}, and this one is spelled
 * {@code ID} over a nullable column. It is the clearing half of the fixture: {@code catalog_code} is
 * NOT NULL, so nothing on the first reference can exercise a clear at all. It lives on its own input
 * ({@code ShelveCatalogueItemInput}, driving the {@code shelve*} mutations) rather than beside
 * {@code catalogueId}, because {@code ID!} is mandatory on every call: an input carrying both would
 * make every clearing call resend the catalogue, which is the cost this shape exists to remove. The
 * optional spelling is admitted because {@code id} is a whole-key carrier and so supplies
 * {@code tenant_id}; the shape still refused is an optional reference that is a matched-key column's
 * <em>only</em> contributor, which is a build-time rejection with no runtime to observe.
 *
 * <p>What the clearing cases assert is the three behaviours an author expects of an optional field
 * (omitted leaves it, an id re-points it, an explicit null clears it) on all four emit consumers:
 * direct-return single-row and bulk, and payload-returning single-row and bulk, the last two of
 * which had no execution coverage of a straddling reference at all. The MATCH SIMPLE claim the
 * clear rests on is asserted rather than assumed, by deleting the shelf the item used to sit on and
 * finding the database allows it.
 *
 * <p>A fourth behaviour is asserted beside those three, because the clear is what makes it fragile:
 * an id that fails to decode still throws rather than clearing. A clearing carrier reaches its
 * decode with a non-null wire value in both cases, so the two are one refactor apart, and only the
 * database can say which one happened.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class StraddlingReferenceUpdateExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;

    // Seeded in init.sql: tenants 1 and 2, each with catalogues and items. Tenant 1 has BOOKS and
    // MEDIA; tenant 2 has BOOKS and TOOLS, so 'TOOLS' names a catalogue tenant 1 cannot reach.
    private static final int TENANT_ONE = 1;
    private static final int TENANT_TWO = 2;

    /**
     * This class's {@code catalogue_item.item_no} band under tenant 1, and the range its cleanup
     * restores. The seeded items (1, 2) stay out of it so a cleanup cannot delete fixture rows.
     */
    private static final int ITEM_NO_BAND_START = 100;
    private static final int ITEM_NO_BAND_END = 199;

    /**
     * A shelf this class creates and deletes, so the "is the reference gone" check can be a real
     * delete. The seeded shelves stay untouched, a delete of one being a fixture change rather than
     * an observation.
     */
    private static final String DISPOSABLE_SHELF = "Z9";

    @BeforeAll
    static void startDatabase() {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            var user = System.getProperty("test.db.username", "postgres");
            var pass = System.getProperty("test.db.password", "postgres");
            dsl = DSL.using(localUrl, user, pass);
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @AfterEach
    void cleanUpWrittenRows() {
        // Only this class's own band under tenant 1, so a concurrent class writing catalogue_item
        // cannot lose rows to this cleanup.
        dsl.deleteFrom(DSL.table("catalogue_item"))
            .where(DSL.field("tenant_id", Integer.class).eq(TENANT_ONE))
            .and(DSL.field("item_no", Integer.class).between(ITEM_NO_BAND_START, ITEM_NO_BAND_END))
            .execute();
        dsl.deleteFrom(DSL.table("catalogue_shelf"))
            .where(DSL.field("tenant_id", Integer.class).eq(TENANT_ONE))
            .and(DSL.field("shelf_code", String.class).eq(DISPOSABLE_SHELF))
            .execute();
    }

    /** Inserts this class's own shelf under tenant 1. */
    private void seedShelf(String shelfCode) {
        dsl.insertInto(DSL.table("catalogue_shelf"),
                DSL.field("tenant_id"), DSL.field("shelf_code"), DSL.field("shelf_name"))
            .values(TENANT_ONE, shelfCode, "disposable " + shelfCode)
            .execute();
    }

    /** Inserts an item at (tenant 1, itemNo) pointing at the given catalogue code, on no shelf. */
    private void seedItem(int itemNo, String catalogCode) {
        seedItem(itemNo, catalogCode, null);
    }

    /** The same, on a shelf of tenant 1. */
    private void seedItem(int itemNo, String catalogCode, String shelfCode) {
        dsl.insertInto(DSL.table("catalogue_item"),
                DSL.field("tenant_id"), DSL.field("item_no"),
                DSL.field("catalog_code"), DSL.field("shelf_code"), DSL.field("item_name"))
            .values(TENANT_ONE, itemNo, catalogCode, shelfCode, "item-" + itemNo)
            .execute();
    }

    private String shelfCodeOf(int itemNo) {
        return dsl.select(DSL.field("shelf_code", String.class))
            .from(DSL.table("catalogue_item"))
            .where(DSL.field("tenant_id", Integer.class).eq(TENANT_ONE))
            .and(DSL.field("item_no", Integer.class).eq(itemNo))
            .fetchOne(DSL.field("shelf_code", String.class));
    }

    /**
     * Deletes a shelf row and reports whether the database allowed it. This is what makes the
     * MATCH SIMPLE claim an observation rather than an inference: while any item still references
     * the shelf, {@code catalogue_item_shelf_fk} refuses the delete, so a successful delete after a
     * clear says the reference is genuinely absent and not merely half-written. The clear's own
     * statement having succeeded proves the constraint accepts a half-null tuple; this proves the
     * half-null tuple is not still pointing anywhere.
     */
    private boolean shelfIsUnreferenced(String shelfCode) {
        try {
            dsl.deleteFrom(DSL.table("catalogue_shelf"))
                .where(DSL.field("tenant_id", Integer.class).eq(TENANT_ONE))
                .and(DSL.field("shelf_code", String.class).eq(shelfCode))
                .execute();
            return true;
        } catch (org.jooq.exception.DataAccessException refused) {
            return false;
        }
    }

    private String catalogCodeOf(int itemNo) {
        return dsl.select(DSL.field("catalog_code", String.class))
            .from(DSL.table("catalogue_item"))
            .where(DSL.field("tenant_id", Integer.class).eq(TENANT_ONE))
            .and(DSL.field("item_no", Integer.class).eq(itemNo))
            .fetchOne(DSL.field("catalog_code", String.class));
    }

    private String itemNameOf(int itemNo) {
        return dsl.select(DSL.field("item_name", String.class))
            .from(DSL.table("catalogue_item"))
            .where(DSL.field("tenant_id", Integer.class).eq(TENANT_ONE))
            .and(DSL.field("item_no", Integer.class).eq(itemNo))
            .fetchOne(DSL.field("item_name", String.class));
    }

    /** How many rows tenant 1 has at this item_no; a re-point must never move the row across tenants. */
    private int rowCountInTenant(int tenantId, int itemNo) {
        return dsl.fetchCount(DSL.table("catalogue_item"),
            DSL.field("tenant_id", Integer.class).eq(tenantId)
                .and(DSL.field("item_no", Integer.class).eq(itemNo)));
    }

    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).as("graphql errors: " + result.getErrors()).isEmpty();
        return result.getData();
    }

    private ExecutionResult executeRaw(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        return graphql.execute(input);
    }

    @Test
    void sameTenantRepoint_writesOnlyCatalogCode() {
        // id -> CatalogueItem(1, 100) pins the row → WHERE tenant_id=1 AND item_no=100.
        // catalogueId -> Catalogue(1, 'MEDIA') lifts (tenant_id=1, catalog_code='MEDIA'). tenant_id is
        // in the key, so it is checked and not written; catalog_code is the single SET write. Reading
        // the wrong decode slot would put the decoded tenant id (1) into catalog_code instead.
        int itemNo = 100;
        seedItem(itemNo, "BOOKS");
        String self = NodeIdEncoder.encode("CatalogueItem", TENANT_ONE, itemNo);
        String catalogue = NodeIdEncoder.encode("Catalogue", TENANT_ONE, "MEDIA");

        Map<String, Object> data = execute(
            "mutation { updateCatalogueItem(in: {id: \"" + self + "\", catalogueId: \"" + catalogue
            + "\"}) { itemNo catalogCode } }");

        Map<String, Object> row = (Map<String, Object>) data.get("updateCatalogueItem");
        assertThat(row).extractingByKey("itemNo").isEqualTo(itemNo);
        assertThat(row)
            .as("the out-of-key half of the reference is written, from its own decode slot")
            .extractingByKey("catalogCode").isEqualTo("MEDIA");
        assertThat(catalogCodeOf(itemNo)).isEqualTo("MEDIA");
        assertThat(rowCountInTenant(TENANT_ONE, itemNo))
            .as("the in-key half is identity and is never written, so the row does not move")
            .isEqualTo(1);
    }

    @Test
    void crossTenantRepoint_throwsBeforeAnyWrite() {
        // id -> CatalogueItem(1, 101) → WHERE tenant_id=1. catalogueId -> Catalogue(2, 'TOOLS') decodes
        // tenant_id=2. The two disagree on tenant_id, which the FK forces equal for well-formed input,
        // so requireColumnAgreement throws before the UPDATE: catalog_code is untouched and the row
        // does not move to tenant 2.
        int itemNo = 101;
        seedItem(itemNo, "BOOKS");
        String self = NodeIdEncoder.encode("CatalogueItem", TENANT_ONE, itemNo);
        String otherTenantCatalogue = NodeIdEncoder.encode("Catalogue", TENANT_TWO, "TOOLS");

        ExecutionResult result = executeRaw(
            "mutation { updateCatalogueItem(in: {id: \"" + self + "\", catalogueId: \""
            + otherTenantCatalogue + "\", itemName: \"renamed\"}) { itemNo } }");

        // The runtime surfaces a sanitised reference rather than the throw's own text, so what is
        // pinned here is that the call failed and wrote nothing. The label naming both contributing
        // input fields rides on the generated requireColumnAgreement call; asserting it would mean
        // grepping generated source, which this corpus does not do.
        assertThat(result.getErrors())
            .as("disagreeing contributors to tenant_id must surface a value-agreement error")
            .isNotEmpty();
        Map<String, Object> data = result.getData();
        assertThat(data.get("updateCatalogueItem")).isNull();
        assertThat(catalogCodeOf(itemNo))
            .as("nothing is written when the two sides disagree").isEqualTo("BOOKS");
        assertThat(itemNameOf(itemNo))
            .as("the item_name SET is rolled back with the agreement throw").isEqualTo("item-" + itemNo);
        assertThat(rowCountInTenant(TENANT_TWO, itemNo))
            .as("the row was not moved into the other tenant").isZero();
    }

    @Test
    void bulkSameTenantRepoint_writesOnlyCatalogCodePerRow() {
        // The bulk arm builds UPDATE ... FROM (VALUES ...) AS v(...). tenant_id reaches v once from
        // the WHERE side and the straddler's checked column never enters the derived table at all, so
        // the alias names each column once; a duplicate would not run at all.
        seedItem(110, "BOOKS");
        seedItem(111, "BOOKS");
        String first = NodeIdEncoder.encode("CatalogueItem", TENANT_ONE, 110);
        String second = NodeIdEncoder.encode("CatalogueItem", TENANT_ONE, 111);
        String media = NodeIdEncoder.encode("Catalogue", TENANT_ONE, "MEDIA");

        Map<String, Object> data = execute(
            "mutation { updateCatalogueItems(in: ["
            + "{id: \"" + first + "\", catalogueId: \"" + media + "\"},"
            + "{id: \"" + second + "\", catalogueId: \"" + media + "\"}"
            + "]) { itemNo catalogCode } }");

        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("updateCatalogueItems");
        assertThat(rows).extracting(r -> r.get("itemNo")).containsExactlyInAnyOrder(110, 111);
        assertThat(rows).extracting(r -> r.get("catalogCode")).containsOnly("MEDIA");
        assertThat(catalogCodeOf(110)).isEqualTo("MEDIA");
        assertThat(catalogCodeOf(111)).isEqualTo("MEDIA");
    }

    // ===== Clearing the optional reference =====
    //
    // catalogue_item carries a second straddling reference over the same tenant column, this one
    // over a nullable column: catalogue_item_shelf_fk lifts (tenant_id, shelf_code). It is spelled
    // `shelfId: ID`, which is admitted because `id` is a whole-key carrier and so pins tenant_id.
    // The three cases below are the three an author expects of an optional field, and the fourth is
    // the cross-tenant guard the non-null sibling already has.

    @Test
    void explicitNullClearsTheOutOfKeyHalfAndLeavesEverythingElse() {
        // The claim this whole item rests on, asserted by reading the row back rather than by
        // trusting the absence of an error: shelf_code goes to NULL, tenant_id stays where the
        // predicate put it, and the foreign key is still satisfied afterwards, because a half-null
        // tuple imposes no referential obligation under MATCH SIMPLE. A DELETE of the shelf row the
        // item used to point at would fail if the reference were still live, which is what the
        // constraint check below actually exercises.
        int itemNo = 130;
        seedShelf(DISPOSABLE_SHELF);
        seedItem(itemNo, "BOOKS", DISPOSABLE_SHELF);
        String self = NodeIdEncoder.encode("CatalogueItem", TENANT_ONE, itemNo);

        Map<String, Object> data = execute(
            "mutation { shelveCatalogueItem(in: {id: \"" + self + "\", shelfId: null}) "
            + "{ itemNo catalogCode shelfCode } }");

        Map<String, Object> row = (Map<String, Object>) data.get("shelveCatalogueItem");
        assertThat(row).extractingByKey("shelfCode").isNull();
        assertThat(shelfCodeOf(itemNo)).isNull();
        assertThat(catalogCodeOf(itemNo))
            .as("the other reference's column is untouched by this one's clear").isEqualTo("BOOKS");
        assertThat(rowCountInTenant(TENANT_ONE, itemNo))
            .as("the in-key half is identity and is never written, so the row does not move")
            .isEqualTo(1);
        assertThat(shelfIsUnreferenced(DISPOSABLE_SHELF))
            .as("a half-null foreign key tuple is an absent reference, not a dangling one")
            .isTrue();
    }

    @Test
    void omittedShelfIdLeavesTheExistingValue() {
        // PATCH semantics, and the case a caller renaming one field of a wide input relies on:
        // omitting the reference is not clearing it.
        int itemNo = 131;
        seedItem(itemNo, "BOOKS", "A1");
        String self = NodeIdEncoder.encode("CatalogueItem", TENANT_ONE, itemNo);

        execute("mutation { shelveCatalogueItem(in: {id: \"" + self + "\", itemName: \"renamed\"}) "
            + "{ itemNo } }");

        assertThat(shelfCodeOf(itemNo)).isEqualTo("A1");
        assertThat(itemNameOf(itemNo)).isEqualTo("renamed");
    }

    @Test
    void sendingAShelfIdRepointsTheReference() {
        int itemNo = 132;
        seedItem(itemNo, "BOOKS", "A1");
        String self = NodeIdEncoder.encode("CatalogueItem", TENANT_ONE, itemNo);
        String shelf = NodeIdEncoder.encode("CatalogueShelf", TENANT_ONE, "B2");

        execute("mutation { shelveCatalogueItem(in: {id: \"" + self + "\", shelfId: \"" + shelf
            + "\"}) { itemNo shelfCode } }");

        assertThat(shelfCodeOf(itemNo)).isEqualTo("B2");
    }

    @Test
    void crossTenantShelf_throwsBeforeAnyWrite() {
        // The optional spelling does not weaken the agreement check: the reference still decodes a
        // tenant, and a shelf in another tenant disagrees with the one `id` pins.
        int itemNo = 133;
        seedItem(itemNo, "BOOKS", "A1");
        String self = NodeIdEncoder.encode("CatalogueItem", TENANT_ONE, itemNo);
        String otherTenantShelf = NodeIdEncoder.encode("CatalogueShelf", TENANT_TWO, "C3");

        ExecutionResult result = executeRaw(
            "mutation { shelveCatalogueItem(in: {id: \"" + self + "\", shelfId: \""
            + otherTenantShelf + "\", itemName: \"renamed\"}) { itemNo } }");

        assertThat(result.getErrors()).isNotEmpty();
        assertThat(shelfCodeOf(itemNo)).as("nothing is written when the two sides disagree").isEqualTo("A1");
        assertThat(itemNameOf(itemNo)).isEqualTo("item-" + itemNo);
    }

    @Test
    void wrongTypeShelfId_throwsRatherThanClearing() {
        // The distinction a clearing carrier's decode local exists to keep: an explicit null clears,
        // but an id that fails to decode still throws. Both reach the decode as a non-null wire value
        // that yields no record, so a decode local collapsing the two would silently clear the column
        // on a malformed request instead of refusing it. A Catalogue id in shelfId is exactly that
        // shape: a well-formed node id whose type is not the one the field expects.
        int itemNo = 134;
        seedItem(itemNo, "BOOKS", "A1");
        String self = NodeIdEncoder.encode("CatalogueItem", TENANT_ONE, itemNo);
        String notAShelf = NodeIdEncoder.encode("Catalogue", TENANT_ONE, "BOOKS");

        ExecutionResult result = executeRaw(
            "mutation { shelveCatalogueItem(in: {id: \"" + self + "\", shelfId: \"" + notAShelf
            + "\"}) { itemNo } }");

        // The pair of assertions is what discriminates, since the message itself is redacted to a
        // correlation id at the wire boundary: a collapsed decode branch would clear the column and
        // report no error at all, so an error beside an unchanged column can only be the refusal.
        // The same call with a real shelf id succeeds, two tests up, so the id's type is the only
        // thing that differs.
        assertThat(result.getErrors())
            .as("a wrong-type id is a malformed request, not a clear")
            .isNotEmpty();
        assertThat(shelfCodeOf(itemNo))
            .as("and nothing is written, least of all the null a clear would have written")
            .isEqualTo("A1");
    }

    @Test
    void bulkWrongTypeShelfId_throwsRatherThanClearing() {
        // The VALUES-join arm declares its decode local per row through its own emitter, so the same
        // distinction is a separate fact there.
        seedItem(142, "BOOKS", "A1");
        String self = NodeIdEncoder.encode("CatalogueItem", TENANT_ONE, 142);
        String notAShelf = NodeIdEncoder.encode("Catalogue", TENANT_ONE, "BOOKS");

        ExecutionResult result = executeRaw(
            "mutation { shelveCatalogueItems(in: [{id: \"" + self + "\", shelfId: \"" + notAShelf
            + "\"}]) { itemNo } }");

        assertThat(result.getErrors()).isNotEmpty();
        assertThat(shelfCodeOf(142))
            .as("the row keeps its shelf; a collapsed branch would have cleared it silently")
            .isEqualTo("A1");
    }

    @Test
    void bulkClear_clearsOneRowAndRepointsAnotherInOneStatement() {
        // The VALUES-join arm. Column membership in v is decided by first-row presence and an
        // explicitly-null key is a present key, so the two rows share one alias and one cell
        // position; a cleared row that dropped its cell would misalign the batch.
        seedItem(140, "BOOKS", "A1");
        seedItem(141, "BOOKS", "A1");
        String first = NodeIdEncoder.encode("CatalogueItem", TENANT_ONE, 140);
        String second = NodeIdEncoder.encode("CatalogueItem", TENANT_ONE, 141);
        String shelf = NodeIdEncoder.encode("CatalogueShelf", TENANT_ONE, "B2");

        Map<String, Object> data = execute(
            "mutation { shelveCatalogueItems(in: ["
            + "{id: \"" + first + "\", shelfId: \"" + shelf + "\"},"
            + "{id: \"" + second + "\", shelfId: null}"
            + "]) { itemNo shelfCode } }");

        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("shelveCatalogueItems");
        assertThat(rows).extracting(r -> r.get("itemNo")).containsExactlyInAnyOrder(140, 141);
        assertThat(shelfCodeOf(140)).isEqualTo("B2");
        assertThat(shelfCodeOf(141)).isNull();
    }

    @Test
    void payloadSingleRow_clearsThroughTheCarrierArm() {
        // The payload-returning single-row arm, which had no execution coverage of a straddling
        // reference at all. It runs the cross-partition agreement preamble and the SET map through
        // their own emitters, so the clear reaching the database here is a separate fact from the
        // direct-return arm's.
        int itemNo = 150;
        seedItem(itemNo, "BOOKS", "A1");
        String self = NodeIdEncoder.encode("CatalogueItem", TENANT_ONE, itemNo);

        Map<String, Object> data = execute(
            "mutation { shelveCatalogueItemPayload(in: {id: \"" + self + "\", shelfId: null}) "
            + "{ item { itemNo shelfCode } } }");

        Map<String, Object> payload = (Map<String, Object>) data.get("shelveCatalogueItemPayload");
        Map<String, Object> item = (Map<String, Object>) payload.get("item");
        assertThat(item).extractingByKey("shelfCode").isNull();
        assertThat(shelfCodeOf(itemNo)).isNull();
        assertThat(catalogCodeOf(itemNo)).isEqualTo("BOOKS");
        assertThat(rowCountInTenant(TENANT_ONE, itemNo)).isEqualTo(1);
    }

    @Test
    void payloadBulk_clearsPerRowThroughTheCarrierArm() {
        // The payload-returning bulk arm is a loop of single-row statements rather than the
        // VALUES-join, so the per-row clear is its own emit path again.
        seedItem(160, "BOOKS", "A1");
        seedItem(161, "BOOKS", "A1");
        String first = NodeIdEncoder.encode("CatalogueItem", TENANT_ONE, 160);
        String second = NodeIdEncoder.encode("CatalogueItem", TENANT_ONE, 161);
        String shelf = NodeIdEncoder.encode("CatalogueShelf", TENANT_ONE, "B2");

        Map<String, Object> data = execute(
            "mutation { shelveCatalogueItemsPayload(in: ["
            + "{id: \"" + first + "\", shelfId: null},"
            + "{id: \"" + second + "\", shelfId: \"" + shelf + "\"}"
            + "]) { items { itemNo shelfCode } } }");

        Map<String, Object> payload = (Map<String, Object>) data.get("shelveCatalogueItemsPayload");
        assertThat((List<?>) payload.get("items")).hasSize(2);
        assertThat(shelfCodeOf(160)).isNull();
        assertThat(shelfCodeOf(161)).isEqualTo("B2");
    }

    @Test
    void bulkCrossTenantRepoint_throwsAndRollsBackTheBatch() {
        // One row of the batch disagrees on tenant_id. The per-row agreement check throws, and the
        // batch rolls back, so the agreeing row in the same call is not written either.
        seedItem(120, "BOOKS");
        seedItem(121, "BOOKS");
        String agreeing = NodeIdEncoder.encode("CatalogueItem", TENANT_ONE, 120);
        String disagreeing = NodeIdEncoder.encode("CatalogueItem", TENANT_ONE, 121);
        String media = NodeIdEncoder.encode("Catalogue", TENANT_ONE, "MEDIA");
        String otherTenant = NodeIdEncoder.encode("Catalogue", TENANT_TWO, "TOOLS");

        ExecutionResult result = executeRaw(
            "mutation { updateCatalogueItems(in: ["
            + "{id: \"" + agreeing + "\", catalogueId: \"" + media + "\"},"
            + "{id: \"" + disagreeing + "\", catalogueId: \"" + otherTenant + "\"}"
            + "]) { itemNo } }");

        assertThat(result.getErrors())
            .as("a disagreeing row must fail the call rather than write a dangling reference")
            .isNotEmpty();
        assertThat(catalogCodeOf(120))
            .as("the agreeing row in the same batch is rolled back too").isEqualTo("BOOKS");
        assertThat(catalogCodeOf(121)).isEqualTo("BOOKS");
    }
}
