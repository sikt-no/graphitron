package no.fellesstudentsystem.graphitron.mappings;

import com.squareup.javapoet.ClassName;
import graphql.relay.DefaultConnectionCursor;
import graphql.relay.DefaultEdge;
import graphql.relay.DefaultPageInfo;
import no.fellesstudentsystem.graphql.relay.ConnectionImpl;
import no.fellesstudentsystem.graphql.relay.ConnectionWithNodes;
import no.fellesstudentsystem.graphql.helpers.EnvironmentUtils;
import no.fellesstudentsystem.graphql.helpers.selection.ConnectionSelectionSets;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSets;

/**
 * Enum of all the classnames in use in the generator, which helps keep track of dependencies.
 * Preferably add any new non-dynamic class name retrievals here.
 */
public enum JavaPoetClassName {
    ARRAYS(ClassName.get(java.util.Arrays.class)),
    STRING(ClassName.get(java.lang.String.class)),
    INTEGER(ClassName.get(java.lang.Integer.class)),
    OVERRIDE(ClassName.get(java.lang.Override.class)),
    MATH(ClassName.get(java.lang.Math.class)),
    EXCEPTION(ClassName.get(java.lang.Exception.class)),
    SET(ClassName.get(java.util.Set.class)),
    HASH_SET(ClassName.get(java.util.HashSet.class)),
    LIST(ClassName.get(java.util.List.class)),
    ARRAY_LIST(ClassName.get(java.util.ArrayList.class)),
    ILLEGAL_ARGUMENT_EXCEPTION(ClassName.get(IllegalArgumentException.class)),
    MAP(ClassName.get(java.util.Map.class)),
    OBJECTS(ClassName.get(java.util.Objects.class)),
    OPTIONAL(ClassName.get(java.util.Optional.class)),
    COLLECTORS(ClassName.get(java.util.stream.Collectors.class)),
    SIMPLE_ENTRY(ClassName.get(java.util.AbstractMap.SimpleEntry.class)),
    COMPLETABLE_FUTURE(ClassName.get(java.util.concurrent.CompletableFuture.class)),
    INJECT(ClassName.get(javax.inject.Inject.class)),
    RECORD2(ClassName.get(org.jooq.Record2.class)),
    FIELD_HELPERS(ClassName.get(no.fellesstudentsystem.kjerneapi.FieldHelpers.class)),
    TABLES(ClassName.get(no.fellesstudentsystem.kjerneapi.Tables.class)),
    FUNCTIONS(ClassName.get(org.jooq.Functions.class)),
    KEYS(ClassName.get(no.fellesstudentsystem.kjerneapi.Keys.class)),
    DATA_FETCHING_ENVIRONMENT(ClassName.get(graphql.schema.DataFetchingEnvironment.class)),
    ENVIRONMENT_UTILS(ClassName.get(EnvironmentUtils.class)),
    DATA_LOADER(ClassName.get(org.dataloader.DataLoader.class)),
    MAPPED_BATCH_LOADER_WITH_CONTEXT(ClassName.get(org.dataloader.MappedBatchLoaderWithContext.class)),
    DATA_LOADER_FACTORY(ClassName.get(org.dataloader.DataLoaderFactory.class)),
    DSL(ClassName.get(org.jooq.impl.DSL.class)),
    DSL_CONTEXT(ClassName.get(org.jooq.DSLContext.class)),
    SELECTION_SETS(ClassName.get(SelectionSets.class)),
    CONNECTION_SELECTION_SETS(ClassName.get(ConnectionSelectionSets.class)),
    RELAY_EDGE_IMPL(ClassName.get(DefaultEdge.class)),
    RELAY_PAGE_INFO_IMPL(ClassName.get(DefaultPageInfo.class)),
    RELAY_CONNECTION(ClassName.get(ConnectionWithNodes.class)),
    RELAY_CONNECTION_IMPL(ClassName.get(ConnectionImpl.class)),
    RELAY_CONNECTION_CURSOR_IMPL(ClassName.get(DefaultConnectionCursor.class));

    public final ClassName className;

    JavaPoetClassName(ClassName className) {
        this.className = className;
    }
}
