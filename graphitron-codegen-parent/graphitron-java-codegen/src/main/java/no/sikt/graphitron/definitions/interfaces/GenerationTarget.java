package no.sikt.graphitron.definitions.interfaces;

import no.sikt.graphql.directives.GenerationDirective;

public interface GenerationTarget {
    /**
     * @return Does this element lead to the generation of additional data fetchers?
     */
    boolean createsDataFetcher();

    /**
     * @return Does this element contain fields which may produce data fetchers?
     */
    boolean createsDataFetchersForFields();

    /**
     * @return Should this object be generated?
     *
     * @deprecated To be removed since transform now handles removal of non-generated fields. This is now always true.
     */
    @Deprecated
    boolean isGenerated();

    /**
     * @return Should this object be generated as or containing resolvers?
     *
     * @deprecated To be removed since transform now handles removal of non-generated fields. This is now always true for resolvers.
     *  To check for resolvers, use {@link GenerationTarget#createsDataFetcher} instead.
     */
    @Deprecated
    boolean isGeneratedWithResolver();

    /**
     * Does this have the {@link GenerationDirective#NOT_GENERATED} directive for skipping generation set?
     *
     * @deprecated To be removed since transform now handles removal of non-generated fields. This is now always false.
     */
    @Deprecated
    boolean isExplicitlyNotGenerated();
}
