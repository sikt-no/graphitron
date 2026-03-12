package no.fellesstudentsystem.schema_transformer;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphql.directives.GenerationDirective.NOT_GENERATED;
import static no.sikt.graphql.naming.GraphQLReservedName.FEDERATION_EXTERNAL;
import static no.sikt.graphql.naming.GraphQLReservedName.FEDERATION_REQUIRES;

public record TransformConfig(
        List<String> schemaLocations,
        Set<String> directivesToFilter,
        Map<String, String> descriptionSuffixForFeatures,
        boolean addFeatureFlags,
        boolean removeGeneratorDirectives,
        boolean removeExcludedElements,
        boolean expandConnections,
        boolean nodesFieldInConnectionsEnabled,
        boolean totalCountFieldInConnectionsEnabled
) {
    public static Set<String> DIRECTIVES_FOR_REMOVING_ELEMENTS = Set.of(
            FEDERATION_EXTERNAL.getName(),
            NOT_GENERATED.getName(),
            FEDERATION_REQUIRES.getName()
    );
}
