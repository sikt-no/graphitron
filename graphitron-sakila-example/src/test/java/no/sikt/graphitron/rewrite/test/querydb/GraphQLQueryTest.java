package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.GraphitronContext;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.InstanceOfAssertFactories.INTEGER;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.assertj.core.api.InstanceOfAssertFactories.map;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;

/**
 * End-to-end tests that execute GraphQL queries against a real PostgreSQL database
 * using the generated wiring, field resolvers, and table methods.
 *
 * <p>This verifies that the generated code actually works — not just that it compiles.
 */
@ExecutionTier
@SuppressWarnings("unchecked") // GraphQL responses are Map<String, Object>; casts to typed shapes are inherent to the assertion style
class GraphQLQueryTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;
    static final AtomicInteger QUERY_COUNT = new AtomicInteger();
    /**
     * Lower-cased rendered SQL for every statement issued through the test {@link DSLContext}.
     * Tests that need to assert on which SQL ran (e.g. that {@code totalCount} was lazy on
     * selection) call {@link #SQL_LOG}{@code .clear()} before executing the GraphQL query.
     */
    static final List<String> SQL_LOG = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeAll
    static void startDatabase() throws Exception {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            var user = System.getProperty("test.db.username", "postgres");
            var pass = System.getProperty("test.db.password", "postgres");
            dsl = DSL.using(localUrl, user, pass);
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine")
                .withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }

        // Count JDBC round-trips via an ExecuteListener. Tests that care (DataLoader batching)
        // call QUERY_COUNT.set(0) before executing and assert on the count afterward. The same
        // listener captures the rendered SQL of every statement into SQL_LOG; the totalCount
        // lazy-on-selection test asserts that no `select count` ran when the field wasn't picked.
        dsl.configuration().set(new org.jooq.impl.DefaultExecuteListenerProvider(
            new org.jooq.ExecuteListener() {
                @Override
                public void executeStart(org.jooq.ExecuteContext ctx) {
                    QUERY_COUNT.incrementAndGet();
                    var sql = ctx.sql();
                    if (sql != null) SQL_LOG.add(sql.toLowerCase(java.util.Locale.ROOT));
                }
            }));

        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String query, Map<String, Object> variables) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).variables(variables).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    /**
     * Executes a query and returns the {@link graphql.ExecutionResult} without asserting
     * on errors — for tests that expect a failure path (e.g. Relay first+last validation).
     */
    private graphql.ExecutionResult executeRaw(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        return graphql.execute(input);
    }

    /**
     * Asserts a top-level field resolved to null. Tolerant of graphql-java's non-null propagation:
     * a {@code [T!]!} field returning null bubbles the null to the root, so {@code getData()} may be
     * null outright rather than a map carrying a null value. Either shape means the field did not
     * resolve, which is the point for an errored fetch.
     */
    @SuppressWarnings("unchecked")
    private static void assertFieldNullified(graphql.ExecutionResult result, String field) {
        Object data = result.getData();
        if (data != null) {
            assertThat(((Map<String, Object>) data).get(field)).isNull();
        }
    }

    // ===== R313: aliasing scalar build-through + execution =====

    @Test
    void aliasingScalar_registeredUnderSdlNameAndResolvesEndToEnd() {
        // Build-through proof: Graphitron.buildSchema(...) in @BeforeAll already assembles this
        // schema; before the fix it threw "type LocalDate not found in schema" because the
        // LocalDate scalar (an alias of ExtendedScalars.Date, intrinsic name "Date") was
        // registered under "Date". Assert the scalar is registered under its SDL name so the
        // Customer.createDate typeRef("LocalDate") resolves.
        var assembled = graphql.getGraphQLSchema();
        assertThat(assembled.getType("LocalDate"))
            .as("aliasing scalar must register under its SDL name, not the constant's name")
            .isInstanceOf(graphql.schema.GraphQLScalarType.class);
        assertThat(assembled.getType("Date"))
            .as("the constant's intrinsic name must not leak into the schema")
            .isNull();

        // Execution proof: the field actually projects the customer.create_date DATE column and
        // serialises it through the Date coercing to an ISO-8601 date string.
        Map<String, Object> data = execute("{ customers { createDate } }");
        assertThat(data).extractingByKey("customers", as(LIST))
            .hasSize(5)
            .allSatisfy(c -> assertThat(((Map<String, Object>) c).get("createDate"))
                .asInstanceOf(STRING)
                .matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    // ===== Multi-field root query =====

    @Test
    void multipleRootFields_eachGetsCorrectSelectionSet() {
        Map<String, Object> data = execute("""
            {
                customers { firstName }
                films { title }
            }
            """);

        assertThat(data).extractingByKey("customers", as(LIST))
            .hasSize(5)
            .first(as(MAP)).containsKey("firstName");
        assertThat(data).extractingByKey("films", as(LIST))
            .hasSize(5)
            .first(as(MAP)).containsKey("title");
    }

    @Test
    void multipleRootFields_filmsColumnsNotLeakedIntoCustomers() {
        // If selection set scoping is wrong, customers might try to SELECT film columns
        Map<String, Object> data = execute("""
            {
                customers { firstName lastName }
                films { title rating }
            }
            """);
        assertThat(data).extractingByKey("customers", as(LIST))
            .hasSize(5)
            .first(as(MAP)).containsOnlyKeys("firstName", "lastName");
    }

    // ===== customers query =====

    @Test
    void customers_returnsAllCustomers() {
        Map<String, Object> data = execute("{ customers { customerId firstName lastName } }");
        assertThat(data).extractingByKey("customers", as(LIST)).hasSize(5);
    }

    @Test
    void customers_filteredByActive() {
        Map<String, Object> data = execute("{ customers(active: true) { customerId firstName } }");
        assertThat(data).extractingByKey("customers", as(list(Map.class)))
            .hasSize(3)
            .extracting(c -> c.get("firstName"))
            .containsExactlyInAnyOrder("Mary", "Patricia", "Linda");
    }

    @Test
    void customersByAddressDistrict_fkTargetNodeIdOverrideCondition_filtersByForeignTable() {
        // R330: CustomerAddressNodeFilter.addressId carries @nodeId(typeName: "Address") +
        // @condition(override: true). customer reaches address via customer_address_id_fkey, so the
        // condition method (addressDistrictAlberta) receives the FK-target Address alias, not the
        // customer table, emitted as a correlated EXISTS. Seed: addresses 1 and 3 are 'Alberta',
        // held by customers Smith (addr 1), Williams (addr 3), and Jones (addr 1). The empty filter
        // exercises the override path: the decoded-id predicate is dropped and the method owns the
        // WHERE entirely. Pre-fix the generated call handed the method the customer table and either
        // failed to compile (concrete Address parameter) or filtered the wrong table.
        Map<String, Object> data = execute("{ customersByAddressDistrict(filter: {}) { lastName } }");
        assertThat(data).extractingByKey("customersByAddressDistrict", as(list(Map.class)))
            .extracting(c -> c.get("lastName"))
            .containsExactlyInAnyOrder("Smith", "Williams", "Jones");
    }

    @Test
    void customersByMultiFieldFilter_fkTargetAmongImplicitSiblings_emitsExists() {
        // R330 rework: the opptak SoknadsmangeltypeFilterInput shim shape — un-annotated implicit
        // siblings (firstName, activebool) produce a GeneratedConditionFilter and lift the `filter`
        // arg to a `filterMap` local, ALONGSIDE a non-null ID! FK-target @nodeId @condition(override).
        // The FK-target term must still emit a correlated EXISTS handing addressDistrictAlberta an
        // aliased Address. Empty filter: the implicit siblings are null (no-op) and the override owns
        // the predicate, so this returns the Alberta customers.
        Map<String, Object> data = execute("{ customersByMultiFieldFilter(filter: {}) { lastName } }");
        assertThat(data).extractingByKey("customersByMultiFieldFilter", as(list(Map.class)))
            .extracting(c -> c.get("lastName"))
            .containsExactlyInAnyOrder("Smith", "Williams", "Jones");
    }

    @Test
    void projectNotesByProject_compositeKeyFkTargetOverride_filtersByForeignTable() {
        // R330 rework: composite-key FK-target @nodeId + @condition(override) — the common consumer
        // shape (composite NodeType keys). project_note reaches project via a composite FK
        // (org_id, project_id); projectNameAtlas receives an aliased Project inside a correlated
        // EXISTS whose correlation ANDs both composite-FK slots. Seed: project (1,100) is 'Atlas'
        // with notes Atlas-N1..N3; (1,101)=Beacon and (2,100)=Cipher are filtered out. Pre-fix the
        // composite arm emitted a plain ConditionFilter passing the project_note table to a method
        // declaring Project, failing at consumer compile (the opptak Regelverksamling shape).
        Map<String, Object> data = execute("{ projectNotesByProject(filter: {}) { body } }");
        assertThat(data).extractingByKey("projectNotesByProject", as(list(Map.class)))
            .extracting(c -> c.get("body"))
            .containsExactlyInAnyOrder("Atlas-N1", "Atlas-N2", "Atlas-N3");
    }

    @Test
    void projectNotesByPlainFilter_plainInputCompositeFkTargetOverride_filtersByForeignTable() {
        // R330 rework: the opptak SoknadsmangeltypeFilterInput shape exactly — an input with no
        // @table carrying a composite FK-target @nodeId + @condition(override) alongside a sibling
        // implicit field. The @condition(override) field is what routes the type off the table path
        // (isUsedWithOverrideCondition, TypeBuilder.buildInputType): without the override it would be
        // promoted to project_note via findReturnTablesForInput, so the divergence is driven by the
        // override directive, not by the absence of @table. It still resolves against project_note;
        // projectNameAtlas receives an aliased Project inside a correlated EXISTS over the composite
        // (org_id, project_id) FK. This non-table routing is what the @table fixtures missed: the
        // structural validator walks only TableInputType (validateTableInputType), so the broken call
        // slipped through to the consumer's javac (exactly the opptak symptom).
        Map<String, Object> data = execute("{ projectNotesByPlainFilter(filter: {}) { body } }");
        assertThat(data).extractingByKey("projectNotesByPlainFilter", as(list(Map.class)))
            .extracting(c -> c.get("body"))
            .containsExactlyInAnyOrder("Atlas-N1", "Atlas-N2", "Atlas-N3");
    }

    @Test
    void projectNotesByPlainFilterConnection_plainInputCompositeFkTargetOverride_filtersByForeignTable() {
        // R330 rework: the same plain-input composite FK-target shape on an @asConnection query,
        // mirroring soknadsmangeltyper(...): [Soknadsmangeltype!] @asConnection. The shim feeds the
        // same composite EXISTS into the connection fetcher.
        Map<String, Object> data = execute(
            "{ projectNotesByPlainFilterConnection(filter: {}) { nodes { body } } }");
        assertThat(data).extractingByKey("projectNotesByPlainFilterConnection", as(map(String.class, Object.class)))
            .extractingByKey("nodes", as(list(Map.class)))
            .extracting(c -> c.get("body"))
            .containsExactlyInAnyOrder("Atlas-N1", "Atlas-N2", "Atlas-N3");
    }

    @Test
    void store_customersByAddressDistrict_inlineFkTargetOverride_filtersByForeignTable() {
        // R330: inline child TableField path (InlineTableFieldEmitter). Store.customersByAddressDistrict
        // correlates store -> customer, then the FK-target @nodeId(Address) @condition(override) on the
        // filter is emitted as a correlated EXISTS handing addressDistrictAlberta an aliased Address.
        // Seed: store 1 holds Smith + Jones (address 1, Alberta), store 2 holds Williams (address 3,
        // Alberta). Pre-fix the inline emitter passed the customer alias to a method declaring Address
        // and failed to compile (the SakFetchers shape that regressed in 10.0.0-RC16).
        Map<String, Object> data = execute(
            "{ storeById(store_id: [1, 2]) { storeId customersByAddressDistrict(filter: {}) { lastName } } }");
        assertThat(data).extractingByKey("storeById", as(list(Map.class)))
            .anySatisfy(s -> {
                assertThat(s.get("storeId")).isEqualTo(1);
                assertThat((List<Map<String, Object>>) s.get("customersByAddressDistrict"))
                    .extracting(c -> c.get("lastName")).containsExactlyInAnyOrder("Smith", "Jones");
            })
            .anySatisfy(s -> {
                assertThat(s.get("storeId")).isEqualTo(2);
                assertThat((List<Map<String, Object>>) s.get("customersByAddressDistrict"))
                    .extracting(c -> c.get("lastName")).containsExactlyInAnyOrder("Williams");
            });
    }

    @Test
    void store_customersByAddressDistrictSplit_splitFkTargetOverride_filtersByForeignTable() {
        // R330: @splitQuery child path (SplitRowsMethodEmitter, the third WHERE-emitting site). Same
        // FK-target EXISTS as the inline sibling, embedded in the batched rows-method instead.
        Map<String, Object> data = execute(
            "{ storeById(store_id: [1, 2]) { storeId customersByAddressDistrictSplit(filter: {}) { lastName } } }");
        assertThat(data).extractingByKey("storeById", as(list(Map.class)))
            .anySatisfy(s -> {
                assertThat(s.get("storeId")).isEqualTo(1);
                assertThat((List<Map<String, Object>>) s.get("customersByAddressDistrictSplit"))
                    .extracting(c -> c.get("lastName")).containsExactlyInAnyOrder("Smith", "Jones");
            })
            .anySatisfy(s -> {
                assertThat(s.get("storeId")).isEqualTo(2);
                assertThat((List<Map<String, Object>>) s.get("customersByAddressDistrictSplit"))
                    .extracting(c -> c.get("lastName")).containsExactlyInAnyOrder("Williams");
            });
    }

    @Test
    void store_customersByFirstName_inlineScalarCondition_narrowsByArgument() {
        // R424: inline (non-@splitQuery) @reference child list whose filter carries a scalar
        // @condition arg (customer.first_name = ?). Pre-fix the inline emitter read the argument off
        // the ancestor fetcher's env (env.getArgument("filter")), which is null there, so the
        // predicate collapsed to noCondition() and the field returned ALL of the store's customers.
        // The fix reads the argument off the inline field's own SelectedField, so it narrows.
        // Seed by store: store 1 = {Mary, Patricia, Barbara}, store 2 = {Linda, Elizabeth}.
        Map<String, Object> data = execute(
            "{ storeById(store_id: [1, 2]) { storeId customersByFirstName(filter: {firstName: \"Mary\"}) { firstName } } }");
        assertThat(data).extractingByKey("storeById", as(list(Map.class)))
            .anySatisfy(s -> {
                assertThat(s.get("storeId")).isEqualTo(1);
                // Narrowed to exactly Mary — not the pre-fix unfiltered {Mary, Patricia, Barbara}.
                assertThat((List<Map<String, Object>>) s.get("customersByFirstName"))
                    .extracting(c -> c.get("firstName")).containsExactly("Mary");
            })
            .anySatisfy(s -> {
                assertThat(s.get("storeId")).isEqualTo(2);
                // Mary is not a store-2 customer: correct behaviour returns empty; the pre-fix bug
                // would return store 2's unfiltered customers (the reproducer's "returns rows it did
                // not ask for" shape).
                assertThat((List<Map<String, Object>>) s.get("customersByFirstName")).isEmpty();
            });
    }

    @Test
    void store_customersByFirstName_inlineVsSplit_identicalResults() {
        // R424: the @splitQuery sibling has always resolved the argument correctly (its env genuinely
        // is the field's own environment). Pin that the fixed inline path now matches it byte-for-byte
        // for the identical filter — inline/split parity.
        Map<String, Object> data = execute(
            "{ storeById(store_id: [1]) {"
            + " inlineList: customersByFirstName(filter: {firstName: \"Mary\"}) { firstName }"
            + " splitList: customersByFirstNameSplit(filter: {firstName: \"Mary\"}) { firstName } } }");
        assertThat(data).extractingByKey("storeById", as(list(Map.class)))
            .singleElement(as(map(String.class, Object.class)))
            .satisfies(s -> {
                var inline = (List<Map<String, Object>>) s.get("inlineList");
                var split = (List<Map<String, Object>>) s.get("splitList");
                assertThat(inline).extracting(c -> c.get("firstName")).containsExactly("Mary");
                assertThat(split).extracting(c -> c.get("firstName")).containsExactly("Mary");
            });
    }

    @Test
    void store_customersFirstN_inlinePagination_limitsRows() {
        // R424: inline @reference list `first` pagination. Pre-fix the limit was read off the ancestor
        // env (env.getArgument("first")), so `first` was silently ignored (Integer.MAX_VALUE) and all
        // rows came back. The fix reads it off the inline field's SelectedField. Store 1 has three
        // customers ordered by primary key (Mary, Patricia, Barbara); first: 2 keeps the first two.
        Map<String, Object> data = execute(
            "{ storeById(store_id: [1]) { customersFirstN(first: 2) { firstName } } }");
        assertThat(data).extractingByKey("storeById", as(list(Map.class)))
            .singleElement(as(map(String.class, Object.class)))
            .satisfies(s ->
                assertThat((List<Map<String, Object>>) s.get("customersFirstN"))
                    .extracting(c -> c.get("firstName")).containsExactly("Mary", "Patricia"));
    }

    @Test
    void store_customersByNodeId_inlineDecodeNarrows_foreignNodeIdReturnsEmpty() {
        // R424: inline (non-@splitQuery) @reference child list whose filter carries a same-table
        // @nodeId field. The decoded predicate (customer.customer_id IN (decoded)) genuinely consumes
        // the argument, so it is the reproducer's decode-with-value path (unlike customersByAddressDistrict,
        // whose @condition(override) ignores its addressId). Pre-fix the inline emitter read the filter
        // off the ancestor env (null), so the decode saw null and the predicate collapsed — the field
        // returned ALL of the store's customers. The fix reads it off the field's own SelectedField.
        // Seed: customer 3 (Linda) belongs to store 2. Filtering store 1's children by Linda's node id
        // must return empty (a node id NOT under the parent); the same filter under store 2 returns Linda,
        // proving the decode narrows rather than always-empties.
        String linda = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 3);
        Map<String, Object> data = execute(
            "{ storeById(store_id: [1, 2]) { storeId customersByNodeId(filter: {customerRefs: [\"" + linda + "\"]}) { firstName } } }");
        assertThat(data).extractingByKey("storeById", as(list(Map.class)))
            .anySatisfy(s -> {
                assertThat(s.get("storeId")).isEqualTo(1);
                // Linda is a store-2 customer: not under store 1, so empty. Pre-fix returned store 1's
                // unfiltered customers (the reproducer's "returns rows it did not ask for" shape).
                assertThat((List<Map<String, Object>>) s.get("customersByNodeId")).isEmpty();
            })
            .anySatisfy(s -> {
                assertThat(s.get("storeId")).isEqualTo(2);
                assertThat((List<Map<String, Object>>) s.get("customersByNodeId"))
                    .extracting(c -> c.get("firstName")).containsExactly("Linda");
            });
    }

    @Test
    void store_customersByNodeId_inlineVsSplit_identicalResults() {
        // R424: the @splitQuery sibling has always resolved the argument correctly (its env is the
        // field's own environment). Pin that the fixed inline decode path now matches it for the
        // identical @nodeId filter — inline/split parity. Store 2 = {Linda (3), Elizabeth (5)}.
        String linda = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 3);
        String elizabeth = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 5);
        Map<String, Object> data = execute(
            "{ storeById(store_id: [2]) {"
            + " inlineList: customersByNodeId(filter: {customerRefs: [\"" + linda + "\", \"" + elizabeth + "\"]}) { firstName }"
            + " splitList: customersByNodeIdSplit(filter: {customerRefs: [\"" + linda + "\", \"" + elizabeth + "\"]}) { firstName } } }");
        assertThat(data).extractingByKey("storeById", as(list(Map.class)))
            .singleElement(as(map(String.class, Object.class)))
            .satisfies(s -> {
                var inline = (List<Map<String, Object>>) s.get("inlineList");
                var split = (List<Map<String, Object>>) s.get("splitList");
                assertThat(inline).extracting(c -> c.get("firstName")).containsExactly("Linda", "Elizabeth");
                assertThat(split).extracting(c -> c.get("firstName")).containsExactly("Linda", "Elizabeth");
            });
    }

    @Test
    void customersByAddressDistrictActive_fieldOverridePlusFkTarget_composesBothShimTerms() {
        // R330: the opptak shim shape exactly — a field-level @condition(override) (customersActiveOnly,
        // against the root customer table) AND an FK-target @nodeId @condition(override)
        // (addressDistrictAlberta, against an aliased Address via EXISTS). The QueryConditions shim ANDs
        // both terms. Alberta customers are Smith, Williams, Jones; Jones is inactive, so the active
        // term drops it. Guards the multi-filter accumulation where soknadsmangeltyper regressed.
        Map<String, Object> data = execute("{ customersByAddressDistrictActive(filter: {}) { lastName } }");
        assertThat(data).extractingByKey("customersByAddressDistrictActive", as(list(Map.class)))
            .extracting(c -> c.get("lastName"))
            .containsExactlyInAnyOrder("Smith", "Williams");
    }

    // ===== films query =====

    @Test
    void films_returnsAllFilms() {
        Map<String, Object> data = execute("{ films { filmId title } }");
        assertThat(data).extractingByKey("films", as(LIST)).hasSize(5);
    }

    // R425 note: the Film service-child tests below do NOT pin the SourceKey force-projection
    // (parent SELECT must include FILM_ID even when unselected) — Film's cast/castByKey
    // @splitQuery siblings already force-project FILM_ID into every parent SELECT, so these
    // stay green if the @service arm of TypeClassGenerator.collectRequiredProjectionColumns
    // regresses. They cover the with-projecting-sibling scenario; the unmasked fixture is the
    // City service children (cities_cityUppercase_* / cities_cityLowercase_* below).
    @Test
    void films_titleLowercase_resolvesViaServiceRecordFieldDataLoader_row1Source() {
        // R61 L6 sibling: identical wiring to titleUppercase but the developer-side method
        // takes Set<Row1<Integer>> (Row source-shape) and returns Map<Row1<Integer>, String>.
        // The classifier routes Row1 sources to MappedRowKeyed (vs MappedRecordKeyed for the
        // Record1 sibling); the framework's MappedRowKeyed.keyElementType() = Row1<Integer>
        // matches the developer's declaration. Confirms the field<N>()-based dispatch path
        // round-trips cleanly through the SQL VALUES table to the database and back, with
        // value-based Row.equals/hashCode joining the developer's response Map to the
        // framework's input keys.
        Map<String, Object> data = execute("{ films { title titleLowercase } }");
        assertThat(data).extractingByKey("films", as(list(Map.class)))
            .hasSize(5)
            .allSatisfy(f -> {
                var title = (String) f.get("title");
                var titleLowercase = (String) f.get("titleLowercase");
                assertThat(titleLowercase)
                    .as("titleLowercase must equal title.toLowerCase() for film '%s'", title)
                    .isEqualTo(title.toLowerCase());
            });
    }

    @Test
    void films_titleTitlecase_resolvesViaServiceRecordFieldDataLoader_tableRecordSource() {
        // R70 L6 sibling: identical wiring to titleUppercase / titleLowercase but the
        // developer-side method takes Set<FilmRecord> (typed-TableRecord source-shape) and
        // returns Map<FilmRecord, String>. The classifier routes Set<X extends TableRecord>
        // to MappedTableRecordKeyed (carrying FilmRecord on the variant); the rows-method
        // emitter passes through `keys` directly because the generated lambda's keys local is
        // already typed Set<FilmRecord>. Confirms the typed extraction
        // (env.getSource().into(Tables.FILM)) round-trips through the DataLoader and the
        // developer can read column values via FilmRecord.getTitle() without an extra fetch.
        // Note: this query selects `title` alongside, so it does not by itself pin the
        // "fully-populated parent records" contract — that lives in
        // films_titleTitlecase_withoutSelectingTitle_readsNonKeyColumnOffSourceRecord (R426).
        Map<String, Object> data = execute("{ films { title titleTitlecase } }");
        assertThat(data).extractingByKey("films", as(list(Map.class)))
            .hasSize(5)
            .allSatisfy(f -> {
                var title = (String) f.get("title");
                var titleTitlecase = (String) f.get("titleTitlecase");
                String expected = expectedTitleCase(title);
                assertThat(titleTitlecase)
                    .as("titleTitlecase must equal title-cased version for film '%s'", title)
                    .isEqualTo(expected);
            });
    }

    @Test
    void films_titleTitlecase_withoutSelectingTitle_readsNonKeyColumnOffSourceRecord() {
        // R426: the unmasked reproducer for the typed-TableRecord source-shape contract. The
        // query selects ONLY the service child — no `title`, no `id` — so nothing in the client
        // selection projects the `title` column. The service body reads film.getTitle() off the
        // source record (a non-key column), which the manual documents as supported: "The
        // framework supplies fully-populated parent records (every column on the parent table)"
        // (handle-services.adoc). Film.$fields must therefore project the full parent row
        // whenever a TableRecord-sourced service child is selected; pre-fix, getTitle() read
        // null off the partial record and the field silently resolved to a titlecased null.
        // This test is the live behaviour behind the manual's "fully-populated parent records"
        // claim — if the projection regresses, this goes red, not just the docs stale.
        Map<String, Object> data = execute("{ films { titleTitlecase } }");
        assertThat(data).extractingByKey("films", as(list(Map.class)))
            .extracting(f -> f.get("titleTitlecase"))
            .containsExactlyInAnyOrder(
                "Academy Dinosaur",
                "Ace Goldfinger",
                "Adaptation Holes",
                "Affair Prejudice",
                "Agent Truman");
    }

    @Test
    @SuppressWarnings("unchecked")
    void films_titleTitlecase_withCollidingMultisetSibling_bothResolve_noMappingException() {
        // R436 Defect 1 repro (key-extraction-multiset-alias-collision.md). Film.Length is a
        // multiset-backed object field whose projection is aliased "Length", case-insensitively
        // shadowing the physical FILM.LENGTH (smallint) column. titleTitlecase is a
        // Wrap.TableRecord @service child that projects the full parent row and rebuilds a
        // FilmRecord from it. Pre-fix the rebuild did env.getSource().into(Tables.FILM), which maps
        // by column name; the "Length" multiset Result shadowed FILM.LENGTH and could not convert
        // to smallint, so every film crashed with a MappingException (and the raw record-dumping
        // message escaped redaction — Defect 2). Post-fix the full row is projected under reserved
        // __src_<col>__ aliases and rebuilt column by column, so the multiset alias cannot collide.
        // Selecting only the two colliding participants isolates the seam.
        Map<String, Object> data = execute("{ films { titleTitlecase Length { inventoryId } } }");
        var films = (java.util.List<Map<String, Object>>) data.get("films");
        assertThat(films).hasSize(5);
        assertThat(films)
            .as("every film's titleTitlecase resolves — pre-fix this threw a MappingException")
            .extracting(f -> f.get("titleTitlecase"))
            .containsExactlyInAnyOrder(
                "Academy Dinosaur", "Ace Goldfinger", "Adaptation Holes",
                "Affair Prejudice", "Agent Truman");
        int totalInventory = 0;
        for (var f : films) {
            totalInventory += ((java.util.List<?>) f.get("Length")).size();
        }
        assertThat(totalInventory)
            .as("the colliding multiset sibling resolves too: films 1-3 each carry one inventory row")
            .isEqualTo(3);
    }

    private static String expectedTitleCase(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean nextUpper = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                out.append(c);
                nextUpper = true;
            } else if (nextUpper) {
                out.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    @Test
    void films_titleUppercase_resolvesViaServiceRecordFieldDataLoader() {
        // R49 Phase B (R32): @service child field with a scalar return. The generator-emitted
        // rows-method body calls FilmService.titleUppercase via the parameterised
        // ArgCallEmitter (Sources -> keys, DslContext -> dsl local). Each key is one parent
        // FILM_ID; the developer's method returns Map<Row1<Integer>, String> with uppercased
        // titles. End-to-end verification that the Phase A plumbing (BatchKey, IMPLEMENTED_LEAVES,
        // shared emitters) works with Phase B's body emission against PostgreSQL.
        Map<String, Object> data = execute("{ films { title titleUppercase } }");
        assertThat(data).extractingByKey("films", as(list(Map.class)))
            .hasSize(5)
            .allSatisfy(f -> {
                var title = (String) f.get("title");
                var titleUppercase = (String) f.get("titleUppercase");
                assertThat(titleUppercase)
                    .as("titleUppercase must equal title.toUpperCase() for film '%s'", title)
                    .isEqualTo(title.toUpperCase());
            });
    }

    @Test
    void films_languageName_resolvesViaScalarReference() {
        // R42 ColumnReferenceField execution-tier fixture: Direct + FK-only single-hop scalar
        // @reference. TypeClassGenerator.$fields() projects an aliased correlated subquery
        // (SELECT language.NAME FROM language WHERE language.LANGUAGE_ID = film.LANGUAGE_ID LIMIT 1);
        // FetcherEmitter wires a ColumnFetcher(DSL.field("languageName")) that reads the alias
        // off the result Record at request time. All seeded films map to language_id=1 ("English").
        // The Sakila language.name column is char(20), so PostgreSQL pads — strip before compare.
        Map<String, Object> data = execute("{ films { title languageName } }");
        var films = assertThat(data).extractingByKey("films", as(list(Map.class))).hasSize(5);
        films.extracting(f -> ((String) f.get("languageName")).strip()).containsOnly("English");
        films.extracting(f -> f.get("title")).doesNotContainNull();
    }

    @Test
    void films_isEnglish_resolvesViaExternalFieldExpression() {
        // R48 ComputedField execution-tier fixture: @externalField(reference: ...) inlines
        // FilmExtensions.isEnglish(table) (Field<Boolean>(LANGUAGE_ID = 1)) into Film.$fields().
        // ColumnFetcher reads the projected alias from the result Record at request time.
        // All seeded films have language_id=1, so the expression resolves to true for each.
        Map<String, Object> data = execute("{ films { title isEnglish } }");
        var films = assertThat(data).extractingByKey("films", as(list(Map.class))).hasSize(5);
        films.extracting(f -> f.get("isEnglish")).containsOnly(Boolean.TRUE);
        films.extracting(f -> f.get("title")).doesNotContainNull();
    }

    @Test
    void films_castMembers_referenceSubfieldResolvesViaServiceTableFieldLift() {
        // R285 lift-back execution-tier primary net. Film.castMembers is a list-cardinality child
        // @service (mapped container) returning film_actor rows; FilmActor.actor is a @reference
        // (correlated multiset, not a stored column). Pre-R285 the verbatim service return carried
        // no `actor` column and the reference fetcher threw
        // 'Field "actor" is not contained in row type'; the lift re-projects each returned PK through
        // FilmActor.$fields so the multiset is present. The execute() helper asserts zero GraphQL
        // errors, so reaching the assertions already proves the reference no longer throws.
        // In-tree analogue of opptak's Sak.saksdokumenter -> Saksdokument.dokument.
        Map<String, Object> data = execute("""
            { films { filmId castMembers { actorId actor { firstName } } } }
            """);
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");

        // Seeded film_actor: film 1 -> actors {1=PENELOPE, 2=NICK}; film 2 -> {1=PENELOPE, 3=ED}.
        // Asserting the exact per-film cast proves (a) the @reference resolves to the joined actor
        // row, (b) the mapped re-wrap keys each parent to its own records (no cross-parent leakage),
        // and (c) the lift re-projects exactly the service-returned PKs (film 1 has precisely two
        // cast members, no widening to the whole film_actor table).
        Map<String, Object> film1 = films.stream()
            .filter(f -> f.get("filmId").equals(1)).findFirst().orElseThrow();
        assertThat(film1).extractingByKey("castMembers", as(list(Map.class)))
            .extracting(
                cm -> cm.get("actorId"),
                cm -> ((Map<String, Object>) cm.get("actor")).get("firstName"))
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(1, "PENELOPE"),
                org.assertj.core.groups.Tuple.tuple(2, "NICK"));

        Map<String, Object> film2 = films.stream()
            .filter(f -> f.get("filmId").equals(2)).findFirst().orElseThrow();
        assertThat(film2).extractingByKey("castMembers", as(list(Map.class)))
            .extracting(cm -> ((Map<String, Object>) cm.get("actor")).get("firstName"))
            .containsExactlyInAnyOrder("PENELOPE", "ED");
    }

    @Test
    void inventoryById_filmRef_resolvesViaExternalFieldReturningFieldOfTableRecord() {
        // R61 execution-tier fixture: @externalField returning Field<TableRecord<?>>.
        // InventoryExtensions.filmRef(table) projects inventory.film_id via DSL.row(...).
        // convertFrom(...), constructing a FilmRecord with only the PK populated. The
        // GraphQL FilmCard type is record-backed by FilmRecord; FilmCard.filmId is read
        // from the lifted FilmRecord at request time. Confirms a Field<X> where X is a
        // jOOQ TableRecord round-trips end-to-end through the existing @externalField
        // resolver (no production change in R61) and the convertFrom trick lifts a typed
        // value at SQL time.
        Map<String, Object> data = execute(
            "{ inventoryById(inventory_id: [1, 2, 3]) { inventoryId filmRef { filmId } } }");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("inventoryById");
        assertThat(rows).hasSize(3);
        // Seed: inventory_id N -> film_id N for N in {1, 2, 3}.
        for (var row : rows) {
            int inventoryId = (Integer) row.get("inventoryId");
            @SuppressWarnings("unchecked")
            Map<String, Object> filmRef = (Map<String, Object>) row.get("filmRef");
            assertThat(filmRef).extractingByKey("filmId").isEqualTo(inventoryId);
        }
    }

    @Test
    void inventoryById_filmCardData_firesAccessorKeyedSingleLiftThroughCustomJavaRecord() {
        // R61 execution-tier fixture: @externalField returning Field<CustomJavaRecord>
        // where the custom record (FilmCardData) carries a typed FilmRecord accessor.
        // The classifier picks the canonical film() accessor on FilmCardData and produces
        // an AccessorKeyedSingle BatchKey for the GraphQL child field `film: Film`. The
        // framework batches dispatch via loader.load(key, env) keyed on the element table's
        // PK (Record1<Integer>) and returns one Film row per key — full columns this time,
        // so Film.title resolves from the framework-fetched record (the lifted FilmRecord
        // only carried the PK).
        //
        // R61 lifted Invariant #10 (the single-cardinality RecordTableField rejection at
        // GraphitronSchemaValidator.validateRecordParentSingleCardinalityRejected) by
        // extending RecordTableField.emitsSingleRecordPerKey() to also be true for
        // single-cardinality fields, so this test exercises the AccessorKeyedSingle path
        // end-to-end against PostgreSQL.
        Map<String, Object> data = execute(
            "{ inventoryById(inventory_id: [1, 2, 3]) { inventoryId filmCardData { film { filmId title } } } }");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("inventoryById");
        assertThat(rows).hasSize(3);
        // Seeded film titles by film_id 1..3.
        Map<Integer, String> expectedTitleByFilmId = Map.of(
            1, "ACADEMY DINOSAUR",
            2, "ACE GOLDFINGER",
            3, "ADAPTATION HOLES");
        for (var row : rows) {
            int inventoryId = (Integer) row.get("inventoryId");
            @SuppressWarnings("unchecked")
            Map<String, Object> filmCardData = (Map<String, Object>) row.get("filmCardData");
            @SuppressWarnings("unchecked")
            Map<String, Object> film = (Map<String, Object>) filmCardData.get("film");
            assertThat(film).extractingByKey("filmId").isEqualTo(inventoryId);
            assertThat(film).extractingByKey("title").isEqualTo(expectedTitleByFilmId.get(inventoryId));
        }
    }

    @Test
    void inventoryById_filmCardDataNullAccessor_rendersFilmNullWithoutNpe() {
        // R269 execution-tier fixture (the load-bearing net for the accessor ONE-arm null guard):
        // the record-backed parent FilmCardData's film() accessor returns null for even film_ids
        // (a nullable to-one @table relation that resolves to NO row on an otherwise-successful
        // parent). buildAccessorKeySingle's `if (element == null) return completedFuture(null)`
        // guard must render `film` as null for those rows rather than raising
        //   Cannot invoke "...Record.into(...)" because "element" is null
        // (the bug R269 fixes). execute(...) asserts result.getErrors() is empty, so the NPE — which
        // would surface as a DataFetcher exception — is pinned out behaviourally, not by grepping the
        // emitted `if (element == null)` out of the fetcher body (banned per the development principles).
        //
        // Mixed batch: with the seed inventory_id N -> film_id N, inventories 1 and 3 (odd film_ids)
        // still resolve their full Film row through the same AccessorKeyedSingle loader, proving the
        // guard nulls only the absent arm and leaves present siblings untouched.
        Map<String, Object> data = execute(
            "{ inventoryById(inventory_id: [1, 2, 3]) { inventoryId filmCardDataMaybeMissing { film { filmId title } } } }");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("inventoryById");
        assertThat(rows).hasSize(3);
        Map<Integer, String> expectedTitleByFilmId = Map.of(
            1, "ACADEMY DINOSAUR",
            3, "ADAPTATION HOLES");
        for (var row : rows) {
            int inventoryId = (Integer) row.get("inventoryId");
            @SuppressWarnings("unchecked")
            Map<String, Object> wrapper = (Map<String, Object>) row.get("filmCardDataMaybeMissing");
            @SuppressWarnings("unchecked")
            Map<String, Object> film = (Map<String, Object>) wrapper.get("film");
            if (inventoryId % 2 == 0) {
                // Even film_id -> null embedded FilmRecord -> the guard renders the field null.
                assertThat(film).isNull();
            } else {
                // Odd film_id -> present record -> the loader resolves the full Film row by PK.
                assertThat(film).extractingByKey("filmId").isEqualTo(inventoryId);
                assertThat(film).extractingByKey("title").isEqualTo(expectedTitleByFilmId.get(inventoryId));
            }
        }
    }

    @Test
    void inventoryById_filmViaTableMethod_correlatesParentRowViaInjectedFkProjection() {
        // R43 FK-projection sub-commit: the child @tableMethod fetcher reads
        // parentRecord.get(DSL.name("film_id"), …) for the parent-row correlation. Without
        // TypeClassGenerator.collectRequiredProjectionColumns injecting Inventory.film_id into
        // the parent SELECT, the read throws IllegalArgumentException because the user's SDL
        // selection (inventoryId, filmViaTableMethod { ... }) doesn't request inventory.film_id.
        // Seed: inventory_id N -> film_id N for N in {1, 2, 3}, with film_id N's title pinned
        // by the sakila init seed.
        Map<String, Object> data = execute(
            "{ inventoryById(inventory_id: [1, 2, 3]) { inventoryId filmViaTableMethod { filmId title } } }");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("inventoryById");
        assertThat(rows).hasSize(3);
        Map<Integer, String> expectedTitleByFilmId = Map.of(
            1, "ACADEMY DINOSAUR",
            2, "ACE GOLDFINGER",
            3, "ADAPTATION HOLES");
        for (var row : rows) {
            int inventoryId = (Integer) row.get("inventoryId");
            @SuppressWarnings("unchecked")
            Map<String, Object> film = (Map<String, Object>) row.get("filmViaTableMethod");
            assertThat(film).extractingByKey("filmId").isEqualTo(inventoryId);
            assertThat(film).extractingByKey("title").isEqualTo(expectedTitleByFilmId.get(inventoryId));
        }
    }

    @Test
    void filmById_languageViaTableMethod_correlatesParentRowViaExplicitReferencePathFk() {
        // R43 FK-projection sub-commit (explicit @reference path arm): Film.languageViaTableMethod
        // uses @reference(path: [{key: "film_language_id_fkey"}]); the resolved FK's source-side
        // column is film.language_id. The same projection-injection mechanism applies — the parent
        // SELECT must carry language_id even when the user's SDL selection omits it.
        // All seeded films have language_id=1; seeded language with language_id=1 has name "English".
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\", \"2\"]) { filmId languageViaTableMethod { languageId name } } }");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("filmById");
        assertThat(rows).hasSize(2);
        for (var row : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> language = (Map<String, Object>) row.get("languageViaTableMethod");
            assertThat(language).extractingByKey("languageId").isEqualTo(1);
            // language.name is char(20) in Sakila; PostgreSQL pads with spaces — strip before compare.
            assertThat(((String) language.get("name")).strip()).isEqualTo("English");
        }
    }

    @Disabled("R277 (tablemethod-under-nested-type): @tableMethod under a table-bound NestingField "
        + "is not yet wired in the generator. The languageViaTableMethod child was removed from "
        + "FilmDetailsForMethod (now a same-table NestingField after R276 dropped @record binding). "
        + "Re-enable as the execution-tier fixture when R277 lands.")
    @Test
    void filmById_detailsForMethod_languageViaTableMethod_routesThroughRecordTableMethodFieldDtoParentEmit() {
        // R43 commit 5 execution-tier fixture: child @tableMethod on a @record (DTO) parent.
        // Film.detailsForMethod is a same-table NestingField passthrough to FilmDetailsForMethod,
        // a @record(FilmRecord)-backed type (JooqTableRecordType). FilmDetailsForMethod's
        // languageViaTableMethod field classifies as ChildField.RecordTableMethodField; the
        // FK-auto-derive arm of the new @tableMethod branch in
        // FieldBuilder.classifyChildFieldOnResultType produces it with SourceKey columns =
        // [film.language_id] (FK source side). The emit
        // (SplitRowsMethodEmitter.buildForRecordTableMethod) uses the RecordTableField
        // DataLoader-keyed batch skeleton with the developer's static @tableMethod call
        // (SampleQueryService.tableMethodLanguage) substituted for the direct Tables.LANGUAGE
        // reference. languageId is declared on FilmDetailsForMethod so the parent SELECT
        // projects film.language_id; the DataFetcher reads parentRecord.get(film.LANGUAGE_ID)
        // for the DataLoader key. All seeded films have language_id=1; language_id=1 has
        // name "English".
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\", \"2\"]) { filmId detailsForMethod { filmId languageId languageViaTableMethod { languageId name } } } }");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("filmById");
        assertThat(rows).hasSize(2);
        for (var row : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) row.get("detailsForMethod");
            @SuppressWarnings("unchecked")
            Map<String, Object> language = (Map<String, Object>) details.get("languageViaTableMethod");
            assertThat(language).extractingByKey("languageId").isEqualTo(1);
            // language.name is char(20) in Sakila; PostgreSQL pads with spaces — strip before compare.
            assertThat(((String) language.get("name")).strip()).isEqualTo("English");
        }
    }

    @Test
    void filmCardWrapper_recordExample_resolvesAllThreeAccessorArms() {
        // R88 execution-tier fixture: a @record-Java-backed type whose three SDL fields each
        // exercise a different accessor-resolution arm — bare-name (Java record component
        // fieldA()), get-prefixed (getFieldB()), and @field(name:) override redirecting to
        // get-prefixed (getRebound()). The classifier resolves each arm at validate-time,
        // and the emitter switches on the pre-resolved Method handles per
        // FetcherEmitter.propertyOrRecordValue.
        Map<String, Object> data = execute(
            "{ inventoryById(inventory_id: [1]) { filmCardData { example { fieldA fieldB fieldC } } } }");
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) data.get("inventoryById");
        assertThat(rows).hasSize(1);
        @SuppressWarnings("unchecked")
        var filmCardData = (Map<String, Object>) rows.get(0).get("filmCardData");
        @SuppressWarnings("unchecked")
        var example = (Map<String, Object>) filmCardData.get("example");
        assertThat(example).extractingByKey("fieldA").isEqualTo("alpha");
        assertThat(example).extractingByKey("fieldB").isEqualTo("B-alpha");
        assertThat(example).extractingByKey("fieldC").isEqualTo("rebound-alpha");
    }

    @Test
    void films_filteredBySameTableNodeId_returnsRowsMatchingDecodedIds() {
        // self-table-nodeid-filter.md: [ID!] @nodeId(typeName: "Film") on a film-bound input
        // → primary-key IN predicate. Encode 2 of the 5 PKs, expect exactly those 2 rows.
        String id1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 2);
        String id3 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 4);
        Map<String, Object> data = execute(
            "{ filmsBySameTableNodeId(filter: {filmIds: [\"" + id1 + "\", \"" + id3 + "\"]}) "
            + "{ filmId title } }");
        assertThat(data).extractingByKey("filmsBySameTableNodeId", as(list(Map.class)))
            .extracting(f -> f.get("filmId")).containsExactlyInAnyOrder(2, 4);
    }

    @Test
    void films_filteredByArgNodeId_returnsRowsMatchingDecodedIds() {
        // R106: argument-level same-table @nodeId now classifies as QueryTableField with a
        // BodyParam.In filter against film.film_id (no lookup-promotion). Each opaque ID
        // decodes once via ThrowOnMismatch (R378); well-formed ids decode cleanly and the
        // predicate emits as `WHERE film_id IN (?, ?)`. Result rows correspond to the supplied
        // ids (order is not guaranteed by SQL, hence containsExactlyInAnyOrder).
        String id2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 2);
        String id4 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 4);
        Map<String, Object> data = execute(
            "{ filmsByNodeIdArg(ids: [\"" + id2 + "\", \"" + id4 + "\"]) { filmId title } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsByNodeIdArg");
        assertThat(films).extracting(f -> f.get("filmId")).containsExactlyInAnyOrder(2, 4);
    }

    @Test
    void filmsByNodeIdArgWithTitleFilter_composesPkInWithSiblingFilter() {
        // R106 acceptance: same-table @nodeId composed with a sibling scalar filter classifies
        // as QueryTableField (not QueryLookupTableField) with a BodyParam.In on film.film_id
        // alongside a BodyParam.Eq on film.title. The pre-R106 lookup-promotion gate dropped
        // sibling filter args silently; the lift puts them on the same rail. Encode three
        // film ids; only the one whose title matches survives the AND.
        String id1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 1);
        String id2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 2);
        String id3 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 3);
        Map<String, Object> first = execute(
            "{ filmsByNodeIdArgWithTitleFilter(ids: [\"" + id1 + "\", \"" + id2 + "\", \"" + id3 + "\"]) "
            + "{ filmId title } }");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstFilms = (List<Map<String, Object>>) first.get("filmsByNodeIdArgWithTitleFilter");
        assertThat(firstFilms).extracting(f -> f.get("filmId")).containsExactlyInAnyOrder(1, 2, 3);

        String onlyTitle = (String) firstFilms.get(0).get("title");
        Map<String, Object> filtered = execute(
            "{ filmsByNodeIdArgWithTitleFilter(ids: [\"" + id1 + "\", \"" + id2 + "\", \"" + id3 + "\"], "
            + "title: \"" + onlyTitle + "\") { filmId title } }");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filteredFilms = (List<Map<String, Object>>) filtered.get("filmsByNodeIdArgWithTitleFilter");
        assertThat(filteredFilms).hasSize(1);
        assertThat(filteredFilms.get(0).get("title")).isEqualTo(onlyTitle);
    }

    @Test
    void filmsByEffectiveNullability_omittedFilter_returnsUnfilteredBaseline() {
        // R230: nullable enclosing `filter: FilmIdListFilter` carries a non-null inner
        // `filmIds: [Int!]!`. Pre-R230 the generator passed the inner field's own non-null
        // declaration straight into BodyParam.nonNull, so the condition method emitted an
        // unguarded `condition.and(film.film_id.in(null))` when the filter Map traversal
        // returned null. jOOQ renders `.in(null)` as the literal `false`, silently producing
        // an empty result set. Post-R230 the BodyParam carries effective runtime nullability
        // (AND of the enclosing chain), so the emitter wraps the condition in a null guard
        // and the unfiltered baseline (all 5 sakila films) survives.
        //
        // This is the only tier that observes jOOQ's `.in(null)` -> `false` rendering:
        // pipeline tier asserts on the classified BodyParam.nonNull slot, compilation tier
        // proves the source compiles, but only execution against real Postgres catches the
        // silent wrong-answer.
        Map<String, Object> data = execute(
            "{ filmsByEffectiveNullability { filmId } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsByEffectiveNullability");
        assertThat(films)
            .as("omitting the nullable filter must return the unfiltered baseline of 5 films, "
                + "not the empty set produced by the pre-R230 `film_id IN (null)` cascade")
            .hasSize(5);
    }

    @Test
    void filmsByNodeIdArg_malformedIds_surfacesClientErrorNotBaseline() {
        // R378 (inverts the former R375 baseline behaviour): an authored @nodeId filter now throws
        // on a malformed id rather than dropping it silently. filmsByNodeIdArg is a top-level fetch
        // field (schema line ~191); the throw propagates out of the WHERE-IN decode helper and the
        // no-channel catch arm routes it through ErrorRouter.surfaceClientErrorOrRedact, so the real
        // client-facing message reaches the response errors array and the field resolves to null.
        // This is the direct regression for the reported bug (soknadId: ["IKKE_EN_ID"] returned the
        // full table); the all-malformed case must NOT degrade into the empty-list baseline.
        graphql.ExecutionResult result = executeRaw(
            "{ filmsByNodeIdArg(ids: [\"garbage-1\", \"garbage-2\"]) { filmId title } }");
        assertThat(result.getErrors())
            .as("a malformed @nodeId filter id is a client error, surfaced not redacted")
            .isNotEmpty();
        assertThat(result.getErrors().get(0).getMessage())
            .contains("garbage-1")
            .contains("not a valid Film id")
            .doesNotContain("An error occurred. Reference:");
        assertFieldNullified(result, "filmsByNodeIdArg");
    }

    @Test
    void filmsByNodeIdArg_wrongTypeId_surfacesWrongTypeMessage() {
        // R378: a well-formed id of a sibling NodeType (FilmActor) handed to a Film @nodeId filter
        // decodes to the wrong type. The message names the type it decoded to, distinct from the
        // malformed branch, so a consumer can tell a typo'd id from a right-shape-wrong-type id.
        String filmActorId = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 1, 2);
        graphql.ExecutionResult result = executeRaw(
            "{ filmsByNodeIdArg(ids: [\"" + filmActorId + "\"]) { filmId } }");
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().get(0).getMessage())
            .contains("decodes to type \"FilmActor\"")
            .contains("expected a Film id")
            .doesNotContain("An error occurred. Reference:");
        assertFieldNullified(result, "filmsByNodeIdArg");
    }

    @Test
    void filmsByNodeIdArg_mixedValidAndMalformed_surfacesClientError() {
        // R378: the "any bad element" rule — one fat-fingered id fails the whole field even when a
        // sibling element is a valid Film id. No partial result; the field returns null with the
        // error naming the bad element.
        String validFilmId = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 1);
        graphql.ExecutionResult result = executeRaw(
            "{ filmsByNodeIdArg(ids: [\"" + validFilmId + "\", \"not-a-real-id\"]) { filmId } }");
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().get(0).getMessage())
            .contains("not-a-real-id")
            .contains("not a valid Film id");
        assertFieldNullified(result, "filmsByNodeIdArg");
    }

    @Test
    void filmsBySameTableNodeId_malformedId_surfacesClientError() {
        // R378: the other filter surface — an input-object-field [ID!] @nodeId filter (the
        // soknadId/HentSoknadInput shape). Exercises the BuildContext SameTable flip end-to-end:
        // the decode throw inside the filter helper surfaces through the no-channel catch arm.
        graphql.ExecutionResult result = executeRaw(
            "{ filmsBySameTableNodeId(filter: {filmIds: [\"IKKE_EN_ID\"]}) { filmId } }");
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().get(0).getMessage())
            .contains("IKKE_EN_ID")
            .contains("not a valid Film id")
            .doesNotContain("An error occurred. Reference:");
        assertFieldNullified(result, "filmsBySameTableNodeId");
    }

    // ===== R113: optional same-table @nodeId on @asConnection — composes =====

    @Test
    void filmsConnectionByOptionalIds_idsSupplied_paginatesBoundedSet() {
        // R113 acceptance: optional same-table @nodeId leaf composes with @asConnection. Three
        // ids supplied, first: 2 → page 1 has 2 of the 3 films and hasNextPage=true; following
        // the cursor returns the remaining 1 film with hasNextPage=false. The PK-IN filter
        // bounds the result; pagination runs over the bounded set.
        String id1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 1);
        String id3 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 3);
        String id5 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 5);
        Map<String, Object> page1 = execute(
            "{ filmsConnectionByOptionalIds(ids: [\"" + id1 + "\", \"" + id3 + "\", \"" + id5 + "\"], first: 2) "
            + "{ nodes { filmId } pageInfo { hasNextPage endCursor } } }");
        var conn1 = assertThat(page1).extractingByKey("filmsConnectionByOptionalIds", as(MAP));
        conn1.extractingByKey("nodes", as(list(Map.class)))
            .hasSize(2)
            .extracting(n -> n.get("filmId")).containsExactly(1, 3);
        String endCursor = (String) conn1.extractingByKey("pageInfo", as(MAP))
            .containsEntry("hasNextPage", true)
            .actual().get("endCursor");

        Map<String, Object> page2 = execute(
            "{ filmsConnectionByOptionalIds(ids: [\"" + id1 + "\", \"" + id3 + "\", \"" + id5 + "\"], "
            + "first: 2, after: \"" + endCursor + "\") { nodes { filmId } pageInfo { hasNextPage } } }");
        var conn2 = assertThat(page2).extractingByKey("filmsConnectionByOptionalIds", as(MAP));
        conn2.extractingByKey("nodes", as(list(Map.class)))
            .hasSize(1)
            .extracting(n -> n.get("filmId")).containsExactly(5);
        conn2.extractingByKey("pageInfo", as(MAP)).containsEntry("hasNextPage", false);
    }

    @Test
    void filmsConnectionByOptionalIds_idsOmitted_paginatesFullTable() {
        // R113 acceptance: caller-omitted optional @nodeId leaf drops the PK-IN filter; the
        // connection paginates the full film table. Test DB has 5 films; first: 3 returns 3
        // with hasNextPage=true.
        Map<String, Object> data = execute(
            "{ filmsConnectionByOptionalIds(first: 3) { nodes { filmId } pageInfo { hasNextPage } } }");
        var conn = assertThat(data).extractingByKey("filmsConnectionByOptionalIds", as(MAP));
        conn.extractingByKey("nodes", as(list(Map.class)))
            .hasSize(3)
            .extracting(n -> n.get("filmId")).containsExactly(1, 2, 3);
        conn.extractingByKey("pageInfo", as(MAP)).containsEntry("hasNextPage", true);
    }

    @Test
    void filmsConnectionByOptionalIds_idsNullExplicit_paginatesFullTable() {
        // R113 acceptance: explicit ids: null behaves like the omitted case. The PK-IN filter
        // is gated on a non-null id list (caller-side), so a null arg drops the filter and the
        // connection paginates the full table.
        Map<String, Object> data = execute(
            "{ filmsConnectionByOptionalIds(ids: null, first: 3) "
            + "{ nodes { filmId } pageInfo { hasNextPage } } }");
        var conn = assertThat(data).extractingByKey("filmsConnectionByOptionalIds", as(MAP));
        conn.extractingByKey("nodes", as(list(Map.class)))
            .hasSize(3)
            .extracting(n -> n.get("filmId")).containsExactly(1, 2, 3);
        conn.extractingByKey("pageInfo", as(MAP)).containsEntry("hasNextPage", true);
    }

    @Test
    void filmsConnectionByOptionalIds_idsEmptyList_paginatesFullTableAndCountsAll() {
        // R375 regression (matches the external bug report exactly): an @asConnection query
        // with a list @nodeId filter receiving an empty list [] must paginate the full table
        // and report the full total, not zero the query. Apollo Client serialises an empty
        // selection as [], so pre-R375 this silently dropped all rows (IN () = false AND-ed
        // into WHERE). The fix drops the empty list to noCondition on the fetch path; the count
        // path composes the same condition method, so totalCount sees the full table too.
        Map<String, Object> data = execute(
            "{ filmsConnectionByOptionalIds(ids: [], first: 10) "
            + "{ totalCount nodes { filmId } } }");
        var conn = assertThat(data).extractingByKey("filmsConnectionByOptionalIds", as(MAP));
        conn.extractingByKey("nodes", as(list(Map.class)))
            .hasSize(5)
            .extracting(n -> n.get("filmId")).containsExactly(1, 2, 3, 4, 5);
        conn.containsEntry("totalCount", 5);
    }

    @Test
    void filmsConnectionByOptionalIds_idsSuppliedWithSiblingFilter_composes() {
        // R113 acceptance: PK-IN filter from same-table @nodeId composes with a sibling
        // condition-driven filter (R106's "siblings compose" guarantee, now lifted onto the
        // connection rail). Three ids span films with three different titles; constrain by
        // one of those titles and a single film survives the AND.
        String id1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 1);
        String id2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 2);
        String id3 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 3);
        Map<String, Object> first = execute(
            "{ filmsConnectionByOptionalIds(ids: [\"" + id1 + "\", \"" + id2 + "\", \"" + id3 + "\"], first: 5) "
            + "{ nodes { filmId title } } }");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstFilms = (List<Map<String, Object>>)
            ((Map<String, Object>) first.get("filmsConnectionByOptionalIds")).get("nodes");
        String onlyTitle = (String) firstFilms.get(0).get("title");

        Map<String, Object> filtered = execute(
            "{ filmsConnectionByOptionalIds(ids: [\"" + id1 + "\", \"" + id2 + "\", \"" + id3 + "\"], "
            + "title: \"" + onlyTitle + "\", first: 5) { nodes { filmId title } } }");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filteredFilms = (List<Map<String, Object>>)
            ((Map<String, Object>) filtered.get("filmsConnectionByOptionalIds")).get("nodes");
        assertThat(filteredFilms).hasSize(1);
        assertThat(filteredFilms.get(0).get("title")).isEqualTo(onlyTitle);
    }

    // ===== R243: per-field direction in @defaultOrder(fields:) =====

    @Test
    void filmsOrderedConnection_mixedOrderEnumValue_ignoresRuntimeDirection() {
        // R243 direction-locked semantics: an @order enum value with per-field directions
        // (RATE_DESC_TITLE_ASC) carries uniformAsc = false on its resolved Fixed. The runtime
        // `direction:` argument on FilmOrderBy is ignored for that arm — both ASC and DESC
        // client inputs return the same SDL-baked order. The seed's rental_rate column varies
        // (4.99, a 2.99 three-way tie, 0.99), so the baked-in `rental_rate DESC, title ASC`
        // order is genuinely observable: ACE (4.99) leads, the 2.99 group is broken by title
        // ASC, ACADEMY (0.99) trails. Asserting that *both* runtime directions return this
        // exact order pins the opt-out semantics (no regression to multiplier semantics) AND
        // that the baked-in DESC actually takes effect (no regression to ASC).
        Map<String, Object> ascResult = execute(
            "{ filmsOrderedConnection(order: [{field: RATE_DESC_TITLE_ASC, direction: ASC}], first: 5) "
            + "{ nodes { title } } }");
        Map<String, Object> descResult = execute(
            "{ filmsOrderedConnection(order: [{field: RATE_DESC_TITLE_ASC, direction: DESC}], first: 5) "
            + "{ nodes { title } } }");
        var ascTitles = assertThat(ascResult).extractingByKey("filmsOrderedConnection", as(MAP))
            .extractingByKey("nodes", as(list(Map.class)))
            .extracting(n -> n.get("title"));
        var descTitles = assertThat(descResult).extractingByKey("filmsOrderedConnection", as(MAP))
            .extractingByKey("nodes", as(list(Map.class)))
            .extracting(n -> n.get("title"));
        ascTitles.containsExactly("ACE GOLDFINGER", "ADAPTATION HOLES", "AFFAIR PREJUDICE",
            "AGENT TRUMAN", "ACADEMY DINOSAUR");
        descTitles.containsExactly("ACE GOLDFINGER", "ADAPTATION HOLES", "AFFAIR PREJUDICE",
            "AGENT TRUMAN", "ACADEMY DINOSAUR");
    }

    @Test
    void filmsByRateDescTitleAsc_executesHeterogeneousOrder() {
        // R243 execution proof: @defaultOrder(fields: [{rental_rate DESC}, {title ASC}]) on the
        // filmsByRateDescTitleAsc connection emits per-entry direction in the generated jOOQ
        // call. The seed's rental_rate values are distinct enough to make both directions
        // observable: ACE GOLDFINGER (4.99) leads on the DESC primary, the 2.99 three-way tie
        // (ADAPTATION/AFFAIR/AGENT) is ordered by the ASC secondary, and ACADEMY DINOSAUR (0.99)
        // trails. This exact sequence differs from plain title ASC, from a primary-direction
        // regression (rate ASC would put ACADEMY first), and from a secondary-direction
        // regression (title DESC would reverse the 2.99 group) — so it independently pins both
        // per-entry directions, not just that the heterogeneous spec compiles and runs.
        Map<String, Object> data = execute(
            "{ filmsByRateDescTitleAsc(first: 5) { nodes { filmId title } } }");
        var conn = assertThat(data).extractingByKey("filmsByRateDescTitleAsc", as(MAP));
        conn.extractingByKey("nodes", as(list(Map.class)))
            .hasSize(5)
            .extracting(n -> n.get("title"))
            .containsExactly("ACE GOLDFINGER", "ADAPTATION HOLES", "AFFAIR PREJUDICE",
                "AGENT TRUMAN", "ACADEMY DINOSAUR");
    }

    @Test
    void filmsConnectionDesc_executesDescendingPrimaryKeyOrder() {
        // R339 execution proof: @defaultOrder(primaryKey: true, direction: DESC) must thread the
        // directive-level direction onto the synthesised PK entries, so the emitted jOOQ runs
        // film_id.desc() and keyset pagination seeks descending. The five seeded films return in
        // film_id order 5..1 — the exact reverse of the filmsConnection PK-ASC baseline (1..5).
        // A resolution-layer regression that drops direction: for primaryKey-mode would emit ASC
        // and return 1..5, breaking this test. A pipeline assertion alone would not catch a
        // seek/emission regression.
        Map<String, Object> data = execute(
            "{ filmsConnectionDesc(first: 5) { nodes { filmId } } }");
        var conn = assertThat(data).extractingByKey("filmsConnectionDesc", as(MAP));
        conn.extractingByKey("nodes", as(list(Map.class)))
            .hasSize(5)
            .extracting(n -> n.get("filmId"))
            .containsExactly(5, 4, 3, 2, 1);
    }

    @Test
    void filmsConnectionByRequiredIds_idsSupplied_paginatesBoundedSet() {
        // R113 production shape: required outer wrapper on a same-table @nodeId list arg
        // composed with @asConnection. Classifier emits a LOG.warn (pinned in
        // AsConnectionSameTableWarnFormatTest); runtime ships the WHERE pk IN (decoded_ids)
        // connection consumers expect. Three ids supplied with first: 2 → page 1 returns 2 of
        // the 3 films with hasNextPage=true; page 2 (after the cursor) returns the remaining 1
        // with hasNextPage=false. Pins that the warn does not block runtime correctness.
        String id1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 1);
        String id3 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 3);
        String id5 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 5);
        Map<String, Object> page1 = execute(
            "{ filmsConnectionByRequiredIds(ids: [\"" + id1 + "\", \"" + id3 + "\", \"" + id5 + "\"], first: 2) "
            + "{ nodes { filmId } pageInfo { hasNextPage endCursor } } }");
        var conn1 = assertThat(page1).extractingByKey("filmsConnectionByRequiredIds", as(MAP));
        conn1.extractingByKey("nodes", as(list(Map.class)))
            .hasSize(2)
            .extracting(n -> n.get("filmId")).containsExactly(1, 3);
        String endCursor = (String) conn1.extractingByKey("pageInfo", as(MAP))
            .containsEntry("hasNextPage", true)
            .actual().get("endCursor");

        Map<String, Object> page2 = execute(
            "{ filmsConnectionByRequiredIds(ids: [\"" + id1 + "\", \"" + id3 + "\", \"" + id5 + "\"], "
            + "first: 2, after: \"" + endCursor + "\") { nodes { filmId } pageInfo { hasNextPage } } }");
        var conn2 = assertThat(page2).extractingByKey("filmsConnectionByRequiredIds", as(MAP));
        conn2.extractingByKey("nodes", as(list(Map.class)))
            .hasSize(1)
            .extracting(n -> n.get("filmId")).containsExactly(5);
        conn2.extractingByKey("pageInfo", as(MAP)).containsEntry("hasNextPage", false);
    }

    @Test
    void filmsByNodeIdArg_emptyList_returnsUnfilteredBaseline() {
        // R375: filmsByNodeIdArg is a fetch field (R106 lifted the argument-level same-table
        // @nodeId onto the `WHERE film_id IN (...)` rail). An empty list narrows by nothing
        // (DSL.noCondition() identity) rather than emitting `IN () = false`, so the field returns
        // the unfiltered baseline of 5 films — the argument-level sibling of
        // films_filteredBySameTableNodeId_emptyListReturnsUnfilteredBaseline.
        Map<String, Object> data = execute("{ filmsByNodeIdArg(ids: []) { filmId title } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsByNodeIdArg");
        assertThat(films).hasSize(5);
    }

    @Test
    void films_filteredBySameTableNodeId_emptyListReturnsUnfilteredBaseline() {
        // R375: an empty list on a fetch-path list-IN filter narrows by nothing
        // (DSL.noCondition() identity) rather than emitting `IN () = false`, which jOOQ
        // renders as the constant `false` and would zero the query. This restores the G9
        // behaviour for fetch filters (Apollo serialising an empty selection as [] meant
        // "no filter"), reverting the deliberate G9→G10 deviation that aligned [] with the
        // unsatisfiable column-equality body. The null/omitted case already drops to
        // noCondition (R230); [] is now its list-arity sibling.
        Map<String, Object> data = execute(
            "{ filmsBySameTableNodeId(filter: {filmIds: []}) { filmId } }");
        assertThat(data).extractingByKey("filmsBySameTableNodeId", as(LIST)).hasSize(5);
    }

    @Test
    void films_filteredByRating() {
        // Test data: ACADEMY DINOSAUR=PG, ACE GOLDFINGER=G, ADAPTATION HOLES=NC_17,
        //            AFFAIR PREJUDICE=G, AGENT TRUMAN=PG
        Map<String, Object> data = execute("{ films(rating: G) { title } }");
        assertThat(data).extractingByKey("films", as(list(Map.class)))
            .extracting(f -> f.get("title"))
            .containsExactlyInAnyOrder("ACE GOLDFINGER", "AFFAIR PREJUDICE");
    }

    @Test
    void films_filteredByTextRating() {
        // TextRating enum maps to varchar column via @field(name:) — NC_17 → "NC-17"
        Map<String, Object> data = execute("{ films(textRating: NC_17) { title } }");
        assertThat(data).extractingByKey("films", as(list(Map.class)))
            .extracting(f -> f.get("title"))
            .containsExactly("ADAPTATION HOLES");
    }

    @Test
    void films_filteredByTextRating_simpleValue() {
        // G maps to "G" (no @field mapping needed)
        Map<String, Object> data = execute("{ films(textRating: G) { title } }");
        assertThat(data).extractingByKey("films", as(list(Map.class)))
            .extracting(f -> f.get("title"))
            .containsExactlyInAnyOrder("ACE GOLDFINGER", "AFFAIR PREJUDICE");
    }

    @Test
    void films_orderedByFilmId() {
        Map<String, Object> data = execute("{ films { title } }");
        assertThat(data).extractingByKey("films", as(list(Map.class)))
            .extracting(f -> f.get("title"))
            .containsExactly("ACADEMY DINOSAUR", "ACE GOLDFINGER", "ADAPTATION HOLES",
                "AFFAIR PREJUDICE", "AGENT TRUMAN");
    }

    @Test
    void films_selectsOnlyRequestedFields() {
        // Only request 'title' — should still work even though filmId etc. are not selected
        Map<String, Object> data = execute("{ films { title } }");
        assertThat(data).extractingByKey("films", as(LIST))
            .isNotEmpty()
            .first(as(MAP)).containsKey("title");
    }

    // ===== filmById lookup query =====

    @Test
    void filmById_returnsRequestedFilms() {
        Map<String, Object> data = execute("{ filmById(film_id: [\"1\", \"3\"]) { filmId title } }");
        // containsExactly (not InAnyOrder) — VALUES+JOIN preserves input order by joining on the
        // derived table's idx column. See docs/argument-resolution.md Phase 1.
        assertThat(data).extractingByKey("filmById", as(list(Map.class)))
            .hasSize(2)
            .extracting(f -> f.get("title"))
            .containsExactly("ACADEMY DINOSAUR", "ADAPTATION HOLES");
    }

    @Test
    void filmById_preservesInputOrder() {
        // VALUES+JOIN ordering evidence: request IDs in a non-sorted order and assert output order
        // matches input order. This is the one thing IN/EQ could not do, so it's the
        // behaviour-level proof that the emitter uses ordered VALUES+JOIN.
        Map<String, Object> data = execute("{ filmById(film_id: [\"3\", \"1\", \"2\"]) { filmId title } }");
        assertThat(data).extractingByKey("filmById", as(list(Map.class)))
            .extracting(f -> f.get("filmId"))
            .containsExactly(3, 1, 2);
    }

    @Test
    void filmById_singleId_returnsOneFilm() {
        Map<String, Object> data = execute("{ filmById(film_id: [\"2\"]) { title } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .hasSize(1)
            .first(as(MAP)).containsEntry("title", "ACE GOLDFINGER");
    }

    // ===== languageByKey lookup query =====

    @Test
    void languageByKey_returnsRequestedLanguages() {
        Map<String, Object> data = execute("{ languageByKey(language_id: [1, 2]) { languageId } }");
        assertThat(data).extractingByKey("languageByKey", as(list(Map.class)))
            .hasSize(2)
            .extracting(l -> l.get("languageId"))
            .containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void languageByKey_singleId_returnsOneLanguage() {
        Map<String, Object> data = execute("{ languageByKey(language_id: [3]) { languageId } }");
        assertThat(data).extractingByKey("languageByKey", as(LIST))
            .hasSize(1)
            .first(as(MAP)).containsEntry("languageId", 3);
    }

    // ===== customerById lookup query =====

    @Test
    void customerById_listKeyAndScalarKey_filtersCorrectly() {
        // Customers 1,2,4 are in store 1; 3,5 are in store 2
        Map<String, Object> data = execute(
            "{ customerById(customer_id: [\"1\", \"2\", \"4\", \"3\"], store_id: \"1\") { customerId } }");
        // Only IDs 1, 2, 4 are in store 1
        assertThat(data).extractingByKey("customerById", as(list(Map.class)))
            .hasSize(3)
            .extracting(c -> c.get("customerId"))
            .containsExactlyInAnyOrder(1, 2, 4);
    }

    @Test
    void customerById_noMatchForStore_returnsEmpty() {
        // Customer 3 is in store 2, requesting store 1 → no match
        Map<String, Object> data = execute(
            "{ customerById(customer_id: [\"3\"], store_id: \"1\") { customerId } }");
        assertThat(data).extractingByKey("customerById", as(LIST)).isEmpty();
    }

    // ===== filmsConnection — forward pagination =====

    @Test
    void filmsConnection_firstPage_returnsFirstNFilms() {
        Map<String, Object> data = execute(
            "{ filmsConnection(first: 2) { nodes { title } pageInfo { hasNextPage hasPreviousPage } } }");
        var conn = assertThat(data).extractingByKey("filmsConnection", as(MAP));
        conn.extractingByKey("nodes", as(list(Map.class)))
            .hasSize(2)
            .extracting(n -> n.get("title"))
            .containsExactly("ACADEMY DINOSAUR", "ACE GOLDFINGER");
        conn.extractingByKey("pageInfo", as(MAP))
            .containsEntry("hasNextPage", true)
            .containsEntry("hasPreviousPage", false);
    }

    @Test
    void filmsConnection_defaultPageSize_returnsUpToDefault() {
        // Default page size is 100; test DB has 5 films, so all 5 are returned
        Map<String, Object> data = execute(
            "{ filmsConnection { nodes { filmId } } }");
        assertThat(data).extractingByKey("filmsConnection", as(MAP))
            .extractingByKey("nodes", as(LIST)).hasSize(5);
    }

    @Test
    void filmsConnection_withAfterCursor_returnsNextPage() {
        // Get page 1 cursor, then use it to get page 2
        Map<String, Object> page1Data = execute(
            "{ filmsConnection(first: 2) { edges { cursor node { title } } pageInfo { endCursor } } }");
        String endCursor = assertThat(page1Data).extractingByKey("filmsConnection", as(MAP))
            .extractingByKey("pageInfo", as(MAP))
            .extractingByKey("endCursor", as(STRING))
            .isNotNull()
            .actual();

        Map<String, Object> page2Data = execute(
            "{ filmsConnection(first: 2, after: \"" + endCursor + "\") { nodes { title } pageInfo { hasNextPage } } }");
        var conn2 = assertThat(page2Data).extractingByKey("filmsConnection", as(MAP));
        conn2.extractingByKey("nodes", as(list(Map.class)))
            .extracting(n -> n.get("title"))
            .containsExactly("ADAPTATION HOLES", "AFFAIR PREJUDICE");
        conn2.extractingByKey("pageInfo", as(MAP))
            .containsEntry("hasNextPage", true);
    }

    @Test
    void filmsConnection_lastPage_hasNextPageFalse() {
        Map<String, Object> data = execute(
            "{ filmsConnection(first: 5) { nodes { title } pageInfo { hasNextPage } } }");
        var conn = assertThat(data).extractingByKey("filmsConnection", as(MAP));
        conn.extractingByKey("nodes", as(LIST)).hasSize(5);
        conn.extractingByKey("pageInfo", as(MAP)).containsEntry("hasNextPage", false);
    }

    // ===== filmsConnection — backward pagination =====

    @Test
    void filmsConnection_rejectsFirstAndLastTogether() {
        // Relay spec: must reject when both first and last are supplied. The connection helper
        // throws GraphitronClientException (R415, migrated from IllegalArgumentException), which
        // the no-channel disposition surfaces with the real message instead of redacting to a
        // correlation-id 500: a client mistake reads as a client error.
        var result = executeRaw(
            "{ filmsConnection(first: 2, last: 2) { nodes { title } } }");
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().get(0).getMessage())
            .isEqualTo("first and last must not both be specified");
    }

    @Test
    void filmsConnection_negativeFirst_surfacesClientError() {
        // R415: a negative page size is a client mistake and must not reach SQL LIMIT (which
        // would throw PostgreSQL's "LIMIT must not be negative" and redact into an opaque 500).
        // The pageRequest guard throws GraphitronClientException naming the argument and value.
        var result = executeRaw(
            "{ filmsConnection(first: -1) { nodes { title } } }");
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().get(0).getMessage())
            .isEqualTo("first must not be negative (was: -1)")
            .doesNotContain("An error occurred. Reference:");
    }

    @Test
    void filmsConnection_negativeLast_surfacesClientError() {
        var result = executeRaw(
            "{ filmsConnection(last: -1) { nodes { title } } }");
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().get(0).getMessage())
            .isEqualTo("last must not be negative (was: -1)");
    }

    @Test
    void filmsConnection_firstMaxInt_surfacesClientError() {
        // R415 derived-limit overflow guard: limit = pageSize + 1 wraps to Integer.MIN_VALUE at
        // Integer.MAX_VALUE, reaching SQL as a negative LIMIT — same redacted-500 family as the
        // negative inputs, guarded at the derived value.
        var result = executeRaw(
            "{ filmsConnection(first: 2147483647) { nodes { title } } }");
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().get(0).getMessage())
            .isEqualTo("page size must be less than 2147483647");
    }

    @Test
    void filmsConnection_firstZero_returnsEmptyPageWithoutError() {
        // first: 0 stays valid (R415 clamps only below zero): an empty page whose hasNextPage is
        // still computable from the limit+1 probe row.
        Map<String, Object> data = execute(
            "{ filmsConnection(first: 0) { nodes { title } pageInfo { hasNextPage } } }");
        var conn = assertThat(data).extractingByKey("filmsConnection", as(MAP));
        conn.extractingByKey("nodes", as(LIST)).isEmpty();
        conn.extractingByKey("pageInfo", as(MAP)).containsEntry("hasNextPage", true);
    }

    @Test
    void filmsConnection_backward_returnsLastNFilms() {
        Map<String, Object> data = execute(
            "{ filmsConnection(last: 2) { nodes { title } pageInfo { hasNextPage hasPreviousPage } } }");
        var conn = assertThat(data).extractingByKey("filmsConnection", as(MAP));
        // last 2 in ascending film_id order: AFFAIR PREJUDICE, AGENT TRUMAN
        conn.extractingByKey("nodes", as(list(Map.class)))
            .hasSize(2)
            .extracting(n -> n.get("title"))
            .containsExactly("AFFAIR PREJUDICE", "AGENT TRUMAN");
        conn.extractingByKey("pageInfo", as(MAP))
            .containsEntry("hasPreviousPage", true)
            .containsEntry("hasNextPage", false);
    }

    @Test
    void filmsConnection_backward_withBeforeCursor_returnsPrevPage() {
        // First get the last page to obtain a before cursor (startCursor of last page)
        Map<String, Object> lastPageData = execute(
            "{ filmsConnection(last: 2) { nodes { title } pageInfo { startCursor } } }");
        String startCursor = assertThat(lastPageData).extractingByKey("filmsConnection", as(MAP))
            .extractingByKey("pageInfo", as(MAP))
            .extractingByKey("startCursor", as(STRING))
            .isNotNull()
            .actual();

        // Paginate backwards before that cursor
        Map<String, Object> prevPageData = execute(
            "{ filmsConnection(last: 2, before: \"" + startCursor + "\") { nodes { title } } }");
        // last: 2 returns [AFFAIR PREJUDICE (4), AGENT TRUMAN (5)]; startCursor = cursor(4).
        // "2 items before cursor(4)" in ascending order = items 2, 3: ACE GOLDFINGER, ADAPTATION HOLES.
        assertThat(prevPageData).extractingByKey("filmsConnection", as(MAP))
            .extractingByKey("nodes", as(list(Map.class)))
            .extracting(n -> n.get("title"))
            .containsExactly("ACE GOLDFINGER", "ADAPTATION HOLES");
    }

    // ===== filmsConnection / stores — totalCount =====

    @Test
    void filmsConnection_totalCount_returnsTotalRowCount() {
        // Structural FilmsConnection declares totalCount: Int; root fetcher binds (table, condition)
        // onto ConnectionResult so ConnectionHelper.totalCount issues SELECT count(*) on demand.
        Map<String, Object> data = execute(
            "{ filmsConnection { totalCount } }");
        assertThat(data).extractingByKey("filmsConnection", as(MAP))
            .containsEntry("totalCount", 5);
    }

    @Test
    void filmsOrderedConnection_totalCount_underFilter_appliesSamePredicate() {
        // SELECT count(*) is run with the same Condition the page query used; rating=G filter
        // restricts the count to the 2 G-rated films in the seed data.
        Map<String, Object> data = execute(
            "{ filmsOrderedConnection(rating: G, first: 1) { totalCount nodes { title } } }");
        var conn = assertThat(data).extractingByKey("filmsOrderedConnection", as(MAP));
        conn.containsEntry("totalCount", 2);
        conn.extractingByKey("nodes", as(LIST)).hasSize(1); // page-trimmed by first:1
    }

    @Test
    void synthesisedConnection_totalCount_returnsRowCount() {
        // `stores: [Store!]! @asConnection` synthesises QueryStoresConnection which always carries
        // totalCount: Int. Two stores are seeded.
        Map<String, Object> data = execute(
            "{ stores { totalCount } }");
        assertThat(data).extractingByKey("stores", as(MAP))
            .containsEntry("totalCount", 2);
    }

    @Test
    void filmsConnection_totalCount_isLazyOnSelection_noCountSqlWhenUnselected() {
        SQL_LOG.clear();
        execute("{ filmsConnection(first: 2) { nodes { title } pageInfo { hasNextPage } } }");
        // graphql-java only invokes the totalCount resolver when the client picks the field.
        // No `select count` should appear among the rendered statements for this query.
        assertThat(SQL_LOG)
            .as("no SELECT count statement should be issued when totalCount is not selected")
            .noneMatch(s -> s.contains("select count"));
    }

    @Test
    void filmsConnection_totalCount_selected_doesIssueCountSql() {
        // Companion ratchet: when totalCount IS selected, exactly one `select count` runs.
        SQL_LOG.clear();
        execute("{ filmsConnection { totalCount } }");
        assertThat(SQL_LOG)
            .filteredOn(s -> s.contains("select count"))
            .as("selecting totalCount should issue exactly one SELECT count statement")
            .hasSize(1);
    }

    // ===== filmsOrderedConnection — dynamic ordering pagination =====

    @Test
    void filmsOrderedConnection_defaultOrder_paginatesById() {
        Map<String, Object> data = execute(
            "{ filmsOrderedConnection(first: 2) { nodes { filmId title } } }");
        assertThat(data).extractingByKey("filmsOrderedConnection", as(MAP))
            .extractingByKey("nodes", as(list(Map.class)))
            .hasSize(2)
            .extracting(n -> n.get("title"))
            .containsExactly("ACADEMY DINOSAUR", "ACE GOLDFINGER");
    }

    @Test
    void filmsOrderedConnection_orderByTitle_paginatesAlphabetically() {
        Map<String, Object> data = execute(
            "{ filmsOrderedConnection(order: [{field: TITLE, direction: ASC}], first: 3) { nodes { title } } }");
        // ACADEMY DINOSAUR < ACE GOLDFINGER < ADAPTATION HOLES alphabetically
        assertThat(data).extractingByKey("filmsOrderedConnection", as(MAP))
            .extractingByKey("nodes", as(list(Map.class)))
            .hasSize(3)
            .extracting(n -> n.get("title"))
            .containsExactly("ACADEMY DINOSAUR", "ACE GOLDFINGER", "ADAPTATION HOLES");
    }

    @Test
    void filmsOrderedConnection_filterPlusOrderPlusPagination_combinesAllThree() {
        // Exercises buildFilters + buildOrderBySpec + buildPaginationSpec on one field.
        // Seed data: two G-rated films — ACE GOLDFINGER, AFFAIR PREJUDICE.
        Map<String, Object> data = execute(
            "{ filmsOrderedConnection(rating: G, order: [{field: TITLE, direction: ASC}], first: 1) { " +
            "nodes { title } pageInfo { hasNextPage } } }");
        var conn = assertThat(data).extractingByKey("filmsOrderedConnection", as(MAP));
        conn.extractingByKey("nodes", as(list(Map.class)))
            .extracting(n -> n.get("title")).containsExactly("ACE GOLDFINGER");
        conn.extractingByKey("pageInfo", as(MAP))
            .containsEntry("hasNextPage", true);
    }

    @Test
    void filmsOrderedConnection_orderByTitle_cursorNavigation() {
        // Get page 1 ordered by title, then follow cursor
        Map<String, Object> page1Data = execute(
            "{ filmsOrderedConnection(order: [{field: TITLE, direction: ASC}], first: 2) { " +
            "nodes { title } pageInfo { endCursor hasNextPage } } }");
        var pageInfo1 = assertThat(page1Data).extractingByKey("filmsOrderedConnection", as(MAP))
            .extractingByKey("pageInfo", as(MAP));
        String endCursor = pageInfo1.extractingByKey("endCursor", as(STRING)).isNotNull().actual();
        pageInfo1.containsEntry("hasNextPage", true);

        Map<String, Object> page2Data = execute(
            "{ filmsOrderedConnection(order: [{field: TITLE, direction: ASC}], first: 2, after: \"" +
            endCursor + "\") { nodes { title } } }");
        assertThat(page2Data).extractingByKey("filmsOrderedConnection", as(MAP))
            .extractingByKey("nodes", as(list(Map.class)))
            .extracting(n -> n.get("title"))
            .containsExactly("ADAPTATION HOLES", "AFFAIR PREJUDICE");
    }

    // ===== G5 inline TableField — single-hop FK =====

    @Test
    void inlineTableField_singleHopFk_returnsNestedRecord() {
        // Customer 1 is in store 1, with address_id=1 → '47 MySakila Drive'
        Map<String, Object> data = execute(
            "{ customerById(customer_id: [\"1\"], store_id: \"1\") { customerId address { addressId address } } }");
        assertThat(data).extractingByKey("customerById", as(LIST))
            .hasSize(1)
            .first(as(MAP)).extractingByKey("address", as(MAP))
            .isNotNull()
            .containsEntry("addressId", 1)
            .containsEntry("address", "47 MySakila Drive");
    }

    // ===== G5 inline TableField — multi-hop FK =====

    @Test
    void inlineTableField_multiHopFk_walksTwoFkHops() {
        // customer 1, store 1, address 1 '47 MySakila Drive'; customer 2, store 1, address 2.
        Map<String, Object> data = execute(
            "{ customerById(customer_id: [\"1\", \"2\"], store_id: \"1\") { customerId storeAddress { address } } }");
        assertThat(data).extractingByKey("customerById", as(list(Map.class)))
            .hasSize(2)
            .filteredOn(c -> Integer.valueOf(1).equals(c.get("customerId")))
            .singleElement(as(MAP))
            .extractingByKey("storeAddress", as(MAP))
            .containsEntry("address", "47 MySakila Drive");
    }

    // ===== G5 inline TableField — single-hop FK, list cardinality =====

    @Test
    void inlineTableField_listCardinality_returnsAllChildren() {
        // Store 1 holds customers 1, 2, 4. Store 2 holds 3, 5. Order by customer_id (PK).
        Map<String, Object> data = execute(
            "{ storeById(store_id: [1]) { storeId customers { customerId firstName } } }");
        assertThat(data).extractingByKey("storeById", as(LIST))
            .hasSize(1)
            .first(as(MAP)).extractingByKey("customers", as(list(Map.class)))
            .extracting(c -> c.get("customerId"))
            .containsExactly(1, 2, 4);
    }

    // ===== G5 inline TableField — self-referential recursion =====

    @Test
    void inlineTableField_selfRef_depth2_recursionTerminatesOnSelectionSet() {
        // Category tree:
        //   Genre (id=1)
        //   └── Action (id=2)
        //       └── Thriller (id=5)
        // Depth-2 query: start at Thriller, walk parent → parent. Verifies Plan Decision 5's
        // "recursion terminates on client selection depth" invariant end-to-end.
        Map<String, Object> data = execute(
            "{ categoryById(category_id: [5]) { name parent { name parent { name } } } }");
        var thriller = assertThat(data).extractingByKey("categoryById", as(LIST))
            .hasSize(1)
            .first(as(MAP));
        thriller.containsEntry("name", "Thriller");
        thriller.extractingByKey("parent", as(MAP))
            .containsEntry("name", "Action")
            .extractingByKey("parent", as(MAP))
            .containsEntry("name", "Genre");
    }

    @Test
    void inlineTableField_selfRef_listCardinality_returnsChildren() {
        // Genre (id=1) has children: Action, Animation, Comedy (ids 2, 3, 4).
        Map<String, Object> data = execute(
            "{ categoryById(category_id: [1]) { name children { name } } }");
        var genre = assertThat(data).extractingByKey("categoryById", as(LIST))
            .hasSize(1)
            .first(as(MAP));
        genre.containsEntry("name", "Genre");
        genre.extractingByKey("children", as(list(Map.class)))
            .extracting(c -> c.get("name"))
            .containsExactly("Action", "Animation", "Comedy");
    }

    @Test
    void inlineTableField_selfRef_nonRootCategory_hasNoChildren() {
        // Thriller (id=5) is a leaf — children list is empty.
        Map<String, Object> data = execute(
            "{ categoryById(category_id: [5]) { name children { name } } }");
        assertThat(data).extractingByKey("categoryById", as(LIST))
            .first(as(MAP))
            .extractingByKey("children", as(LIST)).isEmpty();
    }

    @Test
    void inlineTableField_selfRef_optionalParent_nullable() {
        // Genre (id=1) has no parent — parent is null.
        Map<String, Object> data = execute(
            "{ categoryById(category_id: [1]) { name parent { name } } }");
        assertThat(data).extractingByKey("categoryById", as(LIST))
            .first(as(MAP))
            .extractingByKey("parent").isNull();
    }

    // ===== argres Phase 2a — inline LookupTableField (Film.actors via film_actor junction) =====

    @Test
    void inlineLookupTableField_returnsMatchingActors() {
        // Film 1 (ACADEMY DINOSAUR) cast: PENELOPE (id=1), NICK (id=2).
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { actors(actor_id: [1, 2]) { actorId firstName } } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP)).extractingByKey("actors", as(list(Map.class)))
            .extracting(a -> a.get("firstName"))
            .containsExactlyInAnyOrder("PENELOPE", "NICK");
    }

    @Test
    void inlineLookupTableField_preservesInputOrder() {
        // Input [2, 1] should return NICK before PENELOPE.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { actors(actor_id: [2, 1]) { firstName } } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP)).extractingByKey("actors", as(list(Map.class)))
            .extracting(a -> a.get("firstName"))
            .containsExactly("NICK", "PENELOPE");
    }

    @Test
    void inlineLookupTableField_fkFilter_excludesActorsNotInFilm() {
        // Film 1 cast: actors 1, 2. Actor 3 (ED) is not in film 1 — the FK chain drops him.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { actors(actor_id: [1, 3]) { firstName } } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP)).extractingByKey("actors", as(list(Map.class)))
            .extracting(a -> a.get("firstName")).containsExactly("PENELOPE");
    }

    @Test
    void inlineLookupTableField_emptyInput_returnsEmpty() {
        // R375: the lookup side of the fetch/lookup empty-list divergence. On a lookup field the
        // input rows ARE the FROM-side of a VALUES…JOIN, so [] is an empty join domain (0 rows),
        // not "no filter". This stays empty even as fetch-path list-IN filters drop [] to
        // noCondition (see filmsConnectionByOptionalIds_idsEmptyList_paginatesFullTableAndCountsAll).
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { actors(actor_id: []) { firstName } } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP)).extractingByKey("actors", as(LIST)).isEmpty();
    }

    @Test
    void inlineLookupTableField_nullInput_returnsEmpty() {
        // actor_id is optional; omitting it should short-circuit to an empty list (n=0 path).
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { actors { firstName } } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP)).extractingByKey("actors", as(LIST)).isEmpty();
    }

    @Test
    void inlineLookupTableField_acrossMultipleParents_perFilmFiltering() {
        // Film 2 (ACE GOLDFINGER) cast: PENELOPE (1), ED (3). Film 3 cast: PENELOPE only.
        // Same input [1, 3] on both → film 2 has both; film 3 has only PENELOPE.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"2\", \"3\"]) { filmId actors(actor_id: [1, 3]) { firstName } } }");
        var films = assertThat(data).extractingByKey("filmById", as(list(Map.class))).hasSize(2);

        var film2 = films.element(0, as(MAP)).containsEntry("filmId", 2);
        film2.extractingByKey("actors", as(list(Map.class)))
            .extracting(a -> a.get("firstName"))
            .containsExactlyInAnyOrder("PENELOPE", "ED");

        var film3 = films.element(1, as(MAP)).containsEntry("filmId", 3);
        film3.extractingByKey("actors", as(list(Map.class)))
            .extracting(a -> a.get("firstName")).containsExactly("PENELOPE");
    }

    // ===== argres Phase 2b: Split(Lookup)TableField DataLoader fan-out =====

    @Test
    void splitTableField_singleParent_returnsItsChildren() {
        // Language.films (SplitTableField) — language 1 has films 1-5 seeded.
        Map<String, Object> data = execute(
            "{ languageByKey(language_id: [1]) { languageId films { filmId } } }");
        assertThat(data).extractingByKey("languageByKey", as(LIST))
            .hasSize(1)
            .first(as(MAP)).extractingByKey("films", as(list(Map.class)))
            .extracting(f -> f.get("filmId"))
            .containsExactlyInAnyOrder(1, 2, 3, 4, 5);
    }

    @Test
    void splitTableField_multipleParents_scatterPerParent() {
        // languages 1, 2, 3 — only language 1 has films in the seed. DataLoader batches the
        // three parent lookups into one SQL round-trip; the scatter correctly assigns all
        // films to language 1 and empty lists to languages 2 and 3 (no cross-contamination).
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ languageByKey(language_id: [1, 2, 3]) { languageId films { filmId } } }");
        // Expect 2 JDBC round-trips: 1 for languageByKey root + 1 batched for films. An
        // unbatched scatter would fire 1 + N=3 = 4. This is the primary proof that the
        // DataLoader fan-in works — the value assertions below only prove scatter correctness.
        assertThat(QUERY_COUNT.get()).isEqualTo(2);

        var langs = assertThat(data).extractingByKey("languageByKey", as(list(Map.class))).hasSize(3);
        langs.filteredOn(l -> Integer.valueOf(1).equals(l.get("languageId")))
            .singleElement(as(MAP))
            .extractingByKey("films", as(LIST)).hasSize(5);
        langs.filteredOn(l -> Integer.valueOf(2).equals(l.get("languageId")))
            .singleElement(as(MAP))
            .extractingByKey("films", as(LIST)).isEmpty();
        langs.filteredOn(l -> Integer.valueOf(3).equals(l.get("languageId")))
            .singleElement(as(MAP))
            .extractingByKey("films", as(LIST)).isEmpty();
    }

    @Test
    void splitTableField_preservesParentInputOrder_scatterAlignsByIdx() {
        // Non-identity parent order: [3, 1, 2] — only language 1 has films. If the scatter
        // keyed children by parent-PK instead of __idx__, the films array would land on a
        // different slot than the language-1 slot. Asserts both (a) parent order preservation
        // from VALUES+JOIN on the root lookup, and (b) __idx__ scatter alignment on the child
        // DataLoader.
        Map<String, Object> data = execute(
            "{ languageByKey(language_id: [3, 1, 2]) { languageId films { filmId } } }");
        var langs = assertThat(data).extractingByKey("languageByKey", as(list(Map.class)));
        langs.extracting(l -> l.get("languageId")).containsExactly(3, 1, 2);
        langs.element(0, as(MAP)).extractingByKey("films", as(LIST)).isEmpty();
        langs.element(1, as(MAP)).extractingByKey("films", as(LIST)).hasSize(5);
        langs.element(2, as(MAP)).extractingByKey("films", as(LIST)).isEmpty();
    }

    @Test
    void splitTableField_fkReferencesNonPkUniqueKey_returnsChildRows() {
        // R338: SplitParent.tags (@splitQuery) whose FK split_parent_tag.parent_code references
        // split_parent.parent_code — a non-PK UNIQUE column, not the parent_id PK. The split-rows
        // fetcher must project parent_code (the FK's referenced column) into parentInput and
        // correlate on it. Before the fix, parentInput was keyed by the parent PK, the correlation
        // predicate referenced an absent parentInput column, jOOQ resolved it to NULL, and every
        // parent returned an empty list. The seed gives ALPHA two tags and BETA one.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ splitParents { parentCode tags { tag } } }");
        // 2 round-trips: the splitParents root + one batched DataLoader fetch for tags. Proves the
        // batch fan-in still works while the per-parent scatter keys off the unique-key value.
        assertThat(QUERY_COUNT.get()).isEqualTo(2);

        var parents = assertThat(data).extractingByKey("splitParents", as(list(Map.class)));
        parents.filteredOn(p -> "ALPHA".equals(p.get("parentCode")))
            .singleElement(as(MAP))
            .extractingByKey("tags", as(list(Map.class)))
            .extracting(t -> t.get("tag"))
            .containsExactlyInAnyOrder("a-one", "a-two");
        parents.filteredOn(p -> "BETA".equals(p.get("parentCode")))
            .singleElement(as(MAP))
            .extractingByKey("tags", as(list(Map.class)))
            .extracting(t -> t.get("tag"))
            .containsExactly("b-one");
    }

    // ===== R413: @splitQuery / @sourceRow over a converter-backed domain key =====
    //
    // converter_campus.org_code -> converter_org.org_code is a BIGINT domain (org_code_domain)
    // whose jOOQ columns carry Converter<Long, String> (OrgCodeStringConverter), so the DataLoader
    // key is Row1<String> while the SQL type is the domain. The rows method's parent-input VALUES
    // cells must rebind each scalar at the column's Converter DataType; before R413 the raw key
    // Field bound as varchar and PostgreSQL rejected the correlation JOIN with "operator does not
    // exist: org_code_domain = character varying", nulling every child out.

    @SuppressWarnings("unchecked")
    @Test
    void splitSingle_converterBackedFkKey_bindsThroughConverterDataType() {
        // Single-cardinality parent-holds-FK: the reported Campus.organisasjon shape.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ converterCampuses { campusName organisation { orgName } } }");
        // 2 round-trips: converterCampuses root + one batched organisation fetch.
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
        assertThat(data).extractingByKey("converterCampuses", as(list(Map.class)))
            .extracting(c -> c.get("campusName"),
                c -> ((Map<String, Object>) c.get("organisation")).get("orgName"))
            .containsExactly(
                tuple("Tromsø", "UiT"),
                tuple("Trondheim", "NTNU"),
                tuple("Gjøvik", "NTNU"));
    }

    @Test
    void splitList_converterBackedFkKey_bindsThroughConverterDataType() {
        // List-cardinality child-holds-FK over the same converter-backed key.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ converterOrgs { orgName campuses { campusName } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
        var orgs = assertThat(data).extractingByKey("converterOrgs", as(list(Map.class)));
        orgs.filteredOn(o -> "UiT".equals(o.get("orgName")))
            .singleElement(as(MAP))
            .extractingByKey("campuses", as(list(Map.class)))
            .extracting(c -> c.get("campusName"))
            .containsExactly("Tromsø");
        orgs.filteredOn(o -> "NTNU".equals(o.get("orgName")))
            .singleElement(as(MAP))
            .extractingByKey("campuses", as(list(Map.class)))
            .extracting(c -> c.get("campusName"))
            .containsExactly("Trondheim", "Gjøvik");
    }

    @SuppressWarnings("unchecked")
    @Test
    void sourceRowLifter_converterBackedLeafPkKey_extractsParamValueAndBinds() {
        // @sourceRow leaf-PK arm: the lifter's Row1<String> cells are DSL.row(value) bind Params;
        // the generated parentKeyCellValue helper extracts the scalar and rebinds it at
        // converter_org.org_code's Converter DataType. Live pin for the Param contract — a lifter
        // building its RowN from column references would throw the helper's IllegalStateException
        // instead of silently mistyping the bind.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ converterOrgSummaries { orgCode org { orgName } } }");
        // 1 round-trip: the service hand-rolls the payloads; only the batched org fetch hits JDBC
        // (three parents dedup to two distinct keys in one query).
        assertThat(QUERY_COUNT.get()).isEqualTo(1);
        assertThat(data).extractingByKey("converterOrgSummaries", as(list(Map.class)))
            .extracting(s -> s.get("orgCode"),
                s -> ((List<Map<String, Object>>) s.get("org")).stream()
                    .map(o -> o.get("orgName")).toList())
            .containsExactly(
                tuple("1120", List.of("NTNU")),
                tuple("186", List.of("UiT")),
                tuple("1120", List.of("NTNU")));
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitLookupTableField_filtersActorsPerFilm() {
        // Film 1 cast: PENELOPE (1), NICK (2). Film 2 cast: PENELOPE (1), ED (3).
        // Film 3 cast: PENELOPE (1). actor_id: [1, 2] → film 1 gets {1,2}; film 2 gets {1};
        // film 3 gets {1}.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ films { filmId actorsBySplitLookup(actor_id: [1, 2]) { actorId } } }");
        // 5 parent films + 1 batched SplitLookup child = 2 round-trips. Unbatched: 1 + 5 = 6.
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");

        var byId = films.stream().collect(java.util.stream.Collectors.toMap(
            f -> (Integer) f.get("filmId"), f -> (List<Map<String, Object>>) f.get("actorsBySplitLookup")));

        assertThat(byId.get(1)).extracting(a -> a.get("actorId")).containsExactlyInAnyOrder(1, 2);
        assertThat(byId.get(2)).extracting(a -> a.get("actorId")).containsExactly(1);
        assertThat(byId.get(3)).extracting(a -> a.get("actorId")).containsExactly(1);
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitLookupTableField_filterExcludesActorsNotInFilm() {
        // actor_id: [3] → only films 2 and 5 have actor 3. Films 1, 3, 4 return empty lists;
        // scatter correctly places empty sublists in their slots.
        Map<String, Object> data = execute(
            "{ films { filmId actorsBySplitLookup(actor_id: [3]) { actorId } } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");

        var byId = films.stream().collect(java.util.stream.Collectors.toMap(
            f -> (Integer) f.get("filmId"), f -> (List<Map<String, Object>>) f.get("actorsBySplitLookup")));

        assertThat(byId.get(1)).isEmpty();
        assertThat(byId.get(2)).extracting(a -> a.get("actorId")).containsExactly(3);
        assertThat(byId.get(3)).isEmpty();
        assertThat(byId.get(4)).isEmpty();
        assertThat(byId.get(5)).extracting(a -> a.get("actorId")).containsExactly(3);
    }

    @Test
    void splitLookupTableField_emptyLookupInput_returnsEmptyPerFilm() {
        // Empty @lookupKey list → emptyScatter short-circuit. No DB round-trip for the
        // lookup join; every parent gets an empty sublist.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ films { filmId actorsBySplitLookup(actor_id: []) { actorId } } }");
        // Parent query only — the empty-input short-circuit returns emptyScatter without
        // touching DSL, so no child round-trip fires.
        assertThat(QUERY_COUNT.get()).isEqualTo(1);
        assertThat(data).extractingByKey("films", as(list(Map.class)))
            .allSatisfy(f -> assertThat(f.get("actorsBySplitLookup")).asInstanceOf(LIST).isEmpty());
    }

    @Test
    void splitLookupTableField_nullLookupInput_returnsEmptyPerFilm() {
        // Omitting the @lookupKey arg → null → env.getArgument returns null → rowCount=0 →
        // inputRows helper returns new Row[0] → emptyScatter short-circuit.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ films { filmId actorsBySplitLookup { actorId } } }");
        // Same short-circuit as the empty-list case — parent query only.
        assertThat(QUERY_COUNT.get()).isEqualTo(1);
        assertThat(data).extractingByKey("films", as(list(Map.class)))
            .allSatisfy(f -> assertThat(f.get("actorsBySplitLookup")).asInstanceOf(LIST).isEmpty());
    }

    // ===== single-cardinality @splitQuery =====

    @SuppressWarnings("unchecked")
    @Test
    void splitTableField_singleCardinality_returnsAddressPerCustomer() {
        // Customer.addressSplit (SplitTableField, single cardinality, parent-holds-FK):
        // each customer resolves to its own address. Seeded customer→address mapping:
        //   c1→a1  c2→a2  c3→a3  c4→a1 (shared)  c5→a2 (shared)
        Map<String, Object> data = execute(
            "{ customers { customerId addressSplit { addressId } } }");
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customers");
        var byId = customers.stream().collect(java.util.stream.Collectors.toMap(
            c -> (Integer) c.get("customerId"),
            c -> (Map<String, Object>) c.get("addressSplit")));
        assertThat(byId.get(1).get("addressId")).isEqualTo(1);
        assertThat(byId.get(2).get("addressId")).isEqualTo(2);
        assertThat(byId.get(3).get("addressId")).isEqualTo(3);
        assertThat(byId.get(4).get("addressId")).isEqualTo(1);  // shared with c1
        assertThat(byId.get(5).get("addressId")).isEqualTo(2);  // shared with c2
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitTableField_singleCardinality_multiHop_bridgesToTerminalAddressPerCustomer() {
        // R324: Customer.storeAddressSplit (SplitTableField, single cardinality, multi-hop
        // parent-holds-FK: customer -> store -> address). The rows-method keys by customer.store_id,
        // bridges store -> address via store_address_id_fkey, and scatters the terminal Address 1:1.
        // Seeded chain: c1/c2/c4 -> store 1 -> address 1 (47 MySakila Drive);
        //               c3/c5    -> store 2 -> address 2 (28 MySQL Boulevard).
        // Must match the inline (non-split) Customer.storeAddress navigation exactly.
        Map<String, Object> data = execute(
            "{ customers { customerId storeAddressSplit { addressId address } } }");
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customers");
        var byId = customers.stream().collect(java.util.stream.Collectors.toMap(
            c -> (Integer) c.get("customerId"),
            c -> (Map<String, Object>) c.get("storeAddressSplit")));
        assertThat(byId.get(1)).containsEntry("addressId", 1).containsEntry("address", "47 MySakila Drive");
        assertThat(byId.get(2)).containsEntry("addressId", 1).containsEntry("address", "47 MySakila Drive");
        assertThat(byId.get(3)).containsEntry("addressId", 2).containsEntry("address", "28 MySQL Boulevard");
        assertThat(byId.get(4)).containsEntry("addressId", 1).containsEntry("address", "47 MySakila Drive");
        assertThat(byId.get(5)).containsEntry("addressId", 2).containsEntry("address", "28 MySQL Boulevard");
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitTableField_singleCardinality_multiHop_dedupesSharedKey_oneBatchRoundTrip() {
        // Five customers hit the storeAddressSplit DataLoader, but only two distinct store_id keys
        // (store 1: c1,c2,c4; store 2: c3,c5). Caching-enabled dedup collapses to one batched
        // rows-method round-trip: 1 (customers) + 1 (batched storeAddressSplit) = 2. An un-batched
        // scatter would fire 1 + 5 = 6.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ customers { customerId storeAddressSplit { addressId } } }");
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customers");
        assertThat(customers).hasSize(5);
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
    }

    // ===== R232 condition-join execution-tier coverage =====

    @SuppressWarnings("unchecked")
    @Test
    void inlineTableField_conditionJoin_returnsAddressPerCustomer() {
        // R232: Customer.addressByCondition (inline TableField + ConditionJoin first hop).
        // The condition method `customerToAddress` expresses customer.address_id =
        // address.address_id as a two-arg jOOQ predicate; the emitter routes step-0 correlation
        // through the condition method (DSL.multiset(...) wrapping a correlated SELECT whose
        // WHERE-side predicate IS the condition method's return value). Results must match the
        // FK-equivalent Customer.address navigation exactly.
        Map<String, Object> data = execute(
            "{ customers { customerId addressByCondition { addressId } address { addressId } } }");
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customers");
        assertThat(customers).hasSize(5);
        for (Map<String, Object> c : customers) {
            var byCondition = (Map<String, Object>) c.get("addressByCondition");
            var byFk = (Map<String, Object>) c.get("address");
            assertThat(byCondition)
                .as("Customer %s: addressByCondition (ConditionJoin) returns same row as address (FK)", c.get("customerId"))
                .isEqualTo(byFk);
        }
    }

    // ===== R380: @reference join-subquery filter conditions =====

    @SuppressWarnings("unchecked")
    @Test
    void referenceFilter_scalarArg_singleHop_filtersByJoinedColumn() {
        // R380 Surface 2: citiesByCountryName(countryName) filters City by country.country reached
        // through city.country_id -> country, via a correlated EXISTS. Seed: Italy -> Rome only.
        Map<String, Object> data = execute("{ citiesByCountryName(countryName: \"Italy\") { cityName } }");
        List<Map<String, Object>> cities = (List<Map<String, Object>>) data.get("citiesByCountryName");
        assertThat(cities).extracting(c -> c.get("cityName")).containsExactly("Rome");
    }

    @SuppressWarnings("unchecked")
    @Test
    void referenceFilter_scalarArg_multiHop_filtersByTwoHopJoinedColumn() {
        // R380 Surface 2, two-hop: addressesByCountryName filters Address by country.country through
        // address -> city -> country. Seed: only '28 MySQL Boulevard' (city Rome) is in Italy.
        Map<String, Object> data = execute("{ addressesByCountryName(countryName: \"Italy\") { address } }");
        List<Map<String, Object>> addresses = (List<Map<String, Object>>) data.get("addressesByCountryName");
        assertThat(addresses).extracting(a -> a.get("address")).containsExactly("28 MySQL Boulevard");
    }

    @SuppressWarnings("unchecked")
    @Test
    void referenceFilter_inputObjectField_filtersByJoinedColumn() {
        // R380 Surface 1 (the motivating bug): the @reference filter field lives inside a @table
        // input object; the terminal column (country.country) is absent from the local city table.
        // A pre-R380 build mis-bound it against city and failed to compile the conditions class.
        Map<String, Object> data = execute(
            "{ citiesByCountryFilter(filter: {countryName: \"United States\"}) { cityName } }");
        List<Map<String, Object>> cities = (List<Map<String, Object>>) data.get("citiesByCountryFilter");
        assertThat(cities).extracting(c -> c.get("cityName")).containsExactly("Lethbridge");
    }

    @SuppressWarnings("unchecked")
    @Test
    void referenceFilter_absentArgument_returnsAllRows() {
        // Null arg contributes no predicate (the EXISTS term is guarded by the null check), so an
        // omitted countryName returns every city — proof the null/empty-list semantics carry through.
        Map<String, Object> data = execute("{ citiesByCountryName { cityName } }");
        List<Map<String, Object>> cities = (List<Map<String, Object>>) data.get("citiesByCountryName");
        assertThat(cities).extracting(c -> c.get("cityName"))
            .containsExactlyInAnyOrder("Lethbridge", "Rome", "Tokyo");
    }

    // ===== R425: unmasked @service-child SourceKey projection =====
    // City carries no @splitQuery/@tableMethod sibling, so its @service children are the only
    // reason CITY_ID lands in the parent SELECT. Both queries deliberately select NO field that
    // maps to CITY_ID; if the BatchKeyField arm in
    // TypeClassGenerator.collectRequiredProjectionColumns regresses, cityUppercase resolves to
    // null (silent .into(Tables.CITY) extraction) and cityLowercase fails the request (loud
    // per-column get(...) extraction), turning these red.

    @SuppressWarnings("unchecked")
    @Test
    void cities_cityUppercase_withoutKeyFieldSelected_resolvesViaTableRecordSource() {
        // Wrap.TableRecord — the silent-null reproducer shape from the opptak federation bug.
        Map<String, Object> data = execute("{ citiesByCountryName { cityName cityUppercase } }");
        assertThat(data).extractingByKey("citiesByCountryName", as(list(Map.class)))
            .hasSize(3)
            .allSatisfy(c -> {
                var cityName = (String) c.get("cityName");
                assertThat((String) c.get("cityUppercase"))
                    .as("cityUppercase must resolve non-null for city '%s' even though no "
                        + "selected field maps to CITY_ID", cityName)
                    .isEqualTo(cityName.toUpperCase());
            });
    }

    @SuppressWarnings("unchecked")
    @Test
    void cities_cityLowercase_withoutKeyFieldSelected_resolvesViaRow1Source() {
        // Wrap.Row — the loud-throw shape (jOOQ throws on an absent field at key extraction).
        Map<String, Object> data = execute("{ citiesByCountryName { cityName cityLowercase } }");
        assertThat(data).extractingByKey("citiesByCountryName", as(list(Map.class)))
            .hasSize(3)
            .allSatisfy(c -> {
                var cityName = (String) c.get("cityName");
                assertThat((String) c.get("cityLowercase"))
                    .as("cityLowercase must resolve non-null for city '%s' even though no "
                        + "selected field maps to CITY_ID", cityName)
                    .isEqualTo(cityName.toLowerCase());
            });
    }

    @SuppressWarnings("unchecked")
    @Test
    void multiParentSharedNesting_inlineTableField_returnsAddressPerParent() {
        // R23: OccupantLocation is a plain-object nested type shared across two @table parents,
        // Customer and Store, both of which FK to address. Its `address` field carries no
        // @reference; the one-hop FK joinPath is inferred against each outer parent's own table
        // (customer.address_id → address; store.address_id → address). This query exercises both
        // arms in one request — the shared TableField must resolve independently per parent.
        Map<String, Object> data = execute("""
            {
              customers { customerId address { addressId } location { address { addressId } } }
              storeById(store_id: [1, 2]) { storeId location { address { addressId district } } }
            }
            """);

        // Customer side: the nested location.address resolves the same row as the direct
        // Customer.address FK navigation (both go through customer_address_id_fkey).
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customers");
        assertThat(customers).hasSize(5);
        for (Map<String, Object> c : customers) {
            var direct = (Map<String, Object>) c.get("address");
            var viaNesting = (Map<String, Object>) ((Map<String, Object>) c.get("location")).get("address");
            assertThat(viaNesting)
                .as("Customer %s: location.address resolves the same row as the direct address FK", c.get("customerId"))
                .isEqualTo(direct);
        }

        // Store side: the same shared nested type resolves address through store's own FK,
        // proving the joinPath is per-parent. init.sql seeds store 1 → address 1, store 2 → address 2.
        List<Map<String, Object>> stores = (List<Map<String, Object>>) data.get("storeById");
        assertThat(stores).hasSize(2);
        for (Map<String, Object> s : stores) {
            var addr = (Map<String, Object>) ((Map<String, Object>) s.get("location")).get("address");
            assertThat(addr).as("Store %s: location.address is resolved through store.address_id", s.get("storeId")).isNotNull();
            assertThat(addr.get("addressId")).isNotNull();
            assertThat(addr.get("district")).isNotNull();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitTableField_conditionJoin_returnsActorsPerFilm() {
        // R232: Film.actorsByCondition (SplitTableField + ConditionJoin first hop).
        // The condition method `filmActorsViaCondition` expresses the film_actor junction
        // as an EXISTS predicate; the split-rows emitter routes step-0 correlation through
        // the condition method (FROM actor JOIN film ON cond(film, actor) JOIN parentInput
        // ON film.film_id = parentInput.film_id). Total round-trips: 1 (films root) + 1
        // (batched actorsByCondition rows method) = 2.
        // Seeded film→actors mapping (init.sql):
        //   film 1 → {1,2}  film 2 → {1,3}  film 3 → {1}  film 4 → {2}  film 5 → {3}
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ films { filmId actorsByCondition { actorId } } }");
        assertThat(QUERY_COUNT.get())
            .as("1 root films query + 1 batched DataLoader call for actorsByCondition")
            .isEqualTo(2);
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");
        var byId = films.stream().collect(java.util.stream.Collectors.toMap(
            f -> (Integer) f.get("filmId"),
            f -> (List<Map<String, Object>>) f.get("actorsByCondition")));
        assertThat(byId.get(1)).extracting(a -> a.get("actorId")).containsExactlyInAnyOrder(1, 2);
        assertThat(byId.get(2)).extracting(a -> a.get("actorId")).containsExactlyInAnyOrder(1, 3);
        assertThat(byId.get(3)).extracting(a -> a.get("actorId")).containsExactly(1);
        assertThat(byId.get(4)).extracting(a -> a.get("actorId")).containsExactly(2);
        assertThat(byId.get(5)).extracting(a -> a.get("actorId")).containsExactly(3);
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitTableField_bridgingConditionJoin_returnsActorsPerFilm() {
        // Bridging-hop ConditionJoin regression (mirrors opptak's samordnaOrganisasjoner):
        // Film.actorsViaJunctionCondition navigates an FK first hop (film -> film_actor) then a
        // terminal @condition hop (film_actor -> actor) via filmActorJunctionToActor(FilmActor,
        // Actor). The split-rows emitter joins the junction with .on(filmActorJunctionToActor(
        // film_actor_alias, actor_alias)) -- source first. The concrete incompatible parameter
        // types make a reversed-argument emit a compile error in compile-spec; this test adds the
        // runtime check that the bridging join selects the same actors as the FK / condition-only
        // navigations. Same seeded film->actors mapping and same 1 + 1 = 2 round-trip shape as
        // splitTableField_conditionJoin_returnsActorsPerFilm above.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ films { filmId actorsViaJunctionCondition { actorId } } }");
        assertThat(QUERY_COUNT.get())
            .as("1 root films query + 1 batched DataLoader call for actorsViaJunctionCondition")
            .isEqualTo(2);
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");
        var byId = films.stream().collect(java.util.stream.Collectors.toMap(
            f -> (Integer) f.get("filmId"),
            f -> (List<Map<String, Object>>) f.get("actorsViaJunctionCondition")));
        assertThat(byId.get(1)).extracting(a -> a.get("actorId")).containsExactlyInAnyOrder(1, 2);
        assertThat(byId.get(2)).extracting(a -> a.get("actorId")).containsExactlyInAnyOrder(1, 3);
        assertThat(byId.get(3)).extracting(a -> a.get("actorId")).containsExactly(1);
        assertThat(byId.get(4)).extracting(a -> a.get("actorId")).containsExactly(2);
        assertThat(byId.get(5)).extracting(a -> a.get("actorId")).containsExactly(3);
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitTableField_singleCardinality_dedupesSharedFk_oneBatchRoundTrip() {
        // Five customers hit the addressSplit DataLoader; caching-enabled dedup collapses the
        // 5 loads to 3 distinct keys (addresses 1, 2, 3). Total round-trips: 1 (customers root)
        // + 1 (batched addressSplit rows method) = 2. An un-batched scatter would fire 1 + 5 = 6.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ customers { customerId addressSplit { addressId district } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customers");
        assertThat(customers).hasSize(5);
        // Customers 1 and 4 share address 1 → the same district value resolves for both.
        var byId = customers.stream().collect(java.util.stream.Collectors.toMap(
            c -> (Integer) c.get("customerId"),
            c -> (Map<String, Object>) c.get("addressSplit")));
        assertThat(byId.get(1).get("district")).isEqualTo(byId.get(4).get("district"));
        assertThat(byId.get(2).get("district")).isEqualTo(byId.get(5).get("district"));
    }

    @Test
    void splitTableField_singleCardinality_nullFk_shortCircuitsWithoutLoaderDispatch() {
        // Store 2 has manager_staff_id = NULL in init.sql. The §4 null-FK short-circuit in
        // StoreFetchers.manager returns CompletableFuture.completedFuture(null) before
        // dispatching to the DataLoader, so no rows-method round-trip fires for that store.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ storeById(store_id: [2]) { storeId manager { staffId } } }");
        // Parent query only — null-FK short-circuit eliminates the child round-trip entirely.
        assertThat(QUERY_COUNT.get()).isEqualTo(1);
        assertThat(data).extractingByKey("storeById", as(LIST))
            .hasSize(1)
            .first(as(MAP))
            .containsEntry("storeId", 2)
            .containsEntry("manager", null);
    }

    @Test
    void splitTableField_singleCardinality_nonNullFk_resolvesManager() {
        // Store 1 has manager_staff_id = 1 → Mike Hillyer. Covers the happy path for
        // Store.manager alongside the null-FK test above.
        Map<String, Object> data = execute(
            "{ storeById(store_id: [1]) { storeId manager { staffId firstName } } }");
        assertThat(data).extractingByKey("storeById", as(LIST))
            .hasSize(1)
            .first(as(MAP))
            .extractingByKey("manager", as(MAP))
            .isNotNull()
            .containsEntry("staffId", 1)
            .containsEntry("firstName", "Mike");
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitTableField_singleCardinality_mixedNullAndNonNullFk_scatterAlignsByIdx() {
        // Both stores queried in one batch: store 1 (manager_staff_id=1), store 2 (NULL).
        // Store 2's null short-circuit fires before loader.load, so only store 1 reaches the
        // DataLoader — 1 parent + 1 batched child (for keys=[Row(1)]) = 2 round-trips.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ storeById(store_id: [1, 2]) { storeId manager { staffId } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
        List<Map<String, Object>> stores = (List<Map<String, Object>>) data.get("storeById");
        var byId = stores.stream().collect(java.util.stream.Collectors.toMap(
            s -> (Integer) s.get("storeId"), s -> s));
        assertThat(((Map<String, Object>) byId.get(1).get("manager")).get("staffId")).isEqualTo(1);
        assertThat(byId.get(2).get("manager")).isNull();
    }

    // ===== argres Phase 3 — composite-key @lookupKey via @table input type =====

    @Test
    void compositeKeyLookup_returnsMatchingPairs() {
        // film_actor seed: (film 1, actor 1), (film 1, actor 2), (film 2, actor 1), (film 2, actor 3),
        // (film 3, actor 1), (film 4, actor 2), (film 5, actor 3). Composite-key join on both
        // film_id AND actor_id — query two existing pairs.
        Map<String, Object> data = execute(
            "{ filmActorsByKey(key: [{filmId: 1, actorId: 2}, {filmId: 2, actorId: 3}]) { filmId actorId } }");
        assertThat(data).extractingByKey("filmActorsByKey", as(list(Map.class)))
            .hasSize(2)
            .extracting(r -> r.get("filmId") + ":" + r.get("actorId"))
            .containsExactly("1:2", "2:3");
    }

    @Test
    void compositeKeyLookup_preservesInputOrder() {
        // VALUES+JOIN preserves input order via the derived table's idx column, even for composite
        // keys. Reverse the input to prove ordering is not coincidentally PK-sorted.
        Map<String, Object> data = execute(
            "{ filmActorsByKey(key: [{filmId: 3, actorId: 1}, {filmId: 1, actorId: 2}, {filmId: 2, actorId: 1}]) { filmId actorId } }");
        assertThat(data).extractingByKey("filmActorsByKey", as(list(Map.class)))
            .extracting(r -> r.get("filmId") + ":" + r.get("actorId"))
            .containsExactly("3:1", "1:2", "2:1");
    }

    @Test
    void compositeKeyLookup_mismatchedPairExcluded() {
        // (film 4, actor 1) is NOT a row in film_actor (film 4's cast is actor 2 only). Both
        // film 4 and actor 1 exist individually, but the composite JOIN rejects the pair.
        // (film 1, actor 1) is a real pair → returned.
        Map<String, Object> data = execute(
            "{ filmActorsByKey(key: [{filmId: 4, actorId: 1}, {filmId: 1, actorId: 1}]) { filmId actorId } }");
        assertThat(data).extractingByKey("filmActorsByKey", as(LIST))
            .hasSize(1)
            .first(as(MAP))
            .containsEntry("filmId", 1)
            .containsEntry("actorId", 1);
    }

    @Test
    void compositeKeyLookup_emptyInput_returnsEmpty() {
        Map<String, Object> data = execute(
            "{ filmActorsByKey(key: []) { filmId actorId } }");
        assertThat(data).extractingByKey("filmActorsByKey", as(LIST)).isEmpty();
    }

    // ===== R50 phase (g-C) — composite-PK NodeId @lookupKey via DecodedRecord =====
    //
    // FilmActor is a composite-PK NodeType (PK columns ACTOR_ID, FILM_ID per init.sql). Each
    // opaque ID decodes once per row at the arg layer to a Record2<Integer,Integer> and the
    // generator emits a VALUES(idx, actor_id, film_id) derived table joined on the full
    // composite key. Carrier-driven ThrowOnMismatch surfaces typeId mismatches as
    // GraphqlErrorException; missing rows are simply absent (positional output is dense, not
    // sparse, since orderBy(idx) is on the input, not the join target).

    @Test
    void compositeNodeIdLookup_listIds_returnsRowsInInputOrder() {
        // Encode three real film_actor pairs out of seed order to prove (idx) preservation:
        // (actor=2, film=1), (actor=3, film=2), (actor=2, film=4). The dispatcher decodes each
        // base64 ID to Record2(actor_id, film_id), then orderBy(idx) preserves input position.
        String id1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 2, 1);
        String id2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 3, 2);
        String id3 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 2, 4);
        Map<String, Object> data = execute(
            "{ filmActorByNodeId(id: [\"" + id1 + "\", \"" + id2 + "\", \"" + id3 + "\"])"
            + " { filmId actorId } }");
        assertThat(data).extractingByKey("filmActorByNodeId", as(list(Map.class)))
            .extracting(r -> r.get("actorId") + ":" + r.get("filmId"))
            .containsExactly("2:1", "3:2", "2:4");
    }

    @Test
    void compositeNodeIdLookup_emptyInput_returnsEmpty() {
        Map<String, Object> data = execute("{ filmActorByNodeId(id: []) { filmId } }");
        assertThat(data).extractingByKey("filmActorByNodeId", as(LIST)).isEmpty();
    }

    @Test
    void compositeNodeIdLookup_unknownKey_excludedFromResult() {
        // (actor=99, film=99) decodes successfully but no such film_actor row exists. The JOIN
        // filters it out — the result includes the matching row only. Length-mismatch between
        // inputs and outputs is allowed by design (cf. compositeKeyLookup_mismatchedPairExcluded).
        String real    = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 1, 1);
        String missing = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 99, 99);
        Map<String, Object> data = execute(
            "{ filmActorByNodeId(id: [\"" + missing + "\", \"" + real + "\"]) { filmId actorId } }");
        assertThat(data).extractingByKey("filmActorByNodeId", as(LIST))
            .hasSize(1)
            .first(as(MAP))
            .containsEntry("actorId", 1)
            .containsEntry("filmId", 1);
    }

    @Test
    void compositeNodeIdLookup_typeMismatch_raisesError() {
        // ThrowOnMismatch contract: a NodeId encoded for the wrong typeId must surface as an error
        // rather than silently producing degenerate VALUES rows. A Customer-prefixed id reaches
        // decodeFilmActor, which returns null on prefix-mismatch; the lookup VALUES row-builder
        // (LookupValuesJoinEmitter) then throws a plain GraphqlErrorException. That throw is NOT the
        // GraphitronClientException marker R378 introduced (R378 enriched the
        // CompositeDecodeHelperRegistry filter/lookup-arg helpers, not the separate lookup-values
        // emitter, mirroring how the record-decode siblings keep their own message), so
        // surfaceClientErrorOrRedact falls through to redact: a correlation id, not the raw text.
        // Pins that boundary behaviourally.
        String wrongType = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        graphql.ExecutionResult result = executeRaw(
            "{ filmActorByNodeId(id: [\"" + wrongType + "\"]) { filmId actorId } }");
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().get(0).getMessage())
            .contains("An error occurred. Reference:");
    }

    // ===== C4: RecordTableField — @record parent + DataLoader language batch =====

    @Test
    void recordTableField_singleFilm_returnsLanguage() {
        // Film 1 (ACADEMY DINOSAUR) has language_id=1 (English).
        // filmDetails is a same-table NestingField pass-through; language is a RecordTableField DataLoader.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { languageId filmDetails { title language { name } } } }");
        var details = assertThat(data).extractingByKey("filmById", as(LIST))
            .hasSize(1)
            .first(as(MAP))
            .extractingByKey("filmDetails", as(MAP));
        details.containsEntry("title", "ACADEMY DINOSAUR");
        String langName = details.extractingByKey("language", as(LIST))
            .hasSize(1)
            .first(as(MAP))
            .extractingByKey("name", as(STRING)).actual();
        assertThat(langName.trim()).isEqualTo("English");
    }

    @Test
    void recordTableField_multipleParents_batchesIntoOneSqlRoundTrip() {
        // FilmDetailsCarrier is record-backed (Query.filmDetailsBatch returns List<FilmRecord>), so
        // language is a RecordTableField. 5 films all have language_id=1; the DataLoader should
        // batch all 5 language lookups into 1 SQL SELECT (the rowsLanguage method) rather than
        // firing 5 separate queries. Expected: 2 round-trips — 1 for the service query + 1 for the
        // batched language rows.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ filmDetailsBatch(ids: [1, 2, 3, 4, 5]) { language { name } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
        // Every film maps to English (language_id=1 for all test-data films).
        assertThat(data).extractingByKey("filmDetailsBatch", as(list(Map.class)))
            .hasSize(5)
            .allSatisfy(f -> {
                String langName = assertThat(f.get("language")).asInstanceOf(LIST)
                    .hasSize(1)
                    .first(as(MAP))
                    .extractingByKey("name", as(STRING)).actual();
                assertThat(langName.trim()).isEqualTo("English");
            });
    }

    @SuppressWarnings("unchecked")
    @Test
    void recordTableField_propertyField_resolvedFromSameRecord() {
        // title is a PropertyField on FilmDetails; it uses ColumnFetcher(DSL.field("title"))
        // which extracts from the same Film Record passed through by the NestingField.
        Map<String, Object> data = execute(
            "{ films { filmDetails { title } } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");
        assertThat(films).hasSize(5);
        assertThat(films).extracting(f -> ((Map<String, Object>) f.get("filmDetails")).get("title"))
            .containsExactly(
                "ACADEMY DINOSAUR", "ACE GOLDFINGER", "ADAPTATION HOLES",
                "AFFAIR PREJUDICE", "AGENT TRUMAN");
    }

    // ===== record-fields Phase 2: RecordLookupTableField — @record parent + @splitQuery + @lookupKey =====

    @Test
    void recordLookupTableField_singleFilm_returnsFilteredActors() {
        // FilmDetails.actorsByLookup: RecordLookupTableField — DataLoader-batched lookup keyed by
        // the Film record's film_id, narrowed by the caller's actor_id list via VALUES-join.
        // Film 1 (ACADEMY DINOSAUR) cast: PENELOPE (1), NICK (2).
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { filmDetails { actorsByLookup(actor_id: [1, 2]) { actorId firstName } } } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP))
            .extractingByKey("filmDetails", as(MAP))
            .extractingByKey("actorsByLookup", as(list(Map.class)))
            .extracting(a -> a.get("firstName"))
            .containsExactlyInAnyOrder("PENELOPE", "NICK");
    }

    @Test
    void recordLookupTableField_fkFilter_excludesActorsNotInFilm() {
        // actor_id: [1, 3] on Film 1 → actor 3 (ED) is not in film 1's cast; FK chain drops him.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { filmDetails { actorsByLookup(actor_id: [1, 3]) { firstName } } } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP))
            .extractingByKey("filmDetails", as(MAP))
            .extractingByKey("actorsByLookup", as(list(Map.class)))
            .extracting(a -> a.get("firstName")).containsExactly("PENELOPE");
    }

    @SuppressWarnings("unchecked")
    @Test
    void recordLookupTableField_multipleParents_batchesIntoOneRoundTrip() {
        // FilmDetailsCarrier is record-backed (Query.filmDetailsBatch returns List<FilmRecord>), so
        // actorsByLookup is a RecordLookupTableField. 3 carriers + 1 batched RecordLookup child =
        // 2 round-trips. Unbatched: 1 + 3 = 4.
        // Film 2 cast: PENELOPE (1), ED (3). Film 3 cast: PENELOPE (1). actor_id: [1, 3] →
        // film 1 gets {1}; film 2 gets {1, 3}; film 3 gets {1}.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ filmDetailsBatch(ids: [1, 2, 3]) { filmId actorsByLookup(actor_id: [1, 3]) { actorId } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
        List<Map<String, Object>> carriers = (List<Map<String, Object>>) data.get("filmDetailsBatch");

        var byId = carriers.stream().collect(java.util.stream.Collectors.toMap(
            f -> (Integer) f.get("filmId"),
            f -> (List<Map<String, Object>>) f.get("actorsByLookup")));

        assertThat(byId.get(1)).extracting(a -> a.get("actorId")).containsExactly(1);
        assertThat(byId.get(2)).extracting(a -> a.get("actorId")).containsExactlyInAnyOrder(1, 3);
        assertThat(byId.get(3)).extracting(a -> a.get("actorId")).containsExactly(1);
    }

    @Test
    void recordLookupTableField_emptyLookupInput_shortCircuitsNoChildQuery() {
        // Empty @lookupKey → inputRows helper returns new Row[0] → emptyScatter short-circuit.
        // No child round-trip fires.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ films { filmId filmDetails { actorsByLookup(actor_id: []) { actorId } } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(1);
        assertThat(data).extractingByKey("films", as(list(Map.class))).allSatisfy(f ->
            assertThat(f.get("filmDetails")).asInstanceOf(MAP)
                .extractingByKey("actorsByLookup", as(LIST)).isEmpty());
    }

    @Test
    void recordLookupTableField_nullLookupInput_shortCircuitsNoChildQuery() {
        // Omitting @lookupKey arg → null → rowCount=0 path → emptyScatter short-circuit.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ films { filmId filmDetails { actorsByLookup { actorId } } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(1);
        assertThat(data).extractingByKey("films", as(list(Map.class))).allSatisfy(f ->
            assertThat(f.get("filmDetails")).asInstanceOf(MAP)
                .extractingByKey("actorsByLookup", as(LIST)).isEmpty());
    }

    // ===== NestingField — plain-object nested types =====

    @Test
    void nestingField_nestedScalars_returnCorrectValues() {
        // Film 1 (ACADEMY DINOSAUR) was seeded with release_year 2006.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { summary { title releaseYear } } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP))
            .extractingByKey("summary", as(MAP))
            .containsEntry("title", "ACADEMY DINOSAUR")
            .containsEntry("releaseYear", 2006);
    }

    @Test
    void nestingField_fieldNameRemap_resolvesToParentColumn() {
        // FilmSummary.originalTitle @field(name: "TITLE") resolves to FILM.TITLE despite
        // the distinct GraphQL field name. Locks the @field(name:) remap at nested depth.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { summary { originalTitle } } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP))
            .extractingByKey("summary", as(MAP))
            .containsEntry("originalTitle", "ACADEMY DINOSAUR");
    }

    @Test
    void nestingField_multiLevelNesting_resolvesThroughTransparentNesting() {
        // Film.info.meta.title + Film.info.meta.length both resolve against the outer
        // Film table. ACADEMY DINOSAUR / 86 minutes in the seed data.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { info { releaseYear meta { title length } } } }");
        var info = assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP))
            .extractingByKey("info", as(MAP));
        info.containsEntry("releaseYear", 2006);
        info.extractingByKey("meta", as(MAP))
            .containsEntry("title", "ACADEMY DINOSAUR")
            .containsEntry("length", 86);
    }

    @Test
    void nestingField_onlyRequestedColumnsSelected() {
        // Requesting only summary.title must project FILM.TITLE — not releaseYear —
        // since $fields is selection-aware. One round-trip; correct value returned.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { summary { title } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(1);
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP))
            .extractingByKey("summary", as(MAP))
            .containsOnlyKeys("title")
            .containsEntry("title", "ACADEMY DINOSAUR");
    }

    @Test
    void nestingField_outerListOfFilms_nestedResolvesPerRow() {
        // Requesting summary across the films root list: each row carries its own
        // FilmSummary projection, including the per-row release_year.
        Map<String, Object> data = execute("{ films { filmId summary { releaseYear } } }");
        assertThat(data).extractingByKey("films", as(list(Map.class))).allSatisfy(f ->
            // All seeded films carry release_year 2006
            assertThat(f.get("summary")).asInstanceOf(MAP)
                .containsKey("releaseYear")
                .containsEntry("releaseYear", 2006));
    }

    @Test
    void nestingField_siblingOfTableFields_doesNotDisrupt() {
        // Nesting field projects via $fields on the same outer row as sibling column
        // fields; both must come back correctly on the same query.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { filmId title summary { releaseYear } } }");
        var film = assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP));
        film.containsEntry("filmId", 1)
            .containsEntry("title", "ACADEMY DINOSAUR");
        film.extractingByKey("summary", as(MAP))
            .containsEntry("releaseYear", 2006);
    }

    // ===== NestingField — nested inline TableField / LookupTableField (sfName threading) =====

    @Test
    void nestingField_withNestedInlineTableField_resolvesViaOuterTableAlias() {
        // Film.inlineBundle.language is an inline TableField nested inside a NestingField.
        // The switch arm at depth 1 references sf1.getSelectionSet() (not sf), proving the
        // sfName parameter is threaded through InlineTableFieldEmitter. Without it the
        // generated code would fail to compile (undefined sf at depth 1).
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { inlineBundle { language { languageId name } } } }");
        var language = assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP))
            .extractingByKey("inlineBundle", as(MAP))
            .extractingByKey("language", as(MAP));
        language.containsEntry("languageId", 1);
        String name = language.extractingByKey("name", as(STRING)).actual();
        assertThat(name.trim()).isEqualTo("English");
    }

    @Test
    void nestingField_withNestedInlineLookupTableField_appliesLookupKey() {
        // Film.inlineBundle.actorsByKey is an inline LookupTableField nested inside a
        // NestingField. Exercises the sf1-threaded path through InlineLookupTableFieldEmitter
        // (inputRows call, empty-input short-circuit, buildInnerSelect all use sfName).
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { inlineBundle { actorsByKey(actor_id: [1, 2]) { actorId firstName } } } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP))
            .extractingByKey("inlineBundle", as(MAP))
            .extractingByKey("actorsByKey", as(list(Map.class)))
            .extracting(a -> a.get("firstName"))
            .containsExactlyInAnyOrder("PENELOPE", "NICK");
    }

    @Test
    void nestingField_withNestedInlineLookupTableField_emptyInputShortCircuit() {
        // Empty @lookupKey list hits the rows.length == 0 short-circuit branch in
        // InlineLookupTableFieldEmitter — which also uses sfName in the falseCondition
        // multiset. Parent row still carries the slot, populated as an empty list.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { inlineBundle { actorsByKey(actor_id: []) { actorId } } } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP))
            .extractingByKey("inlineBundle", as(MAP))
            .extractingByKey("actorsByKey", as(LIST)).isEmpty();
    }

    // ===== Film.actorsConnection — @splitQuery + @asConnection (plan-split-query-connection §1) =====

    @SuppressWarnings("unchecked")
    @Test
    void splitQueryConnection_firstPagePerParent_batchesInOneRowsRoundTrip() {
        // film_actor seed: film 1 -> {1, 2}, film 2 -> {1, 3}. Two parent films request
        // actorsConnection(first: 1); the rows method partitions by parent idx, each parent
        // gets its own over-fetched top-2 slice via ROW_NUMBER() OVER (PARTITION BY idx).
        // Total round-trips: 1 (films root) + 1 (batched rowsActorsConnection).
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\", \"2\"]) { filmId actorsConnection(first: 1) "
                + "{ nodes { actorId } pageInfo { hasNextPage hasPreviousPage } } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        assertThat(films).hasSize(2);
        var byId = films.stream().collect(java.util.stream.Collectors.toMap(
            f -> (Integer) f.get("filmId"), f -> (Map<String, Object>) f.get("actorsConnection")));
        List<Map<String, Object>> film1Nodes = (List<Map<String, Object>>) byId.get(1).get("nodes");
        List<Map<String, Object>> film2Nodes = (List<Map<String, Object>>) byId.get(2).get("nodes");
        assertThat(film1Nodes).hasSize(1);
        assertThat(film1Nodes.get(0).get("actorId")).isEqualTo(1);
        assertThat(film2Nodes).hasSize(1);
        assertThat(film2Nodes.get(0).get("actorId")).isEqualTo(1);
        assertThat((Map<String, Object>) byId.get(1).get("pageInfo"))
            .extracting("hasNextPage", "hasPreviousPage").containsExactly(true, false);
        assertThat((Map<String, Object>) byId.get(2).get("pageInfo"))
            .extracting("hasNextPage", "hasPreviousPage").containsExactly(true, false);
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitQueryConnection_withAfterCursor_pagesForwardPerParent() {
        // Page 1: actorsConnection(first: 1) → actor 1 for film 1, actor 1 for film 2.
        // Use the endCursor from film 1 to page forward; because both parents share the
        // same arg shape, the second query still batches them into one rows round-trip,
        // and each parent's WHERE (cols) > (cursor_vals) filter plays out inside ROW_NUMBER.
        Map<String, Object> page1 = execute(
            "{ filmById(film_id: [\"1\", \"2\"]) { filmId actorsConnection(first: 1) "
                + "{ edges { cursor } pageInfo { endCursor } } } }");
        String endCursorFilm1 = (String) ((Map<String, Object>) ((Map<String, Object>)
            ((List<Map<String, Object>>) page1.get("filmById")).get(0).get("actorsConnection")).get("pageInfo")).get("endCursor");
        assertThat(endCursorFilm1).isNotNull();
        QUERY_COUNT.set(0);
        Map<String, Object> page2 = execute(
            "{ filmById(film_id: [\"1\", \"2\"]) { filmId actorsConnection(first: 10, after: \""
                + endCursorFilm1 + "\") { nodes { actorId } pageInfo { hasNextPage } } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
        List<Map<String, Object>> films = (List<Map<String, Object>>) page2.get("filmById");
        var byId = films.stream().collect(java.util.stream.Collectors.toMap(
            f -> (Integer) f.get("filmId"), f -> (Map<String, Object>) f.get("actorsConnection")));
        // Film 1 after actor_id=1 has just actor_id=2; film 2 after actor_id=1 has just actor_id=3.
        assertThat((List<Map<String, Object>>) byId.get(1).get("nodes"))
            .extracting(n -> n.get("actorId")).containsExactly(2);
        assertThat((List<Map<String, Object>>) byId.get(2).get("nodes"))
            .extracting(n -> n.get("actorId")).containsExactly(3);
        assertThat(((Map<String, Object>) byId.get(1).get("pageInfo")).get("hasNextPage")).isEqualTo(false);
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitQueryConnection_dynamicOrderByArg_sortsEachPartitionByNamedField() {
        // plan-split-query-connection.md §2: @splitQuery + @asConnection with a dynamic @orderBy
        // argument. The emitted OrderBy helper accepts the FK-chain terminal alias so column refs
        // bind to the aliased jOOQ Actor table. Order by LAST_NAME descending, then take the first
        // row per parent — that's the actor with the latest last_name in each film.
        // Film 1 actors: {PENELOPE GUINESS, NICK WAHLBERG} → last-desc-first = NICK (WAHLBERG).
        // Film 2 actors: {PENELOPE GUINESS, ED CHASE}      → last-desc-first = PENELOPE (GUINESS).
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\", \"2\"]) { filmId actorsOrderedConnection("
                + "order: [{field: LAST_NAME, direction: DESC}], first: 1) "
                + "{ nodes { actorId firstName lastName } } } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        var byId = films.stream().collect(java.util.stream.Collectors.toMap(
            f -> (Integer) f.get("filmId"),
            f -> (List<Map<String, Object>>) ((Map<String, Object>) f.get("actorsOrderedConnection")).get("nodes")));
        assertThat(byId.get(1)).hasSize(1);
        assertThat(byId.get(1).get(0).get("lastName")).isEqualTo("WAHLBERG");
        assertThat(byId.get(2)).hasSize(1);
        assertThat(byId.get(2).get(0).get("lastName")).isEqualTo("GUINESS");
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitQueryConnection_backwardPagination_returnsLastNAscending() {
        // last: 1 with no cursor: the CTE inverts the ORDER BY (actor_id DESC) so ROW_NUMBER()
        // picks the largest actor_id per partition, then ConnectionResult.trimmedResult()
        // re-reverses back to ascending for the GraphQL client.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\", \"2\"]) { filmId actorsConnection(last: 1) "
                + "{ nodes { actorId } pageInfo { hasNextPage hasPreviousPage } } } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        var byId = films.stream().collect(java.util.stream.Collectors.toMap(
            f -> (Integer) f.get("filmId"), f -> (Map<String, Object>) f.get("actorsConnection")));
        // Film 1 last actor is 2; film 2 last actor is 3.
        assertThat((List<Map<String, Object>>) byId.get(1).get("nodes"))
            .extracting(n -> n.get("actorId")).containsExactly(2);
        assertThat((List<Map<String, Object>>) byId.get(2).get("nodes"))
            .extracting(n -> n.get("actorId")).containsExactly(3);
        assertThat(((Map<String, Object>) byId.get(1).get("pageInfo")).get("hasPreviousPage")).isEqualTo(true);
    }

    // ===== Film.actorsConnection — per-parent totalCount (R414) =====

    @SuppressWarnings("unchecked")
    @Test
    void splitQueryConnection_totalCount_isParentScoped() {
        // R414: the rows method emits a shared cursor-independent countSource derived table;
        // each per-parent ConnectionResult binds it with __idx__ = i, so totalCount counts the
        // whole per-parent connection independent of the page size. Seed: film 1 -> {1, 2},
        // film 2 -> {1, 3}, film 3 -> {1}, film 4 -> {2}, film 5 -> {3}. first: 1 distinguishes
        // the connection count from the page size.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\", \"2\", \"3\", \"4\", \"5\"]) { filmId "
                + "actorsConnection(first: 1) { totalCount } } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        var byId = films.stream().collect(java.util.stream.Collectors.toMap(
            f -> (Integer) f.get("filmId"),
            f -> ((Map<String, Object>) f.get("actorsConnection")).get("totalCount")));
        assertThat(byId).containsEntry(1, 2).containsEntry(2, 2)
            .containsEntry(3, 1).containsEntry(4, 1).containsEntry(5, 1);
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitQueryConnection_totalCount_isCursorIndependent() {
        // The countSource has no orderBy/seek, so paging past a cursor still reports the full
        // per-parent connection count. Page 1 of film 1's connection yields a cursor; paging
        // after it still reports totalCount 2.
        Map<String, Object> page1 = execute(
            "{ filmById(film_id: [\"1\"]) { actorsConnection(first: 1) "
                + "{ totalCount pageInfo { endCursor } } } }");
        Map<String, Object> conn1 = (Map<String, Object>)
            ((List<Map<String, Object>>) page1.get("filmById")).get(0).get("actorsConnection");
        assertThat(conn1.get("totalCount")).isEqualTo(2);
        String endCursor = (String) ((Map<String, Object>) conn1.get("pageInfo")).get("endCursor");
        assertThat(endCursor).isNotNull();

        Map<String, Object> page2 = execute(
            "{ filmById(film_id: [\"1\"]) { actorsConnection(first: 1, after: \"" + endCursor
                + "\") { totalCount nodes { actorId } } } }");
        Map<String, Object> conn2 = (Map<String, Object>)
            ((List<Map<String, Object>>) page2.get("filmById")).get(0).get("actorsConnection");
        assertThat((List<Map<String, Object>>) conn2.get("nodes"))
            .extracting(n -> n.get("actorId")).containsExactly(2);
        assertThat(conn2.get("totalCount")).isEqualTo(2);
    }

    @Test
    void splitQueryConnection_totalCount_isLazyOnSelection() {
        // graphql-java only invokes the registered totalCount resolver on selection: no count
        // SQL when the field is unselected, per-parent count statements when it is (matching
        // the B4c-2 cost profile: N lazy count queries for a batch of N parents).
        SQL_LOG.clear();
        execute("{ filmById(film_id: [\"1\", \"2\"]) { filmId actorsConnection(first: 1) "
            + "{ nodes { actorId } } } }");
        assertThat(SQL_LOG)
            .as("no SELECT count statement should be issued when totalCount is not selected")
            .noneMatch(s -> s.contains("select count"));

        SQL_LOG.clear();
        execute("{ filmById(film_id: [\"1\", \"2\"]) { filmId actorsConnection(first: 1) "
            + "{ totalCount } } }");
        assertThat(SQL_LOG)
            .filteredOn(s -> s.contains("select count"))
            .as("selecting totalCount should issue one per-parent count statement per parent")
            .hasSize(2);
    }

    @Test
    void splitQueryConnection_negativeFirst_surfacesClientErrorThroughAsyncArm() {
        // R415 load-bearing test for the async no-channel flip: the nested (DataLoader-based)
        // connection's pageRequest guard throws inside the batch lambda, DataLoader wraps it in
        // a CompletionException, and the fetcher's .exceptionally arm — surfaceClientErrorOrRedact
        // since R415, plain redact before — unwraps the cause chain and surfaces the real message.
        // A root-connection test alone would pass without the flip (the sync catch arm has
        // surfaced client errors since R378).
        var result = executeRaw(
            "{ filmById(film_id: [\"1\"]) { actorsConnection(first: -1) { nodes { lastName } } } }");
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().get(0).getMessage())
            .isEqualTo("first must not be negative (was: -1)");
    }

    // ===== SplitTableField / SplitLookupTableField under NestingField
    // (plan-splittablefield-nestingfield) =====

    @SuppressWarnings("unchecked")
    @Test
    void splitTableField_nestedUnderNestingField_batchesPerOuterParent() {
        // FilmInfo.cast is a @splitQuery inside a plain-object NestingField. The outer Film
        // Record is passed through by the NestingField wiring, so key extraction reads
        // FILM_ID off env.getSource() just like at non-nested depth. Two parent films should
        // produce exactly one batched rowsCast invocation.
        // Seed: film 1 → {1, 2}, film 2 → {1, 3}.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\", \"2\"]) { filmId info { cast { actorId } } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        var byId = films.stream().collect(java.util.stream.Collectors.toMap(
            f -> (Integer) f.get("filmId"),
            f -> (List<Map<String, Object>>) ((Map<String, Object>) f.get("info")).get("cast")));
        assertThat(byId.get(1)).extracting(a -> a.get("actorId")).containsExactlyInAnyOrder(1, 2);
        assertThat(byId.get(2)).extracting(a -> a.get("actorId")).containsExactlyInAnyOrder(1, 3);
    }

    // ===== argres Phase 4: @condition on INPUT_FIELD_DEFINITION =====

    @Test
    @SuppressWarnings("unchecked")
    void inputFieldCondition_tableInput_filtersByFilmId() {
        // FilmConditionInput.filmId carries @condition → condition is classified, wired,
        // and called at runtime. Phase 4b threads nested-arg extraction through ArgCallEmitter,
        // so filmId arrives as the actual String passed in the Map; filmIdCondition builds a
        // real Film.FILM_ID = ? predicate. Expect exactly one row matching filmId == 1.
        Map<String, Object> data = execute(
            "{ filmsWithInputFieldCondition(filter: {filmId: \"1\"}) { filmId } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsWithInputFieldCondition");
        assertThat(films).extracting(f -> f.get("filmId")).containsExactly(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputFieldCondition_nestedArgInferredByName_filtersSameAsExplicitArgMapping() {
        // R355: rentalRateRange(table, fra, til) carries no argMapping; the fra/til params bind one
        // level into RentalRateRange.{fra,til} by name. The range [2.00, 3.00] selects exactly the
        // three 2.99 films (ADAPTATION HOLES, AFFAIR PREJUDICE, AGENT TRUMAN): >= 2.0 excludes
        // ACADEMY DINOSAUR (0.99) and <= 3.0 excludes ACE GOLDFINGER (4.99). The unique 3-film
        // result proves BOTH bounds bind to the right nested field — a swapped or dropped bound
        // would yield 0 or 4 films. This is the test that proves the narrowed acceptance is
        // correct, not merely non-erroring.
        String query = "{ %s(filter: {rentalRateRange: {fra: 2.0, til: 3.0}}) { title } }";
        List<Map<String, Object>> inferred = (List<Map<String, Object>>) execute(
            query.formatted("filmsByRentalRateRange")).get("filmsByRentalRateRange");
        List<Map<String, Object>> explicit = (List<Map<String, Object>>) execute(
            query.formatted("filmsByRentalRateRangeExplicit")).get("filmsByRentalRateRangeExplicit");

        assertThat(inferred).extracting(f -> f.get("title"))
            .containsExactlyInAnyOrder("ADAPTATION HOLES", "AFFAIR PREJUDICE", "AGENT TRUMAN");
        // The no-argMapping form filters identically to the explicit-argMapping form.
        assertThat(inferred).containsExactlyInAnyOrderElementsOf(explicit);
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputFieldCondition_plainInput_filmTable_filtersByFilmId() {
        // PlainFilmIdInput → PojoInputType → PlainInputArg at the call site. filmId resolves
        // against film → ColumnField with condition is classified, walkInputFieldConditions
        // collects it, and nested-arg extraction delivers the value to filmIdCondition.
        Map<String, Object> data = execute(
            "{ filmsByPlainInput(filter: {filmId: \"2\"}) { filmId } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsByPlainInput");
        assertThat(films).extracting(f -> f.get("filmId")).containsExactly(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputFieldCondition_tableInput_overrideFlagOnRealColumn_explicitMethodStillFires() {
        // FilmConditionInputWithOverrideField.filmId carries @condition(override:true).
        // On a field that carries only an explicit @condition with no un-annotated siblings,
        // there's no implicit predicate to suppress, so the override flag is a no-op here;
        // the explicit method still fires. Runtime effect: filter by the passed filmId.
        Map<String, Object> data = execute(
            "{ filmsWithInputFieldOverride(filter: {filmId: \"3\"}) { filmId } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsWithInputFieldOverride");
        assertThat(films).extracting(f -> f.get("filmId")).containsExactly(3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputFieldCondition_tableInput_outerOverride_preservesInnerExplicitMethod() {
        // Divergence-pinning: outer @condition(override:true) composed with inner
        // @condition on FilmConditionInput.filmId. Legacy "outer owns everything" would
        // drop filmIdCondition and return films 2..5 (outerOverrideMethod is film_id >= 2).
        // Rewrite preserves inner explicit methods across the boundary, producing
        // (film_id >= 2) AND (film_id = 1) → no rows. A regression to legacy semantics
        // would make films non-empty and break this test by name.
        Map<String, Object> data = execute(
            "{ filmsOuterOverrideTableInput(filter: {filmId: \"1\"}) { filmId } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsOuterOverrideTableInput");
        assertThat(films).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputFieldCondition_nestedTwoLevel_pathWalksThroughNestingField() {
        // NestedFilmInput (outer @table) contains NestingField 'inner' (plain InnerFilmInput);
        // InnerFilmInput.filmId carries @condition. walkInputFieldConditions recurses with
        // leafPath=["inner", "filmId"]; ArgCallEmitter emits a two-level instanceof-Map
        // ternary chain to reach env.getArgument("filter").get("inner").get("filmId").
        Map<String, Object> data = execute(
            "{ filmsNestedInput(filter: {inner: {filmId: \"3\"}}) { filmId } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsNestedInput");
        assertThat(films).extracting(f -> f.get("filmId")).containsExactly(3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputFieldCondition_plainInput_outerOverride_preservesInnerExplicitMethod() {
        // alf production shape: outer @condition(override:true) composed with a plain
        // (non-@table) input whose field carries @condition. Same divergence-pinning
        // assertion as the @table case: (film_id >= 2) AND (film_id = 1) → no rows.
        Map<String, Object> data = execute(
            "{ filmsOuterOverridePlainInput(filter: {filmId: \"1\"}) { filmId } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsOuterOverridePlainInput");
        assertThat(films).isEmpty();
    }

    // ===== Implicit column conditions for @table input types =====

    @Test
    @SuppressWarnings("unchecked")
    void implicitInputCondition_filtersByColumn() {
        // FilmImplicitInput.filmId has no @condition — the implicit column-equality predicate
        // (film.FILM_ID = ?) is emitted by walkInputFieldConditions. filmId: "3" → one row.
        Map<String, Object> data = execute(
            "{ filmsWithImplicitInput(filter: {filmId: \"3\"}) { filmId } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsWithImplicitInput");
        assertThat(films).extracting(f -> f.get("filmId")).containsExactly(3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void implicitInputCondition_nullField_omitsPredicate_returnsAll() {
        // filmId: null → DSL.val(null, table.FILM_ID) null-guard skips the predicate →
        // no WHERE filter → all 5 films returned. "Absent means unconstrained" semantics.
        Map<String, Object> data = execute(
            "{ filmsWithImplicitInput(filter: {filmId: null}) { filmId } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsWithImplicitInput");
        assertThat(films).hasSize(5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void implicitInputCondition_parentFieldOverride_suppressesImplicit() {
        // filmsWithImplicitInputOuterOverride has @condition(override:true) → enclosingOverride=true
        // → implicit predicate for filmId is suppressed. Only outerOverrideMethod fires
        // (film_id >= 2). Query with filmId:"1" would return 0 rows if implicit were active
        // ((film_id >= 2) AND (film_id = 1)), but with suppression it returns 4 rows (2..5).
        Map<String, Object> data = execute(
            "{ filmsWithImplicitInputOuterOverride(filter: {filmId: \"1\"}) { filmId } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsWithImplicitInputOuterOverride");
        assertThat(films).extracting(f -> f.get("filmId"))
            .containsExactlyInAnyOrder(2, 3, 4, 5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void implicitInputCondition_twoFields_andsProperly() {
        // Two un-annotated fields: both emit implicit predicates, AND-ed in the GCF.
        // film 3 is ADAPTATION HOLES → match; filmId=3 + title="ACE GOLDFINGER" → no rows
        // (AND, not OR: the two predicates must agree).
        Map<String, Object> both = execute(
            "{ filmsWithMultiImplicit(filter: {filmId: \"3\", title: \"ADAPTATION HOLES\"}) { filmId } }");
        assertThat((List<Map<String, Object>>) both.get("filmsWithMultiImplicit"))
            .extracting(f -> f.get("filmId")).containsExactly(3);

        Map<String, Object> mismatch = execute(
            "{ filmsWithMultiImplicit(filter: {filmId: \"3\", title: \"ACE GOLDFINGER\"}) { filmId } }");
        assertThat((List<Map<String, Object>>) mismatch.get("filmsWithMultiImplicit")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void implicitInputCondition_nestedTwoLevel_firesAtLeaf() {
        // NestedImplicitInput @table wraps InnerImplicitInput (plain) whose filmId has no
        // @condition. Implicit predicate binds to the outer @table's film_id column; the
        // NestedInputField path ["inner", "filmId"] drives call-site extraction.
        Map<String, Object> data = execute(
            "{ filmsWithNestedImplicit(filter: {inner: {filmId: \"2\"}}) { filmId } }");
        assertThat((List<Map<String, Object>>) data.get("filmsWithNestedImplicit"))
            .extracting(f -> f.get("filmId")).containsExactly(2);
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitLookupTableField_nestedUnderNestingField_batchesPerOuterParent() {
        // FilmInfo.castByKey exercises the SplitLookupTableField arm under NestingField:
        // classifier, emitter, and wiring all route via BatchKeyField uniformly, so
        // @splitQuery + @lookupKey at nested depth takes the same path as the top-level
        // Film.actorsBySplitLookup (:732 above).
        // actor_id: [1, 2] → film 1 gets {1, 2}; film 2 gets {1}.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\", \"2\"]) { filmId info { castByKey(actor_id: [1, 2]) { actorId } } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        var byId = films.stream().collect(java.util.stream.Collectors.toMap(
            f -> (Integer) f.get("filmId"),
            f -> (List<Map<String, Object>>) ((Map<String, Object>) f.get("info")).get("castByKey")));
        assertThat(byId.get(1)).extracting(a -> a.get("actorId")).containsExactlyInAnyOrder(1, 2);
        assertThat(byId.get(2)).extracting(a -> a.get("actorId")).containsExactly(1);
    }

    // ===== stores — directive-driven @asConnection (emit-time synthesis) =====

    @Test
    void stores_synthesisedConnection_returnsEdgesAndPageInfo() {
        // The QueryStoresConnection / QueryStoresEdge types are synthesised at emit time
        // (not hand-written). This test proves SDL → classifier → synthesis → programmatic
        // schema → fetcher runtime compose end-to-end.
        // The test DB has 2 stores; request first:1 so hasNextPage is true.
        Map<String, Object> data = execute(
            "{ stores(first: 1) { edges { cursor node { storeId } } pageInfo { hasNextPage hasPreviousPage } } }");
        var conn = assertThat(data).extractingByKey("stores", as(MAP));
        var firstEdge = conn.extractingByKey("edges", as(LIST))
            .hasSize(1)
            .first(as(MAP));
        firstEdge.containsKey("cursor");
        firstEdge.extractingByKey("node", as(MAP)).extractingByKey("storeId").isNotNull();
        conn.extractingByKey("pageInfo", as(MAP))
            .containsEntry("hasNextPage", true)
            .containsEntry("hasPreviousPage", false);
    }

    // ===== Query.node — Relay Global Object Identification =====

    @Test
    void node_customerById_roundTripsThroughDispatcher() {
        String id = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        Map<String, Object> data = execute(
            "{ node(id: \"" + id + "\") { __typename ... on Customer { firstName lastName } } }");
        assertThat(data).extractingByKey("node", as(MAP))
            .isNotNull()
            .containsEntry("__typename", "Customer")
            .containsEntry("firstName", "Mary")
            .containsEntry("lastName", "Smith");
    }

    @Test
    void node_filmById_dispatchesByTypeIdPrefix() {
        // Different NodeType (Film) — verifies the dispatcher routes by typeId prefix.
        String id = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 1);
        Map<String, Object> data = execute(
            "{ node(id: \"" + id + "\") { __typename ... on Film { title } } }");
        assertThat(data).extractingByKey("node", as(MAP))
            .isNotNull()
            .containsEntry("__typename", "Film")
            .containsEntry("title", "ACADEMY DINOSAUR");
    }

    @Test
    void node_unknownTypeId_returnsNull() {
        // typeId prefix no NodeType claims → null (Relay spec: "if no such object exists, the
        // field returns null"). Dispatcher must not raise.
        String id = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("NotARegisteredType", "1");
        Map<String, Object> data = execute("{ node(id: \"" + id + "\") { __typename } }");
        assertThat(data.get("node")).isNull();
    }

    @Test
    void node_garbageInput_returnsNull() {
        // Malformed base64 → null (opacity: decoding errors are not exposed to clients).
        Map<String, Object> data = execute("{ node(id: \"not-a-valid-base64-id\") { __typename } }");
        assertThat(data.get("node")).isNull();
    }

    @Test
    void node_referenceField_encodesFromFkMirrorThenRoundTrips() {
        // Customer.addressNodeId references Address.id via the customer_address_id_fkey FK
        // (FK-mirror collapse: customer.address_id mirrors address.address_id). The dispatcher
        // encodes the FK column on the parent and the resulting opaque ID round-trips through
        // Query.node back to the matching Address row — without ever loading the Address table
        // during the projection.
        Map<String, Object> data = execute(
            "{ customers { customerId addressNodeId } }");
        String addressNodeId = assertThat(data).extractingByKey("customers", as(LIST))
            .isNotEmpty()
            .first(as(MAP))
            .extractingByKey("addressNodeId", as(STRING))
            .isNotNull()
            .actual();

        Map<String, Object> nodeData = execute(
            "{ node(id: \"" + addressNodeId + "\") { __typename ... on Address { addressId district } } }");
        var node = assertThat(nodeData).extractingByKey("node", as(MAP)).isNotNull();
        node.containsEntry("__typename", "Address");
        node.extractingByKey("addressId").isNotNull();
        node.extractingByKey("district").isNotNull();
    }

    @Test
    void node_validPrefixNoSuchRow_returnsNull() {
        // Registered typeId but the row doesn't exist — null, not error.
        String id = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 999999);
        Map<String, Object> data = execute("{ node(id: \"" + id + "\") { __typename } }");
        assertThat(data.get("node")).isNull();
    }

    // ===== nodeId durability / opacity invariants =====
    //
    // The wire format must stay frozen across releases: an ID issued by release N must still
    // resolve under release N+k. These tests pin specific encoded forms and assert the
    // dispatcher can decode them, so any change to NodeIdEncoder (encoding scheme, escape
    // policy, column-order normalisation) breaks loudly here before it can silently
    // invalidate IDs already in circulation.

    @Test
    void node_pinnedSingleKeyId_stillResolves() {
        // base64-url-no-pad of UTF-8 "Customer:1". If the encoder ever switches to standard
        // base64, padding, a different separator, or any other format change, this exact
        // string stops resolving and the test fails. Treat the literal as a release contract.
        String pinnedId = "Q3VzdG9tZXI6MQ";
        assertThat(no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1))
            .isEqualTo(pinnedId);

        Map<String, Object> data = execute(
            "{ node(id: \"" + pinnedId + "\") { __typename ... on Customer { firstName lastName } } }");
        assertThat(data).extractingByKey("node", as(MAP))
            .isNotNull()
            .containsEntry("__typename", "Customer")
            .containsEntry("firstName", "Mary")
            .containsEntry("lastName", "Smith");
    }

    @Test
    void node_compositeKeyEncoding_isFrozen() {
        // The execution-tier schema only exposes single-key NodeTypes, but the encoder is
        // used for composite keys too (see NodeIdPipelineTest's `bar` fixture). Pin the
        // composite wire format directly so a "tidy up the CSV" refactor that, say, switches
        // the separator from ',' to '|' or drops the comma escape rule is caught here.
        String pinned = "Rm9vOjEsMg";  // base64-url-no-pad of "Foo:1,2"
        assertThat(no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Foo", "1", "2"))
            .isEqualTo(pinned);
    }

    @Test
    void node_keyColumnsOrder_isLoadBearing() {
        // Composite keys encode positionally: encoder and decoder must agree on column order.
        // If anyone ever "normalises" by sorting args alphabetically, every previously-issued
        // composite ID stops matching. Asserting (1,2) ≠ (2,1) keeps the positional contract
        // honest. See plan-nodeid-directives.md "KjerneJooqGenerator contract".
        String fwd = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Foo", "1", "2");
        String rev = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Foo", "2", "1");
        assertThat(fwd).isNotEqualTo(rev);
    }

    // ===== Query.nodes — Relay batch dispatch =====

    @Test
    void nodes_emptyIds_returnsEmptyList() {
        Map<String, Object> data = execute("{ nodes(ids: []) { __typename } }");
        assertThat((List<?>) data.get("nodes")).isEmpty();
    }

    @Test
    void nodes_mixedTypeIds_resolvesEachAndPreservesOrder() {
        // Mixing typeIds in one call exercises the per-typeId hasIds branch in rowsNodes:
        // each typeId becomes one query, results scatter back to original positions.
        String c1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        String f1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 1);
        String c2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 2);
        Map<String, Object> data = execute(
            "{ nodes(ids: [\"" + c1 + "\", \"" + f1 + "\", \"" + c2 + "\"]) {"
            + " __typename ... on Customer { firstName } ... on Film { title } } }");
        var nodes = assertThat(data).extractingByKey("nodes", as(LIST)).hasSize(3);
        nodes.element(0, as(MAP)).containsEntry("__typename", "Customer").containsEntry("firstName", "Mary");
        nodes.element(1, as(MAP)).containsEntry("__typename", "Film").containsEntry("title", "ACADEMY DINOSAUR");
        nodes.element(2, as(MAP)).containsEntry("__typename", "Customer").containsEntry("firstName", "Patricia");
    }

    @Test
    void nodes_garbageId_returnsNullEntry() {
        // Malformed base64 — null at that position, others still resolve.
        String c1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        Map<String, Object> data = execute(
            "{ nodes(ids: [\"" + c1 + "\", \"not-a-valid-base64-id\"]) {"
            + " __typename ... on Customer { firstName } } }");
        var nodes = assertThat(data).extractingByKey("nodes", as(LIST)).hasSize(2);
        nodes.element(0, as(MAP)).containsEntry("firstName", "Mary");
        nodes.element(1).isNull();
    }

    @Test
    void nodes_unknownTypeId_returnsNullEntry() {
        // typeId prefix no NodeType claims — null at that position (Relay spec).
        String unknown = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("NotARegisteredType", "1");
        String c1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        Map<String, Object> data = execute(
            "{ nodes(ids: [\"" + unknown + "\", \"" + c1 + "\"]) {"
            + " __typename ... on Customer { firstName } } }");
        var nodes = assertThat(data).extractingByKey("nodes", as(LIST)).hasSize(2);
        nodes.element(0).isNull();
        nodes.element(1, as(MAP)).containsEntry("firstName", "Mary");
    }

    @Test
    void nodes_validPrefixNoSuchRow_returnsNullEntry() {
        // Registered typeId but the row doesn't exist — null at that position.
        String missing = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 999999);
        String c1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        Map<String, Object> data = execute(
            "{ nodes(ids: [\"" + c1 + "\", \"" + missing + "\"]) {"
            + " __typename ... on Customer { firstName } } }");
        var nodes = assertThat(data).extractingByKey("nodes", as(LIST)).hasSize(2);
        nodes.element(0, as(MAP)).containsEntry("firstName", "Mary");
        nodes.element(1).isNull();
    }

    @Test
    void nodes_perTypeIdBatch_emitsValuesJoinOrderByIdxShape() {
        // R50 phase (f-E): the per-typeId batch emission is asserted to be the lookup shape
        // (VALUES + JOIN + ORDER BY idx) rather than the legacy `WHERE row-IN`. Catches
        // regressions where EntityFetcherDispatch falls back to the old form. See
        // lift-nodeid-out-of-model.md "Query.nodes folds onto the lookup pipeline".
        String c1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        String c2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 2);
        String c3 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 3);
        SQL_LOG.clear();
        execute("{ nodes(ids: [\"" + c1 + "\", \"" + c2 + "\", \"" + c3 + "\"]) {"
            + " __typename ... on Customer { firstName } } }");
        // SQL_LOG entries are already lowercased by the ExecuteListener at line ~67. jOOQ
        // renders "public"."customer" with quoted identifiers; match on `customer` to span
        // both quoted and unquoted forms.
        assertThat(SQL_LOG)
            .as("per-typeId Query.nodes batch must use the lookup-shape (VALUES + JOIN + ORDER BY idx)")
            .anyMatch(s -> s.contains("\"customer\"")
                && s.contains("values (")
                && s.contains("join ")
                && s.contains("order by"));
        // Negative pin: no statement falls back to the legacy WHERE row-IN keyset filter.
        assertThat(SQL_LOG)
            .as("no Query.nodes statement should regress to the legacy WHERE row-IN form")
            .noneMatch(s -> s.contains("\"customer\"") && s.contains("\"customer_id\" in ("));
    }

    @Test
    void nodes_paddedBase64Id_canonicalizesAndResolves() {
        // Regression test for the canonicalisation fix: encoder emits no-padding URL base64,
        // but the decoder accepts padded input where the total length is 4-byte-aligned.
        // "Customer:1" (10 bytes) encodes to 14 chars unpadded; padding to 16 chars adds "==".
        // Without canonicalize, rowsNodes keys positions by the literal padded id but encode()
        // produces the no-padding form, so the result row would never match the requested
        // position and result[i] would stay null. Pre-fix, nodes(ids:) silently disagreed with
        // node(id:) on inputs the decoder accepts.
        String canonical = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        String padded = canonical + "==";
        // Sanity: the padded form must round-trip via node(id:) too — pre-existing decoder
        // leniency. If this assertion fails, the encoder changed and the test premise no
        // longer holds.
        Map<String, Object> nodeData = execute(
            "{ node(id: \"" + padded + "\") { __typename ... on Customer { firstName } } }");
        assertThat(nodeData).extractingByKey("node", as(MAP))
            .containsEntry("firstName", "Mary");

        Map<String, Object> data = execute(
            "{ nodes(ids: [\"" + padded + "\"]) {"
            + " __typename ... on Customer { firstName } } }");
        assertThat(data).extractingByKey("nodes", as(LIST))
            .hasSize(1)
            .first(as(MAP))
            .as("padded id must resolve under nodes(ids:)")
            .isNotNull()
            .containsEntry("__typename", "Customer")
            .containsEntry("firstName", "Mary");
    }

    @Test
    void nodes_duplicateIds_recordRepeatsAtAllPositions() {
        // ids = [c1, c2, c1] — DataLoader caching dedups into the batch so the SQL hits the
        // row once; the result fans back to all three positions. Asserts both shape (length 3,
        // matching records) and that the duplicate slot is the same record value.
        String c1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        String c2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 2);
        Map<String, Object> data = execute(
            "{ nodes(ids: [\"" + c1 + "\", \"" + c2 + "\", \"" + c1 + "\"]) {"
            + " __typename ... on Customer { firstName lastName } } }");
        var nodes = assertThat(data).extractingByKey("nodes", as(LIST)).hasSize(3);
        nodes.element(0, as(MAP)).containsEntry("firstName", "Mary");
        nodes.element(1, as(MAP)).containsEntry("firstName", "Patricia");
        nodes.element(2, as(MAP)).containsEntry("firstName", "Mary");
    }

    @Test
    void nodes_idAndOtherFieldsTogether_resolvesBothFromSingleProjection() {
        // Locks down the duplicate-projection invariant: when the GraphQL selection includes
        // `id`, $fields adds the nodeKey columns, and rowsNodes also needs them for the
        // result-scatter encode. The generator dedups so each key column is projected once;
        // both the generated `id` resolver (NodeIdEncoder.encode(typeId, r.get(t.<col>))) and
        // the encode call inside rowsNodes read the same column. Asserting the encoded id
        // equals the canonical encode of the seeded customer_id verifies both reads agree.
        String c1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        Map<String, Object> data = execute(
            "{ nodes(ids: [\"" + c1 + "\"]) {"
            + " __typename id ... on Customer { firstName customerId } } }");
        assertThat(data).extractingByKey("nodes", as(LIST))
            .hasSize(1)
            .first(as(MAP))
            .containsEntry("__typename", "Customer")
            .containsEntry("id", c1)
            .containsEntry("firstName", "Mary")
            .containsEntry("customerId", 1);
    }

    @Test
    void nodes_singleTenant_oneSqlQueryPerTypeId() {
        // 3 customer ids + 2 film ids in the default empty-tenant loader: expect exactly 2
        // SQL queries (one Customer batch + one Film batch). N+1 / per-id dispatch would be
        // 5; cross-typeId merge would be 1 against an impossible UNION query.
        String c1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        String c2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 2);
        String c3 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 3);
        String f1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 1);
        String f2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 2);

        QUERY_COUNT.set(0);
        execute("{ nodes(ids: [\"" + c1 + "\", \"" + f1 + "\", \"" + c2 + "\", \"" + f2 + "\", \"" + c3 + "\"]) { __typename } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
    }

    // Commented out under R190: getTenantId override and per-tenant DataLoader partitioning are
    // reintroduced under R45 (see roadmap/tenant-routing-and-execution-input.md).
    // The QUERY_COUNT == 2 assertion shape stays as the canonical execution-tier proof R45 will
    // re-anchor on once `<tenantColumn>` is configurable on the @table directive.
    // @Test
    // void nodes_perTenantPartition_separateBatchPerTenant() { ... }

    @Test
    void stores_synthesisedConnection_cursorRoundTrip() {
        // Fetch page 1 cursor, use it for page 2, assert hasNextPage is false (2 stores total).
        Map<String, Object> page1 = execute(
            "{ stores(first: 1) { pageInfo { endCursor } } }");
        String endCursor = assertThat(page1).extractingByKey("stores", as(MAP))
            .extractingByKey("pageInfo", as(MAP))
            .extractingByKey("endCursor", as(STRING))
            .isNotNull()
            .actual();

        Map<String, Object> page2 = execute(
            "{ stores(first: 1, after: \"" + endCursor + "\") { nodes { storeId } pageInfo { hasNextPage } } }");
        var conn2 = assertThat(page2).extractingByKey("stores", as(MAP));
        conn2.extractingByKey("nodes", as(LIST)).hasSize(1);
        conn2.extractingByKey("pageInfo", as(MAP)).containsEntry("hasNextPage", false);
    }

    // ===== Service / tableMethod root fetchers =====

    @Test
    @SuppressWarnings("unchecked")
    void queryTableMethod_popularFilms_filtersAndProjectsSelectedColumns() {
        // SampleQueryService.popularFilms returns filmTable.where(RENTAL_RATE >= minRentalRate).
        // The generated jOOQ Film overrides where() to return Film (not Table<R>), so the
        // filtered derived table preserves the specific type required by Invariants §3 — no
        // downcast needed when the framework projects via FilmType.$fields(...). Of the 5
        // seeded films, only ACE GOLDFINGER (rental_rate=4.99) clears the >= 3.0 threshold.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute("{ popularFilms(minRentalRate: 3.0) { title } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("popularFilms");
        assertThat(films).extracting(f -> f.get("title"))
            .containsExactly("ACE GOLDFINGER");
        // tableMethod path runs exactly one SQL query (the projection SELECT over the
        // developer-returned filtered Table).
        assertThat(QUERY_COUNT.get()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryServiceTable_filmsByService_returnsRecordsThatFlowThroughColumnFetchers() {
        // SampleQueryService.filmsByService runs its own SELECT and returns Result<FilmRecord>.
        // The framework does no projection; graphql-java's column fetchers walk the records via
        // field-name lookup. Asserting on title / rentalRate confirms the record traversal path works.
        Map<String, Object> data = execute(
            "{ filmsByService(ids: [1, 2]) { filmId title rentalRate } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsByService");
        assertThat(films).extracting(f -> f.get("title"))
            .containsExactly("ACADEMY DINOSAUR", "ACE GOLDFINGER");
        assertThat(films).extracting(f -> f.get("filmId"))
            .containsExactly(1, 2);
    }

    @Test
    void queryServiceRecord_filmCount_returnsScalar() {
        // SampleQueryService.filmCount returns Integer; graphql-java coerces to Int!.
        // Five films are seeded by init.sql.
        Map<String, Object> data = execute("{ filmCount }");
        assertThat(data.get("filmCount")).isEqualTo(5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryServiceTable_filmsByServiceRenamed_overrideBindsArgToDifferentlyNamedJavaParam() {
        // R53: GraphQL arg `ids` is bound to the Java parameter `filmIds` via argMapping
        // ("filmIds: ids" on the @service directive). Generated fetcher must read
        // env.getArgument("ids") (the GraphQL key) and pass it to the service method's `filmIds`
        // parameter — proves the graphqlArgName / Java-identifier split wires through end-to-end.
        Map<String, Object> data = execute(
            "{ filmsByServiceRenamed(ids: [1, 2]) { filmId title } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsByServiceRenamed");
        assertThat(films).extracting(f -> f.get("filmId")).containsExactly(1, 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryServiceTable_filmsByPath_argMappingWalksIntoNestedInputField() {
        // R84: argMapping right-hand side is a dot-path (`input.ids`). The Relay-style wrapper
        // input pattern: GraphQL takes one `input` argument carrying typed sub-fields; the Java
        // service signature stays GraphQL-input-shape-agnostic. Generated fetcher must walk
        // `env.getArgument("input")` into the `ids` key and pass the resulting List<Integer>
        // to the service method's `filmIds` parameter — proves the path-expression machinery
        // wires through end-to-end (parser → schema-walk → ParamSource.Arg.path → emit).
        Map<String, Object> data = execute(
            "{ filmsByPath(input: {ids: [1, 2]}) { filmId title } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsByPath");
        assertThat(films).extracting(f -> f.get("filmId")).containsExactly(1, 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryServiceTable_filmsByListPath_argMappingWalksThroughIntermediateList() {
        // R84 Phase D-list: argMapping `filmIds: input.items.id` has an intermediate list
        // segment (`items: [FilmIdItem!]!`). The generated fetcher must `.stream().map(...)`
        // each item, project its `id`, and `.toList()` to produce the List<Integer> the
        // service expects. Proves element-wise list traversal at emit time.
        Map<String, Object> data = execute(
            "{ filmsByListPath(input: {items: [{id: 1}, {id: 3}]}) { filmId title } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsByListPath");
        assertThat(films).extracting(f -> f.get("filmId")).containsExactly(1, 3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryServiceTable_filmsByNestedListPath_argMappingWalksTwoIntermediateLists() {
        // R84 Phase D-list (two-list-deep): argMapping `filmIdGroups: input.groups.items.id`
        // streams once over `groups` and once over each group's `items`, producing a
        // List<List<Integer>>. The service flattens before the SQL `IN (...)`.
        Map<String, Object> data = execute(
            "{ filmsByNestedListPath(input: {groups: ["
            + "{items: [{id: 2}, {id: 4}]}, "
            + "{items: [{id: 5}]}"
            + "]}) { filmId title } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsByNestedListPath");
        assertThat(films).extracting(f -> f.get("filmId")).containsExactly(2, 4, 5);
    }

    // ===== TableInterfaceType (Track A) =====

    @Test
    @SuppressWarnings("unchecked")
    void allContent_returnsAllRowsWithTypeName() {
        // allContent selects from the shared 'content' table and routes each row to FilmContent
        // or ShortContent via the TypeResolver. init.sql seeds 2 FILM rows and 2 SHORT rows.
        Map<String, Object> data = execute(
            "{ allContent { __typename contentId title } }");
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("allContent");
        assertThat(items).hasSize(4);
        assertThat(items).extracting(i -> i.get("__typename"))
            .containsExactlyInAnyOrder("FilmContent", "FilmContent", "ShortContent", "ShortContent");
    }

    @Test
    @SuppressWarnings("unchecked")
    void allContent_typeRouting_filmContentHasLength() {
        // FilmContent-typed rows expose the 'length' field; ShortContent rows do not.
        // This exercises inline fragment dispatch and confirms that the TypeResolver
        // correctly routes FILM rows to FilmContent.
        Map<String, Object> data = execute("""
            { allContent {
                __typename
                ... on FilmContent { length }
            } }
            """);
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("allContent");
        var filmItems = items.stream()
            .filter(i -> "FilmContent".equals(i.get("__typename")))
            .toList();
        assertThat(filmItems).hasSize(2);
        assertThat(filmItems).allSatisfy(i ->
            assertThat(i.get("length")).as("FilmContent.length").isNotNull());
    }

    @Test
    @SuppressWarnings("unchecked")
    void allContent_onlyReturnsKnownDiscriminatorValues() {
        // The generated WHERE IN ('FILM','SHORT') filter must exclude any rows with unknown
        // discriminator values that cannot be routed by the TypeResolver. If the filter is
        // missing, those rows would cause a null type resolution and an opaque runtime error.
        // init.sql seeds only FILM and SHORT rows so this test confirms no mystery rows appear.
        Map<String, Object> data = execute("{ allContent { __typename } }");
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("allContent");
        assertThat(items).extracting(i -> i.get("__typename"))
            .allSatisfy(t -> assertThat(t).isIn("FilmContent", "ShortContent"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void filmContent_singleValue_routesToFilmContent() {
        // Film #1 (ACADEMY DINOSAUR) has a content entry with CONTENT_TYPE='FILM' and film_id=1.
        // filmContent is a ChildField.TableInterfaceField on Film, resolved via the FK
        // content.film_id → film.film_id. The TypeResolver must route it to FilmContent.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { filmContent { __typename contentId title } } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        assertThat(films).hasSize(1);
        var film = films.get(0);
        var content = (Map<String, Object>) film.get("filmContent");
        assertThat(content).isNotNull();
        assertThat(content.get("__typename")).isEqualTo("FilmContent");
        assertThat(content.get("title")).isEqualTo("ACADEMY DINOSAUR (extended)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void filmContent_filmWithNoContent_returnsNull() {
        // Film #3 has no linked content entry; the FK join produces no match so the
        // single-value fetcher must return null (not an error).
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"3\"]) { filmContent { __typename } } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        assertThat(films).hasSize(1);
        var content = films.get(0).get("filmContent");
        assertThat(content).isNull();
    }

    // ===== TableInterfaceType cross-table participant fields =====
    //
    // FilmContent.rating lives on the joined film table (FK content.film_id → film.film_id).
    // ShortContent.description lives on the content table itself but is populated only on
    // SHORT rows. The interface fetcher emits a discriminator-gated LEFT JOIN per cross-table
    // participant field; the per-field DataFetcher reads the projected value back from the
    // result Record by alias.

    @Test
    @SuppressWarnings("unchecked")
    void allContent_crossTableField_joinsFilmAndReturnsRatingForFilmContent() {
        // FilmContent.rating reaches into the joined film row via content_film_id_fkey. The
        // film rows seeded in init.sql carry ratings, so requesting the inline-fragment field
        // must return a non-null value for every FilmContent and a null for every ShortContent
        // (whose discriminator-gated JOIN never matches).
        Map<String, Object> data = execute("""
            { allContent {
                __typename
                ... on FilmContent { rating }
            } }
            """);
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("allContent");
        var filmItems = items.stream()
            .filter(i -> "FilmContent".equals(i.get("__typename")))
            .toList();
        assertThat(filmItems).hasSize(2);
        assertThat(filmItems).allSatisfy(i ->
            assertThat(i.get("rating")).as("FilmContent.rating sourced from joined film row").isNotNull());
    }

    @Test
    @SuppressWarnings("unchecked")
    void allContent_crossTableField_leftJoinOmittedWhenNotRequested() {
        // When the cross-table field is not selected, the conditional LEFT JOIN must be skipped
        // so no over-fetching occurs. The SQL_LOG capture lets us assert no `left join film`
        // appears when only same-table columns are projected.
        SQL_LOG.clear();
        execute("{ allContent { __typename contentId title } }");
        var contentSql = SQL_LOG.stream()
            .filter(s -> s.contains("from \"content\"") || s.contains("from content"))
            .toList();
        assertThat(contentSql)
            .as("content fetcher SQL must not LEFT JOIN film when no cross-table field is selected")
            .allSatisfy(sql -> assertThat(sql).doesNotContain("left join \"film\"")
                                              .doesNotContain("left join film"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void allContent_crossTableField_leftJoinPresentWhenRequested() {
        // Inverse of the previous test: requesting the cross-table field must drive the LEFT JOIN.
        // Relaxed to match either {LEFT JOIN, LEFT OUTER JOIN} (jOOQ for Postgres typically
        // renders the latter) and either quoted or unquoted table names.
        SQL_LOG.clear();
        execute("""
            { allContent {
                __typename
                ... on FilmContent { rating }
            } }
            """);
        var joinedFilm = SQL_LOG.stream()
            .anyMatch(s -> s.contains("join") && s.contains("film") && s.contains("content"));
        assertThat(joinedFilm)
            .as("content fetcher SQL must JOIN film when FilmContent.rating is selected; captured: " + SQL_LOG)
            .isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void allContent_filmContentOnly_isolatesLengthFromShortDescription() {
        // FilmContent.length lives on the content table; ShortContent.description also lives on
        // the content table but in a different column. Per-participant projection (FilmContent.$fields
        // vs ShortContent.$fields) plus the type-routing TypeResolver guarantee FILM rows expose
        // length only and SHORT rows expose description only — even though both sit on the same row.
        Map<String, Object> data = execute("""
            { allContent {
                __typename
                ... on FilmContent { length }
                ... on ShortContent { description }
            } }
            """);
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("allContent");
        var filmItems = items.stream()
            .filter(i -> "FilmContent".equals(i.get("__typename")))
            .toList();
        var shortItems = items.stream()
            .filter(i -> "ShortContent".equals(i.get("__typename")))
            .toList();
        assertThat(filmItems).hasSize(2);
        assertThat(shortItems).hasSize(2);
        assertThat(filmItems).allSatisfy(i ->
            assertThat(i.get("length")).as("FilmContent.length populated").isNotNull());
        assertThat(filmItems).allSatisfy(i ->
            assertThat(i.containsKey("description"))
                .as("FilmContent rows do not surface ShortContent.description").isFalse());
        assertThat(shortItems).allSatisfy(i ->
            assertThat(i.get("description")).as("ShortContent.description populated").isNotNull());
        assertThat(shortItems).allSatisfy(i ->
            assertThat(i.containsKey("length"))
                .as("ShortContent rows do not surface FilmContent.length").isFalse());
    }

    @Test
    @SuppressWarnings("unchecked")
    void allContent_allParticipantFieldsTogether_routePerType() {
        // Triple-axis projection: FilmContent.length (same-table), ShortContent.description
        // (same-table, different column), and FilmContent.rating (cross-table via LEFT JOIN to
        // film). Each row carries the field appropriate to its type and not the others.
        Map<String, Object> data = execute("""
            { allContent {
                __typename
                ... on FilmContent { length rating }
                ... on ShortContent { description }
            } }
            """);
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("allContent");
        var filmItems = items.stream()
            .filter(i -> "FilmContent".equals(i.get("__typename")))
            .toList();
        var shortItems = items.stream()
            .filter(i -> "ShortContent".equals(i.get("__typename")))
            .toList();
        assertThat(filmItems).hasSize(2);
        assertThat(shortItems).hasSize(2);
        assertThat(filmItems).allSatisfy(i -> {
            assertThat(i.get("length")).isNotNull();
            assertThat(i.get("rating")).isNotNull();
        });
        assertThat(shortItems).allSatisfy(i ->
            assertThat(i.get("description")).isNotNull());
    }

    // ===== Multi-table polymorphic InterfaceType / UnionType (Track B2) =====
    //
    // Searchable is implemented by Film (table: film) and Actor (table: actor) on
    // heterogeneous tables. The generated fetcher is two-stage: stage 1 is a narrow
    // UNION ALL projecting (typename, pk0, sort) per branch; stage 2 dispatches per
    // typename via ValuesJoinRowBuilder. Records carry a synthetic __typename column
    // so the schema-class TypeResolver routes each row back to its concrete type.

    @Test
    @SuppressWarnings("unchecked")
    void search_returnsAllParticipantTypes() {
        // init.sql seeds 5 films + 3 actors. The search fetcher returns 5 Film rows and 3
        // Actor rows under the Searchable interface contract; __typename resolves per row.
        Map<String, Object> data = execute("{ search { __typename name } }");
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("search");
        assertThat(items).hasSize(8);
        long films = items.stream().filter(i -> "Film".equals(i.get("__typename"))).count();
        long actors = items.stream().filter(i -> "Actor".equals(i.get("__typename"))).count();
        assertThat(films).isEqualTo(5);
        assertThat(actors).isEqualTo(3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_inlineFragmentsResolvePerType() {
        // Inline fragments must dispatch per concrete type: Film rows have filmId+title,
        // Actor rows have actorId+firstName. The schema-class TypeResolver reads __typename
        // off each Record and returns the matching ObjectType so graphql-java picks the
        // correct field arm per row.
        Map<String, Object> data = execute("""
            { search {
                __typename
                ... on Film { filmId title }
                ... on Actor { actorId firstName }
            } }
            """);
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("search");
        var filmRows = items.stream()
            .filter(i -> "Film".equals(i.get("__typename")))
            .toList();
        var actorRows = items.stream()
            .filter(i -> "Actor".equals(i.get("__typename")))
            .toList();
        assertThat(filmRows).hasSize(5);
        assertThat(actorRows).hasSize(3);
        assertThat(filmRows).allSatisfy(i -> {
            assertThat(i.get("filmId")).as("Film.filmId").isNotNull();
            assertThat(i.get("title")).as("Film.title").isNotNull();
        });
        assertThat(actorRows).allSatisfy(i -> {
            assertThat(i.get("actorId")).as("Actor.actorId").isNotNull();
            assertThat(i.get("firstName")).as("Actor.firstName").isNotNull();
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_interfaceFieldName_resolvesPerParticipantColumn() {
        // Searchable.name remaps to FILM.TITLE on Film and ACTOR.FIRST_NAME on Actor.
        // Stage 2's per-typename SELECT projects each participant's $fields(env, t, env)
        // contribution, so the typed Record carries the right column under the interface
        // alias on each row.
        Map<String, Object> data = execute("{ search { __typename name } }");
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("search");
        // ACADEMY DINOSAUR is the first film seeded in init.sql; PENELOPE is the first actor.
        // The interface field 'name' must surface those values, sourced from each
        // participant's distinct concrete column.
        assertThat(items).filteredOn(i -> "Film".equals(i.get("__typename")))
            .extracting(i -> i.get("name"))
            .contains("ACADEMY DINOSAUR");
        assertThat(items).filteredOn(i -> "Actor".equals(i.get("__typename")))
            .extracting(i -> i.get("name"))
            .contains("PENELOPE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void documents_unionVariant_returnsSameShape() {
        // Document = Film | Actor exercises the union variant's wiring (QueryUnionField).
        // MultiTablePolymorphicEmitter produces the same two-stage shape for both
        // QueryInterfaceField and QueryUnionField; this asserts the union form returns the
        // same row count and __typename routing as the interface form.
        Map<String, Object> data = execute("""
            { documents {
                __typename
                ... on Film { filmId }
                ... on Actor { actorId }
            } }
            """);
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("documents");
        assertThat(items).hasSize(8);
        assertThat(items).extracting(i -> (String) i.get("__typename"))
            .allSatisfy(tn -> assertThat(tn).isIn("Film", "Actor"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_orderedBySortKey_acrossParticipants() {
        // Stage 1's ORDER BY runs database-side on the synthetic __sort__ column. Single-PK
        // participants project their PK directly, so the result interleaves Film and Actor
        // rows by their PK values. With Film PKs 1..5 and Actor PKs 1..3, the leading rows
        // (sort key 1..3) carry both types; trailing rows (sort key 4..5) are Films only.
        Map<String, Object> data = execute("""
            { search {
                __typename
                ... on Film { filmId }
                ... on Actor { actorId }
            } }
            """);
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("search");
        assertThat(items.subList(items.size() - 2, items.size()))
            .as("trailing rows carry the highest sort keys (Film 4 and 5; Actor PKs cap at 3)")
            .allSatisfy(i -> assertThat(i.get("__typename")).isEqualTo("Film"));
    }

    // ===== Multi-table polymorphic Connection (Track B4a) =====
    //
    // searchConnection wraps the same Searchable interface as Query.search but in @asConnection
    // form. The emitter wraps the per-branch UNION ALL in a derived table 'pages' so cursor
    // decode + .seek + LIMIT N+1 apply uniformly across the union; each typed stage-2 Record
    // carries both __typename and __sort__ so ConnectionHelper.encodeCursor can read the sort
    // key on each edge. Default page size is 5 (set in fixture) so a default-args query returns
    // exactly 5 of the 8 total rows. The emitter adds (sortField ASC, typenameField ASC) as a
    // composite ORDER BY so rows tied on sort key (e.g. Film(1)/Actor(1)) order deterministically.

    @Test
    @SuppressWarnings("unchecked")
    void searchConnection_firstPage_returnsFirstNAcrossParticipants() {
        // first: 3 returns the 3 lowest sort keys: Actor(1), Film(1), Actor(2). Tie order uses
        // typename ASC so 'Actor' precedes 'Film' on shared sort keys.
        Map<String, Object> data = execute("""
            { searchConnection(first: 3) {
                nodes { __typename ... on Film { filmId } ... on Actor { actorId } }
                pageInfo { hasNextPage hasPreviousPage }
            } }
            """);
        var conn = (Map<String, Object>) data.get("searchConnection");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).hasSize(3);
        var pageInfo = (Map<String, Object>) conn.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(true);
        assertThat(pageInfo.get("hasPreviousPage")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchConnection_defaultPageSize_returnsFiveOfEight() {
        // Fixture sets defaultFirstValue: 5. With 5 films + 3 actors = 8 rows total, an
        // unargumented query returns the first 5 by sort key and reports hasNextPage=true.
        Map<String, Object> data = execute("""
            { searchConnection { nodes { __typename } pageInfo { hasNextPage } } }
            """);
        var conn = (Map<String, Object>) data.get("searchConnection");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).hasSize(5);
        var pageInfo = (Map<String, Object>) conn.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchConnection_withAfterCursor_returnsNextPage() {
        // Page 1 (first: 3) + after cursor → page 2 (first: 3) returns the next 3 by sort key.
        Map<String, Object> page1Data = execute("""
            { searchConnection(first: 3) {
                edges { cursor }
                pageInfo { endCursor }
            } }
            """);
        var conn1 = (Map<String, Object>) page1Data.get("searchConnection");
        var pageInfo1 = (Map<String, Object>) conn1.get("pageInfo");
        String endCursor = (String) pageInfo1.get("endCursor");
        assertThat(endCursor).isNotNull();

        Map<String, Object> page2Data = execute(
            "{ searchConnection(first: 3, after: \"" + endCursor + "\") { "
            + "nodes { __typename } pageInfo { hasNextPage } } }");
        var conn2 = (Map<String, Object>) page2Data.get("searchConnection");
        List<Map<String, Object>> nodes2 = (List<Map<String, Object>>) conn2.get("nodes");
        assertThat(nodes2).hasSize(3);
        var pageInfo2 = (Map<String, Object>) conn2.get("pageInfo");
        // 8 total - 3 (page1) - 3 (page2) = 2 remaining → hasNextPage true.
        assertThat(pageInfo2.get("hasNextPage")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchConnection_lastPage_hasNextPageFalse() {
        // first: 8 returns all 8 rows; hasNextPage is false because over-fetch returns 8 rows
        // (less than pageSize+1 = 9).
        Map<String, Object> data = execute("""
            { searchConnection(first: 8) {
                nodes { __typename }
                pageInfo { hasNextPage }
            } }
            """);
        var conn = (Map<String, Object>) data.get("searchConnection");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).hasSize(8);
        var pageInfo = (Map<String, Object>) conn.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchConnection_inlineFragmentsResolvePerTypeOnConnectionPath() {
        // Confirms the connection path retains TypeResolver routing: inline fragments dispatch
        // per concrete type so Film rows have filmId+title, Actor rows have actorId+firstName.
        Map<String, Object> data = execute("""
            { searchConnection(first: 8) {
                nodes {
                    __typename
                    ... on Film { filmId title }
                    ... on Actor { actorId firstName }
                }
            } }
            """);
        var conn = (Map<String, Object>) data.get("searchConnection");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        var filmRows = nodes.stream().filter(i -> "Film".equals(i.get("__typename"))).toList();
        var actorRows = nodes.stream().filter(i -> "Actor".equals(i.get("__typename"))).toList();
        assertThat(filmRows).hasSize(5);
        assertThat(actorRows).hasSize(3);
        assertThat(filmRows).allSatisfy(i -> {
            assertThat(i.get("filmId")).as("Film.filmId").isNotNull();
            assertThat(i.get("title")).as("Film.title").isNotNull();
        });
        assertThat(actorRows).allSatisfy(i -> {
            assertThat(i.get("actorId")).as("Actor.actorId").isNotNull();
            assertThat(i.get("firstName")).as("Actor.firstName").isNotNull();
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentsConnection_unionVariant_works() {
        // Union variant equivalent: documentsConnection wraps `Document = Film | Actor` in
        // @asConnection. Same emitter path as searchConnection; this test pins parity.
        Map<String, Object> data = execute("""
            { documentsConnection(first: 4) {
                nodes { __typename ... on Film { filmId } ... on Actor { actorId } }
                pageInfo { hasNextPage }
            } }
            """);
        var conn = (Map<String, Object>) data.get("documentsConnection");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).hasSize(4);
        assertThat(nodes).extracting(i -> (String) i.get("__typename"))
            .allSatisfy(tn -> assertThat(tn).isIn("Film", "Actor"));
        var pageInfo = (Map<String, Object>) conn.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchConnection_totalCount_returnsTotalRowCountAcrossParticipants() {
        // B4b: ConnectionResult now carries the UNION-ALL derived table 'pages' so
        // ConnectionHelper.totalCount runs SELECT count(*) FROM (UNION ALL Film + Actor) AS pages.
        // 5 films + 3 actors = 8 total; the count is independent of the page window.
        Map<String, Object> data = execute("""
            { searchConnection(first: 1) { totalCount } }
            """);
        var conn = (Map<String, Object>) data.get("searchConnection");
        assertThat(conn.get("totalCount")).isEqualTo(8);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchConnection_totalCount_independentOfAfterCursor() {
        // The count is over the full UNION ALL, not the page window — so paging past the start
        // with an after cursor still reports the full 8.
        Map<String, Object> page1Data = execute("""
            { searchConnection(first: 3) { pageInfo { endCursor } } }
            """);
        var pageInfo1 = (Map<String, Object>) ((Map<String, Object>) page1Data.get("searchConnection")).get("pageInfo");
        String endCursor = (String) pageInfo1.get("endCursor");

        Map<String, Object> page2Data = execute(
            "{ searchConnection(first: 3, after: \"" + endCursor + "\") { totalCount } }");
        var conn = (Map<String, Object>) page2Data.get("searchConnection");
        assertThat(conn.get("totalCount")).isEqualTo(8);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchConnection_totalCount_isLazyOnSelection_noCountSqlWhenUnselected() {
        // The polymorphic totalCount path should remain lazy on selection just like the
        // single-table case. graphql-java only invokes the resolver when the client picks
        // the field; no SELECT count should fire for an unselected totalCount.
        SQL_LOG.clear();
        execute("{ searchConnection(first: 2) { nodes { __typename } pageInfo { hasNextPage } } }");
        assertThat(SQL_LOG)
            .as("no SELECT count statement should be issued when totalCount is not selected")
            .noneMatch(s -> s.contains("select count"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchConnection_totalCount_selected_issuesCountSql() {
        // Companion ratchet: when totalCount IS selected, exactly one count statement runs;
        // pages-derived-table emission shows up as a `select count(*) from (...) as "pages"` shape.
        SQL_LOG.clear();
        execute("{ searchConnection(first: 2) { totalCount nodes { __typename } } }");
        assertThat(SQL_LOG)
            .filteredOn(s -> s.contains("select count"))
            .as("selecting totalCount should issue exactly one SELECT count statement")
            .hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentsConnection_totalCount_unionVariant_returnsTotalRowCount() {
        // Union variant parity: same emitter path, same totalCount shape.
        Map<String, Object> data = execute("""
            { documentsConnection(first: 1) { totalCount } }
            """);
        var conn = (Map<String, Object>) data.get("documentsConnection");
        assertThat(conn.get("totalCount")).isEqualTo(8);
    }

    // ===== Composite-PK participants in connection mode (R36 item 1) =====
    //
    // pagedItems wraps `interface PagedItem` (PagedA + PagedB, both composite (Integer, Integer)
    // PK) in @asConnection. The polymorphic emitter projects DSL.jsonbArray(k1, k2) as the
    // synthetic __sort__ column and types it as JSONB so PostgreSQL's lexicographic JSONB
    // ordering reproduces the multi-column ordering across the union, and
    // ConnectionHelper.encodeCursor / decodeCursor round-trip JSONB through JSONB.toString()
    // and Convert.convert(String, JSONB.class).
    //
    // Seed (init.sql):
    //   paged_a (k1, k2, name): (1,1,'A-1-1'), (1,2,'A-1-2'), (2,1,'A-2-1')
    //   paged_b (k1, k2, name): (1,1,'B-1-1'), (1,3,'B-1-3'), (3,1,'B-3-1')
    // JSONB lexicographic order is element-wise: [1,1] < [1,2] < [1,3] < [2,1] < [3,1]. With the
    // (sort ASC, typename ASC) tiebreaker, the global order is:
    //   PagedA[1,1], PagedB[1,1], PagedA[1,2], PagedB[1,3], PagedA[2,1], PagedB[3,1].

    @Test
    @SuppressWarnings("unchecked")
    void pagedItemsConnection_firstPage_returnsFirstThreeAcrossCompositePkParticipants() {
        // first: 3 returns the 3 lowest sort keys: PagedA[1,1], PagedB[1,1] (typename tiebreaker
        // resolves the [1,1] tie alphabetically: 'PagedA' < 'PagedB'), PagedA[1,2].
        Map<String, Object> data = execute("""
            { pagedItems(first: 3) {
                nodes {
                    __typename
                    ... on PagedA { k1 k2 name }
                    ... on PagedB { k1 k2 name }
                }
                pageInfo { hasNextPage hasPreviousPage }
            } }
            """);
        var conn = (Map<String, Object>) data.get("pagedItems");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).hasSize(3);
        assertThat(nodes).extracting(i -> i.get("__typename") + ":" + i.get("k1") + "," + i.get("k2"))
            .containsExactly("PagedA:1,1", "PagedB:1,1", "PagedA:1,2");
        var pageInfo = (Map<String, Object>) conn.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(true);
        assertThat(pageInfo.get("hasPreviousPage")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pagedItemsConnection_afterCursor_returnsNextPage() {
        // Page 1 (first: 3) + after endCursor → page 2 (first: 3) returns the next 3 by sort.
        // The cursor encodes the JSONB sort value of the last row on page 1, base64-wrapped via
        // ConnectionHelper.encodeCursor; decodeCursor parses it back as a JSONB Field for seek.
        Map<String, Object> page1Data = execute("""
            { pagedItems(first: 3) { pageInfo { endCursor } } }
            """);
        String endCursor = (String) ((Map<String, Object>) ((Map<String, Object>) page1Data.get("pagedItems")).get("pageInfo")).get("endCursor");
        assertThat(endCursor).isNotNull();

        Map<String, Object> page2Data = execute(
            "{ pagedItems(first: 3, after: \"" + endCursor + "\") { "
            + "nodes { __typename ... on PagedA { k1 k2 } ... on PagedB { k1 k2 } } "
            + "pageInfo { hasNextPage } } }");
        var conn2 = (Map<String, Object>) page2Data.get("pagedItems");
        List<Map<String, Object>> nodes2 = (List<Map<String, Object>>) conn2.get("nodes");
        assertThat(nodes2).hasSize(3);
        assertThat(nodes2).extracting(i -> i.get("__typename") + ":" + i.get("k1") + "," + i.get("k2"))
            .containsExactly("PagedB:1,3", "PagedA:2,1", "PagedB:3,1");
        var pageInfo2 = (Map<String, Object>) conn2.get("pageInfo");
        // 6 total - 3 (page1) - 3 (page2) = 0 remaining → hasNextPage false.
        assertThat(pageInfo2.get("hasNextPage")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pagedItemsConnection_lastPage_hasNextPageFalse() {
        // first: 6 returns all 6 rows; over-fetch sees 6 rows (< pageSize+1 = 7) → hasNextPage false.
        Map<String, Object> data = execute("""
            { pagedItems(first: 6) {
                nodes { __typename }
                pageInfo { hasNextPage }
            } }
            """);
        var conn = (Map<String, Object>) data.get("pagedItems");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).hasSize(6);
        var pageInfo = (Map<String, Object>) conn.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pagedItemsConnection_totalCount_returnsTotalAcrossCompositePkParticipants() {
        // totalCount runs SELECT count(*) FROM (UNION ALL paged_a + paged_b) AS pages,
        // independent of the page window. 3 + 3 = 6.
        Map<String, Object> data = execute("""
            { pagedItems(first: 1) { totalCount } }
            """);
        var conn = (Map<String, Object>) data.get("pagedItems");
        assertThat(conn.get("totalCount")).isEqualTo(6);
    }

    // ===== ChildField.UnionField (Track B3) =====
    //
    // Address.occupants returns a union of Customer + Staff rows whose address_id matches
    // the parent Address record. Stage-1 narrow UNION ALL adds a per-branch
    // .where(<participant>.address_id = parentRecord.address_id) predicate; stage-2
    // dispatches per typename, the same shape B2 uses for the root case.

    @Test
    @SuppressWarnings("unchecked")
    void addressOccupants_returnsCustomersAndStaffForAddress() {
        // init.sql seeds:
        //   address_id=1: Customers Mary + Barbara (2), no staff
        //   address_id=3: Customer Linda (1), Staff Mike (1) — mixed types
        // Linda is Customer 3 with store_id=2; navigate Customer.address to reach Address(3).
        Map<String, Object> data = execute("""
            { customerById(customer_id: ["3"], store_id: "2") {
                firstName
                address {
                    addressId
                    occupants {
                        __typename
                        ... on Customer { firstName }
                        ... on Staff { firstName }
                    }
                }
            } }
            """);
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customerById");
        assertThat(customers).hasSize(1);
        var address = (Map<String, Object>) customers.get(0).get("address");
        assertThat(address.get("addressId")).isEqualTo(3);
        var occupants = (List<Map<String, Object>>) address.get("occupants");
        assertThat(occupants).hasSize(2);
        var byType = occupants.stream()
            .collect(java.util.stream.Collectors.groupingBy(o -> (String) o.get("__typename")));
        assertThat(byType.get("Customer")).hasSize(1);
        assertThat(byType.get("Staff")).hasSize(1);
        assertThat((String) byType.get("Customer").get(0).get("firstName")).isEqualTo("Linda");
        assertThat((String) byType.get("Staff").get(0).get("firstName")).isEqualTo("Mike");
    }

    @Test
    @SuppressWarnings("unchecked")
    void addressOccupants_homogeneousAddress_returnsOnlyOneType() {
        // Address(1) has only Customers (Mary, Barbara) — the Staff branch's stage-1 SELECT
        // returns no rows, and the per-typename dispatch skips selectStaffForOccupants
        // because byType.containsKey("Staff") is false. Mary is Customer 1 in store 1.
        Map<String, Object> data = execute("""
            { customerById(customer_id: ["1"], store_id: "1") {
                address {
                    addressId
                    occupants { __typename }
                }
            } }
            """);
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customerById");
        var address = (Map<String, Object>) customers.get(0).get("address");
        assertThat(address.get("addressId")).isEqualTo(1);
        var occupants = (List<Map<String, Object>>) address.get("occupants");
        assertThat(occupants).hasSize(2);
        assertThat(occupants).extracting(o -> o.get("__typename"))
            .containsOnly("Customer");
    }

    @Test
    @SuppressWarnings("unchecked")
    void addressOccupants_asymmetricFragment_responsePayloadDropsInactiveBranch() {
        // R108 behavioural pin: an asymmetric inline fragment over the union projects firstName
        // only on Customer. Pre-R108 the wire payload was already correct (graphql-java drops
        // the unselected field at serialisation), but the rendered SQL over-selected
        // staff.first_name. R108's wrapper restricts the per-typename selection at the SQL
        // layer; this test pins the response shape alongside the SQL-layer proof in
        // PolymorphicProjectionQueryTest. A future regression that inverts the bug
        // (under-selecting active branches) would fail loudly here as a null firstName on
        // active Customer rows.
        Map<String, Object> data = execute("""
            { customerById(customer_id: ["3"], store_id: "2") {
                address {
                    addressId
                    occupants {
                        __typename
                        ... on Customer { firstName }
                    }
                }
            } }
            """);
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customerById");
        var address = (Map<String, Object>) customers.get(0).get("address");
        var occupants = (List<Map<String, Object>>) address.get("occupants");
        var byType = occupants.stream()
            .collect(java.util.stream.Collectors.groupingBy(o -> (String) o.get("__typename")));
        assertThat((String) byType.get("Customer").get(0).get("firstName")).isEqualTo("Linda");
        // Staff branch must still resolve (the union member exists in the result), but its
        // firstName key is absent from the projected map because no Staff fragment requested it.
        assertThat(byType.get("Staff").get(0))
            .doesNotContainKey("firstName")
            .containsKey("__typename");
    }

    @Test
    @SuppressWarnings("unchecked")
    void addressOccupants_perBranchWhereScopesToAddress() {
        // Two independent address parents must each scope their UNION ALL branches to their
        // own address_id. Mary (address 1, all-customer occupants) and Linda (address 3,
        // mixed occupants) have disjoint occupant sets; the per-branch parentRecord-FK
        // predicate is what keeps those scopes independent.
        Map<String, Object> data = execute("""
            { customers(active: true) {
                customerId
                firstName
                address {
                    addressId
                    occupants { __typename ... on Customer { firstName } ... on Staff { firstName } }
                }
            } }
            """);
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customers");
        var byCustomerId = customers.stream()
            .collect(java.util.stream.Collectors.toMap(
                c -> (Integer) c.get("customerId"),
                c -> (Map<String, Object>) c.get("address")));
        var addressOfMary = byCustomerId.get(1);
        var addressOfLinda = byCustomerId.get(3);
        assertThat(addressOfMary.get("addressId")).isEqualTo(1);
        assertThat(addressOfLinda.get("addressId")).isEqualTo(3);
        var maryOccupants = (List<Map<String, Object>>) addressOfMary.get("occupants");
        var lindaOccupants = (List<Map<String, Object>>) addressOfLinda.get("occupants");
        assertThat(maryOccupants).extracting(o -> o.get("firstName"))
            .as("address 1 occupants are only Customers (Mary, Barbara) — no Staff there")
            .containsExactlyInAnyOrder("Mary", "Barbara");
        assertThat(lindaOccupants).extracting(o -> o.get("firstName"))
            .as("address 3 occupants are mixed (Customer Linda + Staff Mike)")
            .containsExactlyInAnyOrder("Linda", "Mike");
    }

    // ===== ChildField.UnionField + @asConnection (Track B4c-1) =====
    //
    // Address.occupantsConnection wraps the same union as Address.occupants but in connection
    // mode. Per-branch parent-FK WHERE (B3) lives inside each UNION ALL branch; the outer
    // pagesTable derived table feeds .seek/.limit and ConnectionResult.table() so totalCount
    // counts only this parent's occupants. Per-parent inline shape — DataLoader-batched
    // windowed CTE form is the B4c-2 follow-up.

    @Test
    @SuppressWarnings("unchecked")
    void addressOccupantsConnection_returnsParentScopedRows() {
        // Address(3) has Linda (Customer) + Mike (Staff). The connection returns both nodes;
        // hasNextPage is false because pageSize 5 over-fetches and only 2 rows exist.
        Map<String, Object> data = execute("""
            { customerById(customer_id: ["3"], store_id: "2") {
                address {
                    addressId
                    occupantsConnection {
                        nodes { __typename ... on Customer { firstName } ... on Staff { firstName } }
                        pageInfo { hasNextPage hasPreviousPage }
                    }
                }
            } }
            """);
        var customers = (List<Map<String, Object>>) data.get("customerById");
        var address = (Map<String, Object>) customers.get(0).get("address");
        assertThat(address.get("addressId")).isEqualTo(3);
        var conn = (Map<String, Object>) address.get("occupantsConnection");
        var nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).hasSize(2);
        assertThat(nodes).extracting(n -> n.get("firstName"))
            .containsExactlyInAnyOrder("Linda", "Mike");
        var pageInfo = (Map<String, Object>) conn.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(false);
        assertThat(pageInfo.get("hasPreviousPage")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void addressOccupantsConnection_totalCount_isParentScoped() {
        // The per-branch parentRecord-FK WHERE inside each UNION ALL branch is what makes
        // totalCount count only this parent's occupants. Address(2) has 2 customers
        // (Patricia, Elizabeth) + 1 staff (Jon) = 3; Address(3) has 1 customer (Linda) +
        // 1 staff (Mike) = 2. If the WHERE were dropped the count would collapse to the
        // global occupant total.
        Map<String, Object> data = execute("""
            { customerById(customer_id: ["2"], store_id: "1") {
                address { addressId occupantsConnection { totalCount } }
            } }
            """);
        var customers = (List<Map<String, Object>>) data.get("customerById");
        var address = (Map<String, Object>) customers.get(0).get("address");
        assertThat(address.get("addressId")).isEqualTo(2);
        var conn = (Map<String, Object>) address.get("occupantsConnection");
        assertThat(conn.get("totalCount")).isEqualTo(3);

        Map<String, Object> data3 = execute("""
            { customerById(customer_id: ["3"], store_id: "2") {
                address { addressId occupantsConnection { totalCount } }
            } }
            """);
        var address3 = (Map<String, Object>) ((List<Map<String, Object>>) data3.get("customerById"))
            .get(0).get("address");
        assertThat(((Map<String, Object>) address3.get("occupantsConnection")).get("totalCount"))
            .isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void addressOccupantsConnection_paginationWithAfterCursor() {
        // Address(2) has 3 occupants. Sort keys: Customer 2 (Patricia), Customer 5 (Elizabeth),
        // Staff 2 (Jon). Tie order with __typename ASC at sort=2 places Customer before Staff.
        // first: 2 returns the first 2 by sort key; after-cursor advances to the third.
        Map<String, Object> page1Data = execute("""
            { customerById(customer_id: ["2"], store_id: "1") {
                address { occupantsConnection(first: 2) {
                    edges { cursor node { __typename ... on Customer { firstName } ... on Staff { firstName } } }
                    pageInfo { endCursor hasNextPage }
                } }
            } }
            """);
        var page1Conn = (Map<String, Object>) ((Map<String, Object>) ((List<Map<String, Object>>) page1Data
            .get("customerById")).get(0).get("address")).get("occupantsConnection");
        var edges1 = (List<Map<String, Object>>) page1Conn.get("edges");
        assertThat(edges1).hasSize(2);
        var pageInfo1 = (Map<String, Object>) page1Conn.get("pageInfo");
        assertThat(pageInfo1.get("hasNextPage")).isEqualTo(true);
        String endCursor = (String) pageInfo1.get("endCursor");
        assertThat(endCursor).isNotNull();

        Map<String, Object> page2Data = execute(
            "{ customerById(customer_id: [\"2\"], store_id: \"1\") { "
            + "address { occupantsConnection(first: 2, after: \"" + endCursor + "\") { "
            + "nodes { __typename ... on Customer { firstName } ... on Staff { firstName } } "
            + "pageInfo { hasNextPage } } } } }");
        var page2Conn = (Map<String, Object>) ((Map<String, Object>) ((List<Map<String, Object>>) page2Data
            .get("customerById")).get(0).get("address")).get("occupantsConnection");
        var nodes2 = (List<Map<String, Object>>) page2Conn.get("nodes");
        assertThat(nodes2).hasSize(1);
        assertThat(((Map<String, Object>) page2Conn.get("pageInfo")).get("hasNextPage")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void addressOccupantsConnection_dataLoaderBatchesAcrossParents() {
        // R36 Track B4c-2 batching ratchet: with N parents fetching occupantsConnection in
        // one request, the DataLoader collapses the per-branch UNION-ALL page-rows query to
        // ONE invocation regardless of N. The {@code as "parentInput"} VALUES table is unique
        // to the rows-method (stage 2's per-typename helpers use {@code customerInput} /
        // {@code staffInput} aliases), so a single occurrence in SQL_LOG = one batched query.
        // Without B4c-2 (or under B4c-1's per-parent inline shape) this would fire one
        // UNION-ALL per parent.
        SQL_LOG.clear();
        Map<String, Object> data = execute("""
            { customers(active: true) {
                customerId
                address {
                    addressId
                    occupantsConnection(first: 1) {
                        edges { node { __typename } }
                    }
                }
            } }
            """);
        var customers = (List<Map<String, Object>>) data.get("customers");
        assertThat(customers)
            .as("customers list should be non-empty (sakila has 599 active customers)")
            .isNotEmpty();
        // Every customer carries an address with an occupantsConnection sublist.
        assertThat(customers).allSatisfy(c -> {
            var address = (Map<String, Object>) c.get("address");
            assertThat(address.get("addressId")).isNotNull();
            var conn = (Map<String, Object>) address.get("occupantsConnection");
            assertThat(conn).isNotNull();
        });
        // The page-rows rows-method fires ONE UNION-ALL with parentInput VALUES regardless
        // of how many parents we ask about — that is the whole point of the DataLoader
        // promotion from B4c-1 (per-parent inline) to B4c-2 (batched).
        var parentInputQueries = SQL_LOG.stream()
            .filter(s -> s.contains("as \"parentinput\""))
            .toList();
        assertThat(parentInputQueries)
            .as("rows-method fires once for the entire batch; SQL_LOG: " + parentInputQueries)
            .hasSize(1);
    }

    // ===== Composite-PK parent for child interface @asConnection (R36 item 2) =====
    //
    // Project's PK is (org_id, project_id); ProjectNote and ProjectEvent FK back via
    // composite (org_id, project_id) → project. The B4c-2 RowN widening pins Row1 → Row2 at the
    // unit-tier (TypeFetcherGeneratorTest); these tests carry it through to PostgreSQL.
    //
    // Seed (init.sql):
    //   project (org_id, project_id, name): (1,100,'Atlas'), (1,101,'Beacon'), (2,100,'Cipher')
    //   project_note (note_id auto, org_id, project_id, body):
    //     (1, 1, 100, 'Atlas-N1'), (2, 1, 100, 'Atlas-N2'), (3, 1, 100, 'Atlas-N3'),
    //     (4, 1, 101, 'Beacon-N1'), (5, 1, 101, 'Beacon-N2'), (6, 2, 100, 'Cipher-N1')
    //   project_event (event_id auto, org_id, project_id, summary):
    //     (1, 1, 100, 'Atlas-E1'),
    //     (2, 1, 101, 'Beacon-E1'), (3, 1, 101, 'Beacon-E2'),
    //     (4, 2, 100, 'Cipher-E1')
    // Per-parent counts: Atlas 3+1=4, Beacon 2+2=4, Cipher 1+1=2.

    @Test
    @SuppressWarnings("unchecked")
    void projectItemsConnection_returnsParentScopedRows() {
        // Each parent's connection counts only its own children. Atlas has 4 items, Cipher has 2.
        // The parent PK columns (orgId, projectId) must be projected so the child fetcher can
        // extract them from env.getSource() — selecting them explicitly satisfies the framework's
        // jOOQ row-type contract on the parent Record.
        Map<String, Object> data = execute("""
            { projects { name orgId projectId items { nodes { __typename } } } }
            """);
        var projects = (List<Map<String, Object>>) data.get("projects");
        var byName = projects.stream()
            .collect(java.util.stream.Collectors.toMap(p -> (String) p.get("name"), java.util.function.Function.identity()));
        var atlasItems = (List<Map<String, Object>>) ((Map<String, Object>) byName.get("Atlas").get("items")).get("nodes");
        var beaconItems = (List<Map<String, Object>>) ((Map<String, Object>) byName.get("Beacon").get("items")).get("nodes");
        var cipherItems = (List<Map<String, Object>>) ((Map<String, Object>) byName.get("Cipher").get("items")).get("nodes");
        assertThat(atlasItems).hasSize(4);
        assertThat(beaconItems).hasSize(4);
        assertThat(cipherItems).hasSize(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectItemsConnection_totalCount_isParentScoped() {
        // Per-parent ConnectionResult uses idxField.eq(i) so totalCount restricts to the carrier
        // parent's slice of the union. Cipher should report exactly 2; Atlas exactly 4.
        Map<String, Object> data = execute("""
            { projects { name orgId projectId items { totalCount } } }
            """);
        var projects = (List<Map<String, Object>>) data.get("projects");
        var byName = projects.stream()
            .collect(java.util.stream.Collectors.toMap(p -> (String) p.get("name"), java.util.function.Function.identity()));
        assertThat(((Map<String, Object>) byName.get("Atlas").get("items")).get("totalCount")).isEqualTo(4);
        assertThat(((Map<String, Object>) byName.get("Beacon").get("items")).get("totalCount")).isEqualTo(4);
        assertThat(((Map<String, Object>) byName.get("Cipher").get("items")).get("totalCount")).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectItemsConnection_paginationWithAfterCursor() {
        // Sort order within a parent is (sortField ASC, __typename ASC). Beacon has Notes 4, 5
        // and Events 2, 3. PK ASC interleave: Event(2), Event(3), Note(4), Note(5). first: 2
        // returns the first two Events; after-cursor advances to the two Notes.
        Map<String, Object> page1Data = execute("""
            { projects {
                name orgId projectId
                items(first: 2) {
                    edges { node { __typename ... on ProjectNote { noteId } ... on ProjectEvent { eventId } } }
                    pageInfo { endCursor hasNextPage }
                }
            } }
            """);
        var projects = (List<Map<String, Object>>) page1Data.get("projects");
        var beacon = projects.stream().filter(p -> "Beacon".equals(p.get("name"))).findFirst().orElseThrow();
        var page1Conn = (Map<String, Object>) beacon.get("items");
        var pageInfo1 = (Map<String, Object>) page1Conn.get("pageInfo");
        assertThat(pageInfo1.get("hasNextPage")).isEqualTo(true);
        String endCursor = (String) pageInfo1.get("endCursor");
        assertThat(endCursor).isNotNull();

        // Page 2: filter to Beacon only via the after cursor on the second projects fetch.
        // Since `projects` returns all parents, we re-fetch with after cursor and filter again.
        Map<String, Object> page2Data = execute(
            "{ projects { name orgId projectId items(first: 2, after: \"" + endCursor + "\") { "
            + "nodes { __typename ... on ProjectNote { noteId } ... on ProjectEvent { eventId } } "
            + "pageInfo { hasNextPage } } } }");
        var projects2 = (List<Map<String, Object>>) page2Data.get("projects");
        var beacon2 = projects2.stream().filter(p -> "Beacon".equals(p.get("name"))).findFirst().orElseThrow();
        var page2Conn = (Map<String, Object>) beacon2.get("items");
        var nodes2 = (List<Map<String, Object>>) page2Conn.get("nodes");
        assertThat(nodes2).extracting(n -> n.get("__typename"))
            .as("Beacon page 2 should be the two Notes after the two Events")
            .containsExactly("ProjectNote", "ProjectNote");
        assertThat(((Map<String, Object>) page2Conn.get("pageInfo")).get("hasNextPage")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectItemsConnection_inlineFragmentResolvesPerType() {
        // Inline fragments dispatch per concrete type so ProjectNote rows expose noteId+body and
        // ProjectEvent rows expose eventId+summary. The TypeResolver routes via __typename on
        // each typed Record.
        Map<String, Object> data = execute("""
            { projects {
                name orgId projectId
                items {
                    nodes {
                        __typename
                        ... on ProjectNote { noteId body }
                        ... on ProjectEvent { eventId summary }
                    }
                }
            } }
            """);
        var projects = (List<Map<String, Object>>) data.get("projects");
        var atlas = projects.stream().filter(p -> "Atlas".equals(p.get("name"))).findFirst().orElseThrow();
        var atlasItems = (List<Map<String, Object>>) ((Map<String, Object>) atlas.get("items")).get("nodes");
        var noteRows = atlasItems.stream().filter(n -> "ProjectNote".equals(n.get("__typename"))).toList();
        var eventRows = atlasItems.stream().filter(n -> "ProjectEvent".equals(n.get("__typename"))).toList();
        assertThat(noteRows).hasSize(3);
        assertThat(eventRows).hasSize(1);
        assertThat(noteRows).allSatisfy(n -> {
            assertThat(n.get("noteId")).as("ProjectNote.noteId").isNotNull();
            assertThat((String) n.get("body")).as("ProjectNote.body").startsWith("Atlas-N");
        });
        assertThat(eventRows).allSatisfy(e -> {
            assertThat(e.get("eventId")).as("ProjectEvent.eventId").isNotNull();
            assertThat((String) e.get("summary")).as("ProjectEvent.summary").isEqualTo("Atlas-E1");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectItemsConnection_dataLoaderBatchesAcrossParents() {
        // R36 Track B4c-2 batching ratchet for composite-PK parents: with 3 parents (Atlas,
        // Beacon, Cipher) fetching items in one request, the DataLoader collapses the per-branch
        // UNION-ALL page-rows query to ONE invocation. parentInput VALUES is keyed Row3<Integer,
        // Integer, Integer> (idx + composite parent PK) and the per-branch JOIN ON emits an
        // AND-chain over (org_id, project_id). Without B4c-2's RowN widening this would have
        // failed to compile; without B4c-2's batching this would fire 3 separate UNION-ALLs.
        SQL_LOG.clear();
        Map<String, Object> data = execute("""
            { projects {
                name orgId projectId
                items(first: 1) {
                    edges { node { __typename } }
                }
            } }
            """);
        var projects = (List<Map<String, Object>>) data.get("projects");
        assertThat(projects).hasSize(3);
        // The page-rows rows-method fires once across all 3 parents.
        var parentInputQueries = SQL_LOG.stream()
            .filter(s -> s.contains("as \"parentinput\""))
            .toList();
        assertThat(parentInputQueries)
            .as("rows-method fires once for the composite-PK-parent batch; SQL_LOG: " + parentInputQueries)
            .hasSize(1);
        // The composite FK AND-chain shows up as two equality conjuncts on the FK columns.
        assertThat(parentInputQueries.get(0))
            .as("composite-FK JOIN predicate joins on both org_id and project_id")
            .contains("\"org_id\"")
            .contains("\"project_id\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void addressOccupantsConnection_inlineFragmentResolvesPerType() {
        // Connection-path TypeResolver routing: inline fragments dispatch per concrete type so
        // Customer rows expose customerId+firstName and Staff rows expose staffId+firstName.
        Map<String, Object> data = execute("""
            { customerById(customer_id: ["3"], store_id: "2") {
                address { occupantsConnection {
                    nodes {
                        __typename
                        ... on Customer { customerId firstName }
                        ... on Staff { staffId firstName }
                    }
                } }
            } }
            """);
        var address = (Map<String, Object>) ((List<Map<String, Object>>) data.get("customerById"))
            .get(0).get("address");
        var conn = (Map<String, Object>) address.get("occupantsConnection");
        var nodes = (List<Map<String, Object>>) conn.get("nodes");
        var customerRow = nodes.stream().filter(n -> "Customer".equals(n.get("__typename"))).findFirst().orElseThrow();
        var staffRow = nodes.stream().filter(n -> "Staff".equals(n.get("__typename"))).findFirst().orElseThrow();
        assertThat(customerRow.get("customerId")).isNotNull();
        assertThat(customerRow.get("firstName")).isEqualTo("Linda");
        assertThat(staffRow.get("staffId")).isNotNull();
        assertThat(staffRow.get("firstName")).isEqualTo("Mike");
    }

    // ===== R22 Phase 2: INSERT emitter (DML mutation, TableBoundReturnType) =====

    @Test
    @SuppressWarnings("unchecked")
    void createFilm_insertsRowAndReturnsProjectedFilm() {
        // INSERT mutation with @table return: emitter runs
        // `dsl.insertInto(film, title, language_id).values(...).returningResult($fields).fetchOne(r -> r)`,
        // and graphql-java walks the returned Record for the selected columns. This is the
        // first execution-tier coverage for R22's RETURNING-with-multiset shape on PostgreSQL —
        // DELETE shipped without one. The test cleans up its inserted row in a finally block
        // so other tests' film-count assumptions are preserved.
        String marker = "R22-PHASE-2-FILM-" + java.util.UUID.randomUUID();
        int countBefore = dsl.fetchCount(org.jooq.impl.DSL.table("film"));
        try {
            Map<String, Object> data = execute("""
                mutation {
                    createFilm(in: { title: "%s", languageId: 1 }) {
                        title
                        languageId
                        rentalRate
                    }
                }
                """.formatted(marker));

            Map<String, Object> created = (Map<String, Object>) data.get("createFilm");
            assertThat(created).containsEntry("title", marker);
            assertThat(created).containsEntry("languageId", 1);
            // rental_rate has a DB-side default of 4.99 that flows back through the RETURNING
            // projection, even though the input does not supply it.
            assertThat(((Number) created.get("rentalRate")).doubleValue()).isEqualTo(4.99);

            int countAfter = dsl.fetchCount(org.jooq.impl.DSL.table("film"));
            assertThat(countAfter)
                .as("createFilm inserted exactly one row")
                .isEqualTo(countBefore + 1);
        } finally {
            dsl.deleteFrom(org.jooq.impl.DSL.table("film"))
                .where(org.jooq.impl.DSL.field("title").eq(marker))
                .execute();
        }
    }

    @Test
    void assignFilmRecord_decodesNodeIdIntoJooqRecordMember() {
        // R195: a @service input bean whose member is a jOOQ FilmRecord backed by
        // `ID! @nodeId(typeName: "Film")`. The fetcher decodes the wire id into a FilmRecord via
        // decodeFilmRecord (NodeIdEncoder.decodeValues + record.fromArray) instead of casting the
        // wire String to FilmRecord; the service reads the populated film_id back. Round-trips the
        // decode-and-materialize end-to-end against PostgreSQL.
        String filmId3 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 3);
        Map<String, Object> data = execute(
            "mutation { assignFilmRecord(in: {film: \"" + filmId3 + "\"}) }");
        assertThat(data).extractingByKey("assignFilmRecord").isEqualTo("film:3");
    }

    @Test
    void assignFilmRecord_wrongTypeNodeId_throwsDecodeMismatch() {
        // R195 runtime decode-mismatch: a NodeId whose embedded type id is not "Film" (here a
        // FilmActor id) fails the decodeValues("Film", …) type check, so decodeFilmRecord throws
        // instead of materialising a wrong record. The fetcher's catch arm surfaces it as an error
        // (the message is redacted to a UUID reference, so we assert the failure, not its text) and
        // the mutation returns no value. Pins the throw-on-mismatch contract behaviourally at the
        // execution tier; the pipeline tier asserts the decode-record leaf structurally instead of
        // inspecting the generated body.
        String wrongTypeId = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 1, 2);
        graphql.ExecutionResult result = executeRaw(
            "mutation { assignFilmRecord(in: {film: \"" + wrongTypeId + "\"}) }");
        assertThat(result.getErrors())
            .as("a wrong-type NodeId in a jOOQ-record member is an authored-input error, not a silent decode")
            .isNotEmpty();
        // R378 privacy-contract proof: the record-decode path throws a plain GraphqlErrorException,
        // NOT the GraphitronClientException marker, so surfaceClientErrorOrRedact must NOT surface
        // it — it falls through to redact (correlation id only), leaking no internal detail. This
        // pins that the surface arm narrows to the client-error type rather than leaking arbitrary
        // exceptions.
        assertThat(result.getErrors().get(0).getMessage())
            .contains("An error occurred. Reference:")
            .doesNotContain(wrongTypeId);
        Map<String, Object> data = result.getData();
        assertThat(data.get("assignFilmRecord"))
            .as("the mutation does not succeed with a wrong-type NodeId")
            .isNull();
    }

    @Test
    void assignFilmActorRecord_decodesCompositeNodeIdIntoBothKeyColumns() {
        // R195 composite key: a @service input bean whose member is a composite-PK FilmActorRecord
        // backed by `ID! @nodeId(typeName: "FilmActor")`. decodeFilmActorRecord materialises both
        // key columns (actor_id, film_id) in one fromArray call; the service reads both back.
        // Proves fromArray fills every key column positionally, round-tripping against PostgreSQL.
        String filmActorId = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 1, 2);
        Map<String, Object> data = execute(
            "mutation { assignFilmActorRecord(in: {filmActor: \"" + filmActorId + "\"}) }");
        assertThat(data).extractingByKey("assignFilmActorRecord").isEqualTo("filmActor:1:2");
    }

    @Test
    void assignFilmRecordList_decodesListOfNodeIdsIntoListOfRecords() {
        // R195 list member: a @service input bean whose member is List<FilmRecord> backed by
        // `[ID!] @nodeId(typeName: "Film")`. decodeFilmRecordList materialises one FilmRecord per
        // wire id; the service reads each film_id back. Round-trips against PostgreSQL.
        String filmId3 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 3);
        String filmId5 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 5);
        Map<String, Object> data = execute(
            "mutation { assignFilmRecordList(in: {films: [\"" + filmId3 + "\", \"" + filmId5 + "\"]}) }");
        assertThat(data).extractingByKey("assignFilmRecordList").isEqualTo("films:3,5");
    }

    @Test
    void assignFilmActorRecordList_decodesListOfCompositeNodeIds() {
        // R195 both-dimensions corner: a List<FilmActorRecord> member backed by
        // `[ID!] @nodeId(typeName: "FilmActor")`. decodeFilmActorRecordList wraps the composite-key
        // per-element decode; the service reads each element's (actor_id, film_id) back.
        String fa12 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 1, 2);
        String fa24 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 2, 4);
        Map<String, Object> data = execute(
            "mutation { assignFilmActorRecordList(in: {filmActors: [\"" + fa12 + "\", \"" + fa24 + "\"]}) }");
        assertThat(data).extractingByKey("assignFilmActorRecordList").isEqualTo("filmActors:1:2,2:4");
    }

    // ===== R311: a jOOQ TableRecord bound directly as a @service input param =====

    @Test
    void modifyFilmRecord_decodesNodeIdIdentityAndSetsColumnsOnRecordParam() {
        // R311 root singular: the @service param IS a jOOQ FilmRecord (not a bean member).
        // createFilmRecord decodes the `filmId` @nodeId into film_id (NodeIdEncoder.decodeValues +
        // fromArray) AND fromArray-loads the @field columns `title`/`release_year` from the wire Map.
        // The service reads all three back — the identity decode and the column SET land together.
        // Round-trips the column-axis + scalar-key construction end-to-end against PostgreSQL.
        String filmId3 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 3);
        Map<String, Object> data = execute(
            "mutation { modifyFilmRecord(in: {filmId: \"" + filmId3
            + "\", title: \"Reissued\", releaseYear: 2021}) }");
        assertThat(data).extractingByKey("modifyFilmRecord").isEqualTo("film:3:title=Reissued:year=2021");
    }

    @Test
    void modifyFilmRecord_wrongTypeNodeId_throwsDecodeMismatch() {
        // R311 lifted R195 throw-on-mismatch: a NodeId whose embedded type id is not "Film" (here a
        // FilmActor id) fails the decodeValues("Film", …) type check inside createFilmRecord, so the
        // record's identity decode throws rather than materialising a wrong record. The mutation
        // returns no value.
        String wrongTypeId = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 1, 2);
        graphql.ExecutionResult result = executeRaw(
            "mutation { modifyFilmRecord(in: {filmId: \"" + wrongTypeId + "\", title: \"X\"}) }");
        assertThat(result.getErrors())
            .as("a wrong-type NodeId at a jOOQ-record param's identity is an authored-input error")
            .isNotEmpty();
        Map<String, Object> data = result.getData();
        assertThat(data.get("modifyFilmRecord"))
            .as("the mutation does not succeed with a wrong-type NodeId")
            .isNull();
    }

    @Test
    void modifyFilmRecords_listParam_constructsOneRecordPerElement() {
        // R311 root list (the consumer's motivating shape): a List<FilmRecord> @service param against
        // [ModifyFilmRecordInput!]!. createFilmRecordList maps the singular createFilmRecord over each
        // element — two distinct Film NodeIds decode to two constructed records, each carrying its set
        // title. Proves the one shared construction site serves both cardinalities.
        String filmId3 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 3);
        String filmId5 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 5);
        Map<String, Object> data = execute(
            "mutation { modifyFilmRecords(in: ["
            + "{filmId: \"" + filmId3 + "\", title: \"A\"}, "
            + "{filmId: \"" + filmId5 + "\", title: \"B\"}]) }");
        assertThat(data).extractingByKey("modifyFilmRecords").isEqualTo("films:3@A,5@B");
    }

    @Test
    void modifyFilmActorRecord_decodesCompositeIdentityIntoBothKeyColumns() {
        // R311 composite-key root: createFilmActorRecord materialises a composite-PK FilmActorRecord
        // (actor_id, film_id) in one fromArray call from a single FilmActor NodeId. The service reads
        // both key columns back.
        String filmActorId = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 1, 2);
        Map<String, Object> data = execute(
            "mutation { modifyFilmActorRecord(in: {id: \"" + filmActorId + "\"}) }");
        assertThat(data).extractingByKey("modifyFilmActorRecord").isEqualTo("filmActor:1:2");
    }

    // ===== R315: FK-reference @nodeId on a jOOQ-record @service param (cross-table) =====

    @Test
    void endorseFilm_fkReferenceLandsOnRenamedChildColumn() {
        // R315 end-to-end: createFilmEndorsementRecord decodes the FK-reference `filmId` @nodeId
        // (typeName "Film") and — through the film_endorsement → film FK — loads it onto the renamed
        // child column endorsed_film (NOT a same-named film_id). The serial PK endorsement_id is never
        // in the input, so it stays unset (changed=false) and the service-owned INSERT lets the database
        // assign it. The service reads endorsed_film back from the persisted row: the decoded Film id (3)
        // landed on the FK child column.
        String filmId3 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 3);
        Map<String, Object> data = execute(
            "mutation { endorseFilm(in: {filmId: \"" + filmId3 + "\", note: \"great\"}) }");
        assertThat(data).extractingByKey("endorseFilm").isEqualTo("endorsed_film=3");
    }

    @Test
    void describeEndorsement_omittedKeys_leaveColumnsUnwritten() {
        // R315 null-semantics (D4): an omitted nullable FK reference and an omitted nullable plain column
        // are not loaded — changed=false — so they would be excluded from the INSERT/UPDATE. The only
        // tier that can observe changed=false exclusion.
        Map<String, Object> data = execute("mutation { describeEndorsement(in: {}) }");
        assertThat(data).extractingByKey("describeEndorsement")
            .isEqualTo("endorsedFilm[changed=false,val=null] note[changed=false,val=null]");
    }

    @Test
    void describeEndorsement_explicitNull_writesNull() {
        // R315 null-semantics (D4): an explicit null on the nullable FK reference and the nullable plain
        // column is present (changed=true) and loaded as NULL — distinct from omitted.
        Map<String, Object> data = execute(
            "mutation { describeEndorsement(in: {filmId: null, note: null}) }");
        assertThat(data).extractingByKey("describeEndorsement")
            .isEqualTo("endorsedFilm[changed=true,val=null] note[changed=true,val=null]");
    }

    @Test
    void describeEndorsement_setValues_decodeAndLoad() {
        // R315 null-semantics (D4): a present nullable FK reference decodes-and-loads onto the FK child
        // column (endorsed_film = the decoded Film id), and a present plain value loads — the most direct
        // pin of the generalization that nullable FK-reference decodes follow the same conditional-load
        // path as plain columns.
        String filmId5 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 5);
        Map<String, Object> data = execute(
            "mutation { describeEndorsement(in: {filmId: \"" + filmId5 + "\", note: \"set\"}) }");
        assertThat(data).extractingByKey("describeEndorsement")
            .isEqualTo("endorsedFilm[changed=true,val=5] note[changed=true,val=set]");
    }

    @Test
    void upsertAddress_omittedSameTableIdentity_serviceInsertAssignsPk() {
        // R315 D4 execution pin (nullable same-table identity, omitted): addressId @nodeId(typeName:
        // "Address") resolves to AddressRecord's own PK (address_id). Omitting it leaves address_id unset
        // (changed=false) — under R311 a same-table identity always threw on null, so this is the exact
        // throw→skip behavior change D4 folds in. The @service owns the INSERT; the database assigns the
        // serial PK, which jOOQ refreshes back. The only tier that can observe the changed=false → unset
        // PK → DB-assigned outcome.
        Map<String, Object> data = execute("mutation { upsertAddress(in: {}) }");
        assertThat(data).extractingByKey("upsertAddress").isEqualTo("omitted: pkAssignedByDb=true");
    }

    @Test
    void upsertAddress_setSameTableIdentity_decodeLandsOnPk() {
        // R315 D4 execution pin (nullable same-table identity, set): a present addressId decodes and
        // lands on the record's own PK address_id (the update path) — the same conditional-load path the
        // nullable FK reference and plain column take, here for the record's own identity.
        String addressId2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Address", 2);
        Map<String, Object> data = execute(
            "mutation { upsertAddress(in: {addressId: \"" + addressId2 + "\"}) }");
        assertThat(data).extractingByKey("upsertAddress").isEqualTo("set: pk=2");
    }

    // ===== R336: nested input-object fields flatten onto the param record's column axis =====

    @Test
    void customerUpsert_nestedLeafSet_landsOnColumn_omittedSiblingUntouched() {
        // R336 transparent unpack: details.firstName is present and set (lands on first_name, changed=true),
        // details.lastName is omitted within the same group (changed=false, untouched — the partial-update
        // contract). The nested identity.customerId decodes into customer_id. Proves a nested leaf binds
        // exactly as a top-level one would, with the value landing on the right column.
        String customerId1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        Map<String, Object> data = execute(
            "mutation { customerUpsert(in: {identity: {customerId: \"" + customerId1
            + "\"}, details: {firstName: \"Ada\"}}) }");
        assertThat(data).extractingByKey("customerUpsert")
            .isEqualTo("customerId[changed=true,val=1] first[changed=true,val=Ada] last[changed=false,val=null]");
    }

    @Test
    void customerUpsert_explicitNullNestedLeaf_collapsesToOmitted() {
        // R336 graphql-java constraint on nested present-null: unlike a TOP-LEVEL field (where an explicit
        // null is retained and writes NULL — see describeEndorsement_explicitNull_writesNull), graphql-java
        // coercion DROPS an explicit-null field from a NESTED input-object value. The key never reaches the
        // helper's Map, so containsKey is false and the column is left untouched (changed=false), exactly as
        // if the leaf had been omitted. Verified here through the variable path (the production wire shape),
        // so this is a real coercion limitation, not an inline-literal artifact: the present-null signal is
        // destroyed before any generated code runs. The top-level three-way thus narrows to a nested two-way
        // (omitted / explicit-null → untouched; value → set), consistent with the spec's own rule that a null
        // nested *group* is treated as absent — here extended to the leaf.
        String customerId1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        var details = new java.util.HashMap<String, Object>();
        details.put("firstName", null);
        var in = java.util.Map.<String, Object>of(
            "identity", java.util.Map.of("customerId", customerId1),
            "details", details);
        Map<String, Object> data = execute(
            "mutation($in: CustomerUpsertInput!) { customerUpsert(in: $in) }",
            java.util.Map.of("in", in));
        assertThat(data).extractingByKey("customerUpsert")
            .isEqualTo("customerId[changed=true,val=1] first[changed=false,val=null] last[changed=false,val=null]");
    }

    @Test
    void customerUpsert_nullNestedGroup_leavesEveryColumnUnderItUntouched() {
        // R336: a null nullable `details` group is treated identically to absent — every column under it
        // stays changed=false. The present identity group still decodes customer_id.
        String customerId2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 2);
        Map<String, Object> data = execute(
            "mutation { customerUpsert(in: {identity: {customerId: \"" + customerId2 + "\"}, details: null}) }");
        assertThat(data).extractingByKey("customerUpsert")
            .isEqualTo("customerId[changed=true,val=2] first[changed=false,val=null] last[changed=false,val=null]");
    }

    @Test
    void customerUpsert_omittedNullableIdentityGroup_skipsNonNullIdentity_noThrow() {
        // R336 skip-not-throw: the @nodeId identity (customer_id) is non-null (ID!) but lives inside a
        // NULLABLE `identity` group. Omitting the group skips the identity entirely (changed=false) with NO
        // decode error raised — graphql-java never coerces the absent group, so its non-null field is never
        // required, and the generated helper's R195 throw lives in a descent block that is never entered.
        // The nested details leaf still binds.
        Map<String, Object> data = execute(
            "mutation { customerUpsert(in: {details: {firstName: \"Grace\"}}) }");
        assertThat(data).extractingByKey("customerUpsert")
            .isEqualTo("customerId[changed=false,val=null] first[changed=true,val=Grace] last[changed=false,val=null]");
    }

    @Test
    void customerUpsert_emptyInput_everythingUntouched_noThrow() {
        // R336: both nullable groups omitted (in: {}) — customer_id skipped (skip-not-throw), all columns
        // untouched. The most direct proof that an absent group descends into nothing and raises nothing.
        Map<String, Object> data = execute("mutation { customerUpsert(in: {}) }");
        assertThat(data).extractingByKey("customerUpsert")
            .isEqualTo("customerId[changed=false,val=null] first[changed=false,val=null] last[changed=false,val=null]");
    }

    @Test
    void customerUpsert_malformedIdInPresentIdentityGroup_throwsDecodeMismatch() {
        // R336: skip-not-throw is about an ABSENT group, not a free pass. When the identity group IS present,
        // a wrong-type NodeId (a Film id, not a Customer id) fails the decodeValues("Customer", …) type check
        // inside the entered descent block and throws (R195). The mutation returns no value.
        String filmId = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 1);
        graphql.ExecutionResult result = executeRaw(
            "mutation { customerUpsert(in: {identity: {customerId: \"" + filmId + "\"}}) }");
        assertThat(result.getErrors())
            .as("a wrong-type NodeId in a present identity group is an authored-input error")
            .isNotEmpty();
        Map<String, Object> data = result.getData();
        assertThat(data.get("customerUpsert"))
            .as("the mutation does not succeed with a wrong-type NodeId").isNull();
    }

    // ===== R77 Phase B: missing-vs-null on single-row INSERT (containsKey-gated DEFAULT) =====

    @Test
    @SuppressWarnings("unchecked")
    void createFilm_omittedFieldUsesColumnDefault() {
        // R77 Phase B: omitted columns now bind DSL.defaultValue(...) per cell instead of typed
        // null. Sakila's `rental_duration` is `smallint NOT NULL DEFAULT 3`; omitting it from
        // the input lets the DB default land. Pre-R77, the emitter wrote typed null and
        // surfaced a NOT-NULL constraint violation.
        String marker = "R77-PHASE-B-OMIT-" + java.util.UUID.randomUUID();
        try {
            Map<String, Object> data = execute("""
                mutation {
                    createFilm(in: { title: "%s", languageId: 1 }) {
                        filmId
                        rentalDuration
                    }
                }
                """.formatted(marker));

            Map<String, Object> created = (Map<String, Object>) data.get("createFilm");
            assertThat(((Number) created.get("rentalDuration")).intValue()).isEqualTo(3);
        } finally {
            dsl.deleteFrom(org.jooq.impl.DSL.table("film"))
                .where(org.jooq.impl.DSL.field("title").eq(marker))
                .execute();
        }
    }

    @Test
    void createFilm_explicitNullRaisesError() {
        // R77 Phase B: explicit null is preserved by Map.containsKey == true and binds typed
        // null via DSL.val(null, dataType). On `rental_duration` (NOT NULL no default) this
        // surfaces an IntegrityConstraintViolationException; the fetcher's try/catch routes
        // through ErrorRouter.redact (no error channel on createFilm), producing a single
        // redacted error and null data. Locks in the explicit-null branch the missing-vs-null
        // section explicitly accommodates.
        String marker = "R77-PHASE-B-NULL-" + java.util.UUID.randomUUID();
        graphql.ExecutionResult result = executeRaw("""
            mutation {
                createFilm(in: { title: "%s", languageId: 1, rentalDuration: null }) {
                    filmId
                }
            }
            """.formatted(marker));
        assertThat(result.getErrors()).isNotEmpty();
        // Defensive cleanup in case the ICV did not abort the statement (it should have).
        dsl.deleteFrom(org.jooq.impl.DSL.table("film"))
            .where(org.jooq.impl.DSL.field("title").eq(marker))
            .execute();
    }

    // ===== R22 Phase 4: UPDATE emitter (DML mutation, TableBoundReturnType) =====

    @Test
    @SuppressWarnings("unchecked")
    void updateFilm_updatesRowAndReturnsProjectedFilm() {
        // UPDATE mutation with @table return: emitter runs
        // `dsl.update(film).set(title, val).where(film_id.eq(val)).returningResult($fields)
        //  .fetchOne(r -> r)`. Verifies the SET clause writes, the WHERE clause matches, and the
        // RETURNING projection flows back through graphql-java for the selected columns.
        // Pre-inserts a marker row by jOOQ so the test does not depend on (or mutate) any
        // pre-existing Sakila row, and cleans up in finally.
        String originalMarker = "R22-PHASE-4-FILM-" + java.util.UUID.randomUUID();
        String updatedMarker  = "R22-PHASE-4-FILM-UPDATED-" + java.util.UUID.randomUUID();
        var filmTable = org.jooq.impl.DSL.table("film");
        var filmIdCol = org.jooq.impl.DSL.field("film_id", Integer.class);
        Integer filmId = dsl.insertInto(filmTable)
            .set(org.jooq.impl.DSL.field("title"), originalMarker)
            .set(org.jooq.impl.DSL.field("language_id"), (short) 1)
            .returningResult(filmIdCol)
            .fetchOne()
            .value1();
        try {
            Map<String, Object> data = execute("""
                mutation {
                    updateFilm(in: { filmId: %d, title: "%s" }) {
                        title
                        languageId
                    }
                }
                """.formatted(filmId, updatedMarker));

            Map<String, Object> updated = (Map<String, Object>) data.get("updateFilm");
            assertThat(updated).containsEntry("title", updatedMarker);
            // languageId was not in the SET clause, so it carries through unchanged.
            assertThat(updated).containsEntry("languageId", 1);

            // Re-read by PK to confirm the SET clause actually wrote, not just that RETURNING
            // returned the projected shape.
            String dbTitle = dsl.select(org.jooq.impl.DSL.field("title", String.class))
                .from(filmTable).where(filmIdCol.eq(filmId)).fetchOne().value1();
            assertThat(dbTitle).isEqualTo(updatedMarker);
        } finally {
            dsl.deleteFrom(filmTable).where(filmIdCol.eq(filmId)).execute();
        }
    }

    // ===== R22 Phase 5: UPSERT emitter (DML mutation, TableBoundReturnType) =====

    @Test
    @SuppressWarnings("unchecked")
    @org.junit.jupiter.api.Disabled("R144 retires UPSERT generation pending R145 (mutation-cardinality-safety-upsert); the upsertFilm fixture no longer exists.")
    void upsertFilm_updateBranch_writesAndReturnsProjectedFilm() {
        // UPSERT-on-existing-row: emitter runs `dsl.insertInto(film, ...).values(...)
        // .onConflict(film_id).doUpdate().set(title, ...).set(language_id, ...)
        // .returningResult($fields).fetchOne(r -> r)`. The pre-inserted row triggers the
        // ON CONFLICT branch; verifies the SET clause writes and RETURNING projects.
        String originalMarker = "R22-PHASE-5-UPSERT-" + java.util.UUID.randomUUID();
        String upsertedMarker = "R22-PHASE-5-UPSERTED-" + java.util.UUID.randomUUID();
        var filmTable = org.jooq.impl.DSL.table("film");
        var filmIdCol = org.jooq.impl.DSL.field("film_id", Integer.class);
        Integer filmId = dsl.insertInto(filmTable)
            .set(org.jooq.impl.DSL.field("title"), originalMarker)
            .set(org.jooq.impl.DSL.field("language_id"), (short) 1)
            .returningResult(filmIdCol)
            .fetchOne()
            .value1();
        try {
            Map<String, Object> data = execute("""
                mutation {
                    upsertFilm(in: { filmId: %d, title: "%s", languageId: 1 }) {
                        title
                        languageId
                    }
                }
                """.formatted(filmId, upsertedMarker));

            Map<String, Object> upserted = (Map<String, Object>) data.get("upsertFilm");
            assertThat(upserted).containsEntry("title", upsertedMarker);
            assertThat(upserted).containsEntry("languageId", 1);

            String dbTitle = dsl.select(org.jooq.impl.DSL.field("title", String.class))
                .from(filmTable).where(filmIdCol.eq(filmId)).fetchOne().value1();
            assertThat(dbTitle).isEqualTo(upsertedMarker);
        } finally {
            dsl.deleteFrom(filmTable).where(filmIdCol.eq(filmId)).execute();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    @org.junit.jupiter.api.Disabled("R144 retires UPSERT generation pending R145.")
    void upsertFilm_insertBranch_writesAndReturnsProjectedFilm() {
        // UPSERT-on-novel-row: the supplied filmId does not exist, so the INSERT branch fires.
        // Pick an id well above the Sakila max (~1000) to avoid collisions with other tests.
        // We can't simply omit filmId because the schema marks it non-null; the INSERT branch
        // therefore exercises the user-supplied-PK shape, which is the canonical UPSERT case.
        int filmId = 999_001;
        String marker = "R22-PHASE-5-INSERT-BRANCH-" + java.util.UUID.randomUUID();
        var filmTable = org.jooq.impl.DSL.table("film");
        var filmIdCol = org.jooq.impl.DSL.field("film_id", Integer.class);
        // Pre-clean in case a prior failed run left the row behind.
        dsl.deleteFrom(filmTable).where(filmIdCol.eq(filmId)).execute();
        try {
            Map<String, Object> data = execute("""
                mutation {
                    upsertFilm(in: { filmId: %d, title: "%s", languageId: 1 }) {
                        title
                        languageId
                    }
                }
                """.formatted(filmId, marker));

            Map<String, Object> upserted = (Map<String, Object>) data.get("upsertFilm");
            assertThat(upserted).containsEntry("title", marker);
            assertThat(upserted).containsEntry("languageId", 1);

            String dbTitle = dsl.select(org.jooq.impl.DSL.field("title", String.class))
                .from(filmTable).where(filmIdCol.eq(filmId)).fetchOne().value1();
            assertThat(dbTitle).isEqualTo(marker);
        } finally {
            dsl.deleteFrom(filmTable).where(filmIdCol.eq(filmId)).execute();
        }
    }

    // ===== R77 Phase C: missing-vs-null on single-row UPDATE / UPSERT-update-branch =====

    @Test
    @SuppressWarnings("unchecked")
    void updateFilm_omittedFieldLeavesColumnAlone_explicitNullWritesNull() {
        // R77 Phase C: dynamic SET. The runtime SET clause is built from `in.keySet()` so
        // omitted fields drop out of the UPDATE entirely (PATCH semantics; preserves the
        // existing row's value), while explicit-null fields bind typed null and write SQL
        // NULL to the column. Pre-R77 the emitter wrote typed null on every setFields()
        // entry regardless of whether the input map carried the key, silently nulling out
        // omitted columns.
        String originalTitle       = "R77-PHASE-C-FILM-" + java.util.UUID.randomUUID();
        String originalDescription = "R77-PHASE-C-DESC-" + java.util.UUID.randomUUID();
        String updatedTitle        = "R77-PHASE-C-FILM-UPDATED-" + java.util.UUID.randomUUID();
        var filmTable = org.jooq.impl.DSL.table("film");
        var filmIdCol = org.jooq.impl.DSL.field("film_id", Integer.class);
        var titleCol = org.jooq.impl.DSL.field("title", String.class);
        var descCol = org.jooq.impl.DSL.field("description", String.class);
        Integer filmId = dsl.insertInto(filmTable)
            .set(titleCol, originalTitle)
            .set(descCol, originalDescription)
            .set(org.jooq.impl.DSL.field("language_id"), (short) 1)
            .returningResult(filmIdCol)
            .fetchOne()
            .value1();
        try {
            // Step 1: omit `description`. Only `title` is in the SET clause; description
            // survives untouched.
            execute("""
                mutation {
                    updateFilm(in: { filmId: %d, title: "%s" }) { filmId }
                }
                """.formatted(filmId, updatedTitle));
            String dbDesc = dsl.select(descCol).from(filmTable).where(filmIdCol.eq(filmId)).fetchOne().value1();
            assertThat(dbDesc).as("omitted description preserved").isEqualTo(originalDescription);
            String dbTitle = dsl.select(titleCol).from(filmTable).where(filmIdCol.eq(filmId)).fetchOne().value1();
            assertThat(dbTitle).as("present title written").isEqualTo(updatedTitle);

            // Step 2: explicit null on `description`. The key is present, the value is null;
            // typed null binds and the UPDATE writes SQL NULL to the column.
            execute("""
                mutation {
                    updateFilm(in: { filmId: %d, title: "%s", description: null }) { filmId }
                }
                """.formatted(filmId, updatedTitle));
            String dbDesc2 = dsl.select(descCol).from(filmTable).where(filmIdCol.eq(filmId)).fetchOne().value1();
            assertThat(dbDesc2).as("explicit-null description written as SQL NULL").isNull();
        } finally {
            dsl.deleteFrom(filmTable).where(filmIdCol.eq(filmId)).execute();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    @org.junit.jupiter.api.Disabled("R144 retires UPSERT generation pending R145.")
    void upsertFilm_omittedFieldOnUpdateBranchLeavesColumnAlone() {
        // R77 Phase C: UPSERT update-branch shares UPDATE's dynamic SET. When the conflict
        // branch fires, the SET map walks `in`'s present-key set with `DSL.excluded(col)` as
        // the value. An omitted column drops out of `DO UPDATE SET` entirely (PATCH semantics
        // on the update branch), so the existing row's value survives the upsert. Without
        // dynamic SET, a naive `c = EXCLUDED.c` for every setFields() column would resolve
        // EXCLUDED.c to the table default whenever the proposed INSERT row used DEFAULT,
        // overwriting the existing value with the default — silent data loss.
        //
        // Sakila's `rental_duration` is `smallint NOT NULL DEFAULT 3`. We pre-insert the row
        // with `rental_duration = 7`, then upsert *without* `rentalDuration` in the input.
        // The conflict fires; the dynamic SET map contains only the supplied keys (title,
        // languageId), so rental_duration drops out of DO UPDATE SET and stays at 7. Pre-R77
        // it would be overwritten with EXCLUDED.rental_duration which (because the INSERT
        // branch's value cell was `DEFAULT`) resolves to 3.
        String originalTitle = "R77-PHASE-C-UPSERT-" + java.util.UUID.randomUUID();
        String upsertedTitle = "R77-PHASE-C-UPSERTED-" + java.util.UUID.randomUUID();
        var filmTable = org.jooq.impl.DSL.table("film");
        var filmIdCol = org.jooq.impl.DSL.field("film_id", Integer.class);
        var rentalCol = org.jooq.impl.DSL.field("rental_duration", Integer.class);
        Integer filmId = dsl.insertInto(filmTable)
            .set(org.jooq.impl.DSL.field("title"), originalTitle)
            .set(org.jooq.impl.DSL.field("language_id"), (short) 1)
            .set(rentalCol, 7)
            .returningResult(filmIdCol)
            .fetchOne()
            .value1();
        try {
            execute("""
                mutation {
                    upsertFilm(in: { filmId: %d, title: "%s", languageId: 1 }) { filmId }
                }
                """.formatted(filmId, upsertedTitle));
            Integer dbRental = dsl.select(rentalCol).from(filmTable).where(filmIdCol.eq(filmId)).fetchOne().value1();
            assertThat(dbRental).as("omitted rentalDuration preserved on upsert update branch").isEqualTo(7);
            String dbTitle = dsl.select(org.jooq.impl.DSL.field("title", String.class))
                .from(filmTable).where(filmIdCol.eq(filmId)).fetchOne().value1();
            assertThat(dbTitle).as("present title written on upsert update branch").isEqualTo(upsertedTitle);
        } finally {
            dsl.deleteFrom(filmTable).where(filmIdCol.eq(filmId)).execute();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    @org.junit.jupiter.api.Disabled("R144 retires UPSERT generation pending R145.")
    void upsertFilm_omittedFieldOnInsertBranchUsesColumnDefault() {
        // R77 Phase B + C: when the upsert hits the INSERT branch (no conflict), per-cell
        // containsKey-gated DEFAULT applies. Sakila's `rental_duration` is NOT NULL DEFAULT 3.
        // Omitting `rentalDuration` from the input lets DSL.defaultValue(...) bind in the
        // VALUES list, and the DB default lands. Deferred from Phase B (FilmUpsertInput
        // didn't carry rentalDuration until Phase C added it).
        int filmId = 999_002;
        String marker = "R77-PHASE-C-UPSERT-INSERT-BRANCH-" + java.util.UUID.randomUUID();
        var filmTable = org.jooq.impl.DSL.table("film");
        var filmIdCol = org.jooq.impl.DSL.field("film_id", Integer.class);
        var rentalCol = org.jooq.impl.DSL.field("rental_duration", Integer.class);
        // Pre-clean in case a prior failed run left the row behind.
        dsl.deleteFrom(filmTable).where(filmIdCol.eq(filmId)).execute();
        try {
            execute("""
                mutation {
                    upsertFilm(in: { filmId: %d, title: "%s", languageId: 1 }) { filmId }
                }
                """.formatted(filmId, marker));
            Integer dbRental = dsl.select(rentalCol).from(filmTable).where(filmIdCol.eq(filmId)).fetchOne().value1();
            assertThat(dbRental).as("omitted rentalDuration takes column default on upsert insert branch").isEqualTo(3);
        } finally {
            dsl.deleteFrom(filmTable).where(filmIdCol.eq(filmId)).execute();
        }
    }

    // ===== R77 Phase F: no-set-fields-present runtime check, single-row analogues =====

    @Test
    void updateFilm_onlyLookupKeyFields_raisesError() {
        // Single-row analogue of updateFilms_onlyLookupKeyFields_raisesError. The dynamic
        // SET walk gates each cf in tia.setFields() on in.containsKey(name); when the input
        // carries only the @lookupKey field, sets is empty and the no-set-fields-present
        // runtime check throws IllegalArgumentException before any SQL dispatch.
        var filmTable = org.jooq.impl.DSL.table("film");
        var filmIdCol = org.jooq.impl.DSL.field("film_id", Integer.class);
        Integer filmId = dsl.insertInto(filmTable)
            .set(org.jooq.impl.DSL.field("title"), "R77-PHASE-F-UPD-LK-" + java.util.UUID.randomUUID())
            .set(org.jooq.impl.DSL.field("language_id"), (short) 1)
            .returningResult(filmIdCol).fetchOne().value1();
        try {
            graphql.ExecutionResult result = executeRaw(
                "mutation { updateFilm(in: { filmId: " + filmId + " }) { filmId } }");
            assertThat(result.getErrors())
                .as("no-set-fields-present guard surfaces via the routed error envelope")
                .isNotEmpty();
        } finally {
            dsl.deleteFrom(filmTable).where(filmIdCol.eq(filmId)).execute();
        }
    }

    @Test
    @org.junit.jupiter.api.Disabled("R144 retires UPSERT generation pending R145.")
    void upsertFilm_onlyLookupKeyFields_raisesError() {
        // Single-row analogue of upsertFilms_onlyLookupKeyFields_raisesError. FilmUpsertInput
        // has non-lookup setFields (title, languageId, rentalDuration), but the input map
        // carries only filmId — the dynamic doUpdate SET walk gates each setField on
        // in.containsKey(name) and produces an empty setsUpdate map; the no-set-fields-
        // present check throws before any SQL dispatch.
        int filmId = 999_701;
        var filmTable = org.jooq.impl.DSL.table("film");
        var filmIdCol = org.jooq.impl.DSL.field("film_id", Integer.class);
        dsl.deleteFrom(filmTable).where(filmIdCol.eq(filmId)).execute();
        graphql.ExecutionResult result = executeRaw(
            "mutation { upsertFilm(in: { filmId: " + filmId + " }) { filmId } }");
        assertThat(result.getErrors())
            .as("no-set-fields-present guard surfaces via the routed error envelope on UPSERT .doUpdate() mode")
            .isNotEmpty();
    }

    // ===== R12 @error end-to-end (error-handling-parity.md) =====
    //
    // The query-side @error fixture exists for visibility, not for exhaustive coverage of every
    // handler shape. Without an end-to-end driver the @error codepath in
    // GraphitronSchemaClassGenerator (per-@error-type path/message DataFetchers, the union's
    // source-class TypeResolver) is invisible to compile-spec and execute-spec, which is what
    // let an ambiguous-overload + removed-DataFetchingEnvironment.getObject() bug ship to
    // production. The three tests below drive the full pipeline against the real graphql-java
    // engine: the per-fetcher catch arm into ErrorRouter.dispatch, the union's instanceof
    // dispatch ladder into the right @error type, and the synthesized path/message fetchers
    // populating the SDL's [String!]! / String! contract. The happy-path test pins the
    // payload-factory's defaulted slot ('title' = null only on the catch arm; populated on the
    // straight return).
    //
    // The fixture intentionally uses two GENERIC handlers (over a DATABASE/VALIDATION mix). The
    // bug we shipped fired on every @error type the loop emitted, regardless of handler kind;
    // two GENERIC entries exercise the per-@error DataFetcher loop and the union TypeResolver's
    // multi-branch dispatch without dragging in jOOQ's SQLException semantics or the Jakarta
    // validation pre-step (which has its own dependency footprint). DATABASE / VALIDATION end-
    // to-end coverage lands with the rest of R12's "Test fixture updates for source-direct
    // dispatch" Remaining-work bullet, on the four named fixtures (SakPayload, DeleteFilmPayload,
    // and the validator integration).

    @Test
    @SuppressWarnings("unchecked")
    void filmLookup_invalidId_routesThroughInvalidIdErrorType() {
        // id < 0 throws FilmLookupInvalidIdException; ErrorRouter.dispatch matches on the
        // ErrorMappings.FILM_LOOKUP_PAYLOAD ExceptionMapping for that class, places the throwable
        // into the errors list, and constructs new FilmLookupPayload(null, errors). graphql-java
        // then walks the union via the source-class TypeResolver and resolves to FilmLookupInvalid.
        Map<String, Object> data = execute("""
            {
                filmLookup(id: -7) {
                    title
                    errors {
                        __typename
                        ... on FilmLookupInvalid { path message }
                        ... on FilmLookupNotFound { path message }
                    }
                }
            }
            """);
        var payload = assertThat(data).extractingByKey("filmLookup", as(MAP));
        // The catch arm constructs the payload with the title slot defaulted (defaultLiteralFor
        // String == "null"); the dispatch route never produces a non-null title.
        payload.containsEntry("title", null);
        var errors = payload.extractingByKey("errors", as(list(Map.class)));
        errors.hasSize(1);
        var only = errors.element(0, as(MAP));
        only.containsEntry("__typename", "FilmLookupInvalid");
        // Synthesized path DataFetcher routes Throwable sources through
        // env.getExecutionStepInfo().getPath().toList() so the [String!]! contract holds even
        // though Throwable has no getPath() accessor. The path is the SDL field path of the
        // path-field's own resolution (filmLookup → errors → 0 → path); list indices are
        // String::valueOf-coerced so the [String!]! contract holds for graphql-java's mixed
        // String/Integer path elements.
        only.extractingByKey("path", as(LIST))
            .containsExactly("filmLookup", "errors", "0", "path");
        // message DataFetcher routes through Throwable.getMessage().
        only.containsEntry("message", "invalid id: -7");
    }

    @Test
    @SuppressWarnings("unchecked")
    void filmLookup_zeroId_routesThroughNotFoundErrorType() {
        // The second handler in source order: FilmLookupNotFoundException → FilmLookupNotFound.
        // Confirms the TypeResolver's instanceof ladder dispatches to the right branch, and that
        // the per-@error path/message DataFetchers are wired for both @error types (not only the
        // first one in declaration order).
        Map<String, Object> data = execute("""
            {
                filmLookup(id: 0) {
                    title
                    errors {
                        __typename
                        ... on FilmLookupNotFound { path message }
                        ... on FilmLookupInvalid { path message }
                    }
                }
            }
            """);
        var payload = assertThat(data).extractingByKey("filmLookup", as(MAP));
        payload.containsEntry("title", null);
        var only = payload.extractingByKey("errors", as(list(Map.class)))
            .hasSize(1)
            .element(0, as(MAP));
        only.containsEntry("__typename", "FilmLookupNotFound");
        only.containsEntry("message", "film 0 not found");
        only.extractingByKey("path", as(LIST))
            .containsExactly("filmLookup", "errors", "0", "path");
    }

    @Test
    @SuppressWarnings("unchecked")
    void filmLookup_validId_returnsHappyPathPayload() {
        // Happy path: the service returns FilmLookupPayload directly (NoAssembly), no catch arm
        // fires, errors resolves null on the Success arm (R275: null, not [], honouring the
        // nullable errors field). Confirms the per-fetcher try/catch wrapper doesn't perturb
        // non-error returns.
        Map<String, Object> data = execute("""
            { filmLookup(id: 5) { title errors { __typename } } }
            """);
        var payload = assertThat(data).extractingByKey("filmLookup", as(MAP));
        payload.containsEntry("title", "THE LOOKED-UP FILM");
        payload.containsEntry("errors", null);
    }

    // ===== R12 mutation-side @error end-to-end =====
    //
    // Mirrors the query-side filmLookup tests on the mutation pillar. The submitFilmReview
    // mutation classifies as MutationServiceRecordField (the same shape that the production
    // BehandleSakPayload bug fired on) and shares buildServiceFetcherCommon with the query
    // side, but the dispatch arm is wired through MutationFetchers rather than QueryFetchers.
    // Pinning the codepath at execute-tier means a future regression in the mutation switch's
    // try/catch wrapper or the per-fetcher dispatch fork lands as a build failure rather than
    // a production schema break.
    //
    // The fixture intentionally leaves DML mutation @error coverage (insert/update/upsert/delete
    // payload assemblies) to R12's Remaining work; those variants share the channel slot but
    // emit different bodies (the DML payload-assembly arm), so an end-to-end fixture there
    // belongs with the four named source-direct fixtures (SakPayload, DeleteFilmPayload, ...).

    @Test
    @SuppressWarnings("unchecked")
    void submitFilmReview_invalidRating_routesThroughBadRatingErrorType() {
        // rating outside [1,10] throws FilmReviewBadRatingException; ErrorRouter.dispatch
        // matches the ExceptionMapping for that class on ErrorMappings.FILM_REVIEW_PAYLOAD,
        // places the throwable into the errors list, and constructs new FilmReviewPayload(
        // null, errors). graphql-java walks the union via the source-class TypeResolver and
        // resolves to FilmReviewBadRating.
        Map<String, Object> data = execute("""
            mutation {
                submitFilmReview(filmId: 1, rating: 11) {
                    reviewId
                    errors {
                        __typename
                        ... on FilmReviewBadRating { path message }
                        ... on FilmReviewMissingFilm { path message }
                    }
                }
            }
            """);
        var payload = assertThat(data).extractingByKey("submitFilmReview", as(MAP));
        // Catch arm constructs the payload with the reviewId slot defaulted (defaultLiteralFor
        // boxed Integer == "null"); the dispatch route never produces a non-null reviewId.
        payload.containsEntry("reviewId", null);
        var only = payload.extractingByKey("errors", as(list(Map.class)))
            .hasSize(1)
            .element(0, as(MAP));
        only.containsEntry("__typename", "FilmReviewBadRating");
        only.containsEntry("message", "rating must be in [1, 10]; got 11");
        only.extractingByKey("path", as(LIST))
            .containsExactly("submitFilmReview", "errors", "0", "path");
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitFilmReview_unknownFilm_routesThroughMissingFilmErrorType() {
        // Second handler in source order: FilmReviewMissingFilmException → FilmReviewMissingFilm.
        // Confirms multi-branch dispatch on the mutation pillar.
        Map<String, Object> data = execute("""
            mutation {
                submitFilmReview(filmId: 999, rating: 5) {
                    reviewId
                    errors {
                        __typename
                        ... on FilmReviewMissingFilm { path message }
                        ... on FilmReviewBadRating { path message }
                    }
                }
            }
            """);
        var payload = assertThat(data).extractingByKey("submitFilmReview", as(MAP));
        payload.containsEntry("reviewId", null);
        var only = payload.extractingByKey("errors", as(list(Map.class)))
            .hasSize(1)
            .element(0, as(MAP));
        only.containsEntry("__typename", "FilmReviewMissingFilm");
        only.containsEntry("message", "film 999 not found");
        only.extractingByKey("path", as(LIST))
            .containsExactly("submitFilmReview", "errors", "0", "path");
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitFilmReview_validInput_returnsHappyPathPayload() {
        // Happy path: service returns FilmReviewPayload directly; no catch arm fires. Confirms
        // the per-fetcher try/catch wrapper on the mutation switch doesn't perturb non-error
        // returns. reviewId = rating * 10000 + filmId = 5 * 10000 + 42 = 50042.
        Map<String, Object> data = execute("""
            mutation {
                submitFilmReview(filmId: 42, rating: 5) { reviewId errors { __typename } }
            }
            """);
        var payload = assertThat(data).extractingByKey("submitFilmReview", as(MAP));
        payload.containsEntry("reviewId", 50042);
        payload.containsEntry("errors", null);
    }

    // ===== R329: @service record-composite payload carrier =====
    //
    // createFilmsWithActors returns List<FilmWithActors> — a consumer composite bundling one FilmRecord
    // plus a List<ActorRecord>. The payload's `results` data field is a source-passthrough projection of
    // that list (RecordCompositeField — no re-fetch); the intermediate result type's @field-mapped
    // @table children re-fetch Film / Actor through the record-backed accessor path off the composite's
    // filmRecord() / actorRecords(). A filmId of -1 throws, exercising the Outcome error arm.

    @Test
    @SuppressWarnings("unchecked")
    void createFilmsWithActors_listCompositeProjection_roundTrips() {
        // Happy path: the producer returns two composites; graphql-java maps each onto
        // FilmWithActorsResult and re-fetches the film + its actors off the composite's records.
        Map<String, Object> data = execute("""
            mutation {
                createFilmsWithActors(filmIds: [1, 2]) {
                    results { film { title } actors { firstName } }
                    errors { __typename }
                }
            }
            """);
        var payload = assertThat(data).extractingByKey("createFilmsWithActors", as(MAP));
        payload.containsEntry("errors", null);
        var results = payload.extractingByKey("results", as(list(Map.class))).hasSize(2);
        var first = results.element(0, as(MAP));
        first.extractingByKey("film", as(MAP)).extractingByKey("title").isNotNull();
        first.extractingByKey("actors", as(LIST)).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void createFilmsWithActors_errorArm_rendersResultsNull() {
        // filmId -1 throws IllegalArgumentException; the MutationServiceRecordField try/catch ships
        // Outcome.ErrorList. The `results` passthrough narrows Outcome.Success, sees the ErrorList arm,
        // and returns null (data: null); the sibling errors field reads ErrorList.errors().
        Map<String, Object> data = execute("""
            mutation {
                createFilmsWithActors(filmIds: [-1]) {
                    results { film { title } }
                    errors { __typename ... on FilmsWithActorsError { message } }
                }
            }
            """);
        var payload = assertThat(data).extractingByKey("createFilmsWithActors", as(MAP));
        payload.containsEntry("results", null);
        payload.extractingByKey("errors", as(list(Map.class)))
            .hasSize(1)
            .element(0, as(MAP))
            .containsEntry("__typename", "FilmsWithActorsError")
            .containsEntry("message", "invalid film id: -1");
    }

    // ===== R268 arm-switch: @table DataLoader data field under a root @service Outcome payload =====
    //
    // submitFilmReviewWithFilm returns a FilmReviewWithFilmPayload whose `film` field is a
    // @table-bound DataLoader lookup (RecordTableField, @sourceRow leaf-PK keyed off the payload's
    // filmId) sibling to the WrapperArm errors field. This is the pairing R244's inventory found
    // nowhere in sakila and that the retired arm-switch allow-list wrongly rejected. Both arms run
    // against the real generated fetchers: the success arm batch-loads the Film off
    // Outcome.Success.value(); the error arm narrows Success, sees the ErrorList arm, and returns
    // completedFuture(null) before key extraction (no NPE on a missing key), rendering film: null
    // while the sibling errors field stays reachable.

    @Test
    @SuppressWarnings("unchecked")
    void submitFilmReviewWithFilm_validInput_armSwitchLoadsFilmAndNullErrors() {
        // Happy path: service returns the payload (reviewId = 5 * 10000 + 1 = 50001, filmId = 1).
        // The film fetcher narrows Outcome.Success, lifts filmId off success.value(), and
        // batch-loads film 1 (ACADEMY DINOSAUR); errors resolves null on the Success arm (R275:
        // null, not [], so the wire honours the nullable errors field's SDL nullability).
        Map<String, Object> data = execute("""
            mutation {
                submitFilmReviewWithFilm(filmId: 1, rating: 5) {
                    reviewId
                    film { filmId title }
                    errors { __typename }
                }
            }
            """);
        var payload = assertThat(data).extractingByKey("submitFilmReviewWithFilm", as(MAP));
        payload.containsEntry("reviewId", 50001);
        payload.containsEntry("errors", null);
        var only = payload.extractingByKey("film", as(list(Map.class)))
            .hasSize(1)
            .element(0, as(MAP));
        only.containsEntry("filmId", 1);
        only.containsEntry("title", "ACADEMY DINOSAUR");
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitFilmReviewWithFilm_invalidRating_armSwitchRendersFilmNull() {
        // Error arm: rating outside [1,10] throws; the throwable lands on Outcome.ErrorList. The
        // @table-bound film fetcher arm-switches to completedFuture(null) before touching the
        // DataLoader — the regression R268 fixes (an un-arm-switched @table child would read a
        // property off the Outcome object or NPE on a missing key). film renders null, the sibling
        // errors field stays reachable, and reviewId resolves null on the error arm.
        Map<String, Object> data = execute("""
            mutation {
                submitFilmReviewWithFilm(filmId: 1, rating: 11) {
                    reviewId
                    film { filmId title }
                    errors {
                        __typename
                        ... on FilmReviewBadRating { path message }
                        ... on FilmReviewMissingFilm { path message }
                    }
                }
            }
            """);
        var payload = assertThat(data).extractingByKey("submitFilmReviewWithFilm", as(MAP));
        payload.containsEntry("reviewId", null);
        payload.containsEntry("film", null);
        var only = payload.extractingByKey("errors", as(list(Map.class)))
            .hasSize(1)
            .element(0, as(MAP));
        only.containsEntry("__typename", "FilmReviewBadRating");
        only.containsEntry("message", "rating must be in [1, 10]; got 11");
        only.extractingByKey("path", as(LIST))
            .containsExactly("submitFilmReviewWithFilm", "errors", "0", "path");
    }

    // ===== R275 source-record carrier with an error channel end-to-end =====
    //
    // serviceFilmByIdWithErrors returns a bare FilmRecord into { film: Film, errors: [...] }. The
    // producer wraps the record in Outcome; the `film` field is a SingleRecordTableField with the
    // OUTCOME_SUCCESS envelope, so its fetcher narrows Outcome.Success and reads off success.value()
    // (success arm) or returns null (ErrorList arm). Before R275 the fetcher cast the Outcome source
    // straight to FilmRecord and threw ClassCastException — the opptak buckets B/D defect.

    @Test
    @SuppressWarnings("unchecked")
    void serviceFilmByIdWithErrors_validId_armSwitchReadsFilmOffSuccessValue() {
        // Success arm: the service returns FilmRecord(1); the producer emits Outcome.Success. The
        // film fetcher narrows Success, casts success.value() to FilmRecord, and re-selects the full
        // row; errors resolves null on the success arm (R275: null, not []).
        Map<String, Object> data = execute("""
            mutation {
                serviceFilmByIdWithErrors(id: 1) {
                    film { filmId title }
                    errors { __typename }
                }
            }
            """);
        var payload = assertThat(data).extractingByKey("serviceFilmByIdWithErrors", as(MAP));
        payload.containsEntry("errors", null);
        var film = payload.extractingByKey("film", as(MAP));
        film.containsEntry("filmId", 1);
        film.containsEntry("title", "ACADEMY DINOSAUR");
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceFilmByIdWithErrors_missingFilm_armSwitchRendersFilmNull() {
        // Error arm: id 999 throws the mapped @error; the producer routes it onto Outcome.ErrorList.
        // The film fetcher narrows Success, sees the error arm, and resolves null before any key
        // read (no ClassCastException, no NPE); the typed error lands in the errors union.
        Map<String, Object> data = execute("""
            mutation {
                serviceFilmByIdWithErrors(id: 999) {
                    film { filmId title }
                    errors {
                        __typename
                        ... on FilmReviewMissingFilm { message }
                    }
                }
            }
            """);
        var payload = assertThat(data).extractingByKey("serviceFilmByIdWithErrors", as(MAP));
        payload.containsEntry("film", null);
        var only = payload.extractingByKey("errors", as(list(Map.class)))
            .hasSize(1)
            .element(0, as(MAP));
        only.containsEntry("__typename", "FilmReviewMissingFilm");
        only.containsEntry("message", "film 999 not found");
    }

    // ===== R275 reopened scope: @service list-data-field source-record carrier end-to-end =====
    //
    // serviceFilmsByIdsWithErrors returns List<FilmRecord> into { films: [Film!], errors: [...] }
    // — the opptak leggTilTagger shape. The films fetcher exercises the MANY + OUTCOME_SUCCESS
    // combination: narrow Outcome.Success, cast success.value() to List<FilmRecord>, re-select by
    // PK in input order. (R294: the carrier used to also carry a redundant @splitQuery on the data
    // field purely to fire the tolerated-redundant advisory; that path is now pinned on minimal
    // SDL at pipeline tier by SingleRecordTableFieldServiceProducerPipelineTest, so the directive
    // was dropped from this fixture and the carrier re-selects by PK regardless.)

    @Test
    @SuppressWarnings("unchecked")
    void serviceFilmsByIdsWithErrors_validIds_projectsFilmsInInputOrder() {
        // Success arm: the producer emits Outcome.Success(List<FilmRecord>); the films fetcher
        // projects the rows by PK preserving input order; errors resolves null on the success arm.
        Map<String, Object> data = execute("""
            mutation {
                serviceFilmsByIdsWithErrors(ids: [2, 1]) {
                    films { filmId title }
                    errors { __typename }
                }
            }
            """);
        var payload = assertThat(data).extractingByKey("serviceFilmsByIdsWithErrors", as(MAP));
        payload.containsEntry("errors", null);
        var films = payload.extractingByKey("films", as(list(Map.class))).hasSize(2);
        films.element(0, as(MAP)).containsEntry("filmId", 2).containsEntry("title", "ACE GOLDFINGER");
        films.element(1, as(MAP)).containsEntry("filmId", 1).containsEntry("title", "ACADEMY DINOSAUR");
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceFilmsByIdsWithErrors_missingFilm_armSwitchRendersFilmsNull() {
        // Error arm: an unknown id throws the mapped @error; the producer routes it onto
        // Outcome.ErrorList. The films fetcher narrows Success, sees the error arm, and resolves
        // null before any key read; the typed error lands in the errors union.
        Map<String, Object> data = execute("""
            mutation {
                serviceFilmsByIdsWithErrors(ids: [1, 999]) {
                    films { filmId }
                    errors {
                        __typename
                        ... on FilmReviewMissingFilm { message }
                    }
                }
            }
            """);
        var payload = assertThat(data).extractingByKey("serviceFilmsByIdsWithErrors", as(MAP));
        payload.containsEntry("films", null);
        var only = payload.extractingByKey("errors", as(list(Map.class)))
            .hasSize(1)
            .element(0, as(MAP));
        only.containsEntry("__typename", "FilmReviewMissingFilm");
        only.containsEntry("message", "film 999 not found");
    }

    // ===== R275 requirement 2: @nodeId-from-record source-record carrier end-to-end =====
    //
    // serviceDeleteFilmByIdWithErrors / serviceDeleteFilmsByIdsWithErrors return bare
    // FilmRecord(s) into { filmId: ID @nodeId, errors } / { filmIds: [ID] @nodeId, errors } —
    // the opptak fjernSakTagg / fjernSakTagger shapes. The data field is a SingleRecordIdField
    // with the OUTCOME_SUCCESS envelope: it narrows Outcome.Success and encodes the node-key
    // column(s) straight off the in-memory record(s) through the Film @node encoder, with no
    // follow-up SELECT. The producer synthesizes records whose ids (9001, 9002) exist in no
    // table, so a regression that reintroduces a re-fetch resolves null/empty here and fails.

    @Test
    @SuppressWarnings("unchecked")
    void serviceDeleteFilmByIdWithErrors_validId_encodesNodeIdOffInMemoryRecord() {
        Map<String, Object> data = execute("""
            mutation {
                serviceDeleteFilmByIdWithErrors(id: 9001) {
                    filmId
                    errors { __typename }
                }
            }
            """);
        var payload = assertThat(data).extractingByKey("serviceDeleteFilmByIdWithErrors", as(MAP));
        payload.containsEntry("errors", null);
        payload.containsEntry("filmId",
            no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 9001));
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceDeleteFilmByIdWithErrors_missingFilm_armSwitchRendersIdNull() {
        // Error arm: id 999 throws the mapped @error; the producer routes it onto
        // Outcome.ErrorList. The id fetcher narrows Success, sees the error arm, and resolves
        // null before any encode; the typed error lands in the errors union.
        Map<String, Object> data = execute("""
            mutation {
                serviceDeleteFilmByIdWithErrors(id: 999) {
                    filmId
                    errors {
                        __typename
                        ... on FilmReviewMissingFilm { message }
                    }
                }
            }
            """);
        var payload = assertThat(data).extractingByKey("serviceDeleteFilmByIdWithErrors", as(MAP));
        payload.containsEntry("filmId", null);
        var only = payload.extractingByKey("errors", as(list(Map.class)))
            .hasSize(1)
            .element(0, as(MAP));
        only.containsEntry("__typename", "FilmReviewMissingFilm");
        only.containsEntry("message", "film 999 not found");
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceDeleteFilmsByIdsWithErrors_validIds_encodesNodeIdsInProducerOrder() {
        Map<String, Object> data = execute("""
            mutation {
                serviceDeleteFilmsByIdsWithErrors(ids: [9002, 9001]) {
                    filmIds
                    errors { __typename }
                }
            }
            """);
        var payload = assertThat(data).extractingByKey("serviceDeleteFilmsByIdsWithErrors", as(MAP));
        payload.containsEntry("errors", null);
        payload.extractingByKey("filmIds", as(LIST)).containsExactly(
            no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 9002),
            no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 9001));
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceDeleteFilmsByIdsWithErrors_missingFilm_armSwitchRendersIdsNull() {
        Map<String, Object> data = execute("""
            mutation {
                serviceDeleteFilmsByIdsWithErrors(ids: [9001, 999]) {
                    filmIds
                    errors {
                        __typename
                        ... on FilmReviewMissingFilm { message }
                    }
                }
            }
            """);
        var payload = assertThat(data).extractingByKey("serviceDeleteFilmsByIdsWithErrors", as(MAP));
        payload.containsEntry("filmIds", null);
        var only = payload.extractingByKey("errors", as(list(Map.class)))
            .hasSize(1)
            .element(0, as(MAP));
        only.containsEntry("__typename", "FilmReviewMissingFilm");
        only.containsEntry("message", "film 999 not found");
    }

    // ===== R12 DML LocalContext error channel end-to-end =====
    //
    // Mirrors the @service-backed submitFilmReview tests on the DML pillar. The
    // createFilmWithErrors mutation classifies as MutationDmlRecordField (admitted by the
    // structural DML-carrier scan) with errorChannel = Optional.of(ErrorChannel.LocalContext)
    // (bound by FieldBuilder.detectStructuralDmlErrorChannel). The catch arm dispatches
    // through ErrorRouter.dispatchToLocalContext, which packs the matched throwable into
    // env.getLocalContext() as a single-element list and returns data: null; the carrier's
    // errors field reads that list back via env.getLocalContext(), and the data field (Film)
    // short-circuits on the null source so the SDL response renders { film: null, errors: [{
    // __typename, path, message }] }.

    @Test
    @SuppressWarnings("unchecked")
    void createFilmWithErrors_validLanguage_returnsHappyPathPayload() {
        // Happy path: valid language_id round-trips through the carrier-walk DML fetcher. The
        // catch arm doesn't fire; the data field's follow-up SELECT projects the inserted row.
        // errors slot resolves to an empty list (graphql-java treats absent localContext as
        // null and the SDL-level errors-field resolver returns []). Confirms the LocalContext
        // wiring doesn't perturb non-error returns.
        String title = "R12-LC-HAPPY-" + java.util.UUID.randomUUID();
        try {
            var rawResult = executeRaw("""
                mutation {
                    createFilmWithErrors(in: { title: "%s", languageId: 1 }) {
                        film { filmId title languageId }
                        errors { __typename }
                    }
                }
                """.formatted(title));
            assertThat(rawResult.getErrors()).as("top-level errors: %s", rawResult.getErrors()).isEmpty();
            Map<String, Object> data = rawResult.getData();
            var payload = assertThat(data).extractingByKey("createFilmWithErrors", as(MAP));
            payload.extractingByKey("film", as(MAP))
                .containsEntry("title", title)
                .containsEntry("languageId", 1);
            // graphql-java renders an absent localContext as null for the nullable
            // [FilmCreateError] list; on the happy path no exception fires, so localContext is
            // never set.
            payload.containsEntry("errors", null);
        } finally {
            // Inserted rows would otherwise drift the totalCount / ordering assertions in the
            // sibling films-connection tests against the seeded Sakila set.
            dsl.deleteFrom(DSL.table("film"))
                .where(DSL.field("title", String.class).eq(title))
                .execute();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void createFilmWithErrors_invalidLanguageId_routesThroughLocalContextErrorChannel() {
        // Invalid FK reference: language_id 99999 doesn't exist in `language`. PostgreSQL raises
        // SQLSTATE 23503 (foreign_key_violation); jOOQ surfaces it as
        // org.jooq.exception.IntegrityConstraintViolationException, which the @error
        // GENERIC-handler mapping on FilmCreateConstraintViolation matches.
        // ErrorRouter.dispatchToLocalContext packs the throwable into env.getLocalContext();
        // the data field (Film) short-circuits on the null source and the errors field reads the
        // list back. graphql-java's source-class TypeResolver dispatches the union arm.
        graphql.ExecutionResult result = executeRaw("""
            mutation {
                createFilmWithErrors(in: { title: "R12-LC-FK-ERR", languageId: 99999 }) {
                    film { title }
                    errors {
                        __typename
                        ... on FilmCreateConstraintViolation { path message }
                    }
                }
            }
            """);
        // No top-level errors: the catch arm produced a DataFetcherResult; the field error
        // surfaces inside `data.createFilmWithErrors.errors` instead.
        assertThat(result.getErrors()).as("top-level errors: %s", result.getErrors()).isEmpty();
        Map<String, Object> data = result.getData();
        var payload = assertThat(data).extractingByKey("createFilmWithErrors", as(MAP));
        payload.containsEntry("film", null);
        var only = payload.extractingByKey("errors", as(list(Map.class)))
            .hasSize(1)
            .element(0, as(MAP));
        only.containsEntry("__typename", "FilmCreateConstraintViolation");
        // Path follows the same convention as the @service mutation tests: full coordinate path
        // through the union element.
        only.extractingByKey("path", as(LIST))
            .containsExactly("createFilmWithErrors", "errors", "0", "path");
        // Message routes through Throwable.getMessage(); jOOQ wraps the PG message which names
        // the violated FK constraint. Liberal contains() check insulates against PG-version
        // formatting drift.
        only.extractingByKey("message", as(STRING))
            .containsIgnoringCase("foreign key");
    }

    // ===== R154 mutable-bean payload shape end-to-end =====
    //
    // submitSetterShapeFilmReview returns a SetterShapeFilmReviewPayload (no-arg ctor +
    // setters). The carrier classifier resolves R154's MutableBean shape; the emitter produces
    // the catch-arm payload-factory in setter form. End-to-end round-trip through Sakila
    // PostgreSQL pins that R154's emit changes don't regress the mutation pillar.

    @Test
    @SuppressWarnings("unchecked")
    void submitSetterShapeFilmReview_validInput_returnsHappyPathPayload() {
        Map<String, Object> data = execute("""
            mutation {
                submitSetterShapeFilmReview(filmId: 42, rating: 5) { reviewId errors { __typename } }
            }
            """);
        var payload = assertThat(data).extractingByKey("submitSetterShapeFilmReview", as(MAP));
        payload.containsEntry("reviewId", 50042);
        payload.containsEntry("errors", null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitSetterShapeFilmReview_invalidRating_routesThroughBadRatingErrorType() {
        // Catch-arm payload-factory emits `errors -> { var p = new SetterShapeFilmReviewPayload();
        // p.setReviewId(null); p.setErrors(errors); return p; }` in setter form. End-to-end
        // round-trip places the bad-rating throwable into the errors list.
        Map<String, Object> data = execute("""
            mutation {
                submitSetterShapeFilmReview(filmId: 1, rating: 11) {
                    reviewId
                    errors {
                        __typename
                        ... on FilmReviewBadRating { path message }
                        ... on FilmReviewMissingFilm { path message }
                    }
                }
            }
            """);
        var payload = assertThat(data).extractingByKey("submitSetterShapeFilmReview", as(MAP));
        payload.containsEntry("reviewId", null);
        var only = payload.extractingByKey("errors", as(list(Map.class)))
            .hasSize(1)
            .element(0, as(MAP));
        only.containsEntry("__typename", "FilmReviewBadRating");
        only.containsEntry("message", "rating must be in [1, 10]; got 11");
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitFilmReviewWithDetails_routesThroughInstantiatedInputBean() {
        // R150 execution-tier proof: GraphQL mutation hands an input-object map to the fetcher,
        // which routes it through createFilmReviewDetails (record canonical-ctor instantiation
        // + recursive createFilmReviewTagList for the nested list). The service body reads the
        // typed bean's scalar fields and computes reviewId = rating * 10000 + filmId; if the
        // helper failed to populate, the body would NullPointerException on autoboxing. The
        // nested-list helper is exercised by passing two tags; the service ignores them, but
        // exercising the recursive helper at runtime pins the createFilmReviewTagList emit path.
        Map<String, Object> data = execute("""
            mutation {
                submitFilmReviewWithDetails(details: {
                    filmId: 42, rating: 5, comment: "ok",
                    tags: [{ name: "good", weight: 1 }, { name: "fast", weight: 2 }]
                }) {
                    reviewId
                    errors { __typename }
                }
            }
            """);
        var payload = assertThat(data).extractingByKey("submitFilmReviewWithDetails", as(MAP));
        payload.containsEntry("reviewId", 50042);
        payload.containsEntry("errors", null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitFilmReviewSummary_routesThroughFieldRenamedRecordBean() {
        // R200 execution-tier proof: the SDL input fields filmId/rating diverge from the record
        // components film/stars, bridged by @field(name:). The fetcher must read env.getArgument by
        // the SDL field name (filmId/rating) and bind positionally to the canonical constructor
        // FilmReviewSummary(film, stars). If @field were ignored, classification would reject (no
        // component matches filmId/rating); if the binding mis-positioned, reviewId would differ.
        // The round-trip delegates to submit(), computing reviewId = stars * 10000 + film.
        Map<String, Object> data = execute("""
            mutation {
                submitFilmReviewSummary(summary: { filmId: 42, rating: 5 }) {
                    reviewId
                    errors { __typename }
                }
            }
            """);
        var payload = assertThat(data).extractingByKey("submitFilmReviewSummary", as(MAP));
        payload.containsEntry("reviewId", 50042);
        payload.containsEntry("errors", null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void allSubjects_returnsDiscriminatorPerRow() {
        // R389 composite-shared-key joined-table fixture. Subject exposes its discriminator
        // (subjectKind) as a plain interface field; with no inline fragment no detail join fires, so
        // this exercises the base projection (shared-key subjectId / subjectKind off the base, the
        // inherited displayName off the base aliased as the field name) and the WHERE IN filter in
        // isolation. init.sql seeds two APP subjects then two PERSON subjects (ordered by id).
        Map<String, Object> data = execute("{ allSubjects { subjectId subjectKind displayName } }");
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("allSubjects");
        assertThat(items).hasSize(4);
        assertThat(items).extracting(i -> i.get("subjectKind"))
            .containsExactly("APP", "APP", "PERSON", "PERSON");
        assertThat(items).extracting(i -> i.get("displayName"))
            .containsExactly("Billing service", "Reporting service", "Ada Lovelace", "Alan Turing");
    }

    @Test
    @SuppressWarnings("unchecked")
    void allSubjects_inlineFragmentDetail_joinsWithoutAmbiguousColumn() {
        // R389 composite case (subsumes the R388 workaround this fixture used to carry). Each concrete
        // type declares its own detail @table (jti_app_account / jti_person); the interface fetcher
        // LEFT JOINs each detail table on the composite child->parent key, gated by the discriminator
        // value, and projects the participant's detail-exclusive column off the detail alias. The
        // discriminator qualification stress carries over: subject_kind is re-declared on the detail
        // tables via the composite key, so a bare discriminator reference in the SELECT projection,
        // the LEFT JOIN ON-clause, or the WHERE filter makes PostgreSQL reject the query as ambiguous
        // once the join fires. execute() asserts no GraphQL errors, so reaching the data assertions
        // already proves the ambiguous-column failure is gone. The detail value is non-null for
        // matching discriminator rows and null for the others (the LEFT JOIN is discriminator-gated).
        SQL_LOG.clear();
        Map<String, Object> data = execute("""
            { allSubjects {
                __typename
                subjectKind
                ... on AppAccount { clientId }
                ... on Person { fullName }
            } }
            """);
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("allSubjects");
        assertThat(items).hasSize(4);

        var apps = items.stream().filter(i -> "AppAccount".equals(i.get("__typename"))).toList();
        assertThat(apps).hasSize(2);
        assertThat(apps).extracting(i -> i.get("clientId"))
            .containsExactlyInAnyOrder("billing-client-001", "reporting-client-002");
        assertThat(apps).allSatisfy(i ->
            assertThat(i.get("fullName")).as("Person-only field is null on AppAccount rows").isNull());

        var persons = items.stream().filter(i -> "Person".equals(i.get("__typename"))).toList();
        assertThat(persons).hasSize(2);
        assertThat(persons).extracting(i -> i.get("fullName"))
            .containsExactlyInAnyOrder("Ada Lovelace (full)", "Alan Turing (full)");
        assertThat(persons).allSatisfy(i ->
            assertThat(i.get("clientId")).as("AppAccount-only field is null on Person rows").isNull());

        // Mechanical guard: the generated SQL qualifies the discriminator column to the base table
        // (SQL_LOG entries are lowercased by the ExecuteListener). The qualified
        // "jti_subject"."subject_kind" form, present at the join site, is what removes the ambiguity.
        assertThat(SQL_LOG)
            .as("the discriminated-interface query must qualify the discriminator to the base table at the join site")
            .anyMatch(s -> s.contains("\"jti_subject\".\"subject_kind\"") && s.contains("join"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void allSubjects_discriminatorFieldInsideFragment_routesViaSyntheticAlias() {
        // R392 regression: the discriminator field (subjectKind) selected INSIDE an inline fragment,
        // alongside a cross-table detail field. The interface exposes subjectKind as a queryable field,
        // so the participant $fields projects the real subject_kind column; without a synthetic routing
        // alias the discriminated TypeResolver's bare read of "subject_kind" matches both that projection
        // and the routing copy ("Ambiguous match found", resolved by luck). The fix projects the routing
        // copy as "__discriminator__" and the TypeResolver reads that alias, so routing is unambiguous and
        // the user-facing subjectKind field still resolves from its own column.
        SQL_LOG.clear();
        Map<String, Object> data = execute("""
            { allSubjects {
                subjectId
                ... on AppAccount { subjectKind clientId }
                ... on Person     { subjectKind fullName }
            } }
            """);
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("allSubjects");
        assertThat(items).hasSize(4);

        var apps = items.stream().filter(i -> "billing-client-001".equals(i.get("clientId"))
            || "reporting-client-002".equals(i.get("clientId"))).toList();
        assertThat(apps).hasSize(2);
        // The user-facing discriminator field resolves correctly for the routed type.
        assertThat(apps).allSatisfy(i -> assertThat(i.get("subjectKind")).isEqualTo("APP"));

        var persons = items.stream().filter(i -> "Ada Lovelace (full)".equals(i.get("fullName"))
            || "Alan Turing (full)".equals(i.get("fullName"))).toList();
        assertThat(persons).hasSize(2);
        assertThat(persons).allSatisfy(i -> assertThat(i.get("subjectKind")).isEqualTo("PERSON"));

        // Mechanical guard: the routing discriminator is projected under the synthetic alias, distinct
        // from the real subject_kind column the $fields projection also emits. (SQL_LOG is lowercased.)
        assertThat(SQL_LOG)
            .as("the discriminated interface fetcher must project the routing discriminator under the __discriminator__ alias")
            .anyMatch(s -> s.contains("as \"__discriminator__\""));
    }

    @Test
    @SuppressWarnings("unchecked")
    void allParties_joinedTableInheritance_routesAndProjectsPerParticipantTable() {
        // R389 acceptance gate (single-column shared-PK fixture). Party is first-class discriminated
        // joined-table inheritance: each participant declares its OWN detail @table and its inherited
        // displayName via @reference back to the base. The interface fetcher selects FROM party and
        // LEFT JOINs each detail table gated by the participant's discriminator value. Correctness is
        // a runtime property (join orientation, discriminator gating, NULL-through), so execute()
        // carrying no errors plus the per-row assertions below are the proof, not the generated shape.
        Map<String, Object> data = execute("""
            { allParties {
                __typename
                partyId
                displayName
                ... on Individual { birthDate }
                ... on Company { orgNumber }
            } }
            """);
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("allParties");
        assertThat(items).hasSize(3);

        var grace = items.stream().filter(i -> Integer.valueOf(1).equals(i.get("partyId"))).findFirst().orElseThrow();
        assertThat(grace.get("__typename")).isEqualTo("Individual");
        assertThat(grace.get("displayName")).as("inherited base field resolves off the base").isEqualTo("Grace Hopper");
        assertThat(grace.get("birthDate")).asString().startsWith("1906");
        assertThat(grace.get("orgNumber")).as("the sibling participant's own column is absent on Individual rows").isNull();

        var sikt = items.stream().filter(i -> Integer.valueOf(2).equals(i.get("partyId"))).findFirst().orElseThrow();
        assertThat(sikt.get("__typename")).isEqualTo("Company");
        assertThat(sikt.get("displayName")).isEqualTo("Sikt");
        assertThat(sikt.get("orgNumber")).isEqualTo("NO-919477822");
        assertThat(sikt.get("birthDate")).as("the sibling participant's own column is absent on Company rows").isNull();

        // NULL-through: the INDIVIDUAL base row with no matching party_individual detail row still
        // routes correctly and resolves its base fields, with the detail column NULL.
        var detached = items.stream().filter(i -> Integer.valueOf(3).equals(i.get("partyId"))).findFirst().orElseThrow();
        assertThat(detached.get("__typename")).isEqualTo("Individual");
        assertThat(detached.get("displayName")).isEqualTo("Detached Individual");
        assertThat(detached.get("birthDate")).as("LEFT JOIN NULL-through: no detail row, so the detail column is NULL").isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void allIndividuals_standalone_resolvesInheritedFieldViaParentReference() {
        // R389 standalone use: the joined-table concrete type queried outside its interface. The
        // fetcher selects FROM party_individual; displayName (inherited, base-resident) resolves
        // through the parent @reference join back to party, while partyId / birthDate read directly
        // off the detail table. This is the regression guard for the property the redesign exists for.
        Map<String, Object> data = execute("""
            { allIndividuals {
                partyId
                displayName
                birthDate
            } }
            """);
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("allIndividuals");
        // FROM party_individual: only the one row with a detail row (Grace Hopper); the detached
        // INDIVIDUAL base row has no party_individual row and so is absent from the standalone query.
        assertThat(items).hasSize(1);
        var grace = items.get(0);
        assertThat(grace.get("partyId")).isEqualTo(1);
        assertThat(grace.get("displayName")).as("inherited field resolved via the parent reference join to party").isEqualTo("Grace Hopper");
        assertThat(grace.get("birthDate")).asString().startsWith("1906");
    }
}
