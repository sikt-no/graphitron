package no.sikt.graphitron.rewrite.maven;

import java.util.Map;

/**
 * POM XML binding for the {@code <outbox>} block: where the messages of every {@code @outbox}
 * field go. Collapses into a {@link no.sikt.graphitron.model.config.OutboxConfig} on
 * {@link no.sikt.graphitron.model.config.RunContext}, as strings; the store's derivation resolves
 * the names against the jOOQ catalog.
 *
 * <pre>{@code
 * <outbox>
 *   <table>outbox</table>
 *   <columns><type>event_type</type></columns>
 *   <payload>actor: $session.userId, operation: $scope.operationName</payload>
 * </outbox>
 * }</pre>
 * <p>One of {@code <table>} (an outbox table graphitron inserts into, by default in Debezium's
 * layout) or {@code <function>} (a database function or procedure graphitron calls once per
 * message) names the target. A block naming both or neither is recorded as written and reported at
 * each {@code @outbox} it affects.
 */
public class OutboxBinding {
    /** The outbox table graphitron inserts one row per message into. */
    String table;
    /** The database function or procedure graphitron calls once per message. */
    String function;
    /** Role to column name of the table target: type, aggregatetype, aggregateid, payload. */
    Map<String, String> columns;
    /** Role to parameter name of the function target. */
    Map<String, String> parameters;
    /** The argMapping every message carries: {@code $session}, {@code $scope} and literal-free sources only. */
    String payload;
}
