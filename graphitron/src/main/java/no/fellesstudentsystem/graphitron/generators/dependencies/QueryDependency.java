package no.fellesstudentsystem.graphitron.generators.dependencies;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.generators.abstractions.DBClassGenerator;

import javax.lang.model.element.Modifier;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.declare;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.INJECT;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * A dependency on a generated Query class. Intended for resolvers.
 */
public class QueryDependency extends NamedDependency {
    public QueryDependency(String name, String subPath) {
        super(ClassName.get(GeneratorConfig.outputPackage() + "." + DBClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + subPath, name));
    }

    @Override
    public CodeBlock getDeclarationCode() {
        return declare(uncapitalize(getName()), CodeBlock.of("new $T()", getTypeName()));
    }

    @Override
    public FieldSpec getSpec() {
        return FieldSpec
                .builder(getTypeName(), uncapitalize(getName()), Modifier.PRIVATE)
                .addAnnotation(INJECT.className)
                .build();
    }
}
