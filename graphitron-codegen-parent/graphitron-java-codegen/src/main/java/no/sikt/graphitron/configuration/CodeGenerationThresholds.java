package no.sikt.graphitron.configuration;

import no.sikt.graphitron.javapoet.MethodSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class CodeGenerationThresholds {
    Integer upperBoundCodeSize;
    Integer upperBoundNestingDepth;
    Integer crashPointCodeSize;
    Integer crashPointNestingDepth;
    List<String> upperBoundMessages = new ArrayList<>();
    List<String> crashPointMessages = new ArrayList<>();

    public CodeGenerationThresholds() {
    }

    public CodeGenerationThresholds(Integer upperBoundCodeSize, Integer upperBoundNestingDepth) {
        this.upperBoundCodeSize = upperBoundCodeSize;
        this.upperBoundNestingDepth = upperBoundNestingDepth;
    }

    public CodeGenerationThresholds(List<MethodSpec> methods) {
        methods.forEach(method -> {
            addMessageIfMethodExceedsNestingDepthBounds(method);
            addMessageIfMethodExceedsCodeSizeBounds(method);
        });
    }

    public List<String> getUpperBoundMessages() {
        return upperBoundMessages;
    }

    public List<String> getCrashPointMessages() {
        return crashPointMessages;
    }

    private String getUpperBoundNestingDepthMessage(String methodName, long depth) {
        return String.format("Query nesting depth in %s has exceeded its upper bound %d/%d", methodName, depth, upperBoundNestingDepth);
    }

    private String getCrashPointNestingDepthMessage(String methodName, long depth) {
        return String.format("Query nesting depth in %s has exceeded its crash point %d/%d", methodName, depth, crashPointNestingDepth);
    }

    private String getUpperBoundCodeSizeMessage(String methodName, int codeSize) {
        return String.format("Code size in %s has exceeded its upper bound %d/%d", methodName, codeSize, upperBoundNestingDepth);
    }

    private String getCrashPointCodeSizeMessage(String methodName, int codeSize) {
        return String.format("Code size in %s has exceeded its crash point %d/%d", methodName, codeSize, crashPointNestingDepth);
    }

    public void addMessageIfMethodExceedsNestingDepthBounds(MethodSpec method) {
        if (upperBoundNestingDepth == null || upperBoundNestingDepth <= 0) {
            return;
        }

        Pattern selectPattern = Pattern.compile("\\.select\\(");
        var depth = selectPattern.matcher(method.toString()).results().count();

        if (depth > upperBoundNestingDepth) {
            this.upperBoundMessages.add(getUpperBoundNestingDepthMessage(method.name(), depth));
        }
        if (depth > crashPointNestingDepth) {
            this.crashPointMessages.add(getCrashPointNestingDepthMessage(method.name(), depth));
        }
    }

    public void addMessageIfMethodExceedsCodeSizeBounds(MethodSpec method) {
        if (upperBoundCodeSize == null || upperBoundCodeSize <= 0) {
            return;
        }

        var codeSize = method.toString().length();

        if (codeSize > upperBoundCodeSize) {
            this.upperBoundMessages.add(getUpperBoundCodeSizeMessage(method.name(), codeSize));
        }
        if (codeSize > crashPointCodeSize) {
            this.crashPointMessages.add(getCrashPointCodeSizeMessage(method.name(), codeSize));
        }
    }
}
