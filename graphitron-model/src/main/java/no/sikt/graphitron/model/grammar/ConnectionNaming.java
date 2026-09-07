package no.sikt.graphitron.model.grammar;

/**
 * Single source of truth for the derived Connection type name of a directive-driven
 * {@code @asConnection} carrier: {@code <ParentType><FieldName>Connection}. Read by the
 * synthesis pass ({@code ConnectionPromoter}) when the carrier does not override the name.
 * The Edge name formula sits here too, but is a shared formula rather than a shared fact; see
 * {@link #defaultEdgeName(String)}.
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

    /**
     * The minted Edge type name for a carrier whose Connection type is named {@code connectionName}:
     * the Connection name with {@code Edge} appended. This is the Graphitron 9 contract, which
     * minted the Edge as the resolved Connection name plus an {@code Edge} suffix, so a consumer's
     * published schema keeps its {@code *ConnectionEdge} types across the upgrade.
     *
     * <p>It takes the resolved Connection name rather than the parent type and field, so the
     * {@code connectionName:} override path and the derived path share one entry point: an override
     * of {@code SharedMoviesConnection} mints {@code SharedMoviesConnectionEdge}, as legacy did.
     *
     * <p>Append rather than substitute, and not only for fidelity: substituting {@code Connection}
     * for {@code Edge} is a global substring replace, so an override of
     * {@code ConnectionsConnection} would yield {@code EdgesEdge}, and an override carrying no
     * {@code Connection} substring at all would leave the Edge name equal to the Connection name
     * and register both classifications at one coordinate. Appending makes both unreachable.
     *
     * <p>A shared formula, not a shared fact. The fact store already holds the Edge name, as the
     * minted-type row and the {@code edges} field's named type written by capture's macro
     * expansion; {@code ConnectionPromoter} recomputes it only because classification runs ahead of
     * capture in the pipeline. This method keeps the two evaluations equal until the promoter reads
     * the minted row, and is deleted with the promoter. {@code FactCaptureAgreementTest}'s
     * {@code synthesisProvenanceAgreesWithConnectionSynthesis} is the gate that fails the build on a
     * one-sided change.
     */
    public static String defaultEdgeName(String connectionName) {
        return connectionName + "Edge";
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
