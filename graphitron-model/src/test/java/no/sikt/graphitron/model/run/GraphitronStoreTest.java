package no.sikt.graphitron.model.run;

import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run opens a store, fills it from its own configuration, and closes it.
 *
 * <p>What these pin is the direction. Nothing is parsed before the store is opened and nothing is
 * handed in already read: the caller supplies a configuration and the gatherer finds the files
 * itself, which is what makes the per-file rows possible at all.
 */
class GraphitronStoreTest {

    /** The loader the generated jOOQ classes are on, which for this module's tests is its own. */
    private static final ClassLoader CODEGEN = GraphitronStoreTest.class.getClassLoader();

    @Test
    @DisplayName("a run opens a store, captures its configured schema into it, and closes it")
    void aRunCapturesItsOwnConfiguration(@TempDir Path tmp) {
        write(tmp, "film.graphqls", "type Film { title: String }\n");
        write(tmp, "actor.graphqls", "type Actor { name: String }\n");

        try (var store = GraphitronStore.inMemory()) {
            GraphitronStore.capture(store, new GraphIdentity("g", tmp), configOver(tmp), CODEGEN);

            assertThat(store.dsl()
                    .select(GRAPHQL_AST_TYPE_DECLARATION_ENTRY.NAME)
                    .from(GRAPHQL_AST_TYPE_DECLARATION_ENTRY)
                    .where(GRAPHQL_AST_TYPE_DECLARATION_ENTRY.GRAPH_NAME.eq("g"))
                    .fetch(GRAPHQL_AST_TYPE_DECLARATION_ENTRY.NAME))
                .as("both files were found and read, which no caller told the gatherer to do")
                .contains("Film", "Actor");

            assertThat(store.dsl()
                    .select(GRAPHQL_AST_FIELD_DEFINITION_ENTRY.NAME)
                    .from(GRAPHQL_AST_FIELD_DEFINITION_ENTRY)
                    .where(GRAPHQL_AST_FIELD_DEFINITION_ENTRY.SOURCE_NAME
                        .like("%film.graphqls"))
                    .fetch(GRAPHQL_AST_FIELD_DEFINITION_ENTRY.NAME))
                .as("and what is inside the braces came with them")
                .containsExactly("title");
        }
    }

    /**
     * The two rows the entries hang their keys on. Asserted because a gatherer that wrote entries
     * without them could not have written entries at all, and one that wrote a source row with no
     * {@code source_ref} would leave every row it wrote looking like a reading the registry does
     * not vouch for.
     */
    @Test
    @DisplayName("the registry row and the graph row are written before the rows that need them")
    void theKeysTheEntriesHangOnAreWritten(@TempDir Path tmp) {
        write(tmp, "film.graphqls", "type Film { title: String }\n");

        try (var store = GraphitronStore.inMemory()) {
            GraphitronStore.capture(store, new GraphIdentity("g", tmp), configOver(tmp), CODEGEN);

            assertThat(store.dsl().fetchCount(STORE_SOURCE,
                    STORE_SOURCE.SOURCE_NAME.like("%film.graphqls")))
                .as("the file is in the source registry").isEqualTo(1);

            assertThat(store.dsl()
                    .selectDistinct(GRAPHQL_AST_TYPE_DECLARATION_ENTRY.SOURCE_REF)
                    .from(GRAPHQL_AST_TYPE_DECLARATION_ENTRY)
                    .where(GRAPHQL_AST_TYPE_DECLARATION_ENTRY.NAME.eq("Film"))
                    .fetch(GRAPHQL_AST_TYPE_DECLARATION_ENTRY.SOURCE_REF))
                .as("and the twin vouches for the reading")
                .allSatisfy(ref -> assertThat(ref).endsWith("film.graphqls"));
        }
    }

    /**
     * Two documents declaring one name, which is the corpus a merged registry cannot hold and the
     * reason this gatherer reads the files itself. Both are rows.
     */
    @Test
    @DisplayName("two files declaring one type are both captured")
    void twoFilesDeclaringOneTypeAreBothCaptured(@TempDir Path tmp) {
        write(tmp, "first.graphqls", "type X { a: String }\n");
        write(tmp, "second.graphqls", "type X { b: Int }\n");

        try (var store = GraphitronStore.inMemory()) {
            GraphitronStore.capture(store, new GraphIdentity("g", tmp), configOver(tmp), CODEGEN);

            assertThat(store.dsl()
                    .select(GRAPHQL_AST_TYPE_DECLARATION_ENTRY.SOURCE_NAME)
                    .from(GRAPHQL_AST_TYPE_DECLARATION_ENTRY)
                    .where(GRAPHQL_AST_TYPE_DECLARATION_ENTRY.NAME.eq("X"))
                    .fetch(GRAPHQL_AST_TYPE_DECLARATION_ENTRY.SOURCE_NAME))
                .as("one row per declaration, the loser of the merge included")
                .hasSize(2);
        }
    }

    /**
     * Both gatherers through one call. The catalog half needs a class loader, which is the one
     * thing configuration cannot carry: the generated jOOQ classes are resolved reflectively and
     * only the build tool can assemble the classpath they live on.
     */
    @Test
    @DisplayName("one call fills the schema and the catalog")
    void oneCallFillsBoth(@TempDir Path tmp) {
        write(tmp, "film.graphqls", "type Film { title: String }\n");
        var config = SubjectConfig.of(new SchemaRecipe(tmp.resolve("pom.xml"),
            List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls")),
            "no.sikt.graphitron.rewrite.test.jooq");

        try (var store = GraphitronStore.inMemory()) {
            GraphitronStore.capture(store, new GraphIdentity("g", tmp), config, CODEGEN);

            assertThat(store.dsl().fetchCount(GRAPHQL_AST_TYPE_DECLARATION_ENTRY,
                    GRAPHQL_AST_TYPE_DECLARATION_ENTRY.NAME.eq("Film")))
                .as("the schema the recipe named").isEqualTo(1);
            assertThat(store.dsl().fetchCount(SQL_TABLE, SQL_TABLE.TABLE_NAME.eq("film")))
                .as("and the database the jOOQ package describes").isEqualTo(1);
        }
    }

    /** A configuration naming no jOOQ package captures no catalog facts and never touches the loader. */
    @Test
    @DisplayName("a run with no jOOQ package captures no catalog")
    void noJooqPackageCapturesNoCatalog(@TempDir Path tmp) {
        write(tmp, "film.graphqls", "type Film { title: String }\n");

        try (var store = GraphitronStore.inMemory()) {
            GraphitronStore.capture(store, new GraphIdentity("g", tmp), configOver(tmp), null);

            assertThat(store.dsl().fetchCount(GRAPHQL_AST_TYPE_DECLARATION_ENTRY))
                .as("the schema still lands").isPositive();
            assertThat(store.dsl().fetchCount(SQL_TABLE)).as("and no catalog rows").isZero();
        }
    }

    private static SubjectConfig configOver(Path baseDir) {
        return SubjectConfig.of(new SchemaRecipe(baseDir.resolve("pom.xml"),
            List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls")));
    }

    private static void write(Path directory, String name, String sdl) {
        try {
            Files.writeString(directory.resolve(name), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
