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

    /**
     * The front matter every family page now opens with, asserted from the store's own rows
     * rather than from a roster restated here: the introduction where the charter used to be, a
     * start-here entry per headline carrying its grain sentence, the charter demoted under its
     * own heading, and the index blurbs reading as introductions.
     */
    @Test
    void everyFamilyPageOpensWithItsIntroductionHeadlinesAndMeetings() {
        StoreCatalog catalog;
        try (var store = GraphitronModelStore.open()) {
            catalog = StoreCatalog.read(store.dsl());
        }
        var pages = SchemaReferencePages.render(catalog);
        String index = pages.get("index.adoc");

        for (var family : catalog.families()) {
            String page = pages.get(family.prefix().replaceAll("_$", "") + ".adoc");
            assertThat(page).as("a page for " + family.prefix()).isNotNull();
            assertThat(page.indexOf(family.introduction()))
                .as(family.prefix() + " opens with its introduction, before every heading")
                .isPositive()
                .isLessThan(page.indexOf("== Where to start"));
            assertThat(page).as(family.prefix() + " demotes its charter under its own heading")
                .contains("== Why the name is right\n\n" + family.definition());
            assertThat(page.indexOf("== Where to start"))
                .as(family.prefix() + " starts the reader before telling them how it meets others")
                .isLessThan(page.indexOf("== How this family meets the others"));
            assertThat(index).as("the index blurb for " + family.prefix() + " is its introduction")
                .contains(family.introduction())
                .doesNotContain(family.definition());
        }

        for (var headline : catalog.headlines()) {
            String page = pages.get(headline.familyPrefix().replaceAll("_$", "") + ".adoc");
            String start = page.substring(page.indexOf("== Where to start"),
                page.indexOf("== How this family meets the others"));
            assertThat(start).as(headline.relationName() + " is linked from where to start")
                .contains("#" + headline.relationName() + "[" + headline.relationName() + "]::");
        }

        for (var bridge : catalog.bridges()) {
            for (String prefix : List.of(bridge.spelledPrefix(), bridge.censusPrefix())) {
                assertThat(pages.get(prefix.replaceAll("_$", "") + ".adoc"))
                    .as(prefix + " states the crossing " + bridge.relationName() + " owns")
                    .contains(bridge.rule());
            }
        }
    }

    @Test
    void aFamilyWithNoIntroductionFailsTheFloor() {
        assertThatThrownBy(() -> SchemaReferencePages.render(
            catalogOf(new StoreCatalog.Family("x_", "X", 0, " ", "The x_ family."),
                List.of(new StoreCatalog.Headline("x_thing", "x_", 0)))))
            .isInstanceOf(BuildFailure.class)
            .hasMessageContaining("no introduction");
    }

    @Test
    void aFamilyWithNoHeadlineFailsTheFloor() {
        assertThatThrownBy(() -> SchemaReferencePages.render(catalogOf(family(), List.of())))
            .isInstanceOf(BuildFailure.class)
            .hasMessageContaining("no headline relations");
    }

    @Test
    void aHeadlineOnNoPageFailsTheFloor() {
        assertThatThrownBy(() -> SchemaReferencePages.render(
            catalogOf(family(), List.of(new StoreCatalog.Headline("x_thing", "x_", 0),
                new StoreCatalog.Headline("x_gone", "x_", 1)))))
            .isInstanceOf(BuildFailure.class)
            .hasMessageContaining("renders on no page");
    }

    private static StoreCatalog.Family family() {
        return new StoreCatalog.Family("x_", "X", 0, "The x_ rows.", "The x_ family.");
    }

    private static StoreCatalog catalogOf(StoreCatalog.Family family,
                                          List<StoreCatalog.Headline> headlines) {
        var relation = new StoreCatalog.Relation("x_thing", true, "A thing exists.",
            Optional.of("x_"), false, List.of(), List.of(), List.of(), List.of());
        return new StoreCatalog(List.of(family), List.of(), List.of(relation), headlines,
            List.of(), List.of());
    }

    @Test
    void anEmptyCatalogFailsTheFloor() {
        assertThatThrownBy(() -> SchemaReferencePages.render(
            new StoreCatalog(List.of(), List.of(), List.of(), List.of(), List.of(), List.of())))
            .isInstanceOf(BuildFailure.class)
            .hasMessageContaining("empty catalog");
    }

    @Test
    void aBlankCommentFailsTheFloor() {
        var relation = new StoreCatalog.Relation("x_thing", false, " ",
            Optional.of("x_"), false, List.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> SchemaReferencePages.render(
            new StoreCatalog(List.of(family()), List.of(), List.of(relation), List.of(),
                List.of(), List.of())))
            .isInstanceOf(BuildFailure.class)
            .hasMessageContaining("blank comment");
    }

    @Test
    void aRelationOnNoPageFailsTheFloor() {
        var orphan = new StoreCatalog.Relation("stray", true, "A stray.",
            Optional.empty(), false, List.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> SchemaReferencePages.render(
            new StoreCatalog(List.of(family()), List.of(), List.of(orphan), List.of(),
                List.of(), List.of())))
            .isInstanceOf(BuildFailure.class)
            .hasMessageContaining("no documentation page");
    }

    @Test
    void anExemptionNamingNoFamilyPageFailsTheFloor() {
        var exemption = new StoreCatalog.Exemption("stray", Optional.of("y_"), "Because.");
        var stray = new StoreCatalog.Relation("stray", true, "A stray.",
            Optional.empty(), true, List.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> SchemaReferencePages.render(
            new StoreCatalog(List.of(family()), List.of(exemption), List.of(stray), List.of(),
                List.of(), List.of())))
            .isInstanceOf(BuildFailure.class)
            .hasMessageContaining("no family");
    }

    private static int occurrences(String haystack, String needle) {
        return (int) Pattern.compile(Pattern.quote(needle)).matcher(haystack).results().count();
    }
}
