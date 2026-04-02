package no.sikt.graphql;

import graphql.schema.DataFetchingEnvironment;
import org.jetbrains.annotations.NotNull;
import org.jooq.DSLContext;

import java.util.Optional;

import static graphql.util.StringKit.capitalize;

public class DefaultGraphitronContext implements GraphitronContext {
    private final DSLContext ctx;

    public DefaultGraphitronContext(DSLContext ctx) {
        this.ctx = ctx;
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
    public @NotNull Optional<String> getTenantId(DataFetchingEnvironment env) {
        return Optional.empty();
    }
}
