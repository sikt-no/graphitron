package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the detection contract of {@link RoadmapReferenceScanner}, one projection per habitat.
 * The comment scan ({@link RoadmapReferenceScanner#scanSource}) flags a citation in a comment
 * or javadoc region and ignores string literals; the string-literal scan
 * ({@link RoadmapReferenceScanner#scanSourceStrings}) is its mirror, flagging a citation inside a
 * string, character, or text-block literal and ignoring comments. Permanent-artifact slugs are
 * allowlisted in both, and a line comment ends at its newline.
 *
 * <p>Every fixture is an in-memory source string, so this test carries no citation of its own in
 * a scanned region; the tokens under test live inside the fixture strings.
 */
@UnitTier
class RoadmapReferenceScannerTest {

    private static final Path F = Path.of("Fixture.java");

    private static int count(String source) {
        return RoadmapReferenceScanner.scanSource(F, source).size();
    }

    private static int countStrings(String source) {
        return RoadmapReferenceScanner.scanSourceStrings(F, source).size();
    }

    @Test
    void javadocCitation_isFlagged() {
        assertThat(count("/**\n * Introduced by R482 originally.\n */\nclass X {}")).isEqualTo(1);
    }

    @Test
    void lineCommentCitation_isFlagged() {
        assertThat(count("class X {\n  // keyed per R123 rule\n  int y;\n}")).isEqualTo(1);
    }

    @Test
    void trailingCommentCitation_isFlagged() {
        assertThat(count("int x = 42; // per R99 this is fine")).isEqualTo(1);
    }

    @Test
    void stringLiteralCitation_isNotFlaggedByCommentScan() {
        // The comment scan ignores string literals; the string scan owns that habitat.
        assertThat(count("String s = \"see R484 for details\";")).isZero();
    }

    @Test
    void textBlockCitation_isNotFlaggedByCommentScan() {
        assertThat(count("String s = \"\"\"\n  rejected: see R484\n  \"\"\";")).isZero();
    }

    @Test
    void stringLiteralCitation_isFlaggedByStringScan() {
        assertThat(countStrings("String s = \"see R484 for details\";")).isEqualTo(1);
    }

    @Test
    void textBlockCitation_isFlaggedByStringScan() {
        assertThat(countStrings("String s = \"\"\"\n  rejected: see R484\n  \"\"\";")).isEqualTo(1);
    }

    @Test
    void stringSlugCitation_isFlaggedByStringScan() {
        assertThat(countStrings("String s = \"see roadmap/some-transient-slug.md\";")).isEqualTo(1);
    }

    @Test
    void allowlistedSlugInString_isNotFlaggedByStringScan() {
        assertThat(countStrings("String s = \"recorded in roadmap/changelog.md\";")).isZero();
    }

    @Test
    void commentCitation_isNotFlaggedByStringScan() {
        // The mirror of the comment-scan cases: the string scan ignores comment regions.
        assertThat(countStrings("class X {\n  // keyed per R123 rule\n  int y;\n}")).isZero();
    }

    @Test
    void stringScan_reportsCitationLineNumber() {
        List<RoadmapReferenceScanner.Finding> f = RoadmapReferenceScanner.scanSourceStrings(F,
            "class X {\n  String a = \"clean\";\n  String b = \"deferred under R145\";\n}");
        assertThat(f).hasSize(1);
        assertThat(f.get(0).line()).isEqualTo(3);
    }

    @Test
    void allowlistedSlug_isNotFlagged() {
        assertThat(count("// appended to roadmap/changelog.md on landing")).isZero();
        assertThat(count("// recorded in roadmap/changelog.md.")).isZero();
    }

    @Test
    void transientSlug_isFlagged() {
        assertThat(count("// see roadmap/some-transient-slug.md for context")).isEqualTo(1);
    }

    @Test
    void cleanComment_isNotFlagged() {
        assertThat(count("/** A frozen projection with no citations. */\nclass X {}")).isZero();
    }

    @Test
    void lineComment_endsAtNewline() {
        // Regression pin: a line comment must reset at its newline, so a later code line whose
        // string literal happens to hold an id is not mis-scanned as comment text.
        String source = "class X {\n"
            + "  // an ordinary comment\n"
            + "  String msg = \"rejected under R484\";\n"
            + "}";
        assertThat(count(source)).isZero();
    }

    @Test
    void multilineJavadoc_reportsCitationLineNumber() {
        List<RoadmapReferenceScanner.Finding> f = RoadmapReferenceScanner.scanSource(F,
            "/**\n * Clean summary line.\n * Carved off under R266 here.\n */");
        assertThat(f).hasSize(1);
        assertThat(f.get(0).line()).isEqualTo(3);
    }

    @Test
    void plantedReintroduction_isCaught() {
        // The guard's reason for existing: a newly added javadoc that leans on a roadmap id is a
        // finding, so the build fails on reintroduction.
        String reintroduced = "/**\n"
            + " * Resolves the carrier shape. R500 folds the new arrival case into this path.\n"
            + " */\n"
            + "class NewlyAdded {}";
        assertThat(count(reintroduced)).isEqualTo(1);
    }
}
