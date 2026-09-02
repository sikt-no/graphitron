package no.sikt.graphitron.rewrite.capture;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Where a generating run wrote. One value because the three travel together: they are present
 * together on any generating run and jointly answer one question, which is the family's grain
 * rule ("joint presence and joint meaning") rather than three loose components.
 */
public record OutputCoordinates(String outputPackage, String jooqPackage, Path outputDirectory) {
    public OutputCoordinates {
        Objects.requireNonNull(outputPackage, "outputPackage");
        Objects.requireNonNull(jooqPackage, "jooqPackage");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
    }
}
