package no.sikt.graphitron.configuration;

public class CodeGenerationThresholds {
    Integer upperBoundLinesOfCode;
    Integer crashPointLinesOfCode;
    Integer upperBoundNestingDepth;
    Integer crashPointNestingDepth;

    public CodeGenerationThresholds() {
    }

    public CodeGenerationThresholds(
            Integer upperBoundLinesOfCode,
            Integer crashPointLinesOfCode,
            Integer upperBoundNestingDepth,
            Integer crashPointNestingDepth) {
        this.upperBoundLinesOfCode = upperBoundLinesOfCode;
        this.crashPointLinesOfCode = crashPointLinesOfCode;
        this.upperBoundNestingDepth = upperBoundNestingDepth;
        this.crashPointNestingDepth = crashPointNestingDepth;
    }

    public Integer getUpperBoundLinesOfCode() {
        return upperBoundLinesOfCode;
    }

    public Integer getUpperBoundNestingDepth() {
        return upperBoundNestingDepth;
    }

    public Integer getCrashPointLinesOfCode() {
        return crashPointLinesOfCode;
    }

    public Integer getCrashPointNestingDepth() {
        return crashPointNestingDepth;
    }
}