package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.sdl.SdlEntries;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
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

import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_TYPE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_TYPE_EXTENSION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_INPUT_OBJECT_TYPE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_INPUT_OBJECT_TYPE_EXTENSION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_INTERFACE_TYPE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_INTERFACE_TYPE_EXTENSION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_OBJECT_TYPE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_OBJECT_TYPE_EXTENSION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SCALAR_TYPE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SCALAR_TYPE_EXTENSION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_UNION_TYPE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_UNION_TYPE_EXTENSION_ENTRY;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The document reader, on the corpus a merge cannot hold.
 *
 * <p>Two files declare {@code type X}. That is an error and the schema will not build, but it is an
 * error a developer makes by saving a file, so the store has to be able to say what each document
 * declared rather than only that they collided. Keyed by position, both are rows, and which of them
 * is the incumbent is a question about their files rather than about which write landed first.
 */
class SdlEntriesTest {

    private static final String GRAPH = "entries";

    @Test
    @DisplayName("two documents declaring one object type are both written")
    void twoDocumentsDeclaringOneTypeAreBothWritten(@TempDir Path tmp) {
        Path first = write(tmp, "first.graphqls", "type X { a: String }\n");
        Path second = write(tmp, "second.graphqls", "type X { b: Int! }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, first, second);

            assertThat(dsl.select(GRAPHQL_OBJECT_TYPE_ENTRY.SOURCE_NAME)
                    .from(GRAPHQL_OBJECT_TYPE_ENTRY)
                    .where(GRAPHQL_OBJECT_TYPE_ENTRY.NAME.eq("X"))
                    .fetch(GRAPHQL_OBJECT_TYPE_ENTRY.SOURCE_NAME))
                .as("one row per declaration site, so a corpus the merge refuses is a corpus the "
                    + "store still holds")
                .extracting(SdlEntriesTest::name)
                .containsExactlyInAnyOrder("first.graphqls", "second.graphqls");
        });
    }

    /**
     * An extension is a different node kind and belongs to a different relation, so it must not
     * appear here. Asserted rather than assumed: this reader reaches the node lists through
     * {@code getTypes(ObjectTypeDefinition.class)}, and {@code ObjectTypeExtensionDefinition}
     * extends {@code ObjectTypeDefinition}, so a registry that kept extensions among its types would
     * quietly hand this relation two rows for one declaration.
     */
    @Test
    @DisplayName("an extension is not an object type definition, so it lands in neither this relation nor twice")
    void anExtensionIsNotWrittenHere(@TempDir Path tmp) {
        Path base = write(tmp, "base.graphqls", "type Q { a: String }\n");
        Path extension = write(tmp, "extension.graphqls", "extend type Q { b: String }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, base, extension);

            assertThat(dsl.select(GRAPHQL_OBJECT_TYPE_ENTRY.SOURCE_NAME)
                    .from(GRAPHQL_OBJECT_TYPE_ENTRY)
                    .where(GRAPHQL_OBJECT_TYPE_ENTRY.NAME.eq("Q"))
                    .fetch(GRAPHQL_OBJECT_TYPE_ENTRY.SOURCE_NAME))
                .as("the base declaration only; the extension is its own node kind and its own "
                    + "relation, which is what this family mirroring graphql-java's own shapes buys")
                .extracting(SdlEntriesTest::name)
                .containsExactly("base.graphqls");
        });
    }

    /**
     * Each node kind lands in its own relation, which is what the mirroring is for. One document
     * carrying an object type, an interface and an extension of the object type puts one row in each
     * of the three relations and nothing anywhere else.
     */
    @Test
    @DisplayName("each node kind lands in its own relation")
    void eachNodeKindLandsInItsOwnRelation(@TempDir Path tmp) {
        Path file = write(tmp, "kinds.graphqls", """
            interface Named { name: String }
            type Thing implements Named { name: String }
            extend type Thing { extra: Int }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, file);

            assertThat(dsl.fetchCount(GRAPHQL_OBJECT_TYPE_ENTRY,
                    GRAPHQL_OBJECT_TYPE_ENTRY.NAME.eq("Thing")))
                .as("the object type's base declaration").isEqualTo(1);
            assertThat(dsl.fetchCount(GRAPHQL_INTERFACE_TYPE_ENTRY,
                    GRAPHQL_INTERFACE_TYPE_ENTRY.NAME.eq("Named")))
                .as("the interface, in the relation its node kind owns").isEqualTo(1);
            assertThat(dsl.fetchCount(GRAPHQL_OBJECT_TYPE_EXTENSION_ENTRY,
                    GRAPHQL_OBJECT_TYPE_EXTENSION_ENTRY.NAME.eq("Thing")))
                .as("and the extension, which shares the object type's name and not its relation")
                .isEqualTo(1);
        });
    }

    /**
     * Every base kind and every extension kind, each in the relation its node kind owns.
     *
     * <p>Also the one thing {@code scalars()} does that the other accessors do not: it hands back
     * the built-in scalars alongside any the document declared. Those are the engine's, not a
     * document's, so they carry no position and no file and must not become rows. {@code String} is
     * asserted absent for that reason.
     */
    @Test
    @DisplayName("every node kind lands in its own relation, and the built-in scalars land nowhere")
    void everyNodeKindLandsInItsOwnRelation(@TempDir Path tmp) {
        Path file = write(tmp, "all.graphqls", """
            "an object" type Obj { a: String }
            interface Iface { a: String }
            union Both = Obj
            enum Colour { RED }
            input Filter { a: String }
            scalar Money
            extend type Obj { b: Int }
            extend interface Iface { b: Int }
            extend union Both = Obj
            extend enum Colour { BLUE }
            extend input Filter { b: Int }
            extend scalar Money @deprecated
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, file);

            // Exactly one row each, not merely present. Every extension node class extends its
            // base's, so a base relation reached through getTypes(Base.class) would hold two rows
            // for one name if the registry kept extensions among its types. Counting is what says
            // it does not; a containment assertion would pass either way.
            assertThat(names(dsl, GRAPHQL_OBJECT_TYPE_ENTRY, GRAPHQL_OBJECT_TYPE_ENTRY.NAME, file))
                .as("object type, and only the base declaration").containsExactly("Obj");
            assertThat(names(dsl, GRAPHQL_INTERFACE_TYPE_ENTRY, GRAPHQL_INTERFACE_TYPE_ENTRY.NAME, file))
                .as("interface type, and only the base declaration").containsExactly("Iface");
            assertThat(names(dsl, GRAPHQL_UNION_TYPE_ENTRY, GRAPHQL_UNION_TYPE_ENTRY.NAME, file))
                .as("union type, and only the base declaration").containsExactly("Both");
            assertThat(names(dsl, GRAPHQL_ENUM_TYPE_ENTRY, GRAPHQL_ENUM_TYPE_ENTRY.NAME, file))
                .as("enum type, and only the base declaration").containsExactly("Colour");
            assertThat(names(dsl, GRAPHQL_INPUT_OBJECT_TYPE_ENTRY, GRAPHQL_INPUT_OBJECT_TYPE_ENTRY.NAME, file))
                .as("input object type, and only the base declaration").containsExactly("Filter");

            assertThat(names(dsl, GRAPHQL_SCALAR_TYPE_ENTRY, GRAPHQL_SCALAR_TYPE_ENTRY.NAME, file))
                .as("the declared scalar is a row; the engine's built-ins are not a document's "
                    + "facts, carry no position to key on, and so are in no file's rows at all")
                .containsExactly("Money");

            assertThat(names(dsl, GRAPHQL_OBJECT_TYPE_EXTENSION_ENTRY, GRAPHQL_OBJECT_TYPE_EXTENSION_ENTRY.NAME, file))
                .as("object extension, and only the extension").containsExactly("Obj");
            assertThat(names(dsl, GRAPHQL_INTERFACE_TYPE_EXTENSION_ENTRY, GRAPHQL_INTERFACE_TYPE_EXTENSION_ENTRY.NAME, file))
                .as("interface extension, and only the extension").containsExactly("Iface");
            assertThat(names(dsl, GRAPHQL_UNION_TYPE_EXTENSION_ENTRY, GRAPHQL_UNION_TYPE_EXTENSION_ENTRY.NAME, file))
                .as("union extension, and only the extension").containsExactly("Both");
            assertThat(names(dsl, GRAPHQL_ENUM_TYPE_EXTENSION_ENTRY, GRAPHQL_ENUM_TYPE_EXTENSION_ENTRY.NAME, file))
                .as("enum extension, and only the extension").containsExactly("Colour");
            assertThat(names(dsl, GRAPHQL_INPUT_OBJECT_TYPE_EXTENSION_ENTRY, GRAPHQL_INPUT_OBJECT_TYPE_EXTENSION_ENTRY.NAME, file))
                .as("input object extension, and only the extension").containsExactly("Filter");
            assertThat(names(dsl, GRAPHQL_SCALAR_TYPE_EXTENSION_ENTRY, GRAPHQL_SCALAR_TYPE_EXTENSION_ENTRY.NAME, file))
                .as("scalar extension, and only the extension").containsExactly("Money");

            assertThat(dsl.select(GRAPHQL_OBJECT_TYPE_ENTRY.DESCRIPTION)
                    .from(GRAPHQL_OBJECT_TYPE_ENTRY)
                    .where(GRAPHQL_OBJECT_TYPE_ENTRY.NAME.eq("Obj"))
                    .fetchOne(GRAPHQL_OBJECT_TYPE_ENTRY.DESCRIPTION))
                .as("a description written on a base declaration is kept").isEqualTo("an object");
        });
    }

    /**
     * A store is not always empty. The same file gets read again on the next build, so writing it
     * twice has to leave the same rows rather than double them or fail on the key.
     */
    @Test
    @DisplayName("reading one file twice leaves one file's rows")
    void readingTheSameFileTwiceIsIdempotent(@TempDir Path tmp) {
        Path file = write(tmp, "again.graphqls", "type Twice { a: String }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, file);
            read(dsl, file);

            assertThat(names(dsl, GRAPHQL_OBJECT_TYPE_ENTRY, GRAPHQL_OBJECT_TYPE_ENTRY.NAME, file))
                .as("the second reading replaces the first rather than adding to it")
                .containsExactly("Twice");
        });
    }

    /**
     * The case a per-file delete exists for, and the one deriving the file from the declarations
     * would get wrong: an edit that removes a declaration. The rows that are gone from the document
     * have to be gone from the store, which nothing but a delete scoped to the file can do.
     */
    @Test
    @DisplayName("an edit that removes a declaration removes its row")
    void anEditThatRemovesADeclarationRemovesItsRow(@TempDir Path tmp) {
        Path file = write(tmp, "edited.graphqls", "type Kept { a: String }\ntype Dropped { b: Int }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, file);
            assertThat(names(dsl, GRAPHQL_OBJECT_TYPE_ENTRY, GRAPHQL_OBJECT_TYPE_ENTRY.NAME, file))
                .as("both declarations, before the edit")
                .containsExactlyInAnyOrder("Kept", "Dropped");

            write(tmp, "edited.graphqls", "type Kept { a: String }\n");
            read(dsl, file);

            assertThat(names(dsl, GRAPHQL_OBJECT_TYPE_ENTRY, GRAPHQL_OBJECT_TYPE_ENTRY.NAME, file))
                .as("what the author deleted is deleted; a reader that only inserted would leave "
                    + "Dropped behind and no query over these rows could tell it was stale")
                .containsExactly("Kept");
        });
    }

    /**
     * An edit that changes a declaration in place updates its row, which is the upsert's other half.
     *
     * <p>Worth asserting on its own because the failure would not look like staleness. A row the
     * second reading failed to update would also keep the first reading's instant, so the sweep would
     * delete it and the declaration would vanish from a document that still declares it.
     */
    @Test
    @DisplayName("an edit in place updates the row rather than replacing or dropping it")
    void anEditInPlaceUpdatesTheRow(@TempDir Path tmp) {
        Path file = write(tmp, "described.graphqls", "\"first\" type Same { a: String }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, file);
            write(tmp, "described.graphqls", "\"second\" type Same { a: String }\n");
            read(dsl, file);

            assertThat(dsl.select(GRAPHQL_OBJECT_TYPE_ENTRY.DESCRIPTION)
                    .from(GRAPHQL_OBJECT_TYPE_ENTRY)
                    .where(GRAPHQL_OBJECT_TYPE_ENTRY.NAME.eq("Same"))
                    .fetch(GRAPHQL_OBJECT_TYPE_ENTRY.DESCRIPTION))
                .as("one row, carrying what the document says now")
                .containsExactly("second");
        });
    }

    /**
     * The names one document declared in one relation. Scoped to the file, because the bundled
     * directive vocabulary is read alongside it and declares enums and input objects of its own; an
     * unscoped count would be asserting something about that document too.
     */
    private static List<String> names(DSLContext dsl, Table<?> table, Field<String> name, Path file) {
        return dsl.select(name).from(table)
            .where(table.field("SOURCE_NAME", String.class).eq(file.toString()))
            .fetch(name);
    }

    /**
     * Reads each file as its own document and writes it, which is the whole of the reader's use.
     * The bundled directive vocabulary is a document like any other and is read alongside them.
     */
    private static void read(DSLContext dsl, Path... files) {
        // A distinct instant per reading, which is what the sweep tells readings apart by.
        LocalDateTime touchedAt = LocalDateTime.now();
        seedSource(dsl, SchemaLoader.DIRECTIVES_SOURCE_NAME, "SCHEMA_FILE");
        Arrays.stream(files).forEach(file -> seedSource(dsl, file.toString(), "SCHEMA_FILE"));
        SchemaLoader.parsePerSource(Arrays.stream(files).map(SchemaSource::file).toList())
            .perSource()
            .forEach(document -> SdlEntries.write(dsl, GRAPH, document.sourceName(), document.registry(), touchedAt));
    }

    private static Path write(Path directory, String name, String sdl) {
        try {
            return Files.writeString(directory.resolve(name), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String name(String sourceName) {
        return sourceName.substring(sourceName.lastIndexOf('/') + 1);
    }
}
