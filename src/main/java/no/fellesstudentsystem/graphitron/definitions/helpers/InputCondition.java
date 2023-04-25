package no.fellesstudentsystem.graphitron.definitions.helpers;

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
        var name = getNameWithPath();
        var fieldType = input.getFieldType();
        if (fieldType.isIterableWrapped()) {
            if (!fieldType.isIterableNonNullable()) {
                this.nullChecks.add(name + " != null");
            }
            this.nullChecks.add(name + ".size() > 0");
        } else if (!fieldType.isNonNullable()) {
            this.nullChecks.add(name + " != null");
        }
    }

    public String getNamePath() {
        return namePath;
    }

    public String getNameWithPath() {
        return namePath.isEmpty() ? input.getName() : namePath + input.getMappingFromFieldName().asGetCall();
    }

    public String getChecksAsSequence() {
        return !nullChecks.isEmpty() ? nullChecks.stream().sorted().collect(Collectors.joining(" && ")) : "";
    }

    public String getCheckedNameWithPath() {
        var nameWithPath = getNameWithPath();
        var checks = getChecksAsSequence();
        return !checks.isEmpty() && !namePath.isEmpty() ? checks + " ? " + nameWithPath + " : null" : nameWithPath;
    }

    public InputCondition iterate(InputField input) {
        return new InputCondition(input, getNameWithPath(), nullChecks);
    }

    public InputCondition applyTo(InputField input) {
        return new InputCondition(input, namePath, nullChecks);
    }
}
