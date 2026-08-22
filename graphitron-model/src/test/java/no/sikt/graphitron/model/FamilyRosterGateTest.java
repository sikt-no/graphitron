package no.sikt.graphitron.model;

import no.sikt.graphitron.model.test.FactStores;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static no.sikt.graphitron.model.Tables.META_FAMILY;
import static no.sikt.graphitron.model.Tables.META_FAMILY_BRIDGE;
import static no.sikt.graphitron.model.Tables.META_FAMILY_HEADLINE;
import static no.sikt.graphitron.model.Tables.META_RELATION_FAMILY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closes the two authored rosters the family pages open with against the observed schema, the
 * shape {@code meta_relation_family} already uses for the family roster itself and
 * {@link MaterializeRegistryGateTest} uses for the materialization registry.
 *
 * <p>Resolve gates only, deliberately. That a headline names a relation the schema declares, and
 * that a bridge names two families the roster carries, are claims a declaration can be held to on
 * its own. Whether the declared crossings cover every crossing the views actually perform is a
 * derivation over what the view definitions read, which no reading of the declarations can
 * answer, so nothing here pretends to it.
 *
 * <p>Prose is beyond every gate here past non-blankness: an introduction that names a relation, or
 * a rule sentence that restates a view body instead of the rule it spells, is caught by the column
 * comments saying so and by review, not by a test that cannot read intent.
 */
class FamilyRosterGateTest {

    @Test
    @DisplayName("every family carries a non-blank introduction")
    void everyFamilyIntroducesItself() {
        withStore(dsl -> {
            var offenders = dsl.select(META_FAMILY.PREFIX, META_FAMILY.INTRODUCTION)
                .from(META_FAMILY)
                .fetch().stream()
                .filter(row -> row.value2() == null || row.value2().isBlank())
                .map(row -> row.value1())
                .toList();
            assertThat(offenders)
                .as("families whose page would open with nothing; the introduction is what the"
                    + " reference leads with and the charter no longer is")
                .isEmpty();
        });
    }

    @Test
    @DisplayName("every headline resolves to an observed relation the census places in a family")
    void everyHeadlineResolves() {
        withStore(dsl -> {
            var placed = new LinkedHashMap<String, String>();
            dsl.select(META_RELATION_FAMILY.RELATION_NAME, META_RELATION_FAMILY.PREFIX)
                .from(META_RELATION_FAMILY)
                .fetch()
                .forEach(row -> placed.put(row.value1(), row.value2()));
            var offenders = new ArrayList<String>();
            for (String relation : headlineNames(dsl)) {
                if (!placed.containsKey(relation)) {
                    offenders.add(relation + " is a headline of no relation the schema declares");
                } else if (placed.get(relation) == null) {
                    offenders.add(relation + " is a headline but the census places it in no family");
                }
            }
            assertThat(offenders).as("headline rows the census cannot place").isEmpty();
        });
    }

    /**
     * Density rather than uniqueness, the convention the schema gates keep throughout: a roster
     * of 0, 1, 3 is unique and still says a row was dropped, and the roster is short enough that
     * nothing but the gate would notice.
     */
    @Test
    @DisplayName("headline ordinals are dense from zero within each family")
    void headlineOrdinalsAreDenseWithinEachFamily() {
        withStore(dsl -> {
            var byFamily = new LinkedHashMap<String, List<Integer>>();
            dsl.select(META_RELATION_FAMILY.PREFIX, META_FAMILY_HEADLINE.ORDINAL)
                .from(META_FAMILY_HEADLINE)
                .join(META_RELATION_FAMILY)
                .on(META_RELATION_FAMILY.RELATION_NAME.eq(META_FAMILY_HEADLINE.RELATION_NAME))
                .orderBy(META_FAMILY_HEADLINE.ORDINAL)
                .fetch()
                .forEach(row -> byFamily.computeIfAbsent(row.value1(), k -> new ArrayList<>())
                    .add(row.value2()));
            var offenders = new ArrayList<String>();
            byFamily.forEach((prefix, ordinals) -> {
                var expected = IntStream.range(0, ordinals.size()).boxed().toList();
                if (!ordinals.equals(expected)) {
                    offenders.add(prefix + " has headline ordinals " + ordinals
                        + ", expected " + expected);
                }
            });
            assertThat(offenders).as("families whose headline ordinals are not dense from zero")
                .isEmpty();
        });
    }

    @Test
    @DisplayName("every family has at least one headline")
    void everyFamilyStartsSomewhere() {
        withStore(dsl -> {
            var withHeadline = dsl.selectDistinct(META_RELATION_FAMILY.PREFIX)
                .from(META_FAMILY_HEADLINE)
                .join(META_RELATION_FAMILY)
                .on(META_RELATION_FAMILY.RELATION_NAME.eq(META_FAMILY_HEADLINE.RELATION_NAME))
                .fetch(0, String.class);
            assertThat(dsl.select(META_FAMILY.PREFIX).from(META_FAMILY).fetch(0, String.class))
                .as("families whose page would render a start-here section with nothing in it;"
                    + " a single-relation family lists its one resident")
                .allSatisfy(prefix -> assertThat(withHeadline).contains(prefix));
        });
    }

    @Test
    @DisplayName("every bridge names an observed relation, two rostered distinct families, a rule")
    void everyBridgeResolves() {
        withStore(dsl -> {
            var observed = dsl.select(META_RELATION_FAMILY.RELATION_NAME).from(META_RELATION_FAMILY)
                .fetch(0, String.class);
            var rostered = dsl.select(META_FAMILY.PREFIX).from(META_FAMILY).fetch(0, String.class);
            var offenders = new ArrayList<String>();
            dsl.select(META_FAMILY_BRIDGE.RELATION_NAME, META_FAMILY_BRIDGE.SPELLED_PREFIX,
                    META_FAMILY_BRIDGE.CENSUS_PREFIX, META_FAMILY_BRIDGE.RULE)
                .from(META_FAMILY_BRIDGE)
                .fetch()
                .forEach(row -> {
                    if (!observed.contains(row.value1())) {
                        offenders.add(row.value1() + " owns a declared crossing but the schema"
                            + " declares no such relation");
                    }
                    if (!rostered.contains(row.value2())) {
                        offenders.add(row.value1() + " is spelled in " + row.value2()
                            + ", which is no family the roster carries");
                    }
                    if (!rostered.contains(row.value3())) {
                        offenders.add(row.value1() + " is matched against " + row.value3()
                            + ", which is no family the roster carries");
                    }
                    if (row.value2().equals(row.value3())) {
                        offenders.add(row.value1() + " declares " + row.value2()
                            + " on both sides, which is a normalization inside one family rather"
                            + " than a crossing between two");
                    }
                    if (row.value4() == null || row.value4().isBlank()) {
                        offenders.add(row.value1() + " states no rule, so the section would render"
                            + " a crossing a reader cannot read");
                    }
                });
            assertThat(offenders).as("bridge rows that do not resolve").isEmpty();
        });
    }

    // ===== Reading the observed schema =====

    private static void withStore(Consumer<DSLContext> body) {
        try (var store = FactStores.inMemory()) {
            body.accept(store.dsl());
        }
    }

    private static List<String> headlineNames(DSLContext dsl) {
        return dsl.select(META_FAMILY_HEADLINE.RELATION_NAME)
            .from(META_FAMILY_HEADLINE)
            .fetch(0, String.class);
    }
}
