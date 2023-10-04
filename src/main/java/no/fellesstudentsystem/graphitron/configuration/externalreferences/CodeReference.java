package no.fellesstudentsystem.graphitron.configuration.externalreferences;

import graphql.language.DirectivesContainer;
import no.fellesstudentsystem.graphql.directives.DirectiveHelpers;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam;

import static no.fellesstudentsystem.graphql.directives.DirectiveHelpers.*;
import static no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam.METHOD;
import static no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam.NAME;

/**
 * Simple representation of a code reference input type.
 */
public class CodeReference {
    private final String schemaClassReference, methodName;

    public CodeReference(DirectivesContainer<?> field, GenerationDirective fromDirective, GenerationDirectiveParam fromParam) {
        this(field, fromDirective, fromParam, "");
    }

    public CodeReference(DirectivesContainer<?> field, GenerationDirective fromDirective, GenerationDirectiveParam fromParam, String defaultMethodName) {
        var referenceFields = getDirectiveArgumentObjectFields(field, fromDirective, fromParam);
        schemaClassReference = stringValueOf(getObjectFieldByName(referenceFields, NAME));
        methodName = getOptionalObjectFieldByName(referenceFields, METHOD).map(DirectiveHelpers::stringValueOf).orElse(defaultMethodName);
    }

    public CodeReference(String schemaClassReference, String methodName) {
        this.schemaClassReference = schemaClassReference;
        this.methodName = methodName;
    }

    public String getSchemaClassReference() {
        return schemaClassReference;
    }

    public String getMethodName() {
        return methodName;
    }
}
