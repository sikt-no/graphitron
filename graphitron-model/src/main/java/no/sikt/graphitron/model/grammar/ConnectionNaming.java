package no.sikt.graphitron.model.grammar;

/**
 * Single source of truth for the derived Connection type name of a directive-driven
 * {@code @asConnection} carrier: {@code <ParentType><FieldName>Connection}. Read by the
 * synthesis pass ({@code ConnectionPromoter}) when the carrier does not override the name.
 *
 * <p>The deprecated {@code @asConnection(connectionName:)} override bypasses this derivation.
 * Consumers no longer re-derive the name to find a carrier's
 * {@code GraphitronType.ConnectionType} entry; they resolve the carrier coordinate through the
 * connection-synthesis relation ({@code GraphitronSchema.connectionSynthesis()}), so an
 * overridden name resolves like any other.
 */
public final class ConnectionNaming {

    private ConnectionNaming() {
    }

    /** The derived Connection type name for a carrier field: {@code <ParentType><FieldName>Connection}. */
    public static String defaultConnectionName(String parentTypeName, String fieldName) {
        return parentTypeName + capitalize(fieldName) + "Connection";
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
