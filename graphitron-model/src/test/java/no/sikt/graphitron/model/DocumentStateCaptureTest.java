package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.GraphQLAstCapture;
import no.sikt.graphitron.model.capture.document.GraphQLSourceCapture;
import no.sikt.graphitron.model.capture.document.GraphitronAstCapture;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.ModelCapture;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.diagnostics.SchemaParseException;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.test.ThreadConfinedStore;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ELEMENT;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a reading does to the scope of each document it is accountable for.
 *
 * <p>One rule, asserted five ways: a reading brings each {@code (graph, source)} scope into line
 * with what that document now states. The interesting half is the documents that state nothing.
 * A file that stopped parsing and a file that left the corpus are both easy to leave out of the
 * list a gatherer walks, and leaving either out fails nowhere: the rows of the last good reading
 * simply stand, indefinitely, as though the author had never touched anything.
 *
 * <p>The instants are stated rather than taken from the clock. A sweep tells one reading's rows
 * from the last one's by them, so two readings a few microseconds apart is exactly the case where
 * a test would pass while proving nothing.
 */
class DocumentStateCaptureTest {

    private static final String GRAPH = "document-state";

    private static final LocalDateTime FIRST = LocalDateTime.parse("2024-01-01T10:00:00");
    private static final LocalDateTime SECOND = LocalDateTime.parse("2024-01-02T10:00:00");
    private static final LocalDateTime THIRD = LocalDateTime.parse("2024-01-03T10:00:00");

    private static final String WIDGET = "type Widget { id: ID! name: String }\n";
    private static final String QUERY = "type Query { widgets: [Widget!]! }\n";

    /**
     * The skip, and the reason it is safe: the scope already holds what the document states, so the
     * reading leaves it alone rather than rewriting it to the same thing.
     *
     * <p>Asserted on the instant rather than on a row count, a rewrite to identical values being
     * indistinguishable from a skip by any other measure. The rows keeping the first reading's
     * instant is also the property the sweep has to tolerate: whatever else changes, a scope nobody
     * marked must not be swept.
     */
    @Test
    @DisplayName("a document whose bytes have not moved keeps the rows it already had")
    void anUnchangedDocumentIsLeftAlone() {
        ThreadConfinedStore.run(dsl -> {
            Path directory = temporaryDirectory();
            write(directory, "widget.graphqls", WIDGET);
            read(dsl, directory, FIRST);
            var before = positions(dsl, directory, "widget.graphqls");

            read(dsl, directory, SECOND);

            assertThat(positions(dsl, directory, "widget.graphqls"))
                .as("the rows are still there after a reading that had nothing to say about them")
                .isEqualTo(before).isPositive();
            assertThat(instants(dsl, directory, "widget.graphqls"))
                .as("and they carry the reading that wrote them, not the one that skipped them")
                .containsExactly(FIRST);
            assertThat(claimedAt(dsl, directory, "widget.graphqls"))
                .as("the claim dates the transcription too, a reading that performed none having"
                    + " nothing to date")
                .isEqualTo(FIRST);
        });
    }

    /**
     * The other side of the same comparison: bytes that moved are transcribed again, so the skip
     * cannot quietly be a skip of everything.
     */
    @Test
    @DisplayName("a document whose bytes moved is transcribed again")
    void aChangedDocumentIsRewritten() {
        ThreadConfinedStore.run(dsl -> {
            Path directory = temporaryDirectory();
            write(directory, "widget.graphqls", WIDGET);
            read(dsl, directory, FIRST);

            write(directory, "widget.graphqls", WIDGET.replace("name: String", "title: String"));
            read(dsl, directory, SECOND);

            assertThat(instants(dsl, directory, "widget.graphqls"))
                .as("the edited file is rewritten on this reading's instant")
                .containsExactly(SECOND);
        });
    }

