package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.completions.CompletionContext;
import no.sikt.graphitron.lsp.completions.FieldCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
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
 * Coverage for {@code @field(name: "...")} column autocomplete. Two things meet at this arm: what the
 * site's members resolve against, which is one read of the store's own relations, and what that table
 * or class then offers, which is a read of the graph's {@code sql_column} or classpath census.
 *
 * <p>The census is the fixture module's real generated jOOQ model, captured once for the class. A
 * hand-built column list could state a table the catalog does not have, or state a jOOQ field name
 * the generator would not have produced; the point of most cases here is the dispatch, so the
 * candidate set they are checked against had better be the real one.
 */
class FieldCompletionsTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

    /** The census's producer, whose return types ground the class-backed cases' own SDL. */
    private static final String SERVICE_FIXTURE = "no.sikt.graphitron.lsp.fixtures.R157Service";

    @TempDir
    static Path sharedDirectory;

    private static StoreFixture STORE;

    @BeforeAll
    static void captureTheCatalog() {
        STORE = StoreFixture.ofCatalog(sharedDirectory, "type Query { placeholder: Int }\n",
            StoreFixture.backingClasses());
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

        var items = runCaptured(source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .startsWith("FILM_ID", "TITLE", "DESCRIPTION")
            .contains("LANGUAGE_ID");
    }

    @Test
    void cursorOnFieldDirectiveWithoutEnclosingTableReturnsEmpty() {
        // The type binds no table and no producer grounds it, so the store scopes it to neither a
        // table nor a class and the arm has nothing to offer.
        String source = """
            type Foo {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var items = runCaptured(source, cursor);

        assertThat(items).isEmpty();
    }

    @Test
    void unknownTableReturnsEmpty() {
        // The enclosing type points at a table the catalog does not know, so the binding resolves
        // to nothing and the type is scoped to nothing; an empty answer here is the store agreeing
        // there is nothing to say.
        String source = """
            type Foo @table(name: "MISSING") {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var items = runCaptured(source, cursor);

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

        var items = run(STORE.handle(), emptySnapshot(), source, cursor);

        assertThat(items).isEmpty();
    }

    @Test
    void interfaceTypeWithTableDirectiveAlsoResolvesColumns() {
        // @table binds an interface as it binds an object, so the same read answers both.
        String source = """
            interface Movie @table(name: "film") {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var items = runCaptured(source, cursor);

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

        var items = runCaptured(source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .startsWith("FILM_ID", "TITLE", "DESCRIPTION")
            .contains("LANGUAGE_ID");
    }

    /**
     * The store says the parent resolves against a class and which class, and the census says what
     * that class offers. So the document itself has to be the captured one: the class is the store's
     * answer for a type this SDL declares, grounded by a producer of its own, and the candidates are
     * the components a compiler recorded for it.
     */
    @Test
    void recordBackingCompletionReturnsRecordComponents() {
        String source = """
            type Query {
                card: FilmCard @service(service: {className: "%s", method: "makeFilmRecord"})
            }
            type FilmCard {
                bar: Int @field(name: "")
            }
            """.formatted(SERVICE_FIXTURE);
        int line = 4;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var items = runBacked(source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactly("filmId", "title");
    }

    @Test
    void pojoBackingCompletionReturnsBeanAccessors() {
        String source = """
            type Query {
                view: FilmPojoView @service(service: {className: "%s", method: "makeFilmPojo"})
            }
            type FilmPojoView {
                bar: Int @field(name: "")
            }
            """.formatted(SERVICE_FIXTURE);
        int line = 4;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var items = runBacked(source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactly("filmId", "title");
    }

    /**
     * A type the store reaches no single class for offers nothing, which is the reading a contested
     * binding gets everywhere: two producers naming different classes leave a surface with no basis
     * for offering one class's members over the other's.
     */
    @Test
    void contestedBackingCompletionReturnsEmpty() {
        String source = """
            type Query {
                asRecord: Contested @service(service: {className: "%s", method: "makeFilmRecord"})
                asPojo: Contested @service(service: {className: "%s", method: "makeFilmPojo"})
            }
            type Contested {
                bar: Int @field(name: "")
            }
            """.formatted(SERVICE_FIXTURE, SERVICE_FIXTURE);
        int line = 5;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var items = runBacked(source, cursor);

        assertThat(items).isEmpty();
    }

    /**
     * The column arm consults no projection at all now, so what the snapshot is doing cannot silence
     * it. Two cases that pinned the opposite are gone with the dependency: an entry missing from the
     * projection and a projection not built yet both used to mean silence here, and both now mean
     * only that the arm reads something else.
     */
    @Test
    void theColumnArmAnswersWithoutASnapshot() {
        String source = """
            type Foo @table(name: "film") {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        try (var store = StoreFixture.ofCatalog(sharedDirectory, source)) {
            assertThat(run(store.handle(), LspSchemaSnapshot.unavailable(), source, cursor))
                .extracting(CompletionItem::getLabel)
                .contains("FILM_ID", "TITLE");
        }
    }

    // ===== $source sigil completion =====

    @Test
    void sourceSigil_atCarrierDataField_isSuggested() {
        // Carrier projection declares FilmListPayload.films as the carrier data field, and the store
        // scopes the type to neither a table nor a class, so $source ships as the only completion.
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
            Map.of(),
            Map.of("FilmListPayload", "films")
        );
        var items = run(STORE.handle(), snapshot, source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactly(no.sikt.graphitron.rewrite.FieldSourceSigil.UPSTREAM_ROOT_LITERAL);
    }

    /**
     * The same site with no carrier entry for it: the sigil is not suggested, and nothing else is
     * either, the type being one the store scopes to neither a table nor a class. The LSP's narrow
     * predicate matches the build's narrow predicate.
     *
     * <p>A second case stood beside this one for a projection carrying no entry for the type at all,
     * which was a distinguishable state while the column arm read the projection for the parent's
     * backing. It is the same state as this one now: the sigil arm reads the carrier map alone, and
     * the arm beside it reads the store, so a projection has no third thing to say here.
     */
    @Test
    void sourceSigil_awayFromTheCarrierDataField_isNotSuggested() {
        String source = """
            type FilmListPayload {
                films: [Film!] @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var items = run(STORE.handle(), emptySnapshot(), source, cursor);

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

        var items = runCaptured(source, cursor);

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

        var items = runCaptured(source, cursor);

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

        try (var fixture = StoreFixture.ofCatalog(tmp, source)) {
            assertThat(documentationOf(
                run(fixture.handle(), emptySnapshot(), source, cursor), "FILM_ID"))
                .as("no source parsed yet, and the fixture database carries no column comments")
                .isEmpty();

            fixture.withJavaSource(tmp.resolve("generated"), fixture.tableClassFqn("film"), """
                public class Film {
                    /** The column <code>public.film.film_id</code>. */
                    public final Object FILM_ID = null;
                }
                """);

            assertThat(documentationOf(
                run(fixture.handle(), emptySnapshot(), source, cursor), "FILM_ID"))
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

    /**
     * Runs the arm against a store that captured this very document over the backing-class census,
     * which is what a class-backed case needs: the class the arm resolves is the store's answer for
     * a type the document declares, so the document and the capture have to be the same schema.
     */
    private List<CompletionItem> runBacked(String source, Point cursor) {
        try (var store = StoreFixture.of(sharedDirectory, source, StoreFixture.backingClasses())) {
            return run(store.handle(), emptySnapshot(), source, cursor);
        }
    }

    /** No projection at all: the cases using it resolve their scope from the store. */
    private static LspSchemaSnapshot emptySnapshot() {
        return new LspSchemaSnapshot.Built.Current(List.of(), Map.of(), Map.of());
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
