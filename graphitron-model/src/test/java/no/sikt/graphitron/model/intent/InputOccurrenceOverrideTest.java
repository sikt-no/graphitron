package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.INTENT_INPUT_OCCURRENCE_OVERRIDE;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedDeclaredType;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedOccurrencePath;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the override relation returns: which occurrences of the input surface sit under an
 * enclosing {@code @condition(override: true)}, and which site is named as the reason. In the
 * classification walk this is a boolean threaded down the recursion; as a relation it is a
 * predicate over paths, and the two shapes differ in what they can be asked. A boolean can only be
 * read where the recursion currently is, while the relation answers for every occurrence at once
 * and has to say, per occurrence, which of several enclosing sites it read.
 *
 * <p>Three sites can enclose an occurrence and they are three separate relations, not one: the use
 * site's own field condition, the argument's condition, and the condition on any input field the
 * path steps through. Each contributes its own arm, so the arms are stated one at a time, and the
 * witness columns are keyed differently across them: an argument-site witness names its argument
 * and the other two leave that column absent, which is each relation's own key shape surfacing
 * rather than a flag the view invents.
 *
 * <p>Two boundaries are what tell the arms apart from a blanket cascade. A step encloses what is
 * below it and not itself, the last step being the occurrence's own site rather than something
 * around it, so the case that states this seeds a path deep enough to have an inside and an
 * outside. And a step's condition is declared on an input type rather than at a use site, so it
 * cascades under every argument that reaches that type, which needs two use sites over one type to
 * state at all.
 *
 * <p>Where several sites enclose one occurrence the relation still answers once, naming the nearest
 * one, so precedence here is a fact about a single row rather than about which rows survive. The
 * case pinning it builds one path with all three kinds of site overriding at once and reads the
 * witness off each of its prefixes, the answer changing as the prefixes leave sites behind.
 *
 * <p>Whether an author's {@code @condition(override: true)} reaches this relation as a true flag at
 * all, and whether the occurrences it answers over are the ones the walk expands, is a different
 * question with a different home:
 * {@code no.sikt.graphitron.rewrite.derive.InputOccurrenceShadowTest} sweeps the classified corpus
 * against the walk's own cascade verdicts over a real capture.
 */
class InputOccurrenceOverrideTest {

    private static final String GRAPH = "g";
    private static final String OTHER_GRAPH = "g2";

    private static final OccurrenceStep NAME = new OccurrenceStep("Filter", "name", "String");
    private static final OccurrenceStep NESTED = new OccurrenceStep("Filter", "nested", "Nested");
    private static final OccurrenceStep DEEP = new OccurrenceStep("Nested", "deep", "String");

    // ===== The three enclosing sites =====

    /**
     * The use site's own condition, which encloses the whole subtree under it: every occurrence
     * reached from that field's argument is overridden, at whatever depth, and each names the field
     * as the reason with no argument of its own.
     */
    @Test
    void aUseSiteFieldsOverrideEnclosesEveryOccurrenceBeneathIt() {
        withSeededStore(GRAPH, dsl -> {
            seedFilterTypes(dsl, GRAPH);
            seedUseSite(dsl, GRAPH, "films", "filter", NAME);
            seedUseSite(dsl, GRAPH, "films", "filter", NESTED, DEEP);
            seedFieldCondition(dsl, GRAPH, "Query", "films", true);

            assertThat(overriddenPaths(dsl)).containsExactlyInAnyOrder(
                "Query.films(filter)",
                "Query.films(filter)/name",
                "Query.films(filter)/nested",
                "Query.films(filter)/nested/deep");
            assertThat(witnessOf(dsl, "Query.films(filter)/nested/deep"))
                .isEqualTo("Query.films/-");
        });
    }

