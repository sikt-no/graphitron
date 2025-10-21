package no.fellesstudentsystem.schema_transformer.transform;

import graphql.language.*;
import graphql.schema.idl.TypeDefinitionRegistry;

import java.util.List;

public class AddKeyDirectives {
    public static void transform(TypeDefinitionRegistry typeDefinitionRegistry) {
        var implementors = typeDefinitionRegistry.getImplementationsOf(new InterfaceTypeDefinition("Node"));
        for (var oldType : implementors) {
            var newType = oldType.transform(it -> it.directive(new Directive("key", List.of(new Argument("fields", new StringValue("id"))))));
            typeDefinitionRegistry.remove(oldType);
            typeDefinitionRegistry.add(newType);
        }
    }
}
