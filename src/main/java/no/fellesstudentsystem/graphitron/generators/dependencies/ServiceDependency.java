package no.fellesstudentsystem.graphitron.generators.dependencies;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;

import javax.lang.model.element.Modifier;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.CONTEXT_NAME;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * A dependency on a manually defined service class. Intended for mutation resolvers.
 */
public class ServiceDependency extends NamedDependency {
    public ServiceDependency(ClassName type) {
        super(type);
    }

    public FieldSpec getSpec() {
        return FieldSpec
                .builder(getTypeName(), uncapitalize(getName()), Modifier.PRIVATE)
                .initializer("new $T($N)", getTypeName(), CONTEXT_NAME)
                .build();
    }
}
