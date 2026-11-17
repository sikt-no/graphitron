package no.sikt.graphitron.sakila.example.app;

import no.sikt.graphitron.generated.schema.ConnectionSource;
import no.sikt.graphitron.generated.schema.ConnectionSources;
import no.sikt.graphitron.rewrite.test.services.SakilaSessionIdentity;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;

/**
 * The example's connection source: every key's connection comes from one pool, with the request's
 * identity mounted through the {@link SakilaSessionIdentity} facade (one raw claims JSON string,
 * reshaped into the composite the {@code session_connect} routine takes) and unmounted through the
 * paired routine. The claims are captured here, when the request's input is built, so the mount
 * needs no ambient state on whatever thread first needs a connection. Also the dev session method
 * {@code <devDatabase><session>} names, which is why it takes the claims first.
 */
public final class SakilaSessions {

    private SakilaSessions() {}

    /** The connection source for a request carrying {@code claims}, over {@code dataSource}. */
    public static ConnectionSource of(String claims, DataSource dataSource) {
        return ConnectionSources.mounting(
            key -> dataSource,
            (connection, key) -> SakilaSessionIdentity.mount(configuration(connection), claims),
            (connection, key, handle) -> SakilaSessionIdentity.unmount(configuration(connection), handle));
    }

    private static org.jooq.Configuration configuration(java.sql.Connection connection) {
        return DSL.using(connection, SQLDialect.POSTGRES).configuration();
    }
}
