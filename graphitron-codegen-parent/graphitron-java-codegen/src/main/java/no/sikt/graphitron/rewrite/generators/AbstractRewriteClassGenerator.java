package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeSpec;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Base class for rewrite-pipeline class generators.
 *
 * <p>Provides standard file I/O without the legacy {@code MethodGenerator} / dependency
 * machinery found in {@link no.sikt.graphitron.generators.abstractions.AbstractClassGenerator}.
 * Rewrite generators must not depend on the legacy infrastructure because:
 * <ul>
 *   <li>The legacy {@code getSpec(className, generators)} / {@code setDependencies} API is
 *       oriented around mutable method-generator lists that accumulate side-effects; the rewrite
 *       pipeline builds immutable {@link TypeSpec} values instead.</li>
 *   <li>Keeping the dependency boundary explicit prevents accidental coupling as both pipelines
 *       evolve independently.</li>
 * </ul>
 *
 * <p>Subclasses that need static imports in the emitted {@link JavaFile} (e.g. for jOOQ
 * {@code Tables.*}) should override {@link #writeToFile(TypeSpec, String, String, String)} and
 * {@link #writeToString(TypeSpec)} to customise the {@link JavaFile.Builder} before writing.
 */
public abstract class AbstractRewriteClassGenerator {

    /** Returns all {@link TypeSpec}s produced by this generator for the current schema. */
    public abstract List<TypeSpec> generateAll();

    /** Returns the sub-package name (e.g. {@code "rewrite.tables"}) within the output package. */
    public abstract String getDefaultSaveDirectoryName();

    /** Returns the class-name suffix appended to every generated file name, or {@code ""}. */
    public abstract String getFileNameSuffix();

    /** Writes all generated classes to the given output directory and package. */
    public void generateAllToDirectory(String path, String packagePath) {
        generateAll().forEach(it -> writeToFile(it, path, packagePath));
    }

    /** Writes a single generated class using the default save directory. */
    public void writeToFile(TypeSpec generatedClass, String path, String packagePath) {
        writeToFile(generatedClass, path, packagePath, getDefaultSaveDirectoryName());
    }

    /** Writes a single generated class to the given directory override within the package. */
    public void writeToFile(TypeSpec generatedClass, String path, String packagePath, String directoryOverride) {
        var file = JavaFile
            .builder(packagePath + "." + directoryOverride, generatedClass)
            .indent("    ")
            .build();
        try {
            file.writeTo(new File(path));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Returns a map of class name → rendered Java source for all generated classes. */
    public Map<String, String> generateAllAsMap() {
        return generateAll().stream().collect(Collectors.toMap(TypeSpec::name, this::writeToString));
    }

    /** Renders a single generated class to a Java source string (package is left empty). */
    public String writeToString(TypeSpec generatedClass) {
        return JavaFile.builder("", generatedClass).indent("    ").build().toString();
    }
}
