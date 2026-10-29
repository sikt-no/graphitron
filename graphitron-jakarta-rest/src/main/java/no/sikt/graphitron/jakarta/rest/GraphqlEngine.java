package no.sikt.graphitron.jakarta.rest;

import graphql.GraphQL;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Holds the cached {@link GraphQL} engine at application scope. graphql-java's {@code GraphQL} is
 * immutable and thread-safe once built, so it is assembled once from the consumer's
 * {@link GraphitronApplication#engineBuilder()} and reused for every request.
 */
@ApplicationScoped
public class GraphqlEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphqlEngine.class);

    @Inject
    GraphitronApplication application;

    private GraphQL graphql;

    @PostConstruct
    void init() {
        this.graphql = application.engineBuilder().build();
        if (application.builtInEndpointEnabled() && !suppliesBuiltInInput(application)) {
            LOGGER.error("{} serves the built-in /graphql endpoint but does not override"
                + " newExecutionInput(), so every request to it answers a redacted 500. Override"
                + " newExecutionInput(), or return false from builtInEndpointEnabled() and serve GraphQL"
                + " from a resource that hands its input to GraphqlEndpoint.", application.getClass().getName());
        }
    }

    /**
     * Whether the application overrides {@link GraphitronApplication#newExecutionInput()}. The
     * instance may be a container proxy, which declares every method itself, so the check looks
     * past generated proxy and subclass types to the application's own class. A type it cannot
     * place counts as overriding: the log line is a best-effort startup warning, and the default
     * method's own failure is what answers a request.
     */
    static boolean suppliesBuiltInInput(GraphitronApplication application) {
        Class<?> type = application.getClass();
        while (type != null && type != Object.class && isGenerated(type)) {
            type = type.getSuperclass();
        }
        if (type == null || type == Object.class) {
            return true;
        }
        try {
            Method method = type.getMethod("newExecutionInput");
            return method.getDeclaringClass() != GraphitronApplication.class;
        } catch (NoSuchMethodException e) {
            return true;
        }
    }

    private static boolean isGenerated(Class<?> type) {
        String name = type.getName();
        return name.contains("_ClientProxy") || name.contains("_Subclass") || name.contains("$$")
            || name.contains("$Proxy") || type.isSynthetic();
    }

    /** @return the shared, immutable engine */
    public GraphQL get() {
        return graphql;
    }
}
