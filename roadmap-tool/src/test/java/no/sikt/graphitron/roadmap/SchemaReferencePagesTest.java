package no.sikt.graphitron.roadmap;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.catalog.StoreCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the schema-reference renderer to the real store and pins its floors to the failure they
 * exist for. The reality test renders the live catalog, so the counts and coverage assertions
 * derive from the store itself rather than restating a roster this test would then fall behind;
 * the floor tests feed synthetic catalogs, because the live DDL (guarded by the schema gates)
 * can no longer exhibit the defects the floors catch.
 */
class SchemaReferencePagesTest {

    @Test
    void rendersEveryRelationOfTheLiveCatalogExactlyOnce() {
        StoreCatalog catalog;
        try (var store = GraphitronModelStore.open()) {
            catalog = StoreCatalog.read(store.dsl());
        }
        assertThat(catalog.relations()).as("relations to render").isNotEmpty();

        var pages = SchemaReferencePages.render(catalog);

        assertThat(pages).as("one page per family row plus the index")
            .hasSize(catalog.families().size() + 1);
        assertThat(pages).containsKey("index.adoc");
        String all = String.join("\n", pages.values());
        for (var relation : catalog.relations()) {
            assertThat(occurrences(all, "[#" + relation.relationName() + "]"))
                .as("anchors for " + relation.relationName())
                .isEqualTo(1);
        }
        for (var family : catalog.families()) {
            assertThat(pages.values().stream()
                .filter(page -> page.contains("= " + family.title())).count())
                .as("a page titled for " + family.prefix())
                .isPositive();
        }
        // The prose really interpolates: a known comment fragment surfaces verbatim.
        assertThat(all).contains("the owning graph's partition");
        // Rendering is a pure function of the catalog.
        assertThat(SchemaReferencePages.render(catalog)).isEqualTo(pages);
    }

    @Test
    void anEmptyCatalogFailsTheFloor() {
        assertThatThrownBy(() -> SchemaReferencePages.render(
            new StoreCatalog(List.of(), List.of(), List.of())))
            .isInstanceOf(BuildFailure.class)
            .hasMessageContaining("empty catalog");
    }

    @Test
    void aBlankCommentFailsTheFloor() {
        var family = new StoreCatalog.Family("x_", "X", 0, "The x_ family.");
        var relation = new StoreCatalog.Relation("x_thing", false, " ",
            Optional.of("x_"), false, List.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> SchemaReferencePages.render(
            new StoreCatalog(List.of(family), List.of(), List.of(relation))))
            .isInstanceOf(BuildFailure.class)
            .hasMessageContaining("blank comment");
    }

    @Test
    void aRelationOnNoPageFailsTheFloor() {
        var family = new StoreCatalog.Family("x_", "X", 0, "The x_ family.");
        var orphan = new StoreCatalog.Relation("stray", true, "A stray.",
            Optional.empty(), false, List.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> SchemaReferencePages.render(
            new StoreCatalog(List.of(family), List.of(), List.of(orphan))))
            .isInstanceOf(BuildFailure.class)
            .hasMessageContaining("no documentation page");
    }

    @Test
    void anExemptionNamingNoFamilyPageFailsTheFloor() {
        var family = new StoreCatalog.Family("x_", "X", 0, "The x_ family.");
        var exemption = new StoreCatalog.Exemption("stray", Optional.of("y_"), "Because.");
        var stray = new StoreCatalog.Relation("stray", true, "A stray.",
            Optional.empty(), true, List.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> SchemaReferencePages.render(
            new StoreCatalog(List.of(family), List.of(exemption), List.of(stray))))
            .isInstanceOf(BuildFailure.class)
            .hasMessageContaining("no family");
    }

    private static int occurrences(String haystack, String needle) {
        return (int) Pattern.compile(Pattern.quote(needle)).matcher(haystack).results().count();
    }
}
