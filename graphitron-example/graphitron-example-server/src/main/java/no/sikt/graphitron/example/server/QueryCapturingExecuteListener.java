package no.sikt.graphitron.example.server;

import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A jOOQ ExecuteListener that captures all executed SQL queries.
 * Used for testing to verify which database queries are actually executed.
 * <p>
 * Must be explicitly enabled via {@link #enable()} before it will capture queries.
 */
public class QueryCapturingExecuteListener implements ExecuteListener {

    private static final QueryCapturingExecuteListener INSTANCE = new QueryCapturingExecuteListener();
    private static final AtomicBoolean enabled = new AtomicBoolean(false);
    private final List<String> executedQueries = new CopyOnWriteArrayList<>();

    private QueryCapturingExecuteListener() {}

    public static QueryCapturingExecuteListener getInstance() {
        return INSTANCE;
    }

    public static Optional<QueryCapturingExecuteListener> getInstanceIfEnabled() {
        return enabled.get() ? Optional.of(INSTANCE) : Optional.empty();
    }

    public static void enable() {
        enabled.set(true);
    }

    public static void disable() {
        enabled.set(false);
    }

    public static boolean isEnabled() {
        return enabled.get();
    }

    @Override
    public void executeStart(ExecuteContext ctx) {
        if (ctx.sql() != null) {
            executedQueries.add(ctx.sql());
        }
    }

    /**
     * Returns all captured SQL queries.
     */
    public List<String> getExecutedQueries() {
        return Collections.unmodifiableList(executedQueries);
    }

    /**
     * Checks if any COUNT query was executed.
     */
    public boolean hasCountQuery() {
        return countCountQueries() > 0;
    }

    /**
     * Returns the number of COUNT queries executed.
     */
    public long countCountQueries() {
        return executedQueries.stream()
                .filter(q -> q.toLowerCase().contains("count("))
                .count();
    }

    /**
     * Clears all captured queries. Should be called before each test.
     */
    public void clear() {
        executedQueries.clear();
    }
}
