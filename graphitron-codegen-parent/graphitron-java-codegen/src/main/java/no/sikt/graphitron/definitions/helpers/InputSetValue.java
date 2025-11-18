package no.sikt.graphitron.definitions.helpers;

import no.sikt.graphitron.definitions.fields.InputField;

import java.util.LinkedHashSet;

import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_ITERATOR;
import static org.apache.commons.lang3.StringUtils.uncapitalize;


public class InputSetValue extends InputComponent {

    private InputSetValue(
            InputField input,
            InputField sourceInput,
            String startName,
            String namePath,
            LinkedHashSet<String> nullChecks,
            boolean pastWasIterable,
            Boolean isWrappedInList) {
        super(input, sourceInput, startName, namePath, nullChecks, pastWasIterable, isWrappedInList);
        inferAdditionalChecks(input);
    }

    public InputSetValue(InputField input, String startName) {
        this(input, input, startName, "", new LinkedHashSet<>(), false, false);
    }

    private void inferAdditionalChecks(InputField input) {
        if (input.isNullable()) {
            this.nullChecks.add(getNameWithPathString() + " != null");
        }
    }

    @Override
    public String getNameWithPathString() {
        if (namePath.isEmpty()) {
            return uncapitalize(startName.isEmpty() ? input.getName() : startName);
        }

        return namePath + input.getMappingFromSchemaName().asGetCall().toString();
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
                isWrappedInList || this.input.isIterableWrapped());
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
                isWrappedInList);
    }
}