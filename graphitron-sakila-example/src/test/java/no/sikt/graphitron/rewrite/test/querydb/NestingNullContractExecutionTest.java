package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The null contract of a nesting field against a real PostgreSQL database: a nullable field
 * returning a plain nesting type resolves to {@code null} when every column its type reads is
 * null, decided over all the type's fields whatever the query selects, and a non-null field
 * returning the same type always resolves to an object. Fixture: {@code PivotNestingHost.labels}
 * and {@code labelsRequired}, both {@code HostLabels} over {@code pivot_nesting_host.nn} and
 * {@code nb}; host 1 carries both columns, host 2 neither, host 3 only {@code nb}. A reference
 * counts by the row it reaches: {@code PivotNestingHost.origin} is a {@code HostOrigin} holding
 * only references from {@code pivot_nesting_host.language_id}, which reaches a language on host 1
 * and nothing on hosts 2 and 3. A scalar {@code @externalField} counts as a column does:
 * {@code labelsComputed}, its same-row twin {@code labelsComputedRow}, and {@code labelsMixed} (one
 * column, one {@code @externalField}) read the same two columns through expressions, and agree with
 * {@code labels} on every host. The references-and-lists cases over a join column that is always
 * set are {@code TableChildNullContractExecutionTest}'s.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class NestingNullContractExecutionTest {

    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        dsl = SakilaDatabase.dsl();

        graphql = Graphitron.newGraphQL().build();
    }


    /** The hosts keyed by {@code hostId}, each carrying {@code selection}. */
    private Map<Integer, Map<String, Object>> hosts(String selection) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user")
            .query("{ pivotHosts { hostId " + selection + " } }").build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        var byId = new HashMap<Integer, Map<String, Object>>();
        for (var host : (List<Map<String, Object>>) ((Map<String, Object>) result.getData()).get("pivotHosts")) {
            byId.put((Integer) host.get("hostId"), host);
        }
        return byId;
    }

    @Test
    void nullableWrapper_withEveryColumnNull_resolvesToNull() {
        var host = hosts("labels { nn nb }").get(2);
        assertThat(host).containsEntry("labels", null);
    }

    @Test
    void nonNullWrapper_withEveryColumnNull_resolvesToAnObjectOfNulls() {
        var labels = (Map<String, Object>) hosts("labelsRequired { nn nb }").get(2).get("labelsRequired");
        assertThat(labels).containsEntry("nn", null).containsEntry("nb", null);
    }

    @Test
    void nullableWrapper_withColumnsPresent_resolvesToAnObject() {
        var labels = (Map<String, Object>) hosts("labels { nn nb }").get(1).get("labels");
        assertThat(labels).containsEntry("nn", "nn-vert").containsEntry("nb", "nb-vert");
    }

    @Test
    void presence_isDecidedOverEveryField_notTheSelection() {
        // Host 3 carries nb only; selecting just nn still resolves the wrapper to an object, so a
        // field's nullness never depends on its own sub-selection.
        var labels = (Map<String, Object>) hosts("labels { nn }").get(3).get("labels");
        assertThat(labels).isNotNull().containsEntry("nn", null);
        assertThat(hosts("labels { nn }").get(2)).containsEntry("labels", null);
    }

    @Test
    void referenceOnlyWrapper_reachingNoRow_resolvesToNull() {
        assertThat(hosts("origin { languageName language { name } }").get(2)).containsEntry("origin", null);
    }

    @Test
    void referenceOnlyWrapper_reachingARow_resolvesToAnObject() {
        var origin = (Map<String, Object>) hosts("origin { languageName language { name } }").get(1).get("origin");
        // language.name is char(20), so the value arrives space-padded.
        assertThat(((String) origin.get("languageName")).trim()).isEqualTo("Italian");
        assertThat(((String) ((Map<String, Object>) origin.get("language")).get("name")).trim()).isEqualTo("Italian");
    }

    @Test
    void referencePresence_isDecidedOverEveryField_notTheSelection() {
        // The scalar and the object reference both count whatever the query selects, so selecting
        // either agrees on whether origin is null.
        var byScalar = hosts("origin { languageName }");
        var byObject = hosts("origin { language { name } }");
        assertThat(byScalar.get(1).get("origin")).isNotNull();
        assertThat(byObject.get(1).get("origin")).isNotNull();
        assertThat(byScalar.get(2)).containsEntry("origin", null);
        assertThat(byObject.get(2)).containsEntry("origin", null);
    }

    @Test
    void computedOnlyWrapper_withEveryExpressionNull_resolvesToNull() {
        var byId = hosts("labelsComputed { nn nb }");
        assertThat(byId.get(2)).containsEntry("labelsComputed", null);
        var host3 = (Map<String, Object>) byId.get(3).get("labelsComputed");
        assertThat(host3).containsEntry("nn", null).containsEntry("nb", "nb-only");
        var host1 = (Map<String, Object>) byId.get(1).get("labelsComputed");
        assertThat(host1).containsEntry("nn", "nn-vert").containsEntry("nb", "nb-vert");
    }

    @Test
    void computedPresence_isDecidedOverEveryField_notTheSelection() {
        // Selecting nn alone still evaluates nb in the check: host 3 carries nb only and stays an
        // object, host 2 carries neither and is null, as under the full selection.
        var byId = hosts("labelsComputed { nn }");
        assertThat(byId.get(2)).containsEntry("labelsComputed", null);
        assertThat((Map<String, Object>) byId.get(3).get("labelsComputed")).isNotNull().containsEntry("nn", null);
    }

    @Test
    void computedOnlySameRowTwin_agreesWithTheNestingType() {
        var byId = hosts("labelsComputedRow { nn nb }");
        assertThat(byId.get(2)).containsEntry("labelsComputedRow", null);
        assertThat((Map<String, Object>) byId.get(3).get("labelsComputedRow"))
            .containsEntry("nn", null).containsEntry("nb", "nb-only");
        assertThat((Map<String, Object>) byId.get(1).get("labelsComputedRow"))
            .containsEntry("nn", "nn-vert").containsEntry("nb", "nb-vert");
        assertThat(hosts("labelsComputedRow { nn }").get(2)).containsEntry("labelsComputedRow", null);
    }

    @Test
    void columnAndComputedWrapper_agreesWithTheColumnWrapperOnEveryHost() {
        var mixed = hosts("labelsMixed { nn nb }");
        var columns = hosts("labels { nn nb }");
        for (var hostId : List.of(1, 2, 3)) {
            assertThat(mixed.get(hostId).get("labelsMixed")).as("host " + hostId)
                .isEqualTo(columns.get(hostId).get("labels"));
        }
        // Selecting the column alone, the computed nb still keeps host 3 an object.
        assertThat((Map<String, Object>) hosts("labelsMixed { nn }").get(3).get("labelsMixed"))
            .isNotNull().containsEntry("nn", null);
    }

    @Test
    void computedOnlyTableChild_inlineAndSplit_decidesOverEveryField() {
        // The inline multiset and the batched rows method carry the same null terms: host 1's
        // Italian row keeps the object even when only the null englishName is selected.
        for (var field : List.of("languageNames", "languageNamesSplit")) {
            var byId = hosts(field + " { englishName }");
            assertThat((Map<String, Object>) byId.get(1).get(field)).as(field)
                .isNotNull().containsEntry("englishName", null);
            assertThat(byId.get(2)).as(field).containsEntry(field, null);
            assertThat(byId.get(3)).as(field).containsEntry(field, null);
            var italian = (Map<String, Object>) hosts(field + " { italianName }").get(1).get(field);
            assertThat(((String) italian.get("italianName")).trim()).as(field).isEqualTo("Italian");
        }
    }
}
