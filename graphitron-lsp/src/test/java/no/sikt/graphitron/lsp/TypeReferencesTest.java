package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.references.TypeReferences;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.model.read.StoreHandle;
import org.assertj.core.api.Assertions;
import org.eclipse.lsp4j.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Find-references for an SDL type name: cursor on a type's declaration or on a use of it, answer is
 * every site in the schema that uses it. Drives real capture over real SDL and reads the positions
 * back, so every line asserted below is where a parse found a site rather than a number a fixture
 * declared.
 *
 * <p>One fixture serves most of the arms because the four populations are four ways of writing a
 * type's name and a schema that exercises them all is a schema worth reading once: {@code Film} is
 * used by a field and by a union member, {@code Identifiable} by a field and by an
 * {@code implements}, and {@code FilmFilter} by an argument.
 */
class TypeReferencesTest {

    /**
     * Line numbers are asserted against this, so the layout is the fixture. Zero-based, the
     * conversion having already happened by the time a test sees a location:
     * <pre>
     *  0 type Query {
     *  1   films(filter: FilmFilter): [Film!]!
     *  2   subject: Identifiable
     *  3 }
     *  5 input FilmFilter {
     *  9 interface Identifiable {
     * 13 type Film implements Identifiable {
     * 18 union SearchResult = Film
     * </pre>
     */
    private static final String SDL = """
        type Query {
          films(filter: FilmFilter): [Film!]!
          subject: Identifiable
        }

        input FilmFilter {
          title: String
        }

        interface Identifiable {
          id: ID!
        }

        type Film implements Identifiable {
          id: ID!
          title: String
        }

        union SearchResult = Film
        """;

    @Test
    void aFieldAndAUnionMemberUsingTheTypeAreBothListed(@TempDir Path tmp) {
        try (var fixture = StoreFixture.of(tmp, SDL)) {
            var uses = referencesTo(fixture, "Film implements");

            // The field's own site (`films` on line 1) and the union member token itself
            // (`Film` at the end of line 18). The two arms position different things, which is
            // the relations' doing rather than the reader's.
            assertThat(uses).extracting(startLine(), startColumn())
                .containsExactly(tuple(1, 2), tuple(18, 21));
        }
    }

    @Test
    void aFieldAndAnImplementsUsingTheInterfaceAreBothListed(@TempDir Path tmp) {
        try (var fixture = StoreFixture.of(tmp, SDL)) {
            var uses = referencesTo(fixture, "Identifiable {");

            // `subject` on line 2, and the `Identifiable` token of the implements on line 13.
            assertThat(uses).extracting(startLine(), startColumn())
                .containsExactly(tuple(2, 2), tuple(13, 21));
        }
    }

    @Test
    void anArgumentUsingTheInputTypeIsListed(@TempDir Path tmp) {
        try (var fixture = StoreFixture.of(tmp, SDL)) {
            var uses = referencesTo(fixture, "FilmFilter {");

            // The argument's own site: `filter` inside the field's argument list on line 1.
            assertThat(uses).extracting(startLine(), startColumn())
                .containsExactly(tuple(1, 8));
        }
    }

    /**
     * The cursor on a use answers with the same population as the cursor on the declaration. The
     * two shapes are different tree-sitter nodes and the same question, which is why the provider
     * resolves both to a name before reading anything.
     */
    @Test
    void aCursorOnAUseAnswersTheSameAsACursorOnTheDeclaration(@TempDir Path tmp) {
        try (var fixture = StoreFixture.of(tmp, SDL)) {
            assertThat(referencesTo(fixture, "Film!"))
                .isEqualTo(referencesTo(fixture, "Film implements"));
        }
    }

    @Test
    void theDeclarationJoinsTheListOnlyWhenTheEditorAsksForIt(@TempDir Path tmp) {
        try (var fixture = StoreFixture.of(tmp, SDL)) {
            assertThat(referencesTo(fixture, "Film implements", true))
                .extracting(startLine(), startColumn())
                .containsExactly(tuple(1, 2), tuple(13, 0), tuple(18, 21));
        }
    }

    /**
     * Every declaration site joins, not the one a jump would pick. An extension is a place the name
     * is written, so an author about to rename the type needs to see it.
     */
    @Test
    void anExtensionIsADeclarationSiteOfItsOwn(@TempDir Path tmp) {
        try (var fixture = StoreFixture.of(tmp, """
            type Query {
              films: [Film!]!
            }

            extend type Film {
              rentalRate: Float
            }

            type Film {
              title: String
            }
            """)) {
            assertThat(referencesTo(fixture, "Film!", true))
                .extracting(startLine())
                .containsExactly(1, 4, 8);
        }
    }

