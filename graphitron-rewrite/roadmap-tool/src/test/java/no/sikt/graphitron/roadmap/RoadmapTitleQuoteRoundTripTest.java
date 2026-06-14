package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pins R264: a {@code status} transition must not corrupt a front-matter
 * {@code title:} whose value contains a {@code ": "} (the common
 * "subtitle: detail" shape, e.g. R256's
 * {@code "Absorb the service walker substrate: typed per-arm errors"}).
 *
 * <p>The pre-R264 write path round-tripped the block through
 * {@code parseFrontMatter} (snakeyaml {@code load}) and then re-emitted the map
 * with no value-quoting, so the title came back as a bare string and was written
 * as an unquoted {@code title: subtitle: detail}, invalid YAML. The very next
 * parse, including the README regeneration the same subcommand runs, threw a
 * {@code ScannerException} and left the file unreadable. The fix patches only
 * {@code status:} and {@code last-updated:} in place and leaves the quoted
 * title byte-for-byte untouched.
 */
class RoadmapTitleQuoteRoundTripTest {

    private static final String COLON_TITLE =
        "Absorb the service walker substrate: typed per-arm errors + multi-arg ctors";

    @Test
    void statusSubcommand_preservesColonBearingTitle_andRegenerationSucceeds(
            @TempDir Path dir) throws IOException {
        Path file = writeItem(dir, "colon-title", 1, "Spec", COLON_TITLE);
        String quotedTitleLine = "title: \"" + COLON_TITLE + "\"";

        // Full subcommand: applies the transition, then regenerates the README.
        // Pre-R264 the regeneration re-parse threw ScannerException here, which
        // is the live failure mode this test pins.
        assertThatCode(() ->
            Main.main(new String[]{"status", dir.toString(), "R1", "Ready"}))
            .doesNotThrowAnyException();

        String written = Files.readString(file);
        // The quoted title line is left byte-for-byte untouched.
        assertThat(written).contains(quotedTitleLine);
        // Status flipped and last-updated stamped today.
        assertThat(written).contains("status: Ready");
        assertThat(written).contains("last-updated: " + LocalDate.now());

        // The file re-parses and the title round-trips with its colon intact.
        List<Main.Item> items = Main.readItems(dir);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo(COLON_TITLE);
    }

    @Test
    void patchFrontMatter_replacesPresentKeysAndAppendsAbsentOnes() {
        String content = """
            ---
            id: R1
            title: "Subtitle: a detail with : colons"
            status: Spec
            depends-on: []
            ---

            # body stays put
            """;

        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("status", "Ready");          // present: replaced in place
        updates.put("last-updated", "2026-06-14"); // absent: appended before fence

        String patched = Main.patchFrontMatter(content, updates);

        // Quoted title and other lines preserved byte-for-byte.
        assertThat(patched).contains("title: \"Subtitle: a detail with : colons\"");
        assertThat(patched).contains("id: R1");
        assertThat(patched).contains("depends-on: []");
        // status replaced on its own line; old value gone.
        assertThat(patched).contains("status: Ready");
        assertThat(patched).doesNotContain("status: Spec");
        // absent key appended just before the closing fence.
        assertThat(patched).contains("last-updated: 2026-06-14\n---");
        // body untouched.
        assertThat(patched).contains("# body stays put");
        // round-trips through the parser without throwing.
        Main.ParsedFile parsed = Main.parseFrontMatter(patched);
        assertThat(parsed.frontMatter().get("title"))
            .isEqualTo("Subtitle: a detail with : colons");
        assertThat(parsed.frontMatter().get("status")).isEqualTo("Ready");
    }

    private static Path writeItem(Path dir, String slug, int id, String status,
                                  String title) throws IOException {
        // Mirrors what the `create` subcommand writes: a double-quoted title
        // with embedded quotes escaped. The slug is colon-free; the title is
        // not, which is the case the date-column test helper does not exercise.
        String content = "---\n"
            + "id: R" + id + "\n"
            + "title: \"" + title.replace("\"", "\\\"") + "\"\n"
            + "status: " + status + "\n"
            + "depends-on: []\n"
            + "---\n\n# " + slug + "\n";
        Path file = dir.resolve(slug + ".md");
        Files.writeString(file, content);
        return file;
    }
}
