package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.server.GraphitronTextDocumentService;
import no.sikt.graphitron.lsp.state.StoreAccess;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.model.boot.ReadBudget;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.Rejection;
import graphql.language.SourceLocation;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static no.sikt.graphitron.model.test.StoreAnswers.answered;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the language server does when a store read runs out of its budget, and which reader each
 * door reaches.
 *
 * <p>Two postures are asserted here rather than in the surface tests, because they are the two the
 * sealed arm exists for: they are <em>not</em> the same as answering absent. A drain that publishes
 * an empty list has erased the developer's squiggles, and a vocabulary that degrades to the empty
 * one has silenced every surface for every file until the next build. Both would be invisible to a
 * case that only checked that the request came back.
 *
 * <p>Nothing here asserts a duration. An overrun is provoked by making a relation the production
 * query reads non-terminating, so the case turns on an arm rather than on a clock; the door-routing
 * case turns on a session setting. {@code RunawayRelation} carries the reasoning.
 */
class StoreOutOfBudgetTest {

    private static final String SDL = "type Foo { x: Int }\n";

    /** Distinguishable numbers, asserted as settings and never as time. */
    private static final ReadBudget INTERACTIVE = new ReadBudget.Bounded(500);
    private static final ReadBudget SESSION_WIDE = new ReadBudget.Bounded(1_500);

    /** How H2 reports the statement budget of the session a read is running on. */
    private static final String QUERY_TIMEOUT =
        "SELECT setting_value FROM information_schema.settings WHERE setting_name = 'QUERY_TIMEOUT'";

    @TempDir
    Path tmp;

    /**
     * The case the arm exists for. A drain whose read overruns publishes <em>nothing</em>, so the
     * warnings the last drain put on screen stay there; publishing an empty list instead would clear
     * them, which is a worse outcome than the timeout itself and is what an absence-shaped degrade
     * could not have avoided.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aDrainThatRunsOutOfBudgetLeavesThePreviousPublishStanding() {
        try (var fixture = StoreFixture.of(tmp, SDL);
             var access = fixture.access(INTERACTIVE, SESSION_WIDE)) {
            String uri = Path.of(fixture.sourceName()).toUri().toString();
            var workspace = new Workspace();
            workspace.setStore(access);
            var service = new GraphitronTextDocumentService(workspace);
            var client = new RecordingClient();
            service.setClient(client);

            fixture.withValidationErrors(List.of(new ValidationError("Foo.x",
                Rejection.structural("invalid type"),
                new SourceLocation(1, 1, fixture.sourceName()))));
            workspace.didOpen(uri, 1, SDL);

            assertThat(client.published).hasSize(1);
            assertThat(client.published.getFirst().getDiagnostics())
                .as("the squiggle this case is about not losing")
                .isNotEmpty();

            // The drain resolves each queued document's graph membership before it reads a fact, so
            // this is the first statement of every drain from here on.
            fixture.makeRunaway("store_graph_source");
            workspace.markAllForRecalculation();

            assertThat(client.published)
                .as("nothing at all on the wire, so the client keeps rendering what it has; an "
                    + "empty list here would have cleared the developer's warnings")
                .hasSize(1);
        }
    }

    /**
     * The vocabulary is session state, not one answer, so a reload that overruns keeps the last good
     * one. Degrading it to the empty vocabulary would resolve no cursor to any coordinate on every
     * surface for every file until the next build, silently.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aVocabularyReloadThatRunsOutOfBudgetKeepsTheLastGoodOne() {
        try (var fixture = StoreFixture.of(tmp, SDL);
             var access = fixture.access(INTERACTIVE, SESSION_WIDE)) {
            var workspace = new Workspace();
            workspace.setStore(access);

            var loaded = workspace.vocabulary();
            assertThat(loaded)
                .as("the graph's own directive surface, which is what there is to lose")
                .isNotEqualTo(LspVocabulary.empty());

            // The relation the directive surface is read from.
            fixture.makeRunaway("graphql_directive");
            workspace.markAllForRecalculation();

            assertThat(workspace.vocabulary())
                .as("the same instance, not an empty replacement")
                .isSameAs(loaded);
        }
    }

    /**
     * The delegation split, asserted where it can be re-collapsed. {@code answering} used to be
     * implemented as {@code answeringAll(List.of(one), ...)}, and a later reader seeing two methods
     * doing the same thing would fold them back together; that would answer every keystroke on the
     * reader the drain owns, which is both the wrong budget and the head-of-line blocking two
     * readers exist to remove. The budgets are read back as settings, so this turns on which
     * connection answered and never on how long anything took.
     */
    @Test
    void eachDoorReachesTheReaderItsGrainStates() {
        try (var fixture = StoreFixture.of(tmp, SDL);
             var access = fixture.access(INTERACTIVE, SESSION_WIDE)) {
            String sourceName = fixture.sourceName();

            String onAnswering = answered(
                access.answering(sourceName, handle -> budgetOf(handle.orElseThrow())));
            String onAnsweringAll = answered(access.answeringAll(List.of(sourceName),
                handles -> budgetOf(handles.of(sourceName).orElseThrow())));
            String onSessionGraph = answered(
                access.readingSessionGraph(StoreOutOfBudgetTest::budgetOf));

            assertThat(onAnswering)
                .as("a keystroke answers on the interactive reader")
                .isEqualTo("500");
            assertThat(onAnsweringAll)
                .as("the drain answers on the session-wide reader")
                .isEqualTo("1500");
            assertThat(onSessionGraph)
                .as("session state shares the drain's reader, the two never contending")
                .isEqualTo("1500");
        }
    }

    /** A session with no store answers absent rather than out of budget: there was nothing to read. */
    @Test
    void aSessionWithNoStoreAnswersAbsentRatherThanOutOfBudget() {
        var workspace = new Workspace();

        boolean singleAbsent = answered(
            workspace.answering("file:///nothing.graphqls", handle -> handle.isEmpty()));
        boolean bulkAbsent = answered(workspace.answeringAll(List.of("file:///nothing.graphqls"),
            handles -> handles.apply("file:///nothing.graphqls").isEmpty()));

        assertThat(singleAbsent).isTrue();
        assertThat(bulkAbsent).isTrue();
    }

    /** The statement budget of whichever reader's connection this handle is riding. */
    private static String budgetOf(StoreHandle handle) {
        return handle.dsl().fetchOne(QUERY_TIMEOUT).get(0, String.class);
    }

    private static final class RecordingClient implements LanguageClient {
        final List<PublishDiagnosticsParams> published = new ArrayList<>();

        @Override public void telemetryEvent(Object object) {}
        @Override public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
            published.add(diagnostics);
        }
        @Override public void showMessage(MessageParams messageParams) {}
        @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams r) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void logMessage(MessageParams message) {}
    }
}
