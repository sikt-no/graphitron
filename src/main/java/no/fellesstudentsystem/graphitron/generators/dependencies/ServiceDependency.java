package no.fellesstudentsystem.graphitron.generators.dependencies;

import com.squareup.javapoet.FieldSpec;
import no.fellesstudentsystem.graphitron.definitions.helpers.ServiceWrapper;

import javax.lang.model.element.Modifier;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.CONTEXT_NAME;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * A dependency on a manually defined service class. Intended for mutation resolvers.
 */
public class ServiceDependency extends NamedDependency {

    public ServiceDependency(String name, String path) {
        super(name, path);
    }

    public ServiceDependency(ServiceWrapper wrapper) {
        super(wrapper.getServiceName(), wrapper.getPackageName());
    }

    public FieldSpec getSpec() {
        return FieldSpec
                .builder(getClassName(), uncapitalize(getName()), Modifier.PRIVATE)
                .initializer("new $T($N)", getClassName(), CONTEXT_NAME)
                .build();
    }
}
