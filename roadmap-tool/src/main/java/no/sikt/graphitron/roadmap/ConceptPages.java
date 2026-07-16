package no.sikt.graphitron.roadmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private static final Pattern HREF_ATTR = Pattern.compile("href=\"([^\"]*)\"");

    /**
     * Reads every {@code *.html} page under {@code conceptsDir} and returns
     * slug → title, sorted by slug. Enforces the title contract on each page,
     * so any caller that lists concepts (generate, verify, render-adoc) also
     * verifies the contract. An absent directory yields an empty map.
     */
    static Map<String, String> readTitles(Path conceptsDir) throws IOException {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isDirectory(conceptsDir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(conceptsDir)) {
            for (Path page : s.filter(p -> p.getFileName().toString().endsWith(".html")).sorted().toList()) {
                String slug = page.getFileName().toString().replaceFirst("\\.html$", "");
                out.put(slug, extractTitle(page.getFileName().toString(), Files.readString(page)));
            }
        }
        return out;
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
     * Stages {@code roadmap/concepts/} into the rendered-site output: each
     * {@code *.html} page has its {@code href} values rewritten through
     * {@link #mapHref} and is written to {@code outConceptsDir}; the shared
     * {@code *.css} / {@code *.js} assets are copied byte-for-byte. The title
     * contract is enforced on every staged page. No-op when the concepts
     * directory does not exist.
     */
    static void stage(Path roadmapDir, Path outConceptsDir) throws IOException {
        Path conceptsDir = roadmapDir.resolve("concepts");
        if (!Files.isDirectory(conceptsDir)) {
            return;
        }
        Files.createDirectories(outConceptsDir);
        try (Stream<Path> s = Files.list(conceptsDir)) {
            for (Path p : s.sorted().toList()) {
                String name = p.getFileName().toString();
                if (name.endsWith(".html")) {
                    String html = Files.readString(p);
                    extractTitle(name, html);
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
