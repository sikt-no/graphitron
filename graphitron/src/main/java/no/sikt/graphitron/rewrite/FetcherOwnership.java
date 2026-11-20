package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.emit.ServiceEmission;
import no.sikt.graphitron.rewrite.model.GraphitronField;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Which fields of the classified model get a fetcher, the store emitting the services. A field
 * {@link ServiceEmission#owns the store owns} gets one, whatever the classified model made of it,
 * an {@link GraphitronField.UnclassifiedField} included: the store judged it, and the classified
 * model's refusal there is dropped. A {@link GraphitronField.StoreOwnedField} the store does not own
 * gets none, since nothing in the schema leads to it; one the store states a fetcher for all the
 * same is a generator bug ({@link ServiceEmission#requireOwnedWhereFetched}). Every other classified
 * field keeps its classified fetcher, and an unclassified one gets none.
 *
 * <p>The fetchers classes, their registrations, the schema-shape rows and the nesting reach all ask
 * {@link #hasFetcher}, so a field is wired by every one of them or by none. A field the classified
 * model refused and the store emits is wired, rather than left to graphql-java's default fetcher,
 * which would answer null with no error.
 */
public final class FetcherOwnership {

    /** No store: nothing owned, every classified field keeps its classified fetcher. */
    public static final FetcherOwnership NONE = new FetcherOwnership(coordinate -> false, coordinate -> {});

    private final Predicate<String> owns;
    private final Consumer<String> requireOwnedWhereFetched;

    private FetcherOwnership(Predicate<String> owns, Consumer<String> requireOwnedWhereFetched) {
        this.owns = Objects.requireNonNull(owns, "owns");
        this.requireOwnedWhereFetched = Objects.requireNonNull(requireOwnedWhereFetched, "requireOwnedWhereFetched");
    }

    /** What {@code services} owns, and which fields its store states a fetcher for. */
    public static FetcherOwnership of(ServiceEmission services) {
        return new FetcherOwnership(services::owns, services::requireOwnedWhereFetched);
    }

    /** Whether the store owns {@code field}, its fetcher emitted from the store. */
    public boolean owns(GraphitronField field) {
        return owns.test(field.qualifiedName());
    }

    /** Whether {@code field} gets a fetcher and a registration. */
    public boolean hasFetcher(GraphitronField field) {
        if (owns(field)) {
            return true;
        }
        if (field instanceof GraphitronField.StoreOwnedField) {
            requireOwnedWhereFetched.accept(field.qualifiedName());
            return false;
        }
        return !(field instanceof GraphitronField.UnclassifiedField);
    }
}
