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
import no.sikt.graphitron.model.test.CorpusDocuments;
import no.sikt.graphitron.model.test.CorpusStore;
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
import static no.sikt.graphitron.model.Tables.INTENT_INPUT_OCCURRENCE_PATH;
import static no.sikt.graphitron.model.Tables.INTENT_INPUT_OCCURRENCE_PATH_STEP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shadow reader of the input occurrence surface, and the registered agreement anchor for
 * {@code intent_input_occurrence_path} and its step child. Two bindings, each deliberately
 * independent of the other's machinery: the derived path population equals a structural reference
 * enumeration recomputed from the assembled schema (classification-independent, so tombstoned
 * consumers cost no exclusion list); and every use-keyed cascade verdict the classification walk
 * mints into the build diagnostics names a derived path.
 *
 * <p>The targeted fixtures pin each population non-empty (an admitted cascade, a rejected cascade
 * at two use sites, the malformed shape inside a cascade, cyclic nesting), so the bindings cannot
 * go vacuous, and {@link #rejectedCascadeAtTwoUseSitesMintsTwoUseKeyedFacts} is the named fixture
 * pinning the Java mint's path serialization equal to the store key.
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
     * reference enumeration, and each cascade verdict's quoted path is a derived row. The floor on
     * compared paths
     * keeps the sweep from passing on an accidentally empty surface.
     */
    @Test
    void occurrencePathsAgreeWithTheStructuralEnumerationOverTheCorpus() {
        record Tally(int comparedPaths, int cascadeVerdicts) {}
        var tally = CorpusStore.bare().read(dsl -> {
            int comparedPaths = 0;
            int cascadeVerdicts = 0;
            for (CorpusDocuments.Document example : CorpusDocuments.documents()) {
                var bundle = TestSchemaHelper.buildBundle(preluded(example));
                var expected = enumerate(bundle.assembled());

                var derived = fetchPaths(dsl, example.id());
                assertThat(derived)
                    .as("derived occurrence paths vs the structural enumeration (%s)", example.id())
                    .containsExactlyInAnyOrderElementsOf(expected.keySet());
                comparedPaths += derived.size();

                for (var diagnostic : bundle.model().diagnostics()) {
                    String path = occurrencePathOf(diagnostic.message());
                    if (path == null) continue;
                    cascadeVerdicts++;
                    assertThat(derived)
                        .as("cascade verdict names a derived path (%s): %s", example.id(), path)
                        .contains(path);
                }
            }
            return new Tally(comparedPaths, cascadeVerdicts);
        });
        assertThat(tally.comparedPaths())
            .as("the corpus exercises a non-trivial occurrence surface")
            .isGreaterThan(10);
        assertThat(tally.cascadeVerdicts()).as("corpus examples are accepted schemas; the rejected-cascade "
            + "population is pinned non-empty by the targeted fixtures instead").isNotNegative();
    }

    // ===== Targeted fixtures =====

    /**
     * The admitted cascade: an arg-level {@code @condition(override: true)} resolves the unbound
     * field, so the walk mints no cascade verdict and the consumer classifies.
     */
    @Test
    void admittedCascadeSilencesTheWalk() {
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

    private static String preluded(CorpusDocuments.Document example) {
        String full = CorpusDocuments.prelude() + "\n" + example.sdl();
        return full.contains("interface Node") ? full : full + "\ninterface Node { id: ID! }\n";
    }

    private static JooqCatalog jooq() {
        var ctx = testContext();
        return new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
    }
}
