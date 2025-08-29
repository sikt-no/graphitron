package no.sikt.graphitron.mappings;

import no.sikt.graphitron.javapoet.ClassName;
import graphql.GraphQLError;
import graphql.schema.GraphQLSchema;
import graphql.schema.TypeResolver;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.TypeRuntimeWiring;
import no.sikt.graphitron.generators.mapping.TransformerClassGenerator;
import no.sikt.graphitron.validation.RecordValidator;
import no.sikt.graphql.NodeIdHandler;
import no.sikt.graphql.NodeIdStrategy;
import no.sikt.graphql.exception.*;
import no.sikt.graphql.helpers.query.QueryHelper;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;
import no.sikt.graphql.helpers.resolvers.EnvironmentHandler;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphql.helpers.resolvers.ServiceDataFetcherHelper;
import no.sikt.graphql.helpers.selection.SelectionSet;
import no.sikt.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphql.schema.SchemaReadingHelper;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jooq.JSONB;
import org.jooq.SelectField;
import org.jooq.SelectJoinStep;
import org.jooq.SelectSelectStep;
import org.jooq.exception.DataAccessException;

import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getGeneratedClassName;

/**
 * Enum of all the classnames in use in the generator, which helps keep track of dependencies.
 * Preferably add any new non-dynamic class name retrievals here.
 */
public enum JavaPoetClassName {
    ARRAYS(java.util.Arrays.class),
    ARRAY_LIST(java.util.ArrayList.class),
    CLASS(java.lang.Class.class),
    COLLECTORS(java.util.stream.Collectors.class),
    COMPLETABLE_FUTURE(java.util.concurrent.CompletableFuture.class),
    DATA_ACCESS_EXCEPTION(DataAccessException.class),
    DATA_ACCESS_EXCEPTION_CONTENT_TO_ERROR_MAPPING(DataAccessExceptionContentToErrorMapping.class),
    DATA_ACCESS_EXCEPTION_MAPPING_CONTENT(DataAccessExceptionMappingContent.class),
    DATA_FETCHING_ENVIRONMENT(graphql.schema.DataFetchingEnvironment.class),
    ENVIRONMENT_HANDLER(EnvironmentHandler.class),
    DATA_FETCHER(graphql.schema.DataFetcher.class),
    DATA_FETCHER_HELPER(DataFetcherHelper.class),
    DATA_SERVICE_FETCHER(ServiceDataFetcherHelper.class),
    DSL(org.jooq.impl.DSL.class),
    SORT_FIELD(org.jooq.SortField.class),
    DSL_CONTEXT(org.jooq.DSLContext.class),
    EXCEPTION(java.lang.Exception.class),
    EXCEPTION_TO_ERROR_MAPPING_PROVIDER(ExceptionToErrorMappingProvider.class),
    NODE_ID_HANDLER(NodeIdHandler.class),
    FUNCTION(java.util.function.Function.class),
    FUNCTIONS(org.jooq.Functions.class),
    GENERIC_EXCEPTION_CONTENT_TO_ERROR_MAPPING(GenericExceptionContentToErrorMapping.class),
    GENERIC_EXCEPTION_MAPPING_CONTENT(GenericExceptionMappingContent.class),
    GRAPHQL_ERROR(GraphQLError.class),
    HASH_MAP(java.util.HashMap.class),
    HASH_SET(java.util.HashSet.class),
    ILLEGAL_ARGUMENT_EXCEPTION(IllegalArgumentException.class),
    IMMUTABLE_PAIR(ImmutablePair.class),
    INJECT(javax.inject.Inject.class),
    INTEGER(java.lang.Integer.class),
    FLOAT(java.lang.Float.class),
    BOOLEAN(java.lang.Boolean.class),
    LIST(java.util.List.class),
    STREAM(java.util.stream.Stream.class),
    MAP(java.util.Map.class),
    OBJECT(java.lang.Object.class),
    SIMPLE_ENTRY(java.util.AbstractMap.SimpleEntry.class),
    EXCEPTION_STRATEGY_CONFIGURATION(ExceptionStrategyConfiguration.class),
    OBJECTS(java.util.Objects.class),
    OVERRIDE(java.lang.Override.class),
    PAIR(Pair.class),  // Note that this enforces a dependency on org.apache.commons on users.
    PAYLOAD_CREATOR(ExceptionStrategyConfiguration.PayloadCreator.class),
    QUERY_HELPER(QueryHelper.class),
    RECORD1(org.jooq.Record1.class),
    RECORD2(org.jooq.Record2.class),
    RECORD3(org.jooq.Record3.class),
    RECORD_VALIDATOR(RecordValidator.class),
    RESOLVER_HELPERS(ResolverHelpers.class),
    SELECTION_SET(SelectionSet.class),
    SET(java.util.Set.class),
    SINGLETON(javax.inject.Singleton.class),
    STRING(java.lang.String.class),
    THROWABLE(Throwable.class),
    VALIDATION_VIOLATION_EXCEPTION(ValidationViolationGraphQLException.class),
    RECORD_TRANSFORMER(getGeneratedClassName(TransformerClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME, TransformerClassGenerator.FILE_NAME_SUFFIX)),
    ABSTRACT_TRANSFORMER(AbstractTransformer.class),
    JSONB(JSONB.class),
    RUNTIME_WIRING(RuntimeWiring.class),
    RUNTIME_WIRING_BUILDER(RuntimeWiring.Builder.class),
    TYPE_RUNTIME_WIRING(TypeRuntimeWiring.class),
    TYPE_RESOLVER(TypeResolver.class),
    TYPE_DEFINITION_REGISTRY(TypeDefinitionRegistry.class),
    SCHEMA_READER(SchemaReadingHelper.class),
    GRAPHQL_SCHEMA(GraphQLSchema.class),
    SCHEMA_GENERATOR(SchemaGenerator.class),
    NODE_ID_STRATEGY(NodeIdStrategy.class),
    SELECT_JOIN_STEP(SelectJoinStep.class),
    SELECT_FIELD(SelectField.class),
    SELECT_SELECT_STEP(SelectSelectStep.class);

    public final ClassName className;

    JavaPoetClassName(ClassName className) {
        this.className = className;
    }

    JavaPoetClassName(Class<?> clazz) {
        this.className = ClassName.get(clazz);
    }
}
