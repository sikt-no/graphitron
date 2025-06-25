package no.sikt.graphitron.example.frontgen.generate;

import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.abstractions.MethodGenerator;
import no.sikt.graphitron.javapoet.TypeSpec;

import java.util.List;
import java.util.Map;

public class ComponentGenerator implements ClassGenerator {


    @Override
    public List<TypeSpec> generateAll() {
        return List.of();
    }

    @Override
    public void generateAllToDirectory(String path, String packagePath) {

    }

    @Override
    public void writeToFile(TypeSpec generatedClass, String path, String packagePath) {

    }

    @Override
    public void writeToFile(TypeSpec generatedClass, String path, String packagePath, String directoryOverride) {

    }

    @Override
    public Map<String, String> generateAllAsMap() {
        return Map.of();
    }

    @Override
    public String writeToString(TypeSpec generatedClass) {
        return "";
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return "";
    }

    @Override
    public String getFileNameSuffix() {
        return "";
    }

    @Override
    public TypeSpec.Builder getSpec(String className, List<? extends MethodGenerator> generators) {
        return null;
    }
}
