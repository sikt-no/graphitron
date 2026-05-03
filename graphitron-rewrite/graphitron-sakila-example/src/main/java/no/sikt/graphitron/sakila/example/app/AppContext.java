package no.sikt.graphitron.sakila.example.app;

import graphql.schema.DataFetchingEnvironment;
import io.agroal.api.AgroalDataSource;
import no.sikt.graphitron.generated.schema.GraphitronContext;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import java.util.Map;

public final class AppContext implements GraphitronContext {

    private final DSLContext dsl;
    private final Map<String, Object> contextValues;

    public AppContext(AgroalDataSource dataSource, Map<String, Object> contextValues) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        this.contextValues = contextValues == null ? Map.of() : contextValues;
    }

    @Override
    public DSLContext getDslContext(DataFetchingEnvironment env) {
        return dsl;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getContextArgument(DataFetchingEnvironment env, String name) {
        return (T) contextValues.get(name);
    }
}
