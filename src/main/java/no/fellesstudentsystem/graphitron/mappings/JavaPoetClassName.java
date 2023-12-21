package no.fellesstudentsystem.graphitron.mappings;

import com.squareup.javapoet.ClassName;
import graphql.GraphQLError;
import graphql.relay.DefaultConnectionCursor;
import graphql.relay.DefaultEdge;
import graphql.relay.DefaultPageInfo;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphql.exception.MutationExceptionStrategyConfiguration;
import no.fellesstudentsystem.graphql.exception.ValidationViolationGraphQLException;
import no.fellesstudentsystem.graphql.helpers.EnvironmentUtils;
import no.fellesstudentsystem.graphql.helpers.FieldHelperHack;
import no.fellesstudentsystem.graphql.helpers.selection.ConnectionSelectionSet;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.fellesstudentsystem.graphql.helpers.validation.RecordValidator;
import no.fellesstudentsystem.graphql.relay.ConnectionImpl;
import no.fellesstudentsystem.graphql.relay.ExtendedConnection;

/**
 * Enum of all the classnames in use in the generator, which helps keep track of dependencies.
 * Preferably add any new non-dynamic class name retrievals here.
 */
public enum JavaPoetClassName {
    ARRAYS(ClassName.get(java.util.Arrays.class)),
    ARGUMENTS(ClassName.get(no.fellesstudentsystem.graphql.helpers.arguments.Arguments.class)),
    ARRAY_LIST(ClassName.get(java.util.ArrayList.class)),
    CLASS(ClassName.get(java.lang.Class.class)),
    COLLECTORS(ClassName.get(java.util.stream.Collectors.class)),
    COMPLETABLE_FUTURE(ClassName.get(java.util.concurrent.CompletableFuture.class)),
    CONNECTION_SELECTION_SET(ClassName.get(ConnectionSelectionSet.class)),
    DATA_FETCHING_ENVIRONMENT(ClassName.get(graphql.schema.DataFetchingEnvironment.class)),
    DATA_LOADER(ClassName.get(org.dataloader.DataLoader.class)),
    DATA_LOADER_FACTORY(ClassName.get(org.dataloader.DataLoaderFactory.class)),
    DSL(ClassName.get(org.jooq.impl.DSL.class)),
    DSL_CONTEXT(ClassName.get(org.jooq.DSLContext.class)),
    ENVIRONMENT_UTILS(ClassName.get(EnvironmentUtils.class)),
    EXCEPTION(ClassName.get(java.lang.Exception.class)),
    FIELD_HELPERS(ClassName.get(FieldHelperHack.class)),
    FUNCTIONS(ClassName.get(org.jooq.Functions.class)),
    GRAPHQL_ERROR(ClassName.get(GraphQLError.class)),
    HASH_MAP(ClassName.get(java.util.HashMap.class)),
    HASH_SET(ClassName.get(java.util.HashSet.class)),
    ILLEGAL_ARGUMENT_EXCEPTION(ClassName.get(IllegalArgumentException.class)),
    INJECT(ClassName.get(javax.inject.Inject.class)),
    INTEGER(ClassName.get(java.lang.Integer.class)),
    KEYS(ClassName.get(GeneratorConfig.getGeneratedJooqKeysClass())),
    LIST(ClassName.get(java.util.List.class)),
    MAPPED_BATCH_LOADER_WITH_CONTEXT(ClassName.get(org.dataloader.MappedBatchLoaderWithContext.class)),
    MAP(ClassName.get(java.util.Map.class)),
    MATH(ClassName.get(java.lang.Math.class)),
    MUTATION_EXCEPTION_STRATEGY_CONFIGURATION(ClassName.get(MutationExceptionStrategyConfiguration.class)),
    OBJECTS(ClassName.get(java.util.Objects.class)),
    OPTIONAL(ClassName.get(java.util.Optional.class)),
    OVERRIDE(ClassName.get(java.lang.Override.class)),
    PAYLOAD_CREATOR(ClassName.get(MutationExceptionStrategyConfiguration.PayloadCreator.class)),
    RECORD2(ClassName.get(org.jooq.Record2.class)),
    RECORD_VALIDATOR(ClassName.get(RecordValidator.class)),
    RELAY_CONNECTION(ClassName.get(ExtendedConnection.class)),
    RELAY_CONNECTION_CURSOR_IMPL(ClassName.get(DefaultConnectionCursor.class)),
    RELAY_CONNECTION_IMPL(ClassName.get(ConnectionImpl.class)),
    RELAY_EDGE_IMPL(ClassName.get(DefaultEdge.class)),
    RELAY_PAGE_INFO_IMPL(ClassName.get(DefaultPageInfo.class)),
    SELECTION_SET(ClassName.get(SelectionSet.class)),
    SET(ClassName.get(java.util.Set.class)),
    SIMPLE_ENTRY(ClassName.get(java.util.AbstractMap.SimpleEntry.class)),
    STRING(ClassName.get(java.lang.String.class)),
    TABLES(ClassName.get(GeneratorConfig.getGeneratedJooqTablesClass())),
    THROWABLE(ClassName.get(Throwable.class)),
    VALIDATION_VIOLATION_EXCEPTION(ClassName.get(ValidationViolationGraphQLException.class));

    public final ClassName className;

    JavaPoetClassName(ClassName className) {
        this.className = className;
    }
}
