package no.fellesstudentsystem.schema_transformer;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
    public static Set<String> DIRECTIVES_FOR_REMOVING_ELEMENTS = Set.of("external", "notGenerated", "requires");
}
