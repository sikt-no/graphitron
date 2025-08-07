package no.sikt.graphitron.generators.dependencies;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.generators.db.DBClassGenerator;

import javax.lang.model.element.Modifier;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.declare;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getGeneratedClassName;
import static no.sikt.graphitron.mappings.JavaPoetClassName.INJECT;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * A dependency on a generated Query class. Intended for resolvers.
 */
public class QueryDependency extends NamedDependency {
    public QueryDependency(String name, String subPath) {
        super(getGeneratedClassName(DBClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + subPath, name));
    }

    @Override
    public CodeBlock getDeclarationCode() {
        return declare(uncapitalize(getName()), getTypeName());
    }

    @Override
    public FieldSpec getSpec() {
        return FieldSpec
                .builder(getTypeName(), uncapitalize(getName()), Modifier.PRIVATE)
                .addAnnotation(INJECT.className)
                .build();
    }
}
