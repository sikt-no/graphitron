package no.sikt.graphitron.generators.codebuilding;

import static org.apache.commons.lang3.StringUtils.capitalize;

public class VariableNames {
    public final static String
            SELECTION_SET_NAME = "selectionSet",
            IDS_NAME = "ids",
            RESOLVER_KEYS_NAME = "resolverKeys",
            PAGE_SIZE_NAME = "pageSize",
            CONTEXT_NAME = "ctx",
            GRAPH_CONTEXT_NAME = "_graphCtx",
            VALIDATION_ERRORS_NAME = "validationErrors",
            TRANSFORMER_NAME = "transform",
            ORDER_FIELDS_NAME = "orderFields",
            GRAPHITRON_CONTEXT_NAME = "graphitronContext",
            PATH_NAME = "path",
            PATH_INDEX_NAME = "indexPath",
            PATH_HERE_NAME = "pathHere",
            VARIABLE_ARGS = "_args",
            VARIABLE_ENV = "env",
            VARIABLE_SELECT = "select",
            METHOD_ARGS_NAME = "getArguments",
            METHOD_CONTEXT_NAME = "get" + capitalize(CONTEXT_NAME),
            METHOD_VALIDATE_NAME = "validate",
            METHOD_ENV_NAME = "get" + capitalize(VARIABLE_ENV),
            METHOD_SELECT_NAME = "get" + capitalize(VARIABLE_SELECT),
            METHOD_SOURCE_NAME = "getSource",
            METHOD_GRAPH_CONTEXT = "getGraphQlContext",
            METHOD_SET_RECORD_ID = "setId",
            METHOD_SET_RECORD_REFERENCE_ID = "setReferenceId",
            VARIABLE_VALIDATION_ERRORS = "validationErrors",
            VARIABLE_PATHS_FOR_PROPERTIES = "pathsForProperties",
            VARIABLE_TYPE_NAME = "_targetType",
            VARIABLE_TYPE_ID = "_typeId",
            VARIABLE_FETCHER_NAME = "_fetcher",
            VARIABLE_INPUT_MAP = "_inputMap",
            VARIABLE_OBJECT = "_obj",
            VARIABLE_RESULT = "_result",
            VARIABLE_GRAPHITRON_CONTEXT = "_graphitronContext",
            VARIABLE_INTERNAL_ITERATION = "internal_it_", // To avoid conflicts with potential schema names.
            NODE_ID_HANDLER_NAME = "nodeIdHandler",
            NODE_ID_STRATEGY_NAME = "nodeIdStrategy",
            NODE_MAP_NAME = "TABLE_TO_TYPE";
}
