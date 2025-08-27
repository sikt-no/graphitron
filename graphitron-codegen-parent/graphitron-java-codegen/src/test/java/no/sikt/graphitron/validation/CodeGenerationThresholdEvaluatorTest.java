package no.sikt.graphitron.validation;

import no.sikt.graphitron.configuration.CodeGenerationThresholdEvaluator;
import no.sikt.graphitron.configuration.CodeGenerationThresholds;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Code generation threshold evaluator - Checks generated code up against code generation thresholds")
public class CodeGenerationThresholdEvaluatorTest {

    @Test
    @DisplayName("Should not inform of any methods without thresholds set")
    void noThresholdsSet() {
        var typeSpec = TypeSpec.classBuilder("SomeClass").build();

        var thresholds = new CodeGenerationThresholds(
                null,
                null,
                null,
                null);
        var evaluator = new CodeGenerationThresholdEvaluator(thresholds, typeSpec);

        assertThat(evaluator.getUpperBoundMessages()).isEmpty();
        assertThat(evaluator.getCrashPointMessages()).isEmpty();
    }

    @Test
    @DisplayName("Should inform of code sizes that exceeds thresholds")
    void linesOfCodeExceedsThresholds() {
        var CRASH_POINT = 50;
        var UPPER_BOUND = 25;

        var methodBeyondCrashPoint = getLinesOfCodeMethod(
                "methodBeyondCrashPoint",
                CRASH_POINT+1
        );
        var methodBetweenUpperBoundAndCrashPoint = getLinesOfCodeMethod(
                "methodBetweenUpperBoundAndCrashPoint",
                UPPER_BOUND+1
        );
        var methodBelowUpperBound = getLinesOfCodeMethod(
                "methodBelowUpperBound",
                UPPER_BOUND-1
        );
        var typeSpec = getTypeSpecWithMethods(List.of(
                methodBeyondCrashPoint,
                methodBetweenUpperBoundAndCrashPoint,
                methodBelowUpperBound
        ));

        var thresholds = new CodeGenerationThresholds(
                UPPER_BOUND,
                CRASH_POINT,
                null,
                null
        );
        var evaluator = new CodeGenerationThresholdEvaluator(thresholds, typeSpec);

        assertThat(evaluator.getUpperBoundMessages()).isEqualTo(
                List.of(
                        String.format(
                                "Code size in %s.%s has exceeded its UPPER_BOUND (current/limit) %d/%d",
                                typeSpec.name(),
                                methodBetweenUpperBoundAndCrashPoint.name(),
                                UPPER_BOUND + 1,
                                UPPER_BOUND
                        )
                ));
        assertThat(evaluator.getCrashPointMessages()).isEqualTo(
                List.of(
                        String.format(
                                "Code size in %s.%s has exceeded its CRASH_POINT (current/limit) %d/%d",
                                typeSpec.name(),
                                methodBeyondCrashPoint.name(),
                                CRASH_POINT + 1,
                                CRASH_POINT
                        )
                )
        );
    }

    @Test
    @DisplayName("Should inform of nested queries that exceeds thresholds")
    void nestedDepthExceedsThresholds() {
        var CRASH_POINT = 8;
        var UPPER_BOUND = 4;


        var methodBeyondCrashPoint = getNestedDepthMethod(
                "methodBeyondCrashPoint",
                CRASH_POINT+1
        );
        var methodBetweenUpperBoundAndCrashPoint = getNestedDepthMethod(
                "methodBetweenUpperBoundAndCrashPoint",
                UPPER_BOUND+1
        );
        var methodBelowUpperBound = getNestedDepthMethod(
                "methodBelowUpperBound",
                UPPER_BOUND-1
        );
        var typeSpec = getTypeSpecWithMethods(List.of(
                methodBeyondCrashPoint,
                methodBetweenUpperBoundAndCrashPoint,
                methodBelowUpperBound
        ));

        var thresholds = new CodeGenerationThresholds(
                null,
                null,
                UPPER_BOUND,
                CRASH_POINT
        );
        var evaluator = new CodeGenerationThresholdEvaluator(thresholds, typeSpec);

        assertThat(evaluator.getUpperBoundMessages()).isEqualTo(
                List.of(
                        String.format(
                                "Query nesting depth in %s.%s has exceeded its UPPER_BOUND (current/limit) %d/%d",
                                typeSpec.name(),
                                methodBetweenUpperBoundAndCrashPoint.name(),
                                UPPER_BOUND + 1,
                                UPPER_BOUND
                        )
                ));
        assertThat(evaluator.getCrashPointMessages()).isEqualTo(
                List.of(
                        String.format(
                                "Query nesting depth in %s.%s has exceeded its CRASH_POINT (current/limit) %d/%d",
                                typeSpec.name(),
                                methodBeyondCrashPoint.name(),
                                CRASH_POINT + 1,
                                CRASH_POINT
                        )
                )
        );
    }

    private static MethodSpec getLinesOfCodeMethod(String methodName, int linesOfCode) {
        var method = MethodSpec.methodBuilder(methodName);
        IntStream.range(0, linesOfCode).forEach(i -> {
            method.addCode("someLine\n");
        });
        return method.build();
    }

    private static MethodSpec getNestedDepthMethod(String methodName, int nestedDepth) {
        var method = MethodSpec.methodBuilder(methodName);
        method.addCode("return\nctx\n");
        IntStream.range(0, nestedDepth).forEach(i -> {
            method.addCode(".select(\n");
            method.addCode(String.format("column%d\n", i));
            method.addCode(")\n");
            method.addCode(String.format(".from(table%d)\n", i));
        });
        method.addStatement("from(table)");
        return method.build();
    }

    private static TypeSpec getTypeSpecWithMethods(List<MethodSpec> methods) {
        return TypeSpec.classBuilder("SomeClass")
                .addMethods(methods)
                .build();
    }
}