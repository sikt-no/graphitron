package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Execution-tier proof that a GraphQL response key names a field only within its own selection
 * set: each generated projection sees exactly the fields selected on its own type at its own
 * level. A key reused for different fields under different parents resolves (it used to be
 * rejected as "selects two different fields"), a key selected below a nesting field projects
 * nothing on the nesting type itself (it used to project the unselected same-named leaf), and the
 * same holds across type conditions in {@code Query.nodes} and the single-table discriminated
 * fold.
 *
 * <p>The second half is the row a nesting field's children are read from: a nesting type has no
 * table of its own, so every selection set spliced into one SQL row (sibling nesting fields, one
 * nesting field under two aliases, nesting inside nesting, two participants' nesting fields in the
 * fold) writes its result-key-aliased terms under the path of result keys to it, and reads them
 * back under the same path, so no reader is handed another selection set's value.
 *
 * <p>Fixture rows ({@code init.sql}): active customers Mary (address 1, Alberta), Patricia
 * (address 2, Lazio) and Linda (address 3, Alberta); store 1 owns Mary, Patricia and Barbara;
 * content rows are two FILM rows ('ACADEMY DINOSAUR (extended)', 'ACE GOLDFINGER (extended)') and
 * two SHORT rows with descriptions 'Dawn over a city' and 'Quiet jazz piece'. A key reused across
 * type conditions must name fields of one GraphQL type (field-merging validation), so the
 * cross-participant cases reuse it for {@code FilmContent.heading} (the title column) and
 * {@code ShortContent.description}, both nullable String.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class ResultKeyScopeExecutionTest {

    static DSLContext dsl;
    static GraphQL graphql;

    /** The top-level field names of every result the statements under test returned. */
    static final List<List<String>> RESULT_FIELDS = new ArrayList<>();

    @BeforeAll
    static void startDatabase() {
        dsl = SakilaDatabase.dsl();

        // What the database was asked to return, not how the generator spelled it.
        dsl.configuration().set(new org.jooq.impl.DefaultExecuteListenerProvider(
            new org.jooq.ExecuteListener() {
                @Override
                public void fetchEnd(org.jooq.ExecuteContext ctx) {
                    if (ctx.result() != null) {
                        synchronized (RESULT_FIELDS) {
                            RESULT_FIELDS.add(Arrays.stream(ctx.result().fields()).map(Field::getName).toList());
                        }
                    }
                }
            }));
        graphql = Graphitron.newGraphQL().build();
    }


    // ===== No over-projection =====

    @Test
    void keyBelowANestingField_projectsNothingOnTheNestingTypeItself() {
        RESULT_FIELDS.clear();
        var customers = list(execute(
            "{ customers(active: true) { location { address { district } } } }"), "customers");
        assertThat(customers).extracting(c -> map(map(c, "location"), "address").get("district"))
            .containsExactly("Alberta", "Lazio", "Alberta");
        assertThat(RESULT_FIELDS).as("one statement").hasSize(1);
        assertThat(RESULT_FIELDS.get(0))
            .as("the district under address is not OccupantLocation's own district; the customer"
                + " statement carries no term for the unselected leaf")
            .noneMatch(name -> name.endsWith("district"));
    }

    @Test
    void sameNameOnTheNestingTypeAndBelowIt_eachResolvesFromItsOwnSelection() {
        RESULT_FIELDS.clear();
        var customers = list(execute(
            "{ customers(active: true) { location { district address { district } } } }"), "customers");
        assertThat(customers)
            .extracting(c -> map(c, "location").get("district"),
                c -> map(map(c, "location"), "address").get("district"))
            .containsExactly(tuple("Alberta", "Alberta"), tuple("Lazio", "Lazio"), tuple("Alberta", "Alberta"));
        assertThat(RESULT_FIELDS.get(0))
            .as("selected on the nesting type itself, the leaf is projected")
            .anyMatch(name -> name.endsWith("district"));
    }

    // ===== No rejection: a key reused under different parents =====

    @Test
    void plainList_keyReusedOnTheParentAndAnInlineChild() {
        var customers = list(execute(
            "{ customers(active: true) { k: firstName address { k: district } } }"), "customers");
        assertThat(customers).extracting(c -> c.get("k"), c -> map(c, "address").get("k"))
            .containsExactly(tuple("Mary", "Alberta"), tuple("Patricia", "Lazio"), tuple("Linda", "Alberta"));
    }

    @Test
    void unaliased_anOuterFieldNameReusedAsTheAliasOfAChildsDifferentField() {
        var customers = list(execute(
            "{ customers(active: true) { firstName address { firstName: district } } }"), "customers");
        assertThat(customers).extracting(c -> c.get("firstName"), c -> map(c, "address").get("firstName"))
            .containsExactly(tuple("Mary", "Alberta"), tuple("Patricia", "Lazio"), tuple("Linda", "Alberta"));
    }

    @Test
    void connection_keyReusedOnTheNodeAndAListValuedChild() {
        var stores = (List<Map<String, Object>>) map(execute(
            "{ stores { nodes { k: storeId customers { k: firstName } } } }"), "stores").get("nodes");
        var store1 = stores.stream().filter(s -> Integer.valueOf(1).equals(s.get("k"))).findFirst().orElseThrow();
        assertThat(list(store1, "customers")).extracting(c -> c.get("k"))
            .containsExactly("Mary", "Patricia", "Barbara");
    }

    @Test
    void connection_keyReusedUnderBothRelayPathsAndTheirChildren() {
        var conn = map(execute(
            "{ stores { edges { node { k: storeId customers { k: lastName } } } nodes { k: storeId } } }"),
            "stores");
        var edgeStore1 = list(conn, "edges").stream().map(e -> map(e, "node"))
            .filter(s -> Integer.valueOf(1).equals(s.get("k"))).findFirst().orElseThrow();
        assertThat(list(edgeStore1, "customers")).extracting(c -> c.get("k"))
            .containsExactly("Smith", "Johnson", "Jones");
        assertThat(list(conn, "nodes")).extracting(s -> s.get("k")).contains(1, 2);
    }

    @Test
    void nodes_keyReusedForDifferentFieldsAcrossTypeConditions() {
        var customerId = (String) list(execute("{ customers(active: true) { id } }"), "customers").get(0).get("id");
        var filmId = (String) list(execute("{ filmsOrdered { id } }"), "filmsOrdered").get(0).get("id");
        var nodes = list(execute("""
            { nodes(ids: ["%s", "%s"]) { ... on Customer { k: firstName } ... on Film { k: title } } }
            """.formatted(customerId, filmId)), "nodes");
        assertThat(nodes).extracting(n -> n.get("k")).containsExactly("Mary", "ACADEMY DINOSAUR");
    }

    // ===== The single-table discriminated fold =====

    @Test
    void discriminatedFold_keyReusedForDifferentColumnsAcrossParticipants() {
        var content = list(execute("""
            { allContent { __typename ... on FilmContent { k: heading } ... on ShortContent { k: description } } }
            """), "allContent");
        assertThat(content).extracting(c -> c.get("__typename"), c -> c.get("k"))
            .contains(
                tuple("FilmContent", "ACADEMY DINOSAUR (extended)"),
                tuple("FilmContent", "ACE GOLDFINGER (extended)"),
                tuple("ShortContent", "Dawn over a city"), tuple("ShortContent", "Quiet jazz piece"));
    }

    @Test
    void discriminatedFoldConnection_keyReusedForDifferentColumnsAcrossParticipants() {
        var content = list(map(execute("""
            { allContentConnection { nodes { __typename ... on FilmContent { k: heading } ... on ShortContent { k: description } } } }
            """), "allContentConnection"), "nodes");
        assertThat(content).extracting(c -> c.get("__typename"), c -> c.get("k"))
            .contains(
                tuple("FilmContent", "ACADEMY DINOSAUR (extended)"),
                tuple("FilmContent", "ACE GOLDFINGER (extended)"),
                tuple("ShortContent", "Dawn over a city"), tuple("ShortContent", "Quiet jazz piece"));
    }

    // ===== No cross-wiring: selection sets spliced into one row =====

    @Test
    void siblingNestingFields_eachReadTheirOwnSameNamedLeaf() {
        // Both nesting types declare `language` and both are spliced into the film's row; every
        // seeded film has an English language, and films 1, 2, 3 and 5 an original language that
        // mostly differs from it. Film 4 has none, and an all-null nesting object collapses to
        // null, which is how its original side answers. `original` is selected first, so a
        // collapse of the two terms onto one alias would hand `spoken` the original's term.
        var films = list(execute(
            "{ filmsOrdered { filmId original { language { name } } spoken { language { name } } } }"),
            "filmsOrdered");
        assertThat(films).isNotEmpty().allSatisfy(film ->
            assertThat(name(map(map(film, "spoken"), "language"))).isEqualTo("English"));
        var originals = new java.util.HashMap<Object, String>();
        for (var film : films) {
            var original = map(film, "original");
            originals.put(film.get("filmId"), original == null ? null : name(map(original, "language")));
        }
        assertThat(originals)
            .containsEntry(1, "Japanese")
            .containsEntry(2, "Italian")
            .containsEntry(3, "Italian")
            .containsEntry(4, null)
            .containsEntry(5, "English");
    }

    @Test
    void oneNestingFieldUnderTwoAliases_eachAliasReadsItsOwnSubSelection() {
        var films = list(execute(
            "{ filmsOrdered { a: spoken { language { name } } b: spoken { language { languageId } } } }"),
            "filmsOrdered");
        assertThat(films).isNotEmpty().allSatisfy(film -> {
            assertThat(map(map(film, "a"), "language")).containsOnlyKeys("name");
            assertThat(name(map(map(film, "a"), "language"))).isEqualTo("English");
            assertThat(map(map(film, "b"), "language")).containsExactly(Map.entry("languageId", 1));
        });
    }

    @Test
    void nestedNesting_aKeyReusedAtTwoDepthsOfOneRow() {
        // projected and projected.inner both splice into the film's row; the outer `a` and the
        // inner `a` are different fields.
        var films = list(execute(
            "{ filmsOrdered { projected { a: languageName inner { a: isEnglish } } } }"), "filmsOrdered");
        assertThat(films).isNotEmpty().allSatisfy(film -> {
            assertThat(((String) map(film, "projected").get("a")).trim()).isEqualTo("English");
            assertThat(map(map(film, "projected"), "inner").get("a")).isEqualTo(true);
        });
    }

    @Test
    void sharedNestingType_twoAliasesResolveUnderEitherAnchor() {
        // One nesting type registered once, read under two anchors: the write side threads the
        // path through the anchor's splice, the read side composes it from the step chain.
        var customers = list(execute(
            "{ customers(active: true) { a: location { address { addressId } } b: location { address { district } } } }"),
            "customers");
        assertThat(customers)
            .extracting(c -> map(map(c, "a"), "address").get("addressId"),
                c -> map(map(c, "b"), "address").get("district"))
            .containsExactly(tuple(1, "Alberta"), tuple(2, "Lazio"), tuple(3, "Alberta"));

        var stores = (List<Map<String, Object>>) map(execute(
            "{ stores { nodes { a: location { address { addressId } } b: location { address { district } } } } }"),
            "stores").get("nodes");
        assertThat(stores)
            .extracting(s -> map(map(s, "a"), "address").get("addressId"),
                s -> map(map(s, "b"), "address").get("district"))
            .contains(tuple(1, "Alberta"), tuple(2, "Lazio"));
    }

    @Test
    void discriminatedFold_participantsSplicingTheSameLeafNameReadTheirOwn() {
        // Each participant's nesting field splices a `target` leaf into the fold's one row over
        // its own FK; the splice chain keeps the two aliases apart.
        var items = list(execute("""
            { allFanItems { label
                ... on FanAlpha { alphaPlacement { target { targetLabel } } }
                ... on FanBeta { betaPlacement { target { targetLabel } } } } }
            """), "allFanItems");
        assertThat(items).extracting(i -> i.get("label"), i -> map(map(i,
                i.containsKey("alphaPlacement") ? "alphaPlacement" : "betaPlacement"), "target").get("targetLabel"))
            .contains(
                tuple("Alpha one", "Alpha target"), tuple("Alpha two", "Alpha target"),
                tuple("Beta one", "Beta target"), tuple("Beta two", "Beta target"));
    }

    @Test
    void discriminatedFold_oneKeyForTwoParticipantsNestingFields_staysRejected() {
        // The carve-out: two different fields that write a shared alias in the fold's one row,
        // under one key, would collapse to one term; canonical rejects them loudly instead.
        var result = executeRaw("""
            { allFanItems {
                ... on FanAlpha { k: alphaPlacement { target { targetLabel } } }
                ... on FanBeta { k: betaPlacement { target { targetLabel } } } } }
            """);
        assertThat(result.getErrors()).isNotEmpty()
            .anySatisfy(e -> assertThat(e.getMessage())
                .contains("Result key 'k' selects two different fields"));
    }

    // ===== helpers =====

    /** {@code language.name} is a fixed-width {@code char(20)} column. */
    private static String name(Map<String, Object> language) {
        return ((String) language.get("name")).trim();
    }

    private static Map<String, Object> map(Map<String, Object> parent, String field) {
        return (Map<String, Object>) parent.get(field);
    }

    private static List<Map<String, Object>> list(Map<String, Object> parent, String field) {
        return (List<Map<String, Object>>) parent.get(field);
    }

    private Map<String, Object> execute(String query) {
        var result = executeRaw(query);
        assertThat(result.getErrors()).as(query).isEmpty();
        return result.getData();
    }

    private ExecutionResult executeRaw(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        return graphql.execute(input);
    }
}
