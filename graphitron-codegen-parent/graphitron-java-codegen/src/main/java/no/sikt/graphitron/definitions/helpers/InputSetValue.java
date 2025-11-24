package no.sikt.graphitron.definitions.helpers;

import no.sikt.graphitron.definitions.fields.InputField;

import java.util.LinkedHashSet;

import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_ITERATOR;


public class InputSetValue extends InputComponent {

    private InputSetValue(
            InputField input,
            InputField sourceInput,
            String startName,
            String namePath,
            LinkedHashSet<String> nullChecks,
            boolean pastWasIterable,
            Boolean isWrappedInList,
            boolean hasRecord) {
        super(input, sourceInput, startName, namePath, nullChecks, pastWasIterable, isWrappedInList, hasRecord);
        inferAdditionalChecks(input);
    }

    public InputSetValue(InputField input, String startName, boolean hasRecord) {
        this(input, input, startName, "", new LinkedHashSet<>(), false, false, hasRecord);
    }

    private void inferAdditionalChecks(InputField input) {
        if (input.isNullable()) {
            this.nullChecks.add(getNameWithPathString() + " != null");
        }
    }

    @Override
    public InputSetValue iterate(InputField input) {
        return new InputSetValue(
                input,
                sourceInput,
                startName,
                sourceInput.isIterableWrapped() ? VAR_ITERATOR : startName, // TODO: how about further nesting?
                nullChecks,
                pastWasIterable || this.input.isIterableWrapped(),
                isWrappedInList || this.input.isIterableWrapped(),
                hasRecord
        );
    }

    @Override
    public InputSetValue applyTo(InputField input) {
        return new InputSetValue(
                input,
                sourceInput,
                startName,
                namePath,
                nullChecks,
                pastWasIterable || this.input.isIterableWrapped(),
                isWrappedInList,
                hasRecord
        );
    }
}