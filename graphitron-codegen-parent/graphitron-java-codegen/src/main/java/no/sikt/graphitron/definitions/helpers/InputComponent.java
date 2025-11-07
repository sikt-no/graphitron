package no.sikt.graphitron.definitions.helpers;

import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.javapoet.CodeBlock;

import java.util.LinkedHashSet;
import java.util.stream.Collectors;

public abstract class InputComponent {
    protected final InputField input, sourceInput;
    protected final String namePath, startName;
    protected final LinkedHashSet<String> nullChecks;
    protected final boolean pastWasIterable;
    protected final Boolean isWrappedInList;

    protected InputComponent(
            InputField input,
            InputField sourceInput,
            String startName,
            String namePath,
            LinkedHashSet<String> nullChecks,
            boolean pastWasIterable,
            Boolean isWrappedInList) {
        this.input = input;
        this.sourceInput = sourceInput;
        this.startName = startName;
        this.namePath = namePath;
        this.nullChecks = new LinkedHashSet<>(nullChecks);
        this.pastWasIterable = pastWasIterable;
        this.isWrappedInList = isWrappedInList;
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

    public abstract String getNameWithPathString();

    public String getChecksAsSequence() {
        return !nullChecks.isEmpty() ? nullChecks.stream().sorted().collect(Collectors.joining(" && ")) : "";
    }

    public CodeBlock getCheckSequenceCodeBlock() {
        return CodeBlock.of(getChecksAsSequence());
    }

    public abstract InputComponent iterate(InputField input);

    public abstract InputComponent applyTo(InputField input);
}