    /**
     * A file the author has just broken, saved together with an edit to the file beside it. The
     * reading stops at the parse and the store keeps the last clean reading of both, so nothing the
     * capture skipped is stamped as transcribed: once the break is fixed, the neighbour's edit is
     * read rather than taken as already settled.
     */
    @Test
    @DisplayName("a document that stopped parsing ends the reading, and its neighbour's edit is read once it parses")
    void anUnparsableDocumentEndsTheReading() {
        ThreadConfinedStore.run(dsl -> {
            Path directory = temporaryDirectory();
            write(directory, "widget.graphqls", WIDGET);
            write(directory, "query.graphqls", QUERY);
            read(dsl, directory, FIRST);
            var widget = positions(dsl, directory, "widget.graphqls");
            var query = positions(dsl, directory, "query.graphqls");

            write(directory, "widget.graphqls", "type Widget { ");
            write(directory, "query.graphqls", QUERY + "type Gadget { id: ID! }\n");
            assertThatThrownBy(() -> read(dsl, directory, SECOND))
                .isInstanceOf(SchemaParseException.class);
            assertThat(positions(dsl, directory, "widget.graphqls"))
                .as("the broken file keeps its last clean reading")
                .isEqualTo(widget);
            assertThat(positions(dsl, directory, "query.graphqls"))
                .as("and so does the file edited beside it")
                .isEqualTo(query);

            write(directory, "widget.graphqls", WIDGET);
            read(dsl, directory, THIRD);
            assertThat(positions(dsl, directory, "query.graphqls"))
                .as("the neighbour's edit, never stamped as transcribed, is read now")
                .isGreaterThan(query);
        });
    }

    /**
     * A file the author deleted. It is not in the configuration any more, so a reading that walks
     * only what the configuration names never reaches it and never sweeps it.
     *
     * <p>The source row goes too, and the order is the point: it is the provenance the rows hang
     * on, so a reading that forgot it first would orphan them instead of removing them.
     */
    @Test
    @DisplayName("a document the author deleted loses its rows and its source row")
    void aDeletedDocumentLosesItsRows() {
        ThreadConfinedStore.run(dsl -> {
            Path directory = temporaryDirectory();
            write(directory, "widget.graphqls", WIDGET);
            write(directory, "query.graphqls", QUERY);
            read(dsl, directory, FIRST);
            assertThat(positions(dsl, directory, "widget.graphqls")).isPositive();

            delete(directory, "widget.graphqls");
            read(dsl, directory, SECOND);

            assertThat(positions(dsl, directory, "widget.graphqls"))
                .as("the deleted file's positions are gone")
                .isZero();
            assertThat(dsl.fetchCount(STORE_SOURCE,
                STORE_SOURCE.SOURCE_NAME.eq(name(directory, "widget.graphqls"))))
                .as("and so is the row that said the corpus had ever read it")
                .isZero();
            assertThat(positions(dsl, directory, "query.graphqls"))
                .as("the surviving file is untouched")
                .isPositive();
        });
    }

    /**
     * A file still on disk that the configuration has stopped naming. The same fact to every writer
     * as a deletion, the document being out of this graph's corpus either way, and a different fact
     * to the store: the file is still there, and another graph may be reading it.
     */
    @Test
    @DisplayName("a document the configuration dropped loses its rows and keeps its source row")
    void aDeconfiguredDocumentLosesItsRows() {
        ThreadConfinedStore.run(dsl -> {
            Path directory = temporaryDirectory();
            write(directory, "widget.graphqls", WIDGET);
            write(directory, "query.graphqls", QUERY);
            read(dsl, directory, FIRST, "*.graphqls");
            assertThat(positions(dsl, directory, "widget.graphqls")).isPositive();

            read(dsl, directory, SECOND, "query.graphqls");

            assertThat(positions(dsl, directory, "widget.graphqls"))
                .as("a document this graph no longer reads leaves no rows behind under it")
                .isZero();
            assertThat(dsl.fetchCount(STORE_GRAPH_SOURCE,
                STORE_GRAPH_SOURCE.GRAPH_NAME.eq(GRAPH)
                    .and(STORE_GRAPH_SOURCE.SOURCE_NAME.eq(name(directory, "widget.graphqls")))))
                .as("nor a claim on it")
                .isZero();
            assertThat(dsl.fetchCount(STORE_SOURCE,
                STORE_SOURCE.SOURCE_NAME.eq(name(directory, "widget.graphqls"))))
                .as("but the file is still a file, and the store is shared with graphs that may"
                    + " still be reading it")
                .isOne();
        });
    }

