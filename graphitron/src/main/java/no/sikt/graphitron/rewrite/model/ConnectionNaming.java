package no.sikt.graphitron.rewrite.model;

/**
 * Single source of truth for the derived Connection type name of a directive-driven
 * {@code @asConnection} carrier: {@code <ParentType><FieldName>Connection}. Shared by the
 * synthesis pass ({@code ConnectionPromoter}) and the emitters that must resolve a carrier
 * field's {@link GraphitronType.ConnectionType} entry from the classified model
 * ({@code QueryConditionsGenerator}, {@code TypeFetcherGenerator} for the R13 facet plan), so
 * naming can never drift between synthesis and lookup.
 *
 * <p>The deprecated {@code @asConnection(connectionName:)} override bypasses this derivation;
 * carriers that combine it with {@code @asFacet} are rejected at build time
 * ({@code GraphitronSchemaBuilder.rejectFacetMisuse}), so every faceted connection resolves
 * correctly through this name.
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
