package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

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
 * Bidirectional drift-protection seam between the recipe files under
 * {@code docs/manual/how-to/} and the alphabetical roll-up in
 * {@code docs/manual/how-to/index.adoc}. Every {@code <slug>.adoc} (other than the
 * index itself) must be mentioned in the index by an {@code xref:<slug>.adoc[...]}
 * occurrence; every such xref must resolve to a file.
 *
 * <p>Catches the two common drift modes: a new recipe lands without the index
 * being updated (the recipe is invisible to readers), or a recipe is deleted
 * without its index entry being removed (a stale link 404s).
 *
 * <p>Same shape as {@link DirectiveDocCoverageTest}, scoped to the recipe surface.
 * The check is membership-based, not order-sensitive: the test allows the index
 * to organise entries however it likes (currently alphabetical + categorical
 * columns) as long as every recipe is referenced and every reference resolves.
 */
@UnitTier
class HowToIndexCoverageTest {

    private static final String HOW_TO_DIR = "docs/manual/how-to";
    private static final String INDEX_FILE = "index.adoc";

    /** Matches {@code xref:<slug>.adoc[...]} where the slug is a sibling recipe (no path separator). */
    private static final Pattern SIBLING_XREF =
        Pattern.compile("xref:([\\w-]+)\\.adoc(?:#[\\w-]+)?\\[");

    @Test
    void everyRecipeIsListedInIndexAndEveryListedSlugResolves() throws IOException {
        Path howToDir = locateHowToDir();
        Set<String> recipes = recipeSlugsOnDisk(howToDir);
        Set<String> referenced = recipeSlugsReferencedByIndex(howToDir);

        Set<String> missingFromIndex = new TreeSet<>(recipes);
        missingFromIndex.removeAll(referenced);

        Set<String> staleInIndex = new TreeSet<>(referenced);
        staleInIndex.removeAll(recipes);

        assertThat(recipes)
            .as("at least one recipe file must exist under " + HOW_TO_DIR)
            .isNotEmpty();
        assertThat(missingFromIndex)
            .as("recipe files under " + HOW_TO_DIR + " not referenced from "
                + HOW_TO_DIR + "/" + INDEX_FILE + "; add an entry to the index")
            .isEmpty();
        assertThat(staleInIndex)
            .as(HOW_TO_DIR + "/" + INDEX_FILE + " references slugs with no matching "
                + "recipe file; remove the stale entries or add the missing files")
            .isEmpty();
    }

    private static Set<String> recipeSlugsOnDisk(Path howToDir) throws IOException {
        try (Stream<Path> files = Files.list(howToDir)) {
            return files
                .filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .filter(n -> n.endsWith(".adoc"))
                .filter(n -> !n.equals(INDEX_FILE))
                .map(n -> n.substring(0, n.length() - ".adoc".length()))
                .collect(toCollection(TreeSet::new));
        }
    }

    private static Set<String> recipeSlugsReferencedByIndex(Path howToDir) throws IOException {
        Path index = howToDir.resolve(INDEX_FILE);
        String text = Files.readString(index, StandardCharsets.UTF_8);
        Set<String> slugs = new TreeSet<>();
        Matcher m = SIBLING_XREF.matcher(text);
        while (m.find()) {
            slugs.add(m.group(1));
        }
        // The index references itself via "← Back to the manual landing" (parent path),
        // not via a sibling xref, so SIBLING_XREF won't pick that up. No filter needed.
        return slugs;
    }

    private static Path locateHowToDir() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path candidate = p.resolve(HOW_TO_DIR);
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException(
            "Could not locate " + HOW_TO_DIR + " by walking up from " + cwd);
    }
}
