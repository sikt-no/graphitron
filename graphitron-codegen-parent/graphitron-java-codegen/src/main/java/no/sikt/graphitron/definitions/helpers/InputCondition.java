package no.sikt.graphitron.definitions.helpers;

import no.sikt.graphitron.definitions.fields.InputField;

import java.util.LinkedHashSet;


public class InputCondition extends InputComponent {
    private final Boolean isOverriddenByAncestors;

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
        super(input, sourceInput, startName, namePath, nullChecks, pastWasIterable, isWrappedInList, hasRecord);
        this.isOverriddenByAncestors = isOverriddenByAncestors;
        inferAdditionalChecks(input);
    }

    public InputCondition(InputField input, String startName, boolean hasRecord, Boolean isOverriddenByAncestors) {
        this(input, input, startName, "", new LinkedHashSet<>(), false, hasRecord, isOverriddenByAncestors, false);
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

    public Boolean isOverriddenByAncestors() {
        return this.isOverriddenByAncestors;
    }

    public Boolean isWrappedInList() {
        return this.isWrappedInList;
    }

    @Override
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

    @Override
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
}