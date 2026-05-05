package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.hover.Hovers;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.MarkupKind;
import org.junit.jupiter.api.Test;
import io.github.treesitter.jtreesitter.Point;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-directive hover content. Cursor inside a known argument value
 * surfaces catalog metadata as Markdown; positions on directive names
 * or unknown arg values produce no hover so the editor falls through.
 */
class HoversTest {

    @Test
    void tableHoverShowsTableMetadata() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int
            }
            """);
        // Cursor inside the "film" string value.
        var pos = pointAt(file, 0, "film");

        var hover = Hovers.compute(file, filmCatalog(), pos).orElseThrow();

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

        assertThat(Hovers.compute(file, filmCatalog(), pos)).isEmpty();
    }

    @Test
    void fieldHoverShowsColumnMetadata() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "title")
            }
            """);
        var pos = pointAt(file, 1, "title");

        var hover = Hovers.compute(file, filmCatalog(), pos).orElseThrow();
        var md = hover.getContents().getRight().getValue();

        assertThat(md).contains("**Column** `title`");
        assertThat(md).contains("on `film`");
        assertThat(md).contains("`String`");
        assertThat(md).contains("not null");
    }

    @Test
    void referenceKeyHoverShowsForeignKeyDirection() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: "FILM__FILM_LANGUAGE_ID_FKEY"}])
            }
            """);
        var pos = pointAt(file, 1, "FILM__FILM_LANGUAGE_ID_FKEY");

        var hover = Hovers.compute(file, filmCatalog(), pos).orElseThrow();
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

        var hover = Hovers.compute(file, filmCatalog(), pos).orElseThrow();
        var md = hover.getContents().getRight().getValue();

        assertThat(md).contains("**Table** `language`");
    }

    @Test
    void cursorOnDirectiveNameReturnsEmpty() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int
            }
            """);
        // Cursor on the directive name token, not on its argument.
        int line = 0;
        int col = file.tree().getRootNode().getType().equals("source_file")
            ? "type Foo @t".length() : 0;
        var pos = new Point(line, col);

        assertThat(Hovers.compute(file, filmCatalog(), pos)).isEmpty();
    }

    @Test
    void cursorOnUnknownColumnReturnsEmpty() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "GHOST")
            }
            """);
        var pos = pointAt(file, 1, "GHOST");

        assertThat(Hovers.compute(file, filmCatalog(), pos)).isEmpty();
    }

    private static Point pointAt(WorkspaceFile file, int line, String token) {
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
                x: Int @service(class: "com.example.FilmService", method: "list")
            }
            """);
        var pos = pointAt(file, 1, "FilmService");

        var hover = Hovers.compute(file, classCatalog("com.example.FilmService"), pos).orElseThrow();

        var md = hover.getContents().getRight().getValue();
        assertThat(md).contains("**Class** `com.example.FilmService`");
    }

    @Test
    void recordClassNameHoverShowsClassFqn() {
        var file = file("""
            input FooInput @record(record: {className: "com.example.FooDto"}) {
                bar: Int
            }
            """);
        var pos = pointAt(file, 0, "FooDto");

        var hover = Hovers.compute(file, classCatalog("com.example.FooDto"), pos).orElseThrow();

        var md = hover.getContents().getRight().getValue();
        assertThat(md).contains("**Class** `com.example.FooDto`");
    }

    @Test
    void unknownServiceClassReturnsEmpty() {
        var file = file("""
            type Query {
                x: Int @service(class: "com.example.Missing", method: "list")
            }
            """);
        var pos = pointAt(file, 1, "Missing");

        assertThat(Hovers.compute(file, classCatalog("com.example.Other"), pos)).isEmpty();
    }

    private static CompletionData classCatalog(String fqn) {
        return new CompletionData(
            List.of(),
            List.of(),
            List.of(new CompletionData.ExternalReference(fqn, fqn, "", List.of()))
        );
    }

    private static WorkspaceFile file(String source) {
        return new WorkspaceFile(1, source);
    }

    private static CompletionData filmCatalog() {
        var film = new CompletionData.Table(
            "film",
            "Movies the rental store carries",
            CompletionData.SourceLocation.UNKNOWN,
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
            CompletionData.SourceLocation.UNKNOWN,
            List.of(CompletionData.Column.of("language_id", "Integer", false, "")),
            List.of()
        );
        return new CompletionData(List.of(film, language), List.of(), List.of());
    }
}
