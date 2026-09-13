package no.sikt.graphitron.model.capture.document;

import graphql.language.Argument;
import graphql.language.Definition;
import graphql.language.Directive;
import graphql.language.DirectiveDefinition;
import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValue;
import graphql.language.FloatValue;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.IntValue;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.NullValue;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.language.Value;
import graphql.parser.Parser;
import no.sikt.graphitron.model.schema.SchemaLoader;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether the directive definition admits an application, which is what decides whether the entry
 * stratum transcribes it.
 *
 * <p>An entry says what an author wrote, and the population it is a bag of is legal input. Input the
 * definition does not admit is not a fact about the schema an entry can hold: it is a defect, it is
 * reported as one, and the reporting is better than a transcription would be. Assembly raises
 * {@code DirectiveIllegalArgumentTypeError}, {@code DirectiveUnknownArgumentError} and
 * {@code DirectiveIllegalLocationError} against it, each classified and positioned, and no generator
 * run completes on a corpus carrying one.
 *
 * <p>The check is here rather than borrowed because graphql-java cannot make it where this class
 * does. A {@link graphql.schema.idl.TypeDefinitionRegistry} is partial by construction, holding what
 * has parsed so far, so a library checking an application against a definition the registry might
 * not hold yet would refuse legal input for an ordering reason; assembly, where the corpus is whole,
 * is the first place the library can speak. Every directive this stratum transcribes is graphitron's
 * own and ships beside it, so the definitions are in hand before the first user document is read,
 * which is a position the library is never in.
 *
 * <p>Two rules keep this from drifting into a second validator. It judges one application against
 * one definition and nothing else, so nothing here needs the corpus, the catalog or the classpath.
 * And it must agree with assembly in both directions: looser and an illegal application becomes an
 * entry carrying nonsense, stricter and entries vanish for schemas that are perfectly legal, which
 * is the worse failure because the readers of this stratum join to it inwardly and report nothing
 * when a row is missing.
 */
final class DirectiveLegality {

    private DirectiveLegality() {}

    /**
     * The bundled vocabulary, read once. Parsed from the same resource the schema loader offers to
     * every corpus, so the definitions judged against are the definitions applied against.
     */
    private static final class Vocabulary {
        private static final Vocabulary INSTANCE = parse();

        private final Map<String, DirectiveDefinition> directives;
        private final Map<String, InputObjectTypeDefinition> inputObjects;
        private final Map<String, EnumTypeDefinition> enums;

        private Vocabulary(Map<String, DirectiveDefinition> directives,
                           Map<String, InputObjectTypeDefinition> inputObjects,
                           Map<String, EnumTypeDefinition> enums) {
            this.directives = directives;
            this.inputObjects = inputObjects;
            this.enums = enums;
        }

        private static Vocabulary parse() {
            var directives = new HashMap<String, DirectiveDefinition>();
            var inputObjects = new HashMap<String, InputObjectTypeDefinition>();
            var enums = new HashMap<String, EnumTypeDefinition>();
            for (Definition<?> definition : Parser.parse(SchemaLoader.directivesSdl())
                    .getDefinitions()) {
                switch (definition) {
                    case DirectiveDefinition d -> directives.put(d.getName(), d);
                    case InputObjectTypeDefinition d -> inputObjects.put(d.getName(), d);
                    case EnumTypeDefinition d -> enums.put(d.getName(), d);
                    default -> { /* the vocabulary declares nothing else this check reads */ }
                }
            }
            return new Vocabulary(Map.copyOf(directives), Map.copyOf(inputObjects),
                Map.copyOf(enums));
        }
    }

    /**
     * Whether {@code application} is one the bundled definition of its directive admits at
     * {@code location}.
     *
     * <p>True for a directive the bundled vocabulary does not declare. Those are the author's own
     * and graphql's built-ins, which this stratum does not transcribe and has no definition for;
     * judging them here would be judging input against a definition we do not hold, which is the
     * position this class exists to be out of.
     */
    static boolean admits(Directive application, String location) {
        DirectiveDefinition definition = Vocabulary.INSTANCE.directives.get(application.getName());
        if (definition == null) {
            return true;
        }
        return legalHere(definition, location)
            && argumentsAreDeclared(application, definition)
            && requiredArgumentsAreWritten(application, definition)
            && argumentValuesConform(application, definition);
    }

    /**
     * The location the definition lists, spelled as graphql-java spells it in an SDL location. A
     * caller that cannot name the site passes none, and this declines to judge rather than refusing:
     * refusing on a site we failed to name would drop legal input for a reason that is ours.
     */
    private static boolean legalHere(DirectiveDefinition definition, String location) {
        return location == null || definition.getDirectiveLocations().stream()
            .anyMatch(declared -> declared.getName().equals(location));
    }

