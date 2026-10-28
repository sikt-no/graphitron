package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conditions answered from the seed data at the coordinates where they compose with something
 * else: generated conjuncts beside an authored FK-target condition, a non-key filter beside a
 * lookup's keys (root, inline child and batched child), and an authored condition on a field
 * inside a nesting type.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class ConditionExecutionTest {

    static DSLContext dsl;
    static GraphQL graphql;
    static final AtomicInteger STATEMENT_COUNT = new AtomicInteger();

    /** Address 1; the condition behind it ignores the id and keeps the Alberta addresses (1 and 3). */
    static final String ALBERTA_ADDRESS =
        no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Address", 1);

    @BeforeAll
    static void startDatabase() {
        dsl = SakilaDatabase.dsl();

        dsl.configuration().set(new org.jooq.impl.DefaultExecuteListenerProvider(
            new org.jooq.ExecuteListener() {
                @Override
                public void executeStart(org.jooq.ExecuteContext ctx) {
                    STATEMENT_COUNT.incrementAndGet();
                }
            }));
        graphql = Graphitron.newGraphQL().build();
    }


    @Test
    void generatedConjunctsBesideAuthoredFkTargetCondition_allApply() {
        // Alberta holds Smith and Jones (address 1) and Williams (address 3); Jones is inactive.
        var both = list(execute("{ customersByMultiFieldFilter(filter: { activebool: true,"
            + " addressId: \"" + ALBERTA_ADDRESS + "\" }) { lastName } }"), "customersByMultiFieldFilter");
        assertThat(both).extracting(c -> c.get("lastName")).containsExactly("Smith", "Williams");

        var all = list(execute("{ customersByMultiFieldFilter(filter: { firstName: \"Mary\", activebool: true,"
            + " addressId: \"" + ALBERTA_ADDRESS + "\" }) { lastName } }"), "customersByMultiFieldFilter");
        assertThat(all).extracting(c -> c.get("lastName")).containsExactly("Smith");
    }

    @Test
    void lookupWithAuthoredNonKeyFilter_nullsTheFilteredKeysSlot() {
        // Language 1 is English, language 2 is Italian: both keys match, the prefix filter drops 2.
        var languages = list(execute("{ languagesByKeyFiltered(language_id: [1, 2], name: \"En\") { name } }"),
            "languagesByKeyFiltered");
        assertThat(languages).extracting(l -> l == null ? null : name(l))
            .containsExactly("English", null);
    }

    @Test
    void lookupWithGeneratedNonKeyFilter_filteredKeyIsIndistinguishableFromAMiss() {
        // Keys [hit, hit-but-filtered, miss]: three keys in, three slots out, and the filtered key
        // at index 1 holds null exactly as the unmatched key at index 2 does.
        var languages = list(execute(
            "{ languagesByKeyGenerated(language_id: [1, 2, 99], name: \"English\") { languageId } }"),
            "languagesByKeyGenerated");
        assertThat(languages).extracting(l -> l == null ? null : l.get("languageId"))
            .containsExactly(1, null, null);
    }

    @Test
    void inlineChildLookupWithGeneratedFilter_keepsOnlyTheMatchingKey() {
        // Film 1's cast is actors 1 (PENELOPE) and 2 (NICK); actor 99 does not exist.
        var films = list(execute(
            "{ filmById(film_id: [\"1\"]) { actorsGenerated(actor_id: [1, 2, 99], first_name: \"PENELOPE\") { actorId } } }"),
            "filmById");
        assertThat(films).singleElement()
            .extracting(f -> (List<Map<String, Object>>) f.get("actorsGenerated"))
            .satisfies(actors -> assertThat(actors).extracting(a -> a.get("actorId")).containsExactly(1));
    }

    @Test
    void batchedChildLookupWithGeneratedFilter_keepsOnlyTheMatchingKey() {
        STATEMENT_COUNT.set(0);
        var films = list(execute(
            "{ filmById(film_id: [\"1\"]) { actorsBySplitLookupGenerated(actor_id: [1, 2, 99], first_name: \"PENELOPE\") { actorId } } }"),
            "filmById");
        assertThat(STATEMENT_COUNT.get()).as("the parent statement and one batched child statement").isEqualTo(2);
        assertThat(films).singleElement()
            .extracting(f -> (List<Map<String, Object>>) f.get("actorsBySplitLookupGenerated"))
            .satisfies(actors -> assertThat(actors).extracting(a -> a.get("actorId")).containsExactly(1));
    }

    @Test
    void authoredConditionInsideANestingType_filtersTheNestedReference() {
        // Film 1's language is English.
        assertThat(name(languageFiltered("En"))).isEqualTo("English");
        assertThat(languageFiltered("It")).isNull();
    }

    private Map<String, Object> languageFiltered(String prefix) {
        var films = list(execute("{ filmById(film_id: [\"1\"]) { inlineBundle { languageFiltered(name: \""
            + prefix + "\") { name } } } }"), "filmById");
        assertThat(films).hasSize(1);
        var bundle = (Map<String, Object>) films.get(0).get("inlineBundle");
        return (Map<String, Object>) bundle.get("languageFiltered");
    }

    /** {@code language.name} is {@code char(20)}, so it arrives blank-padded. */
    private static String name(Map<String, Object> language) {
        return ((String) language.get("name")).strip();
    }

    private static List<Map<String, Object>> list(Map<String, Object> data, String field) {
        return (List<Map<String, Object>>) data.get(field);
    }

    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }
}
