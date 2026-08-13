package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.hover.Hovers;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.DirectiveShape;
import no.sikt.graphitron.rewrite.catalog.InputValueShape;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;
import no.sikt.graphitron.rewrite.catalog.TypeShape;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-directive hover content. Cursor inside a known argument value
 * surfaces catalog metadata as Markdown; positions on directive names
 * or unknown arg values produce no hover so the editor falls through.
 */
class HoversTest {

    /** The class the Java-side arms hover on, present in the census and declared in a source file. */
    private static final String SERVICE = "com.example.FilmService";

    @TempDir
    static Path tmp;

    private static StoreFixture store;

    /**
     * One captured census for every case whose subject is a class or one of its methods, plus the
     * source file that declares them. Both halves matter and they are two populations: the classpath
     * scan is what makes a name resolvable at all, and the parse is where a doc comment comes from,
     * joined by name across two cadences.
     */
    @BeforeAll
    static void capture() {
        store = StoreFixture.ofClasspath(tmp, List.of(
            StoreFixture.jarClass(SERVICE, List.of(
                StoreFixture.method("list", "List", StoreFixture.parameter("limit", "int")),
                StoreFixture.method("raw", "List", StoreFixture.parameter(null, "int")),
                StoreFixture.method("page", "Object", StoreFixture.parameter("film", "Object")),
                StoreFixture.method("page", "Object",
                    StoreFixture.parameter("film", "Object"), StoreFixture.parameter("limit", "int")))),
            StoreFixture.jarClass("com.example.FooDto", List.of())));
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

        var hover = hoverWithoutStore(file, filmCatalog(), LspSchemaSnapshot.unavailable(), pos).orElseThrow();

        var md = hover.getContents().getRight().getValue();
        assertThat(md).contains("**Table** `film`");
        assertThat(md).contains("Movies the rental store carries");
        assertThat(md).contains("2 columns");
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

        assertThat(hoverWithoutStore(file, filmCatalog(), LspSchemaSnapshot.unavailable(), pos)).isEmpty();
    }

