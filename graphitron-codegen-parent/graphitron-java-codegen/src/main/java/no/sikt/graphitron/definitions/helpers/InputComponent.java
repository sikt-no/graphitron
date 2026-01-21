package no.sikt.graphitron.definitions.helpers;

import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.mappings.JavaPoetClassName;

import java.util.LinkedHashSet;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_ITERATOR;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

public class InputComponent {
    protected final InputField input, sourceInput;
    protected final String namePath, startName;
    protected final LinkedHashSet<String> nullChecks;
    protected final boolean pastWasIterable, hasRecord, isWrappedInList, isOverriddenByAncestors, isArray, isFilter;

    private InputComponent(
            InputField input,
            InputField sourceInput,
            String startName,
            String namePath,
            LinkedHashSet<String> nullChecks,
            boolean pastWasIterable,
            boolean isWrappedInList,
            boolean hasRecord,
            boolean isFilter,
            boolean isOverriddenByAncestors) {
        this.input = input;
        this.sourceInput = sourceInput;
        this.startName = startName;
        this.namePath = namePath;
        this.nullChecks = new LinkedHashSet<>(nullChecks);
        this.pastWasIterable = pastWasIterable;
        this.isWrappedInList = isWrappedInList;
        this.hasRecord = hasRecord;
        this.isFilter = isFilter;
        this.isOverriddenByAncestors = isOverriddenByAncestors;
        this.isArray = input.isIterableWrapped()
                && (JavaPoetClassName.INTEGER.className.equals(input.getTypeClass())
                    || JavaPoetClassName.STRING.className.equals(input.getTypeClass()));
        inferAdditionalChecks(input);
    }

    public InputComponent(InputField input, String startName, boolean hasRecord, boolean isOverriddenByAncestors, boolean isFilter) {
        this(input, input, startName, "", new LinkedHashSet<>(), false, false, hasRecord,
                isFilter, isOverriddenByAncestors);
    }

    private void inferAdditionalChecks(InputField input) {
        var name = getNameWithPathString();
        if (isFilterInput()) {
            if (!pastWasIterable && input.isNullable()) {
                nullChecks.add(name + " != null");
            }
            if (isArray && hasRecord()) {
                nullChecks.add(name + ".length > 0");
            } else if (input.isIterableWrapped()) {
                nullChecks.add(name + ".size() > 0");
            }
        } else {
            if (input.isNullable()) {
                nullChecks.add(name + " != null");
            }
        }
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

    public InputComponent iterate(InputField field, boolean asFilter) {
        var namePath = asFilter
                ? getNameWithPathString()
                : sourceInput.isIterableWrapped() ? VAR_ITERATOR : startName;
        var isFilterOverriddenByAncestors = asFilter && (isOverriddenByAncestors || getInput().hasOverridingCondition());
        return new InputComponent(
                field,
                sourceInput,
                startName,
                namePath,
                nullChecks,
                pastWasIterable || getInput().isIterableWrapped(),
                isWrappedInList || getInput().isIterableWrapped(),
                hasRecord,
                asFilter,
                isFilterOverriddenByAncestors);
    }

    public boolean hasRecord() {
        return hasRecord;
    }

    public boolean isOverriddenByAncestors() {
        return isOverriddenByAncestors;
    }

    public boolean isWrappedInList() {
        return isWrappedInList;
    }

    public InputField getSourceInput() {
        return sourceInput;
    }

    public boolean isSetValueInput() {
        return !isFilter;
    }

    public boolean isFilterInput() {
        return isFilter;
    }
}