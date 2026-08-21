package no.sikt.graphitron.lsp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.server.GraphitronTextDocumentService;
import no.sikt.graphitron.lsp.state.StoreAccess;
import no.sikt.graphitron.lsp.state.StoreRead;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.model.boot.ReadBudget;
import no.sikt.graphitron.model.boot.StoreAnswer;
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
import org.slf4j.LoggerFactory;

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

    /**
     * A budget no production reader holds, standing in for the annotation reader's identity. That
     * reader takes the interactive budget in a real session, so a case asserting which connection
     * answered has to label the two apart; nothing here says an annotation read may take longer.
     */
    private static final ReadBudget ANNOTATION = new ReadBudget.Bounded(900);

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
            // Same-thread drain executor: the publish completes before the mutator returns, which
            // is the happens-before every assertion below leans on.
            var service = new GraphitronTextDocumentService(workspace, ignored -> {}, Runnable::run);
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
     * What a developer sees on the console when a read overruns, captured at the boundary's own
     * logger. The WARN names the read and carries no statement: which question the server gave up on
     * is the one fact a scanning developer can act on, say in a bug report, and grep for, while the
     * statement a drain issues runs to thousands of characters and pushes everything else out of
     * view. The statement is still what a fix needs, so it appears once at DEBUG on the same logger,
     * and the WARN points there. Asserted against the enum constant rather than a sentence, so
     * rewording the phrase does not break this while dropping the name does.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theWarningNamesTheReadAndTheStatementDropsToDebug() {
        try (var fixture = StoreFixture.of(tmp, SDL);
             var access = fixture.access(INTERACTIVE, SESSION_WIDE)) {
            fixture.makeRunaway("store_graph_source");

            var boundary = (Logger) LoggerFactory.getLogger(StoreAccess.class);
            var recorded = new ListAppender<ILoggingEvent>();
            recorded.start();
            Level inherited = boundary.getLevel();
            boundary.setLevel(Level.DEBUG);
            boundary.addAppender(recorded);
            try {
                var answer = access.answeringAll(StoreRead.DIAGNOSTICS,
                    List.of(fixture.sourceName()), handles -> "unreached");
                String sql = switch (answer) {
                    case StoreAnswer.OutOfBudget<String> expired -> expired.sql();
                    case StoreAnswer.Answered<String> unexpected -> throw new AssertionError(
                        "the runaway relation was read to completion: " + unexpected.value());
                };

                var warns = recorded.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN).toList();
                assertThat(warns).hasSize(1);
                assertThat(warns.getFirst().getFormattedMessage())
                    .as("the WARN names the read, points at the DEBUG logger, and carries no "
                        + "statement")
                    .contains(StoreRead.DIAGNOSTICS.phrase())
                    .contains(StoreAccess.class.getName())
                    .doesNotContain(sql);

                var debugs = recorded.list.stream()
                    .filter(event -> event.getLevel() == Level.DEBUG).toList();
                assertThat(debugs).hasSize(1);
                assertThat(debugs.getFirst().getFormattedMessage())
                    .as("the statement appears once, at DEBUG, under the same read's name")
                    .contains(StoreRead.DIAGNOSTICS.phrase())
                    .contains(sql);
            } finally {
                boundary.detachAppender(recorded);
                boundary.setLevel(inherited);
            }
        }
    }

    /**
     * The delegation split, asserted where it can be re-collapsed. {@code answering} used to be
     * implemented as {@code answeringAll(List.of(one), ...)}, and a later reader seeing two methods
     * doing the same thing would fold them back together; that would answer every keystroke on the
     * reader the drain owns, which is both the wrong budget and the head-of-line blocking two
     * readers exist to remove. The budgets are read back as settings, so this turns on which
     * connection answered and never on how long anything took.
     *
     * <p>{@code annotating} is here for the same reason and is the easier one to re-collapse, since
     * it differs from {@code answering} in neither shape nor budget, only in which connection it
     * holds. Folding it back would put an inlay request over a whole region in front of the cursor
     * again, which is what it was doing when a fifty-line request spent the whole interactive budget
     * and produced nothing.
     */
    @Test
    void eachDoorReachesTheReaderItsGrainStates() {
        try (var fixture = StoreFixture.of(tmp, SDL);
             var access = fixture.access(INTERACTIVE, ANNOTATION, SESSION_WIDE)) {
            String sourceName = fixture.sourceName();

            String onAnswering = answered(access.answering(StoreRead.HOVER, sourceName,
                handle -> budgetOf(handle.orElseThrow())));
            String onAnnotating = answered(access.annotating(StoreRead.INLAY_HINTS, sourceName,
                handle -> budgetOf(handle.orElseThrow())));
            String onAnsweringAll = answered(access.answeringAll(StoreRead.DIAGNOSTICS,
                List.of(sourceName), handles -> budgetOf(handles.of(sourceName).orElseThrow())));
            String onSessionGraph = answered(access.readingSessionGraph(
                StoreRead.DIRECTIVE_VOCABULARY, StoreOutOfBudgetTest::budgetOf));

            assertThat(onAnswering)
                .as("a keystroke answers on the interactive reader")
                .isEqualTo("500");
            assertThat(onAnnotating)
                .as("an annotation read answers on a reader of its own, so a cursor never queues "
                    + "behind a whole-region request")
                .isEqualTo("900");
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

        boolean singleAbsent = answered(workspace.answering(StoreRead.HOVER,
            "file:///nothing.graphqls", handle -> handle.isEmpty()));
        boolean bulkAbsent = answered(workspace.answeringAll(StoreRead.DIAGNOSTICS,
            List.of("file:///nothing.graphqls"),
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
