package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan pages live one directory below the status board (at {@code roadmap/plans/<slug>.html}),
 * so cross-plan links from inside a plan must be slug-relative; a {@code plans/} prefix
 * would produce {@code roadmap/plans/plans/<slug>.html} (404). The status board and
 * by-theme pages are at the roadmap root and need the prefix.
 */
class AdocLinkPrefixTest {

    @Test
    void planPage_blockedByLinks_omitPlansPrefix() {
        Main.Item dependee = item("retire-rust-lsp-and-introspect-mojo", List.of());
        Main.Item dependant = item("retire-maven-plugin", List.of(dependee.slug()));

        String rendered = Main.renderAdocPlan(dependant);

        assertThat(rendered).contains(
            "| Blocked by | xref:retire-rust-lsp-and-introspect-mojo.adoc[retire-rust-lsp-and-introspect-mojo]");
        assertThat(rendered).doesNotContain("xref:plans/");
    }

    @Test
    void statusBoard_blockedByLinks_keepPlansPrefix() {
        Main.Item dependee = item("retire-rust-lsp-and-introspect-mojo", List.of());
        Main.Item dependant = item("retire-maven-plugin", List.of(dependee.slug()));

        String rendered = Main.renderAdocStatusBoard(List.of(dependant, dependee));

        assertThat(rendered).contains("xref:plans/retire-rust-lsp-and-introspect-mojo.adoc");
    }

    private static Main.Item item(String slug, List<String> dependsOn) {
        return new Main.Item(
            slug, "R" + Math.abs(slug.hashCode() % 1000), slug,
            "In Progress", null, null, false, null, null, dependsOn,
            null, null, "");
    }
}
