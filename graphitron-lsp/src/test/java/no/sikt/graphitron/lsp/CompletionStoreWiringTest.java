package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.completions.ClassNameCompletions;
import no.sikt.graphitron.lsp.completions.CompletionContext;
import no.sikt.graphitron.lsp.completions.Completions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.state.StoreAccess;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.ValidationReport;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Completion end to end through the session's store: the request boundary resolves the open document
 * to its graph, the arm queries inside that read, and the answer is that graph's census and no other's.
 *
 * <p>Two properties a per-arm test cannot reach. That a store shared by a whole workspace does not
 * leak one module's classes into another module's popup, which is the failure the scoping predicate
 * exists to prevent and which no projection could even have. And that a session with no store answers
 * a store-backed arm with nothing while the arm that reads no facts keeps working, so the absence
 * costs exactly the arms whose subject is missing.
 */
class CompletionStoreWiringTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

    /** A cursor inside {@code @service}'s empty className value, the class-name arm's trigger. */
    private static final String SOURCE =
        "type Query { x: Int @service(service: {className: \"\", method: \"foo\"}) }\n";

    @Test
    void oneGraphsPopupDoesNotOfferAnothersClasses(@TempDir Path tmp) {
        try (var fixture = StoreFixture.of(tmp, "api", "type Query { x: Int }\n",
                List.of(StoreFixture.reference("com.example.ApiService", List.of(), List.of(),
                    "/nonexistent/api.jar")))
                .andGraph(tmp, "billing", "type Query { y: Int }\n",
                    List.of(StoreFixture.reference("com.example.BillingService", List.of(), List.of(),
                        "/nonexistent/billing.jar")))) {

            assertThat(classNames(fixture.handleFor("api"))).containsExactly("com.example.ApiService");
            assertThat(classNames(fixture.handleFor("billing")))
                .as("the sibling module's class is in the same store and is not a candidate here")
                .containsExactly("com.example.BillingService");
        }
    }

    @Test
    void theOpenDocumentsOwnGraphAnswersForIt(@TempDir Path tmp) {
        try (var fixture = StoreFixture.of(tmp, StoreFixture.GRAPH, "type Query { x: Int }\n",
                List.of(StoreFixture.jarClass("com.example.FilmService", List.of())));
             var access = new StoreAccess(fixture.reader(), StoreFixture.GRAPH)) {

            var workspace = new Workspace();
            workspace.setStore(access);
            String uri = ValidationReport.canonicalUri(fixture.sourceName());
            workspace.didOpen(uri, 1, SOURCE);

            assertThat(completionAt(workspace, uri)).extracting(CompletionItem::getLabel)
                .containsExactly("com.example.FilmService");
        }
    }

    @Test
    void aSessionWithNoStoreOffersNothingForAStoreBackedArm(@TempDir Path tmp) {
        var workspace = new Workspace();
        String uri = tmp.resolve("unstored.graphqls").toUri().toString();
        workspace.didOpen(uri, 1, SOURCE);

        assertThat(completionAt(workspace, uri))
            .as("no store is not a degraded store: there is no census to complete against")
            .isEmpty();
    }

    @Test
    void aSessionWithNoStoreStillCompletesArgumentNames(@TempDir Path tmp) {
        // The fallback arm reads the directive vocabulary rather than the store, so it is unaffected:
        // the absence costs exactly the arms whose subject is the store.
        var workspace = new Workspace();
        String uri = tmp.resolve("unstored.graphqls").toUri().toString();
        String source = "type Query { x: Int @service(service: {className: \"x\"}, ) }\n";
        workspace.didOpen(uri, 1, source);

        // Cursor on the space after the comma, where the next argument name would go.
        assertThat(completionAt(workspace, uri, source, new Point(0, source.indexOf(", ") + 2)))
            .extracting(CompletionItem::getLabel)
            .contains("contextArguments");
    }

    private static List<String> classNames(StoreHandle handle) {
        var bytes = SOURCE.getBytes(StandardCharsets.UTF_8);
        Point cursor = new Point(0, SOURCE.indexOf('"') + 1);
        var directive = directiveAt(bytes, cursor);
        var location = VOCAB.locateAt(directive, cursor, bytes).orElseThrow();
        return ClassNameCompletions.generate(VOCAB, handle, CompletionContext.from(location, bytes))
            .stream().map(CompletionItem::getLabel).toList();
    }

    private static List<CompletionItem> completionAt(Workspace workspace, String uri) {
        return completionAt(workspace, uri, SOURCE, new Point(0, SOURCE.indexOf('"') + 1));
    }

    private static List<CompletionItem> completionAt(
        Workspace workspace, String uri, String source, Point cursor
    ) {
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var directive = directiveAt(bytes, cursor);
        return Completions.at(workspace, uri, directive, cursor,
            new Position(cursor.row(), cursor.column()), bytes);
    }

    private static Directives.Directive directiveAt(byte[] bytes, Point cursor) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        var tree = parser.parse(new String(bytes, StandardCharsets.UTF_8)).orElseThrow();
        return Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected a directive at " + cursor));
    }
}
