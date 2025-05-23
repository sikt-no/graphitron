package no.sikt.graphitron.generators.abstractions;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.codeinterface.wiring.ClassWiringContainer;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringContainer;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.generators.codeinterface.wiring.WiringContainer.asClassWiring;

abstract public class DataFetcherClassGenerator<T extends GenerationTarget> extends ResolverClassGenerator<T> {
    public static final String DEFAULT_SAVE_DIRECTORY_NAME = "resolvers", FILE_NAME_SUFFIX = "GeneratedDataFetcher";
    protected final List<ClassWiringContainer> fetcherWiringContainer = new ArrayList<>();

    public DataFetcherClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec.Builder getSpec(String className, List<? extends MethodGenerator> generators) {
        var spec = super.getSpec(className, generators);
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

    public List<ClassWiringContainer> getGeneratedDataFetchers() {
        return fetcherWiringContainer;
    }

    protected void addFetchers(List<WiringContainer> containers, ClassName className) {
        fetcherWiringContainer.addAll(asClassWiring(containers, className));
    }
}
