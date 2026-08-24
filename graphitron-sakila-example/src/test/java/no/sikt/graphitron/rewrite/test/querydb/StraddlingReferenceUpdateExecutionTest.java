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
    }

    /** Inserts an item at (tenant 1, itemNo) pointing at the given catalogue code. */
    private void seedItem(int itemNo, String catalogCode) {
        dsl.insertInto(DSL.table("catalogue_item"),
                DSL.field("tenant_id"), DSL.field("item_no"),
                DSL.field("catalog_code"), DSL.field("item_name"))
            .values(TENANT_ONE, itemNo, catalogCode, "item-" + itemNo)
            .execute();
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
