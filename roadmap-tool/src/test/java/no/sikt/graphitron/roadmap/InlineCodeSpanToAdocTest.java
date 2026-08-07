package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A markdown code span is literal by definition; a single-backtick AsciiDoc span is not,
 * because it applies the normal substitution group with macros included. These tests pin the
 * conversion that closes the gap: every markdown-sourced span reaches the page in an inert
 * form, and the prose transforms stop at the span boundary in both directions.
 *
 * <p>Same shape as {@link MdTableToAdocTest}: real body shapes through
 * {@link Main#mdBodyToAdoc}, asserted against the emitted AsciiDoc.
 */
class InlineCodeSpanToAdocTest {

    private static String plan(String md) {
        return Main.mdBodyToAdoc(md, Main.ChangelogContext.PLAN);
    }

    @Test
    void backtickedXref_emitsThePlusDelimitedForm() {
        // The half of the class the docs-profile WARN gate misses entirely: a live cross-file
        // xref renders silently, so only the emitted form tells you it was quoted.
        assertThat(plan("Quoting `xref:index.adoc[Roadmap]` in prose.\n"))
            .contains("`+xref:index.adoc[Roadmap]+`");
    }

    @Test
    void backtickedAttributeReference_emitsThePlusDelimitedForm() {
        assertThat(plan("The `{argLine}` property.\n")).contains("`+{argLine}+`");
    }

    @Test
    void markdownLinkInsideASpan_isNotRewritten_whileOneOutsideStillIs() {
        String adoc = plan("Quoting `[other](other.md)` beside [other](other.md).\n");
        assertThat(adoc).contains("`+[other](other.md)+`");
        assertThat(adoc).contains("xref:other.adoc[other]");
    }

    @Test
    void emDashInsideASpan_isPreserved_whileOneOutsideIsSwept() {
        String adoc = plan("A `a \u2014 b` span beside c \u2014 d.\n");
        assertThat(adoc).contains("`+a \u2014 b+`");
        assertThat(adoc).contains("c ; d");
    }

    @Test
    void spanContentCarryingAPlus_fallsBackToThePassMacro() {
        assertThat(plan("The `a + b` form.\n")).contains("`pass:c[a + b]`");
    }

    @Test
    void spanContentCarryingABracket_escapesItInThePassMacro() {
        assertThat(plan("The `a + b]` form.\n")).contains("`pass:c[a + b\\]]`");
    }

    @Test
    void quotedPassthroughBlockDelimiter_survivesThePassMacro() {
        // The changelog quotes the four-plus passthrough block delimiter. Emitted as a bare
        // span it parses as an empty unconstrained passthrough pair inside a monospace span,
        // so the published page shows an empty code span where the delimiter should be.
        assertThat(plan("The `++++` delimiter.\n")).contains("`pass:c[++++]`");
    }

    @Test
    void doubleBacktickSpans_stripThePadAndRouteToThePassMacro() {
        // The three double-backtick spans in the corpus, all carrying backticks in their
        // content. The pad is delimiter syntax rather than content, and backtick content
        // cannot sit inside a single-backtick wrapper at all.
        assertThat(plan("A `` `Map<K|V>` `` type.\n"))
            .contains("`pass:c[`Map<K|V>`]`");
        assertThat(plan("The `` `@table` + `@node` | `NodeType` `` row.\n"))
            .contains("`pass:c[`@table` + `@node` | `NodeType`]`");
        assertThat(plan("A deliberate `` `Field` `` symbol reference.\n"))
            .contains("`pass:c[`Field`]`");
    }

    @Test
    void spanWhoseContentIsAllSpaces_keepsThePadUnstripped() {
        // CommonMark strips the pad only from content that is not entirely spaces.
        assertThat(plan("A ``  `` span.\n")).contains("`pass:c[  ]`");
    }

    @Test
    void spanCrossingALineBreak_staysABareBacktickPair() {
        // The pinned residual limit: the converter is line-based and stays so, and this is
        // the one remnant of cross-span fusing that per-span passthrough cannot kill.
        assertThat(plan("""
            A span that opens `here and
            closes` on the next line.
            """))
            .contains("`here and\ncloses`");
    }

    @Test
    void lineClosingOneSpanAndOpeningAnother_pairsTheRightBackticks() {
        // Without the carried-open delimiter the converter would pair the closing backtick
        // with the next span's opening one and wrap the prose between them.
        assertThat(plan("""
            the same components (`parentTypeName,
            BatchKeyField` (`ChildField.java:446` and `:798`).
            """))
            .contains("BatchKeyField` (`+ChildField.java:446+` and `+:798+`).");
    }

    @Test
    void boldWrappingASpan_stillConverts() {
        // Spans are held back from the prose transforms rather than split out of the text,
        // so a construct straddling one still matches its pattern.
        assertThat(plan("**Retire `@nodeId` shims**\n"))
            .contains("*Retire `+@nodeId+` shims*");
    }

    @Test
    void linkLabelCarryingASpan_usesTheAttrlistSafeForm() {
        assertThat(plan("See [`changelog.md`](changelog.md) for what shipped.\n"))
            .contains("xref:../changelog.adoc[`+changelog.md+`]");
    }

    @Test
    void tableCellSpan_composesWithTheWholeCellPipeEscape() {
        assertThat(plan("""
            | Kind | Type |
            |---|---|
            | map | `Map<K|V>` |
            """))
            .contains("`+Map<K\\|V>+`");
    }

    @Test
    void headingSpan_converts_andHeadingsGainTheEmDashSweep() {
        assertThat(plan("## The `@node` trigger\n")).contains("== The `+@node+` trigger");
        assertThat(plan("## a \u2014 b\n")).contains("== a ; b");
    }

    @Test
    void fenceContent_isUntouched() {
        // Fenced lines copy verbatim into a listing block, where backticks are inert by
        // block context rather than by span form.
        assertThat(plan("""
            ```graphql
            type Query { first: `N` }
            ```
            """))
            .contains("type Query { first: `N` }");
    }

    @Test
    void titleLabel_goesInertOnlyWhenTheAttrlistCanCarryIt() {
        // A `]` terminates the attrlist lexically, before inline substitutions run.
        assertThat(Main.titleLabel("Retire `@nodeId`")).isEqualTo("Retire `+@nodeId+`");
        assertThat(Main.titleLabel("Retire `a]b`")).isEqualTo("Retire `a]b`");
        assertThat(Main.titleLabel("Retire `a + b`")).isEqualTo("Retire `a + b`");
    }

    @Test
    void titleLabel_bareResidue_failsTheCorpusGate() {
        // The other half of the label emitter's contract: staying bare is only safe because
        // the gate then fails loudly on it, which is what tells the author to rephrase the
        // title rather than shipping a substituting span silently.
        String line = "* xref:plans/x.adoc[" + Main.titleLabel("Retire `a]b`") + "]\n";
        assertThat(InertSpans.scan(line))
            .singleElement()
            .satisfies(f -> assertThat(f.span()).isEqualTo("`a]b`"));
    }

    @Test
    void titleLabel_leavesPipesUnescaped() {
        // It emits onto list lines, where no table parser consumes a backslash.
        assertThat(Main.titleLabel("Carry `a|b`")).isEqualTo("Carry `+a|b+`");
    }

    @Test
    void titleCell_usesTheFullProducerAndKeepsThePipeEscape() {
        assertThat(Main.titleCell("Carry `a|b`")).isEqualTo("Carry `+a\\|b+`");
        assertThat(Main.titleCell("Carry `a + b`")).isEqualTo("Carry `pass:c[a + b]`");
    }
}
