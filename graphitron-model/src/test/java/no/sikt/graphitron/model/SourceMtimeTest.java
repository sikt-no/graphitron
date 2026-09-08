package no.sikt.graphitron.model;

import no.sikt.graphitron.model.test.CapturedStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The source registry records when each file was last written, which is the one question a content
 * hash cannot answer.
 *
 * <p>{@code stamp} says whether the bytes changed and {@code read_at} says when we established that.
 * Neither orders two files against each other, and ordering them is what a report needs when several
 * documents declare one coordinate: without it a reader can say that two declarations exist and not
 * which one arrived second, so the message has to name both symmetrically and the developer is left
 * to work out which of their files is the one they just saved.
 *
 * <p>Read where the file is already being opened to be hashed, so it costs no extra visit.
 */
class SourceMtimeTest {

    private static final String FIRST = "type Query { a: String }\n";
    private static final String SECOND = "type Other { b: String }\n";

    @Test
    @DisplayName("a schema file's modification time is recorded, and it is the file's own")
    void theModificationTimeIsRecorded(@TempDir Path tmp) throws Exception {
        try (var store = CapturedStore.ofFiles(tmp, "a.graphqls", FIRST, "b.graphqls", SECOND)) {
            var rows = store.dsl()
                .select(STORE_SOURCE.SOURCE_NAME, STORE_SOURCE.MTIME)
                .from(STORE_SOURCE)
                .where(STORE_SOURCE.SOURCE_KIND.eq("SCHEMA_FILE"))
                .and(STORE_SOURCE.SOURCE_NAME.like(tmp.toString() + "%"))
                .fetch();

            assertThat(rows)
                .as("both schema files are registry rows, so both are candidates to order")
                .hasSize(2);

            for (var row : rows) {
                Path file = Path.of(row.value1());
                assertThat(row.value2())
                    .as("the modification time of %s is read when the file is read, so a reader "
                        + "ordering two declarations has something to order them by", file)
                    .isNotNull()
                    .isEqualTo(onDisk(file));
            }
        }
    }

    /** What the filesystem says, to the second, which is the resolution the column is compared at. */
    private static LocalDateTime onDisk(Path file) throws Exception {
        return LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault())
            .withNano(0);
    }
}
