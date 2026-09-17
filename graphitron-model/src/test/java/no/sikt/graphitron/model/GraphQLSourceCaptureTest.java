package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.GraphQLSourceCapture;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the corpus reader hands on: every source it read, oldest file first.
 *
 * <p>The order is the subject rather than a detail of it. A gatherer below reduces these documents
 * into one registry and settles a collision in favour of whichever declaration it met first, so the
 * order this list arrives in decides which of two declarations of one name survives. Nothing else
 * would notice that changing, which is why it is bound here.
 */
class GraphQLSourceCaptureTest {

    private static final String GRAPH = "source-capture";

    @TempDir
    Path tmp;

    /**
     * Three files written newest first and read oldest first, so the list is the reader's own order
     * rather than the order the directory listed or the order they were created in.
     */
    @Test
    @DisplayName("the documents come back oldest file first")
    void theListIsSortedOldestFirst() {
        withSeededStore(GRAPH, dsl -> {
            write("newest.graphqls", "type Newest { a: String }", Instant.parse("2020-03-01T00:00:00Z"));
            write("oldest.graphqls", "type Oldest { a: String }", Instant.parse("2020-01-01T00:00:00Z"));
            write("middle.graphqls", "type Middle { a: String }", Instant.parse("2020-02-01T00:00:00Z"));

            assertThat(authored(capture(dsl)))
                .as("the modification time orders them, not the name and not the walk")
                .containsExactly("oldest.graphqls", "middle.graphqls", "newest.graphqls");
        });
    }

    /**
     * A source that would not parse is a file with a modification time like any other, so it sits
     * at its own place in the order rather than being appended after the ones that read.
     */
    @Test
    @DisplayName("a source that would not parse holds its place in the order")
    void anUnparsedSourceKeepsItsPlace() {
        withSeededStore(GRAPH, dsl -> {
            write("a-first.graphqls", "type First { a: String }", Instant.parse("2020-01-01T00:00:00Z"));
            write("b-broken.graphqls", "type Broken { ", Instant.parse("2020-02-01T00:00:00Z"));
            write("c-last.graphqls", "type Last { a: String }", Instant.parse("2020-03-01T00:00:00Z"));

            var documents = capture(dsl);
            assertThat(authored(documents))
                .containsExactly("a-first.graphqls", "b-broken.graphqls", "c-last.graphqls");
            assertThat(documents.stream()
                    .filter(document -> !document.parsed())
                    .map(GraphQLSourceCapture.SourceDocument::sourceName)
                    .map(GraphQLSourceCaptureTest::leaf).toList())
                .as("it is in the list because a gatherer below sweeps by source, and it carries no"
                    + " registry because there was none to carry")
                .containsExactly("b-broken.graphqls");
        });
    }

    /**
     * The order honoured, which is the reason the sort exists. Two files declare one type and the
     * reduce keeps the older, so a reader of the combined registry sees the declaration that was
     * there first and the younger one is a refusal rather than an overwrite.
     */
    @Test
    @DisplayName("the reduce keeps the older declaration and records the younger as a refusal")
    void theOlderDeclarationWins() {
        withSeededStore(GRAPH, dsl -> {
            write("old.graphqls", "type Clash { fromTheOlder: String }",
                Instant.parse("2020-01-01T00:00:00Z"));
            write("new.graphqls", "type Clash { fromTheYounger: String }",
                Instant.parse("2020-06-01T00:00:00Z"));

            var merged = SchemaLoader.merge(capture(dsl).stream()
                .filter(GraphQLSourceCapture.SourceDocument::parsed)
                .map(GraphQLSourceCapture.SourceDocument::registry).toList());

            assertThat(merged.registry().types().get("Clash"))
                .as("the older file's declaration is the one that survived")
                .isNotNull()
                .satisfies(type -> assertThat(type.getChildren().toString())
                    .contains("fromTheOlder"));
            assertThat(merged.registryErrors())
                .as("and the younger is refused rather than silently dropped")
                .isNotEmpty();
        });
    }

    /**
     * The reduce keeps what it admitted, which is what it does that the library's own registry merge
     * does not: that one refuses a document whose declaration clashes, where this refuses the
     * declaration and combines everything else the document declares.
     */
    @Test
    @DisplayName("a document with one refused declaration still contributes its others")
    void oneRefusalDoesNotCostTheWholeDocument() {
        withSeededStore(GRAPH, dsl -> {
            write("old.graphqls", "type Clash { a: String }", Instant.parse("2020-01-01T00:00:00Z"));
            write("new.graphqls", "type Clash { b: String } type Innocent { c: String }",
                Instant.parse("2020-06-01T00:00:00Z"));

            var merged = SchemaLoader.merge(capture(dsl).stream()
                .filter(GraphQLSourceCapture.SourceDocument::parsed)
                .map(GraphQLSourceCapture.SourceDocument::registry).toList());

            assertThat(merged.registry().types())
                .as("the declaration beside the refused one is in the registry")
                .containsKey("Innocent");
        });
    }

    private List<GraphQLSourceCapture.SourceDocument> capture(DSLContext dsl) {
        return GraphQLSourceCapture.capture(dsl, new GraphIdentity(GRAPH, tmp),
            SubjectConfig.of(new SchemaRecipe(tmp.resolve("pom.xml"),
                List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls"))),
            LocalDateTime.now());
    }

    /** The authored sources by file name, the bundled directive vocabulary being nobody's file. */
    private static List<String> authored(List<GraphQLSourceCapture.SourceDocument> documents) {
        return documents.stream()
            .map(GraphQLSourceCapture.SourceDocument::sourceName)
            .filter(name -> name.endsWith(".graphqls") && name.contains("/"))
            .map(GraphQLSourceCaptureTest::leaf)
            .toList();
    }

    private static String leaf(String sourceName) {
        return Path.of(sourceName).getFileName().toString();
    }

    private void write(String name, String sdl, Instant modified) {
        try {
            Path file = tmp.resolve(name);
            Files.writeString(file, sdl, StandardCharsets.UTF_8);
            Files.setLastModifiedTime(file, FileTime.from(modified));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
