package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.server.GraphitronTextDocumentService;
import no.sikt.graphitron.lsp.state.Workspace;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The diagnostics drain runs on the injected executor rather than the thread that triggered it,
 * and the properties that asynchrony rests on hold: a mutator returns while a drain is in
 * flight, submits during one drain collapse into at most one follow-up, a file closed between
 * the drain's walk and its publish is never published for after its {@code didClose} clear, and
 * a submit rejected by a shut-down executor is absorbed rather than thrown into the mutator.
 *
 * <p>Every assertion here is ordering, never a duration. The drain is held on a latch inside the
 * client's publish, which sits downstream of the store read on the same drain thread, so "held
 * here" is "the drain is in flight" for every ordering claim below; no store is needed for that.
 */
class DiagnosticsDrainThreadingTest {

    private static final String URI_A = "file:///a.graphqls";
    private static final String URI_B = "file:///b.graphqls";
    private static final String SDL = "type Foo { x: Int }\n";

    @Test
    void drainInFlightDoesNotBlockTheNextNotification() throws Exception {
        var workspace = new Workspace();
        ExecutorService executor = Executors.newSingleThreadExecutor(
            r -> daemon(r, "test-diagnostics-drain"));
        try {
            var client = new GatedClient(URI_A);
            var service = new GraphitronTextDocumentService(workspace, ignored -> {}, executor);
            service.setClient(client);

            // The first mutator returns while its own drain is still held on the latch, which is
            // the whole claim: inline, this call could not have returned before publishing.
            workspace.didOpen(URI_A, 1, SDL);
            assertThat(client.enteredGate.await(5, TimeUnit.SECONDS))
                .as("the drain reached its publish while the mutator had already returned")
                .isTrue();

            // A second notification handler returns while the latch is still held, and nothing
            // has been published yet.
            workspace.didOpen(URI_B, 1, SDL);
            assertThat(client.published).isEmpty();

            // Releasing the latch produces the publishes: the first drain's file, then the
            // follow-up drain the second open asked for.
            client.release.countDown();
            awaitPublished(client, 2);
            assertThat(client.published.stream().map(PublishDiagnosticsParams::getUri))
                .containsExactly(URI_A, URI_B);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void submitsDuringOneDrainCollapseIntoOneFollowUp() throws Exception {
        var workspace = new Workspace();
        ExecutorService single = Executors.newSingleThreadExecutor(
            r -> daemon(r, "test-diagnostics-drain"));
        try {
            var drains = new AtomicInteger();
            var client = new GatedClient(URI_A);
            var service = new GraphitronTextDocumentService(workspace, ignored -> {},
                task -> { drains.incrementAndGet(); single.execute(task); });
            service.setClient(client);

            workspace.didOpen(URI_A, 1, SDL);
            assertThat(client.enteredGate.await(5, TimeUnit.SECONDS)).isTrue();

            // Five build swaps land while the first drain is held. The queue already collapses
            // their URIs; this pins that the submits collapse too.
            for (int i = 0; i < 5; i++) {
                workspace.markAllForRecalculation();
            }

            client.release.countDown();
            awaitPublished(client, 2);
            assertThat(drains)
                .as("one drain for the open, one follow-up for all five swaps together")
                .hasValue(2);
            assertThat(workspace.drainRecalculate())
                .as("the follow-up drained everything the swaps queued")
                .isEmpty();
        } finally {
            single.shutdownNow();
        }
    }

    @Test
    void fileClosedMidDrainIsNotPublishedFor() throws Exception {
        var workspace = new Workspace();
        // Deferred executor: both opens must ride one drain, so no drain may start until the test
        // hands the collected task to its own thread.
        var tasks = new LinkedBlockingQueue<Runnable>();
        var client = new GatedClient(URI_A);
        var service = new GraphitronTextDocumentService(workspace, ignored -> {}, tasks::add);
        service.setClient(client);

        workspace.didOpen(URI_A, 1, SDL);
        workspace.didOpen(URI_B, 1, SDL);
        assertThat(tasks).as("the second open collapsed into the drain the first asked for").hasSize(1);

        var drain = new Thread(tasks.poll(), "test-diagnostics-drain");
        drain.start();
        assertThat(client.enteredGate.await(5, TimeUnit.SECONDS)).isTrue();

        // The drain has walked both files and is held publishing the first; the close lands in
        // the window between its walk and its publish of the second.
        service.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(URI_B)));

        client.release.countDown();
        drain.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(drain.isAlive()).isFalse();

        var forB = client.published.stream().filter(p -> p.getUri().equals(URI_B)).toList();
        assertThat(forB)
            .as("the didClose clear is the last word the client hears for the closed file")
            .hasSize(1);
        assertThat(forB.getFirst().getDiagnostics()).isEmpty();
        assertThat(client.published.stream().filter(p -> p.getUri().equals(URI_A)).toList())
            .as("the file still open is published for as usual")
            .hasSize(1);
        assertThat(tasks).as("a close enqueues nothing, so no follow-up drain").isEmpty();
    }

    /**
     * The teardown window: the workspace outlives connections and its listener slot is not cleared,
     * so after an editor detaches (shutting its connection's executor down) a build swap still
     * reaches the dead connection's service. Inline this window was quiet, so the mutator must not
     * gain a throw: the rejection is absorbed, and the collapse flag does not record a drain that
     * never ran, leaving a later accepted submit free to drain as usual.
     */
    @Test
    void rejectedSubmitIsAbsorbedAndDoesNotWedgeTheFlag() {
        var workspace = new Workspace();
        var rejecting = new AtomicBoolean(true);
        // The gated URI is never published, so this client only records.
        var client = new GatedClient("file:///never-published.graphqls");
        var service = new GraphitronTextDocumentService(workspace, ignored -> {}, task -> {
            if (rejecting.get()) {
                throw new RejectedExecutionException("Task rejected from shut-down executor");
            }
            task.run();
        });
        service.setClient(client);

        assertThatCode(() -> {
            workspace.didOpen(URI_A, 1, SDL);
            workspace.markAllForRecalculation();
        }).as("a mutator never sees the dead executor").doesNotThrowAnyException();
        assertThat(client.published).isEmpty();

        // The flag was reset on each rejection, so the first accepted submit drains what queued up.
        rejecting.set(false);
        workspace.markAllForRecalculation();
        assertThat(client.published)
            .as("the drain the rejection lost is rewanted by the next mutation, not wedged")
            .hasSize(1);
        assertThat(client.published.getFirst().getUri()).isEqualTo(URI_A);
    }

    private static Thread daemon(Runnable r, String name) {
        var thread = new Thread(r, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void awaitPublished(GatedClient client, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (client.published.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(client.published).hasSize(count);
    }

    /**
     * Records every publish, and holds the first publish of {@code gatedUri} on a latch until the
     * test releases it. The record happens after the hold, so "nothing published yet" is
     * observable from the test thread for as long as the gate is closed.
     */
    private static final class GatedClient implements LanguageClient {
        final List<PublishDiagnosticsParams> published = new CopyOnWriteArrayList<>();
        final CountDownLatch enteredGate = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final String gatedUri;

        GatedClient(String gatedUri) {
            this.gatedUri = gatedUri;
        }

        @Override public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
            if (diagnostics.getUri().equals(gatedUri) && enteredGate.getCount() > 0) {
                enteredGate.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            published.add(diagnostics);
        }

        @Override public void telemetryEvent(Object object) {}
        @Override public void showMessage(MessageParams messageParams) {}
        @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams r) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void logMessage(MessageParams message) {}
    }
}
