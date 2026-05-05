package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural tests for {@link QueryConditionsGenerator#computeLiftedOuters}: the per-method
 * outer-arg lift used by R79 §5 to dedupe {@code env.getArgument(outer) instanceof Map<?, ?> _m1}
 * rebinds when ≥2 NestedInputField call params share an outer arg.
 */
@UnitTier
class QueryConditionsGeneratorLiftTest {

    private static final TableRef FILM = TestFixtures.tableRef("film", "FILM", "Film", List.of());

    private static CallParam nestedDirect(String paramName, String outerArg) {
        return new CallParam(paramName,
            new CallSiteExtraction.NestedInputField(outerArg, List.of(paramName)),
            false, "java.lang.String");
    }

    private static WhereFilter filterWith(CallParam... params) {
        return new GeneratedConditionFilter(
            "FilmConditions", "filmCondition", FILM, List.of(params), List.of());
    }

    @Test
    void computeLiftedOuters_twoBodyParamsShareOuter_lifts() {
        var filter = filterWith(
            nestedDirect("filmId", "filter"),
            nestedDirect("title", "filter"));
        Map<String, String> lifted = QueryConditionsGenerator.computeLiftedOuters(List.of(filter));
        assertThat(lifted).containsExactly(Map.entry("filter", "filterMap"));
    }

    @Test
    void computeLiftedOuters_singleBodyParam_doesNotLift() {
        var filter = filterWith(nestedDirect("filmId", "filter"));
        assertThat(QueryConditionsGenerator.computeLiftedOuters(List.of(filter))).isEmpty();
    }

    @Test
    void computeLiftedOuters_distinctOuterArgs_doesNotLift() {
        var filter = filterWith(
            nestedDirect("filmId", "filter"),
            nestedDirect("title", "search"));
        assertThat(QueryConditionsGenerator.computeLiftedOuters(List.of(filter))).isEmpty();
    }

    @Test
    void computeLiftedOuters_acrossMultipleFilters_counts() {
        var f1 = filterWith(nestedDirect("filmId", "filter"));
        var f2 = filterWith(nestedDirect("title", "filter"));
        Map<String, String> lifted = QueryConditionsGenerator.computeLiftedOuters(List.of(f1, f2));
        assertThat(lifted).containsExactly(Map.entry("filter", "filterMap"));
    }

    @Test
    void computeLiftedOuters_camelCasesOuterArgName() {
        var filter = filterWith(
            nestedDirect("filmId", "search_input"),
            nestedDirect("title", "search_input"));
        Map<String, String> lifted = QueryConditionsGenerator.computeLiftedOuters(List.of(filter));
        assertThat(lifted).containsExactly(Map.entry("search_input", "searchInputMap"));
    }
}
