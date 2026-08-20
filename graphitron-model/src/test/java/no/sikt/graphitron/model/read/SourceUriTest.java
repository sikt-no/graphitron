package no.sikt.graphitron.model.read;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two directions of the file spelling, pinned where both halves are: the store holds paths and
 * the wires that name a document by URI convert here, so what matters is that a value survives the
 * trip and that neither direction throws on input a caller can actually hold.
 */
class SourceUriTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("the forward trip is the platform's own path-to-URI rendering")
    void forwardTripIsPathToUri() {
        String path = "/tmp/test.graphqls";
        assertThat(SourceUri.of(path)).isEqualTo(Path.of(path).toUri().toString());
    }

    @Test
    @DisplayName("a value the platform will not accept as a path comes back as written")
    void anUnparseablePathComesBackUnchanged() {
        // Spelled as an escape rather than written in: a literal NUL byte in a .java file is legal
        // inside a string literal and makes the file binary to every tool that reads it.
        String notAPath = "\0bad-path";
        assertThat(SourceUri.of(notAPath)).isEqualTo(notAPath);
    }

    @Test
    @DisplayName("neither direction throws on an absent value")
    void absenceStaysAbsent() {
        assertThat(SourceUri.of(null)).isNull();
        assertThat(SourceUri.ofDirectory(null)).isNull();
        assertThat(SourceUri.sourceNameOf(null)).isEmpty();
    }

    @Test
    @DisplayName("a URI naming no local file resolves to no source name")
    void aNonFileUriResolvesToNothing() {
        assertThat(SourceUri.sourceNameOf("untitled:Untitled-1")).isEmpty();
        assertThat(SourceUri.sourceNameOf("https://example.test/schema.graphqls")).isEmpty();
    }

    @Test
    @DisplayName("a stored path round-trips through the URI form, spaces included")
    void aStoredPathRoundTrips() throws Exception {
        Path spaced = Files.createDirectories(tmp.resolve("with space"));
        for (Path file : java.util.List.of(
                tmp.resolve("plain.graphqls"), spaced.resolve("x.graphqls"))) {
            String stored = file.toAbsolutePath().normalize().toString();
            assertThat(SourceUri.sourceNameOf(SourceUri.of(stored))).contains(stored);
        }
    }

    @Test
    @DisplayName("a directory renders as the file URI truncated, so an existing directory grows no slash")
    void aDirectoryRendersByTruncationRatherThanConversion() throws Exception {
        Path directory = Files.createDirectories(tmp.resolve("schemas"));
        String fileUri = SourceUri.of(directory.resolve("s.graphqls").toString());

        assertThat(SourceUri.of(directory.toString()))
            .as("the trap: Path.toUri asks the filesystem, so converting a directory that exists "
                + "puts the existence of that directory on the wire")
            .endsWith("/");
        assertThat(SourceUri.ofDirectory(directory.toString()))
            .as("the rendered directory is the rendered file with its last segment removed")
            .isEqualTo(fileUri.substring(0, fileUri.lastIndexOf('/')))
            .doesNotEndWith("/");
    }

    @Test
    @DisplayName("a stored directory round-trips too, existing or not")
    void aStoredDirectoryRoundTrips() throws Exception {
        Path existing = Files.createDirectories(tmp.resolve("here"));
        Path missing = tmp.resolve("gone");
        for (Path directory : java.util.List.of(existing, missing)) {
            String stored = directory.toAbsolutePath().normalize().toString();
            assertThat(SourceUri.sourceNameOf(SourceUri.ofDirectory(stored))).contains(stored);
        }
    }
}
