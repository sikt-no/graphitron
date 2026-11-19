package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.emit.ServiceEmission;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.QueryField;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which fields of the classified model get a fetcher, the store emitting the services. A field
 * {@link ServiceEmission#owns the store owns} gets one, whatever the classified model made of it,
 * an {@link GraphitronField.UnclassifiedField} included: the store judged it, and the classified
 * model's refusal there is dropped. A field the classified model took for a service and the store
 * states no fetcher for gets none, since nothing in the schema leads to it. Every other classified
 * field keeps its classified fetcher, and an unclassified one gets none.
 *
 * <p>The fetchers classes, their registrations, the schema-shape rows and the nesting reach all ask
 * {@link #hasFetcher}, so a field is wired by every one of them or by none. A field the classified
 * model refused and the store emits is wired, rather than left to graphql-java's default fetcher,
 * which would answer null with no error.
 */
public final class FetcherOwnership {

    /** No store: nothing owned, every classified field keeps its classified fetcher. */
    public static final FetcherOwnership NONE = new FetcherOwnership(coordinate -> false, coordinate -> false);

    /** The leaves the classified model takes for a service, whose fetchers the store now emits. */
    public static final Set<Class<? extends GraphitronField>> CLASSIFIED_SERVICES = Set.of(
        QueryField.QueryServiceTableField.class,
        QueryField.QueryServiceRecordField.class,
        QueryField.QueryServicePolymorphicField.class,
        QueryField.QueryServiceTableInterfaceField.class,
        MutationField.MutationServiceTableField.class,
        MutationField.MutationServiceRecordField.class,
        MutationField.MutationServicePolymorphicField.class,
        MutationField.MutationServiceTableInterfaceField.class,
        ChildField.ServiceTableField.class,
        ChildField.ServiceRecordField.class);

    private final Predicate<String> owns;
    private final Predicate<String> unfetched;

    private FetcherOwnership(Predicate<String> owns, Predicate<String> unfetched) {
        this.owns = Objects.requireNonNull(owns, "owns");
        this.unfetched = Objects.requireNonNull(unfetched, "unfetched");
    }

    /** What {@code services} owns, and which fields its store states no fetcher for. */
    public static FetcherOwnership of(ServiceEmission services) {
        return new FetcherOwnership(services::owns, services::unfetched);
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
        if (field instanceof GraphitronField.UnclassifiedField) {
            return false;
        }
        return !(CLASSIFIED_SERVICES.contains(field.getClass()) && unfetched.test(field.qualifiedName()));
    }
}
