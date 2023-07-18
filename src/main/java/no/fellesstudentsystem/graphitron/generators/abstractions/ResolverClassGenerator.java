package no.fellesstudentsystem.graphitron.generators.abstractions;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Superclass for any select resolver generator classes.
 */
abstract public class ResolverClassGenerator<T extends GenerationTarget> implements ClassGenerator<T> {
    public static final String
            DEFAULT_SAVE_DIRECTORY_NAME = "resolvers",
            FILE_NAME_SUFFIX = "GeneratedResolver",
            INTERFACE_FILE_NAME_SUFFIX = "Resolver";

    protected final ProcessedSchema processedSchema;

    public ResolverClassGenerator(ProcessedSchema processedSchema) {
        this.processedSchema = processedSchema;
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DEFAULT_SAVE_DIRECTORY_NAME;
    }

    @Override
    public TypeSpec.Builder getSpec(String className, List<MethodGenerator<? extends AbstractField>> generators) {
        var spec = TypeSpec.classBuilder(className + FILE_NAME_SUFFIX);
        if (generators.stream().anyMatch(g -> !g.generatesAll())) {
            spec.addModifiers(Modifier.ABSTRACT);
        }

        spec
                .addSuperinterface(ClassName.get(GeneratorConfig.generatedResolversPackage(), className + getExpectedInterfaceSuffix()))
                .addModifiers(Modifier.PUBLIC)
                .addMethods(
                        generators
                                .stream()
                                .map(MethodGenerator::generateAll)
                                .flatMap(List::stream)
                                .collect(Collectors.toList())
                );

        setDependencies(generators, spec);
        return spec;
    }

    protected void setDependencies(List<MethodGenerator<? extends AbstractField>> generators, TypeSpec.Builder spec) {
        generators
                .stream()
                .flatMap(gen -> gen.getDependencySet().stream())
                .distinct()
                .sorted()
                .forEach(dep -> spec.addField(dep.getSpec()));
    }

    @Override
    public void writeToFile(TypeSpec generatedClass, String path, String packagePath) {
        writeToFile(generatedClass, path, packagePath, DEFAULT_SAVE_DIRECTORY_NAME);
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

    public String getExpectedInterfaceSuffix() {
        return INTERFACE_FILE_NAME_SUFFIX;
    }
}
