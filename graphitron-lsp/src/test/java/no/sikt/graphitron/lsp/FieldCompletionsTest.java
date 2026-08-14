package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.completions.CompletionContext;
import no.sikt.graphitron.lsp.completions.FieldCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for {@code @field(name: "...")} column autocomplete. Two things meet at this arm: which
 * table a site's columns come from, which is a classification question answered off the snapshot,
 * and what columns that table has, which is a read of the graph's {@code sql_column} census.
 *
 * <p>The census is the fixture module's real generated jOOQ model, captured once for the class. A
 * hand-built column list could state a table the catalog does not have, or state a jOOQ field name
 * the generator would not have produced; the point of most cases here is the dispatch, so the
 * candidate set they are checked against had better be the real one.
 */
class FieldCompletionsTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

    @TempDir
    static Path sharedDirectory;

    private static StoreFixture STORE;

    @BeforeAll
    static void captureTheCatalog() {
        STORE = StoreFixture.ofCatalog(sharedDirectory, "type Query { placeholder: Int }\n");
    }

    @AfterAll
    static void closeTheStore() {
        STORE.close();
    }

    @Test
    void columnNameCompletionReturnsTableColumns() {
        String source = """
            type Foo @table(name: "film") {
                bar: Int @field(name: "")
            }
            """;
        // Cursor inside the empty quoted argument value.
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var items = run(STORE.handle(), tableSnapshot("Foo", "film"), source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .startsWith("FILM_ID", "TITLE", "DESCRIPTION")
            .contains("LANGUAGE_ID");
    }

    @Test
    void cursorOnFieldDirectiveWithoutEnclosingTableReturnsEmpty() {
        // Type has no @table directive, so the classifier projected
        // NoBacking; completions silence.
        String source = """
            type Foo {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var snapshot = new LspSchemaSnapshot.Built.Current(
            List.of(),
            Map.of("Foo", new TypeBackingShape.NoBacking.UnbackedResult()),
        Map.of());
        var items = run(STORE.handle(), snapshot, source, cursor);

        assertThat(items).isEmpty();
    }

    @Test
    void unknownTableReturnsEmpty() {
        // Enclosing type points at a table the catalog does not know — but the classifier still
        // projected TableBacking(MISSING). No census row matches the name, so no candidates
        // surface; an empty answer here is the store agreeing there is nothing to say.
        String source = """
            type Foo @table(name: "MISSING") {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var items = run(STORE.handle(), tableSnapshot("Foo", "MISSING"), source, cursor);

        assertThat(items).isEmpty();
    }

    @Test
    void cursorOutsideNameArgReturnsEmpty() {
        String source = """
            type Foo @table(name: "film") {
                bar: Int @field(name: "title")
            }
            """;
        // Cursor on the @field directive name, not inside the argument.
        int line = 1;
        int col = source.split("\n")[line].indexOf("@field") + 1;
        Point cursor = new Point(line, col);

        var items = run(STORE.handle(), tableSnapshot("Foo", "film"), source, cursor);

        assertThat(items).isEmpty();
    }

    @Test
    void interfaceTypeWithTableDirectiveAlsoResolvesColumns() {
        // @table on an interface — TableInterfaceType projects to
        // TableBacking, same data path as TableType.
        String source = """
            interface Movie @table(name: "film") {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var items = run(STORE.handle(), tableSnapshot("Movie", "film"), source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .contains("FILM_ID", "TITLE");
    }

    /**
     * {@code @node(keyColumns:)} reuses
     * {@link no.sikt.graphitron.lsp.parsing.Behavior.CatalogColumnBinding}
     * via an overlay-delta entry. The candidate set is the columns of the
     * enclosing type's {@code @table}; cursor inside a list-element string
     * literal completes the same way a flat string-valued column slot does.
     */
    @Test
    void nodeKeyColumnsCompletionInsideListLiteralReturnsTableColumns() {
        String source = """
            type Foo implements Node @table(name: "film") @node(keyColumns: [""]) {
                id: ID
            }
            """;
        // Cursor inside the empty quoted element of the list.
        int line = 0;
        int col = source.split("\n")[line].indexOf("[\"") + 2;
        Point cursor = new Point(line, col);

        var items = run(STORE.handle(), tableSnapshot("Foo", "film"), source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .startsWith("FILM_ID", "TITLE", "DESCRIPTION")
            .contains("LANGUAGE_ID");
    }

    @Test
    void recordBackingCompletionReturnsRecordComponents() {
        // The parent's record-backing comes from the snapshot's name-keyed projection (below), not
        // from any SDL directive, so member completion resolves without an applied @record.
        String source = """
            input FilmInput {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var snapshot = new LspSchemaSnapshot.Built.Current(
            List.of(),
            Map.of("FilmInput", new TypeBackingShape.RecordBacking("com.example.FilmDto", List.of(
                new TypeBackingShape.MemberSlot("filmId", "Integer", "filmId"),
                new TypeBackingShape.MemberSlot("title", "String", "title")
            ))),
        Map.of());
        var items = run(STORE.handle(), snapshot, source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactly("filmId", "title");
    }

    @Test
    void pojoBackingCompletionReturnsBeanAccessors() {
        String source = """
            type FilmPojo {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var snapshot = new LspSchemaSnapshot.Built.Current(
            List.of(),
            Map.of("FilmPojo", new TypeBackingShape.PojoBacking("com.example.FilmPojo", List.of(
                new TypeBackingShape.MemberSlot("filmId", "Integer", "getFilmId"),
                new TypeBackingShape.MemberSlot("title", "String", "getTitle")
            ))),
        Map.of());
        var items = run(STORE.handle(), snapshot, source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactly("filmId", "title");
    }

    @Test
    void snapshotMissReturnsEmpty() {
        // SDL declares the type but the snapshot has no entry — same as
        // mid-edit state. Silent rather than spamming candidates.
        String source = """
            type Foo @table(name: "film") {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var snapshot = new LspSchemaSnapshot.Built.Current(List.of(), Map.of(), Map.of());
        var items = run(STORE.handle(), snapshot, source, cursor);

        assertThat(items).isEmpty();
    }

    @Test
    void unavailableSnapshotReturnsEmpty() {
        // Pre-build state — no classifier output to consult yet.
        String source = """
            type Foo @table(name: "film") {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var items = run(STORE.handle(), LspSchemaSnapshot.unavailable(), source, cursor);

        assertThat(items).isEmpty();
    }

    // ===== $source sigil completion =====

    @Test
    void sourceSigil_atCarrierDataField_isSuggested() {
        // Carrier projection declares FilmListPayload.films as the carrier data field; the
        // parent's TypeBackingShape is NoBacking.UnbackedResult (the promoted-Pojo carrier
        // shape). $source ships as the only completion (no column / accessor list applies on
        // NoBacking).
        String source = """
            type FilmListPayload {
                films: [Film!] @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var snapshot = new LspSchemaSnapshot.Built.Current(
            List.of(),
            Map.of("FilmListPayload", new TypeBackingShape.NoBacking.UnbackedResult()),
            Map.of("FilmListPayload", "films")
        );
        var items = run(STORE.handle(), snapshot, source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactly(no.sikt.graphitron.rewrite.FieldSourceSigil.UPSTREAM_ROOT_LITERAL);
    }

    @Test
    void sourceSigil_atNonCarrierSite_isNotSuggested() {
        // Same SDL shape (a NoBacking.UnbackedResult parent), but no entry in the carrier
        // projection — $source is NOT suggested. The LSP's narrow predicate matches the
        // build's narrow predicate.
        String source = """
            type FilmListPayload {
                films: [Film!] @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var snapshot = new LspSchemaSnapshot.Built.Current(
            List.of(),
            Map.of("FilmListPayload", new TypeBackingShape.NoBacking.UnbackedResult()),
            Map.of()
        );
        var items = run(STORE.handle(), snapshot, source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .doesNotContain(no.sikt.graphitron.rewrite.FieldSourceSigil.UPSTREAM_ROOT_LITERAL);
    }

    @Test
    void sourceSigil_snapshotUncertainty_silent() {
        // Parent type has no entry in typesByName AND no entry in carrierDataFieldByType:
        // the snapshot's view is "shape unknown" (mid-edit / not-yet-classified rename).
        // The LSP arm is silent on both axes — no completion, no diagnostic. Mirrors the
        // existing snapshot-uncertainty behaviour for the column/accessor arms.
        String source = """
            type RenamedMidEdit {
                films: [Film!] @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var snapshot = new LspSchemaSnapshot.Built.Current(List.of(), Map.of(), Map.of());
        var items = run(STORE.handle(), snapshot, source, cursor);

        assertThat(items).isEmpty();
    }

    // ===== @field(name:) on @reference path field completes terminal-table columns =====

    @Test
    void outputTableWithReferencePathCompletesTerminalTableColumns() {
        // The path's terminal table is where the named column lives, so the dropdown offers its
        // columns and not the enclosing @table's.
        String source = """
            type Film @table(name: "film") {
                languageName: String @field(name: "") @reference(path: [{table: "language"}])
            }
            type Query { films: [Film] }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf("@field(name: \"") + "@field(name: \"".length();
        Point cursor = new Point(line, col);

        var items = runCaptured(source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactly("LANGUAGE_ID", "NAME", "LAST_UPDATE")
            .doesNotContain("FILM_ID", "TITLE");
    }

    @Test
    void unresolvedReferencePathCompletionSilentOnLspSide() {
        // The path names a table the catalog does not have, so it reaches nothing. Suggestions from
        // the enclosing type's own table would point the author at the wrong end of the join they
        // are still writing, so the arm offers nothing at all.
        String source = """
            type FilmType @table(name: "film") {
                languageName: String @field(name: "") @reference(path: [{table: "no_such_table"}])
            }
            type Query { films: [FilmType] }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf("@field(name: \"") + "@field(name: \"".length();
        Point cursor = new Point(line, col);

        var items = runCaptured(source, cursor);

        assertThat(items).isEmpty();
    }

    // ===== @field(name:) on a @table-interface participant cross-table reference =====
    //              completes the @reference terminal-table columns, not the participant's @table

    @Test
    void participantCrossTableReferenceCompletesTerminalTableColumns() {
        // The enclosing @table is "film" (the participant table) and the field reaches "language"
        // across a single-hop path. A participant is a table like any other here: the path decides,
        // so the dropdown lists LANGUAGE's columns rather than FILM's.
        String source = """
            type DokumentMelding implements Melding @table(name: "film") @discriminator(value: "DOKUMENT") {
                languageName: String @field(name: "") @reference(path: [{table: "language"}])
            }
            interface Melding @table(name: "film") { languageName: String }
            type Query { meldinger: [Melding] }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf("@field(name: \"") + "@field(name: \"".length();
        Point cursor = new Point(line, col);

        var items = runCaptured(source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactly("LANGUAGE_ID", "NAME", "LAST_UPDATE")
            .doesNotContain("FILM_ID", "TITLE");
    }

    // ===== @defaultOrder(fields: [{name:}]) completes the element-table columns =====
    //              (the list/connection field's target table), not the enclosing type's @table.

    @Test
    void defaultOrderFieldsCompletesElementTableColumns() {
        // The enclosing @table is "film"; the list field navigates to LANGUAGE. The ordering
        // column named in @defaultOrder lives on LANGUAGE, so the dropdown must list LANGUAGE's
        // columns, never FILM's: the field's named type is bound to a table of its own.
        String source = """
            type Film @table(name: "film") {
                languages: [Language!]! @defaultOrder(fields: [{name: ""}])
            }
            type Language @table(name: "language") { name: String }
            type Query { films: [Film] }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf("{name: \"") + "{name: \"".length();
        Point cursor = new Point(line, col);

        var items = runCaptured(source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactly("LANGUAGE_ID", "NAME", "LAST_UPDATE")
            .doesNotContain("FILM_ID", "TITLE");
    }

    @Test
    void defaultOrderFieldsOnConnectionCompletesElementTableColumns() {
        // @asConnection does not change the element table; the cursor walk still keys the
        // @defaultOrder(fields: [{name:}]) site to FieldSort.name through the stacked directives.
        String source = """
            type Film @table(name: "film") {
                languages: [Language!]! @asConnection @defaultOrder(fields: [{name: ""}])
            }
            type Language @table(name: "language") { name: String }
            type Query { films: [Film] }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf("{name: \"") + "{name: \"".length();
        Point cursor = new Point(line, col);

        var items = runCaptured(source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactly("LANGUAGE_ID", "NAME", "LAST_UPDATE")
            .doesNotContain("FILM_ID", "TITLE");
    }

    @Test
    void defaultOrderFieldsOnReferenceSplitQueryCompletesElementTableColumns() {
        // The motivating shape: a @reference + @splitQuery list field. The authored path's terminal
        // element is LANGUAGE and so is the named type's own table, so both rules agree; the extra
        // directives must not derail the cursor walk to FieldSort.name.
        String source = """
            type Film @table(name: "film") {
                languages: [Language!]! @reference(path: [{table: "language"}]) @splitQuery @defaultOrder(fields: [{name: ""}])
            }
            type Language @table(name: "language") { name: String }
            type Query { films: [Film] }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf("@defaultOrder(fields: [{name: \"")
            + "@defaultOrder(fields: [{name: \"".length();
        Point cursor = new Point(line, col);

        var items = runCaptured(source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactly("LANGUAGE_ID", "NAME", "LAST_UPDATE")
            .doesNotContain("FILM_ID", "TITLE");
    }

    @Test
    void defaultOrderPrimaryKeySiteOffersNoColumns() {
        // Negative: @defaultOrder(primaryKey: true) has no fields object and no name coordinate,
        // so FieldSort.name is never reached; the boolean-arg site must not leak column candidates.
        String source = """
            type Film @table(name: "film") {
                languages: [Language!]! @defaultOrder(primaryKey: )
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf("primaryKey: ") + "primaryKey: ".length();
        Point cursor = new Point(line, col);

        var items = run(STORE.handle(), emptySnapshot(), source, cursor);

        assertThat(items).isEmpty();
    }

    /**
     * The name a directive spells and the name the database declares need not agree on case, so the
     * census is matched case-insensitively, as the incumbent projection's lookup was.
     */
    @Test
    void theTableNameMatchesCaseInsensitively() {
        String source = """
            type Foo @table(name: "FILM") {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        Point cursor = new Point(line, source.split("\n")[line].indexOf('"') + 1);

        var items = run(STORE.handle(), tableSnapshot("Foo", "FILM"), source, cursor);

        assertThat(items).extracting(CompletionItem::getLabel).contains("FILM_ID", "TITLE");
    }

    /**
     * The detail line is the Java type jOOQ binds the column to, which is the fact hover wanted and
     * the projection dropped, plus the column's nullability. Both are read off the census rather
     * than off any live handle, which is what makes them available at all outside a codegen scope.
     */
    @Test
    void theDetailIsTheBindingTypeAndNullability() {
        String source = """
            type Foo @table(name: "film") {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        Point cursor = new Point(line, source.split("\n")[line].indexOf('"') + 1);

        var items = run(STORE.handle(), tableSnapshot("Foo", "film"), source, cursor);

        assertThat(detailOf(items, "FILM_ID")).isEqualTo("java.lang.Integer");
        assertThat(detailOf(items, "DESCRIPTION")).isEqualTo("java.lang.String (nullable)");
    }

    /**
     * The generated field's Javadoc documents the candidate, joined by the table class FQN the
     * catalog walk captured. This is the arm the FQN capture exists for: the generated package is
     * outside the class census by design, so nothing else in the store reaches these declarations.
     *
     * <p>Its own store, because it parses sources into the family the other cases leave empty.
     */
    @Test
    void theGeneratedFieldJavadocDocumentsTheColumn(@TempDir Path tmp) {
        String source = """
            type Foo @table(name: "film") {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        Point cursor = new Point(line, source.split("\n")[line].indexOf('"') + 1);

        try (var fixture = StoreFixture.ofCatalog(tmp, "type Query { placeholder: Int }\n")) {
            assertThat(documentationOf(
                run(fixture.handle(), tableSnapshot("Foo", "film"), source, cursor), "FILM_ID"))
                .as("no source parsed yet, and the fixture database carries no column comments")
                .isEmpty();

            fixture.withJavaSource(tmp.resolve("generated"), fixture.tableClassFqn("film"), """
                public class Film {
                    /** The column <code>public.film.film_id</code>. */
                    public final Object FILM_ID = null;
                }
                """);

            assertThat(documentationOf(
                run(fixture.handle(), tableSnapshot("Foo", "film"), source, cursor), "FILM_ID"))
                .isEqualTo("The column <code>public.film.film_id</code>.");
        }
    }

    private static String detailOf(List<CompletionItem> items, String label) {
        return itemNamed(items, label).getDetail();
    }

    private static String documentationOf(List<CompletionItem> items, String label) {
        var documentation = itemNamed(items, label).getDocumentation();
        return documentation == null ? "" : documentation.getRight().getValue();
    }

    private static CompletionItem itemNamed(List<CompletionItem> items, String label) {
        return items.stream().filter(i -> label.equals(i.getLabel())).findFirst()
            .orElseThrow(() -> new AssertionError("no candidate labelled " + label));
    }

    /**
     * Runs the arm against a store that captured this very document. The cases that read a site's
     * resolved scope need the facts of the schema they are completing inside, so each captures its
     * own graph rather than sharing the class fixture's placeholder.
     */
    private List<CompletionItem> runCaptured(String source, Point cursor) {
        try (var store = StoreFixture.ofCatalog(sharedDirectory, source)) {
            return run(store.handle(), emptySnapshot(), source, cursor);
        }
    }

    /** No projection at all: the cases using it resolve their scope from the store. */
    private static LspSchemaSnapshot emptySnapshot() {
        return new LspSchemaSnapshot.Built.Current(List.of(), Map.of(), Map.of());
    }

    private static LspSchemaSnapshot tableSnapshot(String typeName, String tableName) {
        return new LspSchemaSnapshot.Built.Current(
            List.of(),
            Map.of(typeName, new TypeBackingShape.TableBacking(tableName)),
        Map.of());
    }

    private static List<CompletionItem> run(
        StoreHandle store, LspSchemaSnapshot snapshot, String source, Point cursor
    ) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor"));
        var locOpt = VOCAB.locateAt(directive, cursor, bytes);
        if (locOpt.isEmpty()) return List.of();
        var context = CompletionContext.from(locOpt.get(), bytes);
        return FieldCompletions.generate(VOCAB, store, snapshot, context, directive, bytes);
    }

}