    /** No argument the definition does not declare, which assembly calls an unknown argument. */
    private static boolean argumentsAreDeclared(Directive application,
                                                DirectiveDefinition definition) {
        Set<String> declared = new HashSet<>();
        definition.getInputValueDefinitions().forEach(input -> declared.add(input.getName()));
        return application.getArguments().stream()
            .allMatch(argument -> declared.contains(argument.getName()));
    }

    /**
     * Every argument the definition requires is written. Required means non-null with no default:
     * a non-null argument carrying a default is satisfied by the default, which is why the two
     * conditions are one test rather than two.
     */
    private static boolean requiredArgumentsAreWritten(Directive application,
                                                       DirectiveDefinition definition) {
        for (InputValueDefinition declared : definition.getInputValueDefinitions()) {
            if (!(declared.getType() instanceof NonNullType) || declared.getDefaultValue() != null) {
                continue;
            }
            if (written(application, declared.getName()) == null) {
                return false;
            }
        }
        return true;
    }

    /** Every written argument's value fits the type the definition declares for it. */
    private static boolean argumentValuesConform(Directive application,
                                                 DirectiveDefinition definition) {
        for (InputValueDefinition declared : definition.getInputValueDefinitions()) {
            Value<?> value = written(application, declared.getName());
            if (value != null && !conforms(value, declared.getType())) {
                return false;
            }
        }
        return true;
    }

    private static Value<?> written(Directive application, String argumentName) {
        return application.getArguments().stream()
            .filter(argument -> argument.getName().equals(argumentName))
            .map(Argument::getValue)
            .findFirst().orElse(null);
    }

    /**
     * Whether one written value fits one declared type, which is the whole of the recursion.
     *
     * <p>The list case admits a lone value as well as a list of them, because the specification
     * coerces a single value into a one-element list and refusing it here would be stricter than
     * assembly. The unknown-type case admits anything: a custom scalar's literal is the scalar's
     * business, and a type name the bundled vocabulary does not declare belongs to the author's
     * corpus rather than to the definition being judged.
     */
    private static boolean conforms(Value<?> value, Type<?> declared) {
        return switch (declared) {
            case NonNullType nonNull -> !(value instanceof NullValue)
                && conforms(value, nonNull.getType());
            case ListType list -> value instanceof graphql.language.ArrayValue array
                ? array.getValues().stream().allMatch(element -> conforms(element, list.getType()))
                : conforms(value, list.getType());
            case TypeName named -> conformsToNamed(value, named.getName());
            default -> true;
        };
    }

    private static boolean conformsToNamed(Value<?> value, String typeName) {
        if (value instanceof NullValue) {
            return true;
        }
        InputObjectTypeDefinition inputObject = Vocabulary.INSTANCE.inputObjects.get(typeName);
        if (inputObject != null) {
            return value instanceof ObjectValue object && conformsToInputObject(object, inputObject);
        }
        EnumTypeDefinition enumType = Vocabulary.INSTANCE.enums.get(typeName);
        if (enumType != null) {
            return value instanceof EnumValue written && enumType.getEnumValueDefinitions().stream()
                .anyMatch(declared -> declared.getName().equals(written.getName()));
        }
        return switch (typeName) {
            case "String", "ID" -> value instanceof StringValue;
            case "Int" -> value instanceof IntValue;
            // An Int literal is a legal Float, the specification coercing the one to the other.
            case "Float" -> value instanceof FloatValue || value instanceof IntValue;
            case "Boolean" -> value instanceof graphql.language.BooleanValue;
            default -> true;
        };
    }

    /** An object literal declares no field the input object does not, and writes every required one. */
    private static boolean conformsToInputObject(ObjectValue written,
                                                 InputObjectTypeDefinition declared) {
        List<InputValueDefinition> fields = declared.getInputValueDefinitions();
        Set<String> declaredNames = new HashSet<>();
        fields.forEach(field -> declaredNames.add(field.getName()));
        for (var field : written.getObjectFields()) {
            if (!declaredNames.contains(field.getName())) {
                return false;
            }
        }
        for (InputValueDefinition field : fields) {
            Value<?> value = written.getObjectFields().stream()
                .filter(object -> object.getName().equals(field.getName()))
                .map(object -> object.getValue())
                .findFirst().orElse(null);
            boolean required = field.getType() instanceof NonNullType && field.getDefaultValue() == null;
            if (value == null) {
                if (required) {
                    return false;
                }
                continue;
            }
            if (!conforms(value, field.getType())) {
                return false;
            }
        }
        return true;
    }
}