    /**
     * The corpus's anchors are a statement over the entries and the files' order, so a reading that
     * transcribed nothing and found the order where it was has nothing to anchor. The specification
     * document is part of that: its bundled bytes are stamped like a file's, so it is not read again
     * either.
     */
    @Test
    @DisplayName("a reading that transcribed nothing leaves the corpus's anchors alone")
    void anUnchangedCorpusIsNotAnchoredAgain() {
        ThreadConfinedStore.run(dsl -> {
            Path directory = temporaryDirectory();
            write(directory, "widget.graphqls", WIDGET);
            write(directory, "query.graphqls", QUERY);
            readAsCaptured(dsl, directory, FIRST);

            readAsCaptured(dsl, directory, SECOND);

            assertThat(dsl.selectDistinct(GRAPHQL_ELEMENT.TOUCHED_AT).from(GRAPHQL_ELEMENT)
                    .where(GRAPHQL_ELEMENT.GRAPH_NAME.eq(GRAPH))
                    .fetch(GRAPHQL_ELEMENT.TOUCHED_AT))
                .as("every anchor still carries the reading that derived it")
                .containsExactly(FIRST);
            assertThat(dsl.selectDistinct(GRAPHQL_AST_ENTRY.TOUCHED_AT).from(GRAPHQL_AST_ENTRY)
                    .where(GRAPHQL_AST_ENTRY.GRAPH_NAME.eq(GRAPH))
                    .and(GRAPHQL_AST_ENTRY.SOURCE_NAME.eq(SchemaLoader.SPECIFICATION_SOURCE_NAME))
                    .fetch(GRAPHQL_AST_ENTRY.TOUCHED_AT))
                .as("and the specification's own entries were not transcribed again")
                .containsExactly(FIRST);
        });
    }

    /**
     * Which of two declarations of one coordinate the corpus honours is settled by the files'
     * modification order, so a file touched past its neighbour moves the anchors though no byte
     * moved. A touch that keeps the order is the control: it changes nothing an anchor reads.
     */
    @Test
    @DisplayName("files that swap modification order are anchored again, though no byte moved")
    void aReorderedCorpusIsAnchoredAgain() throws IOException {
        Path directory = temporaryDirectory();
        write(directory, "widget.graphqls", WIDGET);
        write(directory, "query.graphqls", QUERY);
        setModified(directory, "widget.graphqls", 1_000);
        setModified(directory, "query.graphqls", 2_000);
        ThreadConfinedStore.run(dsl -> {
            readAsCaptured(dsl, directory, FIRST);

            setModified(directory, "widget.graphqls", 3_000);
            setModified(directory, "query.graphqls", 4_000);
            readAsCaptured(dsl, directory, SECOND);
            assertThat(anchoredAt(dsl)).as("a touch that keeps the order is not a reason")
                .containsExactly(FIRST);

            setModified(directory, "widget.graphqls", 5_000);
            readAsCaptured(dsl, directory, THIRD);
            assertThat(anchoredAt(dsl)).as("the widget file now younger than the query file is")
                .containsExactly(THIRD);
        });
    }

    // ------------------------------------------------------------------------------- the reading

    /**
     * One reading as a run captures it, the anchors told whether the files' order moved, which is
     * what lets them stand where nothing they read did.
     */
    private static void readAsCaptured(DSLContext dsl, Path baseDir, LocalDateTime readAt) {
        var graph = new GraphIdentity(GRAPH, baseDir);
        ModelCapture.writeGraph(dsl, graph, readAt);
        var reading = GraphQLSourceCapture.capture(dsl, graph, corpus(baseDir, "*.graphqls"),
            readAt);
        SchemaLoader.throwIfUnparsed(reading.failures());
        GraphQLAstCapture.capture(dsl, graph, reading.documents(), reading.reordered(), readAt);
        GraphQLSourceCapture.reclaim(dsl, reading.documents());
    }

