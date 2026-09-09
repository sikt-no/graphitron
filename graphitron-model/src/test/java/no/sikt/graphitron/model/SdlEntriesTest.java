package no.sikt.graphitron.model;

import graphql.language.DirectiveDefinition;
import graphql.language.DirectiveLocation;
import graphql.language.EnumValueDefinition;
import graphql.language.FieldDefinition;
import graphql.language.ImplementingTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.Node;
import graphql.language.NodeTraverser;
import graphql.language.NodeVisitorStub;
import graphql.language.OperationTypeDefinition;
import graphql.language.SchemaDefinition;
import graphql.language.TypeDefinition;
import graphql.language.TypeName;
import graphql.language.UnionTypeDefinition;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import no.sikt.graphitron.model.capture.sdl.SdlEntries;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_APPLIED_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_DIRECTIVE_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_DIRECTIVE_LOCATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENUM_VALUE_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_IMPLEMENTS_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_INPUT_VALUE_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_OPERATION_TYPE_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_SCHEMA_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_UNION_MEMBER_ENTRY;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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

    /**
     * Which relation holds which node kind. Stated here rather than derived, so a relation added to
     * the family without a line here is a relation the census cannot see and the roster below fails
     * on: the map is pinned by the same equality the counts are.
     */
    private static final Map<String, Table<?>> RELATIONS = new LinkedHashMap<>(Map.of(
        "TYPE_DECLARATION", GRAPHQL_AST_TYPE_DECLARATION_ENTRY,
        "FIELD_DEFINITION", GRAPHQL_AST_FIELD_DEFINITION_ENTRY,
        "INPUT_VALUE_DEFINITION", GRAPHQL_AST_INPUT_VALUE_DEFINITION_ENTRY,
        "ENUM_VALUE_DEFINITION", GRAPHQL_AST_ENUM_VALUE_DEFINITION_ENTRY,
        "IMPLEMENTS", GRAPHQL_AST_IMPLEMENTS_ENTRY,
        "UNION_MEMBER", GRAPHQL_AST_UNION_MEMBER_ENTRY,
        "DIRECTIVE_DEFINITION", GRAPHQL_AST_DIRECTIVE_DEFINITION_ENTRY,
        "DIRECTIVE_LOCATION", GRAPHQL_AST_DIRECTIVE_LOCATION_ENTRY,
        "SCHEMA_DEFINITION", GRAPHQL_AST_SCHEMA_DEFINITION_ENTRY,
        "OPERATION_TYPE_DEFINITION", GRAPHQL_AST_OPERATION_TYPE_DEFINITION_ENTRY));

    static {
        RELATIONS.put("APPLIED_DIRECTIVE", GRAPHQL_AST_APPLIED_DIRECTIVE_ENTRY);
        RELATIONS.put("APPLIED_ARGUMENT", GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY);
    }

    @Test
    @DisplayName("two documents declaring one object type are both written")
    void twoDocumentsDeclaringOneTypeAreBothWritten(@TempDir Path tmp) {
        Path first = write(tmp, "first.graphqls", "type X { a: String }\n");
        Path second = write(tmp, "second.graphqls", "type X { b: Int! }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, first, second);

            assertThat(dsl.select(GRAPHQL_AST_TYPE_DECLARATION_ENTRY.SOURCE_NAME)
                    .from(GRAPHQL_AST_TYPE_DECLARATION_ENTRY)
                    .where(GRAPHQL_AST_TYPE_DECLARATION_ENTRY.NAME.eq("X"))
                    .fetch(GRAPHQL_AST_TYPE_DECLARATION_ENTRY.SOURCE_NAME))
                .as("one row per declaration site, so a corpus the merge refuses is a corpus the "
                    + "store still holds")
                .extracting(SdlEntriesTest::name)
                .containsExactlyInAnyOrder("first.graphqls", "second.graphqls");
        });
    }

    /**
     * Every declaration form, base and extension, in one relation saying which it is.
     *
     * <p>Also the one thing {@code scalars()} does that the other accessors do not: it hands back
     * the built-in scalars alongside any the document declared. Those are the engine's, not a
     * document's, so they carry no position and no file and must not become rows. {@code String} is
     * absent for that reason, which the scoped count is what says.
     */
    @Test
    @DisplayName("every declaration form is one row saying its kind, and the built-in scalars are no rows at all")
    void everyDeclarationFormIsOneRowSayingItsKind(@TempDir Path tmp) {
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
            var t = GRAPHQL_AST_TYPE_DECLARATION_ENTRY;

            assertThat(dsl.select(t.NAME, t.KIND, t.IS_EXTENSION).from(t)
                    .where(t.SOURCE_NAME.eq(file.toString())).fetch())
                .as("twelve declaration sites, each saying its form and whether it extends; the "
                    + "engine's built-in scalars are not a document's facts, carry no position to "
                    + "key on, and so are in no file's rows at all")
                .extracting(r -> r.value1(), r -> r.value2(), r -> r.value3())
                .containsExactlyInAnyOrder(
                    tuple("Obj", "OBJECT", false), tuple("Iface", "INTERFACE", false),
                    tuple("Both", "UNION", false), tuple("Colour", "ENUM", false),
                    tuple("Filter", "INPUT_OBJECT", false), tuple("Money", "SCALAR", false),
                    tuple("Obj", "OBJECT", true), tuple("Iface", "INTERFACE", true),
                    tuple("Both", "UNION", true), tuple("Colour", "ENUM", true),
                    tuple("Filter", "INPUT_OBJECT", true), tuple("Money", "SCALAR", true));

            assertThat(dsl.select(t.DESCRIPTION).from(t)
                    .where(t.NAME.eq("Obj")).and(t.IS_EXTENSION.isFalse())
                    .fetchOne(t.DESCRIPTION))
                .as("a description written on a base declaration is kept").isEqualTo("an object");
        });
    }

    /**
     * Every node kind below a declaration, each in the relation its kind owns. One document
     * exercising all eleven, so a kind the reader stops descending into is a kind that shows up
     * empty here rather than quietly missing from a corpus nobody counted.
     */
    @Test
    @DisplayName("every node kind below a declaration lands in its own relation")
    void everyNodeKindBelowADeclarationLandsInItsOwnRelation(@TempDir Path tmp) {
        Path file = write(tmp, "kinds.graphqls", """
            directive @key(fields: String!) repeatable on OBJECT | INTERFACE
            interface Named { name: String }
            type Film implements Named @key(fields: "id") {
              name: String
              rated(scale: Rating = G): Rating @deprecated(reason: "gone")
            }
            union Media = Film
            enum Rating { G PG }
            input Filter { q: String = "a" }
            schema { query: Film }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, file);

            assertThat(named(dsl, GRAPHQL_AST_FIELD_DEFINITION_ENTRY,
                    GRAPHQL_AST_FIELD_DEFINITION_ENTRY.NAME, file))
                .as("fields, on the interface and on the object alike")
                .containsExactlyInAnyOrder("name", "name", "rated");
            assertThat(named(dsl, GRAPHQL_AST_INPUT_VALUE_DEFINITION_ENTRY,
                    GRAPHQL_AST_INPUT_VALUE_DEFINITION_ENTRY.NAME, file))
                .as("one node kind for all three hosts: a field argument, an input object's field, "
                    + "and a directive definition's argument")
                .containsExactlyInAnyOrder("scale", "q", "fields");
            assertThat(named(dsl, GRAPHQL_AST_ENUM_VALUE_DEFINITION_ENTRY,
                    GRAPHQL_AST_ENUM_VALUE_DEFINITION_ENTRY.NAME, file))
                .containsExactlyInAnyOrder("G", "PG");
            assertThat(named(dsl, GRAPHQL_AST_IMPLEMENTS_ENTRY,
                    GRAPHQL_AST_IMPLEMENTS_ENTRY.INTERFACE_NAME, file))
                .containsExactly("Named");
            assertThat(named(dsl, GRAPHQL_AST_UNION_MEMBER_ENTRY,
                    GRAPHQL_AST_UNION_MEMBER_ENTRY.MEMBER_NAME, file))
                .containsExactly("Film");
            assertThat(named(dsl, GRAPHQL_AST_DIRECTIVE_DEFINITION_ENTRY,
                    GRAPHQL_AST_DIRECTIVE_DEFINITION_ENTRY.NAME, file))
                .containsExactly("key");
            assertThat(named(dsl, GRAPHQL_AST_DIRECTIVE_LOCATION_ENTRY,
                    GRAPHQL_AST_DIRECTIVE_LOCATION_ENTRY.LOCATION, file))
                .containsExactlyInAnyOrder("OBJECT", "INTERFACE");
            assertThat(dsl.fetchCount(GRAPHQL_AST_SCHEMA_DEFINITION_ENTRY,
                    GRAPHQL_AST_SCHEMA_DEFINITION_ENTRY.SOURCE_NAME.eq(file.toString())))
                .isEqualTo(1);
            assertThat(named(dsl, GRAPHQL_AST_OPERATION_TYPE_DEFINITION_ENTRY,
                    GRAPHQL_AST_OPERATION_TYPE_DEFINITION_ENTRY.OPERATION, file))
                .as("the operation as the grammar spells it, which is lower case")
                .containsExactly("query");
            assertThat(named(dsl, GRAPHQL_AST_APPLIED_DIRECTIVE_ENTRY,
                    GRAPHQL_AST_APPLIED_DIRECTIVE_ENTRY.NAME, file))
                .as("applications, wherever they were written; the definition above is not one")
                .containsExactlyInAnyOrder("key", "deprecated");
            assertThat(dsl.select(GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY.NAME,
                        GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY.VALUE_SDL)
                    .from(GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY)
                    .where(GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY.SOURCE_NAME.eq(file.toString())).fetch())
                .as("the value kept as the author wrote it")
                .extracting(r -> r.value1(), r -> r.value2())
                .containsExactlyInAnyOrder(tuple("fields", "\"id\""), tuple("reason", "\"gone\""));
        });
    }

    /**
     * The reference the whole family is shaped around: a child names the position of the node it was
     * written inside, and that position is a row of the supertype.
     *
     * <p>Asserted by joining rather than by reading the columns back, because the columns being
     * present is not the claim. The claim is that they reach the parent, and the two fields of one
     * name on two different declarations are what would let a reader that keyed on the name alone
     * pass this while being wrong.
     */
    @Test
    @DisplayName("a child names the position of the node it was written inside")
    void aChildNamesThePositionOfTheNodeItWasWrittenInside(@TempDir Path tmp) {
        Path file = write(tmp, "nested.graphqls", """
            type A { shared: String }
            type B { shared: Int }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, file);
            var child = GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
            var parent = GRAPHQL_AST_TYPE_DECLARATION_ENTRY;

            assertThat(dsl.select(parent.NAME, child.NAME, child.NAMED_TYPE)
                    .from(child)
                    .join(parent)
                    .on(child.GRAPH_NAME.eq(parent.GRAPH_NAME))
                    .and(child.SOURCE_NAME.eq(parent.SOURCE_NAME))
                    .and(child.PARENT_LINE.eq(parent.SOURCE_LINE))
                    .and(child.PARENT_COLUMN.eq(parent.SOURCE_COLUMN))
                    .where(child.SOURCE_NAME.eq(file.toString()))
                    .fetch())
                .as("each field reaches the declaration it was written inside, and the two of one "
                    + "name reach different ones")
                .extracting(r -> r.value1(), r -> r.value2(), r -> r.value3())
                .containsExactlyInAnyOrder(
                    tuple("A", "shared", "String"), tuple("B", "shared", "Int"));
        });
    }

    /**
     * The claim the dropped ordinal column rests on: within one parent, ordering the nodes of a kind
     * by the position they were written at is the order the author wrote them in.
     *
     * <p>Worth an assertion rather than an argument, because an ordinal column would have been the
     * obvious thing to store and the reason not to is that the key already decides it. Two nodes
     * cannot share a position, so the ordering is total; what this checks is that it is also the
     * right one, on a declaration whose fields and arguments are written in an order that is not
     * alphabetical and whose children of other kinds are interleaved among them.
     */
    @Test
    @DisplayName("the order the author wrote them in is the order of their positions")
    void theWrittenOrderIsTheOrderOfThePositions(@TempDir Path tmp) {
        Path file = write(tmp, "ordered.graphqls", """
            interface Old { zzz: String }
            type Counted implements Old @deprecated {
              zebra(yak: Int, ant: Int): String
              aardvark: String
            }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, file);
            var field = GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
            var argument = GRAPHQL_AST_INPUT_VALUE_DEFINITION_ENTRY;

            assertThat(dsl.select(field.NAME).from(field)
                    .where(field.SOURCE_NAME.eq(file.toString()))
                    .and(field.NAME.ne("zzz"))
                    .orderBy(field.PARENT_LINE, field.PARENT_COLUMN,
                             field.SOURCE_LINE, field.SOURCE_COLUMN)
                    .fetch(field.NAME))
                .as("written order, not alphabetical, and the implements clause and the directive "
                    + "written above them are in other relations and cannot disturb it")
                .containsExactly("zebra", "aardvark");

            assertThat(dsl.select(argument.NAME).from(argument)
                    .where(argument.SOURCE_NAME.eq(file.toString()))
                    .orderBy(argument.PARENT_LINE, argument.PARENT_COLUMN,
                             argument.SOURCE_LINE, argument.SOURCE_COLUMN)
                    .fetch(argument.NAME))
                .as("and within the field, which is where the specification makes order meaningful")
                .containsExactly("yak", "ant");
        });
    }

    /**
     * A written type expression, kept whole and unwrapped beside itself. The four columns are the
     * node read down its own spine, and the expression they cannot express is what the fifth is for.
     */
    @Test
    @DisplayName("a type expression is kept as written and unwrapped beside itself")
    void aTypeExpressionIsKeptAsWrittenAndUnwrapped(@TempDir Path tmp) {
        Path file = write(tmp, "types.graphqls", """
            type Shapes {
              plain: String
              required: String!
              list: [String]
              deep: [[String]]!
              both: [String!]!
            }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, file);
            var t = GRAPHQL_AST_FIELD_DEFINITION_ENTRY;

            assertThat(dsl.select(t.NAME, t.TYPE_SDL, t.NAMED_TYPE, t.NON_NULL, t.IS_LIST, t.ITEM_NON_NULL)
                    .from(t).where(t.SOURCE_NAME.eq(file.toString())).fetch())
                .extracting(r -> r.value1(), r -> r.value2(), r -> r.value3(),
                            r -> r.value4(), r -> r.value5(), r -> r.value6())
                .containsExactlyInAnyOrder(
                    tuple("plain", "String", "String", false, false, null),
                    tuple("required", "String!", "String", true, false, null),
                    tuple("list", "[String]", "String", false, true, false),
                    // The doubly nested list is where the four columns stop and type_sdl carries on:
                    // they say a non-null list of something nullable, which is true of the outer list
                    // and says nothing about the inner one.
                    tuple("deep", "[[String]]!", "String", true, true, false),
                    tuple("both", "[String!]!", "String", true, true, true));
        });
    }

    /**
     * The twin, doing what it was split off the key to do: a file deleted from the registry leaves
     * this graph's reading of it standing and flagged.
     *
     * <p>Both halves are asserted because either alone would pass for the wrong reason. A key still
     * carrying the foreign key would refuse the delete outright; one carrying it as a cascade would
     * take the row with it.
     */
    @Test
    @DisplayName("removing a file from the registry flags the reading of it rather than deleting it")
    void removingASourceFlagsTheReadingRatherThanDeletingIt(@TempDir Path tmp) {
        Path file = write(tmp, "removed.graphqls", "type Gone { a: String }\n");
        var t = GRAPHQL_AST_TYPE_DECLARATION_ENTRY;

        withSeededStore(GRAPH, dsl -> {
            read(dsl, file);
            assertThat(dsl.select(t.SOURCE_NAME, t.SOURCE_REF).from(t)
                    .where(t.NAME.eq("Gone")).fetch())
                .as("before the removal, the registry vouches for the reading")
                .extracting(r -> r.value1(), r -> r.value2())
                .containsExactly(tuple(file.toString(), file.toString()));

            dsl.deleteFrom(STORE_SOURCE).where(STORE_SOURCE.SOURCE_NAME.eq(file.toString())).execute();

            assertThat(dsl.select(t.SOURCE_NAME, t.SOURCE_REF).from(t)
                    .where(t.NAME.eq("Gone")).fetch())
                .as("the row stands, still saying which file declared it, and no longer vouched for")
                .extracting(r -> r.value1(), r -> r.value2())
                .containsExactly(tuple(file.toString(), null));
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

            assertThat(named(dsl, GRAPHQL_AST_TYPE_DECLARATION_ENTRY,
                    GRAPHQL_AST_TYPE_DECLARATION_ENTRY.NAME, file))
                .as("the second reading replaces the first rather than adding to it")
                .containsExactly("Twice");
        });
    }

    /**
     * The case a per-file delete exists for, and the one deriving the file from the nodes would get
     * wrong: an edit that removes something. The rows that are gone from the document have to be gone
     * from the store, which nothing but a delete scoped to the file can do.
     *
     * <p>A field rather than a whole declaration, because the field is the case that also has to
     * sweep in an order: the supertype row the field's parent reference points at is swept in the
     * same pass, and a pass that took the parent first would fail the foreign key rather than the
     * assertion.
     */
    @Test
    @DisplayName("an edit that removes a field removes its row and its supertype row")
    void anEditThatRemovesAFieldRemovesItsRows(@TempDir Path tmp) {
        Path file = write(tmp, "edited.graphqls", "type Kept { a: String\n b: Int }\ntype Dropped { c: Int }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, file);
            assertThat(named(dsl, GRAPHQL_AST_FIELD_DEFINITION_ENTRY,
                    GRAPHQL_AST_FIELD_DEFINITION_ENTRY.NAME, file))
                .as("all three fields, before the edit").containsExactlyInAnyOrder("a", "b", "c");

            write(tmp, "edited.graphqls", "type Kept { a: String }\n");
            read(dsl, file);

            assertThat(named(dsl, GRAPHQL_AST_FIELD_DEFINITION_ENTRY,
                    GRAPHQL_AST_FIELD_DEFINITION_ENTRY.NAME, file))
                .as("what the author deleted is deleted; a reader that only inserted would leave "
                    + "them behind and no query over these rows could tell they were stale")
                .containsExactly("a");
            assertThat(named(dsl, GRAPHQL_AST_TYPE_DECLARATION_ENTRY,
                    GRAPHQL_AST_TYPE_DECLARATION_ENTRY.NAME, file))
                .as("and the declaration the author deleted with them")
                .containsExactly("Kept");
        });
    }

    /**
     * An edit that changes something in place updates its row, which is the upsert's other half.
     *
     * <p>Worth asserting on its own because the failure would not look like staleness. A row the
     * second reading failed to update would also keep the first reading's instant, so the sweep would
     * delete it and the node would vanish from a document that still holds it.
     */
    @Test
    @DisplayName("an edit in place updates the row rather than replacing or dropping it")
    void anEditInPlaceUpdatesTheRow(@TempDir Path tmp) {
        Path file = write(tmp, "described.graphqls", "type Same { a: String }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, file);
            write(tmp, "described.graphqls", "type Same { a: Int! }\n");
            read(dsl, file);

            assertThat(dsl.select(GRAPHQL_AST_FIELD_DEFINITION_ENTRY.TYPE_SDL)
                    .from(GRAPHQL_AST_FIELD_DEFINITION_ENTRY)
                    .where(GRAPHQL_AST_FIELD_DEFINITION_ENTRY.SOURCE_NAME.eq(file.toString()))
                    .fetch(GRAPHQL_AST_FIELD_DEFINITION_ENTRY.TYPE_SDL))
                .as("one row, carrying what the document says now")
                .containsExactly("Int!");
        });
    }

    /**
     * The coverage claim, checked against a second mechanism rather than against itself.
     *
     * <p>Every other assertion here names a kind the reader was written to hold, so none of them can
     * see a kind nobody thought of. This one counts the document twice: as the store holds it, and by
     * handing the same registry to graphql-java's {@code NodeTraverser}, which descends every child
     * of every node without being told which children exist. Useless for writing, and exactly right
     * for counting, so a descent quietly dropped fails here and so does a node kind the library
     * grows.
     */
    @Test
    @DisplayName("every node graphql-java parsed is a row, counted by graphql-java's own traverser")
    void everyNodeParsedIsARow(@TempDir Path tmp) {
        Path file = write(tmp, "census.graphqls", """
            "a directive" directive @tag(name: String! = "x", extra: [Int!]) repeatable on OBJECT | FIELD_DEFINITION
            "an interface" interface Named @tag(name: "i") { "a field" name: String @tag(name: "f") }
            type Film implements Named @tag(name: "t") {
              name: String
              rated("an argument" scale: Rating = G @tag(name: "a")): Rating
            }
            extend type Film @tag(name: "e") { extra: Int }
            interface Aged { age: Int }
            extend interface Aged { born: Int }
            union Media @tag(name: "u") = Film
            extend union Media = Film
            "an enum" enum Rating @tag(name: "n") { "a value" G @deprecated(reason: "old") PG }
            extend enum Rating { R }
            "an input" input Filter @tag(name: "in") { "an input field" q: String = "a" @tag(name: "if") }
            extend input Filter { limit: Int }
            "a scalar" scalar Money @tag(name: "s")
            extend scalar Money @deprecated
            schema @tag(name: "sc") { query: Film }
            extend schema @tag(name: "sx") { mutation: Film }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, file);

            Map<String, Long> stored = new TreeMap<>();
            RELATIONS.forEach((kind, table) -> {
                long rows = dsl.fetchCount(table,
                    table.field("SOURCE_NAME", String.class).eq(file.toString()));
                if (rows > 0) {
                    stored.put(kind, rows);
                }
            });

            assertThat(stored)
                .as("the store holds exactly the nodes graphql-java parsed, kind for kind")
                .isEqualTo(parsedNodes(file));
        });
    }

    /**
     * The same document counted by graphql-java's own traverser, which is told nothing about which
     * children to look for. A node the engine provides rather than the document is not counted, on
     * the reader's own terms: it has no file to belong to.
     */
    @SuppressWarnings("rawtypes")
    private static Map<String, Long> parsedNodes(Path file) {
        TypeDefinitionRegistry registry = SchemaLoader
            .parsePerSource(List.of(SchemaSource.file(file))).perSource().stream()
            .filter(parse -> parse.sourceName().equals(file.toString()))
            .findFirst().orElseThrow().registry();

        List<Node> roots = new ArrayList<>(registry.types().values());
        roots.addAll(registry.scalars().values());
        roots.addAll(registry.getDirectiveDefinitions().values());
        registry.schemaDefinition().ifPresent(roots::add);
        roots.addAll(registry.getSchemaExtensionDefinitions());
        registry.objectTypeExtensions().values().forEach(roots::addAll);
        registry.interfaceTypeExtensions().values().forEach(roots::addAll);
        registry.unionTypeExtensions().values().forEach(roots::addAll);
        registry.enumTypeExtensions().values().forEach(roots::addAll);
        registry.inputObjectTypeExtensions().values().forEach(roots::addAll);
        registry.scalarTypeExtensions().values().forEach(roots::addAll);

        Map<String, Long> counted = new TreeMap<>();
        new NodeTraverser().preOrder(new NodeVisitorStub() {
            @Override
            protected TraversalControl visitNode(Node node, TraverserContext<Node> context) {
                String kind = kindOf(node, context.getParentNode());
                if (kind != null && node.getSourceLocation() != null
                    && file.toString().equals(node.getSourceLocation().getSourceName())) {
                    counted.merge(kind, 1L, Long::sum);
                }
                return TraversalControl.CONTINUE;
            }
        }, roots);
        return counted;
    }

    /**
     * Which relation of the family would hold this node, or null where none would. The two type
     * references are told apart by their parent, which is the one thing the traverser does report
     * and the store decides by descending a named accessor instead.
     */
    private static String kindOf(Node<?> node, Node<?> parent) {
        return switch (node) {
            case TypeDefinition<?> ignored -> "TYPE_DECLARATION";
            case FieldDefinition ignored -> "FIELD_DEFINITION";
            case InputValueDefinition ignored -> "INPUT_VALUE_DEFINITION";
            case EnumValueDefinition ignored -> "ENUM_VALUE_DEFINITION";
            case DirectiveDefinition ignored -> "DIRECTIVE_DEFINITION";
            case DirectiveLocation ignored -> "DIRECTIVE_LOCATION";
            case SchemaDefinition ignored -> "SCHEMA_DEFINITION";
            case OperationTypeDefinition ignored -> "OPERATION_TYPE_DEFINITION";
            case graphql.language.Directive ignored -> "APPLIED_DIRECTIVE";
            case graphql.language.Argument ignored -> "APPLIED_ARGUMENT";
            case TypeName ignored when parent instanceof UnionTypeDefinition -> "UNION_MEMBER";
            case TypeName ignored when parent instanceof ImplementingTypeDefinition -> "IMPLEMENTS";
            default -> null;
        };
    }

    /**
     * One column of one document's rows. Scoped to the file, because the bundled directive
     * vocabulary is read alongside it and declares nodes of its own; an unscoped read would be
     * asserting something about that document too.
     */
    private static <R extends Record, T> List<T> named(DSLContext dsl, Table<R> table,
                                                       Field<T> column, Path file) {
        return dsl.select(column).from(table)
            .where(table.field("SOURCE_NAME", String.class).eq(file.toString()))
            .fetch(column);
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
