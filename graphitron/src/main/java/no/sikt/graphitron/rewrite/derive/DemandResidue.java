package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.GraphitronType;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The demand relations' shadow residue: the types whose registration today is decided by
 * machinery the store cannot yet see, so they sit deliberately outside both the demand and the
 * exemption rule views ({@code intent_field_demand_rule} and its siblings). Two populations,
 * kept apart because they close under different migrations:
 *
 * <ul>
 *   <li>{@link #reflectionBound()}: types whose verdict is a {@link GraphitronType.ResultType},
 *       produced by the reflection binding walk (accessor-chain propagation, the record-composite
 *       carrier's data-field element). Fields of these types are registered today while the
 *       demand relation cannot claim them, so the field-grain agreement pins its
 *       registered-but-undemanded direction inside exactly this set.</li>
 *   <li>{@link #embeddingDecided()}: types whose verdict is a
 *       {@link GraphitronType.NestingType}, registered at the embedding edge a
 *       {@code NestingField} establishes. Their fields resolve through the embedding and are not
 *       registered standalone (the field grain agrees through the nesting-target exemption); only
 *       the type-grain agreement needs this population, for its registered-but-undemanded
 *       direction.</li>
 * </ul>
 *
 * <p>A scaffold with a stated removal criterion, the shape {@link ClaimDomain} set: this value
 * is the unreified remainder of the demand relation. It dissolves as the structural classifier
 * arms migrate the binding walk and the embedding edge onto captured facts, at which point the
 * rule views cover these populations and the agreement's equality holds everywhere.
 */
public record DemandResidue(Set<String> reflectionBound, Set<String> embeddingDecided) {

    public DemandResidue {
        reflectionBound = Set.copyOf(reflectionBound);
        embeddingDecided = Set.copyOf(embeddingDecided);
    }

    /** The walked model's registry, split into the two residue populations. */
    public static DemandResidue of(GraphitronSchema schema) {
        var reflectionBound = new LinkedHashSet<String>();
        var embeddingDecided = new LinkedHashSet<String>();
        schema.types().forEach((name, verdict) -> {
            switch (verdict) {
                case GraphitronType.ResultType ignored -> reflectionBound.add(name);
                case GraphitronType.NestingType ignored -> embeddingDecided.add(name);
                default -> { }
            }
        });
        return new DemandResidue(reflectionBound, embeddingDecided);
    }
}
