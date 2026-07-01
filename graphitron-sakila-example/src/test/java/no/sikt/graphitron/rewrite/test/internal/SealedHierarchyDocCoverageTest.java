package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bidirectional drift-protection seam between {@link Rejection}'s sealed permit set
 * and the prose enumeration on {@code docs/architecture/explanation/typed-rejection.adoc}.
 *
 * <p>Forward: every concrete leaf reachable from {@link Rejection#getPermittedSubclasses()},
 * qualified by its sealed-parent ancestry under {@code Rejection}, must appear at least once
 * on the page. Reverse: every dotted qualified mention on the page whose first segment names
 * an intermediate sealed parent (e.g. {@code AuthorError.X}) must correspond to a real leaf.
 * Combined, these catch both drift directions: a new permit that lands without a paragraph
 * and a renamed-or-removed permit whose prose mention rots in place.
 *
 * <p>The reverse-direction matcher is built from the live permit set rather than a
 * hand-maintained regex literal: when a new top-level sealed branch lands on {@code Rejection},
 * its sub-permits are picked up automatically as the alternation extends. Top-level permits
 * (like {@link Rejection.Deferred}) carry no qualifying parent and are checked by the forward
 * direction only; the asymmetry is deliberate.
 *
 * <p>Companion to {@link DirectiveDocCoverageTest}, {@link DiagnosticsDocCoverageTest},
 * {@link DeprecationsDocCoverageTest}; same shape, scoped to the typed-rejection
 * sealed surface rather than the SDL or Mojo or diagnostic surface.
 *
 * <p>The sealed-{@code Resolved} pattern across the thirteen sibling
 * {@code *DirectiveResolver} classes is described shape-only on the page; per-resolver
 * arms are not pinned by this test, since there is no single {@code Resolved} parent
 * class to walk and the chapter intentionally lets javadoc on each
 * {@code *DirectiveResolver.Resolved} carry the per-resolver detail.
 */
@UnitTier
class SealedHierarchyDocCoverageTest {

    private static final String DOCS_PATH = "docs/architecture/explanation/typed-rejection.adoc";

    @Test
    void everyRejectionPermitAppearsOnTypedRejectionDocAndViceVersa() throws IOException {
        Set<String> permits = collectLeafPermits();
        Set<String> intermediateParents = collectIntermediateSealedParents();
        String doc = Files.readString(locateDoc(), StandardCharsets.UTF_8);

        Set<String> missing = new TreeSet<>();
        for (String permit : permits) {
            if (!doc.contains(permit)) {
                missing.add(permit);
            }
        }

        Set<String> staleMentions = new TreeSet<>();
        Pattern reverse = buildSubPermitReferencePattern(intermediateParents);
        if (reverse != null) {
            Matcher m = reverse.matcher(doc);
            while (m.find()) {
                String mention = m.group();
                if (!permits.contains(mention)) {
                    staleMentions.add(mention);
                }
            }
        }

        assertThat(permits)
            .as("at least one Rejection permit must be reachable; if Rejection ever "
                + "becomes a non-sealed type this test stops being meaningful and should "
                + "be deleted, not weakened")
            .isNotEmpty();
        assertThat(missing)
            .as("Rejection permits without a matching mention in typed-rejection.adoc; "
                + "add a paragraph that names each permit (qualified by its sealed-parent "
                + "ancestry, e.g. AuthorError.UnknownName) so the chapter prose tracks the "
                + "sealed surface")
            .isEmpty();
        assertThat(staleMentions)
            .as("typed-rejection.adoc names <SealedParent>.X qualified references that no "
                + "longer exist on Rejection; rename or remove the prose so it tracks the "
                + "current permit set")
            .isEmpty();
    }

    /**
     * Walks {@link Rejection#getPermittedSubclasses()} transitively and yields each
     * concrete (non-sealed-interface) leaf qualified by its sealed-parent ancestry up
     * to but not including {@link Rejection} itself.
     *
     * <p>Example: the {@code Structural} record nested in {@link Rejection.AuthorError}
     * yields {@code "AuthorError.Structural"}; the top-level {@link Rejection.Deferred}
     * record yields {@code "Deferred"}. Qualifying disambiguates the two distinct
     * {@code Structural} arms (one in {@code AuthorError}, one in {@code InvalidSchema}).
     */
    private static Set<String> collectLeafPermits() {
        Set<String> leaves = new TreeSet<>();
        Deque<Class<?>> stack = new ArrayDeque<>();
        stack.push(Rejection.class);
        while (!stack.isEmpty()) {
            Class<?> current = stack.pop();
            Class<?>[] permittedSubclasses = current.getPermittedSubclasses();
            if (permittedSubclasses == null || permittedSubclasses.length == 0) {
                if (current == Rejection.class) continue;
                leaves.add(qualifiedNameUnderRejection(current));
                continue;
            }
            for (Class<?> sub : permittedSubclasses) {
                stack.push(sub);
            }
        }
        return leaves;
    }

    /**
     * Walks {@link Rejection#getPermittedSubclasses()} transitively and yields the simple name
     * of every intermediate sealed parent (a permit class that itself has further permits)
     * encountered below {@code Rejection}. The reverse-direction regex alternation uses these
     * names so a new top-level sealed branch on {@code Rejection} extends coverage automatically.
     */
    private static Set<String> collectIntermediateSealedParents() {
        Set<String> parents = new TreeSet<>();
        Deque<Class<?>> stack = new ArrayDeque<>();
        stack.push(Rejection.class);
        while (!stack.isEmpty()) {
            Class<?> current = stack.pop();
            Class<?>[] permittedSubclasses = current.getPermittedSubclasses();
            if (permittedSubclasses == null || permittedSubclasses.length == 0) continue;
            if (current != Rejection.class) parents.add(current.getSimpleName());
            for (Class<?> sub : permittedSubclasses) {
                stack.push(sub);
            }
        }
        return parents;
    }

    /**
     * Builds the regex used for the reverse-direction scan: a sealed-parent simple name
     * (drawn from the live permit set) followed by a dot and a {@code CapitalisedIdentifier}.
     * Returns {@code null} when there are no intermediate sealed parents (the hierarchy is
     * fully flat under {@link Rejection}); the reverse-direction check then has nothing to do.
     */
    private static Pattern buildSubPermitReferencePattern(Set<String> intermediateParents) {
        if (intermediateParents.isEmpty()) return null;
        String alternation = intermediateParents.stream()
            .map(Pattern::quote)
            .collect(Collectors.joining("|"));
        return Pattern.compile("\\b(" + alternation + ")\\.([A-Z][A-Za-z0-9]+)\\b");
    }

    /**
     * Builds the dotted name of {@code leaf} qualified by every sealed enclosing class up to
     * (but not including) {@link Rejection}. Walks {@link Class#getEnclosingClass()} since
     * the sealed permits are nested types; this gives the same chain a reader sees in the
     * prose ({@code "AuthorError.UnknownName"} rather than {@code "Rejection$AuthorError$UnknownName"}).
     */
    private static String qualifiedNameUnderRejection(Class<?> leaf) {
        StringBuilder name = new StringBuilder(leaf.getSimpleName());
        Class<?> enclosing = leaf.getEnclosingClass();
        while (enclosing != null && enclosing != Rejection.class) {
            name.insert(0, enclosing.getSimpleName() + ".");
            enclosing = enclosing.getEnclosingClass();
        }
        return name.toString();
    }

    /**
     * Walks up from the test working directory until it finds
     * {@code docs/architecture/explanation/typed-rejection.adoc}. Surefire runs from the
     * module directory; the file lives two parents up under the rewrite-docs subtree.
     */
    private static Path locateDoc() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path candidate = p.resolve(DOCS_PATH);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException(
            "Could not locate " + DOCS_PATH + " by walking up from " + cwd);
    }
}

