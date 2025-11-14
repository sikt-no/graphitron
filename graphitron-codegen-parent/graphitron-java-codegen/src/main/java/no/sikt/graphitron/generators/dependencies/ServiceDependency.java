package no.sikt.graphitron.generators.dependencies;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.FieldSpec;

import javax.lang.model.element.Modifier;

import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_CONTEXT;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.servicePrefix;

/**
 * A dependency on a manually defined service class. Intended for mutation resolvers.
 */
public class ServiceDependency extends NamedDependency {
    public ServiceDependency(ClassName className) {
        super(className);
    }

    /**
     * @return The name of this dependency.
     */
    public String getName() {
        return servicePrefix(super.getName());
    }

    @Override
    public FieldSpec getSpec() {
        return FieldSpec
                .builder(getTypeName(), getName(), Modifier.PRIVATE)
                .initializer("new $T($N)", getTypeName(), VAR_CONTEXT)
                .build();
    }
}
