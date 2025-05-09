package no.sikt.graphitron.generators.abstractions;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Superclass for any select resolver generator classes.
 */
abstract public class KickstartResolverClassGenerator<T extends GenerationTarget> extends ResolverClassGenerator<T> {
    public static final String
            DEFAULT_SAVE_DIRECTORY_NAME = "resolvers",
            FILE_NAME_SUFFIX = "GeneratedResolver",
            INTERFACE_FILE_NAME_SUFFIX = "Resolver";

    public KickstartResolverClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DEFAULT_SAVE_DIRECTORY_NAME;
    }

    @Override
    public TypeSpec.Builder getSpec(String className, List<? extends MethodGenerator> generators) {
        var spec = super.getSpec(className, generators);
        if (generators.stream().anyMatch(g -> !((KickstartResolverMethodGenerator)g).generatesAll())) {
            spec.addModifiers(Modifier.ABSTRACT);
        }
        spec.addSuperinterface(ClassName.get(GeneratorConfig.generatedResolversPackage(), className + getExpectedInterfaceSuffix()));
        setDependencies(generators, spec);
        return spec;
    }

    /**
     * @return The suffix that is appended to the name of type resolvers by graphql-codegen.
     */
    public String getExpectedInterfaceSuffix() {
        return INTERFACE_FILE_NAME_SUFFIX;
    }

    @Override
    public String getFileNameSuffix() {
        return FILE_NAME_SUFFIX;
    }
}
