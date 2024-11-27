package no.sikt.graphitron.definitions.interfaces;

import no.sikt.graphql.directives.GenerationDirective;

public interface GenerationTarget {
    /**
     * @return Should this object be generated?
     */
    boolean isGenerated();

    /**
     * @return Should this object be generated as or containing resolvers?
     */
    boolean isGeneratedWithResolver();

    /**
     * Does this have the {@link GenerationDirective#NOT_GENERATED} directive for skipping generation set?
     */
    boolean isExplicitlyNotGenerated();
}
