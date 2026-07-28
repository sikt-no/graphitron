package no.sikt.graphitron.command;

import no.sikt.graphitron.rewrite.model.CallParam;

/**
 * One argument value a condition predicate consumes, paired with the producer-named glue body
 * local that holds it. The extraction itself is the borrowed {@link CallParam} (name, extraction
 * chain, list-ness, Java type), always rooted at the glue signature's argument map; the local
 * name is the producer's decision, qualified and collision-free within its method, so no
 * generated parameter list exists to collide on bare input-field names.
 *
 * <p>This pairs a borrowed ref with producer data, like {@link FkHop}; it is not the cut
 * {@code Binding} type from the item's earlier draft (which copied the extraction vocabulary).
 */
public record ArgBinding(CallParam param, String localName) {

    public ArgBinding {
        if (param == null) {
            throw new IllegalArgumentException("an argument binding carries the borrowed call param");
        }
        if (localName == null || localName.isBlank()) {
            throw new IllegalArgumentException("an argument binding requires a producer-named body local");
        }
    }
}
