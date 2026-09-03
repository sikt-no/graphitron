package no.sikt.graphitron.rewrite.derive;

import graphql.language.BooleanValue;
import graphql.language.DirectivesContainer;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.classifieddsl.CorpusDocuments;
import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedDsl;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_INPUT_OCCURRENCE_OVERRIDE;
import static no.sikt.graphitron.model.Tables.INTENT_INPUT_OCCURRENCE_PATH;
import static no.sikt.graphitron.model.Tables.INTENT_INPUT_OCCURRENCE_PATH_STEP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shadow reader of the input occurrence surface, and the registered agreement anchor for
 * {@code intent_input_occurrence_path}, its step child, and
 * {@code intent_input_occurrence_override}. Three bindings, each deliberately independent of
 * the others' machinery: the derived path population equals a structural reference enumeration
 * recomputed from the assembled schema (classification-independent, so tombstoned consumers
 * cost no exclusion list); the override view equals the {@code @condition(override:)} facts
 * read from the same enumeration's AST; and every use-keyed cascade verdict the classification
 * walk mints into the build diagnostics names a derived path that has no override row.
 *
 * <p>That third binding is one-directional by design, and this is the drift it cannot catch:
 * a store predicate too narrow (an occurrence the derivation misses, or an override row it
 * over-produces) suppresses no walk verdict, because the walk still evaluates its own threaded
 * {@code enclosingOverride} boolean in production. The converse binding arrives when the
 * store-side unbound predicate lands with its own slice and the detection reads these
 * relations; until then the targeted fixtures pin each population non-empty (an admitted
 * cascade, a rejected cascade at two use sites, the malformed shape inside a cascade, cyclic
 * nesting), so the bindings cannot go vacuous, and
 * {@link #rejectedCascadeAtTwoUseSitesMintsTwoUseKeyedFacts} is the named fixture pinning the
 * Java mint's path serialization equal to the store key.
 *
 * <p>Which occurrences the override view calls enclosed, and which of several enclosing sites it
 * names as the witness, is not asked here. That is the view's own algebra, its three site arms,
 * the boundary each draws and the order it picks a witness in, and it lives in the module whose
 * DDL declares it, in {@code no.sikt.graphitron.model.intent.InputOccurrenceOverrideTest}, against
 * a store seeded row by row. What the fixtures below owe instead is the capture side of the same
 * relation: that an author's {@code @condition(override: true)} arrives as a flag the view reads,
 * that the occurrences it answers over are the ones the walk expands, and that the walk's verdicts
 * and the view's rows do not both go empty at once.
 */
@PipelineTier
class InputOccurrenceShadowTest {

    private static final String GRAPH = CapturedStore.GRAPH;
    private static final String OCCURRENCE_MARK = " at occurrence '";

    @TempDir
    Path tmp;

    // ===== The corpus sweep =====

    /**
     * Per corpus example, captured as its own graph in one store: the derived paths equal the
     * reference enumeration, the override rows equal the AST-read expectation, and each cascade
     * verdict's quoted path is a derived row with no override row. The floor on compared paths
     * keeps the sweep from passing on an accidentally empty surface.
     */
    @Test
    void occurrencePathsAgreeWithTheStructuralEnumerationOverTheCorpus() {
        int comparedPaths = 0;
        int cascadeVerdicts = 0;
        try (var store = captureCorpus()) {
            for (CorpusDocuments.Document example : CorpusDocuments.documents()) {
                var bundle = TestSchemaHelper.buildBundle(preluded(example));
                var expected = enumerate(bundle.assembled());

                var derived = fetchPaths(store.dsl(), example.id());
                assertThat(derived)
                    .as("derived occurrence paths vs the structural enumeration (%s)", example.id())
                    .containsExactlyInAnyOrderElementsOf(expected.keySet());
                comparedPaths += derived.size();

                var overridden = fetchOverriddenPaths(store.dsl(), example.id());
                var expectedOverridden = expected.entrySet().stream()
                    .filter(Map.Entry::getValue).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
                assertThat(overridden)
                    .as("override view vs the AST-read cascade expectation (%s)", example.id())
                    .containsExactlyInAnyOrderElementsOf(expectedOverridden);

                for (var diagnostic : bundle.model().diagnostics()) {
                    String path = occurrencePathOf(diagnostic.message());
                    if (path == null) continue;
                    cascadeVerdicts++;
                    assertThat(derived)
                        .as("cascade verdict names a derived path (%s): %s", example.id(), path)
                        .contains(path);
                    assertThat(overridden)
                        .as("cascade verdict fired on an unoverridden path (%s): %s", example.id(), path)
                        .doesNotContain(path);
                }
            }
        }
        assertThat(comparedPaths)
            .as("the corpus exercises a non-trivial occurrence surface")
            .isGreaterThan(10);
        assertThat(cascadeVerdicts).as("corpus examples are accepted schemas; the rejected-cascade "
            + "population is pinned non-empty by the targeted fixtures instead").isNotNegative();
    }

    // ===== Targeted fixtures =====

    /**
     * The admitted cascade: an arg-level {@code @condition(override: true)} resolves the unbound
     * field, so the walk mints no cascade verdict and the consumer classifies, while the override
     * view carries the path. The flag is the fixture's subject on this side: written in SDL at a
     * site the walk reads, it has to arrive in the store as a flag the view reads too, which is
     * what keeps the seeded half's arms from being about a column nothing populates.
     */
    @Test
    void admittedCascadeSilencesTheWalkAndReachesTheOverrideView() {
        String sdl = """
            input PlainFilter { foo: String }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query {
              films(filter: PlainFilter
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "lifterFieldCondition"}, override: true)
              ): [Film!]!
            }
            """;
        var schema = TestSchemaHelper.buildSchema(sdl);
        assertThat(schema.field("Query", "films"))
            .isNotInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(schema.diagnostics()).noneMatch(d -> d.message().contains(OCCURRENCE_MARK));
        try (var store = CapturedStore.ofCatalog(tmp, sdl, jooq())) {
            assertThat(fetchOverriddenPaths(store.dsl(), GRAPH))
                .contains("Query.films(filter)/foo");
        }
    }

    /**
     * The rejected cascade at two use sites: the verdict is use-keyed, so the same input field
     * mints two facts, one per occurrence, each located at the leaf and carrying its own path.
     * This is also the named serialization fixture: the paths quoted in the minted messages are
     * asserted equal to the store's keys, which is what keeps the Java mint's serialization and
     * the derivation's key from drifting apart while the path rides in prose (the recorded
     * deferral until the diagnostics surface grows a typed occurrence component).
     */
    @Test
    void rejectedCascadeAtTwoUseSitesMintsTwoUseKeyedFacts() {
        String sdl = """
            input PlainFilter { foo: String }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query {
              films(filter: PlainFilter): [Film!]!
              moreFilms(other: PlainFilter): [Film!]!
            }
            """;
        var schema = TestSchemaHelper.buildSchema(sdl);
        assertThat(schema.field("Query", "films")).isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(schema.field("Query", "moreFilms")).isInstanceOf(GraphitronField.UnclassifiedField.class);
        var mintedPaths = schema.diagnostics().stream()
            .filter(d -> "PlainFilter.foo".equals(d.coordinate()))
            .map(d -> occurrencePathOf(d.message()))
            .filter(java.util.Objects::nonNull)
            .toList();
        assertThat(mintedPaths).containsExactlyInAnyOrder(
            "Query.films(filter)/foo", "Query.moreFilms(other)/foo");
        try (var store = CapturedStore.ofCatalog(tmp, sdl, jooq())) {
            assertThat(fetchPaths(store.dsl(), GRAPH)).containsAll(mintedPaths);
            assertThat(fetchOverriddenPaths(store.dsl(), GRAPH)).isEmpty();
        }
    }

    /**
     * The shape that used to escape every evaluation site (the validator walk never reached
     * plain inputs, and the consumer arm evaluated only outside a cascade), closed:
     * {@code @condition(override: false)} on a field with no resolving column is malformed
     * regardless of the enclosing cascade, so the definition-and-table-keyed fact mints even
     * though the override cascade admits the occurrence (no cascade verdict, and the consumer
     * classifies; the build fails through the malformed diagnostic alone).
     */
    @Test
    void malformedShapeInsideAnOverrideCascadeStillMintsItsFact() {
        String sdl = """
            input PlainFilter {
              foo: String
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "lifterFieldCondition"})
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query {
              films(filter: PlainFilter
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "lifterFieldCondition"}, override: true)
              ): [Film!]!
            }
            """;
        var schema = TestSchemaHelper.buildSchema(sdl);
        assertThat(schema.field("Query", "films"))
            .isNotInstanceOf(GraphitronField.UnclassifiedField.class);
        var malformed = schema.diagnostics().stream()
            .filter(d -> "PlainFilter.foo".equals(d.coordinate()))
            .filter(d -> d.message().contains("@condition(override: false)"))
            .toList();
        assertThat(malformed).hasSize(1);
        assertThat(malformed.getFirst().message()).contains("table 'film'");
        assertThat(schema.diagnostics()).noneMatch(d -> d.message().contains(OCCURRENCE_MARK));
    }

    /**
     * Cyclic input nesting: the derivation terminates with simple paths, the cycle-closing
     * occurrence keeps its row (and its step decomposition, pinned here so no consumer ever
     * needs to parse the serialized key) and never expands, while the walk's circularity
     * rejection is unchanged.
     */
    @Test
    void cyclicInputNestingTerminatesAndKeepsTheClosingLeaf() {
        String sdl = """
            input A { b: B }
            input B { a: A }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: A): [Film!]! }
            """;
        var schema = TestSchemaHelper.buildSchema(sdl);
        assertThat(schema.field("Query", "films")).isInstanceOf(GraphitronField.UnclassifiedField.class);
        try (var store = CapturedStore.ofCatalog(tmp, sdl, jooq())) {
            var dsl = store.dsl();
            assertThat(fetchPaths(dsl, GRAPH)).containsExactlyInAnyOrder(
                "Query.films(filter)",
                "Query.films(filter)/b",
                "Query.films(filter)/b/a");
            var steps = dsl.selectFrom(INTENT_INPUT_OCCURRENCE_PATH_STEP)
                .where(INTENT_INPUT_OCCURRENCE_PATH_STEP.GRAPH_NAME.eq(GRAPH))
                .and(INTENT_INPUT_OCCURRENCE_PATH_STEP.PATH.eq("Query.films(filter)/b/a"))
                .orderBy(INTENT_INPUT_OCCURRENCE_PATH_STEP.ORDINAL)
                .fetch(r -> r.getOrdinal() + ":" + r.getContainerTypeName() + "."
                    + r.getFieldName() + "->" + r.getNamedType());
            assertThat(steps).containsExactly("1:A.b->B", "2:B.a->A");
        }
    }

    // ===== Reference enumeration =====

    /**
     * The structural reference: every argument of every field of every non-introspection
     * fields-container whose named type is an input object seeds a path, and paths descend
     * through input-object-typed fields under the first-visit rule, exactly the walk's
     * {@code ClassifyContext.expandingTypes} guard. The value is the path's enclosing-override
     * expectation, read from the AST {@code @condition(override:)} arguments so it is
     * independent of both capture and the classification walk.
     */
    private static Map<String, Boolean> enumerate(GraphQLSchema schema) {
        var paths = new LinkedHashMap<String, Boolean>();
        for (var type : schema.getAllTypesAsList()) {
            if (!(type instanceof GraphQLFieldsContainer container) || type.getName().startsWith("__")) {
                continue;
            }
            for (var field : container.getFieldDefinitions()) {
                boolean fieldOverride = overrideTrue(field.getDefinition());
                for (var arg : field.getArguments()) {
                    if (!(GraphQLTypeUtil.unwrapAll(arg.getType()) instanceof GraphQLInputObjectType input)) {
                        continue;
                    }
                    String root = container.getName() + "." + field.getName() + "(" + arg.getName() + ")";
                    boolean argOverride = fieldOverride || overrideTrue(arg.getDefinition());
                    paths.put(root, false);
                    var visited = new LinkedHashSet<String>();
                    visited.add(input.getName());
                    descend(root, input, argOverride, visited, paths);
                }
            }
        }
        return paths;
    }

    private static void descend(String path, GraphQLInputObjectType type, boolean enclosingOverride,
                                Set<String> visited, Map<String, Boolean> paths) {
        for (var field : type.getFieldDefinitions()) {
            String childPath = path + "/" + field.getName();
            paths.put(childPath, enclosingOverride);
            if (GraphQLTypeUtil.unwrapAll(field.getType()) instanceof GraphQLInputObjectType nested
                    && !visited.contains(nested.getName())) {
                var nextVisited = new LinkedHashSet<>(visited);
                nextVisited.add(nested.getName());
                descend(childPath, nested,
                    enclosingOverride || overrideTrue(field.getDefinition()),
                    nextVisited, paths);
            }
        }
    }

    /** Reads {@code @condition(override: true)} off an AST node's directives. */
    private static boolean overrideTrue(DirectivesContainer<?> definition) {
        if (definition == null) return false;
        for (var d : definition.getDirectives()) {
            if (!"condition".equals(d.getName())) continue;
            var arg = d.getArgument("override");
            return arg != null && arg.getValue() instanceof BooleanValue bv && bv.isValue();
        }
        return false;
    }

    /** Extracts the quoted occurrence path from a use-keyed cascade verdict, or null. */
    private static String occurrencePathOf(String message) {
        int start = message.indexOf(OCCURRENCE_MARK);
        if (start < 0) return null;
        int from = start + OCCURRENCE_MARK.length();
        int end = message.indexOf('\'', from);
        return end < 0 ? null : message.substring(from, end);
    }

    // ===== Store plumbing =====

    private static Set<String> fetchPaths(DSLContext dsl, String graphName) {
        return new LinkedHashSet<>(dsl.select(INTENT_INPUT_OCCURRENCE_PATH.PATH)
            .from(INTENT_INPUT_OCCURRENCE_PATH)
            .where(INTENT_INPUT_OCCURRENCE_PATH.GRAPH_NAME.eq(graphName))
            .fetch(org.jooq.Record1::value1));
    }

    private static Set<String> fetchOverriddenPaths(DSLContext dsl, String graphName) {
        return new LinkedHashSet<>(dsl.select(INTENT_INPUT_OCCURRENCE_OVERRIDE.PATH)
            .from(INTENT_INPUT_OCCURRENCE_OVERRIDE)
            .where(INTENT_INPUT_OCCURRENCE_OVERRIDE.GRAPH_NAME.eq(graphName))
            .fetch(org.jooq.Record1::value1));
    }

    /**
     * Every corpus example captured as its own graph into one store, which is what lets the sweep
     * read the partition as part of what it asserts. The catalog carries node inference with it,
     * production's arrangement.
     */
    private CapturedStore captureCorpus() {
        var jooq = jooq();
        CapturedStore store = null;
        for (CorpusDocuments.Document example : CorpusDocuments.documents()) {
            store = store == null
                ? CapturedStore.ofCatalog(tmp, example.id(), preluded(example), jooq)
                : store.andCatalogGraph(example.id(), preluded(example), jooq);
        }
        return store;
    }

    /**
     * A corpus example's SDL as the walk sees it. The Relay Node interface is appended when absent
     * so the captured document matches the one the walk parses, {@link TestSchemaHelper} injecting
     * it there.
     */
    private static String preluded(CorpusDocuments.Document example) {
        String full = CorpusDocuments.prelude() + "\n" + example.sdl();
        return full.contains("interface Node") ? full : full + "\ninterface Node { id: ID! }\n";
    }

    private static JooqCatalog jooq() {
        var ctx = testContext();
        return new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
    }
}
