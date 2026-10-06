package no.sikt.graphitron.rewrite;

import graphql.language.AstPrinter;
import graphql.language.ObjectTypeDefinition;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.read.StoreHandle;
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
            try (var store = GraphitronStore.captured(ctx)) {
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
            result = new GraphQLRewriteGenerator(ctx, new StoreHandle(store.dsl(), ctx.graphName()))
                .generate();
        }
        Path schema = result.emitted().stream()
            .filter(path -> path.getFileName().toString().equals("schema.graphqls"))
            .findFirst().orElseThrow();
        return new SchemaParser().parse(Files.readString(schema, StandardCharsets.UTF_8));
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
