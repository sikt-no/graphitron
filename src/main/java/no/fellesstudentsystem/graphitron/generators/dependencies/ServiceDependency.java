package no.fellesstudentsystem.graphitron.generators.dependencies;

import com.squareup.javapoet.FieldSpec;

import javax.lang.model.element.Modifier;

import static org.apache.commons.lang3.StringUtils.uncapitalize;

public class ServiceDependency extends NamedDependency {

    public ServiceDependency(String name, String path) {
        super(name, path);
    }

    public FieldSpec getSpec() {
        return FieldSpec
                .builder(getClassName(), uncapitalize(getName()), Modifier.PRIVATE)
                .initializer("new $T($N)", getClassName(), CONTEXT_NAME)
                .build();
    }
}
