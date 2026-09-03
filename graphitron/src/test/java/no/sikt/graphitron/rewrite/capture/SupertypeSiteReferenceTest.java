package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jooq.DSLContext;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reference a collapsed subtype gave up, checked where the engine can no longer check it.
 *
 * <p>Two relations in the {@code graphitron_} family hold a fact several kinds of site spell, and
 * both reached that shape by absorbing relations that carried nothing of their own.
 * {@code graphitron_argmapping_entry} absorbed eight, {@code graphitron_method_reference} one. Each
 * absorbed relation had a foreign key into the directive that owned its rows, and no foreign key
 * can span the nine or eleven parents a discriminator column chooses between, so those edges are
 * not enforced by the schema any more. They are still true, because capture writes the shared row
 * inside the branch that has just written the owning directive's own row, and this is the test that
 * says so. Losing an enforced edge is the price of the collapse and the price is only worth paying
 * if somebody checks.
 *
 * <p>Written as a scan for orphans per site rather than as an assertion about one fixture's rows,
 * because what it guards is a rule and not a population: a site value with no owning row under it
 * is the defect, whatever schema produced it. The counts below are the guard against the other
 * failure, which is worse and quieter. An orphan scan over an empty relation passes, so a fixture
 * that stopped reaching a site would turn this gate off for that site without failing, and the
 * per-site population assertion is what makes that a failure instead.
 */
@PipelineTier
class SupertypeSiteReferenceTest {

    /**
     * One schema reaching every site both relations admit and that a schema can legally spell. Two
     * of the eleven method-reference sites are absent by construction rather than by omission: the
     * argument-site {@code @referenceFor} step has a coordinate the validator rejects, so no
     * captured store has one, and {@code ENUM} is covered here while its pair counterpart does not
     * exist, {@code @enum} taking no argMapping site of the pair kind.
     */
    private static final String FIXTURE = """
        type Film @table(name: "film") {
          title: String
          language: Language @sourceRow(
            className: "com.example.Rows", method: "language")
        }

        input FilmFilter {
          title: String @condition(condition: {
            className: "com.example.Conditions", method: "onInputField",
            argMapping: "needle: title"})
        }

        enum Rating @enum(enumReference: {
          className: "com.example.Ratings", method: "parse"}) { G PG }

        type Language @table(name: "language") {
          name: String
          films(filter: FilmFilter): [Film!]! @condition(condition: {
            className: "com.example.Conditions", method: "onField",
            argMapping: "needle: name"})
          external: String @externalField(reference: {
            className: "com.example.External", method: "fetch",
            argMapping: "key: name"})
        }

        type City @table(name: "city") {
          name: String
          films: [Film!]! @reference(path: [
            {table: "film", condition: {
              className: "com.example.Conditions", method: "onReferenceStep",
              argMapping: "needle: name"}}])
          related(kind: String): [Film!]! @reference(path: [
            {table: "film"}])
        }

        type Query {
          languages(filter: FilmFilter): [Language!]! @service(service: {
            className: "com.example.Services", method: "languages",
            argMapping: "needle: filter.title"})
          cities(kind: String @condition(condition: {
            className: "com.example.Conditions", method: "onArgument",
            argMapping: "needle: kind"})): [City!]!
        }
        """;

