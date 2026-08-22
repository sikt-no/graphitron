package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
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
 * Bidirectional drift-protection seam between the pages of a docs section directory and the
 * roll-up in its {@code index.adoc}. Every {@code <slug>.adoc} (other than the index itself)
 * must be mentioned in the index by an {@code xref:<slug>.adoc[...]} occurrence; every such
 * xref must resolve to a file.
 *
 * <p>Catches the two common drift modes: a new page lands without the index being updated
 * (the page is invisible to readers with every other gate green), or a page is deleted
 * without its index entry being removed (a stale link 404s).
 *
 * <p>Same shape as {@link DirectiveDocCoverageTest}, parameterized over the section
 * directories that carry a per-directory roll-up. Direct children only: a nested tree like
 * {@code architecture/reference/schema/} is its own surface with its own index. The check is
 * membership-based, not order-sensitive: the test allows an index to organise entries however
 * it likes as long as every page is referenced and every reference resolves.
 */
@UnitTier
class HowToIndexCoverageTest {

    private static final String INDEX_FILE = "index.adoc";

    /** Matches {@code xref:<slug>.adoc[...]} where the slug is a sibling page (no path separator). */
    private static final Pattern SIBLING_XREF =
        Pattern.compile("xref:([\\w-]+)\\.adoc(?:#[\\w-]+)?\\[");

    @ParameterizedTest
    @ValueSource(strings = {
        "docs/architecture/explanation",
        "docs/architecture/reference",
        "docs/architecture/how-to",
        "docs/architecture/principles",
        "docs/manual/how-to",
    })
    void everyPageIsListedInIndexAndEveryListedSlugResolves(String sectionDir) throws IOException {
        Path section = locateSectionDir(sectionDir);
        Set<String> pages = pageSlugsOnDisk(section);
        Set<String> referenced = pageSlugsReferencedByIndex(section);

        Set<String> missingFromIndex = new TreeSet<>(pages);
        missingFromIndex.removeAll(referenced);

        Set<String> staleInIndex = new TreeSet<>(referenced);
        staleInIndex.removeAll(pages);

        assertThat(pages)
            .as("at least one page file must exist under " + sectionDir)
            .isNotEmpty();
        assertThat(missingFromIndex)
            .as("page files under " + sectionDir + " not referenced from "
                + sectionDir + "/" + INDEX_FILE + "; add an entry to the index")
            .isEmpty();
        assertThat(staleInIndex)
            .as(sectionDir + "/" + INDEX_FILE + " references slugs with no matching "
                + "page file; remove the stale entries or add the missing files")
            .isEmpty();
    }

    private static Set<String> pageSlugsOnDisk(Path sectionDir) throws IOException {
        try (Stream<Path> files = Files.list(sectionDir)) {
            return files
                .filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .filter(n -> n.endsWith(".adoc"))
                .filter(n -> !n.equals(INDEX_FILE))
                .map(n -> n.substring(0, n.length() - ".adoc".length()))
                .collect(toCollection(TreeSet::new));
        }
    }

    private static Set<String> pageSlugsReferencedByIndex(Path sectionDir) throws IOException {
        Path index = sectionDir.resolve(INDEX_FILE);
        String text = Files.readString(index, StandardCharsets.UTF_8);
        Set<String> slugs = new TreeSet<>();
        Matcher m = SIBLING_XREF.matcher(text);
        while (m.find()) {
            slugs.add(m.group(1));
        }
        // An index that references itself or its parent does so via a path-bearing xref,
        // which SIBLING_XREF won't pick up. No filter needed.
        return slugs;
    }

    private static Path locateSectionDir(String sectionDir) {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path candidate = p.resolve(sectionDir);
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException(
            "Could not locate " + sectionDir + " by walking up from " + cwd);
    }
}
