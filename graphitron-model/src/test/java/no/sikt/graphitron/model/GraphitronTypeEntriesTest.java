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

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ERROR_DATABASE_HANDLER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ERROR_GENERIC_HANDLER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ERROR_VALIDATION_HANDLER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_TABLE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_TYPE_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The directive decode, keyed where the directive was written.
 *
 * <p>What these stand up is what the key makes possible. A row says what one application's
 * arguments meant and nothing about what they resolve to, so two documents applying one directive
 * to one type are two rows and neither is refused; which of them the corpus honours is a question
 * asked of the rows afterwards.
 */
class GraphitronTypeEntriesTest {

    private static final String GRAPH = "decoded";

    /**
     * The row and the row it decodes, joined on the key they share. Asserted through the join
     * rather than by reading the columns back, the claim being that the two meet: a decode keyed at
     * a position nothing was written at would be a row about nothing.
     */
    @Test
    @DisplayName("an application's decode is co-keyed to the row saying it was applied")
    void theDecodeIsCoKeyedToTheApplication(@TempDir Path tmp) {
        write(tmp, "film.graphqls", "type Film @table(name: \"sakila.film\") { title: String }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var decode = GRAPHITRON_AST_TABLE_ENTRY;
            var applied = GRAPHQL_AST_TYPE_DIRECTIVE_ENTRY;
            var declared = GRAPHQL_AST_TYPE_DECLARATION_ENTRY;

            assertThat(dsl.select(applied.NAME, declared.NAME, decode.TABLE_REF,
                        decode.TABLE_REF_NAMESPACE_PART, decode.TABLE_REF_NAME_PART)
                    .from(decode)
                    .join(applied)
                    .on(decode.GRAPH_NAME.eq(applied.GRAPH_NAME))
                    .and(decode.SOURCE_NAME.eq(applied.SOURCE_NAME))
                    .and(decode.SOURCE_LINE.eq(applied.SOURCE_LINE))
                    .and(decode.SOURCE_COLUMN.eq(applied.SOURCE_COLUMN))
                    .join(declared)
                    .on(applied.GRAPH_NAME.eq(declared.GRAPH_NAME))
                    .and(applied.SOURCE_NAME.eq(declared.SOURCE_NAME))
                    .and(applied.PARENT_LINE.eq(declared.SOURCE_LINE))
                    .and(applied.PARENT_COLUMN.eq(declared.SOURCE_COLUMN))
                    .fetch())
                .as("the decode, the application it decodes, and the declaration it was written on, "
                    + "none of which repeats what another holds")
                .extracting(r -> r.value1(), r -> r.value2(), r -> r.value3(), r -> r.value4(),
                    r -> r.value5())
                .containsExactly(tuple("table", "Film", "sakila.film", "sakila", "film"));
        });
    }

    /**
     * The corpus a coordinate key cannot hold. Both documents bind Film to a table and the store
     * keeps both bindings, so a reader can say what each document asked for and a rule about which
     * one wins can be applied to the rows rather than baked into the writer.
     */
    @Test
    @DisplayName("two documents binding one type are two decodes")
    void twoDocumentsBindingOneTypeAreTwoDecodes(@TempDir Path tmp) {
        write(tmp, "first.graphqls", "type Film @table(name: \"film\") { a: String }\n");
        write(tmp, "second.graphqls", "type Film @table(name: \"film_reissue\") { b: String }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);

            assertThat(dsl.select(GRAPHITRON_AST_TABLE_ENTRY.TABLE_REF)
                    .from(GRAPHITRON_AST_TABLE_ENTRY)
                    .fetch(GRAPHITRON_AST_TABLE_ENTRY.TABLE_REF))
                .as("neither binding is refused and neither overwrites the other")
                .containsExactlyInAnyOrder("film", "film_reissue");
        });
    }

    /**
     * A bare application asserts the deduction, and the absence of a row is what records it. The
     * fact this relation holds is "bound to the table named X"; a bare {@code @table} names none,
     * asking instead for the type's own name to be used, which is a different thing said and not a
     * null version of this one. That it was applied at all is the applied-directive row's to say.
     */
    @Test
    @DisplayName("a directive applied with no arguments writes no row, and is still applied")
    void aBareApplicationAssertsTheDeduction(@TempDir Path tmp) {
        write(tmp, "bare.graphqls", "type Film @table { title: String }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);

            assertThat(dsl.select(GRAPHQL_AST_TYPE_DIRECTIVE_ENTRY.NAME)
                    .from(GRAPHQL_AST_TYPE_DIRECTIVE_ENTRY)
                    .fetch(GRAPHQL_AST_TYPE_DIRECTIVE_ENTRY.NAME))
                .as("applied, which is what keeps this apart from a type carrying no @table at all")
                .containsExactly("table");
            assertThat(dsl.fetchCount(GRAPHITRON_AST_TABLE_ENTRY))
                .as("and naming nothing, so there is no bound-to-this-table fact to hold")
                .isZero();
        });
    }

    /**
     * A list argument, which is the one shape whose values need a key of their own, and three
     * kinds of value, which is why they need three relations. The index is the author's order
     * inside one application and is shared across the three, so it decides nothing about which
     * document wins and everything about what the author wrote first.
     */
    @Test
    @DisplayName("each handler of an @error application is a row of the relation of its kind")
    void handlersAreRowsOfTheirKind(@TempDir Path tmp) {
        write(tmp, "errors.graphqls", """
            type Failed @error(handlers: [
              {handler: DATABASE, code: "23505"},
              {handler: VALIDATION},
              {handler: GENERIC, className: "java.lang.IllegalStateException", description: "no"}
            ]) { message: String }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var database = GRAPHITRON_AST_ERROR_DATABASE_HANDLER_ENTRY;
            var validation = GRAPHITRON_AST_ERROR_VALIDATION_HANDLER_ENTRY;
            var generic = GRAPHITRON_AST_ERROR_GENERIC_HANDLER_ENTRY;

            assertThat(dsl.select(database.POSITION, database.CODE, database.SQL_STATE)
                    .from(database).fetch())
                .as("one discriminator written, the other absent, and no column for a class name")
                .extracting(r -> r.value1(), r -> r.value2(), r -> r.value3())
                .containsExactly(tuple(0, "23505", null));

            assertThat(dsl.select(validation.POSITION).from(validation)
                    .fetch(validation.POSITION))
                .as("the position is the whole row, this kind carrying nothing the directive allows")
                .containsExactly(1);

            assertThat(dsl.select(generic.POSITION, generic.CLASS_NAME, generic.DESCRIPTION)
                    .from(generic).fetch())
                .as("and the index is the author's order across all three, not a count per kind")
                .extracting(r -> r.value1(), r -> r.value2(), r -> r.value3())
                .containsExactly(tuple(2, "java.lang.IllegalStateException", "no"));
        });
    }

    /**
     * The cost of a relation per kind, and why it is affordable. A field written on a kind that
     * rejects it has no column to land in, so the decode drops it; the whole list stands verbatim
     * beside it, which is where a reader reporting the rejection reads.
     */
    @Test
    @DisplayName("a field written on a kind that rejects it stands in the verbatim argument row")
    void aRejectedFieldStandsVerbatim(@TempDir Path tmp) {
        write(tmp, "errors.graphqls",
            "type Failed @error(handlers: [{handler: DATABASE, className: \"X\"}]) { m: String }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);

            assertThat(dsl.fetchCount(GRAPHITRON_AST_ERROR_DATABASE_HANDLER_ENTRY))
                .as("the handler is a row of its own kind").isEqualTo(1);
            assertThat(dsl.select(GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY.VALUE_SDL)
                    .from(GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY)
                    .where(GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY.NAME.eq("handlers"))
                    .fetchOne(GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY.VALUE_SDL))
                .as("and the field its kind rejects is still readable, this relation having no "
                    + "column for it")
                .contains("className", "\"X\"");
        });
    }

    /**
     * What the author deleted goes, and it goes without this writer's sweep having to find it: the
     * decode hangs off the application row by key, and the reading that sweeps that row takes the
     * decode with it.
     */
    @Test
    @DisplayName("a decode whose application the author removed is gone")
    void aDecodeWhoseApplicationWentIsGone(@TempDir Path tmp) {
        write(tmp, "film.graphqls", "type Film @table(name: \"film\") { title: String }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            assertThat(dsl.fetchCount(GRAPHITRON_AST_TABLE_ENTRY)).as("before the edit").isEqualTo(1);

            write(tmp, "film.graphqls", "type Film { title: String }\n");
            read(dsl, tmp);

            assertThat(dsl.fetchCount(GRAPHITRON_AST_TABLE_ENTRY))
                .as("the application is gone, so what it meant is not a fact any more").isZero();
        });
    }

    /**
     * The case the cascade cannot reach: the position survives because another directive now
     * occupies it, so the application row is updated rather than deleted and only this writer's own
     * sweep can find the decode it left behind.
     */
    @Test
    @DisplayName("a decode whose position another directive now occupies is swept")
    void aDecodeReplacedInPlaceIsSwept(@TempDir Path tmp) {
        write(tmp, "film.graphqls", "type Film @table(name: \"film\") { title: String }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);

            write(tmp, "film.graphqls", "type Film @error(handlers: []) { title: String }\n");
            read(dsl, tmp);

            assertThat(dsl.fetchCount(GRAPHQL_AST_TYPE_DIRECTIVE_ENTRY))
                .as("one application, at the position the other one had").isEqualTo(1);
            assertThat(dsl.fetchCount(GRAPHITRON_AST_TABLE_ENTRY))
                .as("and the binding it replaced is not a fact any more").isZero();
        });
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
