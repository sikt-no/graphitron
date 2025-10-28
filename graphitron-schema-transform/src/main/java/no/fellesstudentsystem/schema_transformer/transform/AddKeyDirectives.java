package no.fellesstudentsystem.schema_transformer.transform;

import graphql.language.*;
import graphql.schema.idl.TypeDefinitionRegistry;

import java.util.List;

public class AddKeyDirectives {
    public static void transform(TypeDefinitionRegistry typeDefinitionRegistry) {
        var implementors = typeDefinitionRegistry.getImplementationsOf(new InterfaceTypeDefinition("Node"));
        for (var oldType : implementors) {
            var alreadyHasNodeKeyDirective = oldType.getDirectives("key").stream()
                    .anyMatch(AddKeyDirectives::isNodeKeyDirective);

            if (alreadyHasNodeKeyDirective) {
                continue;
            }

            var newType = oldType.transform(it -> it.directive(new Directive("key", List.of(new Argument("fields", new StringValue("id"))))));
            typeDefinitionRegistry.remove(oldType);
            typeDefinitionRegistry.add(newType);
        }
    }

    private static boolean isNodeKeyDirective(Directive it) {
        var arg = it.getArgument("fields");
        var val = arg.getValue();
        if (val instanceof StringValue s) {
            return "id".equals(s.getValue());
        }

        return false;
    }
}
