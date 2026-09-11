package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.SdlCapture;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
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
import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEPRECATED_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEPRECATED_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Deprecation, which is one fact with two markers and no way to ask a registry for it.
 *
 * <p>GraphQL forbids {@code @deprecated} on a directive definition and admits it on that
 * definition's arguments, so graphitron says the first with a token in the description. Both are
 * captured rather than recognised at read time, which is the difference these cases are about: a
 * consumer asks the store which directives are deprecated instead of matching a pattern against a
 * description it fetched for another reason.
 */
class GraphitronAnchorTest {

    private static final String GRAPH = "deprecations";

    /**
     * The docstring convention. The token marks the description as carrying the notice and the
     * prose around it is the notice, so the whole text is the reason.
     */
    @Test
    @DisplayName("a directive whose description carries the token is deprecated as a whole")
    void theDocstringMarkerDeprecatesTheDirective(@TempDir Path tmp) {
        write(tmp, "vocabulary.graphqls", """
            "@deprecated use @sortBy(index:) instead"
            directive @legacySort(name: String) on ENUM_VALUE

            "Connect this enum value to a sorting specification."
            directive @sortBy(index: String) on ENUM_VALUE

            type Query { a: String }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var t = GRAPHITRON_DEPRECATED_DIRECTIVE;

            assertThat(dsl.select(t.DIRECTIVE_NAME, t.REASON).from(t).fetch())
                .as("the marked directive and nothing else; the one beside it has a description "
                    + "too, which is what makes this a decode rather than a presence test")
                .extracting(r -> r.value1(), r -> r.value2())
                .contains(tuple("legacySort", "@deprecated use @sortBy(index:) instead"));
            assertThat(marked(dsl, "sortBy"))
                .as("and the directive beside it has a description too, which is what makes this a "
                    + "decode rather than a presence test")
                .isFalse();
        });
    }

    /**
     * The word boundary, which is the whole of what the token match has to get right: a description
     * mentioning an address is not a deprecation notice.
     */
    @Test
    @DisplayName("a token inside a word marks nothing")
    void aMidWordOccurrenceIsNotAMarker(@TempDir Path tmp) {
        write(tmp, "vocabulary.graphqls", """
            "write to my@deprecated.example for the migration guide"
            directive @keep(name: String) on ENUM_VALUE

            type Query { a: String }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            assertThat(marked(dsl, "keep"))
                .as("the token has to stand on its own to mark anything").isFalse();
        });
    }

    /**
     * The native marker, at the one place GraphQL admits it and the anchors do not reach. A
     * directive applied to a directive definition's own argument has no applied-directive anchor,
     * so this relation is the only thing that says the application happened at a coordinate.
     */
    @Test
    @DisplayName("a directive argument carrying @deprecated resolves to its coordinate")
    void theNativeMarkerResolvesToTheArgument(@TempDir Path tmp) {
        write(tmp, "vocabulary.graphqls", """
            directive @paged(
              defaultFirstValue: Int
              pagedName: String @deprecated(reason: "own your Connection type")
            ) on FIELD_DEFINITION

            directive @bare(legacy: String @deprecated) on FIELD_DEFINITION

            type Query { a: String }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var t = GRAPHITRON_DEPRECATED_DIRECTIVE_ARGUMENT;

            assertThat(dsl.select(t.DIRECTIVE_NAME, t.ARGUMENT_NAME, t.REASON).from(t).fetch())
                .as("each marked argument under the directive that declares it, the two parent hops "
                    + "having been resolved; a marker with no reason still deprecates, and stores "
                    + "the empty string rather than a null a reader would have to test for")
                .extracting(r -> r.value1(), r -> r.value2(), r -> r.value3())
                .contains(
                    tuple("paged", "pagedName", "own your Connection type"),
                    tuple("bare", "legacy", ""));
        });
    }

    /**
     * What the author withdrew goes. The marker is a token in prose and an argument's directive,
     * so an edit that removes either leaves a row no upsert can find: only the sweep reaches it.
     */
    @Test
    @DisplayName("a marker the author removed is not a fact any more")
    void aWithdrawnMarkerIsSwept(@TempDir Path tmp) {
        write(tmp, "vocabulary.graphqls", """
            "@deprecated use something else"
            directive @old(legacy: String @deprecated(reason: "gone")) on FIELD_DEFINITION

            type Query { a: String }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            assertThat(marked(dsl, "old")).as("before").isTrue();
            assertThat(markedArgument(dsl, "old", "legacy")).as("before").isTrue();

            write(tmp, "vocabulary.graphqls", """
                "a directive that is no longer going anywhere"
                directive @old(legacy: String) on FIELD_DEFINITION

                type Query { a: String }
                """);
            read(dsl, tmp);

            assertThat(marked(dsl, "old"))
                .as("the token is gone from the description, so the directive is not deprecated")
                .isFalse();
            assertThat(markedArgument(dsl, "old", "legacy"))
                .as("and the argument's own marker went with the edit that removed it")
                .isFalse();
        });
    }

    /**
     * The bundled vocabulary graphitron ships is read by every capture, so its own markers are in
     * the store beside a case's. That is worth a case rather than a workaround: the two directives
     * it deprecates by docstring are exactly the two the convention exists for, GraphQL having no
     * way to say either.
     */
    @Test
    @DisplayName("graphitron's own vocabulary declares its deprecations, and the store holds them")
    void theBundledVocabularyIsCaptured(@TempDir Path tmp) {
        write(tmp, "schema.graphqls", "type Query { a: String }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var t = GRAPHITRON_DEPRECATED_DIRECTIVE;

            assertThat(dsl.select(t.DIRECTIVE_NAME).from(t).fetch(t.DIRECTIVE_NAME))
                .as("@index is the deprecated alias of @order(index:) and @record no longer binds "
                    + "anything, and both say so in prose because GraphQL gives them nowhere else "
                    + "to say it")
                .contains("index", "record");
        });
    }

    /** Whether the corpus marks this directive deprecated as a whole. */
    private static boolean marked(DSLContext dsl, String directive) {
        var t = GRAPHITRON_DEPRECATED_DIRECTIVE;
        return dsl.fetchExists(dsl.selectOne().from(t).where(t.DIRECTIVE_NAME.eq(directive)));
    }

    /** Whether the corpus marks this argument of this directive deprecated. */
    private static boolean markedArgument(DSLContext dsl, String directive, String argument) {
        var t = GRAPHITRON_DEPRECATED_DIRECTIVE_ARGUMENT;
        return dsl.fetchExists(dsl.selectOne().from(t)
            .where(t.DIRECTIVE_NAME.eq(directive)).and(t.ARGUMENT_NAME.eq(argument)));
    }

    /** One reading of everything the directory holds, which is what a run does. */
    private static void read(DSLContext dsl, Path baseDir) {
        SdlCapture.capture(dsl, new GraphIdentity(GRAPH, baseDir),
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
