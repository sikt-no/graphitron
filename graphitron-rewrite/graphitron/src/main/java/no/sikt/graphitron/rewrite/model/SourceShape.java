package no.sikt.graphitron.rewrite.model;

/**
 * The shape of what arrives at {@code env.getSource()} for a {@link Carrier#Source}-carried field:
 * the source-side endpoint of the field's edge, on the same mirror/reflect vocabulary
 * {@link Mapping} uses for the output (R305).
 *
 * <ul>
 *   <li>{@link #Table} — the source is a catalog table row: the parent producer put a jOOQ
 *       {@code Record} / {@code TableRecord} from a catalog SELECT at {@code env.getSource()}.
 *       The normal inline-nesting and {@code @lookupKey} child case; a {@code @table}-backed
 *       parent. This is the {@link DomainReturnType.Record} / {@link DomainReturnType.TableRecord}
 *       reflect side as seen from the child, when the parent type is catalog-backed.</li>
 *   <li>{@link #Record} — the source is a producer-handed domain record: a developer
 *       {@code @service} method or a DML write handed back a record (a payload / DTO parent),
 *       and the field re-projects from it. The distinguishing fact that separates a re-fetch
 *       inline field (source {@code Record}) from a {@code @lookupKey} child lookup (source
 *       {@code Table}) when both classify as {@link Intent#Lookup} on a {@link Mapping#Table}.</li>
 * </ul>
 *
 * <p>Source-shape is a projection of the parent producer's {@link OutputField#domainReturnType()}
 * (the catalog-vs-domain split), materialised onto the {@link Carrier.Source} arm at the classify
 * boundary, not a second independently-classified fact. {@link Carrier#Query} / {@link Carrier#Mutation}
 * carriers have no source and therefore no source-shape.
 */
public enum SourceShape {
    /** The source is a catalog table row (a catalog-backed parent). */
    Table,
    /** The source is a producer-handed domain record (a {@code @service} / DML payload parent). */
    Record
}
