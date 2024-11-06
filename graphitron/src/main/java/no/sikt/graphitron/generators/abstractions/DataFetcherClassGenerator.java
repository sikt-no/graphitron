package no.sikt.graphitron.generators.abstractions;

import com.squareup.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.List;

abstract public class DataFetcherClassGenerator<T extends GenerationTarget> extends AbstractClassGenerator<T> {
    public static final String DEFAULT_SAVE_DIRECTORY_NAME = "datafetchers", FILE_NAME_SUFFIX = "GeneratedDataFetcher";

    public DataFetcherClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec.Builder getSpec(String className, List<MethodGenerator<? extends GenerationTarget>> generators) {
        var spec = super.getSpec(className, generators);
        if (generators.stream().anyMatch(g -> !g.generatesAll())) {
            spec.addModifiers(Modifier.ABSTRACT);
        }
        setDependencies(generators, spec);
        return spec;
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DEFAULT_SAVE_DIRECTORY_NAME;
    }

    @Override
    public String getFileNameSuffix() {
        return FILE_NAME_SUFFIX;
    }
}
