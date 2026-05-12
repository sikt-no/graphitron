package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins R143: per-item `created:` and `last-updated:` keys, stamped by the
 * `create` and `status` subcommands, surfaced in the rendered roadmap as a
 * new "Updated" column. The validator is permissive on absent keys; pre-R143
 * items render `last-updated:` only once they next transition and never get
 * a synthetic `created:`.
 */
class RoadmapDateColumnTest {

    @Test
    void create_stampsBothDatesToday(@TempDir Path dir) throws IOException {
        Main.main(new String[]{"create", dir.toString(), "new-thing",
            "--title", "New Thing"});

        String written = Files.readString(dir.resolve("new-thing.md"));
        String today = LocalDate.now().toString();
        assertThat(written).contains("created: " + today);
        assertThat(written).contains("last-updated: " + today);
    }

    @Test
    void status_stampsLastUpdatedAndPreservesCreated(@TempDir Path dir) throws IOException {
        Path file = writePlan(dir, "old-thing", 1, "Spec",
            "created: 2025-01-01\nlast-updated: 2025-06-01");

        String previous = Main.applyStatusTransition(file, "Ready");

        assertThat(previous).isEqualTo("Spec");
        String written = Files.readString(file);
        // created: original date, not today.
        assertThat(written).contains("created: 2025-01-01");
        // last-updated: today, not the previous value.
        assertThat(written).contains("last-updated: " + LocalDate.now());
        assertThat(written).doesNotContain("last-updated: 2025-06-01");
        // Status flipped.
        assertThat(written).contains("status: Ready");
    }

    @Test
    void status_leavesCreatedAbsentForLegacyItem(@TempDir Path dir) throws IOException {
        // Pre-R143 item: no created: / last-updated: in front-matter.
        Path file = writePlan(dir, "legacy", 1, "Spec", "");

        Main.applyStatusTransition(file, "Ready");

        String written = Files.readString(file);
        // last-updated: stamped today.
        assertThat(written).contains("last-updated: " + LocalDate.now());
        // created: NEVER invented; the column is non-uniform forever for legacy items.
        assertThat(written).doesNotContain("created:");
    }

