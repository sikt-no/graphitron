package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The R-id allocator must honour two invariants together: live items contribute
 * the high-water mark observed today, and the {@code next-id:} counter in
 * {@code changelog.md} contributes the high-water mark of every ID ever
 * allocated (including ones whose plan files have since been deleted on Done).
 * Without the changelog counter the allocator silently reuses the IDs of
 * shipped items. These tests pin the contract.
 */
class NextIdAllocationTest {

    @Test
    void counterAheadOfObserved_winsOverObserved(@TempDir Path dir) throws IOException {
        // Live item with id R10; counter says next free is R55. The counter is
        // ahead because R11..R54 shipped and were deleted. Allocator must pick
        // R55, not R11.
        writePlan(dir, "live", 10);
        writeChangelog(dir, 55);

        String next = Main.nextId(Main.readItems(dir), Main.readChangelogNextId(dir));

        assertThat(next).isEqualTo("R55");
    }

    @Test
    void observedAheadOfCounter_selfHeals(@TempDir Path dir) throws IOException {
        // A plan file was authored by hand with an explicit id past the counter
        // (counter drift). Allocator must respect the observed max so the next
        // create call doesn't issue a duplicate.
        writePlan(dir, "manual", 99);
        writeChangelog(dir, 50);

        String next = Main.nextId(Main.readItems(dir), Main.readChangelogNextId(dir));

        assertThat(next).isEqualTo("R100");
    }

    @Test
    void noChangelog_fallsBackToObservedPlusOne(@TempDir Path dir) throws IOException {
        writePlan(dir, "only", 7);

        String next = Main.nextId(Main.readItems(dir), Main.readChangelogNextId(dir));

        assertThat(next).isEqualTo("R8");
    }

    @Test
    void emptyDir_returnsR1(@TempDir Path dir) throws IOException {
        String next = Main.nextId(Main.readItems(dir), Main.readChangelogNextId(dir));

        assertThat(next).isEqualTo("R1");
    }

    @Test
    void writeChangelogNextId_roundTrips(@TempDir Path dir) throws IOException {
        writeChangelog(dir, 10);

        Main.writeChangelogNextId(dir, 42);

        assertThat(Main.readChangelogNextId(dir)).isEqualTo(42);
        // Body preserved verbatim around the front-matter rewrite.
        assertThat(Files.readString(dir.resolve("changelog.md")))
            .contains("# Rewrite Changelog")
            .contains("- entry one");
    }

    @Test
    void writeChangelogNextId_createsFrontMatterIfAbsent(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("changelog.md"), "# Rewrite Changelog\n\nbody.\n");

        Main.writeChangelogNextId(dir, 7);

        String written = Files.readString(dir.resolve("changelog.md"));
        assertThat(written).startsWith("---\nnext-id: R7\n---\n");
        assertThat(written).contains("# Rewrite Changelog");
        assertThat(written).contains("body.");
    }

    @Test
    void readChangelogNextId_rejectsMalformedValue(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("changelog.md"),
            "---\nnext-id: garbage\n---\n\n# Rewrite Changelog\n");

        assertThatIllegalArgumentException()
            .isThrownBy(() -> Main.readChangelogNextId(dir))
            .withMessageContaining("malformed");
    }

    private static void writePlan(Path dir, String slug, int id) throws IOException {
        Files.writeString(dir.resolve(slug + ".md"),
            "---\n"
                + "id: R" + id + "\n"
                + "title: \"" + slug + "\"\n"
                + "status: Backlog\n"
                + "depends-on: []\n"
                + "---\n\n"
                + "# " + slug + "\n");
    }

    private static void writeChangelog(Path dir, int nextId) throws IOException {
        Files.writeString(dir.resolve("changelog.md"),
            "---\n"
                + "next-id: R" + nextId + "\n"
                + "---\n\n"
                + "# Rewrite Changelog\n\n"
                + "- entry one\n");
    }
}
