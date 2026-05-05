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
 * <p>Asserts every directive declared in the schema has a {@code <name>.adoc} page,
 * and every {@code <name>.adoc} page corresponds to a directive declared in the
 * schema. Failures print the missing pages or stale files so the fix is mechanical.
 *
 * <p>R68 Phase 2 closing slice. A future PR that adds a directive cannot land green
 * without adding the doc page; a PR that removes a directive must remove its page
 * (or the build fails). The roadmap entry calls for this test in the docs-verifier
 * module if one exists, otherwise in {@code graphitron-sakila-example}; we use the
 * latter since {@code graphitron-sakila-example} already carries cross-cutting
 * structural tests against the project layout.
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

        Set<String> missingPages = new TreeSet<>(directives);
        missingPages.removeAll(pages);

        Set<String> stalePages = new TreeSet<>(pages);
        stalePages.removeAll(directives);

        assertThat(directives)
            .as("at least one directive must be declared in directives.graphqls")
            .isNotEmpty();
        assertThat(missingPages)
            .as("directives declared in directives.graphqls without a matching "
                + "reference/directives/<name>.adoc page; add the missing page(s)")
            .isEmpty();
        assertThat(stalePages)
            .as("reference/directives/<name>.adoc pages with no matching directive "
                + "in directives.graphqls; remove the stale page(s)")
            .isEmpty();
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
        Path docsDir = locateDocsDirectivesDir();
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
     * Walks up from the test working directory until it finds the
     * {@code docs/manual/reference/directives/} subtree. Surefire runs from the
     * module directory, so the directory is normally two parents up; the walk
     * keeps the test robust against future restructuring of the module layout.
     */
    private static Path locateDocsDirectivesDir() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path candidate = p.resolve(DOCS_DIRECTIVES_PATH);
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException(
            "Could not locate " + DOCS_DIRECTIVES_PATH + " by walking up from " + cwd);
    }
}
