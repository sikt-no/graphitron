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

    private String getNestingDepthMessage(String methodName, long depth, ThresholdType type) {
        return String.format(
                "Query nesting depth in %s has exceeded its %s %d/%d",
                methodName,
                type.name(),
                depth,
                crashPointNestingDepth
        );
    }

    private String getCodeSizeMessage(String methodName, int codeSize, ThresholdType type) {
        return String.format(
                "Code size in %s has exceeded its %s %d/%d",
                methodName,
                type.name(),
                codeSize,
                upperBoundNestingDepth
        );
    }

    public void addMessageIfMethodExceedsNestingDepthBounds(MethodSpec method) {
        Pattern selectPattern = Pattern.compile("\\.select\\(");
        var depth = selectPattern.matcher(method.toString()).results().count();

        if (crashPointNestingDepth != null && depth > crashPointNestingDepth) {
            this.crashPointMessages.add(
                    getNestingDepthMessage(
                            method.name(),
                            depth,
                            ThresholdType.CRASH_POINT
                    )
            );
            return;
        }
        if (upperBoundNestingDepth != null && depth > upperBoundNestingDepth) {
            this.upperBoundMessages.add(
                    getNestingDepthMessage(
                            method.name(),
                            depth,
                            ThresholdType.UPPER_BOUND
                    )
            );
        }
    }

    public void addMessageIfMethodExceedsCodeSizeBounds(MethodSpec method) {
        var codeSize = method.toString().length();

        if (crashPointCodeSize != null && codeSize > crashPointCodeSize) {
            this.crashPointMessages.add(
                    getCodeSizeMessage(method.name(),
                            codeSize,
                            ThresholdType.CRASH_POINT
                    )
            );
            return;
        }
        if (upperBoundCodeSize != null && codeSize > upperBoundCodeSize) {
            this.upperBoundMessages.add(
                    getCodeSizeMessage(
                            method.name(),
                            codeSize,
                            ThresholdType.UPPER_BOUND
                    )
            );
        }
    }

    enum ThresholdType {
        UPPER_BOUND,
        CRASH_POINT
    }
}
