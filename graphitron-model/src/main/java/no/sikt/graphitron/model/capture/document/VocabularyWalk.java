package no.sikt.graphitron.model.capture.document;

import graphql.language.ArrayValue;
import graphql.language.Directive;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.language.Value;

/**
 * Every value a directive application writes, each handed over with the name and the type the
 * bundled vocabulary declares for it.
 *
 * <p>A fact the vocabulary spells the same way wherever it is written is read off this walk rather
 * than off a list of sites: a value of a given type, or a value under a given name, at whatever
 * depth. A site the vocabulary gains is then walked without anyone writing it down. The walk reads
 * the definitions {@link DirectiveLegality} judges against, so what it visits is what the decode
 * admitted.
 *
 * <p>A lone value where a list is declared is a list of one, on {@link DirectiveLegality}'s terms,
 * and a directive or an input type the vocabulary does not declare is not walked into.
 */
final class VocabularyWalk {

    private VocabularyWalk() {}

    /** What the walk hands each value: the argument or field name it was written under, and its type. */
    @FunctionalInterface
    interface Visitor {
        void visit(String name, Value<?> value, TypeName declared);
    }

    /** Walks every argument {@code application} writes. */
    static void walk(Directive application, Visitor visitor) {
        var definition = DirectiveLegality.definition(application.getName());
        if (definition == null) {
            return;
        }
        for (var argument : application.getArguments()) {
            definition.getInputValueDefinitions().stream()
                .filter(declared -> declared.getName().equals(argument.getName()))
                .findFirst()
                .ifPresent(declared ->
                    walk(argument.getName(), argument.getValue(), declared.getType(), visitor));
        }
    }

    private static void walk(String name, Value<?> value, Type<?> declared, Visitor visitor) {
        switch (declared) {
            case NonNullType nonNull -> walk(name, value, nonNull.getType(), visitor);
            case ListType list -> {
                if (value instanceof ArrayValue array) {
                    array.getValues().forEach(element -> walk(name, element, list.getType(), visitor));
                } else {
                    walk(name, value, list.getType(), visitor);
                }
            }
            case TypeName named -> {
                visitor.visit(name, value, named);
                var inputObject = DirectiveLegality.inputObject(named.getName());
                if (inputObject == null || !(value instanceof ObjectValue object)) {
                    return;
                }
                for (ObjectField field : object.getObjectFields()) {
                    inputObject.getInputValueDefinitions().stream()
                        .filter(declaredField -> declaredField.getName().equals(field.getName()))
                        .map(InputValueDefinition::getType)
                        .findFirst()
                        .ifPresent(type -> walk(field.getName(), field.getValue(), type, visitor));
                }
            }
            default -> { }
        }
    }
}
