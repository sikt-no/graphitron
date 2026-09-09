package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * A revive brings an item whose Done verdict was wrong back onto the board as
 * itself. Two halves have to hold together, and each fails quietly by hand.
 * The item file lands at {@code Spec} carrying the retracted commit, so the
 * next {@code Spec -> Ready} gate supplies the independent judgment the
 * retracted verdict shows was missing. And the changelog entries the verdict
 * wrote are retracted <em>whole</em>: entries run to several paragraphs and one
 * id can head several bullets, so a line-at-a-time retraction would leave the
 * changelog invariant failing with a message telling the session to run the
 * command that just ran. These tests pin both.
 */
class ReviveTest {

    private static final String SHA = "bf3d987";

    @Test
    void revivedItem_landsAtSpecCarryingTheRetractedSha(@TempDir Path dir) throws IOException {
        Path item = writeRestoredItem(dir, "wrongly-done", 571, "In Review");
        writeChangelog(dir, "- R571 (`2178df3` the mechanism): shipped.\n");

        Main.Revived revived = Main.applyRevive(dir, "R571", SHA);

        assertThat(revived.target()).isEqualTo(item);
        assertThat(revived.id()).isEqualTo("R571");
        assertThat(revived.retractedEntries()).isTrue();
        String written = Files.readString(item);
        assertThat(written)
            .contains("status: Spec")
            .contains("last-updated: " + LocalDate.now())
            // created: is the filing date and stays the filing date; the revive
            // is the same work under the same number, not a new item.
            .contains("created: 2026-01-01")
            .contains("revived-from: " + SHA);
        assertThat(Main.readChangelogDoneIds(dir)).doesNotContain("R571");
    }

    @Test
    void multiParagraphEntry_goesWhole_leavingNeighboursByteIdentical(@TempDir Path dir)
            throws IOException {
        writeRestoredItem(dir, "held-census", 916, "In Review");
        // R916's shape: a bullet line, then indented continuation paragraphs
        // separated by blank lines, then the next top-level bullet.
        writeChangelog(dir,
            "- R918 (`aaaaaaa` the neighbour above): stays.\n"
                + "\n"
                + "- R916 (`725f0d6` the held census): the two populations get the detectors.\n"
                + "\n"
                + "  Jars are most of the bytes and change only when a sibling project is built.\n"
                + "\n"
                + "  Independent-session In Review -> Done review, sharing no session.\n"
                + "\n"
                + "- R914 (`a870bdf` the neighbour below): stays.\n");

        Main.applyRevive(dir, "R916", SHA);

        assertThat(Files.readString(dir.resolve("changelog.md"))).isEqualTo(
            "---\n"
                + "next-id: R950\n"
                + "---\n"
                + "\n"
                + "# Rewrite Changelog\n"
                + "\n"
                + "- R918 (`aaaaaaa` the neighbour above): stays.\n"
                + "\n"
                + "- R914 (`a870bdf` the neighbour below): stays.\n");
    }

    @Test
    void idHeadingSeveralBullets_hasEveryBulletRetracted(@TempDir Path dir) throws IOException {
        writeRestoredItem(dir, "table-method", 43, "In Review");
        // R43's shape: one id heading several per-commit bullets. Retraction is
        // total over the id, or validate fails on whichever span survived.
        writeChangelog(dir,
            "- R43 commit 3 (`ccccccc`): the child lift.\n"
                + "\n"
                + "- R44 (`ddddddd`): an unrelated neighbour.\n"
                + "\n"
                + "- R43 commit 2 (`bbbbbbb`): path resolution.\n"
                + "\n"
                + "- R43 commit 1 (`aaaaaaa`): directive flattening.\n");

        Main.applyRevive(dir, "R43", SHA);

        String written = Files.readString(dir.resolve("changelog.md"));
        assertThat(Main.changelogDoneIds(written)).containsExactly("R44");
        assertThat(written).endsWith("- R44 (`ddddddd`): an unrelated neighbour.\n");
    }

    @Test
    void bareBulletShape_isRetractedToo(@TempDir Path dir) throws IOException {
        writeRestoredItem(dir, "absorption", 232, "In Review");
        // The file carries both `- R<n> (` and bare `- R<n> ` heads; the word
        // boundary after the digits is what makes them one population.
        writeChangelog(dir, "- R232 + R129 absorption (`3079b99`): reference paths classify.\n");

        Main.applyRevive(dir, "R232", SHA);

        assertThat(Main.readChangelogDoneIds(dir)).isEmpty();
    }

    @Test
    void idNamedOnlyInAnotherEntrysProse_survives(@TempDir Path dir) throws IOException {
        writeRestoredItem(dir, "absorbed", 129, "In Review");
        writeChangelog(dir, "- R232 + R129 absorption (`3079b99`): reference paths classify.\n");

        Main.Revived revived = Main.applyRevive(dir, "R129", SHA);

        // R129 heads no bullet, so it has no entry to retract: the invariant is
        // about which id an entry is filed under, not which ids it mentions.
        assertThat(revived.retractedEntries()).isFalse();
        assertThat(Main.readChangelogDoneIds(dir)).containsExactly("R232");
    }

