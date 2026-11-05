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

import java.util.ArrayList;
import java.util.List;

/**
 * Every value a directive application writes, each handed over with the trail of names it was
 * written under and the type the bundled vocabulary declares for it.
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

    /**
     * One name on the way down to a value: an argument or an input field, and the value's index in
     * it where it is a list, a lone value where a list is declared being index 0.
     */
    record Step(String name, Integer index) {}

    /**
     * The names a value was written under, from the application's argument down: for example
     * {@code fields[1].path[0].condition} on an {@code @order} is the steps fields 1, path 0 and
     * condition.
     */
    record Trail(List<Step> steps) {
        Trail {
            steps = List.copyOf(steps);
        }

        /** The argument or field the value itself was written under. */
        String name() {
            return steps.getLast().name();
        }

        /** The application's argument the value sits in. */
        String argument() {
            return steps.getFirst().name();
        }

        /** The value's index in its argument's list, null where the argument is no list. */
        Integer argumentIndex() {
            return steps.getFirst().index();
        }

        /**
         * The value's index in a {@code path} list below the argument, null where it sits in none:
         * {@code fields[1].path[0]} is index 0, and an argument that is itself the path has its index
         * as {@link #argumentIndex}.
         */
        Integer pathIndex() {
            return steps.stream().skip(1).filter(step -> step.name().equals("path"))
                .map(Step::index).findFirst().orElse(null);
        }

        Trail then(String name) {
            var next = new ArrayList<>(steps);
            next.add(new Step(name, null));
            return new Trail(next);
        }

        Trail at(int index) {
            var next = new ArrayList<>(steps);
            next.set(next.size() - 1, new Step(steps.getLast().name(), index));
            return new Trail(next);
        }
    }

    /** What the walk hands each value: the trail of names it was written under, and its type. */
    @FunctionalInterface
    interface Visitor {
        void visit(Trail trail, Value<?> value, TypeName declared);
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
                .ifPresent(declared -> walk(new Trail(List.of()).then(argument.getName()),
                    argument.getValue(), declared.getType(), visitor));
        }
    }

    private static void walk(Trail trail, Value<?> value, Type<?> declared, Visitor visitor) {
        switch (declared) {
            case NonNullType nonNull -> walk(trail, value, nonNull.getType(), visitor);
            case ListType list -> {
                if (value instanceof ArrayValue array) {
                    var elements = array.getValues();
                    for (int i = 0; i < elements.size(); i++) {
                        walk(trail.at(i), elements.get(i), list.getType(), visitor);
                    }
                } else {
                    walk(trail.at(0), value, list.getType(), visitor);
                }
            }
            case TypeName named -> {
                visitor.visit(trail, value, named);
                var inputObject = DirectiveLegality.inputObject(named.getName());
                if (inputObject == null || !(value instanceof ObjectValue object)) {
                    return;
                }
                for (ObjectField field : object.getObjectFields()) {
                    inputObject.getInputValueDefinitions().stream()
                        .filter(declaredField -> declaredField.getName().equals(field.getName()))
                        .map(InputValueDefinition::getType)
                        .findFirst()
                        .ifPresent(type -> walk(trail.then(field.getName()), field.getValue(), type, visitor));
                }
            }
            default -> { }
        }
    }
}
