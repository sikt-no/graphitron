package no.sikt.graphitron.roadmap;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Classification of a link target as authored in roadmap item bodies, i.e.
 * relative to {@code roadmap/}. This is the single owner of the target-kind
 * knowledge; two thin formatters consume it: the AsciiDoc emitter in
 * {@link Main} (markdown bodies → rendered {@code .adoc} pages) and the HTML
 * href emitter in {@link ConceptPages} (concept pages → staged site HTML).
 * Keeping the taxonomy in one place is what stops the two directions from
 * drifting apart.
 *
 * <p>Callers whose source sits below {@code roadmap/} (concept pages at
 * {@code roadmap/concepts/}) strip their one extra {@code ../} before
 * classifying, so one grain serves both authoring locations.
 */
sealed interface LinkTarget {

    /** {@code http://} / {@code https://} URL; passed through in both directions. */
    record External(String url) implements LinkTarget {}

    /** Empty target; the link was an anchor-only reference within the page. */
    record SamePageAnchor() implements LinkTarget {}

    /** Sibling roadmap item, {@code <slug>.md}. */
    record SiblingItem(String slug) implements LinkTarget {}

    /** The rolled-up {@code README.md} (site: the roadmap index page). */
    record Readme() implements LinkTarget {}

    /** {@code changelog.md}, the durable record of shipped work. */
    record Changelog() implements LinkTarget {}

    /** {@code ../docs/workflow.adoc}; the file left the site and co-locates with the roadmap. */
    record Workflow() implements LinkTarget {}

    /**
     * Flat legacy architecture-doc reference {@code ../docs/<slug>.{md,adoc}}.
     * {@code quadrant} is the Diataxis quadrant from {@code ARCH_QUADRANT},
     * or {@code null} for a slug absent from the map (renders flat under
     * {@code architecture/}).
     */
    record ArchitectureDoc(String slug, String quadrant) implements LinkTarget {}

    /** Top-level doc {@code ../../docs/<slug>.{md,adoc}}. */
    record TopLevelDoc(String slug) implements LinkTarget {}

    /** Concept explainer page, {@code concepts/<slug>.html} (R486). */
    record ConceptPage(String slug) implements LinkTarget {}

    /**
     * Full path into the Diataxis trees: {@code ../docs/manual/**} or
     * {@code ../docs/architecture/**}. {@code docsPath} is the part after
     * {@code ../docs/} (e.g. {@code manual/reference/directives/reference.adoc}).
     * The flat legacy patterns do not match these; the adoc emitter passes the
     * original through unchanged exactly as the unknown case does, while the
     * href emitter maps it to the rendered {@code .html} location.
     */
    record DeepDocsPath(String docsPath) implements LinkTarget {}

    /** Legacy (pre-rewrite) module path; resolves to a GitHub URL on {@code main}. */
    record LegacyModulePath(String path) implements LinkTarget {}

    /** {@code claude-code-web-environment.md}, moved to {@code .claude/web-environment.md}. */
    record WebEnvironmentRedirect() implements LinkTarget {}

    /** Anything else; both emitters pass it through unchanged. */
    record Unknown(String target) implements LinkTarget {}

    // Diataxis quadrant for each authored architecture page (R182). Drives the
    // ../docs/<slug>.adoc → ../architecture/<quadrant>/<slug>.adoc mapping. A
    // slug absent from this map (e.g. index) renders flat under architecture/;
    // workflow.adoc is handled separately (it left the site).
    Map<String, String> ARCH_QUADRANT = Map.ofEntries(
        Map.entry("development-principles", "explanation"),
        Map.entry("emitter-conventions", "reference"),
        Map.entry("dispatch-axes", "explanation"),
        Map.entry("typed-rejection", "explanation"),
        Map.entry("pipeline-overview", "explanation"),
        Map.entry("code-generation-triggers", "reference"),
        Map.entry("argument-resolution", "reference"),
        Map.entry("runtime-extension-points", "reference"),
        Map.entry("modules", "reference"),
        Map.entry("testing", "how-to"),
        Map.entry("release-natives", "how-to"),
        Map.entry("dev-loop-internals", "how-to"));

    Pattern SIBLING_ITEM = Pattern.compile("([\\w-]+)\\.md");
    Pattern CONCEPT_PAGE = Pattern.compile("concepts/([\\w-]+)\\.html");
    Pattern DEEP_DOCS = Pattern.compile("\\.\\./docs/((?:manual|architecture)/.+)");
    Pattern FLAT_DOCS = Pattern.compile("\\.\\./docs/([\\w-]+)\\.(?:md|adoc)");
    Pattern TOP_DOCS = Pattern.compile("\\.\\./\\.\\./docs/([\\w-]+)\\.(?:md|adoc)");
    Pattern LEGACY_MODULE = Pattern.compile(
        "\\.\\./\\.\\./(graphitron-(?:codegen-parent|common|example|maven-plugin|schema-transform|servlet-parent)/.*)");

    /**
     * Classifies a target string (anchor already split off by the caller),
     * interpreted relative to {@code roadmap/}. The match order preserves the
     * pre-R486 inline {@code mapAdocTarget} behavior on every case that
     * existed then; {@link ConceptPage} and {@link DeepDocsPath} are the R486
     * additions and collide with none of the legacy patterns.
     */
    static LinkTarget classify(String target) {
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return new External(target);
        }
        if (target.isEmpty()) {
            return new SamePageAnchor();
        }
        var sibling = SIBLING_ITEM.matcher(target);
        if (sibling.matches()) {
            String slug = sibling.group(1);
            if ("README".equals(slug)) return new Readme();
            if ("changelog".equals(slug)) return new Changelog();
            return new SiblingItem(slug);
        }
        var concept = CONCEPT_PAGE.matcher(target);
        if (concept.matches()) {
            return new ConceptPage(concept.group(1));
        }
        var deep = DEEP_DOCS.matcher(target);
        if (deep.matches()) {
            return new DeepDocsPath(deep.group(1));
        }
        var flat = FLAT_DOCS.matcher(target);
        if (flat.matches()) {
            String slug = flat.group(1);
            if ("workflow".equals(slug)) return new Workflow();
            return new ArchitectureDoc(slug, ARCH_QUADRANT.get(slug));
        }
        var top = TOP_DOCS.matcher(target);
        if (top.matches()) {
            return new TopLevelDoc(top.group(1));
        }
        var legacy = LEGACY_MODULE.matcher(target);
        if (legacy.matches()) {
            return new LegacyModulePath(legacy.group(1));
        }
        if (target.endsWith("claude-code-web-environment.md")) {
            return new WebEnvironmentRedirect();
        }
        return new Unknown(target);
    }
}