    /**
     * The argument's own condition, which encloses that argument's subtree and no sibling's. Two
     * arguments of the same input type on one field are what separates the argument reading from
     * the field reading, the field being shared and the answer not.
     */
    @Test
    void anArgumentsOverrideEnclosesItsOwnSubtreeAndNoSiblings() {
        withSeededStore(GRAPH, dsl -> {
            seedFilterTypes(dsl, GRAPH);
            seedUseSite(dsl, GRAPH, "films", "filter", NAME);
            seedArgument(dsl, GRAPH, "Query", "films", "other", "Filter");
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "other", "Filter", NAME);
            seedArgumentCondition(dsl, GRAPH, "Query", "films", "filter", true);

            assertThat(overriddenPaths(dsl)).containsExactlyInAnyOrder(
                "Query.films(filter)",
                "Query.films(filter)/name");
            assertThat(witnessOf(dsl, "Query.films(filter)/name"))
                .as("the argument site names its own argument, which the other two arms cannot")
                .isEqualTo("Query.films/filter");
        });
    }

    /**
     * A step's condition, which encloses what lies below that step and not the step itself. The
     * occurrence a condition sits on is its own site, so the relation that answers about enclosure
     * leaves it out, and a path with an inside and an outside is what states the difference.
     */
    @Test
    void aStepsOverrideEnclosesWhatIsBelowItAndNotItsOwnOccurrence() {
        withSeededStore(GRAPH, dsl -> {
            seedFilterTypes(dsl, GRAPH);
            seedUseSite(dsl, GRAPH, "films", "filter", NESTED, DEEP);
            seedFieldCondition(dsl, GRAPH, "Filter", "nested", true);

            assertThat(overriddenPaths(dsl))
                .as("the marked occurrence carries its own condition, not an enclosing one")
                .containsExactly("Query.films(filter)/nested/deep");
            assertThat(witnessOf(dsl, "Query.films(filter)/nested/deep"))
                .as("a step witness names the input type it is declared on, and no argument")
                .isEqualTo("Filter.nested/-");
        });
    }

    /**
     * A step's condition is declared on an input type rather than at a use site, so one condition
     * encloses occurrences under every argument that reaches that type, and under no argument that
     * does not. Two use sites over the one marked input type, and a third of the same depth over an
     * unmarked one, are what states both halves; with a single use site the reading keyed on the
     * type and the reading keyed on the use site are one row apart.
     */
    @Test
    void aStepsOverrideReachesEveryUseSiteThatDescendsThroughItAndNoOther() {
        withSeededStore(GRAPH, dsl -> {
            seedFilterTypes(dsl, GRAPH);
            seedUseSite(dsl, GRAPH, "films", "filter", NESTED, DEEP);
            seedField(dsl, GRAPH, "Query", "shows", "Show", true);
            seedArgument(dsl, GRAPH, "Query", "shows", "by", "Filter");
            seedOccurrencePath(dsl, GRAPH, "Query", "shows", "by", "Filter", NESTED, DEEP);

            seedDeclaredType(dsl, GRAPH, "Other", "INPUT_OBJECT");
            seedDeclaredType(dsl, GRAPH, "Inner", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "Other", "inner", "Inner", false);
            seedField(dsl, GRAPH, "Inner", "leaf", "String", false);
            seedField(dsl, GRAPH, "Query", "elsewhere", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "elsewhere", "by", "Other");
            seedOccurrencePath(dsl, GRAPH, "Query", "elsewhere", "by", "Other",
                new OccurrenceStep("Other", "inner", "Inner"),
                new OccurrenceStep("Inner", "leaf", "String"));

            seedFieldCondition(dsl, GRAPH, "Filter", "nested", true);

            assertThat(overriddenPaths(dsl))
                .as("the third occurrence has a step of its own at the same position, and no reason")
                .containsExactlyInAnyOrder(
                    "Query.films(filter)/nested/deep",
                    "Query.shows(by)/nested/deep");
            assertThat(witnessOf(dsl, "Query.shows(by)/nested/deep")).isEqualTo("Filter.nested/-");
        });
    }

    /**
     * A site encloses by its whole coordinate and not by the names in it. The marked use site is
     * flanked by two that repeat one of its names, another type declaring a field called the same
     * and the same type declaring another field taking an argument called the same, each being a
     * part of the coordinate that a fixture with one use site cannot tell from a wildcard.
     *
     * <p>Both arms are marked at once on the one site. They key off the same three columns of the
     * path, so a neighbour that stays absent is absent for both, and the witness still says which
     * of the two answered.
     */
    @Test
    void anEnclosingSiteIsKeyedByItsWholeCoordinateRatherThanByName() {
        withSeededStore(GRAPH, dsl -> {
            seedFilterTypes(dsl, GRAPH);
            seedUseSite(dsl, GRAPH, "films", "filter", NAME);
            seedFieldCondition(dsl, GRAPH, "Query", "films", true);
            seedArgumentCondition(dsl, GRAPH, "Query", "films", "filter", true);

            seedField(dsl, GRAPH, "Show", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Show", "films", "filter", "Filter");
            seedOccurrencePath(dsl, GRAPH, "Show", "films", "filter", "Filter", NAME);

            seedUseSite(dsl, GRAPH, "other", "filter", NAME);

            assertThat(overriddenPaths(dsl))
                .as("neither the type nor the field name alone is what either arm matched on")
                .containsExactlyInAnyOrder(
                    "Query.films(filter)",
                    "Query.films(filter)/name");
            assertThat(witnessOf(dsl, "Query.films(filter)/name"))
                .isEqualTo("Query.films/filter");
        });
    }

    // ===== The flag =====

    /**
     * Only the flag written true encloses anything, at all three sites. The column is nullable
     * because the directive's argument is optional, so the omitted spelling and the explicitly
     * false one are different rows and a relation reading the flag has to answer the same on both.
     */
    @Test
    void onlyTheFlagWrittenTrueEnclosesAnything() {
        withSeededStore(GRAPH, dsl -> {
            seedFilterTypes(dsl, GRAPH);
            seedUseSite(dsl, GRAPH, "declined", "filter", NAME);
            seedFieldCondition(dsl, GRAPH, "Query", "declined", false);

            seedUseSite(dsl, GRAPH, "omitted", "filter", NAME);
            seedArgumentCondition(dsl, GRAPH, "Query", "omitted", "filter", null);

            seedUseSite(dsl, GRAPH, "stepwise", "filter", NESTED, DEEP);
            seedFieldCondition(dsl, GRAPH, "Filter", "nested", false);

            seedUseSite(dsl, GRAPH, "admitted", "filter", NAME);
            seedFieldCondition(dsl, GRAPH, "Query", "admitted", true);

            assertThat(overriddenPaths(dsl))
                .as("a false flag, an omitted one and a condition-free path read alike")
                .containsExactlyInAnyOrder(
                    "Query.admitted(filter)",
                    "Query.admitted(filter)/name");
        });
    }

    // ===== The nearest site =====

    /**
     * An occurrence enclosed several times over is still one row, naming the nearest site. Reading
     * the witness off each prefix of one deep path is how the ordering is stated: the deepest
     * enclosing step wins, a shallower step beats the argument, the argument beats the field, and
     * each prefix drops the sites it no longer lies below.
     */
    @Test
    void anOccurrenceEnclosedSeveralTimesNamesTheNearestSiteOnce() {
        withSeededStore(GRAPH, dsl -> {
            seedFilterTypes(dsl, GRAPH);
            seedDeclaredType(dsl, GRAPH, "Deeper", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "Nested", "deeper", "Deeper", false);
            seedField(dsl, GRAPH, "Deeper", "leaf", "String", false);
            seedUseSite(dsl, GRAPH, "films", "filter", NESTED,
                new OccurrenceStep("Nested", "deeper", "Deeper"),
                new OccurrenceStep("Deeper", "leaf", "String"));
            seedFieldCondition(dsl, GRAPH, "Query", "films", true);
            seedArgumentCondition(dsl, GRAPH, "Query", "films", "filter", true);
            seedFieldCondition(dsl, GRAPH, "Filter", "nested", true);
            seedFieldCondition(dsl, GRAPH, "Nested", "deeper", true);

            assertThat(witnessOf(dsl, "Query.films(filter)/nested/deeper/leaf"))
                .as("the deepest enclosing step")
                .isEqualTo("Nested.deeper/-");
            assertThat(witnessOf(dsl, "Query.films(filter)/nested/deeper"))
                .as("its own step no longer encloses, so the one above it answers")
                .isEqualTo("Filter.nested/-");
            assertThat(witnessOf(dsl, "Query.films(filter)/nested"))
                .as("no step is left above it, so the argument answers over the field")
                .isEqualTo("Query.films/filter");
            assertThat(rowCount(dsl))
                .as("four occurrences, four rows, however many sites enclose each")
                .isEqualTo(4);
        });
    }

    // ===== The partition =====

    /**
     * A graph's conditions enclose nothing under a sibling graph's identically named use site, at
     * each of the three arms, and the witness is picked within a graph rather than across the store.
     *
     * <p>Two graphs carrying the same type and field names is the arrangement one workspace of
     * several schemas produces and one capture of one schema never does. The sites are placed so
     * the two graphs disagree about which occurrences are enclosed and about which site answers,
     * so a partition that lost a graph would have to change one of the two answers.
     */
    @Test
    void aGraphEnclosesNothingOnItsSiblingsBehalf() {
        withSeededStore(GRAPH, dsl -> {
            seedGraph(dsl, OTHER_GRAPH);
            for (String graph : List.of(GRAPH, OTHER_GRAPH)) {
                seedFilterTypes(dsl, graph);
                seedUseSite(dsl, graph, "films", "filter", NESTED, DEEP);
                seedUseSite(dsl, graph, "shows", "by", NAME);
            }
            seedFieldCondition(dsl, GRAPH, "Filter", "nested", true);
            seedArgumentCondition(dsl, GRAPH, "Query", "shows", "by", true);
            seedFieldCondition(dsl, OTHER_GRAPH, "Query", "films", true);

            assertThat(allOverridden(dsl)).containsExactlyInAnyOrder(
                GRAPH + " Query.films(filter)/nested/deep",
                GRAPH + " Query.shows(by)",
                GRAPH + " Query.shows(by)/name",
                OTHER_GRAPH + " Query.films(filter)",
                OTHER_GRAPH + " Query.films(filter)/nested",
                OTHER_GRAPH + " Query.films(filter)/nested/deep");
            assertThat(witnessOf(dsl, OTHER_GRAPH, "Query.films(filter)/nested/deep"))
                .as("the sibling's own use site answers, the nearer site next door being another "
                    + "graph's")
                .isEqualTo("Query.films/-");
        });
    }

    // ===== Fixtures =====

    /** The input surface every case descends: {@code Filter { name, nested: Nested { deep } }}. */
    private static void seedFilterTypes(DSLContext dsl, String graphName) {
        seedDeclaredType(dsl, graphName, "Query", "OBJECT");
        seedDeclaredType(dsl, graphName, "Show", "OBJECT");
        seedDeclaredType(dsl, graphName, "Film", "OBJECT");
        seedDeclaredType(dsl, graphName, "Filter", "INPUT_OBJECT");
        seedDeclaredType(dsl, graphName, "Nested", "INPUT_OBJECT");
        seedField(dsl, graphName, "Filter", "name", "String", false);
        seedField(dsl, graphName, "Filter", "nested", "Nested", false);
        seedField(dsl, graphName, "Nested", "deep", "String", false);
    }

    /**
     * One field taking one argument of the shared input type, plus one occurrence below it. Called
     * twice with the same field and argument for a branching input surface, the shared prefix being
     * one row either way.
     */
    private static void seedUseSite(DSLContext dsl, String graphName, String fieldName,
                                    String argumentName, OccurrenceStep... steps) {
        if (!dsl.fetchExists(GRAPHQL_ARGUMENT,
                GRAPHQL_ARGUMENT.GRAPH_NAME.eq(graphName)
                    .and(GRAPHQL_ARGUMENT.TYPE_NAME.eq("Query"))
                    .and(GRAPHQL_ARGUMENT.FIELD_NAME.eq(fieldName))
                    .and(GRAPHQL_ARGUMENT.ARGUMENT_NAME.eq(argumentName)))) {
            seedField(dsl, graphName, "Query", fieldName, "Film", true);
            seedArgument(dsl, graphName, "Query", fieldName, argumentName, "Filter");
        }
        seedOccurrencePath(dsl, graphName, "Query", fieldName, argumentName, "Filter", steps);
    }

    // ===== Readings =====

    private static List<String> overriddenPaths(DSLContext dsl) {
        return dsl.select(INTENT_INPUT_OCCURRENCE_OVERRIDE.PATH)
            .from(INTENT_INPUT_OCCURRENCE_OVERRIDE)
            .where(INTENT_INPUT_OCCURRENCE_OVERRIDE.GRAPH_NAME.eq(GRAPH))
            .fetch(org.jooq.Record1::value1);
    }

    /** Every overridden occurrence in the store, graph first, so the partition is read as a value. */
    private static List<String> allOverridden(DSLContext dsl) {
        return dsl.select(INTENT_INPUT_OCCURRENCE_OVERRIDE.GRAPH_NAME,
                INTENT_INPUT_OCCURRENCE_OVERRIDE.PATH)
            .from(INTENT_INPUT_OCCURRENCE_OVERRIDE)
            .fetch(r -> r.value1() + " " + r.value2());
    }

    /** The witness as {@code <type>.<field>/<argument>}, an absent argument reading as a dash. */
    private static String witnessOf(DSLContext dsl, String path) {
        return witnessOf(dsl, GRAPH, path);
    }

    private static String witnessOf(DSLContext dsl, String graphName, String path) {
        return dsl.selectFrom(INTENT_INPUT_OCCURRENCE_OVERRIDE)
            .where(INTENT_INPUT_OCCURRENCE_OVERRIDE.GRAPH_NAME.eq(graphName))
            .and(INTENT_INPUT_OCCURRENCE_OVERRIDE.PATH.eq(path))
            .fetchOptional(r -> r.getOverrideTypeName() + "." + r.getOverrideFieldName() + "/"
                + (r.getOverrideArgumentName() == null ? "-" : r.getOverrideArgumentName()))
            .orElse(null);
    }

    private static int rowCount(DSLContext dsl) {
        return dsl.fetchCount(INTENT_INPUT_OCCURRENCE_OVERRIDE,
            INTENT_INPUT_OCCURRENCE_OVERRIDE.GRAPH_NAME.eq(GRAPH));
    }
}