    private static List<LocalDateTime> anchoredAt(DSLContext dsl) {
        return dsl.selectDistinct(GRAPHQL_ELEMENT.TOUCHED_AT).from(GRAPHQL_ELEMENT)
            .where(GRAPHQL_ELEMENT.GRAPH_NAME.eq(GRAPH))
            .fetch(GRAPHQL_ELEMENT.TOUCHED_AT);
    }

    /** Sets a file's modification time to a fixed number of seconds after the epoch. */
    private static void setModified(Path directory, String name, long seconds) {
        try {
            Files.setLastModifiedTime(directory.resolve(name),
                java.nio.file.attribute.FileTime.fromMillis(seconds * 1_000));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One reading of everything the configuration names, which is what a run does. */
    private static void read(DSLContext dsl, Path baseDir, LocalDateTime readAt) {
        read(dsl, baseDir, readAt, "*.graphqls");
    }

    private static void read(DSLContext dsl, Path baseDir, LocalDateTime readAt, String pattern) {
        var graph = new GraphIdentity(GRAPH, baseDir);
        ModelCapture.writeGraph(dsl, graph, readAt);
        var reading = GraphQLSourceCapture.capture(dsl, graph, corpus(baseDir, pattern), readAt);
        SchemaLoader.throwIfUnparsed(reading.failures());
        var documents = reading.documents();
        GraphQLAstCapture.capture(dsl, graph, documents, readAt);
        GraphitronAstCapture.capture(dsl, graph, documents, readAt);
        GraphQLSourceCapture.reclaim(dsl, documents);
    }

    private static SubjectConfig corpus(Path baseDir, String pattern) {
        return SubjectConfig.of(new SchemaRecipe(baseDir.resolve("pom.xml"),
            List.of(SchemaRecipe.Binding.pattern(pattern)), List.of("graphqls")));
    }

    // --------------------------------------------------------------------------- what it wrote

    private static int positions(DSLContext dsl, Path directory, String file) {
        return dsl.fetchCount(GRAPHQL_AST_ENTRY, GRAPHQL_AST_ENTRY.GRAPH_NAME.eq(GRAPH)
            .and(GRAPHQL_AST_ENTRY.SOURCE_NAME.eq(name(directory, file))));
    }

    private static List<LocalDateTime> instants(DSLContext dsl, Path directory, String file) {
        return dsl.selectDistinct(GRAPHQL_AST_ENTRY.TOUCHED_AT).from(GRAPHQL_AST_ENTRY)
            .where(GRAPHQL_AST_ENTRY.GRAPH_NAME.eq(GRAPH))
            .and(GRAPHQL_AST_ENTRY.SOURCE_NAME.eq(name(directory, file)))
            .fetch(GRAPHQL_AST_ENTRY.TOUCHED_AT);
    }

    /** When this graph last says it transcribed the file, off the claim rather than off the rows. */
    private static LocalDateTime claimedAt(DSLContext dsl, Path directory, String file) {
        return dsl.select(STORE_GRAPH_SOURCE.READ_AT).from(STORE_GRAPH_SOURCE)
            .where(STORE_GRAPH_SOURCE.GRAPH_NAME.eq(GRAPH))
            .and(STORE_GRAPH_SOURCE.SOURCE_NAME.eq(name(directory, file)))
            .fetchOne(STORE_GRAPH_SOURCE.READ_AT);
    }

    private static String name(Path directory, String file) {
        return directory.resolve(file).toString();
    }

    // ------------------------------------------------------------------------------- the corpus

    private static void write(Path directory, String name, String sdl) {
        try {
            Files.writeString(directory.resolve(name), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path directory, String name) {
        try {
            Files.delete(directory.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("document-state");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