    @Test
    void fieldHoverShowsColumnMetadata() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "title")
            }
            """);
        var pos = pointAt(file, 1, "title");

        var hover = hoverWithoutStore(file, filmCatalog(), fooFilmSnapshot(), pos).orElseThrow();
        var md = hover.getContents().getRight().getValue();

        assertThat(md).contains("**Column** `title`");
        assertThat(md).contains("on `film`");
        assertThat(md).contains("`String`");
        assertThat(md).contains("not null");
    }

    @Test
    void fieldHoverOnRecordBackingShowsComponentMetadata() {
        // The parent's record-backing comes from the snapshot's name-keyed projection (below), not
        // from any SDL directive, so the member hover resolves without an applied @record.
        var file = file("""
            input FilmInput {
                bar: Int @field(name: "title")
            }
            """);
        var pos = pointAt(file, 1, "title");

        var snapshot = new LspSchemaSnapshot.Built.Current(
            List.of(),
            java.util.Map.of("FilmInput", new TypeBackingShape.RecordBacking(
                "com.example.FilmDto",
                List.of(new TypeBackingShape.MemberSlot("title", "String", "title"))
            )),
        Map.of());
        var hover = hoverWithoutStore(file, filmCatalog(), snapshot, pos).orElseThrow();
        var md = hover.getContents().getRight().getValue();
        assertThat(md).contains("**title**").contains("`String`");
    }

    @Test
    void referenceKeyHoverShowsForeignKeyDirection() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: "FILM__FILM_LANGUAGE_ID_FKEY"}])
            }
            """);
        var pos = pointAt(file, 1, "FILM__FILM_LANGUAGE_ID_FKEY");

        var hover = hoverWithoutStore(file, filmCatalog(), LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        var md = hover.getContents().getRight().getValue();

        assertThat(md).contains("**Foreign key** `FILM__FILM_LANGUAGE_ID_FKEY`");
        assertThat(md).contains("`film` → `language`");
    }

    @Test
    void referenceTableHoverShowsTableMetadata() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{table: "language"}])
            }
            """);
        var pos = pointAt(file, 1, "language");

        var hover = hoverWithoutStore(file, filmCatalog(), LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        var md = hover.getContents().getRight().getValue();

        assertThat(md).contains("**Table** `language`");
    }

    @Test
    void cursorOnBundledDirectiveNameSurfacesDocstring() {
        // Cursor on a bundled directive's name token (the
        // @table identifier itself, not its arguments) surfaces the
        // directive's SDL docstring. The bundled SDL ships descriptions
        // on every directive, so the hover now lights up free for all
        // seventeen built-in directives.
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int
            }
            """);
        int line = 0;
        int col = "type Foo @t".length();
        var pos = new Point(line, col);

        var hover = hoverWithoutStore(file, filmCatalog(), LspSchemaSnapshot.unavailable(), pos)
            .orElseThrow();
        assertThat(hover.getContents().getRight().getValue()).isNotBlank();
    }

    @Test
    void cursorOnUnknownColumnReturnsEmpty() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "GHOST")
            }
            """);
        var pos = pointAt(file, 1, "GHOST");

        assertThat(hoverWithoutStore(file, filmCatalog(), fooFilmSnapshot(), pos)).isEmpty();
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
        // The census is a graph's own. A second graph in the same store carries the class; hovering
        // through this one falls through to the SDL docstring, the same answer an author gets before
        // anything has been compiled.
        var file = file("""
            type Query {
                x: Int @service(service: {className: "com.example.FilmService", method: "list"})
            }
            """);
        var pos = pointAt(file, 1, "FilmService");

        var md = Hovers.compute(file, emptyCatalog(), Optional.of(store.handleFor("elsewhere")),
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
        assertThat(md).contains("List list(int limit)");
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
        assertThat(md).contains("List list(int limit)");
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

    // ---- user-declared directives via the snapshot. ----

    @Test
    void userDeclaredDirectiveNameHover_returnsSnapshotDescription() {
        // Cursor on the @auth identifier itself. The bundled overlay has no
        // @auth, so resolution falls through to the snapshot's directive
        // shape and the directive's description renders as the hover body.
        var snapshot = new LspSchemaSnapshot.Built.Current(List.of(authShape()), Map.of(), Map.of());
        var file = file("""
            type Query {
                customers: [String!]! @auth(role: "admin")
            }
            """);
        int line = 1;
        int col = lineSource(file, line).indexOf("@auth") + 2;
        var pos = new Point(line, col);

        var hover = hoverWithoutStore(file, emptyCatalog(), snapshot, pos).orElseThrow();
        assertThat(hover.getContents().getRight().getValue())
            .contains("guards access");
    }

    @Test
    void userDeclaredDirectiveArgHover_returnsSnapshotArgDescription() {
        // Cursor on the `role:` arg-name token of a user-declared directive.
        // Bundled has no coordinate for @auth's role; falls through to the
        // user snapshot's InputValueShape description.
        var snapshot = new LspSchemaSnapshot.Built.Current(List.of(authShape()), Map.of(), Map.of());
        var file = file("""
            type Query {
                customers: [String!]! @auth(role: "admin")
            }
            """);
        int line = 1;
        int col = lineSource(file, line).indexOf("role:") + 1;
        var pos = new Point(line, col);

        var hover = hoverWithoutStore(file, emptyCatalog(), snapshot, pos).orElseThrow();
        assertThat(hover.getContents().getRight().getValue())
            .contains("required role name");
    }

    @Test
    void userDirectiveHoverUnderUnavailableSnapshot_returnsEmpty() {
        // Pre-build state. No snapshot to consult, so the user directive
        // name resolves to Unknown and the hover surface is empty.
        var file = file("""
            type Query {
                customers: [String!]! @auth(role: "admin")
            }
            """);
        int line = 1;
        int col = lineSource(file, line).indexOf("@auth") + 2;
        var pos = new Point(line, col);

        assertThat(hoverWithoutStore(file, emptyCatalog(), LspSchemaSnapshot.unavailable(), pos))
            .isEmpty();
    }

    @Test
    void userDirectiveHoverUnderPreviousSnapshot_stillReturnsContent() {
        // Stale-prefers-over-silence: hovers fire even on Built.Previous,
        // since an old description beats nothing while the user is mid-edit.
        var snapshot = new LspSchemaSnapshot.Built.Previous(List.of(authShape()), Map.of(), Map.of());
        var file = file("""
            type Query {
                customers: [String!]! @auth(role: "admin")
            }
            """);
        int line = 1;
        int col = lineSource(file, line).indexOf("@auth") + 2;
        var pos = new Point(line, col);

        var hover = hoverWithoutStore(file, emptyCatalog(), snapshot, pos).orElseThrow();
        assertThat(hover.getContents().getRight().getValue())
            .contains("guards access");
    }

    @Test
    void bundledDirectiveArgHover_ignoresSnapshotShadow() {
        // Settled design: bundled shadows snapshot. Cursor on
        // an arg-name that lives only in the snapshot's shadow @table
        // (not in the bundled @table) must NOT surface the shadow's arg
        // description — doing so would make the LSP appear to "know" an
        // arg that the build pipeline will reject. Hover stays empty;
        // the snapshot-driven arg-typo diagnostic on the Diagnostics side
        // already covers user feedback.
        var shadow = new DirectiveShape(
            "table",
            List.of(new InputValueShape(
                "extraArg",
                new TypeShape.Named("String", false),
                java.util.Optional.of("shadow description — must not leak through to hover."))),
            java.util.Optional.empty());
        var file = file("""
            type Foo @table(extraArg: "x", name: "film") {
                bar: Int
            }
            """);
        int line = 0;
        int col = lineSource(file, line).indexOf("extraArg:") + 1;
        var pos = new Point(line, col);

        assertThat(hoverWithoutStore(file, filmCatalog(),
            new LspSchemaSnapshot.Built.Current(List.of(shadow), Map.of(), Map.of()), pos))
            .isEmpty();
    }

    @Test
    void bundledDirectiveNameHover_returnsBundledDescription() {
        // Pins the bundled side-benefit explicitly: hovering on @table's
        // own name token surfaces directives.graphqls's description for
        // the directive, not the table-binding catalog content (that
        // requires the cursor on the name: arg's value).
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int
            }
            """);
        int line = 0;
        int col = lineSource(file, line).indexOf("@table") + 2;
        var pos = new Point(line, col);

        var hover = hoverWithoutStore(file, filmCatalog(), LspSchemaSnapshot.unavailable(), pos)
            .orElseThrow();
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

        var hover = hoverWithoutStore(file, filmCatalog(), fooFilmSnapshot(), pos).orElseThrow();
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

        var hover = hoverWithoutStore(file, nodeCatalog(), LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        var md = hover.getContents().getRight().getValue();

        assertThat(md).contains("**Node** `Film`");
        assertThat(md).contains("TypeId: `Film`");
        assertThat(md).contains("`film_id`");
    }

    private static CompletionData nodeCatalog() {
        var film = new CompletionData.Table(
            "film", "Movies",
            null,
            List.of(CompletionData.Column.of("film_id", "Integer", false, "")),
            List.of()
        );
        return new CompletionData(
            List.of(film),
            List.of(),
            List.of(),
            java.util.Map.of("Film", new CompletionData.NodeMetadata("Film", List.of("film_id")))
        );
    }

    private static String lineSource(FileSnapshot file, int line) {
        String source = new String(file.source(), java.nio.charset.StandardCharsets.UTF_8);
        return source.split("\n")[line];
    }

    private static DirectiveShape authShape() {
        return new DirectiveShape(
            "auth",
            List.of(new InputValueShape(
                "role",
                new TypeShape.Named("String", true),
                java.util.Optional.of("The required role name."))),
            java.util.Optional.of("Restricts access to callers who hold the named role; guards access at the field level.")
        );
    }

    private static CompletionData emptyCatalog() {
        return new CompletionData(List.of(), List.of(), List.of());
    }

    // ===== @field(name:) on @reference path field hovers on terminal-table column =====

    @Test
    void outputTableWithReferencePathHoversOnTerminalTableColumn() {
        // Output-side mirror.
        var file = file("""
            type Film @table(name: "film") {
                languageName: String @field(name: "lang_name") @reference(path: [{table: "language"}])
            }
            """);
        var pos = pointAt(file, 1, "lang_name");

        var snapshot = new LspSchemaSnapshot.Built.Current(
            List.of(),
            java.util.Map.of("Film", new TypeBackingShape.TableBacking("film")),
            java.util.Map.of(),
            java.util.Map.of("Film.languageName",
                new no.sikt.graphitron.rewrite.catalog.FieldClassification.ColumnReference(
                    "language", "lang_name", List.of())),
            java.util.Map.of()
        );
        var hover = hoverWithoutStore(file, filmAndLanguageCatalogWithLanguageName(), snapshot, pos).orElseThrow();
        var md = hover.getContents().getRight().getValue();

        assertThat(md).contains("**Column** `lang_name`");
        assertThat(md).contains("on `language`");
    }

    @Test
    void unresolvedReferencePathHoverSilentOnLspSide() {
        // Classifier could not assign a variant (Unclassified). The hover must be silent so the
        // editor falls through to the SDL docstring rather than printing column metadata pulled
        // from the wrong table.
        var file = file("""
            type FilmType @table(name: "film") {
                languageName: String @field(name: "lang_name") @reference(path: [{table: "language"}])
            }
            """);
        var pos = pointAt(file, 1, "lang_name");

        var snapshot = new LspSchemaSnapshot.Built.Current(
            List.of(),
            java.util.Map.of("FilmType", new TypeBackingShape.TableBacking("film")),
            java.util.Map.of(),
            java.util.Map.of("FilmType.languageName",
                new no.sikt.graphitron.rewrite.catalog.FieldClassification.Unresolvable("synthetic test reason")),
            java.util.Map.of()
        );

        assertThat(hoverWithoutStore(file, filmAndLanguageCatalogWithLanguageName(), snapshot, pos)).isEmpty();
    }

    // ===== @field(name:) on a @table-interface participant cross-table reference =====
    //              hovers on the @reference terminal-table column, not the participant's @table

    @Test
    void participantCrossTableReferenceHoversOnTerminalTableColumn() {
        // The enclosing @table is "film" (the participant table); the field reaches "lang_name"
        // on the terminal table "language" via a ParticipantCrossTable classification. Previously
        // the hover dispatched on the enclosing backing and rendered the wrong table; routing
        // ParticipantCrossTable through lspColumnDispatch() hovers the terminal-table column.
        var file = file("""
            type DokumentMelding implements Melding @table(name: "film") @discriminator(value: "DOKUMENT") {
                languageName: String @field(name: "lang_name") @reference(path: [{table: "language"}])
            }
            """);
        var pos = pointAt(file, 1, "lang_name");

        var snapshot = new LspSchemaSnapshot.Built.Current(
            List.of(),
            java.util.Map.of("DokumentMelding", new TypeBackingShape.TableBacking("film")),
            java.util.Map.of(),
            java.util.Map.of("DokumentMelding.languageName",
                new no.sikt.graphitron.rewrite.catalog.FieldClassification.ParticipantCrossTable(
                    "language", "lang_name", "DOKUMENT_MELDING__DOKUMENT_MELDING_BASE_FK", "soknad")),
            java.util.Map.of()
        );
        var hover = hoverWithoutStore(file, filmAndLanguageCatalogWithLanguageName(), snapshot, pos).orElseThrow();
        var md = hover.getContents().getRight().getValue();

        assertThat(md).contains("**Column** `lang_name`");
        assertThat(md).contains("on `language`");
        assertThat(md).doesNotContain("on `film`");
    }

    // ===== @defaultOrder(fields: [{name:}]) hovers on the element-table column =====

    @Test
    void defaultOrderFieldNameHoversOnElementTableColumn() {
        // The enclosing @table is "film"; the list field navigates to "language". The ordering
        // column "lang_name" lives on the element table, so the hover must render it on "language",
        // not "film". TableTarget.lspColumnDispatch() Resolves the element table for the hover too.
        var file = file("""
            type Film @table(name: "film") {
                languages: [Language!]! @defaultOrder(fields: [{name: "lang_name"}])
            }
            """);
        var pos = pointAt(file, 1, "lang_name");

        var snapshot = new LspSchemaSnapshot.Built.Current(
            List.of(),
            java.util.Map.of("Film", new TypeBackingShape.TableBacking("film")),
            java.util.Map.of(),
            java.util.Map.of("Film.languages",
                new no.sikt.graphitron.rewrite.catalog.FieldClassification.TableTarget(
                    "language", List.of(), false, false)),
            java.util.Map.of()
        );
        var hover = hoverWithoutStore(file, filmAndLanguageCatalogWithLanguageName(), snapshot, pos).orElseThrow();
        var md = hover.getContents().getRight().getValue();

        assertThat(md).contains("**Column** `lang_name`");
        assertThat(md).contains("on `language`");
        assertThat(md).doesNotContain("on `film`");
    }

    private static CompletionData filmAndLanguageCatalogWithLanguageName() {
        var film = new CompletionData.Table(
            "film", "Movies",
            null,
            List.of(
                CompletionData.Column.of("film_id", "Integer", false, ""),
                CompletionData.Column.of("title", "String", false, "")
            ),
            List.of(CompletionData.Reference.of("language", "FILM__FILM_LANGUAGE_ID_FKEY", false))
        );
        var language = new CompletionData.Table(
            "language", "Languages",
            null,
            List.of(
                CompletionData.Column.of("language_id", "Integer", false, ""),
                CompletionData.Column.of("lang_name", "String", false, "")
            ),
            List.of()
        );
        return new CompletionData(List.of(film, language), List.of(), List.of());
    }

    private static FileSnapshot file(String source) {
        return WorkspaceFileTestSupport.snapshot(source);
    }

    /**
     * Hover with no store, which is the shape for every arm that still reads the projection. The two
     * Java-side arms read facts and answer nothing here; the cases that exercise them go through
     * {@link #hoverAt} and the captured census instead.
     */
    private static Optional<Hover> hoverWithoutStore(
        FileSnapshot file, CompletionData catalog, LspSchemaSnapshot snapshot, Point pos
    ) {
        return Hovers.compute(file, catalog, Optional.empty(), snapshot, pos);
    }

    /** Hover against the captured census: the shape for the class-name and method arms. */
    private static Optional<Hover> hoverAt(FileSnapshot file, Point pos) {
        return Hovers.compute(file, emptyCatalog(), Optional.of(store.handle()),
            LspSchemaSnapshot.unavailable(), pos);
    }

    /** The markdown of a hover that must exist. */
    private static String markdownAt(FileSnapshot file, Point pos) {
        return hoverAt(file, pos).orElseThrow().getContents().getRight().getValue();
    }

    private static CompletionData filmCatalog() {
        var film = new CompletionData.Table(
            "film",
            "Movies the rental store carries",
            null,
            List.of(
                CompletionData.Column.of("film_id", "Integer", false, ""),
                CompletionData.Column.of("title", "String", false, "")
            ),
            List.of(
                CompletionData.Reference.of("language", "FILM__FILM_LANGUAGE_ID_FKEY", false)
            )
        );
        var language = new CompletionData.Table(
            "language", "Spoken languages",
            null,
            List.of(CompletionData.Column.of("language_id", "Integer", false, "")),
            List.of()
        );
        return new CompletionData(List.of(film, language), List.of(), List.of());
    }
}