    /**
     * Each site of {@code graphitron_argmapping_entry} against the relation that owns its rows,
     * as the predicate joining the shared row {@code s} to an owner {@code d}. The two condition
     * sites share an owner, the owning type's kind being what splits them, so both point at the
     * one relation; a step site's owner is the application rather than the step, which is the same
     * choice the source position on these rows makes.
     */
    private static final Map<String, String> PAIR_OWNERS = new LinkedHashMap<>(Map.of(
        "ROUTINE",
        "graphitron_routine d WHERE d.graph_name = s.graph_name AND d.type_name = s.type_name"
            + " AND d.field_name = s.field_name AND d.ordinal = s.ordinal",
        "SERVICE",
        "graphitron_service d WHERE d.graph_name = s.graph_name AND d.type_name = s.type_name"
            + " AND d.field_name = s.field_name",
        "FIELD_CONDITION",
        "graphitron_field_condition d WHERE d.graph_name = s.graph_name"
            + " AND d.type_name = s.type_name AND d.field_name = s.field_name",
        "INPUT_FIELD_CONDITION",
        "graphitron_field_condition d WHERE d.graph_name = s.graph_name"
            + " AND d.type_name = s.type_name AND d.field_name = s.field_name",
        "ARGUMENT_CONDITION",
        "graphitron_argument_condition d WHERE d.graph_name = s.graph_name"
            + " AND d.type_name = s.type_name AND d.field_name = s.field_name"
            + " AND d.argument_name = s.argument_name",
        "FIELD_REFERENCE_STEP",
        "graphitron_field_reference_step d WHERE d.graph_name = s.graph_name"
            + " AND d.type_name = s.type_name AND d.field_name = s.field_name"
            + " AND d.ordinal = s.ordinal AND d.position = s.step_position",
        "ARGUMENT_REFERENCE_STEP",
        "graphitron_argument_reference_step d WHERE d.graph_name = s.graph_name"
            + " AND d.type_name = s.type_name AND d.field_name = s.field_name"
            + " AND d.argument_name = s.argument_name AND d.ordinal = s.ordinal"
            + " AND d.position = s.step_position",
        "REFERENCE_FOR_STEP",
        "graphitron_reference_for_step d WHERE d.graph_name = s.graph_name"
            + " AND d.type_name = s.type_name AND d.field_name = s.field_name"
            + " AND d.ordinal = s.ordinal AND d.position = s.step_position",
        "ARGUMENT_REFERENCE_FOR_STEP",
        "graphitron_argument_reference_for_step d WHERE d.graph_name = s.graph_name"
            + " AND d.type_name = s.type_name AND d.field_name = s.field_name"
            + " AND d.argument_name = s.argument_name AND d.ordinal = s.ordinal"
            + " AND d.position = s.step_position"));

    /**
     * The same, for {@code graphitron_method_reference}. {@code SOURCE_ROW} is absent by design and
     * not by oversight: its subtype is the one that collapsed, so there is no owning relation left
     * to point at and nothing for an orphan scan to check. That site's correctness is the
     * population assertion's alone.
     */
    private static final Map<String, String> METHOD_OWNERS = new LinkedHashMap<>(Map.of(
        "ENUM",
        "graphitron_enum d WHERE d.graph_name = s.graph_name AND d.type_name = s.type_name",
        "SERVICE",
        "graphitron_service d WHERE d.graph_name = s.graph_name AND d.type_name = s.type_name"
            + " AND d.field_name = s.field_name",
        "EXTERNAL_FIELD",
        "graphitron_external_field d WHERE d.graph_name = s.graph_name"
            + " AND d.type_name = s.type_name AND d.field_name = s.field_name",
        "FIELD_CONDITION",
        "graphitron_field_condition d WHERE d.graph_name = s.graph_name"
            + " AND d.type_name = s.type_name AND d.field_name = s.field_name",
        "INPUT_FIELD_CONDITION",
        "graphitron_field_condition d WHERE d.graph_name = s.graph_name"
            + " AND d.type_name = s.type_name AND d.field_name = s.field_name",
        "ARGUMENT_CONDITION",
        "graphitron_argument_condition d WHERE d.graph_name = s.graph_name"
            + " AND d.type_name = s.type_name AND d.field_name = s.field_name"
            + " AND d.argument_name = s.argument_name",
        "FIELD_REFERENCE_STEP",
        "graphitron_field_reference_step d WHERE d.graph_name = s.graph_name"
            + " AND d.type_name = s.type_name AND d.field_name = s.field_name"
            + " AND d.ordinal = s.ordinal AND d.position = s.step_position",
        "ARGUMENT_REFERENCE_STEP",
        "graphitron_argument_reference_step d WHERE d.graph_name = s.graph_name"
            + " AND d.type_name = s.type_name AND d.field_name = s.field_name"
            + " AND d.argument_name = s.argument_name AND d.ordinal = s.ordinal"
            + " AND d.position = s.step_position",
        "REFERENCE_FOR_STEP",
        "graphitron_reference_for_step d WHERE d.graph_name = s.graph_name"
            + " AND d.type_name = s.type_name AND d.field_name = s.field_name"
            + " AND d.ordinal = s.ordinal AND d.position = s.step_position",
        "ARGUMENT_REFERENCE_FOR_STEP",
        "graphitron_argument_reference_for_step d WHERE d.graph_name = s.graph_name"
            + " AND d.type_name = s.type_name AND d.field_name = s.field_name"
            + " AND d.argument_name = s.argument_name AND d.ordinal = s.ordinal"
            + " AND d.position = s.step_position"));

