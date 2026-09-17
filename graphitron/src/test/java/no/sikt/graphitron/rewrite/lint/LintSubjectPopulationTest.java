package no.sikt.graphitron.rewrite.lint;

import graphql.schema.idl.SchemaParser;
import no.sikt.graphitron.model.capture.document.GraphQLAssemblyCapture;
import no.sikt.graphitron.model.capture.document.GraphQLAstCapture;
import no.sikt.graphitron.model.capture.document.GraphQLSourceCapture;
import no.sikt.graphitron.model.capture.document.GraphitronAstCapture;
import no.sikt.graphitron.model.lint.LintRule;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.schema.input.TagLinkSynthesiser;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.model.test.SeededStore;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the lint engine's traversal visits, as a set, against what the fact store says the same set
 * is. The two are one query apart and this pins that they agree, which is the premise every rule
 * moving into SQL rests on: a statement selecting from {@code graphql_type} and its siblings sees
 * exactly the nodes the walk dispatched, no more and no fewer.
 *
 * <p>The store's side of the claim is one predicate, and it replaces two mechanisms. The engine
 * skips graphitron's own bundled surface by name, computed by parsing {@code directives.graphqls} a
 * second time, and skips the federation {@code @link} injector's definitions by a name set threaded
 * in from the pipeline. Both are provenance questions the rows already answer: an element carries
 * the source it was written in, and the only two sources the generator injects itself are the
 * bundled vocabulary and the tag-link synthesiser's. So "a type with a declaration site none of
 * those two names" is the whole exclusion, and it covers a third case neither mechanism addressed,
 * the engine-provided scalars, which declare no site at all.
 *
 * <p>Injected federation definitions need no arm here because they are not in the store to exclude:
 * {@code graphql_} holds the authored SDL, capture reading each parsed source in turn, and the
 * federation expansion happens after that reading. {@link #injectedDefinitionsAreNotInTheStore}
 * pins that, because the absence is now load-bearing rather than incidental.
 */
@PipelineTier
class LintSubjectPopulationTest {

    private static final String GRAPH = "lint-population";

    /**
     * A corpus with something at every grain the walk dispatches, including the shapes whose
     * provenance the predicate has to get right: an author type beside the bundled vocabulary, and
     * fields whose named types are engine-provided scalars.
     */
    private static final String SDL = """
        "A widget."
        type Widget implements Named @key(fields: "id") {
          "Its id."
          id: ID!
          name(locale: String = "nb", verbose: Boolean): String
          kind: WidgetKind
        }

        interface Named { name: String }

        union Anything = Widget

        enum WidgetKind { SMALL LARGE }

        input WidgetFilterInput { namePrefix: String, kind: WidgetKind }

        scalar DateTime

        type Query { widgets(filter: WidgetFilterInput): [Widget!]! }
        """;

    @Test
    @DisplayName("the walk's types are the types with an authored declaration site")
    void typePopulationAgrees() {
        withCapture((registry, dsl) -> {
            var walked = record(registry, dsl).types();
            var stored = storedTypes(dsl);
            assertThat(walked)
                .as("every type the walk dispatched, and only those, has an authored declaration")
                .containsExactlyInAnyOrderElementsOf(stored);
        });
    }

    @Test
    @DisplayName("the walk's fields, arguments and enum values are the stored ones at those grains")
    void memberPopulationsAgree() {
        withCapture((registry, dsl) -> {
            var walked = record(registry, dsl);
            assertThat(walked.outputFields())
                .as("output fields")
                .containsExactlyInAnyOrderElementsOf(storedFields(dsl, false));
            assertThat(walked.inputFields())
                .as("input-object fields")
                .containsExactlyInAnyOrderElementsOf(storedFields(dsl, true));
            assertThat(walked.arguments())
                .as("field arguments")
                .containsExactlyInAnyOrderElementsOf(storedArguments(dsl));
            assertThat(walked.enumValues())
                .as("enum values")
                .containsExactlyInAnyOrderElementsOf(storedEnumValues(dsl));
        });
    }

    /**
     * The engine-provided scalars are in the store as coordinates and out of the population, which
     * is the case neither name-set exclusion covered. They anchor rows because every SDL fact hangs
     * off a coordinate; they declare no site, which is what makes them nobody's to lint.
     */
    @Test
    @DisplayName("built-in scalars carry no declaration site, so the predicate excludes them")
    void builtInScalarsAreOutsideThePopulation() {
        withCapture((registry, dsl) -> {
            var undeclared = dsl.select(GRAPHQL_TYPE.TYPE_NAME)
                .from(GRAPHQL_TYPE)
                .where(GRAPHQL_TYPE.GRAPH_NAME.eq(GRAPH))
                .andNotExists(dsl.selectOne().from(GRAPHQL_TYPE_DECLARATION)
                    .where(GRAPHQL_TYPE_DECLARATION.GRAPH_NAME.eq(GRAPH))
                    .and(GRAPHQL_TYPE_DECLARATION.TYPE_NAME.eq(GRAPHQL_TYPE.TYPE_NAME)))
                .fetchSet(GRAPHQL_TYPE.TYPE_NAME);
            assertThat(undeclared)
                .as("the specification scalars are the site-less types")
                .contains("String", "Boolean", "ID");
            assertThat(storedTypes(dsl))
                .as("and none of them is in the lint population")
                .doesNotContainAnyElementsOf(undeclared);
        });
    }

    /**
     * The bundled vocabulary's own support types are in the store, stamped with the resource name,
     * and the predicate drops them. That is the replacement for {@code BUNDLED_TYPE_NAMES}' second
     * parse, asserted on a type that parse exists to exclude.
     */
    @Test
    @DisplayName("the bundled directive vocabulary is stored and excluded by its source name")
    void bundledSurfaceIsExcludedByProvenance() {
        withCapture((registry, dsl) -> {
            var bundled = dsl.selectDistinct(GRAPHQL_TYPE_DECLARATION.TYPE_NAME)
                .from(GRAPHQL_TYPE_DECLARATION)
                .where(GRAPHQL_TYPE_DECLARATION.GRAPH_NAME.eq(GRAPH))
                .and(GRAPHQL_TYPE_DECLARATION.SOURCE_NAME.eq(SchemaLoader.DIRECTIVES_SOURCE_NAME))
                .fetchSet(GRAPHQL_TYPE_DECLARATION.TYPE_NAME);
            assertThat(bundled)
                .as("capture read the bundled file and stamped its declarations with its name")
                .isNotEmpty();
            assertThat(storedTypes(dsl))
                .as("no bundled type is in the lint population")
                .doesNotContainAnyElementsOf(bundled);
        });
    }

    /**
     * The federation {@code @link} injector's definitions are absent from the store, so the
     * {@code injectedNames} exclusion has nothing to do. Pinned against the real injection path's
     * own fixture shape rather than argued from capture's source.
     */
    @Test
    @DisplayName("injected federation definitions are not in the store at all")
    void injectedDefinitionsAreNotInTheStore() {
        String linked = """
            extend schema
              @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key"])
            type Widget @node { id: ID! }
            type Query { widget: Widget }
            """;
        withCapture(linked, (registry, dsl) -> {
            var namespaced = dsl.select(GRAPHQL_TYPE.TYPE_NAME)
                .from(GRAPHQL_TYPE)
                .where(GRAPHQL_TYPE.GRAPH_NAME.eq(GRAPH))
                .fetchSet(GRAPHQL_TYPE.TYPE_NAME)
                .stream()
                .filter(name -> name.startsWith("federation__") || name.startsWith("link__"))
                .toList();
            assertThat(namespaced)
                .as("capture reads authored sources; the federation expansion runs after it")
                .isEmpty();
        });
    }

    // ------------------------------------------------------------------ the store's side

    /** Every type the population predicate admits: an authored declaration site, and no other kind. */
    private static Set<String> storedTypes(DSLContext dsl) {
        var d = GRAPHQL_TYPE_DECLARATION;
        return new LinkedHashSet<>(dsl.selectDistinct(d.TYPE_NAME)
            .from(d)
            .where(d.GRAPH_NAME.eq(GRAPH))
            .and(d.SOURCE_NAME.notIn(SchemaLoader.DIRECTIVES_SOURCE_NAME,
                TagLinkSynthesiser.SYNTHESISED_SOURCE_NAME))
            .fetch(d.TYPE_NAME));
    }

    /**
     * Fields at one of the two parents the walk treats apart: an input object's fields are its
     * {@code INPUT_FIELD_DEFINITION}s and every other parent's are {@code FIELD_DEFINITION}s. The
     * provenance filter is the owning type's, not the field's own, because the walk skips an
     * excluded type whole.
     */
    private static Set<String> storedFields(DSLContext dsl, boolean inputObject) {
        var f = GRAPHQL_FIELD;
        var t = GRAPHQL_TYPE;
        return new LinkedHashSet<>(dsl.select(f.TYPE_NAME, f.FIELD_NAME)
            .from(f)
            .join(t).on(t.GRAPH_NAME.eq(f.GRAPH_NAME), t.TYPE_NAME.eq(f.TYPE_NAME))
            .where(f.GRAPH_NAME.eq(GRAPH))
            .and(inputObject ? t.KIND.eq("INPUT_OBJECT") : t.KIND.in("OBJECT", "INTERFACE"))
            .and(f.TYPE_NAME.in(storedTypes(dsl)))
            .fetch()
            .map(row -> row.value1() + "." + row.value2()));
    }

    private static Set<String> storedArguments(DSLContext dsl) {
        var a = GRAPHQL_ARGUMENT;
        return new LinkedHashSet<>(dsl.select(a.TYPE_NAME, a.FIELD_NAME, a.ARGUMENT_NAME)
            .from(a)
            .where(a.GRAPH_NAME.eq(GRAPH))
            .and(a.TYPE_NAME.in(storedTypes(dsl)))
            .fetch()
            .map(row -> row.value1() + "." + row.value2() + "(" + row.value3() + ":)"));
    }

    private static Set<String> storedEnumValues(DSLContext dsl) {
        var v = GRAPHQL_ENUM_VALUE;
        return new LinkedHashSet<>(dsl.select(v.TYPE_NAME, v.VALUE_NAME)
            .from(v)
            .where(v.GRAPH_NAME.eq(GRAPH))
            .and(v.TYPE_NAME.in(storedTypes(dsl)))
            .fetch()
            .map(row -> row.value1() + "." + row.value2()));
    }

    // ------------------------------------------------------------------ the walk's side

    /** What one traversal dispatched, per grain, spelled as the store spells the same coordinate. */
    private record Walked(Set<String> types, Set<String> outputFields, Set<String> inputFields,
                          Set<String> arguments, Set<String> enumValues) {}

    /**
     * Runs the engine with a single visitor subscribed to every kind, which records rather than
     * reports. The engine's own exclusions run as they do in a build, so what comes back is the
     * population a rule sees today.
     */
    private static Walked record(graphql.schema.idl.TypeDefinitionRegistry registry, DSLContext dsl) {
        var recorder = new Recorder();
        new LintEngine(List.of(recorder)).run(registry, new StoreHandle(dsl, GRAPH));
        return new Walked(recorder.types, recorder.outputFields, recorder.inputFields,
            recorder.arguments, recorder.enumValues);
    }

    private static final class Recorder implements LintVisitor {
        private final Set<String> types = new LinkedHashSet<>();
        private final Set<String> outputFields = new LinkedHashSet<>();
        private final Set<String> inputFields = new LinkedHashSet<>();
        private final Set<String> arguments = new LinkedHashSet<>();
        private final Set<String> enumValues = new LinkedHashSet<>();
        /** The field currently being dispatched, so an argument can name the field it sits on. */
        private String lastField;

        @Override
        public LintRule rule() {
            return LintRule.TYPE_NAMES_PASCAL_CASE;
        }

        @Override
        public Set<LintNodeKind> kinds() {
            return EnumSet.of(LintNodeKind.OBJECT_TYPE, LintNodeKind.INTERFACE_TYPE,
                LintNodeKind.UNION_TYPE, LintNodeKind.ENUM_TYPE, LintNodeKind.INPUT_OBJECT_TYPE,
                LintNodeKind.SCALAR_TYPE, LintNodeKind.FIELD_DEFINITION,
                LintNodeKind.INPUT_FIELD_DEFINITION, LintNodeKind.ARGUMENT_DEFINITION,
                LintNodeKind.ENUM_VALUE_DEFINITION);
        }

        @Override
        public void inspect(LintTarget target, LintContext ctx) {
            String type = target.enclosingTypeName();
            switch (target.kind()) {
                case OBJECT_TYPE, INTERFACE_TYPE, UNION_TYPE, ENUM_TYPE, INPUT_OBJECT_TYPE,
                     SCALAR_TYPE -> types.add(target.name());
                case FIELD_DEFINITION -> {
                    lastField = target.name();
                    outputFields.add(type + "." + target.name());
                }
                case INPUT_FIELD_DEFINITION -> inputFields.add(type + "." + target.name());
                case ARGUMENT_DEFINITION ->
                    arguments.add(type + "." + lastField + "(" + target.name() + ":)");
                case ENUM_VALUE_DEFINITION -> enumValues.add(type + "." + target.name());
                default -> throw new IllegalStateException("unsubscribed kind " + target.kind());
            }
        }
    }

    // ------------------------------------------------------------------ the fixture

    private interface Body {
        void accept(graphql.schema.idl.TypeDefinitionRegistry registry, DSLContext dsl);
    }

    private static void withCapture(Body body) {
        withCapture(SDL, body);
    }

    /**
     * One text, read twice: parsed as the engine takes it today, and captured as the store holds
     * it. The bundled vocabulary is spelled into the parse because that parse is standalone, and
     * left out of the capture input because capture reads the bundled file itself.
     */
    private static void withCapture(String sdl, Body body) {
        Path directory = temporaryDirectory();
        var registry = new SchemaParser().parse(SchemaLoader.directivesSdl() + "\n" + sdl);
        try (var store = FactStores.inMemory()) {
            SeededStore.seedGraph(store.dsl(), GRAPH);
            var graph = new GraphIdentity(GRAPH, directory);
            var config = config(directory, sdl);
            var readAt = LocalDateTime.now();
            var documents = GraphQLSourceCapture.capture(store.dsl(), graph, config, readAt);
            GraphQLAstCapture.capture(store.dsl(), graph, documents, readAt);
            GraphitronAstCapture.capture(store.dsl(), graph, documents, readAt);
            GraphQLAssemblyCapture.capture(store.dsl(), graph, documents, readAt);
            body.accept(registry, store.dsl());
        }
    }

    private static SubjectConfig config(Path directory, String sdl) {
        try {
            Files.writeString(directory.resolve("schema.graphqls"), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return SubjectConfig.of(new SchemaRecipe(directory.resolve("pom.xml"),
            List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls")));
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("lint-population-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
