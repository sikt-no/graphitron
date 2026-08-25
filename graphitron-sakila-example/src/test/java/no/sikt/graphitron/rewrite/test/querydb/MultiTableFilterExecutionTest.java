package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier proof: a {@code @field}-mapped filter input on a root multitable union query
 * is lowered per participant and ANDed into each UNION branch's {@code WHERE}, so the query returns
 * only matching rows.
 *
 * <p>{@code AddressOccupant = Customer | Staff}; both tables carry a {@code first_name} column. The
 * test covers <em>both</em> emit paths: {@code occupantsByName} (the non-connection list form,
 * {@code buildStage1Block}, which ANDs the filter into an existing per-branch {@code WHERE}) and
 * {@code occupantsByNameConnection} (the {@code @asConnection} form,
 * {@code buildStage1ConnectionBlock}, which previously emitted no per-branch {@code WHERE} and gains
 * one). Seed data: customers include {@code Mary}; staff include {@code Mike}; the two sets do not
 * overlap, so a two-value filter isolates exactly one row per participant.
 *
 * <p>The second half of the class proves the same surface for a {@code @nodeId} argument whose node
 * type differs per participant: a bare {@code @nodeId} on a field returning the union means a
 * {@code Customer} id against {@code customer} and a {@code Staff} id against {@code staff}. Each
 * branch matches only its own ids, an id belonging to neither is still a client error naming both
 * candidates, and both root fetchers (plain and {@code @asConnection}) carry that error.
 */
