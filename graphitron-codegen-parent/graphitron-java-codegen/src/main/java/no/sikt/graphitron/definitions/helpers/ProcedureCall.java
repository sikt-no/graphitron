package no.sikt.graphitron.definitions.helpers;

import graphql.language.DirectivesContainer;
import graphql.language.NamedNode;

import java.util.LinkedHashMap;
import java.util.Map;

import static no.sikt.graphql.directives.DirectiveHelpers.getOptionalDirectiveArgumentString;
import static no.sikt.graphql.directives.GenerationDirective.PROCEDURE_CALL;
import static no.sikt.graphql.directives.GenerationDirectiveParam.ARGUMENTS;
import static no.sikt.graphql.directives.GenerationDirectiveParam.PROCEDURE;
import static no.sikt.graphql.directives.GenerationDirectiveParam.TARGET;

/**
 * Wrapper for handling the configuration of a single {@code @experimental_procedureCall} directive usage on a field.
 *
 * @param procedureName The routine name as written by the user in the directive.
 * @param argumentMap   Mapping of routine IN parameter names (exactly what jOOQ's {@code Parameter.getName()} returns,
 *                      typically snake_case) to argument sources. The interpretation depends on the directive's mode:
 *                      in inline mode every value is a column;
 *                      in target mode every value is a GraphQL input argument on the directive-bearing field.
 * @param targetField   In target mode, the name of a scalar field in the data-fetcher's return type whose cell
 *                      the function call fills. {@code null} or blank in inline mode.
 */
public record ProcedureCall(String procedureName, Map<String, String> argumentMap, String targetField) {
    public <T extends NamedNode<T> & DirectivesContainer<T>> ProcedureCall(T field) {
        this(
                getOptionalDirectiveArgumentString(field, PROCEDURE_CALL, PROCEDURE).orElse(""),
                parseArguments(getOptionalDirectiveArgumentString(field, PROCEDURE_CALL, ARGUMENTS).orElse("")),
                getOptionalDirectiveArgumentString(field, PROCEDURE_CALL, TARGET).orElse(null)
        );
    }

    public boolean hasTarget() {
        return targetField != null && !targetField.isBlank();
    }

    private static Map<String, String> parseArguments(String rawArguments) {
        return rawArguments.isBlank() ? new LinkedHashMap<>() : SelectionParser.parseSelection(rawArguments).values();
    }
}
