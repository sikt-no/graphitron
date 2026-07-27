package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toCollection;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift-protection seam across the whole directive documentation chain: the rewrite's
 * {@code directives.graphqls} (the canonical directive surface auto-injected by
 * {@code RewriteSchemaLoader}), the per-directive reference pages under
 * {@code docs/manual/reference/directives/}, and the hand-maintained directives landing
 * at {@code docs/manual/reference/directives/index.adoc} that indexes them.
 *
 * <p>Two links, both bidirectional. SDL to pages: every directive declared in the schema
 * <em>and advertised in the v1 surface</em> must have a {@code <name>.adoc} page, and every
 * page must correspond to a declared directive. Pages to index: every page must be listed in
 * the landing's alphabetical column and filed under one of its categories, and the columns
 * must name no page that does not exist. Failures print the offending names so the fix is
 * mechanical. Lives in {@code graphitron-sakila-example} because that module carries the
 * cross-cutting structural tests against the project layout.
 *
 * <p>Carve-out: a directive may be declared in {@code directives.graphqls} (so
 * legacy schemas keep parsing) yet withheld from the advertised v1 surface, in which
 * case it has no reference page on purpose. The withheld set is not duplicated here;
 * it is derived from the generated {@code supported-directives.adoc} fragment that
 * {@code DirectiveSupportReport} renders (a declared directive absent from that
 * fragment is withheld), so the exemption cannot drift from the report that owns the
 * policy. A withheld directive has no page, so the pages-to-index link inherits the
 * carve-out without restating it.
 *
 * <p>Scope boundary against the sibling seams. {@link ManualXrefIntegrityTest} already
 * resolves every {@code xref:} target in the manual, so link <em>validity</em> is covered
 * there; what this class adds is <em>completeness</em>, which no walk of existing links can
 * see. The editorial annotations the landing carries next to an entry ("(deprecated,
 * ignored)", "(rejected, remove from the schema)") are prose and stay unguarded here;
 * {@link DeprecationsDocCoverageTest} is the seam that pins deprecation status against the
 * SDL markers.
 */
@UnitTier
class DirectiveDocCoverageTest {

    private static final String DIRECTIVES_RESOURCE =
        "/no/sikt/graphitron/rewrite/schema/directives.graphqls";

    /** Matches an SDL directive declaration at column 0: {@code directive @<name>...}. */
    private static final Pattern DIRECTIVE_DECLARATION =
        Pattern.compile("^directive\\s+@(\\w+)", Pattern.MULTILINE);

    private static final String DOCS_DIRECTIVES_PATH = "docs/manual/reference/directives";

    /**
     * Generated migration fragment listing the advertised directive surface (Supported +
     * Removed/rejected + shape-changes), rendered by {@code DirectiveSupportReport}.
     */
    private static final String SUPPORTED_DIRECTIVES_FRAGMENT =
        "docs/manual/_generated/supported-directives.adoc";

    /** Matches a backtick-wrapped {@code `@<name>`} directive mention in the fragment. */
    private static final Pattern DIRECTIVE_MENTION =
        Pattern.compile("`@(\\w+)");

    /**
     * Page filename → directive name remap for cases where the natural
     * {@code <name>.adoc} would collide with the directory's landing page.
     * {@code index.adoc} is the directives landing (alphabetical + categorical
     * roll-up); the {@code @index} directive lives at {@code index-directive.adoc}
     * to free the {@code index.adoc} slot for the landing.
     */
    private static final java.util.Map<String, String> PAGE_TO_DIRECTIVE = java.util.Map.of(
        "index-directive", "index"
    );

    /**
     * Page filenames that are not per-directive pages (chapter landing,
     * section indexes, etc.). Excluded from the directive ↔ page comparison.
     */
    private static final Set<String> NON_DIRECTIVE_PAGES = Set.of("index");

