package no.sikt.graphitron.configuration.externalreferences;

import graphql.language.DirectivesContainer;
import graphql.language.ObjectField;
import no.sikt.graphql.directives.DirectiveHelpers;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.directives.GenerationDirectiveParam;

import java.util.List;

import static no.sikt.graphql.directives.DirectiveHelpers.*;
import static no.sikt.graphql.directives.GenerationDirectiveParam.CLASSNAME;
import static no.sikt.graphql.directives.GenerationDirectiveParam.METHOD;
import static no.sikt.graphql.directives.GenerationDirectiveParam.NAME;

/**
 * Simple representation of a code reference input type.
 */
public class CodeReference {
    private final String schemaClassReference, className, methodName;

    public CodeReference(DirectivesContainer<?> field, GenerationDirective fromDirective, GenerationDirectiveParam fromParam) {
        this(field, fromDirective, fromParam, "");
    }

    public CodeReference(DirectivesContainer<?> field, GenerationDirective fromDirective, GenerationDirectiveParam fromParam, String defaultMethodName) {
        this(getDirectiveArgumentObjectFields(field, fromDirective, fromParam), defaultMethodName);
    }

    public CodeReference(List<ObjectField> objectFields, String defaultMethodName) {
        this.schemaClassReference = getOptionalObjectFieldByName(objectFields, NAME).map(DirectiveHelpers::stringValueOf).orElse(null);
        this.className = getOptionalObjectFieldByName(objectFields, CLASSNAME).map(DirectiveHelpers::stringValueOf).orElse(null);
        this.methodName = getOptionalObjectFieldByName(objectFields, METHOD).map(DirectiveHelpers::stringValueOf).orElse(defaultMethodName);

        if (this.className == null && this.schemaClassReference == null) {
            throw new IllegalArgumentException("Can't find " + CLASSNAME.getName() + " or " + NAME.getName() + " in directive arguments.");
        }
    }

    public String getSchemaClassReference() {
        return schemaClassReference;
    }

    public String getClassName() {
        return className;
    }
    public String getMethodName() {
        return methodName;
    }
}
