package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.inlay.InlayHints;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.InlayHintConfig;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inferred-directive arm's {@code @field} pass, over a real capture. What it fills in is the
 * member the field's own name reaches, which the store answers in two relations: the column-match
 * classifier where the site resolves against a table, and the class member-slot rule where it
 * resolves against a class. Neither has a generator pass behind it, so every case here runs under an
 * unavailable snapshot, as the {@code @table} pass's cases do in {@code InferredTableHintsTest}.
 *
 * <p>An overlay's text is usually the field's own name, because both arms resolve <em>by</em> that name,
 * so what most cases here turn on is whether an overlay appears at all. That is the signal an author
 * reads: a bare {@code @field} with an overlay beside it resolved, and one without did not. Two cases
 * carry more than presence. The path case renders a column the parent's own table does not have, so
 * the text is evidence of where the resolution ran. The masked case has a column under it and shows
 * nothing, which is what makes the arm a reader of the reduction rather than of the raw classifier.
 *
 * <p>One silence here is a gap rather than a verdict, and it is asserted as one:
 * {@code FilmRow} resolves its member names against the columns of the table whose row type backs it,
 * and no relation derives that match. {@code FieldMemberName} carries the same statement where it
 * outlives this test.
 */
class InferredFieldHintsTest {

    private static final String FIXTURE_SERVICE = "no.sikt.graphitron.lsp.fixtures.R157Service";

    /**
     * One schema covering every shape the pass answers: a table-bound parent with a matching field, a
     * non-matching one and one an authored claim masks; a field whose authored path moves its
     * resolution to another table; a parent backed by a POJO; and a parent backed by a generated row
     * type, which is the population no relation reaches.
     */
    private static final String SDL = """
        type Query {
            placeholder: Int
            pojo: FilmPojoView @service(service: {className: "%1$s", method: "makeFilmPojo"})
            row: FilmRow @service(service: {className: "%1$s", method: "makeFilmRow"})
        }

        type Film @table(name: "film") {
            title: String
            rating: String @service(service: {className: "com.example.FilmService", method: "rating"})
            name: String @reference(path: [{key: "film_language_id_fkey"}])
            unmatched: String
        }

        type FilmPojoView {
            title: String
            unmatched: String
        }

        type FilmRow {
            title: String
        }
        """.formatted(FIXTURE_SERVICE);

    @TempDir
    static Path tmp;

    private static StoreFixture store;

    @BeforeAll
    static void capture() {
        store = StoreFixture.ofCatalog(tmp, SDL, StoreFixture.backingClasses());
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @Test
    void aBareFieldDirectiveShowsTheColumnItsNameResolvedTo() {
        var file = file("""
            type Film @table(name: "film") {
                title: String @field
            }
            """);
        assertThat(labels(file)).containsExactly("name: \"title\"");
    }

    @Test
    void aNameNoColumnAnswersRendersNothing() {
        // The whole value of the overlay's absence: `unmatched` names no column of `film`, so
        // graphitron reads nothing here and the arm says nothing rather than echoing the field name.
        var file = file("""
            type Film @table(name: "film") {
                unmatched: String @field
            }
            """);
        assertThat(labels(file)).isEmpty();
    }

    @Test
    void anAuthoredNameLeavesNothingToFillIn() {
        var file = file("""
            type Film @table(name: "film") {
                title: String @field(name: "title")
            }
            """);
        assertThat(labels(file)).isEmpty();
    }

    /**
     * The reduction is what the arm reads, and this is the case that shows it. {@code rating} is a
     * column of {@code film}, so the structural classifier reaches the coordinate and its raw reading
     * survives; {@code @service} claims the field, so the generator reads no column at all, and an
     * overlay naming one would tell the author graphitron resolved something it does not use.
     */
    @Test
    void anAuthoredClaimSilencesTheOverlayOverItsOwnColumn() {
        var file = file("""
            type Film @table(name: "film") {
                rating: String @field
            }
            """);
        assertThat(labels(file)).isEmpty();
    }

    /**
     * The relation the column arm stands on resolves the site rather than the parent, so an authored
     * path moves the match with it. {@code film} has no {@code name} column and {@code language} does:
     * an overlay here is only possible against the path's terminal table.
     */
    @Test
    void anAuthoredPathResolvesTheNameAtItsTerminalTable() {
        var file = file("""
            type Film @table(name: "film") {
                name: String @field @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        assertThat(labels(file))
            .as("the parent's own table has no such column, so this overlay is the terminal's")
            .containsExactly("name: \"name\"");
    }

    @Test
    void aClassBackedParentResolvesAMemberRatherThanAColumn() {
        // Nothing binds FilmPojoView to a table; a producer grounds it on a POJO, and `title` is a
        // slot that POJO offers through its bean accessor.
        var file = file("""
            type FilmPojoView {
                title: String @field
            }
            """);
        assertThat(labels(file)).containsExactly("name: \"title\"");
    }

    @Test
    void aNameTheBackingClassDoesNotOfferRendersNothing() {
        var file = file("""
            type FilmPojoView {
                unmatched: String @field
            }
            """);
        assertThat(labels(file)).isEmpty();
    }

    /**
     * The gap, pinned so it cannot be mistaken for a rule. {@code FilmRow} is grounded on the row type
     * jOOQ generated for {@code film}, so a name written in it resolves against that table's columns
     * and {@code TypeMemberScope} offers them. The column arm reaches the site's table from a
     * {@code @table} binding or an authored path and never from the parent's backing class, so the
     * match is not derived at this coordinate; closing it is a rule in the scope relation.
     */
    @Test
    void aParentScopedThroughItsBackingRowTypeIsNotReachedYet() {
        var file = file("""
            type FilmRow {
                title: String @field
            }
            """);
        assertThat(labels(file)).isEmpty();
    }

    @Test
    void anExtensionSiteResolvesThroughTheBaseDeclarationsBinding() {
        // A binding is a property of the type rather than of the declaration the cursor is in, so a
        // field of an extension resolves against the base declaration's table. The @table overlay the
        // directiveless extension also earns is the sibling pass's subject, not this one's.
        var file = file("""
            extend type Film {
                title: String @field
            }
            """);
        assertThat(labels(file)).contains("name: \"title\"");
    }

    @Test
    void noOverlaysWithoutAStore() {
        var file = file("""
            type Film @table(name: "film") {
                title: String @field
            }
            """);
        assertThat(InlayHints.compute(config(), file, Optional.empty(),
            LspSchemaSnapshot.unavailable(), fullRange())).isEmpty();
    }

    // ===== Helpers =====

    private static List<String> labels(FileSnapshot file) {
        return InlayHints.compute(config(), file, Optional.of(store.handle()),
                LspSchemaSnapshot.unavailable(), fullRange())
            .stream().map(InferredFieldHintsTest::labelOf).toList();
    }

    private static String labelOf(InlayHint hint) {
        var either = hint.getLabel();
        return either.isLeft() ? either.getLeft() : either.getRight().toString();
    }

    /** The inferred-directive toggle alone, so no other arm's label can be mistaken for an overlay. */
    private static InlayHintConfig config() {
        return new InlayHintConfig(true, false, false, false);
    }

    private static FileSnapshot file(String source) {
        return WorkspaceFileTestSupport.snapshot(source);
    }

    private static Range fullRange() {
        return new Range(new Position(0, 0), new Position(10_000, 0));
    }
}
