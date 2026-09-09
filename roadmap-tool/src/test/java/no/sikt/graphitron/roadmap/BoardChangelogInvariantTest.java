package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The board and {@code changelog.md} are validated as a pair. An id is either
 * a Done entry in the changelog or a live item file, never both: a Done verdict
 * deletes the file, and a revive that retracts the verdict must retract the
 * entries too. Without the check the two surfaces disagree silently, which is
 * exactly the state a hand-rolled revive leaves behind, and the same pair is
 * what makes {@code revived-from:} enforce its {@code ++##++ Revived} section on
 * every build rather than only when the command ran.
 *
 * <p>Every case here runs through {@link Main#main}, one per entry point that
 * validates ({@code verify}, {@code generate}, {@code create},
 * {@code render-adoc}), so the check cannot be entry-point dependent.
 */
class BoardChangelogInvariantTest {

    @Test
    void onBoardIdHeadingAChangelogEntry_failsVerifyNamingBothRemedies(@TempDir Path dir)
            throws IOException {
        writeItem(dir, "shipped-or-not", 571, "Ready", "");
        writeChangelog(dir, "- R571 (`2178df3` the mechanism): shipped.\n");

        String stderr = captureStderr(() -> Main.main(new String[] {"verify", dir.toString()}));

        assertThat(stderr)
            .contains("R571: on the board as 'shipped-or-not.md'")
            .contains("heading a Done entry in changelog.md")
            // Both remedies, because the message cannot know which case it is in.
            .contains("roadmap-tool revive <roadmap-dir> R571 --retracted <sha>")
            .contains("delete shipped-or-not.md");
    }

    @Test
    void bareBulletShape_isCaughtToo(@TempDir Path dir) throws IOException {
        // The changelog carries both `- R<n> (` and bare `- R<n> ` heads; the
        // word boundary after the digits is what makes them one population.
        writeItem(dir, "absorption", 232, "Ready", "");
        writeChangelog(dir, "- R232 + R129 absorption (`3079b99`): reference paths classify.\n");

        String stderr = captureStderr(() -> Main.main(new String[] {"generate", dir.toString()}));

        assertThat(stderr).contains("R232: on the board as 'absorption.md'");
    }

    @Test
    void idNamedOnlyInAnotherEntrysProse_passes(@TempDir Path dir) throws IOException {
        // R129 is named inside R232's entry but heads no bullet of its own. The
        // invariant is about which id an entry is filed under, not which ids it
        // mentions, so R129 may stand on the board.
        writeItem(dir, "absorbed", 129, "Ready", "");
        writeChangelog(dir, "- R232 + R129 absorption (`3079b99`): reference paths classify.\n");
        Path out = dir.resolve("out");

        assertThatCode(() -> Main.main(
            new String[] {"render-adoc", dir.toString(), out.toString()}))
            .doesNotThrowAnyException();
    }

    @Test
    void discardedEntry_doesNotBindItsIdOffTheBoard(@TempDir Path dir) throws IOException {
        // A `Discarded:` entry names the discarded id in prose rather than
        // heading with it, which is right: discarding is not a landing and
        // makes no claim about whether a file should exist.
        writeItem(dir, "discarded-then-revisited", 713, "Ready", "");
        writeChangelog(dir,
            "- Discarded: The decodes read captured rows (`decodes-read-rows`, R713): refused.\n");

        // generate rather than verify: a temp board carries no rendered README,
        // and verify's own sync check would fire before validate says anything.
        assertThatCode(() -> Main.main(new String[] {"generate", dir.toString()}))
            .doesNotThrowAnyException();
    }

    @Test
    void revivedFromWithoutRevivedSection_failsNamingTheSection(@TempDir Path dir)
            throws IOException {
        // The front-matter key is what makes the obligation enforceable on
        // every build: a session that drops the section after the command ran
        // restores the failure the section exists to prevent.
        writeItem(dir, "half-revived", 571, "Spec", "revived-from: bf3d987\n");
        writeChangelog(dir, "");

        String stderr = captureStderr(() -> Main.main(
            new String[] {"create", dir.toString(), "a-new-item", "--title", "New"}));

        assertThat(stderr)
            .contains("half-revived: carries 'revived-from: bf3d987' but no '## Revived' section");
        // validate runs before the allocation, so nothing was written.
        assertThat(Files.exists(dir.resolve("a-new-item.md"))).isFalse();
    }

    @Test
    void revivedFromWithARevivedSection_passes(@TempDir Path dir) throws IOException {
        Path item = writeItem(dir, "properly-revived", 571, "Spec", "revived-from: bf3d987\n");
        Files.writeString(item, Files.readString(item)
            + "\n## Revived\n\nThe gate missed the consumer break; the fix remains.\n");
        writeChangelog(dir, "");

        // generate rather than verify: a temp board carries no rendered README,
        // and verify's own sync check would fire before validate says anything.
        assertThatCode(() -> Main.main(new String[] {"generate", dir.toString()}))
            .doesNotThrowAnyException();
    }

    /** Runs {@code body}, asserting it fails the build, and returns its stderr. */
    private static String captureStderr(ThrowingRunnable body) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            assertThatThrownBy(body::run).isInstanceOf(BuildFailure.class);
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static Path writeItem(Path dir, String slug, int id, String status, String extraFm)
            throws IOException {
        Path target = dir.resolve(slug + ".md");
        Files.writeString(target,
            "---\n"
                + "id: R" + id + "\n"
                + "title: \"" + slug + "\"\n"
                + "status: " + status + "\n"
                + "theme: tooling\n"
                + extraFm
                + "depends-on: []\n"
                + "created: 2026-01-01\n"
                + "last-updated: 2026-01-02\n"
                + "---\n"
                + "\n"
                + "# " + slug + "\n"
                + "\n"
                + "## Goal\n"
                + "\n"
                + "The outcome.\n");
        return target;
    }

    private static void writeChangelog(Path dir, String entries) throws IOException {
        Files.writeString(dir.resolve("changelog.md"),
            "---\nnext-id: R950\n---\n\n# Rewrite Changelog\n\n" + entries);
    }
}
