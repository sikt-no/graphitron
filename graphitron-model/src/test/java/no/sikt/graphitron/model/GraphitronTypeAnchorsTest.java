package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.GraphQLAssemblyCapture;
import no.sikt.graphitron.model.capture.document.GraphQLAstCapture;
import no.sikt.graphitron.model.capture.document.GraphQLSourceCapture;
import no.sikt.graphitron.model.capture.document.GraphitronAstCapture;
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

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_TABLE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_RECORD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SCALAR_TYPE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE_ENTRY;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The type site's anchors, now that they are derived rather than walked.
 *
 * <p>Three relations moved off the incumbent decode and onto {@code GraphitronAnchor}, which selects
 * them out of the entry stratum. What the move has to preserve is not the payload, which is a column
 * copy, but the two things the walk was doing implicitly and a statement has to do out loud: it
 * chose one application per type by arriving first, and it wrote a row for an application that
 * named nothing.
 *
 * <p>So the cases here are about the choosing and the absence rather than about the spelling. Each
 * is written so that removing the thing it is about leaves it red: drop the rank and the first case
 * gets two rows or the wrong one, make the join inner and the second gets none, make it outer and
 * the third gets one.
 */
class GraphitronTypeAnchorsTest {

    private static final String GRAPH = "type-anchors";

