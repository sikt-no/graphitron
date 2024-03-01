package no.fellesstudentsystem.graphitron.definitions.helpers;

import com.squareup.javapoet.CodeBlock;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;

import java.util.HashSet;
import java.util.stream.Collectors;

public class InputCondition {
    private final InputField input;
    private final String namePath;
    private HashSet<String> nullChecks;

    private InputCondition(InputField input, String namePath, HashSet<String> nullChecks) {
        this.input = input;
        this.namePath = namePath;
        this.nullChecks = new HashSet<>(nullChecks);

        inferAdditionalChecks(input);
    }

    public InputCondition(InputField input) {
        this.input = input;
        namePath = "";
        nullChecks = new HashSet<>();

        inferAdditionalChecks(input);
    }

    public InputField getInput() {
        return input;
    }

    private void inferAdditionalChecks(InputField input) {
        var name = getNameWithPathString();
        if (input.isNullable()) {
            this.nullChecks.add(name + " != null");
        }

        if (input.isIterableWrapped()) {
            this.nullChecks.add(name + ".size() > 0");
        }
    }

    public String getNamePath() {
        return namePath;
    }

    public CodeBlock getNameWithPath() {
        return CodeBlock.of(getNameWithPathString());
    }

    public String getNameWithPathString() {
        return namePath.isEmpty() ? input.getName() : namePath + input.getMappingFromSchemaName().asGetCall().toString();
    }

    public String getChecksAsSequence() {
        return !nullChecks.isEmpty() ? nullChecks.stream().sorted().collect(Collectors.joining(" && ")) : "";
    }

    public InputCondition iterate(InputField input) {
        return new InputCondition(input, getNameWithPathString(), nullChecks);
    }

    public InputCondition applyTo(InputField input) {
        return new InputCondition(input, namePath, nullChecks);
    }
}
