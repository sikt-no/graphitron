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
 * Bidirectional drift-protection seam between the rewrite's {@code directives.graphqls}
 * (the canonical directive surface auto-injected by {@code RewriteSchemaLoader}) and
 * the per-directive reference pages under {@code docs/manual/reference/directives/}.
 *
 * <p>Asserts every directive declared in the schema <em>and advertised in the v1
 * surface</em> has a {@code <name>.adoc} page, and every {@code <name>.adoc} page
 * corresponds to a directive declared in the schema. Failures print the missing
 * pages or stale files so the fix is mechanical.
 *
 * <p>R68 Phase 2 closing slice. A future PR that adds a directive cannot land green
 * without adding the doc page; a PR that removes a directive must remove its page
 * (or the build fails). The roadmap entry calls for this test in the docs-verifier
 * module if one exists, otherwise in {@code graphitron-sakila-example}; we use the
 * latter since {@code graphitron-sakila-example} already carries cross-cutting
 * structural tests against the project layout.
 *
 * <p>R400 carve-out: a directive may be declared in {@code directives.graphqls} (so
 * legacy schemas keep parsing) yet withheld from the advertised v1 surface, in which
 * case it has no reference page on purpose. The withheld set is not duplicated here;
 * it is derived from the generated {@code supported-directives.adoc} fragment that
 * {@code DirectiveSupportReport} renders (a declared directive absent from that
 * fragment is withheld), so the exemption cannot drift from the report that owns the
 * policy. When a withheld directive is re-advertised, its page reappears and the
 * bijection tightens automatically.
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
     * Removed/rejected + shape-changes). Rendered by {@code DirectiveSupportReport}; the set
     * of declared directives absent from it is exactly the R400 withheld-from-v1 set.
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

    @Test
    void everyDirectiveHasAReferencePageAndViceVersa() throws IOException {
        Set<String> directives = directivesFromSchema();
        Set<String> pages = pagesFromDocs();
        Set<String> advertised = advertisedDirectives();

        // R400: directives declared but absent from the advertised surface are withheld
        // from v1 and intentionally page-less. Derived from the report's own output so the
        // exemption cannot drift from DirectiveSupportReport.WITHHELD_FROM_V1.
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

    /**
     * Directive names mentioned in the generated {@code supported-directives.adoc} fragment,
     * i.e. the advertised v1 surface. A directive declared in the schema but missing from
     * this set is withheld from v1 (R400) and needs no reference page.
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
