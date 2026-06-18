package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.completions.FieldCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;
import org.junit.jupiter.api.Test;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;
import io.github.treesitter.jtreesitter.Language;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for {@code @field(name: "...")} column autocomplete.
 * The interesting bit is the context resolution: the LSP must walk up
 * from the field's directive to the enclosing type's {@code @table} to
 * pick which table's columns to suggest.
 */
class FieldCompletionsTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

    @Test
    void columnNameCompletionReturnsTableColumns() {
        String source = """
            type Foo @table(name: "FILM") {
                bar: Int @field(name: "")
            }
            """;
        // Cursor inside the empty quoted argument value.
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var items = run(filmCatalog(), tableSnapshot("Foo", "FILM"), source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactly("FILM_ID", "TITLE", "LANGUAGE_ID");
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
        var items = run(filmCatalog(), snapshot, source, cursor);

        assertThat(items).isEmpty();
    }

    @Test
    void unknownTableReturnsEmpty() {
        // Enclosing type points at a table the catalog does not know — but
        // the classifier still projected TableBacking(MISSING). The
        // completion arm consults CompletionData.getTable which returns
        // empty; no candidates surface.
        String source = """
            type Foo @table(name: "MISSING") {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var items = run(filmCatalog(), tableSnapshot("Foo", "MISSING"), source, cursor);

        assertThat(items).isEmpty();
    }

    @Test
    void cursorOutsideNameArgReturnsEmpty() {
        String source = """
            type Foo @table(name: "FILM") {
                bar: Int @field(name: "title")
            }
            """;
        // Cursor on the @field directive name, not inside the argument.
        int line = 1;
        int col = source.split("\n")[line].indexOf("@field") + 1;
        Point cursor = new Point(line, col);

        var items = run(filmCatalog(), tableSnapshot("Foo", "FILM"), source, cursor);

        assertThat(items).isEmpty();
    }

    @Test
    void interfaceTypeWithTableDirectiveAlsoResolvesColumns() {
        // @table on an interface — TableInterfaceType projects to
        // TableBacking, same data path as TableType.
        String source = """
            interface Movie @table(name: "FILM") {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var items = run(filmCatalog(), tableSnapshot("Movie", "FILM"), source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .contains("FILM_ID", "TITLE");
    }

    /**
     * R100 — {@code @node(keyColumns:)} reuses
     * {@link no.sikt.graphitron.lsp.parsing.Behavior.CatalogColumnBinding}
     * via an overlay-delta entry. The candidate set is the columns of the
     * enclosing type's {@code @table}; cursor inside a list-element string
     * literal completes the same way a flat string-valued column slot does.
     */
    @Test
    void nodeKeyColumnsCompletionInsideListLiteralReturnsTableColumns() {
        String source = """
            type Foo implements Node @table(name: "FILM") @node(keyColumns: [""]) {
                id: ID
            }
            """;
        // Cursor inside the empty quoted element of the list.
        int line = 0;
        int col = source.split("\n")[line].indexOf("[\"") + 2;
        Point cursor = new Point(line, col);

        var items = run(filmCatalog(), tableSnapshot("Foo", "FILM"), source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactly("FILM_ID", "TITLE", "LANGUAGE_ID");
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
                new TypeBackingShape.MemberSlot("filmId", "Integer"),
                new TypeBackingShape.MemberSlot("title", "String")
            ))),
        Map.of());
        var items = run(filmCatalog(), snapshot, source, cursor);

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
                new TypeBackingShape.MemberSlot("filmId", "Integer"),
                new TypeBackingShape.MemberSlot("title", "String")
            ))),
        Map.of());
        var items = run(filmCatalog(), snapshot, source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactly("filmId", "title");
    }

    @Test
    void snapshotMissReturnsEmpty() {
        // SDL declares the type but the snapshot has no entry — same as
        // mid-edit state. Silent rather than spamming candidates.
        String source = """
            type Foo @table(name: "FILM") {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var snapshot = new LspSchemaSnapshot.Built.Current(List.of(), Map.of(), Map.of());
        var items = run(filmCatalog(), snapshot, source, cursor);

        assertThat(items).isEmpty();
    }

    @Test
    void unavailableSnapshotReturnsEmpty() {
        // Pre-build state — no classifier output to consult yet.
        String source = """
            type Foo @table(name: "FILM") {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var items = run(filmCatalog(), LspSchemaSnapshot.unavailable(), source, cursor);

        assertThat(items).isEmpty();
    }

    // ===== R159 — $source sigil completion =====

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
        var items = run(filmCatalog(), snapshot, source, cursor);

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
        var items = run(filmCatalog(), snapshot, source, cursor);

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
        var items = run(filmCatalog(), snapshot, source, cursor);

        assertThat(items).isEmpty();
    }

    // ===== R233 — @field(name:) on @reference path field completes terminal-table columns =====

    @Test
    void inputTableWithReferencePathCompletesTerminalTableColumns() {
        // The enclosing @table is "FILM"; the @reference path navigates to "LANGUAGE". Pre-R233
        // the completion dropdown listed FILM's columns (FILM_ID / TITLE) which are not
        // reachable through this field; R233 routes through FieldClassification.lspColumnDispatch()
        // and emits LANGUAGE's columns instead.
        String source = """
            input FilmInput @table(name: "FILM") {
                languageName: String @field(name: "") @reference(path: [{table: "LANGUAGE"}])
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf("@field(name: \"") + "@field(name: \"".length();
        Point cursor = new Point(line, col);

        var snapshot = new LspSchemaSnapshot.Built.Current(
            List.of(),
            Map.of("FilmInput", new TypeBackingShape.TableBacking("FILM")),
            Map.of(),
            Map.of("FilmInput.languageName",
                new no.sikt.graphitron.rewrite.catalog.FieldClassification.ColumnReference(
                    "LANGUAGE", "NAME", List.of())),
            Map.of()
        );
        var items = run(filmAndLanguageCatalog(), snapshot, source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactly("LANGUAGE_ID", "NAME")
            .doesNotContain("FILM_ID", "TITLE");
    }

    @Test
    void outputTableWithReferencePathCompletesTerminalTableColumns() {
        // Output-side mirror — covers the ChildField.ColumnReferenceField projection.
        String source = """
            type Film @table(name: "FILM") {
                languageName: String @field(name: "") @reference(path: [{table: "LANGUAGE"}])
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf("@field(name: \"") + "@field(name: \"".length();
        Point cursor = new Point(line, col);

        var snapshot = new LspSchemaSnapshot.Built.Current(
            List.of(),
            Map.of("Film", new TypeBackingShape.TableBacking("FILM")),
            Map.of(),
            Map.of("Film.languageName",
                new no.sikt.graphitron.rewrite.catalog.FieldClassification.ColumnReference(
                    "LANGUAGE", "NAME", List.of())),
            Map.of()
        );
        var items = run(filmAndLanguageCatalog(), snapshot, source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactly("LANGUAGE_ID", "NAME")
            .doesNotContain("FILM_ID", "TITLE");
    }

    @Test
    void unresolvedReferencePathCompletionSilentOnLspSide() {
        // Classifier could not assign a variant (Unclassified); suggestions from the enclosing-
        // type backing would lead the user toward FILM columns rather than helping resolve the
        // @reference target. The LSP must emit an empty list rather than leak the wrong table.
        String source = """
            input FilmInput @table(name: "FILM") {
                languageName: String @field(name: "") @reference(path: [{table: "LANGUAGE"}])
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf("@field(name: \"") + "@field(name: \"".length();
        Point cursor = new Point(line, col);

        var snapshot = new LspSchemaSnapshot.Built.Current(
            List.of(),
            Map.of("FilmInput", new TypeBackingShape.TableBacking("FILM")),
            Map.of(),
            Map.of("FilmInput.languageName",
                new no.sikt.graphitron.rewrite.catalog.FieldClassification.Unclassified("synthetic test reason")),
            Map.of()
        );
        var items = run(filmAndLanguageCatalog(), snapshot, source, cursor);

        assertThat(items).isEmpty();
    }

    // ===== R331 — @field(name:) on a @table-interface participant cross-table reference =====
    //              completes the @reference terminal-table columns, not the participant's @table

    @Test
    void participantCrossTableReferenceCompletesTerminalTableColumns() {
        // The enclosing @table is "FILM" (the participant table); the field reaches the terminal
        // table "LANGUAGE" via a ParticipantCrossTable classification. Pre-R331 the dropdown
        // listed FILM's columns; routing ParticipantCrossTable through lspColumnDispatch() emits
        // LANGUAGE's columns instead.
        String source = """
            type DokumentMelding implements Melding @table(name: "FILM") @discriminator(value: "DOKUMENT") {
                languageName: String @field(name: "") @reference(path: [{table: "LANGUAGE"}])
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf("@field(name: \"") + "@field(name: \"".length();
        Point cursor = new Point(line, col);

        var snapshot = new LspSchemaSnapshot.Built.Current(
            List.of(),
            Map.of("DokumentMelding", new TypeBackingShape.TableBacking("FILM")),
            Map.of(),
            Map.of("DokumentMelding.languageName",
                new no.sikt.graphitron.rewrite.catalog.FieldClassification.ParticipantCrossTable(
                    "LANGUAGE", "", "DOKUMENT_MELDING__DOKUMENT_MELDING_BASE_FK", "soknad")),
            Map.of()
        );
        var items = run(filmAndLanguageCatalog(), snapshot, source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactly("LANGUAGE_ID", "NAME")
            .doesNotContain("FILM_ID", "TITLE");
    }

    private static CompletionData filmAndLanguageCatalog() {
        var film = new CompletionData.Table(
            "FILM", "Movies", CompletionData.SourceLocation.UNKNOWN,
            List.of(
                CompletionData.Column.of("FILM_ID", "Integer", false, ""),
                CompletionData.Column.of("TITLE", "String", false, "")
            ),
            List.of()
        );
        var language = new CompletionData.Table(
            "LANGUAGE", "Languages", CompletionData.SourceLocation.UNKNOWN,
            List.of(
                CompletionData.Column.of("LANGUAGE_ID", "Integer", false, ""),
                CompletionData.Column.of("NAME", "String", false, "")
            ),
            List.of()
        );
        return new CompletionData(List.of(film, language), List.of(), List.of());
    }

    private static LspSchemaSnapshot tableSnapshot(String typeName, String tableName) {
        return new LspSchemaSnapshot.Built.Current(
            List.of(),
            Map.of(typeName, new TypeBackingShape.TableBacking(tableName)),
        Map.of());
    }

    private static List<org.eclipse.lsp4j.CompletionItem> run(
        CompletionData data, LspSchemaSnapshot snapshot, String source, Point cursor
    ) {
        var parser = new Parser();
        parser.setLanguage(no.sikt.graphitron.lsp.parsing.GraphqlLanguage.get());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor"));
        var locOpt = VOCAB.locateAt(directive, cursor, bytes);
        if (locOpt.isEmpty()) return List.of();
        var context = no.sikt.graphitron.lsp.completions.CompletionContext.from(locOpt.get(), bytes);
        return FieldCompletions.generate(VOCAB, data, snapshot, context, directive, bytes);
    }

    private static CompletionData filmCatalog() {
        return new CompletionData(
            List.of(new CompletionData.Table(
                "FILM",
                "Movies the rental store carries",
                CompletionData.SourceLocation.UNKNOWN,
                List.of(
                    CompletionData.Column.of("FILM_ID", "Integer", false, ""),
                    CompletionData.Column.of("TITLE", "String", false, ""),
                    CompletionData.Column.of("LANGUAGE_ID", "Integer", true, "")
                ),
                List.of()
            )),
            List.of(),
            List.of()
        );
    }
}
