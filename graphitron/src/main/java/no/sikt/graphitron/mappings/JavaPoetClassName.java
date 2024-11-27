package no.sikt.graphitron.mappings;

import com.squareup.javapoet.ClassName;
import graphql.GraphQLError;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.resolvers.mapping.TransformerClassGenerator;
import no.sikt.graphql.NodeIdHandler;
import no.sikt.graphql.exception.*;
import no.sikt.graphql.helpers.EnvironmentUtils;
import no.sikt.graphql.helpers.query.QueryHelper;
import no.sikt.graphql.helpers.resolvers.DataFetcher;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphql.helpers.resolvers.ServiceDataFetcher;
import no.sikt.graphql.helpers.selection.SelectionSet;
import no.sikt.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.validation.RecordValidator;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jooq.exception.DataAccessException;

/**
 * Enum of all the classnames in use in the generator, which helps keep track of dependencies.
 * Preferably add any new non-dynamic class name retrievals here.
 */
public enum JavaPoetClassName {
    ARRAYS(ClassName.get(java.util.Arrays.class)),
    ARRAY_LIST(ClassName.get(java.util.ArrayList.class)),
    CLASS(ClassName.get(java.lang.Class.class)),
    COLLECTORS(ClassName.get(java.util.stream.Collectors.class)),
    COMPLETABLE_FUTURE(ClassName.get(java.util.concurrent.CompletableFuture.class)),
    DATA_ACCESS_EXCEPTION(ClassName.get(DataAccessException.class)),
    DATA_ACCESS_EXCEPTION_CONTENT_TO_ERROR_MAPPING(ClassName.get(DataAccessExceptionContentToErrorMapping.class)),
    DATA_ACCESS_EXCEPTION_MAPPING_CONTENT(ClassName.get(DataAccessExceptionMappingContent.class)),
    DATA_FETCHING_ENVIRONMENT(ClassName.get(graphql.schema.DataFetchingEnvironment.class)),
    ENVIRONMENT_UTILS(ClassName.get(EnvironmentUtils.class)),
    DATA_FETCHER(ClassName.get(DataFetcher.class)),
    DATA_SERVICE_FETCHER(ClassName.get(ServiceDataFetcher.class)),
    DSL(ClassName.get(org.jooq.impl.DSL.class)),
    SORT_FIELD(ClassName.get(org.jooq.SortField.class)),
    DSL_CONTEXT(ClassName.get(org.jooq.DSLContext.class)),
    EXCEPTION(ClassName.get(java.lang.Exception.class)),
    EXCEPTION_TO_ERROR_MAPPING_PROVIDER(ClassName.get(ExceptionToErrorMappingProvider.class)),
    NODE_ID_HANDLER(ClassName.get(NodeIdHandler.class)),
    FUNCTIONS(ClassName.get(org.jooq.Functions.class)),
    GENERIC_EXCEPTION_CONTENT_TO_ERROR_MAPPING(ClassName.get(GenericExceptionContentToErrorMapping.class)),
    GENERIC_EXCEPTION_MAPPING_CONTENT(ClassName.get(GenericExceptionMappingContent.class)),
    GRAPHQL_ERROR(ClassName.get(GraphQLError.class)),
    HASH_MAP(ClassName.get(java.util.HashMap.class)),
    HASH_SET(ClassName.get(java.util.HashSet.class)),
    ILLEGAL_ARGUMENT_EXCEPTION(ClassName.get(IllegalArgumentException.class)),
    IMMUTABLE_PAIR(ClassName.get(ImmutablePair.class)),
    INJECT(ClassName.get(javax.inject.Inject.class)),
    INTEGER(ClassName.get(java.lang.Integer.class)),
    LIST(ClassName.get(java.util.List.class)),
    MAP(ClassName.get(java.util.Map.class)),
    MUTATION_EXCEPTION_STRATEGY_CONFIGURATION(ClassName.get(MutationExceptionStrategyConfiguration.class)),
    OBJECTS(ClassName.get(java.util.Objects.class)),
    OVERRIDE(ClassName.get(java.lang.Override.class)),
    PAIR(ClassName.get(Pair.class)),
    PAYLOAD_CREATOR(ClassName.get(MutationExceptionStrategyConfiguration.PayloadCreator.class)),
    QUERY_HELPER(ClassName.get(QueryHelper.class)),
    RECORD1(ClassName.get(org.jooq.Record1.class)),
    RECORD2(ClassName.get(org.jooq.Record2.class)),
    RECORD3(ClassName.get(org.jooq.Record3.class)),
    RECORD_VALIDATOR(ClassName.get(RecordValidator.class)),
    RESOLVER_HELPERS(ClassName.get(ResolverHelpers.class)),
    SELECTION_SET(ClassName.get(SelectionSet.class)),
    SET(ClassName.get(java.util.Set.class)),
    SINGLETON(ClassName.get(javax.inject.Singleton.class)),
    STRING(ClassName.get(java.lang.String.class)),
    THROWABLE(ClassName.get(Throwable.class)),
    VALIDATION_VIOLATION_EXCEPTION(ClassName.get(ValidationViolationGraphQLException.class)),
    RECORD_TRANSFORMER(ClassName.get(GeneratorConfig.outputPackage() + "." + TransformerClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME, TransformerClassGenerator.FILE_NAME_SUFFIX)),
    ABSTRACT_TRANSFORMER(ClassName.get(AbstractTransformer.class));

    public final ClassName className;

    JavaPoetClassName(ClassName className) {
        this.className = className;
    }
}
