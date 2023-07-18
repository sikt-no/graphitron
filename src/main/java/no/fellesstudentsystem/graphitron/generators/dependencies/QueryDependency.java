package no.fellesstudentsystem.graphitron.generators.dependencies;

import com.squareup.javapoet.FieldSpec;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.generators.abstractions.DBClassGenerator;

import javax.lang.model.element.Modifier;

import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.INJECT;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

public class QueryDependency extends NamedDependency {
    public QueryDependency(String name, String subPath) {
        super(name, GeneratorConfig.outputPackage() + "." + DBClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + subPath);
    }

    public FieldSpec getSpec() {
        return FieldSpec
                .builder(getClassName(), uncapitalize(getName()), Modifier.PRIVATE)
                .addAnnotation(INJECT.className)
                .build();
    }
}
