package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link JavaSourceRegions#code} projection contract. The comment and string
 * projections are pinned transitively through {@link RoadmapReferenceScannerTest}, which
 * exercises them via the scanner's two habitat scans; the code view is consumed directly by
 * the retired-vocabulary guard's reverse-enforcer, so its exclusions are pinned here.
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
        assertThat(joined("int x = 1; // mentions GhostType here"))
            .contains("int x = 1;").doesNotContain("GhostType");
    }

    @Test
    void codeView_excludesBlockCommentAndJavadocContent() {
        assertThat(joined("/** GhostType orientation. */\nclass Widget {}"))
            .doesNotContain("GhostType").contains("Widget");
    }

    @Test
    void codeView_excludesStringAndTextBlockContent() {
        String source = "String a = \"GhostType\";\nString b = \"\"\"\n  GhostType\n  \"\"\";";
        assertThat(joined(source)).doesNotContain("GhostType").contains("String a");
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
}