    @Test
    void status_rejectsInvalidTransition(@TempDir Path dir) throws IOException {
        Path file = writePlan(dir, "item", 1, "Backlog", "");

        // Backlog -> Ready is not a valid transition (must go through Spec).
        assertThatThrownBy(() -> Main.applyStatusTransition(file, "Ready"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot transition 'Backlog' -> 'Ready'");
    }

    @Test
    void status_rejectsDoneAsTarget(@TempDir Path dir) throws IOException {
        Path file = writePlan(dir, "item", 1, "In Review", "");

        // Done is a file-deletion transition; not allowed via the subcommand.
        assertThatThrownBy(() -> Main.applyStatusTransition(file, "Done"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'Done' is not an accepted target state");
    }

    @Test
    void status_rejectsDiscardedAsTarget(@TempDir Path dir) throws IOException {
        Path file = writePlan(dir, "item", 1, "Spec", "");

        assertThatThrownBy(() -> Main.applyStatusTransition(file, "Discarded"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'Discarded' is not an accepted target state");
    }

    @Test
    void resolveItemFile_acceptsId(@TempDir Path dir) throws IOException {
        writePlan(dir, "by-id", 42, "Backlog", "");

        Path resolved = Main.resolveItemFile(dir, "R42");

        assertThat(resolved).isNotNull();
        assertThat(resolved.getFileName().toString()).isEqualTo("by-id.md");
    }

    @Test
    void resolveItemFile_acceptsSlug(@TempDir Path dir) throws IOException {
        writePlan(dir, "by-slug", 7, "Backlog", "");

        Path resolved = Main.resolveItemFile(dir, "by-slug");

        assertThat(resolved).isNotNull();
        assertThat(resolved.getFileName().toString()).isEqualTo("by-slug.md");
    }

    @Test
    void resolveItemFile_returnsNullOnMiss(@TempDir Path dir) throws IOException {
        writePlan(dir, "exists", 1, "Backlog", "");

        assertThat(Main.resolveItemFile(dir, "R99")).isNull();
        assertThat(Main.resolveItemFile(dir, "missing")).isNull();
    }

    @Test
    void renderActive_includesUpdatedColumn() {
        Main.Item i = item("foo", "R1", "Spec", LocalDate.parse("2025-01-01"),
            LocalDate.parse("2025-06-01"));

        String rendered = Main.render(List.of(i));

        assertThat(rendered).contains("| ID | Item | Status | Updated | Plan |");
        // Both dates differ -> combined cell.
        assertThat(rendered).contains("2025-06-01 <sub>created 2025-01-01</sub>");
    }

    @Test
    void renderActive_collapsesWhenDatesMatch() {
        Main.Item i = item("foo", "R1", "Spec", LocalDate.parse("2025-01-01"),
            LocalDate.parse("2025-01-01"));

        String rendered = Main.render(List.of(i));

        // Single line; no created annotation when dates collide.
        assertThat(rendered).contains("| 2025-01-01 |");
        assertThat(rendered).doesNotContain("<sub>created");
    }

    @Test
    void renderActive_emptyCellWhenAbsent() {
        Main.Item i = item("foo", "R1", "Spec", null, null);

        String rendered = Main.render(List.of(i));

        assertThat(rendered).contains("| ID | Item | Status | Updated | Plan |");
        // Empty Updated cell rendered as the surrounding pipes with nothing between.
        assertThat(rendered).contains("Spec |  | [plan](foo.md)");
    }

    @Test
    void renderBacklog_includesUpdatedAnnotation() {
        Main.Item i = backlogItem("foo", "R1", "architecture",
            LocalDate.parse("2025-01-01"), LocalDate.parse("2025-06-01"));

        String rendered = Main.render(List.of(i));

        assertThat(rendered).contains("<sub>updated 2025-06-01, created 2025-01-01</sub>");
    }

    @Test
    void renderBacklog_collapsesWhenDatesMatch() {
        Main.Item i = backlogItem("foo", "R1", "architecture",
            LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-01"));

        String rendered = Main.render(List.of(i));

        assertThat(rendered).contains("<sub>updated 2025-01-01</sub>");
        assertThat(rendered).doesNotContain(", created");
    }

    @Test
    void renderBacklog_noAnnotationWhenAbsent() {
        Main.Item i = backlogItem("foo", "R1", "architecture", null, null);

        String rendered = Main.render(List.of(i));

        assertThat(rendered).doesNotContain("<sub>updated");
    }

    @Test
    void renderAdocStatusBoard_includesUpdatedColumn() {
        Main.Item i = item("foo", "R1", "Spec",
            LocalDate.parse("2025-01-01"), LocalDate.parse("2025-06-01"));

        String rendered = Main.renderAdocStatusBoard(List.of(i));

        assertThat(rendered).contains("[cols=\"1,4,1,1,1\", options=\"header\"]");
        assertThat(rendered).contains("| ID | Item | Status | Updated | Plan");
        assertThat(rendered).contains("2025-06-01 +\n[small]#created 2025-01-01#");
    }

    @Test
    void renderAdocStatusBoard_emptyCellWhenAbsent() {
        Main.Item i = item("foo", "R1", "Spec", null, null);

        String rendered = Main.renderAdocStatusBoard(List.of(i));

        // The "Updated" cell exists but is empty; ensure the next cell still renders.
        assertThat(rendered).contains("| \n| xref:plans/foo.adoc[plan]");
    }

    @Test
    void renderAdocPlan_includesCreatedAndUpdatedRows() {
        Main.Item i = item("foo", "R1", "Spec",
            LocalDate.parse("2025-01-01"), LocalDate.parse("2025-06-01"));

        String rendered = Main.renderAdocPlan(i);

        assertThat(rendered).contains("| Created | 2025-01-01");
        assertThat(rendered).contains("| Updated | 2025-06-01");
    }

    @Test
    void renderAdocPlan_suppressesAbsentDates() {
        Main.Item i = item("foo", "R1", "Spec", null, null);

        String rendered = Main.renderAdocPlan(i);

        assertThat(rendered).doesNotContain("| Created");
        assertThat(rendered).doesNotContain("| Updated");
    }

    @Test
    void parser_rejectsMalformedDate(@TempDir Path dir) throws IOException {
        writePlan(dir, "bad", 1, "Spec", "last-updated: not-a-date");

        assertThatThrownBy(() -> Main.readItems(dir))
            .hasMessageContaining("last-updated")
            .hasMessageContaining("not a valid YYYY-MM-DD date");
    }

    @Test
    void parser_acceptsAbsentDate(@TempDir Path dir) throws IOException {
        writePlan(dir, "ok", 1, "Spec", "");

        List<Main.Item> items = Main.readItems(dir);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).created()).isNull();
        assertThat(items.get(0).lastUpdated()).isNull();
    }

    private static Main.Item item(String slug, String id, String status,
                                  LocalDate created, LocalDate lastUpdated) {
        return new Main.Item(slug, id, slug, status, null, null,
            false, null, null, List.of(), created, lastUpdated, "");
    }

    private static Main.Item backlogItem(String slug, String id, String bucket,
                                         LocalDate created, LocalDate lastUpdated) {
        return new Main.Item(slug, id, slug, "Backlog", bucket, null,
            false, null, null, List.of(), created, lastUpdated, "");
    }

    private static Path writePlan(Path dir, String slug, int id, String status,
                                  String extraFrontMatter) throws IOException {
        StringBuilder fm = new StringBuilder();
        fm.append("---\n")
          .append("id: R").append(id).append('\n')
          .append("title: \"").append(slug).append("\"\n")
          .append("status: ").append(status).append('\n')
          .append("depends-on: []\n");
        if (!extraFrontMatter.isEmpty()) {
            fm.append(extraFrontMatter);
            if (!extraFrontMatter.endsWith("\n")) fm.append('\n');
        }
        fm.append("---\n\n# ").append(slug).append("\n");
        Path file = dir.resolve(slug + ".md");
        Files.writeString(file, fm.toString());
        return file;
    }
}
