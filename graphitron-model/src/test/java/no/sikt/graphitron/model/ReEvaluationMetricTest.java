package no.sikt.graphitron.model;

import no.sikt.graphitron.model.test.ScratchSchema;
import no.sikt.graphitron.model.test.ReEvaluationMetric;
import no.sikt.graphitron.model.test.ReEvaluationMetric.Weighting;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The metric against bodies whose instantiation counts are derivable by hand from the bodies
 * themselves.
 *
 * <p>Every case here forces the weights to one, which turns the metric back into a pure count of
 * rule evaluations. That is deliberate and it is what makes the assertions checkable: with weights
 * on, an expected value would have to be computed by something, and the only candidates are this
 * implementation, which would assert nothing, or a second implementation on a different basis,
 * whose agreement is not a property worth having. The two shipped naming metrics disagree by
 * construction, one counting authored text per view and the other normalized definitions per root
 * reader, and no arithmetic takes either to the other.
 *
 * <p>So the weights are tested for what they do to a ratio rather than to a value: a case below
 * fills two relations to different sizes and asserts the correlated reference over the larger one
 * scores higher, which is the whole claim weighting makes.
 *
 * <p>One store for the class rather than one per case, this module counting schema boots against a
 * budget. The bodies and the rows are therefore set up once and shared, which is why the case about
 * an unpopulated driving side drives from a relation of its own rather than from an empty store.
 */
@DisplayName("ReEvaluationMetric counts rule evaluations rather than namings")
class ReEvaluationMetricTest {

    private static ScratchSchema schema;
    private static DSLContext dsl;

    @BeforeAll
    static void openStore() {
        schema = ScratchSchema.open();
        dsl = schema.dsl();
        schema.define("CREATE TABLE probe_base (a INT, b INT)");
        schema.define("CREATE TABLE probe_other (a INT, c INT)");
        schema.define("CREATE TABLE probe_unfilled (a INT, c INT)");
        schema.define("CREATE VIEW probe_leaf AS SELECT a, b FROM probe_base");
        dsl.execute("INSERT INTO probe_base (a, b) VALUES (1, 1)");
        for (int row = 0; row < 20; row++) {
            dsl.execute("INSERT INTO probe_other (a, c) VALUES (?, ?)", row, row);
        }

        view("probe_plain", "SELECT a FROM probe_leaf");
        view("probe_twice", "SELECT l.a FROM probe_leaf l JOIN probe_leaf m ON l.a = m.a");
        view("probe_two", "SELECT l.a FROM probe_leaf l JOIN probe_leaf m ON l.a = m.a");
        view("probe_four", "SELECT p.a FROM probe_two p JOIN probe_two q ON p.a = q.a");
        view("probe_correlated", "SELECT o.a FROM probe_other o "
            + "WHERE EXISTS (SELECT 1 FROM probe_leaf l WHERE l.a = o.a)");
        view("probe_small", "SELECT b.a FROM probe_base b "
            + "WHERE EXISTS (SELECT 1 FROM probe_leaf l WHERE l.a = b.a)");
        view("probe_large", "SELECT o.a FROM probe_other o "
            + "WHERE EXISTS (SELECT 1 FROM probe_leaf l WHERE l.a = o.a)");
        view("probe_unpopulated", "SELECT u.a FROM probe_unfilled u "
            + "WHERE EXISTS (SELECT 1 FROM probe_leaf l WHERE l.a = u.a)");
    }

    @AfterAll
    static void closeStore() {
        schema.close();
    }

    @Test
    @DisplayName("a rule read once costs one evaluation, and a base table costs none")
    void oneNamingIsOneEvaluation() {
        assertThat(count("probe_leaf")).isEqualTo(0);
        assertThat(count("probe_plain")).isEqualTo(1);
    }

    @Test
    @DisplayName("a rule named twice costs two, H2 eliminating no common subexpression")
    void namingsAddUp() {
        assertThat(count("probe_twice")).isEqualTo(2);
    }

