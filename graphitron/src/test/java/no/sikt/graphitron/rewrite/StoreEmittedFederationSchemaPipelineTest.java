package no.sikt.graphitron.rewrite;

import graphql.language.AstPrinter;
import graphql.language.ObjectTypeDefinition;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.lint.LintConfig;
import no.sikt.graphitron.model.lint.LintRule;
import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.LoadingRewrites;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ASSEMBLY_SYNTHESISED_LINK;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The federation schema a run publishes, read off the file {@link GraphQLRewriteGenerator#generate()}
 * writes over a captured store, which is the path a build takes.
 *
 * <p>{@link ConnectionFederationTagPipelineTest} covers tag inheritance through the walk's own
 * assembly, which never reaches the store; the emitted registry is derived from the store, so the
 * cases here are the ones that see what a consumer's gateway sees. Two inputs tagged differently, the
 * shape a feature-file layout produces: a node type with no authored {@code @key} gets the
 * synthesised one exactly once, a {@code PageInfo} its author declared keeps the author's
 * declaration untagged, and the connection types nobody declared take the carrier's tag. And a graph
 * federated only through a configured tag, with no {@code @link} in any document, still gets its
 * synthesised key, which is the opt-in arm no document states.
 *
 * <p>The types several carriers share carry the tags every carrier carries, which is Apollo's
 * contract rule that a type's tags appear on every field returning it: a contract excluding one
 * carrier's tag keeps the shared type for the others. {@code @shareable} is the other way round,
 * a shared type being shareable when any carrier is. Where carriers disagree on a tag the build
 * says so, under a rule an exclude-based subgraph disables.
 */
@PipelineTier
class StoreEmittedFederationSchemaPipelineTest {

    private static final String LINK = """
        extend schema @link(url: "https://specs.apollo.dev/federation/v2.10",
          import: ["@key", "@shareable", "@tag"])
        """;

    private static final String NODE = """
        interface Node { id: ID! }

        type Query { film: Film }

        type Film implements Node @table(name: "film") @node {
          id: ID! @nodeId
          title: String
        }
        """;

    private static final String KEY_DECLARATION = """
        directive @key(fields: String!, resolvable: Boolean = true) repeatable on OBJECT | INTERFACE
        """;

    private static final String DECLARED_PAGE_INFO = """
        type PageInfo @shareable {
          hasPreviousPage: Boolean!
          hasNextPage: Boolean!
          startCursor: String
          endCursor: String
        }
        """;

    private static final String CONNECTION = """
        extend type Query {
          films: [Film!]! @asConnection @defaultOrder(primaryKey: true)
        }
        """;

    /** The reporter's shape: one carrier per feature file, each tagged by its input, and no declared PageInfo. */
    private static final String STABLE_CARRIER = """
        extend type Query {
          stableFilms: [Film!]! @asConnection @defaultOrder(primaryKey: true)
        }
        """;

    private static final String EXPERIMENTAL_CARRIER = """
        extend type Query {
          experimentalFilms: [Film!]! @asConnection @defaultOrder(primaryKey: true)
        }
        """;

    @Test
    @DisplayName("a stable node, a declared PageInfo and an experimental connection emit as written")
    void theStableFileEmitsAsWritten(@TempDir Path tmp) throws IOException {
        var ctx = context(tmp,
            input(tmp, "stable.graphqls", LINK + NODE + DECLARED_PAGE_INFO, "stable"),
            input(tmp, "experimental.graphqls", CONNECTION, "experimental"));

        var emitted = generate(ctx);

        assertThat(keys(emitted, "Film"))
            .as("the synthesised key, once")
            .containsExactly("@key(fields: \"id\", resolvable: true)");
        assertThat(typeDirectives(emitted, "PageInfo"))
            .as("the author's PageInfo takes no type-level tag from the experimental carrier")
            .containsExactly("@shareable");
        assertThat(tags(emitted, "QueryFilmsConnection")).containsExactly("experimental");
        assertThat(tags(emitted, "QueryFilmsConnectionEdge")).containsExactly("experimental");
    }

    @Test
    @DisplayName("a PageInfo nobody declared is minted and inherits the carrier's tag")
    void aMintedPageInfoStillInherits(@TempDir Path tmp) throws IOException {
        var ctx = context(tmp,
            input(tmp, "stable.graphqls", LINK + NODE, "stable"),
            input(tmp, "experimental.graphqls", CONNECTION, "experimental"));

        var emitted = generate(ctx);

        assertThat(tags(emitted, "PageInfo")).containsExactly("experimental");
    }

    @Test
    @DisplayName("a PageInfo two differently tagged carriers share is untagged, and each connection keeps its own tag")
    void aSharedPageInfoCarriesNoDisagreeingTag(@TempDir Path tmp) throws IOException {
        var ctx = context(tmp,
            input(tmp, "stable.graphqls", LINK + NODE + STABLE_CARRIER, "stable"),
            input(tmp, "experimental.graphqls", EXPERIMENTAL_CARRIER, "experimental"));

        var emitted = generate(ctx);

        assertThat(tags(emitted, "PageInfo"))
            .as("a contract excluding either tag keeps PageInfo for the other carrier")
            .isEmpty();
        assertThat(tags(emitted, "QueryStableFilmsConnection")).containsExactly("stable");
        assertThat(tags(emitted, "QueryStableFilmsConnectionEdge")).containsExactly("stable");
        assertThat(tags(emitted, "QueryExperimentalFilmsConnection")).containsExactly("experimental");
        assertThat(tags(emitted, "QueryExperimentalFilmsConnectionEdge")).containsExactly("experimental");
    }

    @Test
    @DisplayName("a connection shared through connectionName carries the tag its carriers agree on, and only that")
    void aSharedConnectionCarriesTheTagsItsCarriersAgreeOn(@TempDir Path tmp) throws IOException {
        var ctx = context(tmp,
            input(tmp, "stable.graphqls", LINK + NODE + """
                extend type Query {
                  stableFilms: [Film!]! @asConnection(connectionName: "FilmConnection")
                    @defaultOrder(primaryKey: true) @tag(name: "public")
                }
                """, "stable"),
            input(tmp, "experimental.graphqls", """
                extend type Query {
                  experimentalFilms: [Film!]! @asConnection(connectionName: "FilmConnection")
                    @defaultOrder(primaryKey: true) @tag(name: "public")
                }
                """, "experimental"));

        var emitted = generate(ctx);

        assertThat(tags(emitted, "FilmConnection")).containsExactly("public");
        assertThat(tags(emitted, "FilmConnectionEdge")).containsExactly("public");
        assertThat(tags(emitted, "PageInfo")).containsExactly("public");
    }

    /**
     * A declared Connection with no {@code pageInfo} field returns no {@code PageInfo}, so its tag
     * has no say in the minted one's: {@code PageInfo} takes the {@code @asConnection} carrier's
     * tag alone.
     */
    @Test
    @DisplayName("a declared connection with no pageInfo field leaves PageInfo to the generated connection's carrier")
    void aDeclaredConnectionWithoutPageInfoDoesNotNarrowIt(@TempDir Path tmp) throws IOException {
        var ctx = context(tmp,
            input(tmp, "stable.graphqls", LINK + NODE + STABLE_CARRIER, "stable"),
            input(tmp, "experimental.graphqls", """
                type FilmLinkConnection @tag(name: "experimental") {
                  edges: [FilmLinkEdge!]!
                  nodes: [Film]!
                  totalCount: Int
                }
                type FilmLinkEdge {
                  cursor: String!
                  node: Film
                }
                extend type Query {
                  linkedFilms: FilmLinkConnection! @defaultOrder(primaryKey: true)
                }
                """, "experimental"));

        var emitted = generate(ctx);

        assertThat(tags(emitted, "PageInfo")).containsExactly("stable");
        assertThat(tags(emitted, "FilmLinkConnection"))
            .as("the declared connection, as written")
            .containsExactly("experimental");
    }

    @Test
    @DisplayName("@shareable on a carrier reaches its connection, its edge and the shared PageInfo")
    void aShareableCarrierMakesItsGeneratedTypesShareable(@TempDir Path tmp) throws IOException {
        var ctx = context(tmp,
            input(tmp, "stable.graphqls", LINK + NODE + """
                extend type Query {
                  stableFilms: [Film!]! @asConnection @defaultOrder(primaryKey: true) @shareable
                }
                """, "stable"),
            input(tmp, "experimental.graphqls", EXPERIMENTAL_CARRIER, "experimental"));

        var emitted = generate(ctx);

        assertThat(shareable(emitted, "QueryStableFilmsConnection")).isTrue();
        assertThat(shareable(emitted, "QueryStableFilmsConnectionEdge")).isTrue();
        assertThat(shareable(emitted, "PageInfo"))
            .as("shareable when any carrier is")
            .isTrue();
        assertThat(shareable(emitted, "QueryExperimentalFilmsConnection")).isFalse();
        assertThat(shareable(emitted, "QueryExperimentalFilmsConnectionEdge")).isFalse();
    }

    // ===== The narrowing warning =====

    @Test
    @DisplayName("carriers disagreeing on a tag raise one shared-type-tags-narrowed finding, at a carrier")
    void disagreeingCarriersAreReported(@TempDir Path tmp) throws IOException {
        var ctx = context(tmp,
            input(tmp, "stable.graphqls", LINK + NODE + STABLE_CARRIER, "stable"),
            input(tmp, "experimental.graphqls", EXPERIMENTAL_CARRIER, "experimental"));

        assertThat(narrowedFindings(ctx)).singleElement().satisfies(finding -> {
            assertThat(finding.message()).contains("'PageInfo'");
            assertThat(finding.location()).as("located at a carrier").isNotNull();
            assertThat(finding.location().getSourceName()).endsWith(".graphqls");
        });
    }

    @Test
    @DisplayName("the narrowing finding is suppressed by disabling its rule")
    void theNarrowingFindingIsSuppressible(@TempDir Path tmp) throws IOException {
        var ctx = context(tmp,
            input(tmp, "stable.graphqls", LINK + NODE + STABLE_CARRIER, "stable"),
            input(tmp, "experimental.graphqls", EXPERIMENTAL_CARRIER, "experimental"))
            .withLintConfig(LintConfig.validated(
                Set.of(LintRule.SHARED_TYPE_TAGS_NARROWED.id()), List.of()));

        assertThat(narrowedFindings(ctx)).isEmpty();
    }

    @Test
    @DisplayName("a single carrier narrows nothing and raises no finding")
    void aSingleCarrierIsNotReported(@TempDir Path tmp) throws IOException {
        var ctx = context(tmp,
            input(tmp, "stable.graphqls", LINK + NODE, "stable"),
            input(tmp, "experimental.graphqls", CONNECTION, "experimental"));

        assertThat(narrowedFindings(ctx)).isEmpty();
    }

    /**
     * The synthesised {@code @link} imports {@code @tag} alone, so a graph with no {@code @link}
     * of its own declares {@code @key} itself, which is what the build's refusal tells an author
     * without it to do. Its node type then gets its key from the store's second opt-in arm, and
     * from nowhere else: no document states the link that federates it.
     */
    @Test
    @DisplayName("a graph federated only through a configured tag keeps its one synthesised key")
    void aTagOnlyGraphKeepsItsKey(@TempDir Path tmp) throws IOException {
        var ctx = context(tmp, input(tmp, "stable.graphqls", KEY_DECLARATION + NODE, "stable"));

        var emitted = generate(ctx);

        assertThat(keys(emitted, "Film"))
            .containsExactly("@key(fields: \"id\", resolvable: true)");
    }

    /**
     * The store's tag arm and the generator's are one function, {@code TagLinkSynthesiser} inside
     * {@link LoadingRewrites#apply}, run by capture over the recipe's inputs and by the generator over
     * the context's. What this pins is that the two are handed the same inputs, so the row is
     * present exactly when the generator's own composition says it synthesised the link.
     */
    @Test
    @DisplayName("the store records the synthesised link exactly when the generator's composition adds it")
    void theStoreAgreesWithTheGeneratorsComposition(@TempDir Path tmp) throws IOException {
        var fixtures = List.of(
            context(tmp.resolve("tag-only"),
                input(tmp.resolve("tag-only"), "stable.graphqls", KEY_DECLARATION + NODE,
                    "stable")),
            context(tmp.resolve("tagged-and-linked"),
                input(tmp.resolve("tagged-and-linked"), "stable.graphqls", LINK + NODE, "stable"),
                input(tmp.resolve("tagged-and-linked"), "experimental.graphqls", CONNECTION,
                    "experimental")),
            context(tmp.resolve("untagged"),
                input(tmp.resolve("untagged"), "plain.graphqls", NODE, null)));

        var recorded = new ArrayList<Boolean>();
        for (var ctx : fixtures) {
            boolean stored;
            try (var store = GraphitronStore.captured(ctx).store()) {
                stored = store.dsl().fetchExists(GRAPHQL_ASSEMBLY_SYNTHESISED_LINK,
                    GRAPHQL_ASSEMBLY_SYNTHESISED_LINK.GRAPH_NAME.eq(ctx.graphName()));
            }
            assertThat(stored).as("%s", ctx.basedir().getFileName())
                .isEqualTo(generatorSynthesises(ctx));
            recorded.add(stored);
        }
        assertThat(recorded).as("the fixtures cover both answers").containsExactly(true, false, false);
    }

    // ---------------------------------------------------------------------------------------

    private static SchemaInput input(Path dir, String name, String sdl, String tag) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.writeString(file, sdl);
        return new SchemaInput(SchemaSource.file(file), Optional.ofNullable(tag), Optional.empty());
    }

    private static RunContext context(Path dir, SchemaInput... inputs) throws IOException {
        Files.createDirectories(dir);
        return new RunContext(List.of(inputs), dir, "StoreEmittedFederationSchemaPipelineTest",
            dir.resolve("out"), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
    }

    /** The generator's own composition over the context's inputs, as its registry load runs it. */
    private static boolean generatorSynthesises(RunContext ctx) {
        var sources = ctx.schemaInputs().stream()
            .map(input -> (SchemaSource.File) input.source()).toList();
        var registry = SchemaLoader.parsePerSource(sources).registry();
        return LoadingRewrites.apply(registry, ctx.schemaInputs())
            instanceof LoadingRewrites.Outcome.Applied applied && applied.synthesisedLink();
    }

    /** Runs the generator over a store captured from {@code ctx} and parses the schema it wrote. */
    private static TypeDefinitionRegistry generate(RunContext ctx) throws IOException {
        GraphQLRewriteGenerator.GenerationResult result;
        try (var store = GraphitronStore.captured(ctx)) {
            result = new GraphQLRewriteGenerator(ctx, store)
                .generate();
        }
        Path schema = result.emitted().stream()
            .filter(path -> path.getFileName().toString().equals("schema.graphqls"))
            .findFirst().orElseThrow();
        return new SchemaParser().parse(Files.readString(schema, StandardCharsets.UTF_8));
    }

    /** The {@code shared-type-tags-narrowed} findings in the report the build assembles. */
    private static List<BuildWarning.LintFinding> narrowedFindings(RunContext ctx) {
        ValidationReport report;
        try (var store = GraphitronStore.captured(ctx)) {
            report = new GraphQLRewriteGenerator(ctx, store)
                .buildOutput().report();
        }
        return report.warnings().stream()
            .filter(BuildWarning.LintFinding.class::isInstance)
            .map(BuildWarning.LintFinding.class::cast)
            .filter(f -> f.rule() == LintRule.SHARED_TYPE_TAGS_NARROWED)
            .toList();
    }

    private static boolean shareable(TypeDefinitionRegistry registry, String type) {
        return typeDirectives(registry, type).contains("@shareable");
    }

    private static List<String> typeDirectives(TypeDefinitionRegistry registry, String type) {
        var definition = registry.getTypeOrNull(type, ObjectTypeDefinition.class);
        assertThat(definition).as(type).isNotNull();
        return definition.getDirectives().stream().map(AstPrinter::printAst).toList();
    }

    private static List<String> keys(TypeDefinitionRegistry registry, String type) {
        return typeDirectives(registry, type).stream().filter(d -> d.startsWith("@key")).toList();
    }

    private static List<String> tags(TypeDefinitionRegistry registry, String type) {
        var definition = registry.getTypeOrNull(type, ObjectTypeDefinition.class);
        assertThat(definition).as(type).isNotNull();
        return definition.getDirectives("tag").stream()
            .map(d -> ((graphql.language.StringValue) d.getArgument("name").getValue()).getValue())
            .toList();
    }
}
