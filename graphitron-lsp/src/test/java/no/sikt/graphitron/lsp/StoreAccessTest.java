package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.state.StoreAccess;
import no.sikt.graphitron.lsp.state.StoreRead;
import no.sikt.graphitron.model.read.SourceUri;
import no.sikt.graphitron.model.read.StoreHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static no.sikt.graphitron.model.test.StoreAnswers.answered;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The session's read boundary: which graph answers for a document, and what happens when none does.
 *
 * <p>The interesting case is the file two modules both read. The store refuses to pick, because at
 * that layer both memberships are true; the session picks, because it knows which project the editor
 * has open. These cases pin that the choice is made on that knowledge and not on row order, and that
 * a session with no claim on a document says nothing rather than guessing.
 */
class StoreAccessTest {

    private static final String SDL = "type Query { films: Int }\n";

    @Test
    void aDocumentOneGraphReadResolvesToThatGraph(@TempDir Path tmp) {
        try (var fixture = StoreFixture.of(tmp, SDL);
             var access = fixture.access()) {

            assertThat(graphAnswering(access, fixture.sourceName())).contains(StoreFixture.GRAPH);
        }
    }

    @Test
    void aDocumentTwoGraphsReadResolvesToTheSessionsOwn(@TempDir Path tmp) {
        try (var fixture = StoreFixture.of(tmp, SDL).andGraphSharingTheFile(tmp, "sibling");
             var access = fixture.access("sibling")) {

            // Both graphs read this file, so both rows are true and neither is the answer on its own.
            // The session was started for "sibling", which is what settles it.
            assertThat(graphAnswering(access, fixture.sourceName())).contains("sibling");
        }
    }

    @Test
    void aDocumentOnlyOtherGraphsReadAnswersAbsent(@TempDir Path tmp) {
        try (var fixture = StoreFixture.of(tmp, SDL).andGraphSharingTheFile(tmp, "sibling");
             var access = fixture.access("a-third-module")) {

            // The file belongs to two modules, neither of them this session's. Reporting either one's
            // classification would be answering a question nobody asked.
            assertThat(graphAnswering(access, fixture.sourceName())).isEmpty();
        }
    }

    @Test
    void aDocumentNoGraphHasReadAnswersAbsent(@TempDir Path tmp) {
        try (var fixture = StoreFixture.of(tmp, SDL);
             var access = fixture.access()) {

            String unread = tmp.resolve("written-since-the-last-capture.graphqls").toString();

            assertThat(graphAnswering(access, unread)).isEmpty();
        }
    }

    @Test
    void anEditorUriAndACapturedRowMeetOnOneSpelling(@TempDir Path tmp) {
        try (var fixture = StoreFixture.of(tmp, SDL)) {
            // The trip an LSP request makes: the store's source name is rendered as the URI a client
            // sends, and has to come back as the same string or every lookup misses silently.
            String uri = SourceUri.of(fixture.sourceName());

            assertThat(StoreAccess.sourceNameOf(uri)).contains(fixture.sourceName());
        }
    }

    @Test
    void aUriNamingNoLocalFileHasNoSourceName() {
        assertThat(StoreAccess.sourceNameOf("untitled:Untitled-1")).isEmpty();
        assertThat(StoreAccess.sourceNameOf("https://example.com/schema.graphqls")).isEmpty();
        assertThat(StoreAccess.sourceNameOf(null)).isEmpty();
    }

    /** The graph whose handle {@code access} hands an answer, or empty when it hands none. */
    private static Optional<String> graphAnswering(StoreAccess access, String sourceName) {
        return answered(access.answering(StoreRead.HOVER, sourceName,
            handle -> handle.map(StoreHandle::graphName)));
    }
}
