package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the markdown-link → AsciiDoc rewriting for every target kind the
 * converter distinguishes, in both {@link Main.ChangelogContext}s. These
 * assertions were captured against the pre-R486 inline {@code mapAdocTarget}
 * before the {@link LinkTarget} classifier extraction, so they pin
 * pre-refactor behavior rather than ratifying whatever the refactor emits.
 * The classifier feeds two emitters (this adoc direction and the concepts-page
 * href direction in {@link ConceptPages}); this table is the adoc half of the
 * round-trip that proves both directions read one classification.
 */
class LinkTargetRoundTripTest {

    private static String plan(String md) {
        return Main.mdBodyToAdoc(md, Main.ChangelogContext.PLAN);
    }

    private static String standalone(String md) {
        return Main.mdBodyToAdoc(md, Main.ChangelogContext.STANDALONE);
    }

    @Test
    void externalUrl_becomesBareUrlMacro() {
        assertThat(plan("[site](https://example.com/x)"))
            .contains("https://example.com/x[site]");
        assertThat(standalone("[site](https://example.com/x)"))
            .contains("https://example.com/x[site]");
        assertThat(plan("[site](https://example.com/x#frag)"))
            .contains("https://example.com/x#frag[site]");
    }

    @Test
    void samePageAnchor_becomesInternalCrossReference() {
        assertThat(plan("[section](#the-anchor)"))
            .contains("<<the-anchor,section>>");
        assertThat(standalone("[section](#the-anchor)"))
            .contains("<<the-anchor,section>>");
    }

    @Test
    void siblingItem_planIsSlugRelative_standaloneGetsPlansPrefix() {
        assertThat(plan("[other](other-item.md)"))
            .contains("xref:other-item.adoc[other]");
        assertThat(standalone("[other](other-item.md)"))
            .contains("xref:plans/other-item.adoc[other]");
        assertThat(plan("[other](other-item.md#part)"))
            .contains("xref:other-item.adoc#part[other]");
    }

    @Test
    void readme_mapsToIndex() {
        assertThat(plan("[roadmap](README.md)"))
            .contains("xref:../index.adoc[roadmap]");
        assertThat(standalone("[roadmap](README.md)"))
            .contains("xref:index.adoc[roadmap]");
    }

    @Test
    void changelog_mapsToChangelogAdoc() {
        assertThat(plan("[done](changelog.md)"))
            .contains("xref:../changelog.adoc[done]");
        assertThat(standalone("[done](changelog.md)"))
            .contains("xref:changelog.adoc[done]");
    }

    @Test
    void workflow_redirectsToRoadmapSibling() {
        assertThat(plan("[workflow](../docs/workflow.adoc)"))
            .contains("xref:../workflow.adoc[workflow]");
        assertThat(standalone("[workflow](../docs/workflow.adoc)"))
            .contains("xref:workflow.adoc[workflow]");
    }

    @Test
    void architectureDoc_quadrantMapped() {
        assertThat(plan("[principles](../docs/development-principles.adoc)"))
            .contains("xref:../../architecture/explanation/development-principles.adoc[principles]");
        assertThat(standalone("[principles](../docs/development-principles.adoc)"))
            .contains("xref:../architecture/explanation/development-principles.adoc[principles]");
        // .md straggler resolves the same way.
        assertThat(plan("[triggers](../docs/code-generation-triggers.md)"))
            .contains("xref:../../architecture/reference/code-generation-triggers.adoc[triggers]");
    }

    @Test
    void architectureDoc_slugAbsentFromQuadrantMap_rendersFlat() {
        assertThat(plan("[arch](../docs/index.adoc)"))
            .contains("xref:../../architecture/index.adoc[arch]");
        assertThat(standalone("[arch](../docs/index.adoc)"))
            .contains("xref:../architecture/index.adoc[arch]");
    }

    @Test
    void topLevelDoc_mapsAboveRoadmap() {
        assertThat(plan("[notes](../../docs/notes.md)"))
            .contains("xref:../../notes.adoc[notes]");
        assertThat(standalone("[notes](../../docs/notes.md)"))
            .contains("xref:../notes.adoc[notes]");
    }

    @Test
    void legacyModulePath_becomesGithubUrlOnMain() {
        assertThat(plan("[legacy](../../graphitron-common/src/Foo.java)"))
            .contains("https://github.com/sikt-no/graphitron/tree/main/graphitron-common/src/Foo.java[legacy]");
        assertThat(standalone("[legacy](../../graphitron-common/src/Foo.java)"))
            .contains("https://github.com/sikt-no/graphitron/tree/main/graphitron-common/src/Foo.java[legacy]");
    }

    @Test
    void webEnvironmentDoc_redirectsToDotClaudeOnRewriteBranch() {
        assertThat(plan("[env](../claude-code-web-environment.md)"))
            .contains("https://github.com/sikt-no/graphitron/blob/claude/graphitron-rewrite/.claude/web-environment.md[env]");
        assertThat(standalone("[env](../claude-code-web-environment.md)"))
            .contains("https://github.com/sikt-no/graphitron/blob/claude/graphitron-rewrite/.claude/web-environment.md[env]");
    }

    @Test
    void deepDocsPath_passesThroughAsLinkMacro() {
        // A full docs/manual/** or docs/architecture/** path does not match the
        // flat legacy patterns; it passes through as a link: macro unchanged.
        assertThat(plan("[ref](../docs/manual/reference/directives/reference.adoc)"))
            .contains("link:../docs/manual/reference/directives/reference.adoc[ref]");
        assertThat(standalone("[dispatch](../docs/architecture/explanation/dispatch-axes.adoc)"))
            .contains("link:../docs/architecture/explanation/dispatch-axes.adoc[dispatch]");
    }

    @Test
    void unknownTarget_passesThroughAsLinkMacro() {
        assertThat(plan("[misc](../notes/thing.txt)"))
            .contains("link:../notes/thing.txt[misc]");
        assertThat(standalone("[misc](../notes/thing.txt)"))
            .contains("link:../notes/thing.txt[misc]");
    }
}
