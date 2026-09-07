package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one thing the {@code _entry} suffix cannot say about itself: that a new relation gets it.
 *
 * <p>The as-written half of the {@code graphitron_} family is identified by its name now, which is
 * what lets a reader find the anti-join between the two halves and what let two gates stop carrying
 * a list of 56 relations that went stale the first time the family grew. A name is only a
 * classification while something holds the writer to it. Nothing in the schema can: a suffix is a
 * spelling, and no constraint knows which code wrote a row.
 *
 * <p>So the check is over the decode's own source. Every relation the decode names is a relation
 * whose rows are a function of one document, that being what the decode does, so every one of them
 * has to carry the suffix. The scan is one region of one file rather than a walk, and deliberately:
 * a guard that ranged wider would fire on a stage legitimately reading an entry, which is a read and
 * not a write and which source text cannot tell apart.
 *
 * <p>The region is everything below the first of the five decode methods, which is the file's own
 * seam: the gatherer's stages sit above it and the decode and its helpers below. That is an
 * assumption about layout, and the guard is written so that breaking it fails loudly rather than
 * quietly. A stage moved below the seam names its anchors inside the region and fires this with
 * their names, which is a true report of a file that no longer has the seam its javadoc claims; the
 * fix there is the layout, not the suffix. Wording the failure for both readings is what keeps that
 * from being read as a naming defect.
 *
 * <p>What it does not catch is the other direction, a suffixed relation some stage writes. That
 * needs to tell a read from a write in source text, which this cannot, and it is the direction the
 * declaration pass closes properly by giving every relation a declared owner. Two statements of one
 * fact, and the day they disagree is a check neither is alone.
 */
@UnitTier
class EntryNamingGuardTest {

    private static final Path DECODE = Path.of("graphitron-model", "src", "main", "java", "no",
        "sikt", "graphitron", "model", "capture", "graphitron", "GraphitronFactCapture.java");

    /**
     * Where the decode begins: the first of its five methods, in file order. A symbol rather than
     * the section banner above it, so a comment reflow cannot move the guard's boundary.
     */
    private static final String REGION_OPENS = "public void captureSchemaDirective";

    /** Every {@code graphitron_} relation constant, as the generated model spells one. */
    private static final Pattern RELATION = Pattern.compile("\\bGRAPHITRON_[A-Z0-9_]+\\b");

    @Test
    @DisplayName("every relation the decode writes is named an entry")
    void theDecodeNamesOnlySuffixedRelations() {
        String source = Files.exists(path()) ? read(path()) : null;
        assertThat(source)
            .as("the guard reads one file by path, so a move renames the guard's subject out from "
                + "under it rather than failing; repoint %s", DECODE)
            .isNotNull();

        int opens = source.indexOf(REGION_OPENS);
        assertThat(opens)
            .as("the decode region opens at %s, which is how the guard knows where the gatherer's "
                + "stages end and the decode begins", REGION_OPENS)
            .isNotNegative();

        var unsuffixed = new ArrayList<String>();
        var seen = new LinkedHashSet<String>();
        Matcher matcher = RELATION.matcher(withoutComments(source.substring(opens)));
        while (matcher.find()) {
            String relation = matcher.group();
            if (seen.add(relation) && !relation.endsWith("_ENTRY")) {
                unsuffixed.add(relation);
            }
        }

        assertThat(seen)
            .as("a region naming no relation would pass this vacuously, which is what a refactor "
                + "that moved the decode elsewhere would look like from here")
            .isNotEmpty();
        assertThat(unsuffixed)
            .as("either the decode names a relation whose name does not say it is an entry, in "
                + "which case rename it with the _entry suffix or move the write to a gatherer "
                + "stage if its rows are not a function of one document; or a stage has moved "
                + "below %s and its anchors are being read as the decode's, in which case put the "
                + "file's seam back", REGION_OPENS)
            .isEmpty();
    }

    /**
     * {@code source} with every comment region blanked, so a javadoc naming an anchor to explain
     * why the decode does not write one is not read as the decode writing it. Blanks rather than
     * deletes, which keeps nothing else about the text true but keeps the two spellings of the same
     * relation from being joined into one token across a removed comment.
     */
    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
    }

    private static Path path() {
        return GuardScope.locateRepoRoot().resolve(DECODE);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