    /** The directives landing: the single hand-maintained index of the per-directive pages. */
    private static final String DOCS_LANDING_PATH =
        "docs/manual/reference/directives/index.adoc";

    /**
     * Headings that delimit the landing's two columns. Both lists live inside one
     * {@code [cols="1,1"]} table, so the cell break ({@code a|}) is not a usable anchor;
     * the headings are. Slicing between them keeps the page preamble out of the
     * alphabetical half, so a future intro paragraph that links a directive page cannot
     * silently stand in for a missing list entry.
     */
    private static final String ALPHABETICAL_HEADING = "== Alphabetical";
    private static final String BY_CATEGORY_HEADING = "== By category";

    /**
     * Matches a landing xref to a sibling page: {@code xref:<filename>.adoc[...]}.
     * Group 1 is the page filename, which still needs {@link #PAGE_TO_DIRECTIVE}
     * applied before it can be compared against {@link #pagesFromDocs()}.
     */
    private static final Pattern LANDING_XREF =
        Pattern.compile("xref:([\\w-]+)\\.adoc\\[");

    @Test
    void everyDirectiveHasAReferencePageAndViceVersa() throws IOException {
        Set<String> directives = directivesFromSchema();
        Set<String> pages = pagesFromDocs();
        Set<String> advertised = advertisedDirectives();

        // Declared-but-not-advertised directives are withheld from v1 and intentionally
        // page-less; derived from the report's own output so the exemption cannot drift
        // from DirectiveSupportReport.WITHHELD_FROM_V1.
        Set<String> withheld = new TreeSet<>(directives);
        withheld.removeAll(advertised);

        Set<String> directivesRequiringAPage = new TreeSet<>(directives);
        directivesRequiringAPage.removeAll(withheld);

        Set<String> missingPages = new TreeSet<>(directivesRequiringAPage);
        missingPages.removeAll(pages);

        Set<String> stalePages = new TreeSet<>(pages);
        stalePages.removeAll(directives);

        assertThat(directives)
            .as("at least one directive must be declared in directives.graphqls")
            .isNotEmpty();
        assertThat(missingPages)
            .as("advertised directives in directives.graphqls without a matching "
                + "reference/directives/<name>.adoc page; add the missing page(s). "
                + "(Withheld-from-v1 directives, exempt here: " + withheld + ")")
            .isEmpty();
        assertThat(stalePages)
            .as("reference/directives/<name>.adoc pages with no matching directive "
                + "in directives.graphqls; remove the stale page(s)")
            .isEmpty();
    }

    @Test
    void everyReferencePageIsIndexedOnTheDirectivesLanding() throws IOException {
        Set<String> pages = pagesFromDocs();
        String landing = Files.readString(locate(DOCS_LANDING_PATH));

        int alphabeticalAt = landing.indexOf(ALPHABETICAL_HEADING);
        int byCategoryAt = landing.indexOf(BY_CATEGORY_HEADING);
        assertThat(alphabeticalAt)
            .as("directives landing must carry an '" + ALPHABETICAL_HEADING + "' heading; it is "
                + "the only structural anchor for the alphabetical column. If the page was "
                + "restructured, repoint this test rather than dropping it")
            .isNotNegative();
        assertThat(byCategoryAt)
            .as("directives landing must carry a '" + BY_CATEGORY_HEADING + "' heading; it is "
                + "the only structural anchor separating the two columns. Without it the "
                + "category assertion below matches the whole page and goes vacuous")
            .isGreaterThan(alphabeticalAt);

        Set<String> alphabetical = indexedPages(landing.substring(alphabeticalAt, byCategoryAt));
        Set<String> categorised = indexedPages(landing.substring(byCategoryAt));

        Set<String> missingFromAlphabetical = new TreeSet<>(pages);
        missingFromAlphabetical.removeAll(alphabetical);

        Set<String> alphabeticalWithoutAPage = new TreeSet<>(alphabetical);
        alphabeticalWithoutAPage.removeAll(pages);

        Set<String> uncategorised = new TreeSet<>(pages);
        uncategorised.removeAll(categorised);

        assertThat(missingFromAlphabetical)
            .as("reference/directives/<name>.adoc pages with no entry in the alphabetical "
                + "column of " + DOCS_LANDING_PATH + "; add the missing xref(s)")
            .isEmpty();
        assertThat(alphabeticalWithoutAPage)
            .as("entries in the alphabetical column of " + DOCS_LANDING_PATH + " naming a page "
                + "that is not a directive page; remove the stale entry (or restore the page)")
            .isEmpty();
        assertThat(uncategorised)
            .as("reference/directives/<name>.adoc pages named nowhere in the '"
                + BY_CATEGORY_HEADING + "' column of " + DOCS_LANDING_PATH + "; file each one "
                + "under the category it belongs to, adding a category if none fits")
            .isEmpty();
    }

