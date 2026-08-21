package no.sikt.graphitron.rewrite.model;

/**
 * One declared field on an {@code @error} type, as the runtime registration needs it: where the
 * value is read from, and how it reaches the wire.
 *
 * <p>An {@code @error} type has no developer-supplied backing class, so its fields are not
 * projected by a generated fetcher at all: the runtime source for an entry in the payload's errors
 * list is the matched object itself, and each declared field is read off it. That read used to be
 * stated twice, as a classified {@link ChildField.RecordReadField} the emit path never consulted
 * and as {@link GraphitronType.ErrorType}'s type-level accessor-override list the registration
 * folded over. The override list held only the fields carrying {@code @field(name:)}, so an extra
 * field without one got no registration at all and fell through to graphql-java on its SDL name;
 * that is why nothing on the type could carry a wire direction, and why a {@code @nodeId} extra
 * field reached the consumer unencoded.
 *
 * <p>This is that read stated once, per field, over every declared field. It is derived from the
 * classified leaves rather than carried on {@link GraphitronType.ErrorType} for an ordering reason:
 * the type's own lift runs inside the pass that builds the node index, so it cannot resolve an
 * encoder, while field classification runs after and can.
 *
 * <ul>
 *   <li>{@link Builtin} — {@code path} / {@code message}, whose values graphitron synthesises
 *       itself. Reified as named methods on {@code <ErrorType>Fetchers} and wired by method
 *       reference; the type's own contract makes both required, so both are always present.</li>
 *   <li>{@link SourceAccessor} — an extra field, read off the handler source class by
 *       {@code accessorBase} ({@code @field(name:)} when the field carries one, else the field's
 *       own name), and delivered per {@code wire}.</li>
 * </ul>
 */
public sealed interface ErrorFieldRead {

    /** The GraphQL field this read is for. */
    String sdlFieldName();

    /** A field graphitron populates itself: {@code path} or {@code message}. */
    record Builtin(String sdlFieldName) implements ErrorFieldRead {
        public Builtin {
            java.util.Objects.requireNonNull(sdlFieldName, "sdlFieldName");
        }
    }

    /**
     * An extra field read off the matched exception (or, for {@code VALIDATION}, the
     * {@code GraphQLError}) through {@code accessorBase}. {@code wire} is
     * {@link CallSiteCompaction.Direct} where the read's value is the field's value, and
     * {@link CallSiteCompaction.NodeIdEncodeKeys} where the field carries
     * {@code @nodeId(typeName:)} and what the read yields is a node key to encode.
     */
    record SourceAccessor(String sdlFieldName, String accessorBase, CallSiteCompaction wire)
            implements ErrorFieldRead {
        public SourceAccessor {
            java.util.Objects.requireNonNull(sdlFieldName, "sdlFieldName");
            java.util.Objects.requireNonNull(accessorBase, "accessorBase");
            java.util.Objects.requireNonNull(wire, "wire");
        }
    }
}
