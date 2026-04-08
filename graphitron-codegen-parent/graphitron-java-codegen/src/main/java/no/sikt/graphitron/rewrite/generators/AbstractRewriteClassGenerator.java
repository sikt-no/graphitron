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
 * The pipeline entry point is {@link #generateAllToDirectory}; both writing and string
 * rendering go through {@link #buildFile}, which subclasses may override to add static
 * imports (e.g. for jOOQ {@code Tables.*}).
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

    /**
     * Creates the {@link JavaFile.Builder} for a generated class.
     *
     * <p>Override this method to add static imports before the file is written.
     * The {@code packageName} is the full output package
     * (e.g. {@code "com.example.rewrite.resolvers"}).
     */
    protected JavaFile.Builder buildFile(TypeSpec spec, String packageName) {
        return JavaFile.builder(packageName, spec).indent("    ");
    }

    private void write(TypeSpec spec, String path, String packageName) {
        try {
            buildFile(spec, packageName).build().writeTo(new File(path));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
