package no.sikt.graphitron.generators.abstractions;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.generators.codebuilding.TypeNameFormat;
import no.sikt.graphitron.generators.dependencies.ServiceDependency;

import javax.lang.model.element.Modifier;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

abstract public class AbstractClassGenerator implements ClassGenerator {
    @Override
    public TypeSpec.Builder getSpec(String className, List<? extends MethodGenerator> generators) {
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

    public TypeSpec.Builder getSpec(String className, MethodGenerator generator) {
        return getSpec(className, List.of(generator));
    }

    /**
     * Add all the dependency fields for this class.
     * @param generators Generators to extract dependencies from.
     */
    protected void setDependencies(List<? extends MethodGenerator> generators, TypeSpec.Builder spec) {
        generators
                .stream()
                .flatMap(gen -> gen.getDependencySet().stream())
                .distinct()
                .sorted()
                .filter(dep -> !(dep instanceof ServiceDependency)) // Inelegant solution, but it should work for now.
                .forEach(dep -> spec.addField(dep.getSpec()));
    }

    @Override
    public Map<String, String> generateAllAsMap() {
        return generateAll().stream().collect(Collectors.toMap(TypeSpec::name, this::writeToString));
    }

    @Override
    public void generateAllToDirectory(String path, String packagePath) {
        generateAll().forEach(it -> writeToFile(it, path, packagePath));
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

    @Override
    public String writeToString(TypeSpec generatedClass) {
        return JavaFile.builder("", generatedClass).indent("    ").build().toString();
    }

    /**
     * @param name Name of the class.
     * @return ClassName based on the default output package and save directory.
     */
    public ClassName getGeneratedClassName(String name) {
        return TypeNameFormat.getGeneratedClassName(getDefaultSaveDirectoryName(), name);
    }
}
