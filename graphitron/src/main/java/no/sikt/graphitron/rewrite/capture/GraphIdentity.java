package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.rewrite.compile.CompileFacts;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The graph a capture run writes under, as a <em>coordinate</em> and nothing else: the partition
 * every SDL row of the run carries, and the base directory the graph's ownership is checked
 * against. Deliberately not also capture's subject, which is what {@link SubjectConfig} is:
 * conflating the two is what put a nullable recipe on this record, and billed callers that hold
 * no configuration at all ({@link CompileFacts} writes {@code javac_diagnostic} rows) for a
 * component they could only ever synthesise.
 */
public record GraphIdentity(String name, Path baseDir) {
    public GraphIdentity {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(baseDir, "baseDir");
        if (name.isBlank()) {
            throw new IllegalArgumentException("graph name must be non-blank");
        }
        baseDir = baseDir.toAbsolutePath().normalize();
    }
}