    /**
     * Directive names xref'd from one column of the directives landing. Normalises page
     * filenames the same way {@link #pagesFromDocs()} does, so both sides of the comparison
     * speak directive names rather than one speaking filenames: {@code index-directive.adoc}
     * becomes {@code index}, and a link to the landing itself is not an index entry.
     */
    private static Set<String> indexedPages(String column) {
        Set<String> names = new TreeSet<>();
        Matcher m = LANDING_XREF.matcher(column);
        while (m.find()) {
            String page = m.group(1);
            if (NON_DIRECTIVE_PAGES.contains(page)) continue;
            names.add(PAGE_TO_DIRECTIVE.getOrDefault(page, page));
        }
        return names;
    }

    /**
     * Directive names mentioned in the generated {@code supported-directives.adoc} fragment,
     * i.e. the advertised v1 surface.
     */
    private static Set<String> advertisedDirectives() throws IOException {
        Path fragment = locate(SUPPORTED_DIRECTIVES_FRAGMENT);
        String text = Files.readString(fragment);
        Set<String> names = new TreeSet<>();
        Matcher m = DIRECTIVE_MENTION.matcher(text);
        while (m.find()) {
            names.add(m.group(1));
        }
        assertThat(names)
            .as("generated fragment " + SUPPORTED_DIRECTIVES_FRAGMENT
                + " mentions no directives; the regen step likely did not run")
            .isNotEmpty();
        return names;
    }

    private static Set<String> directivesFromSchema() throws IOException {
        try (InputStream in = DirectiveDocCoverageTest.class.getResourceAsStream(DIRECTIVES_RESOURCE)) {
            assertThat(in)
                .as("classpath resource: " + DIRECTIVES_RESOURCE
                    + " (the graphitron module's directives.graphqls must be on the test classpath)")
                .isNotNull();
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Set<String> names = new TreeSet<>();
            Matcher m = DIRECTIVE_DECLARATION.matcher(text);
            while (m.find()) {
                names.add(m.group(1));
            }
            return names;
        }
    }

    private static Set<String> pagesFromDocs() throws IOException {
        Path docsDir = locate(DOCS_DIRECTIVES_PATH);
        try (Stream<Path> files = Files.list(docsDir)) {
            return files
                .filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .filter(n -> n.endsWith(".adoc"))
                .map(n -> n.substring(0, n.length() - ".adoc".length()))
                .filter(n -> !NON_DIRECTIVE_PAGES.contains(n))
                .map(n -> PAGE_TO_DIRECTIVE.getOrDefault(n, n))
                .collect(toCollection(TreeSet::new));
        }
    }

    /**
     * Walks up from the test working directory until {@code relativePath} resolves to an
     * existing file or directory. Surefire runs from the module directory, so the docs tree
     * is normally two parents up; the walk keeps the test robust against future
     * restructuring of the module layout.
     */
    private static Path locate(String relativePath) {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path candidate = p.resolve(relativePath);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException(
            "Could not locate " + relativePath + " by walking up from " + cwd);
    }
}
