package no.sikt.graphitron.lsp;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.lsp.completions.FieldCompletions;
import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.fixtures.R157FilmPojo;
import no.sikt.graphitron.lsp.fixtures.R157FilmRecord;
import no.sikt.graphitron.lsp.hover.Hovers;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.model.classpath.CompletionData;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Primary tier for @field member completion: a real {@code .graphqls}
 * containing one SDL type per {@link TypeBackingShape} arm, classified
 * through the real
 * {@link GraphitronSchemaBuilder}, with real backing classes
 * ({@link R157FilmRecord}, {@link R157FilmPojo}) discoverable on the test
 * classpath through {@link no.sikt.graphitron.model.classpath.ClasspathScanner}.
 *
 * <p>Drives {@link CatalogBuilder#projectTypesByName} for real over that classifier's output,
 * then exercises {@link FieldCompletions}, {@link Diagnostics}, and {@link Hovers} against a
 * store captured from the same scan. This is the test most likely to catch silent classifier
 * widening: if a future change to the classifier produced a {@code PojoResultType.Backed} where
 * a {@link GraphitronType.JavaRecordType} was warranted, the {@code recordBacked...} assertion
 * below would fail.
 */
class R157PipelineTest {

    private static final String JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.test.jooq";

    @TempDir
    static Path tmp;

    @Test
    void recordBackedTypeSurfacesComponentsThroughThePipeline() {
        var artefacts = build("""
            type FilmCard {
                filmId: Int @field(name: "filmId")
                title: String @field(name: "title")
            }
            type Query {
                x: FilmCard @service(service: {className: "no.sikt.graphitron.lsp.fixtures.R157Service", method: "makeFilmRecord"})
            }
            """);

        // The projection names the class; that its components are filmId and title is the store's
        // answer, which the completion and diagnostic legs below read.
        assertThat(artefacts.backing().get("FilmCard"))
            .isEqualTo(new TypeBackingShape.RecordBacking(
                "no.sikt.graphitron.lsp.fixtures.R157FilmRecord"));

        var completions = completionsAt(artefacts,
            "type FilmCard {\n"
                + "    filmId: Int @field(name: \"\")\n"
                + "    title: String @field(name: \"title\")\n"
                + "}\n", 1);
        assertThat(completions).extracting(CompletionItem::getLabel)
            .containsExactly("filmId", "title");

        var diags = diagnosticsFor(artefacts,
            "type FilmCard {\n"
                + "    filmId: Int @field(name: \"TYPO\")\n"
                + "}\n");
        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("TYPO").contains("component");
    }

    @Test
    void pojoBackedTypeSurfacesBeanAccessorsThroughThePipeline() {
        var artefacts = build("""
            type FilmPojoView {
                filmId: Int @field(name: "filmId")
                title: String @field(name: "title")
            }
            type Query {
                x: FilmPojoView @service(service: {className: "no.sikt.graphitron.lsp.fixtures.R157Service", method: "makeFilmPojo"})
            }
            """);

        assertThat(artefacts.backing().get("FilmPojoView"))
            .isEqualTo(new TypeBackingShape.PojoBacking(
                "no.sikt.graphitron.lsp.fixtures.R157FilmPojo"));
        // The bean rule itself is the store's; that this class's accessors reach the author as member
        // names is what the completion leg of the record case pins, over the same census.
        assertThat(completionsAt(artefacts,
            "type FilmPojoView {\n"
                + "    filmId: Int @field(name: \"\")\n"
                + "}\n", 1))
            .extracting(CompletionItem::getLabel)
            .contains("filmId", "title");
    }

    @Test
    void rootTypeProjectsToNoBackingRoot() {
        var artefacts = build("""
            type Query {
                x: Int
            }
            """);
        assertThat(artefacts.backing().get("Query"))
            .isInstanceOf(TypeBackingShape.NoBacking.Root.class);
    }

    @Test
    void plainInterfaceProjectsToNoBackingUnclassifiedInterface() {
        var artefacts = build("""
            interface Shape {
                id: ID
            }
            type Box implements Shape {
                id: ID
            }
            type Query { x: Shape }
            """);
        assertThat(artefacts.backing().get("Shape"))
            .isInstanceOf(TypeBackingShape.NoBacking.UnclassifiedInterface.class);
    }

    // ---- Pipeline helpers ----

    private record Artefacts(
        String sdl,
        TypeDefinitionRegistry registry,
        no.sikt.graphitron.rewrite.GraphitronSchema schema,
        CompletionData catalog,
        java.util.Map<String, TypeBackingShape> backing
    ) {}

    /**
     * Loads {@code directives.graphqls} + the {@code Node} interface, parses
     * the combined registry, runs the real classifier, runs the real catalog
     * builder over the LSP module's {@code target/test-classes}, and projects
     * the walk's backing resolution.
     */
    private static Artefacts build(String schemaText) {
        var registry = parse(prelude(schemaText) + schemaText);
        var ctx = testContextWithTestClasses();
        var schema = GraphitronSchemaBuilder.build(registry, ctx);
        var jooq = new JooqCatalog(JOOQ_PACKAGE);
        // GraphitronSchemaBuilder.buildBundle would have given us the assembled
        // GraphQLSchema; rebuilding it here keeps the test focused on what the
        // walk bound each type to rather than on the assembled-schema construction.
        var bundle = GraphitronSchemaBuilder.buildBundle(registry, ctx);
        var catalog = CatalogBuilder.build(jooq, bundle.assembled(), ctx);
        return new Artefacts(schemaText, registry, schema, catalog,
            CatalogBuilder.projectTypesByName(schema));
    }

    private static String prelude(String schemaText) {
        var sb = new StringBuilder();
        sb.append(loadDirectives()).append('\n');
        if (!schemaText.contains("interface Node")) {
            sb.append("interface Node { id: ID! }\n");
        }
        return sb.toString();
    }

    private static String loadDirectives() {
        try (InputStream is = R157PipelineTest.class.getResourceAsStream(
            "/no/sikt/graphitron/model/schema/directives.graphqls")) {
            if (is == null) {
                throw new IllegalStateException("directives.graphqls not found on test classpath");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static TypeDefinitionRegistry parse(String sdl) {
        return new SchemaParser().parse(sdl);
    }

    /**
     * Test context with the LSP module's {@code target/test-classes} root
     * exposed to {@link CatalogBuilder#build}'s classpath scan, so the
     * fixtures ({@link R157FilmRecord}, {@link R157FilmPojo}) surface in
     * {@link CompletionData#externalReferences()}.
     */
    private static RunContext testContextWithTestClasses() {
        Path testClasses = testClassesRoot();
        return new RunContext(
            List.of(), Path.of(""), "R157PipelineTest", Path.of(""), "fake.output", JOOQ_PACKAGE, List.of(testClasses)
        );
    }

    private static Path testClassesRoot() {
        try {
            return Paths.get(R157FilmRecord.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- LSP-side drivers ----

    private static List<CompletionItem> completionsAt(Artefacts artefacts, String source, int line) {
        Point cursor = new Point(line, source.split("\n")[line].indexOf("\"\"") + 1);
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("no directive at cursor"));
        var vocab = BundledVocabulary.get();
        var locOpt = vocab.locateAt(directive, cursor, bytes);
        if (locOpt.isEmpty()) return List.of();
        var context = no.sikt.graphitron.lsp.completions.CompletionContext.from(locOpt.get(), bytes);
        // Both halves of the arm come from the one scan this test ran: the store got the census
        // through capture, and names the backing class and what it offers off the same rows.
        try (var store = storeOver(artefacts)) {
            return FieldCompletions.generate(vocab, store.handle(), context, directive, bytes);
        }
    }

    private static List<org.eclipse.lsp4j.Diagnostic> diagnosticsFor(Artefacts artefacts, String source) {
        var file = WorkspaceFileTestSupport.snapshot(source);
        try (var store = storeOver(artefacts)) {
            return Diagnostics.compute(BundledVocabulary.get(), "", file, Optional.of(store.handle()));
        }
    }

    /**
     * A store over the same schema and the same class census the projection was built from. Taking
     * the census off the catalog rather than re-scanning is what makes this one pipeline: the
     * classifier, the projection and the store all answer about the classes this test's own scan
     * read. The schema has to be the same one too, now that which class backs a type is the store's
     * answer rather than the permit's.
     */
    private static StoreFixture storeOver(Artefacts artefacts) {
        return StoreFixture.of(tmp, artefacts.sdl(), artefacts.catalog().externalReferences());
    }
}
