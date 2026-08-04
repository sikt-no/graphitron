package no.sikt.graphitron.rewrite.model;

/**
 * Where a {@link GraphitronType.NodeType}'s two identity parameters came from. Carried on the
 * classified type so a consumer that has to say <em>why</em> a node has the {@code typeId} or the
 * key columns it has reads one recorded fact instead of re-reading SDL below the classifier
 * boundary.
 *
 * <p>The two axes are independent. {@code @node(typeId: "195")} on a type whose backing jOOQ class
 * publishes {@code __NODE_KEY_COLUMNS} is {@link Origin#DECLARED} on {@code typeId} and
 * {@link Origin#METADATA} on {@code keyColumns}, which is why this is a pair rather than a single
 * declared-versus-inferred flag.
 *
 * @param typeId     where the node's wire {@code typeId} came from
 * @param keyColumns where the node's key column list came from
 */
public record NodeProvenance(Origin typeId, Origin keyColumns) {

    /** The source of one identity parameter. */
    public enum Origin {
        /** Written by the author as an argument to {@code @node}. */
        DECLARED,
        /**
         * Taken from the backing jOOQ class's {@code __NODE_TYPE_ID} / {@code __NODE_KEY_COLUMNS}
         * constants. The whole of a node's identity is {@code METADATA} exactly when the author
         * left both axes to the catalog, whether or not a bare {@code @node} is present.
         */
        METADATA,
        /**
         * Derived by the classifier from the schema itself: the GraphQL type name for
         * {@code typeId}, the backing table's primary key for {@code keyColumns}. Reachable only
         * on the {@code @node}-without-metadata path.
         */
        DEFAULTED
    }

    /** Both axes from catalog metadata, the shape an author writes with no {@code @node} arguments. */
    public static NodeProvenance fromMetadata() {
        return new NodeProvenance(Origin.METADATA, Origin.METADATA);
    }
}
