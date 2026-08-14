package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.hover.Hovers;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupKind;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.treesitter.jtreesitter.Point;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-directive hover content. Cursor inside a known argument value surfaces catalog metadata as
 * Markdown; a name token or a value nothing resolves falls back to the captured SDL's docstring for
 * the coordinate, and a coordinate nothing describes produces no hover at all.
 */
class HoversTest {

    /** The class the Java-side arms hover on, present in the census and declared in a source file. */
    private static final String SERVICE = "com.example.FilmService";

    /**
     * The graph's own SDL: a {@code @node} type, which is the one binding arm whose subject is the
     * graph, and a directive of the author's own, whose docstrings the same relations hold that
     * graphitron's bundled ones land in.
     */
    private static final String SDL = """
        "Restricts access to callers who hold the named role; guards access at the field level."
        directive @auth(
            "The required role name."
            role: String!
        ) on FIELD_DEFINITION

        type Query { placeholder: Int }
        type Film @table(name: "film") @node(typeId: "Film", keyColumns: ["film_id"]) { id: ID }
        """;

    /** A second graph of the same store, for the arms whose subject is that a census is per-graph. */
    private static final String OTHER_GRAPH = "elsewhere";

    @TempDir
    static Path tmp;

    private static StoreFixture store;

    /**
     * One capture for the whole class, over all three populations a hover reads: the fixture
     * module's real generated jOOQ catalog, a classpath census, and the parsed sources that carry
     * the doc comments. The catalog is the real generated model rather than a stand-in, so a table
     * name, a column's jOOQ name and its binding type are the values a consumer's editor would
     * actually be hovering.
     */
    @BeforeAll
    static void capture() {
        // The hand-built references stand in for a consumer's jar; the scanned ones are the
        // backing-class fixtures, whose member slots the store's own rule reads off a real
        // classfile's declared form rather than off a list a fixture wrote.
        store = StoreFixture.ofCatalog(tmp, SDL, Stream.concat(
            Stream.of(
                StoreFixture.jarClass(SERVICE, List.of(
                    StoreFixture.genericMethod("list", "List", "List<Film>", StoreFixture.parameter("limit", "int")),
                    StoreFixture.method("raw", "List", StoreFixture.parameter(null, "int")),
                    StoreFixture.method("page", "Object", StoreFixture.parameter("film", "Object")),
                    StoreFixture.method("page", "Object",
                        StoreFixture.parameter("film", "Object"),
                        StoreFixture.parameter("limit", "int")))),
                StoreFixture.jarClass("com.example.FooDto", List.of())),
            StoreFixture.backingClasses().stream()).toList());
        // A second graph over a schema of its own: it captured the same bundled directive
        // definitions and none of this graph's classes, which is what a sibling module looks like.
        store.andGraph(tmp, OTHER_GRAPH, "type Query { placeholder: Int }\n", List.of());
        store.withJavaSource(tmp.resolve("src"), SERVICE, """
            /** Lists films from the catalog. */
            public class FilmService {
                /** Returns the first N films. */
                public Object list(int limit) { return null; }
                /** One page of films. */
                public Object page(Object film) { return null; }
                /** One page of films, capped. */
                public Object page(Object film, int limit) { return null; }
            }
            """);
        // The generated table class, whose Javadoc is the only description the fixture database can
        // supply: it carries no comments, so the .java cadence is where a table's text comes from.
        store.withJavaSource(tmp.resolve("src"), store.tableClassFqn("film"), """
            /** Movies the rental store carries. */
            public class Film {
            }
            """);
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @Test
    void tableHoverShowsTableMetadata() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int
            }
            """);
        // Cursor inside the "film" string value.
        var pos = pointAt(file, 0, "film");

        var hover = hoverAt(file, pos).orElseThrow();

        var md = hover.getContents().getRight().getValue();
        assertThat(md).contains("**Table** `film`");
        // The generated class's Javadoc, joined by the FQN the catalog walk captured: the fixture
        // database carries no comments, so this is the only text a table has.
        assertThat(md).contains("Movies the rental store carries.");
        // Both counts are correlated subselects on the table's own row rather than a fetched list.
        assertThat(md).containsPattern("\\d+ columns, \\d+ references\\.");
        assertThat(hover.getContents().getRight().getKind()).isEqualTo(MarkupKind.MARKDOWN);
    }

    @Test
    void tableHoverWithUnknownTableReturnsEmpty() {
        var file = file("""
            type Foo @table(name: "GHOST") {
                bar: Int
            }
            """);
        var pos = pointAt(file, 0, "GHOST");

        assertThat(hoverAt(file, pos)).isEmpty();
    }

    /**
     * A table name two schemas both declare is answered for both. {@code sql_table} records every
     * table every schema declares and calls resolving an unqualified name a derivation, so the arm
     * reports rather than picks; the projection answered from whichever table its list held first.
     */
    @Test
    void aTableNameTwoSchemasDeclareHoversAsBoth(@TempDir Path directory) {
        var file = file("""
            type Foo @table(name: "event") {
                bar: Int
            }
            """);
        var pos = pointAt(file, 0, "event");

        try (var multiSchema = StoreFixture.ofMultiSchemaCatalog(directory, SDL)) {
            var md = markdownOf(multiSchema, file, pos);

            assertThat(md).contains("**Table** `event`")
                .contains("In schema `multischema_a`:")
                .contains("In schema `multischema_b`:");
        }
    }

    @Test
    void fieldHoverShowsColumnMetadata() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "title")
            }
            """);
        var pos = pointAt(file, 1, "title");

        var md = markdownAt(file, fooFilmSnapshot(), pos);

        assertThat(md).contains("**Column** `title`");
        assertThat(md).contains("on `film`");
        // Both of the column's types, because the census carries both and neither derives from the
        // other. The projection carried only the second, under a name calling it a GraphQL type.
        assertThat(md).contains("SQL type: `varchar`");
        assertThat(md).contains("Java type: `java.lang.String`");
        assertThat(md).contains("not null");
    }

    /**
     * Either of the column's two names resolves it. The census carries the SQL name and the
     * generated jOOQ name, and a directive may be written either way; the projection held only the
     * jOOQ name, so it answered a SQL spelling at all only where the two agree up to case.
     */
    @Test
    void columnHoverAnswersTheGeneratedNameToo() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "TITLE")
            }
            """);
        var pos = pointAt(file, 1, "TITLE");

        assertThat(markdownAt(file, fooFilmSnapshot(), pos))
            // The heading is the SQL name whichever spelling was typed: it is the column's
            // coordinate, and the generated name is a fact about generated code.
            .contains("**Column** `title` on `film`");
    }

    /**
     * Stale-prefers-over-silence, at the one arm that still reads the snapshot: an old classification
     * beats nothing while the author is mid-edit, so a {@code Built.Previous} snapshot resolves the
     * enclosing type's table exactly as a current one does. The directive-docstring arms used to carry
     * this case and no longer read the snapshot at all.
     */
    @Test
    void columnHoverUnderAPreviousSnapshotStillAnswers() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "title")
            }
            """);
        var pos = pointAt(file, 1, "title");
        var stale = new LspSchemaSnapshot.Built.Previous(
            List.of(),
            java.util.Map.of("Foo", new TypeBackingShape.TableBacking("film")),
            Map.of());

        assertThat(markdownAt(file, stale, pos)).contains("**Column** `title` on `film`");
    }

    /**
     * The parent's record-backing comes from the snapshot's name-keyed projection, not from any SDL
     * directive, so the member hover resolves without an applied {@code @record}. What the class
     * offers is the census's, so the rendered type is the one a compiler recorded for the component
     * rather than one this fixture chose; the permit's own slot list is empty because the arm no
     * longer reads it.
     */
    @Test
    void fieldHoverOnRecordBackingShowsComponentMetadata() {
        var file = file("""
            input FilmInput {
                bar: Int @field(name: "title")
            }
            """);
        var pos = pointAt(file, 1, "title");
        var snapshot = recordBackedFilmInput();

        var md = markdownAt(file, snapshot, pos);

        assertThat(md).contains("**title**").contains("`String`");
    }

    /**
     * Without a store the member hover renders nothing, which is the same posture every other
     * census-backed arm takes: the class's members are a fact, and a surface with no access to the
     * facts declines rather than guessing from the projection that named the class.
     */
    @Test
    void fieldHoverOnRecordBackingIsSilentWithoutAStore() {
        var file = file("""
            input FilmInput {
                bar: Int @field(name: "title")
            }
            """);
        var pos = pointAt(file, 1, "title");

        assertThat(hoverWithoutStore(file, recordBackedFilmInput(), pos)).isEmpty();
    }

    /** A type the projection binds to the fixture record, whose members the census answers for. */
    private static LspSchemaSnapshot.Built.Current recordBackedFilmInput() {
        return new LspSchemaSnapshot.Built.Current(
            List.of(),
            java.util.Map.of("FilmInput", new TypeBackingShape.RecordBacking("no.sikt.graphitron.lsp.fixtures.R157FilmRecord")),
            Map.of());
    }

    @Test
    void referenceKeyHoverShowsForeignKeyDirection() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        var pos = pointAt(file, 1, "film_language_id_fkey");

        var md = markdownAt(file, pos);

        assertThat(md).contains("**Foreign key** `film_language_id_fkey`");
        assertThat(md).contains("`film` → `language`");
        // The other namespace the value resolves in, named rather than assumed known.
        assertThat(md).contains("Also resolves under the generated constant `FILM__FILM_LANGUAGE_ID_FKEY`.");
    }

    /**
     * The generated constant is the other spelling {@code key:} resolves, and the one the projection
     * matched exclusively and case-sensitively. Both namespaces answer here, as they do in the
     * generator's own resolver.
     */
    @Test
    void referenceKeyHoverAnswersTheGeneratedConstantToo() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: "FILM__FILM_LANGUAGE_ID_FKEY"}])
            }
            """);
        var pos = pointAt(file, 1, "FILM__FILM_LANGUAGE_ID_FKEY");

        assertThat(markdownAt(file, pos)).contains("`film` → `language`");
    }

    /**
     * A constraint name two schemas both declare is answered for both, each with the schema that
     * tells them apart. A qualified spelling binds hard, as it does in the resolver: it is scoped to
     * the declaring table's schema rather than widening the set.
     */
    @Test
    void aKeyNameTwoSchemasDeclareHoversAsBoth(@TempDir Path directory) {
        var file = file("""
            type Foo @table(name: "note") {
                bar: Int @reference(path: [{key: "note_event_fk"}])
            }
            """);
        var pos = pointAt(file, 1, "note_event_fk");

        try (var multiSchema = StoreFixture.ofMultiSchemaCatalog(directory, SDL)) {
            var md = markdownOf(multiSchema, file, pos);

            assertThat(md).contains("**Foreign key** `note_event_fk`")
                .contains("(schema `multischema_a`)")
                .contains("(schema `multischema_b`)");

            var qualified = file("""
                type Foo @table(name: "note") {
                    bar: Int @reference(path: [{key: "multischema_b.note_event_fk"}])
                }
                """);
            assertThat(markdownOf(multiSchema, qualified,
                pointAt(qualified, 1, "multischema_b.note_event_fk")))
                .doesNotContain("multischema_a");
        }
    }

    @Test
    void referenceTableHoverShowsTableMetadata() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{table: "language"}])
            }
            """);
        var pos = pointAt(file, 1, "language");

        assertThat(markdownAt(file, pos)).contains("**Table** `language`");
    }

    /**
     * The docstring arms read the store like every other arm, which costs the one thing the bundled
     * registry gave for free: a session that has captured nothing has no docstring to render either.
     * The bundled definitions are rows, and that is the item's own rule rather than a regression to
     * work around inside the arm.
     */
    @Test
    void aDocstringArmAnswersNothingWithoutAStore() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int
            }
            """);
        var pos = new Point(0, "type Foo @t".length());

        assertThat(hoverWithoutStore(file, LspSchemaSnapshot.unavailable(), pos)).isEmpty();
        assertThat(markdownAt(file, pos)).isNotBlank();
    }

    @Test
    void cursorOnUnknownColumnReturnsEmpty() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "GHOST")
            }
            """);
        var pos = pointAt(file, 1, "GHOST");

        assertThat(hoverAt(file, fooFilmSnapshot(), pos)).isEmpty();
    }

    /** {@code Foo → TableBacking("film")}; matches every {@code type Foo @table(name: "film")} fixture in this file. */
    private static LspSchemaSnapshot fooFilmSnapshot() {
        return new LspSchemaSnapshot.Built.Current(
            List.of(),
            java.util.Map.of("Foo", new TypeBackingShape.TableBacking("film")),
        Map.of());
    }

    private static Point pointAt(FileSnapshot file, int line, String token) {
        String source = new String(file.source(), java.nio.charset.StandardCharsets.UTF_8);
        var lines = source.split("\n");
        int col = lines[line].indexOf(token);
        if (col < 0) {
            throw new AssertionError("token '" + token + "' not on line " + line + ": " + lines[line]);
        }
        // Land on the middle of the token so we are unambiguously inside it.
        return new Point(line, col + Math.max(1, token.length() / 2));
    }

    @Test
    void serviceClassHoverShowsClassFqn() {
        var file = file("""
            type Query {
                x: Int @service(service: {className: "com.example.FilmService", method: "list"})
            }
            """);

        assertThat(markdownAt(file, pointAt(file, 1, "FilmService")))
            .contains("**Class** `com.example.FilmService`");
    }

    @Test
    void aClassNoGraphOfThisSessionsHasWalkedHoversAsUnknown() {
        // The census is a graph's own. This graph captured the same bundled directives and none of
        // the other's classes, so the class arm declines and the coordinate's docstring answers: an
        // FQN a sibling module compiled is as unknown here as one nothing compiled at all.
        var file = file("""
            type Query {
                x: Int @service(service: {className: "com.example.FilmService", method: "list"})
            }
            """);
        var pos = pointAt(file, 1, "FilmService");

        var md = Hovers.compute(file, Optional.of(store.handleFor(OTHER_GRAPH)),
            LspSchemaSnapshot.unavailable(), pos).orElseThrow()
            .getContents().getRight().getValue();
        assertThat(md).doesNotContain("**Class**");
        assertThat(md).isNotBlank();
    }

    @Test
    void recordClassName_carveOut_noLiveBindingHover() {
        // @record is deprecated and ignored, so hovering its className shows no live-binding
        // "**Class**" hover even when the class resolves in the catalog. It falls through to the SDL
        // docstring on the shared ExternalCodeReference.className coordinate (the carve-out gates on
        // the enclosing directive name; the same coordinate under @enum/@service still hovers the class).
        var file = file("""
            input FooInput @record(record: {className: "com.example.FooDto"}) {
                bar: Int
            }
            """);
        var md = markdownAt(file, pointAt(file, 0, "FooDto"));
        assertThat(md).doesNotContain("**Class**");
        assertThat(md).isNotBlank();
    }

    @Test
    void unknownServiceClassFallsBackToSdlDocstring() {
        // Hover on a known coordinate without a richer
        // catalog match falls through to the SDL docstring on the
        // coordinate's parsed definition. ExternalCodeReference.className's
        // description in directives.graphqls describes what className means;
        // this is more useful than the previous silent-empty.
        var file = file("""
            type Query {
                x: Int @service(service: {className: "com.example.Missing", method: "list"})
            }
            """);

        // The SDL docstring on ExternalCodeReference.className is non-empty
        // and references either "klassen" (Norwegian) or className itself.
        assertThat(markdownAt(file, pointAt(file, 1, "Missing"))).isNotBlank();
    }

    @Test
    void serviceClassHoverShowsJavadocWhenPresent() {
        // The doc comment is a join to the java-source family by name: the classpath census carries
        // no Javadoc by design, and the source parse is what a hover body renders.
        var file = file("""
            type Query {
                x: Int @service(service: {className: "com.example.FilmService", method: "list"})
            }
            """);

        var md = markdownAt(file, pointAt(file, 1, "FilmService"));
        assertThat(md).contains("**Class** `com.example.FilmService`");
        assertThat(md).contains("Lists films from the catalog.");
    }

    @Test
    void serviceMethodHoverShowsJavadocWhenPresent() {
        var file = file("""
            type Query {
                x: Int @service(service: {className: "com.example.FilmService", method: "list"})
            }
            """);

        var md = markdownAt(file, pointAt(file, 1, "list"));
        assertThat(md).contains("**Method** `list`");
        assertThat(md).contains("Returns the first N films.");
        assertThat(md).contains("List<Film> list(int limit)");
    }

    /**
     * The signature is spelled the way the author wrote it. A hover that said {@code List} where the
     * source says {@code List<Film>} was showing the erasure the descriptor carries, which tells an
     * author less than the line they are hovering over; the census carries the declared form beside
     * it for exactly this.
     */
    @Test
    void methodHoverSpellsTheDeclaredReturnTypeRatherThanItsErasure() {
        var file = file("""
            type Query {
                x: Int @service(service: {className: "com.example.FilmService", method: "list"})
            }
            """);

        var md = markdownAt(file, pointAt(file, 1, "list"));
        assertThat(md).contains("List<Film> list(int limit)");
        assertThat(md).doesNotContain("List list(int limit)");
    }

    @Test
    void serviceMethodHoverShowsSignature() {
        var file = file("""
            type Query {
                x: Int @service(service: {className: "com.example.FilmService", method: "list"})
            }
            """);

        var md = markdownAt(file, pointAt(file, 1, "list"));
        assertThat(md).contains("**Method** `list`");
        assertThat(md).contains("`com.example.FilmService`");
        assertThat(md).contains("List<Film> list(int limit)");
    }

    @Test
    void everyOverloadOfTheNamedMethodIsShownWithItsOwnDoc() {
        // SDL names a method by name alone, so the hover cannot pick an overload without inventing
        // a rule. Both signatures show, in descriptor order, and each carries the doc comment its
        // own arity's declaration has: the arity is what the classfile and the source parse can be
        // joined on at all.
        var file = file("""
            type Query {
                x: Int @service(service: {className: "com.example.FilmService", method: "page"})
            }
            """);

        var md = markdownAt(file, pointAt(file, 1, "page"));
        assertThat(md).contains("Object page(Object film)");
        assertThat(md).contains("Object page(Object film, int limit)");
        assertThat(md).containsSubsequence("One page of films.", "One page of films, capped.");
    }

    @Test
    void methodHoverWithNullParameterNamesShowsArgPlaceholderAndWarning() {
        var file = file("""
            type Query {
                x: Int @service(service: {className: "com.example.FilmService", method: "raw"})
            }
            """);

        var md = markdownAt(file, pointAt(file, 1, "raw"));
        assertThat(md).contains("List raw(int arg0)");
        assertThat(md).contains("-parameters");
    }

    @Test
    void serviceMethodHoverWithUnknownMethodFallsBackToSdlDocstring() {
        // Same shape as unknownServiceClassFallsBackToSdlDocstring: the
        // method-on-class lookup misses, so hover falls through to
        // ExternalCodeReference.method's SDL docstring.
        var file = file("""
            type Query {
                x: Int @service(service: {className: "com.example.FilmService", method: "missing"})
            }
            """);

        assertThat(markdownAt(file, pointAt(file, 1, "missing"))).isNotBlank();
    }

    // ---- directives an author declared: the same relations, read the same way. ----

    /**
     * Cursor on the {@code @auth} identifier itself. Nothing about this arm knows the directive is
     * the author's rather than graphitron's: capture wrote both definitions into
     * {@code graphql_directive}, and the incumbent's bundled-versus-user fork had nothing left to
     * decide once the projection stopped being the user side's only home.
     */
    @Test
    void aDirectiveAnAuthorDeclaredHoversOnItsOwnDocstring() {
        var file = file("""
            type Query {
                customers: [String!]! @auth(role: "admin")
            }
            """);
        var pos = new Point(1, lineSource(file, 1).indexOf("@auth") + 2);

        assertThat(markdownAt(file, pos)).contains("guards access");
    }

    @Test
    void anArgumentOfADirectiveAnAuthorDeclaredHoversOnItsOwnDocstring() {
        var file = file("""
            type Query {
                customers: [String!]! @auth(role: "admin")
            }
            """);
        var pos = new Point(1, lineSource(file, 1).indexOf("role:") + 1);

        assertThat(markdownAt(file, pos)).contains("required role name");
    }

    /**
     * A bundled directive's argument name answers too, which the incumbent declined: its arg-name arm
     * was gated on the user-shaped projection, so hovering {@code typeName:} said nothing while
     * hovering the value beside it resolved the node. One relation describes both, so there is no gate
     * left to state, and the two positions now give the two different answers they should.
     */
    @Test
    void anArgumentOfABundledDirectiveHoversOnItsDocstringToo() {
        var file = file("""
            type Query {
                x(id: ID @nodeId(typeName: "Film")): Int
            }
            """);
        var pos = new Point(1, lineSource(file, 1).indexOf("typeName:") + 1);

        assertThat(markdownAt(file, pos))
            .contains("Name of the type the ID belongs to")
            .doesNotContain("**Node**");
    }

    /** A directive no capture read has no row, and absence is the answer. */
    @Test
    void aDirectiveNoCaptureReadHoversAsNothing() {
        var file = file("""
            type Query {
                customers: [String!]! @ghost(role: "admin")
            }
            """);
        var pos = new Point(1, lineSource(file, 1).indexOf("@ghost") + 2);

        assertThat(hoverAt(file, pos)).isEmpty();
    }

    /**
     * An argument name no definition declares hovers as nothing. The incumbent reached the same
     * answer by a precedence rule, refusing to let a snapshot's shadow {@code @table} describe an
     * argument the bundled definition has none of; the store has no shadow to prefer against, since a
     * redeclaration of a bundled directive loses at registry admission before capture sees it.
     */
    @Test
    void anArgNameNoDefinitionDeclaresHoversAsNothing() {
        var file = file("""
            type Foo @table(extraArg: "x", name: "film") {
                bar: Int
            }
            """);
        var pos = new Point(0, lineSource(file, 0).indexOf("extraArg:") + 1);

        assertThat(hoverAt(file, pos)).isEmpty();
    }

    @Test
    void bundledDirectiveNameHover_returnsBundledDescription() {
        // Pins the bundled side-benefit explicitly: hovering on @table's own name token surfaces the
        // captured description of the bundled definition, not the table-binding catalog content
        // (that requires the cursor on the name: arg's value).
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int
            }
            """);
        int line = 0;
        int col = lineSource(file, line).indexOf("@table") + 2;
        var pos = new Point(line, col);

        var hover = hoverAt(file, pos).orElseThrow();
        var md = hover.getContents().getRight().getValue();
        assertThat(md).isNotBlank();
        // The bundled description, not the catalog-table renderer's output.
        assertThat(md).doesNotContain("**Table** `film`");
    }

    // @node(keyColumns:) and @nodeId(typeName:) hover.

    @Test
    void nodeKeyColumnsHover_insideListElement_showsColumnMetadata() {
        // Cursor inside the second element of the list. The rangeNode
        // should be the element, not the enclosing list_value;
        // valueNodeFor descends into list_value to honour
        // "Leaf.valueNode is the scalar value node" universally.
        var file = file("""
            type Foo implements Node @table(name: "film") @node(keyColumns: ["film_id", "title"]) {
                id: ID
            }
            """);
        var pos = pointAt(file, 0, "title");

        var hover = hoverAt(file, fooFilmSnapshot(), pos).orElseThrow();
        var md = hover.getContents().getRight().getValue();

        assertThat(md).contains("**Column** `title`");
        assertThat(md).contains("on `film`");
    }

    @Test
    void nodeIdTypeNameHover_resolvesTypeIdAndKeyColumns() {
        var file = file("""
            type Query {
                x(id: ID @nodeId(typeName: "Film")): Int
            }
            """);
        var pos = pointAt(file, 1, "Film");

        var hover = hoverAt(file, pos).orElseThrow();
        var md = hover.getContents().getRight().getValue();

        assertThat(md).contains("**Node** `Film`");
        assertThat(md).contains("TypeId: `Film`");
        // The key column, typed from the node type's own table. The projection looked a key column
        // up across every table in the catalog and took the first hit, which on a name as common as
        // "id" answered from whichever table came first.
        assertThat(md).contains("- `film_id` (`java.lang.Integer`)");
    }

    /**
     * A type no {@code @node} declaration names has nothing to say about node identity, and the
     * fall-through leaves the SDL docstring to answer for the coordinate.
     */
    @Test
    void nodeIdTypeNameHover_forATypeThatIsNotANode_fallsBackToTheDocstring() {
        var file = file("""
            type Query {
                x(id: ID @nodeId(typeName: "Actor")): Int
            }
            """);
        var pos = pointAt(file, 1, "Actor");

        assertThat(markdownAt(file, pos)).doesNotContain("**Node**").isNotBlank();
    }

    private static String lineSource(FileSnapshot file, int line) {
        String source = new String(file.source(), java.nio.charset.StandardCharsets.UTF_8);
        return source.split("\n")[line];
    }


    // ===== @field(name:) on @reference path field hovers on terminal-table column =====

    @Test
    void outputTableWithReferencePathHoversOnTerminalTableColumn() {
        // The column named here lives on the path's terminal table, so that is the table the hover
        // renders it on.
        String sdl = """
            type Film @table(name: "film") {
                languageName: String @field(name: "last_update") @reference(path: [{table: "language"}])
            }
            type Query { films: [Film] }
            """;
        var file = file(sdl);
        var pos = pointAt(file, 1, "last_update");

        var md = capturedHover(sdl, file, pos).orElseThrow().getContents().getRight().getValue();

        assertThat(md).contains("**Column** `last_update`");
        assertThat(md).contains("on `language`");
    }

    @Test
    void unresolvedReferencePathHoverSilentOnLspSide() {
        // The path names a table the catalog does not have, so it reaches nothing. The hover stays
        // silent and the editor falls through to the SDL docstring, rather than printing column
        // metadata pulled off the enclosing type's own table.
        String sdl = """
            type FilmType @table(name: "film") {
                languageName: String @field(name: "last_update") @reference(path: [{table: "no_such_table"}])
            }
            type Query { films: [FilmType] }
            """;
        var file = file(sdl);
        var pos = pointAt(file, 1, "last_update");

        assertThat(capturedHover(sdl, file, pos)).isEmpty();
    }

    // ===== @field(name:) on a @table-interface participant cross-table reference =====
    //              hovers on the @reference terminal-table column, not the participant's @table

    @Test
    void participantCrossTableReferenceHoversOnTerminalTableColumn() {
        // The enclosing @table is "film" (the participant table) and the field reaches "last_update"
        // across a single-hop path. A participant is a table like any other here: the path decides,
        // so the hover renders the column on "language".
        String sdl = """
            type DokumentMelding implements Melding @table(name: "film") @discriminator(value: "DOKUMENT") {
                languageName: String @field(name: "last_update") @reference(path: [{table: "language"}])
            }
            interface Melding @table(name: "film") { languageName: String }
            type Query { meldinger: [Melding] }
            """;
        var file = file(sdl);
        var pos = pointAt(file, 1, "last_update");

        var md = capturedHover(sdl, file, pos).orElseThrow().getContents().getRight().getValue();

        assertThat(md).contains("**Column** `last_update`");
        assertThat(md).contains("on `language`");
        assertThat(md).doesNotContain("on `film`");
    }

    // ===== @defaultOrder(fields: [{name:}]) hovers on the element-table column =====

    @Test
    void defaultOrderFieldNameHoversOnElementTableColumn() {
        // The enclosing @table is "film"; the list field navigates to "language". The ordering
        // column "last_update" lives on the element table, so the hover must render it on "language",
        // not "film": the field's named type is bound to a table of its own.
        String sdl = """
            type Film @table(name: "film") {
                languages: [Language!]! @defaultOrder(fields: [{name: "last_update"}])
            }
            type Language @table(name: "language") { name: String }
            type Query { films: [Film] }
            """;
        var file = file(sdl);
        var pos = pointAt(file, 1, "last_update");

        var md = capturedHover(sdl, file, pos).orElseThrow().getContents().getRight().getValue();

        assertThat(md).contains("**Column** `last_update`");
        assertThat(md).contains("on `language`");
        assertThat(md).doesNotContain("on `film`");
    }

    private static FileSnapshot file(String source) {
        return WorkspaceFileTestSupport.snapshot(source);
    }

    /**
     * Hover with no store at all, which is what a document whose graph was never captured gets. Every
     * arm reading facts answers nothing here; what can still speak is the member-slot hover, whose
     * subject is the classification snapshot.
     */
    private static Optional<Hover> hoverWithoutStore(
        FileSnapshot file, LspSchemaSnapshot snapshot, Point pos
    ) {
        return Hovers.compute(file, Optional.empty(), snapshot, pos);
    }

    /** Hover against the captured facts, with no classification snapshot behind it. */
    private static Optional<Hover> hoverAt(FileSnapshot file, Point pos) {
        return hoverAt(file, LspSchemaSnapshot.unavailable(), pos);
    }

    /** Hover against the captured facts and a classification snapshot: the column arms need both. */
    private static Optional<Hover> hoverAt(FileSnapshot file, LspSchemaSnapshot snapshot, Point pos) {
        return Hovers.compute(file, Optional.of(store.handle()), snapshot, pos);
    }

    /**
     * Hover against a store that captured this very document. The column arms resolve a site's scope
     * from the facts of the schema the cursor is inside, so a case whose subject is that resolution
     * captures its own graph rather than sharing the class fixture's SDL.
     */
    private static Optional<Hover> capturedHover(String sdl, FileSnapshot file, Point pos) {
        try (var fixture = StoreFixture.ofCatalog(tmp, sdl)) {
            return Hovers.compute(
                file, Optional.of(fixture.handle()), LspSchemaSnapshot.unavailable(), pos);
        }
    }

    /** The markdown of a hover that must exist. */
    private static String markdownAt(FileSnapshot file, Point pos) {
        return hoverAt(file, LspSchemaSnapshot.unavailable(), pos).orElseThrow()
            .getContents().getRight().getValue();
    }

    /** The markdown of a hover that must exist, against a classification snapshot. */
    private static String markdownAt(FileSnapshot file, LspSchemaSnapshot snapshot, Point pos) {
        return hoverAt(file, snapshot, pos).orElseThrow().getContents().getRight().getValue();
    }

    /** The markdown of a hover that must exist, against a fixture other than the shared one. */
    private static String markdownOf(StoreFixture fixture, FileSnapshot file, Point pos) {
        return Hovers.compute(file, Optional.of(fixture.handle()),
            LspSchemaSnapshot.unavailable(), pos).orElseThrow()
            .getContents().getRight().getValue();
    }
}
