package no.sikt.graphitron.generators.dependencies;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.FieldSpec;

import javax.lang.model.element.Modifier;

import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_CONTEXT;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * A dependency on a manually defined service class. Intended for mutation resolvers.
 */
public class ServiceDependency extends NamedDependency {
    public ServiceDependency(ClassName className) {
        super(className);
    }

    @Override
    public FieldSpec getSpec() {
        return FieldSpec
                .builder(getTypeName(), uncapitalize(getName()), Modifier.PRIVATE)
                .initializer("new $T($N)", getTypeName(), VAR_CONTEXT)
                .build();
    }
}
