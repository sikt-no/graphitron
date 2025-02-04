package no.sikt.graphitron.generators.abstractions;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.datafetchers.wiring.ClassWiringContainer;
import no.sikt.graphitron.generators.datafetchers.wiring.WiringContainer;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

abstract public class DataFetcherClassGenerator<T extends GenerationTarget> extends AbstractClassGenerator<T> {
    public static final String DEFAULT_SAVE_DIRECTORY_NAME = "datafetchers", FILE_NAME_SUFFIX = "GeneratedDataFetcher";
    protected final List<ClassWiringContainer>
            fetcherWiringContainer = new ArrayList<>(),
            typeWiringContainer = new ArrayList<>();

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

    public List<ClassWiringContainer> getGeneratedDataFetchers() {
        return fetcherWiringContainer;
    }

    public List<ClassWiringContainer> getGeneratedTypeResolvers() {
        return typeWiringContainer;
    }

    protected void addFetchers(List<WiringContainer> containers, ClassName className) {
        fetcherWiringContainer.addAll(asClassWiring(containers, className));
    }

    protected void addTypeResolvers(List<WiringContainer> containers, ClassName className) {
        typeWiringContainer.addAll(asClassWiring(containers, className));
    }

    private static List<ClassWiringContainer> asClassWiring(List<WiringContainer> containers, ClassName className) {
        return containers
                .stream()
                .map(it -> new ClassWiringContainer(it, className))
                .collect(Collectors.toList());
    }
}
