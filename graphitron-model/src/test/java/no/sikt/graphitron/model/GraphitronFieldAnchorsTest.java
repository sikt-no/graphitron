package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.SdlCapture;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_CONNECTION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_DEFAULT_ORDER_FIELD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CONNECTION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEFAULT_ORDER_FIELD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PIVOT_ENTRY;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The field site's anchors, derived rather than walked.
 *
 * <p>Four directives the specification admits at {@code FIELD_DEFINITION} and nowhere else moved
 * here, which is what lets each of them derive from one entry relation. A directive also legal on
 * an input object's field has its applications split across two, an input object's field being an
 * input value in the entry stratum rather than a field, and its anchor is a union with a
 * parent-kind test rather than a select. That distinction is the reason this batch is these four.
 *
 * <p>What the cases are about is the same two things the type site's are, plus one the field site
 * adds. The choosing, which here has to reach through two declarations to find merge order. The
 * absence, where a join's polarity is the relation's own shape. And the renumbering, which is the
 * one place the two strata disagree about a value rather than about a row.
 */
class GraphitronFieldAnchorsTest {

    private static final String GRAPH = "field-anchors";

    /**
     * The renumbering, and it is the case worth having most. An element the decode refused keeps its
     * written index in the entry and takes no row in the anchor, so the anchor's positions have to
     * close over the gap: that is what the walk did by counting as it wrote, and a derivation that
     * copied the entry's index would leave a hole no reader expects.
     */
    @Test
    @DisplayName("an unusable order element leaves no gap in the positions the anchor states")
    void theAnchorsPositionsCloseOverARefusedElement(@TempDir Path tmp) {
        write(tmp, "corpus.graphqls", """
            type Query {
              films: [Film!]
                @defaultOrder(fields: [
                  {name: "title"},
                  {collate: "xdanish_ai"},
                  {name: "released", direction: DESC}
                ])
            }
            type Film { title: String, released: String }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var e = GRAPHITRON_AST_DEFAULT_ORDER_FIELD_ENTRY;
            assertThat(dsl.select(e.POSITION, e.NAME_REF).from(e).where(e.GRAPH_NAME.eq(GRAPH))
                    .orderBy(e.POSITION).fetch().map(Record::intoList))
                .as("the entry keeps every element where the author wrote it, the middle one "
                    + "naming no field and still occupying index 1")
                .containsExactly(List.of(0, "title"), Arrays.asList(1, null),
                    List.of(2, "released"));

            var t = GRAPHITRON_DEFAULT_ORDER_FIELD_ENTRY;
            assertThat(dsl.select(t.POSITION, t.NAME_REF).from(t).where(t.GRAPH_NAME.eq(GRAPH))
                    .orderBy(t.POSITION).fetch().map(Record::intoList))
                .as("and the anchor numbers the rows it holds, densely, so the second usable "
                    + "element is at 1 rather than at 2")
                .containsExactly(List.of(0, "title"), List.of(1, "released"));
        });
    }

    /**
     * The polarity at a site whose payload is the fact. Both of {@code @pivot}'s columns are NOT
     * NULL, so an application naming neither asserts nothing and draws no row, where the walk said
     * the same thing by returning before it wrote.
     */
    @Test
    @DisplayName("a pivot naming neither column writes no row")
    void aPivotNamingNeitherColumnWritesNoRow(@TempDir Path tmp) {
        write(tmp, "corpus.graphqls", """
            type Query {
              spread: [Film!] @pivot(on: "category", value: "total")
              empty: [Film!] @pivot
            }
            type Film { title: String }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var t = GRAPHITRON_PIVOT_ENTRY;
            assertThat(dsl.select(t.FIELD_NAME, t.ON_COLUMN, t.VALUE_COLUMN).from(t)
                    .where(t.GRAPH_NAME.eq(GRAPH)).fetch().map(Record::intoList))
                .as("the application that named its columns is a row; the one that named none is "
                    + "not, the grain having nothing to hold for it")
                .containsExactly(List.of("spread", "category", "total"));
        });
    }

    /**
     * The other polarity, at a site where every column is optional because the directive deduces
     * what the author leaves out. A bare application is still a request for a connection, so the
     * row exists and says nothing, which is the difference between deducing a page size and not
     * having been asked for a connection at all.
     */
    @Test
    @DisplayName("a bare connection request is a row that states nothing")
    void aBareConnectionRequestIsARowStatingNothing(@TempDir Path tmp) {
        write(tmp, "corpus.graphqls", """
            type Query {
              films: [Film!] @asConnection
              actors: [Film!] @asConnection(defaultFirstValue: 25)
              plain: [Film!]
            }
            type Film { title: String }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var t = GRAPHITRON_CONNECTION_ENTRY;
            assertThat(dsl.select(t.FIELD_NAME, t.DEFAULT_FIRST_VALUE).from(t)
                    .where(t.GRAPH_NAME.eq(GRAPH)).orderBy(t.FIELD_NAME).fetch()
                    .map(Record::intoList))
                .as("both applications are rows and the bare one carries no page size; the field "
                    + "that applied nothing has no row, which is what tells the two apart")
                .containsExactly(List.of("actors", 25), Arrays.asList("films", null));
        });
    }

    /**
     * The choosing, at the grain that makes it reachable. A field declared twice is retained by the
     * registry rather than refused, so one coordinate can carry two applications, and a
     * coordinate-keyed anchor admits one of them. Merge order picks it, which is the declaration's
     * order and not the file's.
     */
    @Test
    @DisplayName("a field declared twice keeps the base declaration's application")
    void theBaseDeclarationWinsARedeclaredField(@TempDir Path tmp) {
        write(tmp, "corpus.graphqls", """
            extend type Query { films: [Film!] @asConnection(defaultFirstValue: 99) }

            type Query { films: [Film!] @asConnection(defaultFirstValue: 25) }

            type Film { title: String }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var e = GRAPHITRON_AST_CONNECTION_ENTRY;
            assertThat(dsl.select(e.DEFAULT_FIRST_VALUE).from(e).where(e.GRAPH_NAME.eq(GRAPH))
                    .fetch(e.DEFAULT_FIRST_VALUE))
                .as("both declarations reached capture and both applications are entries; without "
                    + "this the case below could hold because the registry kept only one")
                .containsExactlyInAnyOrder(25, 99);
            var t = GRAPHITRON_CONNECTION_ENTRY;
            assertThat(dsl.select(t.DEFAULT_FIRST_VALUE).from(t).where(t.GRAPH_NAME.eq(GRAPH))
                    .fetch(t.DEFAULT_FIRST_VALUE))
                .as("one row, and it is the base declaration's, however the file is laid out")
                .containsExactly(25);
        });
    }

    /**
     * The facts this gatherer writes, without the assembly that raises the problem rows. The
     * corpora above are ones an author can write and a toolchain reports on, and capture states
     * what they say either way; asking for the problems too would mean assembling first, which is
     * a different question from what the rows are.
     */
    private static void read(DSLContext dsl, Path baseDir) {
        SdlCapture.captureFacts(dsl, new GraphIdentity(GRAPH, baseDir),
            SubjectConfig.of(new SchemaRecipe(baseDir.resolve("pom.xml"),
                List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls"))),
            LocalDateTime.now());
    }

    private static void write(Path directory, String name, String sdl) {
        try {
            Files.writeString(directory.resolve(name), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
