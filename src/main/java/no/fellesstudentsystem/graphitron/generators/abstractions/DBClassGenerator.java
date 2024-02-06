package no.fellesstudentsystem.graphitron.generators.abstractions;

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.KEYS;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.TABLES;

/**
 * Superclass for any select query generator classes.
 */
abstract public class DBClassGenerator<T extends GenerationTarget> extends AbstractClassGenerator<T> {
    public static final String DEFAULT_SAVE_DIRECTORY_NAME = "queries", FILE_NAME_SUFFIX = "DBQueries";

    public DBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DEFAULT_SAVE_DIRECTORY_NAME;
    }

    @Override
    public TypeSpec.Builder getSpec(String className, List<MethodGenerator<? extends GenerationTarget>> generators) {
        var spec = super.getSpec(className, generators);
        setDependencies(generators, spec);
        return spec;
    }

    @Override
    public void writeToFile(TypeSpec generatedClass, String path, String packagePath, String directoryOverride) {
        var file = JavaFile
                .builder(packagePath + "." + directoryOverride, generatedClass)
                .addStaticImport(TABLES.className, "*")
                .addStaticImport(KEYS.className, "*")
                .indent("    ")
                .build();
        try {
            file.writeTo(new File(path));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getFileNameSuffix() {
        return FILE_NAME_SUFFIX;
    }
}
