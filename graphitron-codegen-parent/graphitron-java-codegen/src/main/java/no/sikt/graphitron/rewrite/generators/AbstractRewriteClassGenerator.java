package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeSpec;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Base class for rewrite-pipeline class generators.
 *
 * <p>Subclasses implement {@link #generateAll()} and {@link #getDefaultSaveDirectoryName()}.
 * The pipeline entry point is {@link #generateAllToDirectory}.
 */
public abstract class AbstractRewriteClassGenerator {

    /** Returns all {@link TypeSpec}s produced by this generator for the current schema. */
    public abstract List<TypeSpec> generateAll();

    /** Returns the sub-package name within the output package (e.g. {@code "rewrite.tables"}). */
    public abstract String getDefaultSaveDirectoryName();

    /** Writes all generated classes to the given output directory and package. */
    public final void generateAllToDirectory(String path, String packagePath) {
        var packageName = packagePath + "." + getDefaultSaveDirectoryName();
        generateAll().forEach(spec -> write(spec, path, packageName));
    }

    private void write(TypeSpec spec, String path, String packageName) {
        try {
            JavaFile.builder(packageName, spec).indent("    ").build().writeTo(new File(path));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
