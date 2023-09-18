package no.fellesstudentsystem.graphitron.validation;

import graphql.language.*;
import no.fellesstudentsystem.graphitron.mojo.GraphQLGenerator;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * A validation class that checks whether any directives are missing in the schema. This check will become obsolete once
 * Graphitron exports its own schema definition for directives.
 */
@Deprecated(forRemoval = true)
public class DirectiveDefinitionsValidator {
    static final Logger LOGGER = LoggerFactory.getLogger(GraphQLGenerator.class);

    private final Map<String, DirectiveDefinition> directiveDefinitions;

    public DirectiveDefinitionsValidator(Map<String, DirectiveDefinition> directiveDefinitions) {
        this.directiveDefinitions = directiveDefinitions;
    }

    @Deprecated(forRemoval = true)
    public void warnMismatchedDirectives() {
        var expectedDirectives = Arrays.stream(GenerationDirective.values()).map(GenerationDirective::getName).collect(Collectors.toSet());

        var difference = expectedDirectives.stream().filter(e -> !directiveDefinitions.containsKey(e)).collect(Collectors.toSet());
        if (difference.size() > 0) {
            LOGGER.warn("The following directives are declared in the code generator, but were not found in the GraphQL schema files: " + String.join(", ", difference));
        }
    }
}
