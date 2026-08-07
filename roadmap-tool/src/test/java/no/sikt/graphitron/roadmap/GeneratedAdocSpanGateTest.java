package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The structural enforcer for the invariant "generated roadmap AsciiDoc carries no
 * substituting monospace span". The docs-profile WARN gate cannot hold this line: it is
 * configured on the site-render execution inside the docs profile, so it does not run under
 * a {@code -P!docs} build, and a live cross-file xref renders completely silently anyway.
 *
 * <p>The gate renders the real corpus through the real renderers, so it catches the future
 * regression a converter-level unit test cannot: a later markdown-sourced surface that
 * forgets to route through {@link InertSpans}. Harness borrowed from
 * {@link TransientCitationCheckTest}, walking up from the module basedir to the reactor root
 * that holds the corpus.
 */
class GeneratedAdocSpanGateTest {

    @Test
    void bareSpanInFlowedProse_isAFinding() {
        assertThat(InertSpans.scan("A `live` span.\n"))
            .singleElement()
            .satisfies(f -> assertThat(f.span()).isEqualTo("`live`"));
    }

    @Test
    void bothInertForms_areNotFindings() {
        assertThat(InertSpans.scan("A `+plain+` and a `pass:c[a + b]` span.\n")).isEmpty();
    }

    @Test
    void passMacroCarryingBackticksAndAnEscapedBracket_isNotAFinding() {
        assertThat(InertSpans.scan("A `pass:c[`Map<K|V>`]` type and a `pass:c[a\\]b]` span.\n"))
            .isEmpty();
    }

    @Test
    void spaceAdjacentPlusPair_isNotMistakenForAPassthrough() {
        // A constrained formatting pair needs non-space-adjacent delimiters, so this one is
        // a live span wearing the shape of an inert one.
        assertThat(InertSpans.scan("A `+ a +` span.\n")).hasSize(1);
    }

    @Test
    void passMacroWhoseContentEndsInABackslash_isAFinding() {
        // Not representable: `\]` is the only escape the macro grammar offers, so a trailing
        // backslash swallows the terminator. The recognizer applies Asciidoctor's own rule,
        // which is what turns an unrepresentable content into a loud failure.
        assertThat(InertSpans.scan("A `pass:c[a\\]` span.\n")).hasSize(1);
    }

    @Test
    void listingBlockContent_isSkipped() {
        assertThat(InertSpans.scan("""
            ----
            type Query { first: `N` }
            ----
            """)).isEmpty();
    }

    @Test
    void blockClosesOnlyOnItsOwnDelimiter() {
        assertThat(InertSpans.scan("""
            ----
            ------
            `still inside the listing`
            ----
            """)).isEmpty();
    }

    @Test
    void tableBlockContent_isNotSkipped() {
        // The table cell is one of the surfaces the gate exists to police, including the
        // status board's own item-id spans.
        assertThat(InertSpans.scan("""
            |===
            | `R1`
            |===
            """)).hasSize(1);
    }

    @Test
    void spanCrossingALineBreak_isNotAFinding() {
        // The pinned residual limit of a line-based converter, not a bypassed surface.
        assertThat(InertSpans.scan("""
            A span that opens `here and
            closes` on the next line.
            """)).isEmpty();
    }

    @Test
    void unclosedSpanDoesNotLeakPastTheParagraph() {
        assertThat(InertSpans.scan("""
            An opener `left hanging.

            A `live` span in the next paragraph.
            """))
            .singleElement()
            .satisfies(f -> assertThat(f.span()).isEqualTo("`live`"));
    }

    @Test
    void generatedRoadmapAdoc_carriesNoSubstitutingMonospaceSpan() throws IOException {
        Path roadmap = repoRoot().resolve("roadmap");
        List<Main.Item> items = Main.readItems(roadmap);
        assertThat(items).as("roadmap items to render").isNotEmpty();

        Map<String, String> rendered = new LinkedHashMap<>();
        ConceptIndex concepts = ConceptIndex.of(items, ConceptPages.readPages(roadmap));
        rendered.put("index.adoc", Main.renderAdocStatusBoard(items, concepts));
        rendered.put("by-theme.adoc", Main.renderAdocByTheme(items));
        rendered.put("changelog.adoc",
            Main.renderAdocChangelog(Files.readString(roadmap.resolve("changelog.md"))));
        for (Main.Item i : items) {
            rendered.put("plans/" + i.slug() + ".adoc", Main.renderAdocPlan(i));
        }

        List<String> findings = new ArrayList<>();
        rendered.forEach((page, adoc) -> InertSpans.scan(adoc)
            .forEach(f -> findings.add(page + ":" + f.line() + ": " + f.span())));

        assertThat(findings)
            .as("substituting monospace spans in generated roadmap AsciiDoc; some surface "
                + "emitted a span without routing it through InertSpans")
            .isEmpty();
    }

    /** Walks up from the module basedir to the reactor root that holds the corpus. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("CLAUDE.md"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("reactor root holding CLAUDE.md").isNotNull();
        return dir;
    }
}
