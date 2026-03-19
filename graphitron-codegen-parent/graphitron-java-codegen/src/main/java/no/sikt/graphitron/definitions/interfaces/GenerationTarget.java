package no.sikt.graphitron.definitions.interfaces;

import no.sikt.graphql.directives.GenerationDirective;

public interface GenerationTarget {
    /**
     * @return Does this element lead to the generation of additional data fetchers?
     */
    default boolean createsDataFetcher() {
        return false;
    }

    /**
     * @return Does this element contain fields which may produce data fetchers?
     */
    default boolean createsDataFetchersForFields() {
        return false;
    }

    /**
     * @return Should this object be generated?
     *
     * @deprecated To be removed since transform now handles removal of non-generated fields. This is now always true.
     */
    @Deprecated
    default boolean isGenerated() {
        return true;
    }

    /**
     * @return Should this object be generated as or containing resolvers?
     *
     * @deprecated To be removed since transform now handles removal of non-generated fields. This is now always true for resolvers.
     *  To check for resolvers, use {@link GenerationTarget#createsDataFetcher} instead.
     */
    @Deprecated
    default boolean isGeneratedWithResolver() {
        return false;
    }

    /**
     * Does this have the {@link GenerationDirective#NOT_GENERATED} directive for skipping generation set?
     *
     * @deprecated To be removed since transform now handles removal of non-generated fields. This is now always false.
     */
    @Deprecated
    default boolean isExplicitlyNotGenerated() {
        return false;
    }
}