    @Test
    @DisplayName("a rule's own reads are paid once per expansion of it, so depth multiplies")
    void depthMultiplies() {
        assertThat(count("probe_two")).isEqualTo(2);
        assertThat(count("probe_four")).isEqualTo(6);
    }

    @Test
    @DisplayName("materializing a rule stops its readers evaluating it and its own reads with it")
    void aCutSetStopsExpansion() {
        assertThat(count("probe_four")).isEqualTo(6);
        assertThat(countWith("probe_four", Set.of("probe_two"))).isEqualTo(0);
    }

    @Test
    @DisplayName("with weights forced to one a correlated naming counts as one, which is exactly "
        + "the blindness this metric exists to remove")
    void uniformWeightsAreBlindToPosition() {
        assertThat(count("probe_correlated")).isEqualTo(count("probe_plain"));
    }

    @Test
    @DisplayName("weighted, a correlated naming costs the rows it is driven by, and the same body "
        + "over a larger driving side costs more")
    void weightingSeesTheDrivingSide() {
        assertThat(weighted("probe_small")).isEqualTo(1);
        assertThat(weighted("probe_large")).isEqualTo(20);
    }

    @Test
    @DisplayName("an unpopulated driving side makes the weight one, which is the metric silently "
        + "degraded to the naming count it replaces, and is why this wants a filled store")
    void anEmptyDrivingSideDegradesToCounting() {
        assertThat(weighted("probe_unpopulated")).isEqualTo(count("probe_unpopulated"));
    }

    @Test
    @DisplayName("the fact schema's own root readers are the views nothing names, and a "
        + "registration's source view is not one of them")
    void rootReadersExcludeRefreshSources() {
        var metric = ReEvaluationMetric.over(dsl, Weighting.uniform());

        assertThat(metric.rootReadersInStore()).isNotEmpty();
        assertThat(metric.rootReadersInStore()).noneMatch(reader -> reader.endsWith("_live"));
    }

    @Test
    @DisplayName("materializing more can only make reading cheaper, never dearer")
    void moreMaterializationNeverCostsAReaderMore() {
        var metric = ReEvaluationMetric.over(dsl, Weighting.uniform());
        var registrations = metric.everyRegisteredRelation();

        assertThat(metric.score(registrations).instantiations())
            .isLessThanOrEqualTo(metric.score(Set.of()).instantiations());
    }

    @Test
    @DisplayName("a refresh is charged to the cut set that asks for it, so materializing more can "
        + "cost more overall even while every read gets cheaper")
    void refreshIsChargedSeparately() {
        var metric = ReEvaluationMetric.over(dsl, Weighting.uniform());
        var registrations = metric.everyRegisteredRelation();

        assertThat(metric.refreshCost(Set.of())).isEqualTo(BigInteger.ZERO);
        assertThat(metric.refreshCost(registrations).signum()).isPositive();
        assertThat(metric.totalCost(registrations))
            .isEqualTo(metric.score(registrations).instantiations()
                .add(metric.refreshCost(registrations)));
    }

    @Test
    @DisplayName("a relation named only by refresh sources is still where a read enters, which is "
        + "the rule that keeps a whole family from scoring as worth nothing")
    void refreshSourcesDoNotDemoteWhatTheyName() {
        var metric = ReEvaluationMetric.over(dsl, Weighting.uniform());

        assertThat(metric.rootReadersInStore()).contains("intent_mutation_matched_key");
    }

    private static void view(String name, String body) {
        schema.define("CREATE VIEW " + name + " AS " + body);
    }

    private static long count(String view) {
        return countWith(view, Set.of());
    }

    private static long countWith(String view, Set<String> cutSet) {
        return ReEvaluationMetric.over(dsl, Weighting.uniform())
            .instantiationsOf(view, cutSet).longValueExact();
    }

    private static long weighted(String view) {
        return ReEvaluationMetric.over(dsl, Weighting.byCardinality())
            .instantiationsOf(view, Set.of()).longValueExact();
    }
}