    /**
     * Nothing uses the type, so the answer is an empty list. Distinct from declining: the surface
     * understood the question and the schema's answer is that there are no uses.
     */
    @Test
    void aTypeNothingUsesAnswersAnEmptyList(@TempDir Path tmp) {
        try (var fixture = StoreFixture.of(tmp, """
            type Query {
              placeholder: Int
            }

            type Orphan {
              name: String
            }
            """)) {
            assertThat(referencesTo(fixture, "Orphan {")).isEmpty();
        }
    }

    /**
     * A built-in scalar is declared by the language, and every schema is full of fields returning
     * one. Listing them is noise rather than an answer, so the cursor resolves to nothing.
     */
    @Test
    void aBuiltinScalarIsNotASubject(@TempDir Path tmp) {
        try (var fixture = StoreFixture.of(tmp, SDL)) {
            assertThat(referencesTo(fixture, "String")).isEmpty();
        }
    }

    /**
     * A field declaration name is the member-usage subject, which this surface defers. Declining is
     * what keeps a deferred subject from quietly answering with the enclosing type's population.
     */
    @Test
    void aCursorOnAFieldDeclarationNameIsNotYetASubject(@TempDir Path tmp) {
        try (var fixture = StoreFixture.of(tmp, SDL)) {
            assertThat(referencesTo(fixture, "title: String")).isEmpty();
        }
    }

    /**
     * A session outside a build answers with an empty list rather than falling back to the buffers.
     * The asymmetry with the intra-schema jump is deliberate and is the freshness decision: no arm
     * here can answer from open buffers, because the population is workspace-wide and the buffers
     * are whatever the author has open.
     */
    @Test
    void aSessionWithNoStoreAnswersEmpty() {
        var ws = new Workspace();
        String uri = "file:///schema.graphqls";
        ws.didOpen(uri, 1, SDL);

        var uses = ws.withView(uri, List.<Location>of(), file ->
            TypeReferences.compute(file, Optional.empty(), cursorOn(ws, uri, "Film implements"), false));
        assertThat(uses).isEmpty();
    }

    private static List<Location> referencesTo(StoreFixture fixture, String token) {
        return referencesTo(fixture, token, false);
    }

    /**
     * Opens the captured file as the buffer the cursor sits in, so the request is the one an editor
     * makes: a position in a real file, answered from the graph that captured it.
     */
    private static List<Location> referencesTo(
        StoreFixture fixture, String token, boolean includeDeclaration
    ) {
        var ws = new Workspace();
        String uri = capturedUri(fixture);
        ws.didOpen(uri, 1, sourceOf(fixture));
        StoreHandle handle = fixture.handle();
        var uses = ws.withView(uri, List.<Location>of(), file ->
            TypeReferences.compute(file, handle, cursorOn(ws, uri, token), includeDeclaration));
        assertThat(uses).allSatisfy(use -> assertThat(use.getUri()).isEqualTo(uri));
        return uses;
    }

    private static String sourceOf(StoreFixture fixture) {
        try {
            return Files.readString(Path.of(fixture.sourceName()));
        } catch (IOException e) {
            throw new AssertionError("the fixture's own schema file is unreadable", e);
        }
    }

    private static String capturedUri(StoreFixture fixture) {
        return Path.of(fixture.sourceName()).toUri().toString();
    }

    private static java.util.function.Function<Location, Integer> startLine() {
        return use -> use.getRange().getStart().getLine();
    }

    private static java.util.function.Function<Location, Integer> startColumn() {
        return use -> use.getRange().getStart().getCharacter();
    }

    /** Lands inside the first occurrence of {@code token}, never on its first edge. */
    private static Point cursorOn(Workspace ws, String uri, String token) {
        String source = ws.withView(uri, null, v -> new String(v.source(), StandardCharsets.UTF_8));
        if (source == null) {
            throw new AssertionError("uri '" + uri + "' is not open");
        }
        int idx = source.indexOf(token);
        if (idx < 0) {
            Assertions.fail("token '" + token + "' not in source of " + uri);
        }
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < idx; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new Point(line, (idx - lineStart) + 1);
    }
}
