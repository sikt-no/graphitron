package no.sikt.graphitron.configuration;

import no.sikt.graphitron.javapoet.MethodSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class CodeGenerationThresholds {
    Integer upperBoundLinesOfCode;
    Integer upperBoundNestingDepth;
    Integer crashPointLinesOfCode;
    Integer crashPointNestingDepth;
    List<String> upperBoundMessages = new ArrayList<>();
    List<String> crashPointMessages = new ArrayList<>();

    public CodeGenerationThresholds() {
    }

    public CodeGenerationThresholds(Integer upperBoundLinesOfCode, Integer crashPointLinesOfCode, Integer upperBoundNestingDepth, Integer crashPointNestingDepth, List<MethodSpec> methods) {
        this.upperBoundLinesOfCode = upperBoundLinesOfCode;
        this.upperBoundNestingDepth = upperBoundNestingDepth;
        this.crashPointLinesOfCode = crashPointLinesOfCode;
        this.crashPointNestingDepth = crashPointNestingDepth;
        addMessageIfMethodExceedsThresholds(methods);
    }

    public void addMessageIfMethodExceedsThresholds(List<MethodSpec> methods) {
        methods.forEach(method -> {
            addMessageIfMethodExceedsNestingDepthBounds(method);
            addMessageIfMethodExceedsLinesOfCodeBounds(method);
        });
    }

    private String getNestingDepthMessage(String methodName, long depth, ThresholdType type) {
        return String.format(
                "Query nesting depth in %s has exceeded its %s %d/%d",
                methodName,
                type.name(),
                depth,
                type == ThresholdType.UPPER_BOUND ? upperBoundNestingDepth : crashPointNestingDepth
        );
    }

    private String getLinesOfCodeMessage(String methodName, int linesOfCode, ThresholdType type) {
        return String.format(
                "Code size in %s has exceeded its %s %d/%d",
                methodName,
                type.name(),
                linesOfCode,
                type == ThresholdType.UPPER_BOUND ? upperBoundLinesOfCode : crashPointLinesOfCode
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

    public void addMessageIfMethodExceedsLinesOfCodeBounds(MethodSpec method) {
        var linesOfCode = method.code().toString().split("\\R").length;

        if (crashPointLinesOfCode != null && linesOfCode > crashPointLinesOfCode) {
            this.crashPointMessages.add(
                    getLinesOfCodeMessage(method.name(),
                            linesOfCode,
                            ThresholdType.CRASH_POINT
                    )
            );
            return;
        }
        if (upperBoundLinesOfCode != null && linesOfCode > upperBoundLinesOfCode) {
            this.upperBoundMessages.add(
                    getLinesOfCodeMessage(
                            method.name(),
                            linesOfCode,
                            ThresholdType.UPPER_BOUND
                    )
            );
        }
    }

    enum ThresholdType {
        UPPER_BOUND,
        CRASH_POINT
    }

    public List<String> getUpperBoundMessages() {
        return upperBoundMessages;
    }

    public List<String> getCrashPointMessages() {
        return crashPointMessages;
    }
}
