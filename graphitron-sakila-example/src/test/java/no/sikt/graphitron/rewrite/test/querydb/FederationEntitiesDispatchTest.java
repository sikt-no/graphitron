package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.generated.federated.Graphitron;
import no.sikt.graphitron.generated.federated.schema.GraphitronContext;
import no.sikt.graphitron.generated.federated.util.NodeIdEncoder;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;

/**
 * End-to-end tests that exercise the federation entity dispatcher (the generated
 * {@code EntityFetcherDispatch}) against a real PostgreSQL database. Verifies the
 * runtime path: representation → most-specific resolvable alternative → per-tenant SELECT
 * via {@code VALUES (idx, ...)} derived table → scatter back to original positions.
 */
@ExecutionTier
class FederationEntitiesDispatchTest {

    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() throws Exception {
        dsl = SakilaDatabase.dsl();

        graphql = Graphitron.newGraphQL().build();
    }


    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String query, Map<String, Object> variables) {
        var input = Graphitron.newExecutionInput(dsl)
            .query(query)
            .variables(variables)
            .build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    /**
     * Happy path for a {@code @node} type's {@code NODE_ID}-shape alternative: the rep
     * carries a base64 NodeId; the dispatcher decodes it, runs a single SELECT via the
     * derived-table join, and returns the row with the requested fields plus the
     * synthetic {@code __typename}.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_resolveSingleNodeIdRep_returnsHydratedRow() {
        String customerId = NodeIdEncoder.encode("Customer", 1);
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename ... on Customer { firstName lastName } } }",
            Map.of("reps", List.of(Map.of("__typename", "Customer", "id", customerId))));

        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0)).containsEntry("__typename", "Customer");
        assertThat(entities.get(0)).containsKey("firstName");
        assertThat(entities.get(0)).containsKey("lastName");
    }

    /**
     * Address carries a legacy key shape, {@code (address_id, city_id)}: a rep whose id was minted
     * in it resolves to the row, the conversion running inside the dispatcher's decode, and the id
     * the entity answers is the current form.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_resolveARepWhoseIdIsInTheLegacyKeyShape() {
        String legacy = NodeIdEncoder.encode("Address", 1, 300);
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename ... on Address { id addressId } } }",
            Map.of("reps", List.of(Map.of("__typename", "Address", "id", legacy))));

        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).singleElement().satisfies(entity -> {
            assertThat(entity).containsEntry("addressId", 1);
            assertThat(entity).containsEntry("id", NodeIdEncoder.encode("Address", 1));
        });
    }

    /**
     * Order preservation under federation's exact-position contract: a single
     * {@code _entities} call with three reps of mixed {@code __typename} returns results
     * in the same order. Carrying {@code idx} through SQL makes order a SQL property,
     * not a Java post-processing step.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_mixedTypenames_preserveOrder() {
        String customer1 = NodeIdEncoder.encode("Customer", 1);
        String film1 = NodeIdEncoder.encode("Film", 1);
        String customer2 = NodeIdEncoder.encode("Customer", 2);

        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename"
            + " ... on Customer { firstName }"
            + " ... on Film { title }"
            + " } }",
            Map.of("reps", List.of(
                Map.of("__typename", "Customer", "id", customer1),
                Map.of("__typename", "Film", "id", film1),
                Map.of("__typename", "Customer", "id", customer2))));

        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(3);
        assertThat(entities.get(0)).containsEntry("__typename", "Customer");
        assertThat(entities.get(1)).containsEntry("__typename", "Film");
        assertThat(entities.get(2)).containsEntry("__typename", "Customer");
    }

    /**
     * Empty representations list returns an empty list cleanly, with no SELECT issued and
     * no NPE.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_emptyRepresentations_returnsEmptyList() {
        Map<String, Object> data = execute(
            "{ _entities(representations: []) { __typename } }",
            Map.of());
        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).isEmpty();
    }

    /**
     * Unknown {@code __typename} surfaces a federation-level resolution failure (null
     * entry) rather than an NPE in the dispatcher. Federation lifts this into its own
     * "entity resolution failed" error at the gateway.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_unknownTypename_yieldsNullSlot() {
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename } }",
            Map.of("reps", List.of(Map.of("__typename", "DoesNotExist", "id", "garbage"))));
        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0)).isNull();
    }

    /**
     * Garbage NodeId (fails to decode through {@link NodeIdEncoder#decodeValues}) yields a
     * null slot, not an NPE or wrong-row return.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_garbageNodeId_yieldsNullSlot() {
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename } }",
            Map.of("reps", List.of(Map.of("__typename", "Customer", "id", "not-a-base64-id"))));
        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0)).isNull();
    }

    /**
     * A well-formed NodeId whose decoded key has too few parts for a composite-key
     * {@code @node} type ({@code FilmActor}, PK actor_id + film_id) yields a null slot,
     * not a 500: the dispatcher's arity guard skips the rep instead of indexing past the
     * decoded array, whose out-of-bounds message would leak unredacted through the federation
     * error surface. Since the {@link #execute} helper asserts the errors list is empty, a
     * null slot with no error is exactly the contract.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_compositeNodeIdUnderArity_yieldsNullSlotNoError() {
        String underArity = NodeIdEncoder.encode("FilmActor", 1); // one key part; FilmActor needs two
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename ... on FilmActor { filmId actorId } } }",
            Map.of("reps", List.of(Map.of("__typename", "FilmActor", "id", underArity))));
        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0)).isNull();
    }

    /**
     * Over-arity companion: a NodeId with too many key parts whose valid 2-part prefix
     * ({@code actor_id=1, film_id=1}) IS an existing row. The {@code != N} arity guard rejects
     * it rather than dropping the extra part and resolving the prefix row: the rep is skipped
     * and its slot stays null, so a client cannot address a row by a longer id than the type's
     * key admits.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_compositeNodeIdOverArity_yieldsNullNotWrongRow() {
        String overArity = NodeIdEncoder.encode("FilmActor", 1, 1, 999); // 2-part prefix (1,1) is real
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename ... on FilmActor { filmId actorId } } }",
            Map.of("reps", List.of(Map.of("__typename", "FilmActor", "id", overArity))));
        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0))
            .as("over-arity id must not silently resolve its valid 2-part prefix row")
            .isNull();
    }

    /**
     * A node-id representation whose key value is not a value of its column, wrapped
     * ({@code 4294967297} is 2^32 + 1, which a lenient coercion turns into customer 1) or
     * non-numeric, beside a valid one: the undecodable rep's slot is null with no error, and the
     * valid rep resolves. Nothing is bound into SQL for the refused rep.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_nodeIdKeyValueNotOfItsColumn_yieldsNullSlotBesideAValidRep() {
        for (String key : List.of("4294967297", "abc")) {
            String bad = NodeIdEncoder.encode("Customer", key);
            String valid = NodeIdEncoder.encode("Customer", 2);
            Map<String, Object> data = execute(
                "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
                + " __typename ... on Customer { customerId } } }",
                Map.of("reps", List.of(
                    Map.of("__typename", "Customer", "id", bad),
                    Map.of("__typename", "Customer", "id", valid))));
            List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
            assertThat(entities).as("key value '%s'", key).hasSize(2);
            assertThat(entities.get(0)).as("key value '%s'", key).isNull();
            assertThat(entities.get(1)).containsEntry("customerId", 2);
        }
    }

    /**
     * A {@code @key} representation whose authored key value would wrap its {@code int4} column
     * onto city 1: a null slot with no error, not city 1. The documented coercion stays for a
     * value that does not wrap, which the valid sibling rep shows.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_directKeyValueWrapsItsColumn_yieldsNullSlotNotTheWrappedRow() {
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename ... on City { cityId } } }",
            Map.of("reps", List.of(
                Map.of("__typename", "City", "cityId", 4294967297L),
                Map.of("__typename", "City", "cityId", 2))));
        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(2);
        assertThat(entities.get(0)).isNull();
        assertThat(entities.get(1)).containsEntry("cityId", 2);
    }

    /**
     * DIRECT-shape alternative: Film carries {@code @key(fields: "filmId")} and a rep with
     * {@code filmId: 1} resolves through the column-value path (not NodeId decode). Same
     * SQL shape as the NODE_ID path; only the rep-to-column-values translation differs.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_directShapeKey_resolvesViaColumnValue() {
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename ... on Film { title } } }",
            Map.of("reps", List.of(Map.of("__typename", "Film", "filmId", 1))));
        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0)).containsEntry("__typename", "Film");
        assertThat(entities.get(0)).containsKey("title");
    }

    /**
     * A same-table child over the primary-key reference under an entity: three representations for
     * three films each get
     * their own film's title under {@code sameRow}, never one row shared by the batch.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_sameRowChild_readsEachEntitysOwnRow() {
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " ... on Film { filmId title sameRow { title } } } }",
            Map.of("reps", List.of(
                Map.of("__typename", "Film", "filmId", 1),
                Map.of("__typename", "Film", "filmId", 2),
                Map.of("__typename", "Film", "filmId", 3))));
        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).extracting(e -> e.get("filmId")).containsExactly(1, 2, 3);
        assertThat(entities).allSatisfy(e ->
            assertThat((Map<String, Object>) e.get("sameRow")).containsEntry("title", e.get("title")));
        assertThat(entities).extracting(e -> ((Map<String, Object>) e.get("sameRow")).get("title"))
            .doesNotHaveDuplicates();
    }

    /**
     * Type-scoped selection set: a single _entities call with two __typenames and inline
     * fragments resolves each entity with its own fragment's fields. graphql-java's
     * {@code DataFetchingFieldSelectionSet} is type-scoped at the {@code _entities} DFE
     * call site, so {@code <TypeName>.$project} walking it picks up only the inline
     * fragment scoped to that __typename.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_typeScopedSelectionSet_resolvesEachTypesOwnFields() {
        String customerId = NodeIdEncoder.encode("Customer", 1);
        String filmId = NodeIdEncoder.encode("Film", 1);
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename"
            + " ... on Customer { firstName lastName }"
            + " ... on Film { title rentalRate }"
            + " } }",
            Map.of("reps", List.of(
                Map.of("__typename", "Customer", "id", customerId),
                Map.of("__typename", "Film", "id", filmId))));

        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(2);
        assertThat(entities.get(0))
            .containsOnlyKeys("__typename", "firstName", "lastName")
            .containsEntry("__typename", "Customer")
            .containsEntry("firstName", "Mary")
            .containsEntry("lastName", "Smith");
        assertThat(entities.get(1))
            .containsOnlyKeys("__typename", "title", "rentalRate")
            .containsEntry("__typename", "Film")
            .containsEntry("title", "ACADEMY DINOSAUR");
        assertThat(((Number) entities.get(1).get("rentalRate")).doubleValue()).isEqualTo(0.99);
    }

    // The per-tenant partition proof lives with the multi-tenant fixture: the dispatcher's
    // grouping widens per decoded tenant, so one batch spanning tenants issues one SELECT per
    // tenant-homogeneous group, proven with inferred bindings (no getTenantId override) in
    // TenantDivinedRoutingExecutionTest.nodes_batchSpanningTenants_partitionsPerDecodedTenant
    // (Query.nodes rides the same resolveByReps dispatch surface as _entities).

    /**
     * Multi-alternative dispatch: Film carries both a synthesised NODE_ID alternative
     * (from {@code @node}) and a DIRECT alternative (from {@code @key(fields: "filmId")}).
     * Reps that supply only {@code id} pick the NODE_ID alternative; reps that supply only
     * {@code filmId} pick the DIRECT alternative. Each rep resolves its own film, in rep order.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_multipleAlternatives_dispatchPerRep() {
        String filmId1 = NodeIdEncoder.encode("Film", 1);
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename ... on Film { title } } }",
            Map.of("reps", List.of(
                Map.of("__typename", "Film", "id", filmId1),
                Map.of("__typename", "Film", "filmId", 2))));

        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(2);
        assertThat(entities).extracting(e -> e.get("__typename"), e -> e.get("title"))
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("Film", "ACADEMY DINOSAUR"),
                org.assertj.core.groups.Tuple.tuple("Film", "ACE GOLDFINGER"));
    }

    /**
     * Empty selection-set sanity: querying only {@code __typename} on an entity returns
     * the type discriminator without requesting any actual columns. The synthetic
     * {@code __typename} literal is always projected, so the rep still resolves.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_typenameOnly_resolvesWithoutFieldProjection() {
        String customerId = NodeIdEncoder.encode("Customer", 1);
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) { __typename } }",
            Map.of("reps", List.of(Map.of("__typename", "Customer", "id", customerId))));
        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0)).containsEntry("__typename", "Customer");
    }

    /**
     * Compound key: {@code FilmActor @key(fields: "filmId actorId")} resolves a rep
     * carrying both column values via a {@code VALUES (idx, film_id, actor_id) JOIN
     * film_actor ON ...} derived-table SELECT. Verifies the dispatcher's multi-column
     * Row arity emission.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_compoundKey_resolvesWithBothColumnValues() {
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename ... on FilmActor { filmId actorId } } }",
            Map.of("reps", List.of(Map.of(
                "__typename", "FilmActor",
                "filmId", 1,
                "actorId", 2))));
        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0)).containsEntry("__typename", "FilmActor");
        assertThat(entities.get(0)).containsEntry("filmId", 1);
        assertThat(entities.get(0)).containsEntry("actorId", 2);
    }

    /**
     * Compound key partial match: a rep that omits one of the two required fields does
     * not match the alternative, so federation surfaces a resolution failure (null slot)
     * rather than silently picking up a different match.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_compoundKey_partialMatchYieldsNullSlot() {
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename } }",
            Map.of("reps", List.of(Map.of(
                "__typename", "FilmActor",
                "filmId", 1)))); // actorId missing
        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0)).isNull();
    }

    /**
     * Most-specific tie-break: Film carries {@code @key(fields: "filmId")} and
     * {@code @key(fields: "filmId title")}. A representation that supplies both fields must
     * select the compound alternative (more required fields wins; ties broken by declaration
     * order). The compound alternative matches on {@code title} as well, so a rep whose title
     * disagrees with the row resolves to null where the simple alternative would have found
     * film 1.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_mostSpecificTieBreak_selectsCompoundOverSimple() {
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename ... on Film { title } } }",
            Map.of("reps", List.of(
                Map.of("__typename", "Film", "filmId", 1, "title", "ACADEMY DINOSAUR"),
                Map.of("__typename", "Film", "filmId", 1, "title", "NOT THIS FILM"))));
        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(2);
        assertThat(entities.get(0))
            .containsEntry("__typename", "Film")
            .containsEntry("title", "ACADEMY DINOSAUR");
        assertThat(entities.get(1))
            .as("the title is part of the match, so a wrong title finds no film")
            .isNull();
    }

    /**
     * {@code @key(fields: "languageId", resolvable: false)} on a non-{@code @node} type:
     * Language declares the key for reference only. A rep that matches the alternative must
     * yield {@code null} (federation surfaces "entity resolution failed"). Locks the
     * dispatcher's {@code resolvable: false} skip path end-to-end.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_nonResolvableKey_yieldsNullSlot() {
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) { __typename } }",
            Map.of("reps", List.of(Map.of("__typename", "Language", "languageId", 1))));
        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0))
            .as("non-resolvable @key alternative must yield null for matching reps")
            .isNull();
    }

    /**
     * A federation customizer that calls {@code fb.fetchEntities(...)} replaces the default
     * dispatcher entirely: the subsequent {@code _entities} call hits the consumer's fetcher,
     * never the generated one. Locks the documented customizer order ("federation customizer
     * runs after Graphitron's defaults attach").
     */
    @Test
    void entities_customizerReplacesDefaultFetchEntities() {
        var customCalled = new java.util.concurrent.atomic.AtomicBoolean();
        GraphQLSchema customised = Graphitron.buildSchema(b -> {}, fed -> fed.fetchEntities(env -> {
            customCalled.set(true);
            return java.util.concurrent.CompletableFuture.completedFuture(java.util.List.of());
        }));
        var customGraphql = GraphQL.newGraphQL(customised).build();
        var input = Graphitron.newExecutionInput(dsl)
            .query("{ _entities(representations: [{__typename: \"Film\", filmId: 1}]) { __typename } }")
            .build();
        customGraphql.execute(input);
        assertThat(customCalled.get())
            .as("custom fetchEntities must run when registered via the federation customizer")
            .isTrue();
    }

    /**
     * A federated {@code _entities} query that selects both {@code id} (the NodeId-encoded
     * primary key) and a sibling SDL field whose {@code @field(name:)} resolves to the same
     * underlying column resolves both fields.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_nodeIdAndOverlappingSiblingField_resolves() {
        String customerNodeId = NodeIdEncoder.encode("Customer", 1);
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename ... on Customer { id customerId firstName } } }",
            Map.of("reps", List.of(Map.of("__typename", "Customer", "id", customerNodeId))));

        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0)).containsEntry("__typename", "Customer");
    }

    /**
     * A representations-driven {@code _entities} fetch that selects <em>only</em> a
     * {@code @service} child: the key arrives via the representation ({@code cityId}) and is
     * deliberately not re-selected in the sub-selection (the Apollo Router shape: the router
     * selects just the fields it needs and supplies key columns through the representation).
     * The entity dispatch SELECT goes through {@code City.$project}, which must force-project
     * {@code CITY_ID} (the service child's SourceKey column) so the DataLoader key extraction
     * finds it on the row; without it the extraction's {@code source.get(Tables.CITY.CITY_ID)}
     * reads a field the SELECT omitted, which jOOQ rejects, and the request fails before the
     * batch is dispatched. City is the unmasked fixture: no other child of City force-projects
     * the key column.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_serviceChildOnly_keyNotReselected_resolvesNonNull() {
        // Seed: city_id 1 = 'Lethbridge' (first row of the city INSERT in init.sql).
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename ... on City { cityUppercase } } }",
            Map.of("reps", List.of(Map.of("__typename", "City", "cityId", 1))));
        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0)).containsEntry("__typename", "City");
        assertThat((String) entities.get(0).get("cityUppercase"))
            .as("service child must resolve non-null when the key is supplied only via the representation")
            .isEqualTo("LETHBRIDGE");
    }

    /**
     * A representations-driven {@code _entities} fetch that selects <em>only</em> a
     * typed-{@code TableRecord}-sourced {@code @service} child whose body needs a column the
     * framework does not supply. This is the PK-only contract end to end: the framework hands over
     * {@code FILM_ID} and nothing else, and the service batch-fetches the {@code title} it needs
     * through the injected {@code DSLContext}.
     *
     * <p>Both children are {@code SourceKey.Wrap.TableRecord} and both services fetch their own
     * non-key data, so neither the wrap nor the fetch separates this from its sibling
     * ({@link #entities_serviceChildOnly_keyNotReselected_resolvesNonNull}). The method does:
     * {@code titleTitlecase} is the example the reverted full-row premise was introduced for, having
     * read {@code film.getTitle()} straight off the parent record instead of fetching it. Pinning
     * its corrected form here, with {@code title} deliberately unselected, is the direct check that
     * the revert holds under federation. No federation fixture pins the {@code Wrap.Row} arm and
     * none needs to: the gated correlation-key arms take {@code sourceKey().columns()} for every
     * wrap alike, so the projection this test guards cannot vary by wrap, and the Row arm's own
     * resolution is pinned at the execution tier by
     * {@link GraphQLQueryTest#cities_cityLowercase_withoutKeyFieldSelected_resolvesViaRow1Source}.
     *
     * <p>The federation scenario is what makes it worth pinning separately: the key arrives in the
     * representation rather than in the client's selection, so the selected service field's own
     * arm is the only thing projecting {@code FILM_ID}. If it stopped doing so, the entity
     * dispatch SELECT would omit it and the extraction's
     * {@code source.get(Tables.FILM.FILM_ID)} would read a field absent from the row type, which
     * jOOQ rejects. No key record is built and the service is never entered; the request fails
     * through {@code ErrorRouter} rather than resolving against a partial key.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_tableRecordServiceChildOnly_serviceFetchedColumnResolvesNonNull() {
        // Seed: film_id 1 = 'ACADEMY DINOSAUR' (first row of the film INSERT in init.sql).
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename ... on Film { titleTitlecase } } }",
            Map.of("reps", List.of(Map.of("__typename", "Film", "filmId", 1))));
        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0)).containsEntry("__typename", "Film");
        assertThat((String) entities.get(0).get("titleTitlecase"))
            .as("TableRecord-sourced service child resolves when its key is supplied only via the "
                + "representation and its own batched fetch supplies the non-key data")
            .isEqualTo("Academy Dinosaur");
    }

    /**
     * Compound key batching: multiple reps of the same {@code (typename, alternative,
     * tenantId)} batch through the {@code VALUES (idx, ...)} derived table and scatter back to
     * their rep positions.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_compoundKeyBatch_resolvesEachRepInPosition() {
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename ... on FilmActor { filmId actorId } } }",
            Map.of("reps", List.of(
                Map.of("__typename", "FilmActor", "filmId", 1, "actorId", 1),
                Map.of("__typename", "FilmActor", "filmId", 1, "actorId", 2),
                Map.of("__typename", "FilmActor", "filmId", 2, "actorId", 1))));
        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).extracting(e -> e.get("filmId"), e -> e.get("actorId"))
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(1, 1),
                org.assertj.core.groups.Tuple.tuple(1, 2),
                org.assertj.core.groups.Tuple.tuple(2, 1));
    }
}
