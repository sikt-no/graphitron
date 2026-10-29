package no.sikt.graphitron.model.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code <outbox>} block as authored: where the messages of every {@code @outbox} field go.
 * Strings only, as {@link SessionStateConfig} is, because what the names resolve to is a question
 * for the store's derivation against the jOOQ catalog and never something to resolve here.
 *
 * <p>Both targets may be present, and neither: a configuration that names no target, or both, is
 * recorded as written and reported as a defect at every {@code @outbox} it affects, because a
 * defect has to sit at an SDL position and the configuration has none. The only faults raised
 * while the pom is read are the ones with no meaning even as data: a blank element.
 *
 * @param table      the {@code <table>} element, the outbox table graphitron inserts into
 * @param function   the {@code <function>} element, the database function or procedure it calls
 *                   once per message
 * @param columns    the {@code <columns>} role bindings (role to column name) of the table target,
 *                   in written order
 * @param parameters the {@code <parameters>} role bindings (role to parameter name) of the
 *                   function target, in written order
 * @param payload    the {@code <payload>} argMapping every message carries, as written
 */
public record OutboxConfig(Optional<String> table, Optional<String> function,
                           Map<String, String> columns, Map<String, String> parameters,
                           Optional<String> payload) {

    /** No {@code <outbox>} block at all. */
    private static final OutboxConfig NONE =
        new OutboxConfig(Optional.empty(), Optional.empty(), Map.of(), Map.of(), Optional.empty());

    public OutboxConfig {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(payload, "payload");
        columns = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(columns));
        parameters = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }

    /** The no-configuration form. */
    public static OutboxConfig none() {
        return NONE;
    }

    /** Whether the pom carried an {@code <outbox>} block. */
    public boolean isConfigured() {
        return !equals(NONE);
    }

    /**
     * Reconciles the raw {@code <outbox>} strings, or throws {@link IllegalArgumentException}
     * naming a present-but-blank element. {@code null} means the element was absent.
     */
    public static OutboxConfig from(String table, String function, Map<String, String> columns,
                                    Map<String, String> parameters, String payload) {
        var cols = columns == null ? Map.<String, String>of() : columns;
        var params = parameters == null ? Map.<String, String>of() : parameters;
        if (table == null && function == null && cols.isEmpty() && params.isEmpty() && payload == null) {
            return none();
        }
        return new OutboxConfig(
            present(table, "<table>"), present(function, "<function>"),
            roles(cols, "<columns>"), roles(params, "<parameters>"), present(payload, "<payload>"));
    }

    private static Optional<String> present(String value, String element) {
        if (value == null) {
            return Optional.empty();
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                "<outbox>'s " + element + " is blank; remove the element if it is not used");
        }
        return Optional.of(value.trim());
    }

    private static Map<String, String> roles(Map<String, String> raw, String element) {
        var out = new LinkedHashMap<String, String>();
        raw.forEach((role, name) -> {
            if (role == null || role.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException(
                    "<outbox>'s " + element + " has an entry with a blank name or value");
            }
            out.put(role.trim(), name.trim());
        });
        return out;
    }
}
