package no.sikt.graphitron.roadmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Concept explainer pages (R486): interactive, intuition-first HTML pages at
 * {@code roadmap/concepts/<slug>.html}, plus the shared assets
 * {@code concepts.css} / {@code concepts.js}. This class owns everything the
 * tool does with them:
 *
 * <ul>
 *   <li>the title contract: each page's {@code <h1>} carries
 *       {@code data-concept-title="<plain-text title>"}, and listings derive
 *       from that attribute; a missing or blank value fails the build;</li>
 *   <li>the items contract (R488): the same {@code <h1>} carries
 *       {@code data-concept-items="R<n>[, R<m>...]"} naming the roadmap
 *       item(s) the concept backs; a missing, blank, or malformed value, an
 *       id that was never allocated, or a declared id absent from the header
 *       {@code kicker} line all fail the build naming the file;</li>
 *   <li>the staging step for {@code render-adoc}: pages are copied into the
 *       site output with {@code href} values rewritten from the repo layout to
 *       the rendered-site layout (page content is otherwise untouched);
 *       assets are copied byte-for-byte.</li>
 * </ul>
 *
 * <p>Authors write repo-relative hrefs, natural for the authoring LLM and
 * consistent with item bodies, and working in local {@code file://} preview.
 * The href emitter here and the AsciiDoc emitter in {@link Main} both format
 * from {@link LinkTarget#classify}, so the target taxonomy lives in one place.
 */
final class ConceptPages {

    private ConceptPages() {}

    private static final Pattern TITLE_ATTR = Pattern.compile("data-concept-title=\"([^\"]*)\"");
    private static final Pattern ITEMS_ATTR = Pattern.compile("data-concept-items=\"([^\"]*)\"");
    private static final Pattern HREF_ATTR = Pattern.compile("href=\"([^\"]*)\"");
    /** A single well-formed roadmap id token: {@code R} then a positive integer with no leading zero. */
    private static final Pattern ID_TOKEN = Pattern.compile("R[1-9][0-9]*");
    /** Inner text of the first {@code class="kicker"} element (the header kicker). */
    private static final Pattern KICKER = Pattern.compile("class=\"kicker\"[^>]*>(.*?)<", Pattern.DOTALL);

    /**
     * One concept page's derived facts: its {@code slug} (filename without
     * {@code .html}), its plain-text {@code title} (from
     * {@code data-concept-title}), and the roadmap {@code itemIds} it backs
     * (from {@code data-concept-items}, in declared order). All three are read
     * once, so every caller that lists concepts enforces every contract.
     */
    record ConceptPage(String slug, String title, List<String> itemIds) {}

    /**
     * Reads every {@code *.html} page under {@code roadmapDir/concepts} and
     * returns slug → {@link ConceptPage}, sorted by slug. Enforces the title,
     * items, and kicker contracts on each page, so any caller that lists
     * concepts (generate, verify, render-adoc) verifies all three. Takes the
     * roadmap directory rather than the concepts directory so it can read
     * {@code changelog.md}'s {@code next-id:} counter for the allocated-id
     * bound. An absent concepts directory yields an empty map.
     */
    static Map<String, ConceptPage> readPages(Path roadmapDir) throws IOException {
        Map<String, ConceptPage> out = new TreeMap<>();
        Path conceptsDir = roadmapDir.resolve("concepts");
        if (!Files.isDirectory(conceptsDir)) {
            return out;
        }
        int changelogNextId = Main.readChangelogNextId(roadmapDir);
        try (Stream<Path> s = Files.list(conceptsDir)) {
            for (Path page : s.filter(p -> p.getFileName().toString().endsWith(".html")).sorted().toList()) {
                String name = page.getFileName().toString();
                String slug = name.replaceFirst("\\.html$", "");
                out.put(slug, parsePage(slug, name, Files.readString(page), changelogNextId));
            }
        }
        return out;
    }

    /**
     * Reads one page's title, backing item ids, and enforces the kicker
     * contract, returning the assembled {@link ConceptPage}. The single read
     * boundary both {@link #readPages} and {@link #stage} route through, so the
     * three contracts are enforced identically wherever a page is read.
     */
    static ConceptPage parsePage(String slug, String fileName, String html, int changelogNextId) {
        String title = extractTitle(fileName, html);
        List<String> itemIds = extractItemIds(fileName, html, changelogNextId);
        enforceKicker(fileName, html, itemIds);
        return new ConceptPage(slug, title, itemIds);
    }

    /**
     * Extracts the plain-text concept title from a page's
     * {@code data-concept-title} attribute. The attribute value, not the
     * {@code <h1>} element's inner HTML (which may carry {@code <code>}
     * markup), is what listings derive from. Missing or blank fails the build
     * with a message naming the file and the contract; the enforcer is what
     * turns the scrape from a fragile convention into a pinned invariant.
     */
    static String extractTitle(String fileName, String html) {
        var m = TITLE_ATTR.matcher(html);
        if (!m.find() || m.group(1).isBlank()) {
            System.err.println("concept page contract violation: " + fileName
                + " must carry a non-blank data-concept-title=\"<plain-text title>\" attribute"
                + " on its <h1>; the README and status-board listings derive from it.");
            throw new BuildFailure("concept page missing data-concept-title: " + fileName);
        }
        return m.group(1);
    }

    /**
     * Extracts the backing roadmap item ids from a page's
     * {@code data-concept-items} attribute (R488). The value is a
     * comma-separated {@code R<n>} list (whitespace around commas trimmed).
     * A missing or blank value, or any token not matching {@code R[1-9][0-9]*},
     * fails the build naming the file, exactly as the title contract does.
     *
     * <p><b>Allocated-id bound.</b> Beyond well-formedness, every id must be
     * numerically below {@code changelogNextId} (the {@code next-id:} counter).
     * Ids are allocated monotonically, so {@code n < next-id} is exactly "this
     * id has ever existed": a typo like {@code R999} fails at build time, while
     * a shipped item (file deleted on Done) and a discarded item both pass, so
     * a relation to a shipped item stays a legal anchor.
     */
    static List<String> extractItemIds(String fileName, String html, int changelogNextId) {
        var m = ITEMS_ATTR.matcher(html);
        if (!m.find() || m.group(1).isBlank()) {
            System.err.println("concept page contract violation: " + fileName
                + " must carry a non-blank data-concept-items=\"R<n>[, R<m>...]\" attribute"
                + " on its <h1>, naming the roadmap item(s) the concept backs;"
                + " the README and status-board listings derive from it.");
            throw new BuildFailure("concept page missing data-concept-items: " + fileName);
        }
        List<String> ids = new ArrayList<>();
        for (String token : m.group(1).split(",")) {
            String id = token.strip();
            if (!ID_TOKEN.matcher(id).matches()) {
                System.err.println("concept page contract violation: " + fileName
                    + " has a malformed data-concept-items token '" + id + "';"
                    + " each must be R<positive integer>, e.g. R458.");
                throw new BuildFailure("concept page malformed data-concept-items token '"
                    + id + "': " + fileName);
            }
            int n = Integer.parseInt(id.substring(1));
            if (n >= changelogNextId) {
                System.err.println("concept page contract violation: " + fileName
                    + " declares data-concept-items id '" + id + "', which was never allocated"
                    + " (>= changelog next-id R" + changelogNextId + "). Only ever-allocated"
                    + " roadmap ids are legal anchors; a shipped or discarded item still passes.");
                throw new BuildFailure("concept page references never-allocated id '"
                    + id + "': " + fileName);
            }
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    /**
     * Enforces that the header kicker (the first {@code class="kicker"}
     * element) names each declared item id (R488). The kicker is authored
     * prose of the form {@code Concept explainer · R458 · theme: <theme>}, but
     * a restated fact needs an enforcer: a declared id missing from the kicker
     * fails the build naming the file. Deriving the kicker at stage time was
     * rejected so the repo copy and local {@code file://} preview carry the
     * visible statement that is the point of the line.
     */
    static void enforceKicker(String fileName, String html, List<String> itemIds) {
        var m = KICKER.matcher(html);
        String kicker = m.find() ? m.group(1) : null;
        for (String id : itemIds) {
            boolean present = kicker != null
                && Pattern.compile("\\b" + id + "\\b").matcher(kicker).find();
            if (!present) {
                System.err.println("concept page contract violation: " + fileName
                    + " declares data-concept-items id '" + id + "' but its header kicker"
                    + " does not name it; the kicker must read"
                    + " \"Concept explainer · " + id + " · theme: <theme>\".");
                throw new BuildFailure("concept page kicker missing declared id '"
                    + id + "': " + fileName);
            }
        }
    }

    /**
     * Stages {@code roadmap/concepts/} into the rendered-site output: each
     * {@code *.html} page has its {@code href} values rewritten through
     * {@link #mapHref} and is written to {@code outConceptsDir}; the shared
     * {@code *.css} / {@code *.js} assets are copied byte-for-byte. The title,
     * items, and kicker contracts are enforced on every staged page. No-op when
     * the concepts directory does not exist.
     */
    static void stage(Path roadmapDir, Path outConceptsDir) throws IOException {
        Path conceptsDir = roadmapDir.resolve("concepts");
        if (!Files.isDirectory(conceptsDir)) {
            return;
        }
        int changelogNextId = Main.readChangelogNextId(roadmapDir);
        Files.createDirectories(outConceptsDir);
        try (Stream<Path> s = Files.list(conceptsDir)) {
            for (Path p : s.sorted().toList()) {
                String name = p.getFileName().toString();
                if (name.endsWith(".html")) {
                    String html = Files.readString(p);
                    parsePage(name.replaceFirst("\\.html$", ""), name, html, changelogNextId);
                    Files.writeString(outConceptsDir.resolve(name), rewriteHrefs(html, roadmapDir));
                } else if (name.endsWith(".css") || name.endsWith(".js")) {
                    Files.copy(p, outConceptsDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Rewrites every {@code href="..."} attribute value through {@link #mapHref}. */
    static String rewriteHrefs(String html, Path roadmapDir) {
        return HREF_ATTR.matcher(html).replaceAll(m ->
            Matcher.quoteReplacement("href=\"" + mapHref(m.group(1), roadmapDir) + "\""));
    }

    /**
     * Maps one repo-relative href, as authored in a page at
     * {@code roadmap/concepts/}, to its rendered-site location. Hrefs that do
     * not reach above the concepts directory (shared assets, sibling concept
     * pages, same-page anchors, external URLs) are untouched. For the rest,
     * the leading {@code ../} is stripped so the target reads at the same
     * grain as an item-body link, and the classification is formatted for the
     * site layout.
     *
     * <p><b>Cross-Done fallback.</b> A sibling-item target whose {@code .md}
     * file still exists maps to its rendered plan page; one whose file is gone
     * (deleted on Done) maps to the changelog, the durable record of shipped
     * work. That gives cross-boundary drift a deterministic landing instead of
     * a 404, without making item deletion on Done break the docs build. The
     * explainer skill's refresh mode later rewrites the visible prose; this
     * fallback is the always-on safety net between refreshes.
     */
    static String mapHref(String href, Path roadmapDir) {
        if (!href.startsWith("../")) {
            return href;
        }
        String target = href.substring(3);
        String anchor = "";
        int hash = target.indexOf('#');
        if (hash >= 0) {
            anchor = target.substring(hash);
            target = target.substring(0, hash);
        }
        return switch (LinkTarget.classify(target)) {
            case LinkTarget.SiblingItem(String slug) ->
                Files.exists(roadmapDir.resolve(slug + ".md"))
                    ? "../plans/" + slug + ".html" + anchor
                    : "../changelog.html" + anchor;
            case LinkTarget.Readme() -> "../index.html" + anchor;
            case LinkTarget.Changelog() -> "../changelog.html" + anchor;
            case LinkTarget.DeepDocsPath(String docsPath) ->
                "../../" + docsPath.replaceFirst("\\.adoc$", ".html") + anchor;
            default -> href;
        };
    }
}
