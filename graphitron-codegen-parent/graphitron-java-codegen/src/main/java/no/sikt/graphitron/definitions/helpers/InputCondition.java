package no.sikt.graphitron.definitions.helpers;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.definitions.fields.InputField;

import java.util.LinkedHashSet;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.uncapitalize;


public class InputCondition {
    private final InputField input, sourceInput;
    private final String namePath, startName;
    private final LinkedHashSet<String> nullChecks;
    private final boolean pastWasIterable, hasRecord;
    private final Boolean isOverriddenByAncestors, isWrappedInList;

    private InputCondition(
            InputField input,
            InputField sourceInput,
            String startName,
            String namePath,
            LinkedHashSet<String> nullChecks,
            boolean pastWasIterable,
            boolean hasRecord,
            Boolean isOverriddenByAncestors,
            Boolean isWrappedInList) {
        this.input = input;
        this.sourceInput = sourceInput;
        this.startName = startName;
        this.namePath = namePath;
        this.nullChecks = new LinkedHashSet<>(nullChecks);
        this.pastWasIterable = pastWasIterable;
        this.hasRecord = hasRecord;
        this.isOverriddenByAncestors = isOverriddenByAncestors;
        this.isWrappedInList = isWrappedInList;

        inferAdditionalChecks(input);
    }

    public InputCondition(InputField input, String startName, boolean hasRecord, Boolean isOverriddenByAncestors) {
        this(input, input, startName, "", new LinkedHashSet<>(), false, hasRecord, isOverriddenByAncestors, false);
    }

    public InputCondition(InputField input, boolean hasRecord) {
        this(input, input, "", "",  new LinkedHashSet<>(), false, hasRecord, false, false);
    }

    public InputField getInput() {
        return input;
    }

    public InputField getSourceInput() {
        return sourceInput;
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
        if (namePath.isEmpty()) {
            return uncapitalize(startName.isEmpty() ? input.getName() : startName);
        }

        return namePath + (
                hasRecord
                        ? input.getMappingForRecordFieldOverride().asGetCall()
                        : input.getMappingFromSchemaName().asGetCall()
        ).toString();
    }

    public Boolean isOverriddenByAncestors() {
        return this.isOverriddenByAncestors;
    }

    public Boolean isWrappedInList() {
        return this.isWrappedInList;
    }

    public String getChecksAsSequence() {
        return !nullChecks.isEmpty() ? nullChecks.stream().sorted().collect(Collectors.joining(" && ")) : "";
    }

    public InputCondition iterate(InputField input) {
        return new InputCondition(
                input,
                sourceInput,
                startName,
                getNameWithPathString(),
                nullChecks,
                pastWasIterable || this.input.isIterableWrapped(),
                hasRecord,
                isOverriddenByAncestors || this.input.hasOverridingCondition(),
                isWrappedInList || this.input.isIterableWrapped());
    }

    public InputCondition applyTo(InputField input) {
        return new InputCondition(
                input,
                sourceInput,
                startName,
                namePath,
                nullChecks,
                pastWasIterable || this.input.isIterableWrapped(),
                hasRecord,
                isOverriddenByAncestors,
                isWrappedInList);
    }

    public boolean hasRecord() {
        return hasRecord;
    }
}
