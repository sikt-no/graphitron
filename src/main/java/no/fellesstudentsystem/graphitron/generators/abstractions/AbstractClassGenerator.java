package no.fellesstudentsystem.graphitron.generators.abstractions;

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.dependencies.ServiceDependency;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

abstract public class AbstractClassGenerator<T extends GenerationTarget> implements ClassGenerator<T> {

    protected final ProcessedSchema processedSchema;

    public AbstractClassGenerator(ProcessedSchema processedSchema) {
        this.processedSchema = processedSchema;
    }

    @Override
    public TypeSpec.Builder getSpec(String className, List<MethodGenerator<? extends GenerationTarget>> generators) {
        return TypeSpec
                .classBuilder(className + getFileNameSuffix())
                .addModifiers(Modifier.PUBLIC)
                .addMethods(
                        generators
                                .stream()
                                .map(MethodGenerator::generateAll)
                                .flatMap(List::stream)
                                .collect(Collectors.toList())
                );
    }

    /**
     * Add all the dependency fields for this class.
     * @param generators Generators to extract dependencies from.
     */
    protected void setDependencies(List<MethodGenerator<? extends GenerationTarget>> generators, TypeSpec.Builder spec) {
        generators
                .stream()
                .flatMap(gen -> gen.getDependencySet().stream())
                .distinct()
                .sorted()
                .filter(dep -> !(dep instanceof ServiceDependency)) // Inelegant solution, but it should work for now.
                .forEach(dep -> spec.addField(dep.getSpec()));
    }

    @Override
    public void writeToFile(TypeSpec generatedClass, String path, String packagePath) {
        writeToFile(generatedClass, path, packagePath, getDefaultSaveDirectoryName());
    }

    @Override
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
}
