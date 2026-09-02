package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.references.BindingReferences;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.model.classpath.CompletionData;
import org.eclipse.lsp4j.Location;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Find-references from inside a directive argument: the cursor names a table, column, class, method
 * or foreign key, and the answer is every coordinate in the captured schema binding the same thing.
 *
 * <p>The population is the store's, so the schema that matters is the captured one below rather than
 * the buffer the cursor sits in. That is the dev session's own shape, a capture from the last build
 * under a buffer being edited, and it is why every expectation here is a line of {@link #CAPTURED}.
 */
class BindingReferencesTest {

    @TempDir
    static Path sourceRoot;

    private static StoreFixture store;

    private static final String SVC_FQN = "com.example.PriceService";
    private static final String FK_CONSTANT = "FILM__FILM_LANGUAGE_ID_FKEY";

    /**
     * The captured schema, laid out so each binding sits on a line worth naming. Zero-based:
     * <pre>
     *  2 type Foo @table(name: "film") {
     *  3   bar: Int @field(name: "title")
     *  4   price: Int @service(service: {className: ..., method: "price"})
     *  5   hop: Int @reference(path: [{key: "FILM__FILM_LANGUAGE_ID_FKEY"}])
     *  8 type Baz @table(name: "film") {
     *  9   title: String
     * 12 type Other @table(name: "language") {
     * 13   name: String @service(service: {className: ..., method: "shifted"})
     * </pre>
     */
    private static final String CAPTURED = """
        type Query { placeholder: Int }

        type Foo @table(name: "film") {
          bar: Int @field(name: "title")
          price: Int @service(service: {className: "com.example.PriceService", method: "price"})
          hop: Int @reference(path: [{key: "FILM__FILM_LANGUAGE_ID_FKEY"}])
        }

        type Baz @table(name: "film") {
          title: String
        }

        type Other @table(name: "language") {
          name: String @service(service: {className: "com.example.PriceService", method: "shifted"})
        }
        """;

    @BeforeAll
    static void capture() {
        store = StoreFixture.ofCatalog(sourceRoot, CAPTURED, census());
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    /**
     * Both types bound to the table answer, and the type bound to another does not. The cursor's own
     * buffer is not part of the capture, so what comes back is the schema's other bindings, which is
     * what the question asks for.
     */
    @Test
    void everyTypeBoundToTheSameTableIsListed() {
        var uses = referencesTo("type Cursor @table(name: \"film\") { bar: Int }", 0, "film");

        assertThat(uses).extracting(BindingReferencesTest::line)
            .as("Foo on line 2 and Baz on line 8, never Other on line 12")
            .containsExactly(2, 8);
    }

    /**
     * A differently cased spelling of the same table finds the same sites. The match is on the
     * table a spelling resolves to rather than on the string, and the resolution is the census
     * reader's, the same one the jump and the completions use.
     */
    @Test
    void aDifferentlyCasedSpellingOfTheSameTableFindsTheSameSites() {
        var plain = referencesTo("type Cursor @table(name: \"film\") { bar: Int }", 0, "film");
        var shouted = referencesTo("type Cursor @table(name: \"FILM\") { bar: Int }", 0, "FILM");

        assertThat(shouted).isNotEmpty().isEqualTo(plain);
    }

    /**
     * The explicit binding and the field whose own name matches the column are both uses. The
     * generator reads them the same way, so an author asking what maps to this column gets both.
     */
    @Test
    void anImplicitNameMatchCountsAsABindingOfTheColumn() {
        var uses = referencesTo("""
            type Foo @table(name: "film") {
              bar: Int @field(name: "title")
            }
            """, 1, "title");

        assertThat(uses).extracting(BindingReferencesTest::line)
            .as("Foo.bar's @field binding on line 3, and Baz.title matching by name on line 9")
            .containsExactly(3, 9);
    }

    @Test
    void everyCoordinateNamingTheClassIsListed() {
        var uses = referencesTo(
            "type Cursor { y: Int @service(service: {className: \"" + SVC_FQN
                + "\", method: \"price\"}) }", 0, SVC_FQN);

        assertThat(uses).extracting(BindingReferencesTest::line)
            .as("both @service sites name the class, whichever method they call")
            .containsExactly(4, 13);
    }

    /** The method narrows the class population to the sites that call this one. */
    @Test
    void theMethodNarrowsTheClassPopulation() {
        var uses = referencesTo(
            "type Cursor { y: Int @service(service: {className: \"" + SVC_FQN
                + "\", method: \"price\"}) }", 0, "\"price\"");

        assertThat(uses).extracting(BindingReferencesTest::line)
            .as("only the site calling price, not the one calling shifted")
            .containsExactly(4);
    }

    /** A path hop is positioned by the reference it belongs to, the store holding no hop position. */
    @Test
    void aPathHopKeyedOnTheConstraintIsListed() {
        var uses = referencesTo(
            "type Cursor @table(name: \"film\") { hop: Int @reference(path: [{key: \""
                + FK_CONSTANT + "\"}]) }", 0, FK_CONSTANT);

        assertThat(uses).extracting(BindingReferencesTest::line).containsExactly(5);
    }

    /**
     * A name the census does not hold is not a target, so the answer is empty rather than every site
     * that happens to spell something similar.
     */
    @Test
    void anUnknownTableIsNotASubject() {
        assertThat(referencesTo("type Cursor @table(name: \"MISSING\") { bar: Int }", 0, "MISSING"))
            .isEmpty();
    }

    /**
     * A cursor outside any directive argument is not this arm's, and it says so with an empty list
     * rather than answering from the enclosing type.
     */
    @Test
    void aCursorOutsideADirectiveIsNotThisArms() {
        assertThat(referencesTo("type Cursor @table(name: \"film\") { bar: Int }", 0, "Cursor"))
            .isEmpty();
    }

    private static int line(Location use) {
        return use.getRange().getStart().getLine();
    }

    private static List<Location> referencesTo(String buffer, int line, String token) {
        var file = WorkspaceFileTestSupport.snapshot(buffer);
        var uses = BindingReferences.compute(
            BundledVocabulary.get(), file, store.handle(), pointAt(file, line, token), false);
        assertThat(uses)
            .as("every site is in the captured schema file")
            .allSatisfy(use -> assertThat(use.getUri()).endsWith(Path.of(store.sourceName()).getFileName().toString()));
        return uses;
    }

    private static Point pointAt(FileSnapshot file, int line, String token) {
        String source = new String(file.source(), StandardCharsets.UTF_8);
        var lines = source.split("\n");
        int col = lines[line].indexOf(token);
        if (col < 0) {
            throw new AssertionError("token '" + token + "' not on line " + line + ": " + lines[line]);
        }
        return new Point(line, col + Math.max(1, token.length() / 2));
    }

    private static List<CompletionData.ExternalReference> census() {
        return List.of(StoreFixture.jarClass(SVC_FQN, List.of(
            StoreFixture.method("price", "Field"),
            StoreFixture.method("shifted", "Object"))));
    }
}
