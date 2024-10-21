package no.fellesstudentsystem.graphitron.maven;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static no.fellesstudentsystem.graphitron.maven.TestConfiguration.EXPECTED_OUTPUT_NAME;
import static no.fellesstudentsystem.graphitron.maven.TestConfiguration.SRC_ROOT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * Abstractions for functionality that is used across multiple test classes. Based on GeneratorTest in graphitron.
 */
public abstract class GeneratorTest {
    private final String sourceTestPath;
    protected final boolean checkProcessedSchemaDefault;

    private final static String
            MSG_GENERATED_EXPECT = "Expected the generated code:",
            MSG_GENERATED_ACTUAL = "to be equivalent with excluding imports:";

    public GeneratorTest() {
        sourceTestPath = SRC_ROOT + "/" + getSubpath() + "/";
        this.checkProcessedSchemaDefault = getCheckProcessedSchemaDefault();
    }

    public String getSourceTestPath() {
        return sourceTestPath;
    }

    public static void assertGeneratedContentMatches(String expectedOutputFolder, Map<String, List<String>> generatedFiles) {
        var path = Paths.get(expectedOutputFolder + "/" + EXPECTED_OUTPUT_NAME);
        if (!Files.exists(path)) {
            assertThat(generatedFiles).isEmpty();
            return;
        }

        var expectedFileNames = new HashSet<String>();
        var assertList = new ArrayList<Executable>();
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path expectedOutputFile, BasicFileAttributes attrs) {
                    String expectedFileName = expectedOutputFile.getFileName().toString().replace(".java", "");
                    expectedFileNames.add(expectedFileName);
                    var expectedFile = readFileAsStrings(expectedOutputFile);
                    if (!generatedFiles.containsKey(expectedFileName)) {
                        return FileVisitResult.CONTINUE;
                    }

                    var generatedFile = generatedFiles.get(expectedFileName);
                    var expected = formatExpectedFile(expectedFile);
                    assertList.add(
                            () -> assertThat(formatGeneratedFile(generatedFile))
                                    .withFailMessage(
                                            "\u001B[33;1m%s\u001B[0;35m\n%s\n\n\u001B[33;1m%s\u001B[0;35m\n%s\n\u001B[0m",
                                            MSG_GENERATED_EXPECT,
                                            String.join("\n", generatedFile),
                                            MSG_GENERATED_ACTUAL,
                                            expected
                                    )
                                    .isEqualToIgnoringWhitespace(expected)
                    );

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        assertList.add(() -> assertThat(generatedFiles.keySet()).containsExactlyInAnyOrderElementsOf(expectedFileNames));
        assertAll(assertList);
    }

    private static String formatGeneratedFile(List<String> generatedFile) {
        return String.join("\n", generatedFile);
    }

    private static String formatExpectedFile(List<String> expectedFile) {
        var trimmedExpectedFile = new ArrayList<String>();
        var isText = false;
        for (var line : expectedFile) {
            if (!line.isEmpty()) {
                isText = true;
            }
            if (isText) {
                trimmedExpectedFile.add(line);
            }
        }
        return String.join("\n", trimmedExpectedFile);
    }

    @AfterEach
    public void destroy() {
        GeneratorConfig.clear(); // To prevent any config from remaining when running multiple tests.
    }

    protected static List<String> readFileAsStrings(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Could not read file: " + file.toFile().getPath(), e);
        }
    }

    protected String getSubpath() {
        return "";
    }

    protected boolean getCheckProcessedSchemaDefault() {
        return true;
    }
}
