package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier proof that an ordering declared on a root multitable union is lowered onto every
 * {@code UNION ALL} branch: the rows come back in the declared order, interleaved across
 * participants rather than segregated by them, a runtime {@code direction:} reverses them, and a
 * connection pages through every row exactly once under that order.
 *
 * <p>{@code AddressOccupant = Customer | Staff}. The seed interleaves by {@code last_name}: Brown
 * (customer 5), Hillyer (staff 1), Johnson (customer 2), Jones (customer 4), Smith (customer 1),
 * Stephens (staff 2), Williams (customer 3), so a result that kept every customer before every
 * staff member, or that stayed in primary-key order, fails. It ties across participants on
 * {@code store_id} (store 1 holds customers 1, 2, 4 and staff 1; store 2 holds customers 3, 5 and
 * staff 2), and customer 1 and staff 1 share the key value 1 besides, which is what the appended
 * key-column / {@code __typename} tiebreakers are for.
 *
 * <p>{@code PagedItem = PagedA | PagedB} carries a two-column key {@code (k1, k2)}. The union
 * orders and seeks on the key columns themselves, so {@code (1, 10)} sorts after {@code (1, 3)}:
 * the order a comparison of the key's text would get wrong.
 */
@ExecutionTier
class MultiTableOrderingExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;

    private static final String OCCUPANT = """
        __typename
        ... on Customer { customerId }
        ... on Staff { staffId }
        """;

    private static final String PAGED_ITEM = """
        __typename
        ... on PagedA { name }
        ... on PagedB { name }
        """;

    /** The seed in {@code last_name} order. */
    private static final List<String> BY_LAST_NAME = List.of("C5", "S1", "C2", "C4", "C1", "S2", "C3");

    /** The seed in {@code store_id} order, ties broken by the key and then the typename. */
    private static final List<String> BY_STORE = List.of("C1", "S1", "C2", "C4", "S2", "C3", "C5");

    /** The primary-key order, the typename breaking the {@code 1 = 1} tie. */
    private static final List<String> BY_KEY = List.of("C1", "S1", "C2", "S2", "C3", "C4", "C5");

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

    // ===== The fixed ordering =====

    @Test
    void defaultOrder_returnsTheDeclaredColumnsOrderInterleavedAcrossParticipants() {
        assertThat(occupants("{ occupantsByLastName { " + OCCUPANT + " } }", "occupantsByLastName"))
            .as("last_name order, not primary-key order, with Customer and Staff rows interleaved")
            .containsExactlyElementsOf(BY_LAST_NAME);
    }

    // ===== The runtime ordering =====

    @Test
    void orderByArgumentAbsent_fallsBackToTheDefaultOrder() {
        assertThat(occupants("{ occupantsOrdered { " + OCCUPANT + " } }", "occupantsOrdered"))
            .containsExactlyElementsOf(BY_LAST_NAME);
    }

    /**
     * The reported symptom was byte-identical pages under both directions. A tie on the authored
     * column is in the seed, and the tiebreakers flip with the runtime direction, so the descending
     * result is the exact reverse of the ascending one, ties included.
     */
    @Test
    void directionDesc_isTheExactReverseOfAsc_tiesIncluded() {
        var asc = occupants("{ occupantsOrdered(orderBy: {field: STORE, direction: ASC}) { "
            + OCCUPANT + " } }", "occupantsOrdered");
        var desc = occupants("{ occupantsOrdered(orderBy: {field: STORE, direction: DESC}) { "
            + OCCUPANT + " } }", "occupantsOrdered");

        assertThat(asc).containsExactlyElementsOf(BY_STORE);
        assertThat(desc).containsExactlyElementsOf(reversed(BY_STORE));
    }

    @Test
    void namedOrder_selectsItsOwnColumns() {
        assertThat(occupants("{ occupantsOrdered(orderBy: {field: LAST_NAME, direction: DESC}) { "
            + OCCUPANT + " } }", "occupantsOrdered"))
            .containsExactlyElementsOf(reversed(BY_LAST_NAME));
    }

    /** A named order carrying a non-ascending entry is direction-locked: the runtime direction is inert. */
    @Test
    void directionLockedNamedOrder_ignoresTheRuntimeDirection() {
        // store_id ASC, last_name DESC: store 1 is Smith, Jones, Johnson, Hillyer; store 2 is
        // Williams, Stephens, Brown.
        var expected = List.of("C1", "C4", "C2", "S1", "C3", "S2", "C5");
        assertThat(occupants("{ occupantsOrdered(orderBy: {field: STORE_THEN_NAME_DESC, direction: ASC}) { "
            + OCCUPANT + " } }", "occupantsOrdered")).containsExactlyElementsOf(expected);
        assertThat(occupants("{ occupantsOrdered(orderBy: {field: STORE_THEN_NAME_DESC, direction: DESC}) { "
            + OCCUPANT + " } }", "occupantsOrdered")).containsExactlyElementsOf(expected);
    }

    /**
     * {@code @orderBy} with no {@code @defaultOrder}: the base is the participants' primary keys,
     * which the classifier maps onto the key columns rather than projecting slots for. A request
     * with no argument returns the primary-key order it always has.
     */
    @Test
    void orderByWithNoDefaultOrder_queriedWithoutTheArgument_returnsPrimaryKeyOrder() {
        assertThat(occupants("{ occupantsOrderedByKey { " + OCCUPANT + " } }", "occupantsOrderedByKey"))
            .containsExactlyElementsOf(BY_KEY);
        assertThat(occupants("{ occupantsOrderedByKey(orderBy: {field: OCCUPANT_ID, direction: DESC}) { "
            + OCCUPANT + " } }", "occupantsOrderedByKey"))
            .as("the primary-key named order reverses through the key columns")
            .containsExactlyElementsOf(reversed(BY_KEY));
    }

    @Test
    void listValuedArgument_composesTheElementsInOrder() {
        assertThat(occupants("""
            { occupantsOrderedByList(orderBy: [{field: STORE, direction: DESC}, {field: LAST_NAME}]) { %s } }
            """.formatted(OCCUPANT), "occupantsOrderedByList"))
            // store 2 by last_name (Brown, Stephens, Williams), then store 1 (Hillyer, Johnson, Jones, Smith)
            .containsExactly("C5", "S2", "C3", "S1", "C2", "C4", "C1");
    }

    // ===== The connection: the order is the cursor =====

    /**
     * Paging the whole set in pages smaller than it, under a non-primary-key order with
     * cross-participant ties, visits every row exactly once, forward and backward. This is the case
     * that fails if the seek key is composed out of step with the page order.
     */
    @Test
    void connection_pagesEveryRowExactlyOnce_forwardAndBackward() {
        for (String direction : List.of("ASC", "DESC")) {
            var expected = direction.equals("ASC") ? BY_STORE : reversed(BY_STORE);
            var args = "orderBy: {field: STORE, direction: " + direction + "}";
            assertThat(pageForward("occupantsOrderedConnection", args, 2, OCCUPANT))
                .as("forward, %s", direction)
                .containsExactlyElementsOf(expected);
            assertThat(pageBackward("occupantsOrderedConnection", args, 3, OCCUPANT))
                .as("backward, %s", direction)
                .containsExactlyElementsOf(expected);
        }
        assertThat(pageForward("occupantsOrderedConnection", "", 3, OCCUPANT))
            .as("the @defaultOrder base")
            .containsExactlyElementsOf(BY_LAST_NAME);
    }

    /**
     * {@code org_code} is the {@code org_code_domain} bigint exposed as {@code String} through a
     * converter on both participants. The numeric order (186 before 1120) is not the string order,
     * and a page after the first binds the cursor value in its seek: a slot field typed by a class
     * rather than by the column's {@code DataType} would bind a varchar against the domain there.
     */
    @Test
    void connection_overAConverterBackedOrderColumn_pagesThroughTheConverter() {
        var place = """
            __typename
            ... on ConverterCampus { campusId }
            ... on ConverterSite { siteId }
            """;
        var expected = List.of("ConverterCampus1", "ConverterSite1", "ConverterCampus2",
            "ConverterSite2", "ConverterCampus3");
        assertThat(pageForward("converterPlacesConnection", "", 2, place)).containsExactlyElementsOf(expected);
        assertThat(pageBackward("converterPlacesConnection", "", 2, place)).containsExactlyElementsOf(expected);
    }

    /** Composite-key participants: the order slot composes with the key-column tiebreaker. */
    @Test
    void connection_overCompositeKeyParticipants_composesWithTheKeyColumns() {
        var expected = List.of("B-3-1", "B-1-3", "B-1-10", "B-1-1", "A-2-1", "A-1-2", "A-1-1");
        assertThat(pageForward("pagedItemsByName", "", 2, PAGED_ITEM)).containsExactlyElementsOf(expected);
        assertThat(pageBackward("pagedItemsByName", "", 2, PAGED_ITEM)).containsExactlyElementsOf(expected);
    }

    /**
     * The implicit primary-key order over composite keys: paging forward and backward visits every
     * row exactly once in {@code (k1, k2, __typename)} order, and the forward walk ends on a page
     * reporting {@code hasNextPage: false}. PostgreSQL would order a JSON rendering of the key the
     * same way, so this covers the plain-column shape rather than reproducing the dialect defect;
     * {@code B-1-10} after {@code B-1-3} is the row a text comparison would misplace.
     */
    @Test
    void connection_overCompositeKeys_pagesInKeyOrder_forwardAndBackward() {
        var expected = List.of("A-1-1", "B-1-1", "A-1-2", "B-1-3", "B-1-10", "A-2-1", "B-3-1");
        assertThat(pageForward("pagedItems", "", 2, PAGED_ITEM)).containsExactlyElementsOf(expected);
        assertThat(pageBackward("pagedItems", "", 2, PAGED_ITEM)).containsExactlyElementsOf(expected);
    }

    // ===== The child that shares the stage-1 builder =====

    /**
     * The inline single-cardinality child over the same union shares the root list arm's stage-1
     * builder. It lowers no ordering and gains no tiebreaker, so it delivers the row it always has:
     * address 3 holds staff 1 and customer 3, and the key order picks staff 1.
     */
    @Test
    @SuppressWarnings("unchecked")
    void inlineSingleCardinalityChild_deliversTheRowItAlwaysHas() {
        var data = execute("""
            { occupantsRecordWithErrors(addressId: 3) { occupant { %s } } }
            """.formatted(OCCUPANT));
        var payload = (Map<String, Object>) data.get("occupantsRecordWithErrors");
        assertThat(label((Map<String, Object>) payload.get("occupant"))).isEqualTo("S1");
    }

    // ===== Helpers =====

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    @SuppressWarnings("unchecked")
    private List<String> occupants(String query, String field) {
        var rows = (List<Map<String, Object>>) execute(query).get(field);
        return rows.stream().map(MultiTableOrderingExecutionTest::label).toList();
    }

    /** Pages forward from the start in pages of {@code size}, collecting every node in order. */
    @SuppressWarnings("unchecked")
    private List<String> pageForward(String field, String args, int size, String selection) {
        var seen = new ArrayList<String>();
        String after = null;
        for (int guard = 0; guard < 20; guard++) {
            var arguments = "first: " + size + (after == null ? "" : ", after: \"" + after + "\"")
                + (args.isEmpty() ? "" : ", " + args);
            var connection = (Map<String, Object>) execute("{ " + field + "(" + arguments + ") {"
                + " edges { node { " + selection + " } }"
                + " pageInfo { hasNextPage endCursor } } }").get(field);
            for (var edge : (List<Map<String, Object>>) connection.get("edges")) {
                seen.add(label((Map<String, Object>) edge.get("node")));
            }
            var pageInfo = (Map<String, Object>) connection.get("pageInfo");
            if (!(Boolean) pageInfo.get("hasNextPage")) return seen;
            after = (String) pageInfo.get("endCursor");
        }
        throw new AssertionError("paging did not terminate: " + seen);
    }

    /** Pages backward from the end in pages of {@code size}, returning every node in page order. */
    @SuppressWarnings("unchecked")
    private List<String> pageBackward(String field, String args, int size, String selection) {
        var pages = new ArrayList<List<String>>();
        String before = null;
        for (int guard = 0; guard < 20; guard++) {
            var arguments = "last: " + size + (before == null ? "" : ", before: \"" + before + "\"")
                + (args.isEmpty() ? "" : ", " + args);
            var connection = (Map<String, Object>) execute("{ " + field + "(" + arguments + ") {"
                + " edges { node { " + selection + " } }"
                + " pageInfo { hasPreviousPage startCursor } } }").get(field);
            var page = new ArrayList<String>();
            for (var edge : (List<Map<String, Object>>) connection.get("edges")) {
                page.add(label((Map<String, Object>) edge.get("node")));
            }
            pages.addFirst(page);
            var pageInfo = (Map<String, Object>) connection.get("pageInfo");
            if (!(Boolean) pageInfo.get("hasPreviousPage")) {
                return pages.stream().flatMap(List::stream).toList();
            }
            before = (String) pageInfo.get("startCursor");
        }
        throw new AssertionError("paging did not terminate: " + pages);
    }

    /** {@code C<id>} / {@code S<id>} for an occupant, typename plus id for a place, the name for a paged item. */
    private static String label(Map<String, Object> node) {
        return switch ((String) node.get("__typename")) {
            case "Customer" -> "C" + node.get("customerId");
            case "Staff" -> "S" + node.get("staffId");
            case "ConverterCampus" -> "ConverterCampus" + node.get("campusId");
            case "ConverterSite" -> "ConverterSite" + node.get("siteId");
            default -> (String) node.get("name");
        };
    }

    private static List<String> reversed(List<String> list) {
        return list.reversed();
    }
}
