package no.fellesstudentsystem.schema_transformer.transform;

import graphql.language.*;
import graphql.schema.idl.TypeDefinitionRegistry;

import java.util.List;

import static no.sikt.graphql.naming.GraphQLReservedName.*;

public class AddKeyDirectives {
    public static void transform(TypeDefinitionRegistry typeDefinitionRegistry) {
        var implementors = typeDefinitionRegistry.getImplementationsOf(new InterfaceTypeDefinition(NODE_TYPE.getName()));
        for (var oldType : implementors) {
            var alreadyHasNodeKeyDirective = oldType.getDirectives(FEDERATION_KEY.getName()).stream()
                    .anyMatch(AddKeyDirectives::isNodeKeyDirective);

            if (alreadyHasNodeKeyDirective) {
                continue;
            }

            var newType = oldType.transform(it -> it.directive(new Directive(FEDERATION_KEY.getName(), List.of(new Argument(FEDERATION_KEY_FIELDS.getName(), new StringValue(NODE_ID.getName()))))));
            typeDefinitionRegistry.remove(oldType);
            typeDefinitionRegistry.add(newType);
        }
    }

    private static boolean isNodeKeyDirective(Directive directive) {
        var val = directive.getArgument(FEDERATION_KEY_FIELDS.getName()).getValue();
        if (val instanceof StringValue s) {
            return NODE_ID.getName().equals(s.getValue());
        }

        return false;
    }
}
