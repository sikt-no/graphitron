package no.sikt.graphitron.model.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One module of the workspace a run belongs to: its {@code groupId:artifactId}, where it lives, and
 * where its build writes its classes and its jar.
 *
 * @param coordinate        {@code groupId:artifactId}
 * @param baseDirectory     the directory holding the module's pom
 * @param classesDirectory  where its build writes its classes
 * @param buildDirectory    where its build writes everything else, its jar among them
 */
public record WorkspaceModule(String coordinate, Path baseDirectory, Path classesDirectory,
                              Path buildDirectory) {

    public WorkspaceModule {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(baseDirectory, "baseDirectory");
        Objects.requireNonNull(classesDirectory, "classesDirectory");
        Objects.requireNonNull(buildDirectory, "buildDirectory");
    }

    /** A module laid out by convention, its build writing under {@code target}. */
    public static WorkspaceModule conventional(String coordinate, Path baseDirectory) {
        Path base = baseDirectory.toAbsolutePath().normalize();
        Path build = base.resolve("target");
        return new WorkspaceModule(coordinate, base, build.resolve("classes"), build);
    }
}
