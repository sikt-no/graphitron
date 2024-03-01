package no.fellesstudentsystem.graphitron.definitions.interfaces;

import no.fellesstudentsystem.graphql.directives.GenerationDirective;

public interface GenerationTarget {
    /**
     * @return Should this object be generated?
     */
    boolean isGenerated();

    /**
     * Does this have the {@link GenerationDirective#NOT_GENERATED} directive for skipping generation set?
     */
    boolean isExplicitlyNotGenerated();
}
