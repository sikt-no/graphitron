package no.sikt.graphitron.roadmap;

import no.sikt.graphitron.roadmap.ConceptPages.ConceptPage;
import no.sikt.graphitron.roadmap.Main.Item;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The one place the item-to-concept relation is resolved (R488). Concept pages
 * declare the roadmap item(s) they back via {@code data-concept-items}; this
 * index joins that declaration against the live item files once, so render
 * sites consume a resolved outcome and never re-derive liveness. This mirrors
 * the {@link ConceptPages#mapHref} discipline, where the shipped-vs-live
 * question is answered in one place for hrefs.
 *
 * <p>Two views come out of the join:
 * <ul>
 *   <li><b>Item side (item to explainer).</b> A reverse index from item id to
 *       the slug-sorted explainer slugs that back it, driving the
 *       {@code explainer} links that follow an item's plan link in the README
 *       and status board. Only live anchors contribute: a shipped item is no
 *       longer listed, so it gets no listing link.</li>
 *   <li><b>Concept side (explainer to item).</b> Each page's declared ids
 *       resolved, in declared order, to {@link ItemAnchor} outcomes, driving
 *       the {@code (backs ...)} annotation on the Concept explainers listings:
 *       a {@link Live} anchor links to the item's plan, a {@link Shipped} one
 *       renders the id as plain text.</li>
 * </ul>
 */
final class ConceptIndex {

    /** Resolution of one declared item id: the item file is live or shipped (deleted on Done). */
    sealed interface ItemAnchor permits Live, Shipped {}

    /** The backed item still has a file in the roadmap; listing links resolve to its plan. */
    record Live(String itemId, String itemSlug) implements ItemAnchor {}

    /** The backed item shipped (or was discarded); its file is gone, so no listing link. */
    record Shipped(String itemId) implements ItemAnchor {}

    private final Map<String, ConceptPage> pages;
    private final Map<String, List<String>> explainersByItem;
    private final Map<String, List<ItemAnchor>> anchorsByPage;

    private ConceptIndex(Map<String, ConceptPage> pages,
                         Map<String, List<String>> explainersByItem,
                         Map<String, List<ItemAnchor>> anchorsByPage) {
        this.pages = pages;
        this.explainersByItem = explainersByItem;
        this.anchorsByPage = anchorsByPage;
    }

    static ConceptIndex empty() {
        return new ConceptIndex(Map.of(), Map.of(), Map.of());
    }

    /**
     * Builds the index by resolving every page's declared item ids against the
     * live item set. {@code pages} is expected slug-sorted (as {@link
     * ConceptPages#readPages} returns it), so the per-item explainer lists come
     * out in slug order; they are re-sorted defensively regardless.
     */
    static ConceptIndex of(List<Item> items, Map<String, ConceptPage> pages) {
        Map<String, String> liveSlugById = new HashMap<>();
        for (Item i : items) {
            if (i.id() != null) {
                liveSlugById.put(i.id(), i.slug());
            }
        }
        Map<String, List<ItemAnchor>> anchorsByPage = new TreeMap<>();
        Map<String, List<String>> explainersByItem = new TreeMap<>();
        for (ConceptPage page : pages.values()) {
            List<ItemAnchor> anchors = new ArrayList<>();
            for (String id : page.itemIds()) {
                String slug = liveSlugById.get(id);
                if (slug != null) {
                    anchors.add(new Live(id, slug));
                    explainersByItem.computeIfAbsent(id, k -> new ArrayList<>()).add(page.slug());
                } else {
                    anchors.add(new Shipped(id));
                }
            }
            anchorsByPage.put(page.slug(), List.copyOf(anchors));
        }
        explainersByItem.replaceAll((id, slugs) -> {
            slugs.sort(Comparator.naturalOrder());
            return List.copyOf(slugs);
        });
        return new ConceptIndex(pages, explainersByItem, anchorsByPage);
    }

    boolean isEmpty() {
        return pages.isEmpty();
    }

    /** Slug → {@link ConceptPage}, slug-sorted; the concept-side listing iterates this. */
    Map<String, ConceptPage> pages() {
        return pages;
    }

    /** Slug-sorted explainer slugs backing {@code itemId}, empty if none; the item-side links read this. */
    List<String> explainerSlugsFor(String itemId) {
        return explainersByItem.getOrDefault(itemId, List.of());
    }

    /** The page's declared ids resolved to anchors in declared order; the concept-side annotation reads this. */
    List<ItemAnchor> anchorsFor(String slug) {
        return anchorsByPage.getOrDefault(slug, List.of());
    }
}
