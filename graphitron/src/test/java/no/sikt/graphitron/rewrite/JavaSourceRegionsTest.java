package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link JavaSourceRegions#code} and {@link JavaSourceRegions#literalsByLine} projection
 * contracts. The comment projection and the concatenated {@link JavaSourceRegions#strings} view are
 * pinned transitively through {@link RoadmapReferenceScannerTest}, which exercises them via the
 * scanner's two habitat scans; the code view is consumed directly by the retired-vocabulary
 * guard's reverse-enforcer, and the per-literal view by the roadmap-consumer guard's whole-literal
 * rule, so both are pinned here.
 */
@UnitTier
class JavaSourceRegionsTest {

    private static String joined(String source) {
        return String.join("\n", JavaSourceRegions.code(source));
    }

    @Test
    void codeView_keepsCodeIdentifiers() {
        assertThat(joined("class Widget { int spanCount; }"))
            .contains("Widget").contains("spanCount");
    }

    @Test
    void codeView_excludesLineCommentContent() {
        assertThat(joined("int x = 1; // mentions MissingType here"))
            .contains("int x = 1;").doesNotContain("MissingType");
    }

    @Test
    void codeView_excludesBlockCommentAndJavadocContent() {
        assertThat(joined("/** MissingType orientation. */\nclass Widget {}"))
            .doesNotContain("MissingType").contains("Widget");
    }

    @Test
    void codeView_excludesStringAndTextBlockContent() {
        String source = "String a = \"MissingType\";\nString b = \"\"\"\n  MissingType\n  \"\"\";";
        assertThat(joined(source)).doesNotContain("MissingType").contains("String a");
    }

    @Test
    void codeView_resumesAfterTextBlock() {
        String source = "String b = \"\"\"\n  quoted\n  \"\"\";\nint after = 2;";
        assertThat(joined(source)).contains("int after = 2;").doesNotContain("quoted");
    }

    @Test
    void codeView_divisionOperatorIsNotACommentStart() {
        assertThat(joined("int r = total / parts;")).contains("total / parts");
    }

    @Test
    void codeView_lineNumbersAlignWithSource() {
        String[] byLine = JavaSourceRegions.code("/* lead */\nint x;\n// tail\nint y;");
        assertThat(byLine[0]).doesNotContain("lead");
        assertThat(byLine[1]).contains("int x;");
        assertThat(byLine[2]).isEmpty();
        assertThat(byLine[3]).contains("int y;");
    }

    /**
     * The difference between the two literal granularities, and the reason the per-literal view
     * exists: the concatenated view renders two adjacent literals as one run in which neither
     * original value survives and a third, spurious one appears.
     */
    @Test
    void literalView_keepsAdjacentLiteralsApartWhereTheConcatenatedViewCannot() {
        String source = "var s = Set.of(\"alpha/one\", \"beta/two\");";

        assertThat(JavaSourceRegions.strings(source)[0]).isEqualTo("alpha/onebeta/two");
        assertThat(JavaSourceRegions.literalsByLine(source).getFirst())
            .containsExactly("alpha/one", "beta/two");
    }

    @Test
    void literalView_excludesCommentAndCodeContent() {
        String source = "var s = \"kept\"; // dropped\nint x; /* dropped */";
        var byLine = JavaSourceRegions.literalsByLine(source);

        assertThat(byLine.get(0)).containsExactly("kept");
        assertThat(byLine.get(1)).isEmpty();
    }

    @Test
    void literalView_reportsEmptyForALineWithNoLiterals() {
        assertThat(JavaSourceRegions.literalsByLine("int x = 1;").getFirst()).isEmpty();
    }

    @Test
    void literalView_dropsEmptyLiterals() {
        assertThat(JavaSourceRegions.literalsByLine("var s = \"\" + \"kept\";").getFirst())
            .containsExactly("kept");
    }

    @Test
    void literalView_keepsCharacterLiteralsSeparate() {
        assertThat(JavaSourceRegions.literalsByLine("var s = \"ab\" + 'c' + \"de\";").getFirst())
            .containsExactly("ab", "c", "de");
    }

    @Test
    void literalView_lineNumbersAlignWithSource() {
        var byLine = JavaSourceRegions.literalsByLine("int x;\nvar s = \"here\";\nint y;");

        assertThat(byLine.get(0)).isEmpty();
        assertThat(byLine.get(1)).containsExactly("here");
        assertThat(byLine.get(2)).isEmpty();
    }
}
