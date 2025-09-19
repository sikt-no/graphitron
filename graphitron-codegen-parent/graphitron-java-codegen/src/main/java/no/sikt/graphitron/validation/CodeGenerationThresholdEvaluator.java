package no.sikt.graphitron.validation;

import no.sikt.graphitron.configuration.CodeGenerationThresholds;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class CodeGenerationThresholdEvaluator {
    private final Integer upperBoundLinesOfCode;
    private final Integer upperBoundNestingDepth;
    private final Integer crashPointLinesOfCode;
    private final Integer crashPointNestingDepth;
    private final List<String> upperBoundMessages = new ArrayList<>();
    private final List<String> crashPointMessages = new ArrayList<>();
    private final static Pattern SELECT_PATTERN = Pattern.compile("\\.select\\(");

    public CodeGenerationThresholdEvaluator(CodeGenerationThresholds thresholds, TypeSpec typeSpec) {
        this.upperBoundLinesOfCode = thresholds == null ? null : thresholds.getUpperBoundLinesOfCode();
        this.upperBoundNestingDepth = thresholds == null ? null : thresholds.getUpperBoundNestingDepth();
        this.crashPointLinesOfCode = thresholds == null ? null : thresholds.getCrashPointLinesOfCode();
        this.crashPointNestingDepth = thresholds == null ? null : thresholds.getCrashPointNestingDepth();

        typeSpec.methodSpecs().forEach(method -> {
            addMessageIfMethodExceedsNestingDepthBounds(method, typeSpec.name());
            addMessageIfMethodExceedsLinesOfCodeBounds(method, typeSpec.name());
        });
    }

    private String getNestingDepthMessage(String className, String methodName, long depth, ThresholdType type) {
        return String.format(
                "Query nesting depth in %s.%s has exceeded its %s (current/limit) %d/%d",
                className,
                methodName,
                type.name(),
                depth,
                type == ThresholdType.UPPER_BOUND ? upperBoundNestingDepth : crashPointNestingDepth
        );
    }

    private String getLinesOfCodeMessage(String className, String methodName, int linesOfCode, ThresholdType type) {
        return String.format(
                "Code size in %s.%s has exceeded its %s (current/limit) %d/%d",
                className,
                methodName,
                type.name(),
                linesOfCode,
                type == ThresholdType.UPPER_BOUND ? upperBoundLinesOfCode : crashPointLinesOfCode
        );
    }

    public void addMessageIfMethodExceedsNestingDepthBounds(MethodSpec method, String className) {
        var depth = SELECT_PATTERN.matcher(method.toString()).results().count();

        if (crashPointNestingDepth != null && depth > crashPointNestingDepth) {
            this.crashPointMessages.add(
                    getNestingDepthMessage(
                            className,
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
                            className,
                            method.name(),
                            depth,
                            ThresholdType.UPPER_BOUND
                    )
            );
        }
    }

    public void addMessageIfMethodExceedsLinesOfCodeBounds(MethodSpec method, String className) {
        var linesOfCode = method.code().toString().split("\\R").length;

        if (crashPointLinesOfCode != null && linesOfCode > crashPointLinesOfCode) {
            this.crashPointMessages.add(
                    getLinesOfCodeMessage(
                            className,
                            method.name(),
                            linesOfCode,
                            ThresholdType.CRASH_POINT
                    )
            );
            return;
        }
        if (upperBoundLinesOfCode != null && linesOfCode > upperBoundLinesOfCode) {
            this.upperBoundMessages.add(
                    getLinesOfCodeMessage(
                            className,
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
