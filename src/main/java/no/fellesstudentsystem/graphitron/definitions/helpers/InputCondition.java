package no.fellesstudentsystem.graphitron.definitions.helpers;

import com.squareup.javapoet.CodeBlock;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;

import java.util.LinkedHashSet;
import java.util.stream.Collectors;

public class InputCondition {
    private final InputField input;
    private final String namePath, startName;
    private final LinkedHashSet<String> nullChecks;
    private final boolean pastWasIterable, hasRecord;

    private InputCondition(InputField input, String startName, String namePath, LinkedHashSet<String> nullChecks, boolean pastWasIterable, boolean hasRecord) {
        this.input = input;
        this.namePath = namePath;
        this.nullChecks = new LinkedHashSet<>(nullChecks);
        this.pastWasIterable = pastWasIterable;
        this.startName = startName;
        this.hasRecord = hasRecord;

        inferAdditionalChecks(input);
    }

    public InputCondition(InputField input, String startName, boolean hasRecord) {
        this(input, startName, "",  new LinkedHashSet<>(), false, hasRecord);
    }

    public InputCondition(InputField input, boolean hasRecord) {
        this(input, "", "",  new LinkedHashSet<>(), false, hasRecord);
    }

    public InputField getInput() {
        return input;
    }

    private void inferAdditionalChecks(InputField input) {
        var name = getNameWithPathString();
        if (!pastWasIterable && input.isNullable()) {
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
        return namePath.isEmpty()
                ? (startName.isEmpty() ? input.getName() : startName)
                : (namePath + (hasRecord ? input.getMappingForRecordFieldOverride().asGetCall() : input.getMappingFromSchemaName().asGetCall()).toString());
    }

    public String getChecksAsSequence() {
        return !nullChecks.isEmpty() ? nullChecks.stream().sorted().collect(Collectors.joining(" && ")) : "";
    }

    public InputCondition iterate(InputField input) {
        return new InputCondition(input, startName, getNameWithPathString(), nullChecks, pastWasIterable || this.input.isIterableWrapped(), hasRecord);
    }

    public InputCondition applyTo(InputField input) {
        return new InputCondition(input, startName, namePath, nullChecks, pastWasIterable || this.input.isIterableWrapped(), hasRecord);
    }
}
