package no.sikt.graphitron.generators.dependencies;

import com.palantir.javapoet.FieldSpec;
import no.sikt.graphitron.definitions.helpers.ServiceWrapper;

import javax.lang.model.element.Modifier;

import static no.sikt.graphitron.generators.codebuilding.VariableNames.CONTEXT_NAME;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * A dependency on a manually defined service class. Intended for mutation resolvers.
 */
public class ServiceDependency extends NamedDependency {
    private final ServiceWrapper service;

    public ServiceDependency(ServiceWrapper service) {
        super(service.getServiceClassName());
        this.service = service;
    }

    @Override
    public FieldSpec getSpec() {
        return FieldSpec
                .builder(getTypeName(), uncapitalize(getName()), Modifier.PRIVATE)
                .initializer("new $T($N)", getTypeName(), CONTEXT_NAME)
                .build();
    }

    public ServiceWrapper getService() {
        return service;
    }
}
