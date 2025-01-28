package no.sikt.graphitron.example.server;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import javax.sql.DataSource;

@ApplicationScoped
public class DSLContextProducer {

    @Inject
    DataSource dataSource;

    @Produces
    @ApplicationScoped
    public DSLContext createDSLContext() {
        return DSL.using(dataSource, SQLDialect.POSTGRES);
    }
}