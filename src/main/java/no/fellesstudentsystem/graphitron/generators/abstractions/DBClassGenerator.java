package no.fellesstudentsystem.graphitron.generators.abstractions;

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;

/**
 * Superclass for any select query generator classes.
 */
abstract public class DBClassGenerator<T extends GenerationTarget> implements ClassGenerator<T> {
    public static final String DEFAULT_SAVE_DIRECTORY_NAME = "queries", FILE_NAME_SUFFIX = "DBQueries";

    protected final ProcessedSchema processedSchema;

    public DBClassGenerator(ProcessedSchema processedSchema) {
        this.processedSchema = processedSchema;
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DEFAULT_SAVE_DIRECTORY_NAME;
    }

    @Override
    public TypeSpec.Builder getSpec(String className, List<MethodGenerator<? extends AbstractField>> generators) {
        var spec = TypeSpec
                .classBuilder(className)
                .addModifiers(Modifier.PUBLIC)
                .addMethods(
                        generators
                                .stream()
                                .map(MethodGenerator::generateAll)
                                .flatMap(List::stream)
                                .collect(Collectors.toList())
                );

        generators
                .stream()
                .flatMap(gen -> gen.getDependencySet().stream())
                .distinct()
                .sorted()
                .forEach(dep -> spec.addField(dep.getSpec()));
        return spec;
    }

    @Override
    public void writeToFile(TypeSpec generatedClass, String path, String packagePath) {
        writeToFile(generatedClass, path, packagePath, getDefaultSaveDirectoryName());
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
}
