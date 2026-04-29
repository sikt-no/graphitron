package no.sikt.graphitron.definitions.helpers;

import graphql.language.DirectivesContainer;
import graphql.language.NamedNode;

import java.util.LinkedHashMap;
import java.util.Map;

import static no.sikt.graphql.directives.DirectiveHelpers.getOptionalDirectiveArgumentString;
import static no.sikt.graphql.directives.GenerationDirective.PROCEDURE_CALL;
import static no.sikt.graphql.directives.GenerationDirectiveParam.ARGUMENTS;
import static no.sikt.graphql.directives.GenerationDirectiveParam.PROCEDURE;

/**
 * Wrapper for handling the configuration of a single {@code @experimental_procedureCall} directive usage on a field.
 *
 * @param procedureName The routine name as written by the user in the directive. Typically the unqualified database
 *                      name (matches jOOQ's {@code Routine.getName()}, usually snake_case). May be schema-qualified as
 *                      {@code schema.routine} when the bare name is ambiguous across schemas. Matched case-insensitively.
 * @param argumentMap   Mapping of routine IN parameter names (exactly what jOOQ's {@code Parameter.getName()} returns,
 *                      typically snake_case) to columns on the surrounding table. Iterates in directive source order;
 *                      the routine's declaration order is used at codegen time.
 */
public record ProcedureCall(String procedureName, Map<String, String> argumentMap) {
    public <T extends NamedNode<T> & DirectivesContainer<T>> ProcedureCall(T field) {
        this(
                getOptionalDirectiveArgumentString(field, PROCEDURE_CALL, PROCEDURE).orElse(""),
                parseArguments(getOptionalDirectiveArgumentString(field, PROCEDURE_CALL, ARGUMENTS).orElse(""))
        );
    }

    private static Map<String, String> parseArguments(String rawArguments) {
        return rawArguments.isBlank() ? new LinkedHashMap<>() : SelectionParser.parseSelection(rawArguments).values();
    }
}
