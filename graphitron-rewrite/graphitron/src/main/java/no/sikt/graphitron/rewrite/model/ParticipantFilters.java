package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * Field-local pairing of a table-bound participant with the {@link WhereFilter}s lowered against
 * <em>that participant's own table</em>, for multi-table polymorphic root query fields
 * ({@link QueryField.QueryInterfaceField} / {@link QueryField.QueryUnionField}).
 *
 * <p>The same logical {@code @field} filter resolves to a <em>different</em> table-specific
 * {@link GeneratedConditionFilter} per participant (e.g. {@code FeideApplikasjonConditions.…} for one
 * participant and {@code MaskinportenApplikasjonConditions.…} for another), so the filter surface
 * cannot be a single shared {@code List<WhereFilter>} on the field.
 *
 * <p>Deliberately <em>not</em> a component on {@link ParticipantRef.TableBound}: that type is
 * type-scoped and shared (the same interface backing two query fields shares its
 * {@code ParticipantRef} instances), whereas filters are a per-<em>field</em> concern. Pairing the
 * participant with its filters here keys on the participant object, not a stringly typename, so it
 * cannot drift from the participant set.
 */
public record ParticipantFilters(ParticipantRef.TableBound participant, List<WhereFilter> filters) {
    public ParticipantFilters {
        filters = List.copyOf(filters);
    }
}
