package no.sikt.graphql;

import graphql.schema.DataFetchingEnvironment;
import org.jooq.DSLContext;

import static graphql.util.StringKit.capitalize;

public class DefaultGraphitronContext implements GraphitronContext {
    private final DSLContext ctx;
    private final int dataLoaderMaxBatchSize;

    public DefaultGraphitronContext(DSLContext ctx) {
        this(ctx, -1);
    }

    /**
     * @param ctx The jOOQ DSLContext that Graphitron should use.
     * @param dataLoaderMaxBatchSize The maximum number of keys per DataLoader batch, or a value less than 1 for no limit.
     */
    public DefaultGraphitronContext(DSLContext ctx, int dataLoaderMaxBatchSize) {
        this.ctx = ctx;
        this.dataLoaderMaxBatchSize = dataLoaderMaxBatchSize;
    }

    @Override
    public DSLContext getDslContext(DataFetchingEnvironment env) {
        return ctx;
    }

    @Override
    public <T> T getContextArgument(DataFetchingEnvironment env, String name) {
        return env.getGraphQlContext().get(name);
    }

    @Override
    public String getDataLoaderName(DataFetchingEnvironment env) {
        return String.format("%sFor%s",
                capitalize(env.getField().getName()),
                env.getExecutionStepInfo().getObjectType().getName());
    }

    @Override
    public int getDataLoaderMaxBatchSize(DataFetchingEnvironment env) {
        return dataLoaderMaxBatchSize;
    }
}