    /**
     * A type carrying the directive twice, with the second application written above the first in
     * the file. A type-keyed row admits one of them and the corpus merge order picks it, which is
     * the base declaration however the file is laid out; ordering by source position instead would
     * take the extension and this case would say so.
     */
    @Test
    @DisplayName("a type carrying the directive twice keeps the base declaration's binding")
    void theBaseDeclarationWinsARepeatedApplication(@TempDir Path tmp) {
        write(tmp, "corpus.graphqls", """
            extend type Film @table(name: "film_extension") { released: String }

            type Film @table(name: "film") { title: String }

            type Query { films: [Film!] }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var e = GRAPHITRON_AST_TABLE_ENTRY;
            assertThat(dsl.select(e.TABLE_REF).from(e).where(e.GRAPH_NAME.eq(GRAPH))
                    .fetch(e.TABLE_REF))
                .as("both applications are captured, each at the position it was written; without "
                    + "this the case below could hold because the second was never read")
                .containsExactlyInAnyOrder("film", "film_extension");
            assertThat(tableRefs(dsl, "Film"))
                .as("and the type-keyed row is the base declaration's, which is the rank choosing "
                    + "rather than the file's layout")
                .containsExactly("film");
        });
    }

    /**
     * The application that names nothing. A bare {@code @table} is a binding all the same, the type
     * resolving against its own name, so the row exists and its name column does not. That is the
     * difference between the directive being absent and its argument being absent, and only the
     * applied-directive row can state it: the decode writes no entry for an application with no
     * name to record.
     */
    @Test
    @DisplayName("a bare application is a binding whose name column is empty, not an absent row")
    void aBareApplicationIsABindingWithNoName(@TempDir Path tmp) {
        write(tmp, "corpus.graphqls", """
            type Film @table { title: String }
            type Actor { name: String }
            type Query { films: [Film!], actors: [Actor!] }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var t = GRAPHITRON_TABLE_ENTRY;
            assertThat(dsl.select(t.TYPE_NAME, t.TABLE_REF).from(t).where(t.GRAPH_NAME.eq(GRAPH))
                    .fetch().map(Record::intoList))
                .as("Film applied it and named nothing; Actor did not apply it at all, and the two "
                    + "are told apart by the row existing rather than by a column")
                .containsExactly(Arrays.asList("Film", null));
        });
    }

    /**
     * The other polarity, at the site whose payload is its whole fact. {@code scalar_ref} is the
     * relation's one unkeyed column and it is NOT NULL, so an application naming no constant has
     * nothing a row could say and writes none.
     */
    @Test
    @DisplayName("a scalar binding that names no constant writes no row")
    void aScalarBindingNamingNothingWritesNoRow(@TempDir Path tmp) {
        write(tmp, "corpus.graphqls", """
            scalar Money @scalarType(scalar: "com.example.Scalars.MONEY")
            scalar Weight @scalarType
            type Query { price: Money, weight: Weight }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var t = GRAPHITRON_SCALAR_TYPE_ENTRY;
            assertThat(dsl.select(t.TYPE_NAME, t.SCALAR_REF_CLASS_PART, t.SCALAR_REF_FIELD_PART)
                    .from(t).where(t.GRAPH_NAME.eq(GRAPH)).fetch().map(Record::intoList))
                .as("Money is bound and split; Weight named nothing and the grain has no row for "
                    + "an application that said nothing")
                .containsExactly(List.of("Money", "com.example.Scalars", "MONEY"));
        });
    }

    /**
     * The deprecated backing, which takes the outer join for a reason the schema states: the class
     * name is the relation's only payload column and it is nullable, so an application the decode
     * refused is a row with an empty column rather than no row.
     */
    @Test
    @DisplayName("a record backing that names no class is still an application with a row")
    void aRecordBackingNamingNoClassIsStillARow(@TempDir Path tmp) {
        write(tmp, "corpus.graphqls", """
            type Film @record(record: {className: "com.example.FilmRecord"}) { title: String }
            type Actor @record { name: String }
            type Query { films: [Film!], actors: [Actor!] }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var t = GRAPHITRON_RECORD_ENTRY;
            assertThat(dsl.select(t.TYPE_NAME, t.CLASS_NAME).from(t).where(t.GRAPH_NAME.eq(GRAPH))
                    .orderBy(t.TYPE_NAME).fetch().map(Record::intoList))
                .containsExactly(Arrays.asList("Actor", null),
                    Arrays.asList("Film", "com.example.FilmRecord"));
        });
    }

    /**
     * What a second reading owes. These relations carry no instant of their own, so the writer
     * empties the graph's partition and states it again rather than sweeping by stamp; this is the
     * case that says the emptying happens, which nothing else would notice until a consumer read a
     * binding for a type their schema no longer declares.
     */
    @Test
    @DisplayName("a type the corpus stopped declaring leaves no binding behind")
    void aWithdrawnTypeLeavesNoBinding(@TempDir Path tmp) {
        write(tmp, "corpus.graphqls", """
            type Film @table(name: "film") { title: String }
            type Query { films: [Film!] }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            assertThat(tableRefs(dsl, "Film")).as("before").containsExactly("film");

            write(tmp, "corpus.graphqls", """
                type Actor @table(name: "actor") { name: String }
                type Query { actors: [Actor!] }
                """);
            read(dsl, tmp);

            assertThat(tableRefs(dsl, "Film"))
                .as("the type is gone from the corpus, so its binding is gone from the store")
                .isEmpty();
            assertThat(tableRefs(dsl, "Actor"))
                .as("and the reading that removed it wrote its own")
                .containsExactly("actor");
        });
    }

    /** The names one type's binding resolves to, which is at most one. */
    private static List<String> tableRefs(DSLContext dsl, String typeName) {
        var t = GRAPHITRON_TABLE_ENTRY;
        return dsl.select(t.TABLE_REF).from(t)
            .where(t.GRAPH_NAME.eq(GRAPH)).and(t.TYPE_NAME.eq(typeName))
            .fetch(t.TABLE_REF);
    }

    /** One reading of everything the directory holds, which is what a run does. */
    private static void read(DSLContext dsl, Path baseDir) {
        var graph = new GraphIdentity(GRAPH, baseDir);
        var config = SubjectConfig.of(new SchemaRecipe(baseDir.resolve("pom.xml"),
            List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls")));
        var readAt = LocalDateTime.now();
        var documents = GraphQLSourceCapture.capture(dsl, graph, config, readAt);
        GraphQLAstCapture.capture(dsl, graph, documents, readAt);
        GraphitronAstCapture.capture(dsl, graph, documents, readAt);
    }

    private static void write(Path directory, String name, String sdl) {
        try {
            Files.writeString(directory.resolve(name), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