    @Test
    @DisplayName("every argMapping pair resolves to the directive application that spelled it")
    void everyPairReachesItsSite(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            assertNoOrphans(store.dsl(), "graphitron_argmapping_entry", PAIR_OWNERS);
        }
    }

    @Test
    @DisplayName("every named method resolves to the directive application that named it")
    void everyMethodReferenceReachesItsSite(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            assertNoOrphans(store.dsl(), "graphitron_method_reference", METHOD_OWNERS);
        }
    }

    /**
     * The fixture reaches the sites the scans above are written for. Without this the gate could
     * be switched off one site at a time by a fixture that stopped producing rows, and an orphan
     * scan finding nothing would report that as success.
     */
    @Test
    @DisplayName("the fixture populates the sites both scans claim to cover")
    void theFixtureReachesTheSitesTheScansCover(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            assertThat(sites(store.dsl(), "graphitron_argmapping_entry"))
                .as("argMapping pair sites this fixture reaches")
                .contains("SERVICE", "FIELD_CONDITION", "INPUT_FIELD_CONDITION",
                    "ARGUMENT_CONDITION", "FIELD_REFERENCE_STEP");
            assertThat(sites(store.dsl(), "graphitron_method_reference"))
                .as("method reference sites this fixture reaches")
                .contains("ENUM", "SERVICE", "EXTERNAL_FIELD", "SOURCE_ROW", "FIELD_CONDITION",
                    "INPUT_FIELD_CONDITION", "ARGUMENT_CONDITION", "FIELD_REFERENCE_STEP");
        }
    }

    /**
     * The two relations spell one site the same way. This is the invariant the method-reference
     * supertype was shaped around and the one an orphan scan cannot see: those scans join on the
     * decomposed columns, so a row whose {@code use_site} disagreed with them would resolve to its
     * owner and still be wrong. What would break is the join
     * {@code intent_argmapping_bound_parameter_type} now makes, which reaches a pair's method
     * through {@code (site, use_site)} and nothing else, so a disagreement there is six arms of a
     * reconstruction silently returning fewer rows than they used to.
     *
     * <p>Checked by rebuilding the spelling from the columns beside it rather than by comparing the
     * two relations to each other, which would pass if both were wrong the same way.
     */
    @Test
    @DisplayName("use_site is the spelling its own decomposed columns imply, in both relations")
    void theUseSiteAgreesWithItsColumns(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            for (String relation : java.util.List.of(
                    "graphitron_argmapping_entry", "graphitron_method_reference")) {
                var wrong = store.dsl().fetch(
                    "SELECT site, use_site FROM " + relation
                        + " WHERE use_site <> type_name"
                        + "   || COALESCE('.' || field_name, '')"
                        + "   || COALESCE('(' || argument_name || ')', '')"
                        + "   || COALESCE('#' || CAST(ordinal AS VARCHAR), '')"
                        + "   || COALESCE('[' || CAST(step_position AS VARCHAR) || ']', '')");
                assertThat(wrong)
                    .as("%s rows whose use_site disagrees with its own columns", relation)
                    .isEmpty();
            }
        }
    }

    private static void assertNoOrphans(DSLContext dsl, String relation, Map<String, String> owners) {
        owners.forEach((site, owner) -> {
            int orphans = dsl.fetchOne(
                "SELECT COUNT(*) FROM " + relation + " s"
                    + " WHERE s.site = ? AND NOT EXISTS (SELECT 1 FROM " + owner + ")",
                site).into(int.class);
            assertThat(orphans)
                .as("%s rows at site %s with no owning application", relation, site)
                .isZero();
        });
    }

    private static java.util.List<String> sites(DSLContext dsl, String relation) {
        return dsl.fetch("SELECT DISTINCT site FROM " + relation).into(String.class);
    }
}