    @Test
    void noChangelogEntry_revivesAsAReportedNoOp(@TempDir Path dir) throws IOException {
        writeRestoredItem(dir, "routine", 700, "In Review");
        writeChangelog(dir, "- R699 (`aaaaaaa`): an unrelated neighbour.\n");

        Main.Revived revived = Main.applyRevive(dir, "R700", SHA);

        assertThat(revived.retractedEntries()).isFalse();
        assertThat(Files.readString(dir.resolve("changelog.md")))
            .contains("- R699 (`aaaaaaa`): an unrelated neighbour.\n");
        assertThat(Files.readString(dir.resolve("routine.md"))).contains("status: Spec");
    }

    @Test
    void statusOtherThanInReview_failsNamingTheState(@TempDir Path dir) throws IOException {
        writeRestoredItem(dir, "not-restored", 571, "Ready");
        writeChangelog(dir, "- R571 (`2178df3`): shipped.\n");

        assertThatIllegalArgumentException()
            .isThrownBy(() -> Main.applyRevive(dir, "R571", SHA))
            .withMessageContaining("status: Ready")
            .withMessageContaining("Done is reachable only from 'In Review'");
        // Nothing is written on a failed precondition, changelog included.
        assertThat(Main.readChangelogDoneIds(dir)).containsExactly("R571");
    }

    @Test
    void bodyWithoutRevivedSection_failsNamingTheSection(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("no-section.md"), frontMatter(571, "In Review")
            + "# Wrongly done\n\n## Goal\n\nThe outcome that was not delivered.\n");
        writeChangelog(dir, "- R571 (`2178df3`): shipped.\n");

        assertThatIllegalArgumentException()
            .isThrownBy(() -> Main.applyRevive(dir, "R571", SHA))
            .withMessageContaining("'## Revived'");
    }

    @Test
    void revivedHeadingWithNoProse_isNotTheSection(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("empty-section.md"), frontMatter(571, "In Review")
            + "# Wrongly done\n\n## Goal\n\nThe outcome.\n\n## Revived\n\n## Tests\n\nA test.\n");
        writeChangelog(dir, "- R571 (`2178df3`): shipped.\n");

        assertThatIllegalArgumentException()
            .isThrownBy(() -> Main.applyRevive(dir, "R571", SHA))
            .withMessageContaining("'## Revived'");
    }

    @Test
    void missingRetractedSha_failsNamingTheOption(@TempDir Path dir) throws IOException {
        writeRestoredItem(dir, "wrongly-done", 571, "In Review");

        assertThatIllegalArgumentException()
            .isThrownBy(() -> Main.applyRevive(dir, "R571", null))
            .withMessageContaining("--retracted");
    }

    @Test
    void malformedRetractedSha_failsNamingTheOption(@TempDir Path dir) throws IOException {
        writeRestoredItem(dir, "wrongly-done", 571, "In Review");

        assertThatIllegalArgumentException()
            .isThrownBy(() -> Main.applyRevive(dir, "R571", "not-a-sha"))
            .withMessageContaining("--retracted")
            .withMessageContaining("not-a-sha");
    }

    @Test
    void unresolvedReference_failsNamingTheRestoreStep(@TempDir Path dir) throws IOException {
        writeChangelog(dir, "- R571 (`2178df3`): shipped.\n");

        assertThatIllegalArgumentException()
            .isThrownBy(() -> Main.applyRevive(dir, "R571", SHA))
            .withMessageContaining("no roadmap item matches 'R571'")
            .withMessageContaining("restored out of git history");
    }

    private static Path writeRestoredItem(Path dir, String slug, int id, String status)
            throws IOException {
        Path target = dir.resolve(slug + ".md");
        Files.writeString(target, frontMatter(id, status)
            + "# " + slug + "\n"
            + "\n"
            + "## Goal\n"
            + "\n"
            + "The outcome that turned out not to be delivered.\n"
            + "\n"
            + "## Revived\n"
            + "\n"
            + "The Done gate missed that the shipped change breaks a consumer's development\n"
            + "process. What remains is the fix; the mechanism shipped at `2178df3`.\n"
            + "\n"
            + "## Tests\n"
            + "\n"
            + "A test.\n");
        return target;
    }

    private static String frontMatter(int id, String status) {
        return "---\n"
            + "id: R" + id + "\n"
            + "title: \"An item whose Done verdict was wrong: it comes back\"\n"
            + "status: " + status + "\n"
            + "theme: tooling\n"
            + "depends-on: []\n"
            + "created: 2026-01-01\n"
            + "last-updated: 2026-01-02\n"
            + "---\n\n";
    }

    private static void writeChangelog(Path dir, String entries) throws IOException {
        Files.writeString(dir.resolve("changelog.md"),
            "---\n"
                + "next-id: R950\n"
                + "---\n"
                + "\n"
                + "# Rewrite Changelog\n"
                + "\n"
                + entries);
    }
}
