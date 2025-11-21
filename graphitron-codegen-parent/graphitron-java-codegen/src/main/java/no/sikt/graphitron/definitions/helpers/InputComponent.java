package no.sikt.graphitron.definitions.helpers;

import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.javapoet.CodeBlock;

import java.util.LinkedHashSet;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.uncapitalize;

public abstract class InputComponent {
    protected final InputField input, sourceInput;
    protected final String namePath, startName;
    protected final LinkedHashSet<String> nullChecks;
    protected final boolean pastWasIterable;
    protected final boolean hasRecord;
    protected final Boolean isWrappedInList;

    protected InputComponent(
            InputField input,
            InputField sourceInput,
            String startName,
            String namePath,
            LinkedHashSet<String> nullChecks,
            boolean pastWasIterable,
            Boolean isWrappedInList,
            boolean hasRecord) {
        this.input = input;
        this.sourceInput = sourceInput;
        this.startName = startName;
        this.namePath = namePath;
        this.nullChecks = new LinkedHashSet<>(nullChecks);
        this.pastWasIterable = pastWasIterable;
        this.isWrappedInList = isWrappedInList;
        this.hasRecord = hasRecord;
    }

    public InputField getInput() {
        return input;
    }

    public String getNamePath() {
        return namePath;
    }

    public CodeBlock getNameWithPath() {
        return CodeBlock.of(getNameWithPathString());
    }

    public String getNameWithPathString() {
        if (namePath.isEmpty()) {
            return uncapitalize(startName.isEmpty() ? input.getName() : startName);
        }

        return namePath + (
                hasRecord
                        ? input.getMappingForRecordFieldOverride().asGetCall()
                        : input.getMappingFromSchemaName().asGetCall()
        ).toString();
    }

    public String getChecksAsSequence() {
        return !nullChecks.isEmpty() ? nullChecks.stream().sorted().collect(Collectors.joining(" && ")) : "";
    }

    public CodeBlock getCheckSequenceCodeBlock() {
        return CodeBlock.of(getChecksAsSequence());
    }

    public abstract InputComponent iterate(InputField input);

    public abstract InputComponent applyTo(InputField input);

    public boolean hasRecord() {
        return hasRecord;
    }
}