@ExecutionTier
class MultiTableFilterExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;
    static final List<String> SQL_LOG = new java.util.concurrent.CopyOnWriteArrayList<>();

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
        dsl.configuration().set(new org.jooq.impl.DefaultExecuteListenerProvider(
            new org.jooq.ExecuteListener() {
                @Override
                public void executeStart(org.jooq.ExecuteContext ctx) {
                    var sql = ctx.sql();
                    if (sql != null) SQL_LOG.add(sql.toLowerCase(Locale.ROOT));
                }
            }));
        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void clearSqlLog() {
        SQL_LOG.clear();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    @Test
    @SuppressWarnings("unchecked")
    void listForm_filterMatchingOneParticipant_returnsOnlyThatRow() {
        Map<String, Object> data = execute("""
            { occupantsByName(firstName: ["Mary"]) {
                __typename
                ... on Customer { firstName }
                ... on Staff { firstName }
            } }
            """);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("occupantsByName");
        assertThat(rows)
            .as("only the Customer named Mary matches; no Staff is named Mary")
            .singleElement()
            .satisfies(r -> {
                assertThat(r.get("__typename")).isEqualTo("Customer");
                assertThat(r.get("firstName")).isEqualTo("Mary");
            });
    }

    @Test
    @SuppressWarnings("unchecked")
    void listForm_filterMatchingBothParticipants_appliesPerBranch() {
        // Mary is a customer, Mike is a staff member: the filter must narrow EACH branch by its own
        // first_name column, so exactly one row comes from each participant.
        Map<String, Object> data = execute("""
            { occupantsByName(firstName: ["Mary", "Mike"]) {
                __typename
                ... on Customer { firstName }
                ... on Staff { firstName }
            } }
            """);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("occupantsByName");
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> (String) r.get("firstName"))
            .containsExactlyInAnyOrder("Mary", "Mike");
        assertThat(rows).extracting(r -> (String) r.get("__typename"))
            .containsExactlyInAnyOrder("Customer", "Staff");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listForm_filterMatchingNoRows_returnsEmpty() {
        Map<String, Object> data = execute("""
            { occupantsByName(firstName: ["NoSuchName"]) { __typename } }
            """);
        assertThat((List<Map<String, Object>>) data.get("occupantsByName")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void nestedInputFilter_matchingBothParticipants_appliesPerBranch() {
        // The same per-participant filter delivered through an input object (`filter`) rather
        // than as a top-level argument. The branch emitter reaches the value via a self-contained
        // Map traversal (env.getArgument("filter") instanceof Map ...), so each UNION branch still
        // narrows by its own first_name column.
        Map<String, Object> data = execute("""
            { occupantsByFilter(filter: { firstNames: ["Mary", "Mike"] }) {
                __typename
                ... on Customer { firstName }
                ... on Staff { firstName }
            } }
            """);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("occupantsByFilter");
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> (String) r.get("firstName"))
            .containsExactlyInAnyOrder("Mary", "Mike");
        assertThat(rows).extracting(r -> (String) r.get("__typename"))
            .containsExactlyInAnyOrder("Customer", "Staff");
        assertThat(SQL_LOG)
            .as("the nested-input filter still lowers to a per-branch first_name predicate")
            .anyMatch(s -> s.contains("first_name") && s.contains(" in ("));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nestedInputFilter_omittedFilter_returnsAllRows() {
        // The filter input is nullable and the inner list is absent: the null-safe Map traversal
        // yields null, the condition method omits the predicate, and every occupant is returned.
        Map<String, Object> data = execute("""
            { occupantsByFilter(filter: {}) { __typename } }
            """);
        assertThat((List<Map<String, Object>>) data.get("occupantsByFilter"))
            .as("an empty filter narrows by nothing")
            .isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void idTypedFilter_coercesPerBranchAndReturnsMatchingRows() {
        // Phase a: store_id is a shared int column; the [ID!] wire Strings coerce per branch
        // through the participant column's DataType. Store 2 holds customers Linda and Elizabeth
        // and staff Jon, so the filter must narrow EACH branch by its own store_id column.
        Map<String, Object> data = execute("""
            { occupantsByStoreId(storeId: ["2"]) {
                __typename
                ... on Customer { firstName }
                ... on Staff { firstName }
            } }
            """);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("occupantsByStoreId");
        assertThat(rows).extracting(r -> (String) r.get("firstName"))
            .containsExactlyInAnyOrder("Linda", "Elizabeth", "Jon");
        assertThat(rows).extracting(r -> (String) r.get("__typename"))
            .containsExactlyInAnyOrder("Customer", "Customer", "Staff");
        assertThat(SQL_LOG)
            .as("the ID-typed filter lowers to a per-branch store_id predicate")
            .anyMatch(s -> s.contains("store_id") && s.contains(" in ("));
    }

    @Test
    @SuppressWarnings("unchecked")
    void idTypedFilter_matchingNoRows_returnsEmpty() {
        Map<String, Object> data = execute("""
            { occupantsByStoreId(storeId: ["999"]) { __typename } }
            """);
        assertThat((List<Map<String, Object>>) data.get("occupantsByStoreId")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void nestedIdTypedFilter_coercesThroughMapTraversal() {
        // Phase a: the nested [ID!] @field (OccupantFilter.storeIds) routes through a
        // JooqConvert leaf inside the self-contained Map traversal, aligned with the top-level
        // conversion semantics. Same store-2 expectation as the top-level form.
        Map<String, Object> data = execute("""
            { occupantsByFilter(filter: { storeIds: ["2"] }) {
                __typename
                ... on Customer { firstName }
                ... on Staff { firstName }
            } }
            """);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("occupantsByFilter");
        assertThat(rows).extracting(r -> (String) r.get("firstName"))
            .containsExactlyInAnyOrder("Linda", "Elizabeth", "Jon");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nodeIdFilter_decodesAndFiltersPerBranch() {
        // Phase b: an FK-target @nodeId(typeName: "Address") filter. Address 3 is customer
        // Linda's and staff Mike's address, so the decoded key must narrow EACH branch by its own
        // address_id FK column.
        String address3 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Address", 3);
        Map<String, Object> data = execute("""
            { occupantsByAddress(addressId: ["%s"]) {
                __typename
                ... on Customer { firstName }
                ... on Staff { firstName }
            } }
            """.formatted(address3));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("occupantsByAddress");
        assertThat(rows).extracting(r -> (String) r.get("firstName"))
            .containsExactlyInAnyOrder("Linda", "Mike");
        assertThat(rows).extracting(r -> (String) r.get("__typename"))
            .containsExactlyInAnyOrder("Customer", "Staff");
        assertThat(SQL_LOG)
            .as("the decoded node id lowers to a per-branch address_id predicate")
            .anyMatch(s -> s.contains("address_id") && s.contains(" in ("));
    }

    @Test
    void nodeIdFilter_wrongTypeId_surfacesClientError() {
        // An authored @nodeId filter decodes with throw-on-mismatch semantics: a well-formed id of
        // the wrong node type is a client error, not a silent empty narrowing.
        String filmId = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 1);
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user")
            .query("""
                { occupantsByAddress(addressId: ["%s"]) { __typename } }
                """.formatted(filmId))
            .build();
        var result = graphql.execute(input);
        assertThat(result.getErrors())
            .as("a wrong-type node id surfaces as a GraphQL error")
            .isNotEmpty();
        assertThat(result.getErrors().get(0).getMessage()).contains("Address");
    }

    // ===== Per-participant @nodeId dispatch =====
    //
    // A *bare* @nodeId on a field returning AddressOccupant infers a different node type per
    // participant: `Customer` on the customer branch, `Staff` on the staff branch. Each branch keeps
    // only the ids it can decode, and the fetcher rejects an id no branch decodes before stage 1
    // runs, so "no branch matched" is a client error while "this branch did not match" is a filter
    // miss. Seed data: customers Mary(1) … Elizabeth(5); staff Mike(1), Jon(2).

    private static String customerId(int id) {
        return no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", id);
    }

    private static String staffId(int id) {
        return no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Staff", id);
    }

    private static java.util.List<graphql.GraphQLError> errorsOf(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        return graphql.execute(input).getErrors();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatch_customerId_returnsTheCustomerRow() {
        // The reported repro: before dispatch, the first branch's decode-or-throw failed the whole
        // field for any id belonging to another participant.
        Map<String, Object> data = execute("""
            { occupantById(id: "%s") {
                __typename
                ... on Customer { firstName }
                ... on Staff { firstName }
            } }
            """.formatted(customerId(1)));
        Map<String, Object> row = (Map<String, Object>) data.get("occupantById");
        assertThat(row.get("__typename")).isEqualTo("Customer");
        assertThat(row.get("firstName")).isEqualTo("Mary");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatch_staffId_returnsTheStaffRow() {
        Map<String, Object> data = execute("""
            { occupantById(id: "%s") {
                __typename
                ... on Customer { firstName }
                ... on Staff { firstName }
            } }
            """.formatted(staffId(1)));
        Map<String, Object> row = (Map<String, Object>) data.get("occupantById");
        assertThat(row.get("__typename")).isEqualTo("Staff");
        assertThat(row.get("firstName")).isEqualTo("Mike");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatch_mixedIdList_returnsExactlyTheNamedRowsOfBothTypes() {
        // The primary pin: an unpruned branch would add rows here, and unlike the single-valued form
        // this cannot be masked as an order-dependent __typename. Customer Mary and staff Jon share
        // no name and no key space.
        Map<String, Object> data = execute("""
            { occupantsByIds(ids: ["%s", "%s"]) {
                __typename
                ... on Customer { firstName }
                ... on Staff { firstName }
            } }
            """.formatted(customerId(1), staffId(2)));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("occupantsByIds");
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> (String) r.get("firstName"))
            .containsExactlyInAnyOrder("Mary", "Jon");
        assertThat(rows).extracting(r -> (String) r.get("__typename"))
            .containsExactlyInAnyOrder("Customer", "Staff");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatch_idsOfOneParticipantOnly_prunesTheOtherBranch() {
        // Two customer ids and no staff id: the staff branch cannot match either, so it renders
        // false and contributes nothing rather than going unfiltered.
        Map<String, Object> data = execute("""
            { occupantsByIds(ids: ["%s", "%s"]) {
                __typename
                ... on Customer { firstName }
            } }
            """.formatted(customerId(1), customerId(3)));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("occupantsByIds");
        assertThat(rows).extracting(r -> (String) r.get("firstName"))
            .containsExactlyInAnyOrder("Mary", "Linda");
        assertThat(rows).extracting(r -> (String) r.get("__typename"))
            .containsOnly("Customer");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatch_emptyIdList_leavesBothBranchesUnfiltered() {
        // The D3 list cell's absent-vs-mismatched fold. The prune-mode helper answers null for an
        // empty wire list just as it does for an absent one, so neither branch gets a conjunct and
        // every occupant comes back, which is the shipped list-filter semantics a single-table
        // @nodeId list already has. A non-null empty return would instead mean "every element
        // mismatched" and render falseCondition on both branches, returning nothing.
        Map<String, Object> data = execute("""
            { occupantsByIds(ids: []) { __typename } }
            """);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("occupantsByIds");
        assertThat(rows)
            .as("five customers and two staff, unfiltered")
            .hasSize(7);
        assertThat(rows).extracting(r -> (String) r.get("__typename"))
            .containsOnly("Customer", "Staff");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatch_nullableArgumentAbsent_leavesTheFieldUnfiltered() {
        // The D3 nullable-scalar cell, absent half: no conjunct on either branch, so every occupant
        // comes back. A branch that read "absent" as "mismatched" would return nothing here.
        Map<String, Object> data = execute("""
            { occupantByOptionalId { __typename } }
            """);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("occupantByOptionalId");
        assertThat(rows).extracting(r -> (String) r.get("__typename"))
            .as("five customers and two staff, unfiltered")
            .containsOnly("Customer", "Staff");
        assertThat(rows).hasSize(7);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatch_nullableArgumentPresent_prunesTheNonMatchingBranch() {
        // The same cell, present half: the wire value is there, the staff branch cannot decode it,
        // and the difference between the two halves is exactly what the wire-presence guard carries.
        Map<String, Object> data = execute("""
            { occupantByOptionalId(id: "%s") {
                __typename
                ... on Customer { firstName }
            } }
            """.formatted(customerId(2)));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("occupantByOptionalId");
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.get("__typename")).isEqualTo("Customer");
            assertThat(r.get("firstName")).isEqualTo("Patricia");
        });
    }

    @Test
    void dispatch_idOfNoParticipant_surfacesClientErrorNamingTheCandidates() {
        // A Film id decodes for neither branch. Pruning every branch would page empty; the guard
        // keeps it the client error every other @nodeId argument surfaces, with the candidate set in
        // place of the single expected type.
        String filmId = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 1);
        var errors = errorsOf("""
            { occupantById(id: "%s") { __typename } }
            """.formatted(filmId));
        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0).getMessage())
            .contains("decodes to type")
            .contains("Customer")
            .contains("Staff");
    }

    @Test
    void dispatch_malformedId_surfacesTheMalformedBranchMessage() {
        var errors = errorsOf("""
            { occupantById(id: "not-a-node-id") { __typename } }
            """);
        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0).getMessage())
            .contains("not a valid id")
            .contains("Customer")
            .contains("Staff");
    }

    @Test
    void dispatch_connectionForm_idOfNoParticipant_errorsRatherThanPagingEmpty() {
        // The second root fetcher. @asConnection over a same-table @nodeId is admitted with a lint
        // advisory, its branches inherit the prune through the shared extraction, and without the
        // guard on this path the same bad id would come back as an empty page.
        String filmId = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 1);
        var errors = errorsOf("""
            { occupantsByIdsConnection(first: 5, ids: ["%s"]) { edges { node { __typename } } } }
            """.formatted(filmId));
        assertThat(errors)
            .as("a no-branch-matches id fails the field on the connection path too")
            .isNotEmpty();
        assertThat(errors.get(0).getMessage()).contains("Customer").contains("Staff");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatch_connectionForm_mixedIdList_pagesBothParticipants() {
        Map<String, Object> data = execute("""
            { occupantsByIdsConnection(first: 5, ids: ["%s", "%s"]) {
                edges { node { __typename } }
            } }
            """.formatted(customerId(1), staffId(2)));
        Map<String, Object> connection = (Map<String, Object>) data.get("occupantsByIdsConnection");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) connection.get("edges");
        assertThat(edges).hasSize(2);
        assertThat(edges).extracting(e -> (String) ((Map<String, Object>) e.get("node")).get("__typename"))
            .containsExactlyInAnyOrder("Customer", "Staff");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fieldLevelCondition_runsPerBranch() {
        // Phase c: a field-level developer @condition runs against each branch's own stage-1
        // table local. firstNameStartsWithM matches customer Mary and staff Mike.
        Map<String, Object> data = execute("""
            { occupantsStartingWithM {
                __typename
                ... on Customer { firstName }
                ... on Staff { firstName }
            } }
            """);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("occupantsStartingWithM");
        assertThat(rows).extracting(r -> (String) r.get("firstName"))
            .containsExactlyInAnyOrder("Mary", "Mike");
        assertThat(rows).extracting(r -> (String) r.get("__typename"))
            .containsExactlyInAnyOrder("Customer", "Staff");
    }

    @Test
    @SuppressWarnings("unchecked")
    void argLevelConditionOverride_replacesImplicitEquality() {
        // Phase c: @condition(override: true) suppresses the implicit first_name equality and
        // the developer prefix-match runs instead — equality on "M" would match no row, so Mary
        // and Mike coming back proves the method fired per branch.
        Map<String, Object> data = execute("""
            { occupantsByNamePrefix(firstName: "M") {
                __typename
                ... on Customer { firstName }
                ... on Staff { firstName }
            } }
            """);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("occupantsByNamePrefix");
        assertThat(rows).extracting(r -> (String) r.get("firstName"))
            .containsExactlyInAnyOrder("Mary", "Mike");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nestedInputCondition_receivesMapTraversedValue() {
        // Phase c: a nested-input developer @condition (OccupantFilter.namePrefix) receives
        // the Map-traversed value per branch. Prefix "Li" matches only customer Linda.
        Map<String, Object> data = execute("""
            { occupantsByFilter(filter: { namePrefix: "Li" }) {
                __typename
                ... on Customer { firstName }
                ... on Staff { firstName }
            } }
            """);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("occupantsByFilter");
        assertThat(rows)
            .singleElement()
            .satisfies(r -> {
                assertThat(r.get("__typename")).isEqualTo("Customer");
                assertThat(r.get("firstName")).isEqualTo("Linda");
            });
    }

    @Test
    @SuppressWarnings("unchecked")
    void connectionForm_filterApplied_returnsOnlyMatchingNodes() {
        Map<String, Object> data = execute("""
            { occupantsByNameConnection(firstName: ["Mike"]) {
                nodes { __typename ... on Staff { firstName } }
                pageInfo { hasNextPage }
            } }
            """);
        var conn = (Map<String, Object>) data.get("occupantsByNameConnection");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes)
            .as("only Staff Mike matches; the connection branch WHERE must narrow per participant")
            .singleElement()
            .satisfies(n -> {
                assertThat(n.get("__typename")).isEqualTo("Staff");
                assertThat(n.get("firstName")).isEqualTo("Mike");
            });
        // The connection branch loop (buildStage1ConnectionBlock) must emit a per-branch first_name
        // predicate; it once emitted no WHERE at all.
        assertThat(SQL_LOG)
            .as("the connection stage-1 SQL filters on first_name")
            .anyMatch(s -> s.contains("first_name") && s.contains(" in ("));
    }
}
