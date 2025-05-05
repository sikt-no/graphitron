package no.sikt.graphitron.generators.abstractions;

import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.mappings.TableReflection;
import no.sikt.graphql.schema.ProcessedSchema;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Superclass for any select query generator classes.
 */
abstract public class DBClassGenerator<T extends GenerationTarget> extends AbstractSchemaClassGenerator<T> {
    public static final String DEFAULT_SAVE_DIRECTORY_NAME = "queries", FILE_NAME_SUFFIX = "DBQueries";

    public DBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DEFAULT_SAVE_DIRECTORY_NAME;
    }

    @Override
    public TypeSpec.Builder getSpec(String className, List<? extends MethodGenerator> generators) {
        var spec = super.getSpec(className, generators);
        setDependencies(generators, spec);
        return spec;
    }

    protected Set<Class<?>> getStaticImports() {
        var union = new HashSet<>(TableReflection.getClassFromSchemas("Tables"));
        return union;
    }

    @Override
    public void writeToFile(TypeSpec generatedClass, String path, String packagePath, String directoryOverride) {
        var fileBuilder = JavaFile
                .builder(packagePath + "." + directoryOverride, generatedClass)
                .indent("    ");

        getStaticImports().forEach(it -> fileBuilder.addStaticImport(it, "*"));

        var file = fileBuilder.build();
        try {
            file.writeTo(new File(path));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String writeToString(TypeSpec generatedClass) {
        var fileBuilder = JavaFile.builder("", generatedClass).indent("    ");
        getStaticImports().forEach(it -> fileBuilder.addStaticImport(it, "*"));
        return fileBuilder.build().toString();
    }

    @Override
    public String getFileNameSuffix() {
        return FILE_NAME_SUFFIX;
    }
}
