package no.sikt.graphitron.example.server.approval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Header;
import io.restassured.http.Headers;
import jakarta.json.Json;
import jakarta.json.JsonString;
import org.approvaltests.core.Options;
import org.approvaltests.namer.ApprovalNamer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.approvaltests.Approvals.verify;

/**
 * Runs all queries found under resources/approval/queries and compares each response
 * to a previously verified result. With no {@code -Dapproval.variant} system property,
 * results are read from resources/approval/approvals. With a variant active, results
 * are read from resources/approval/approvals/variants/&lt;variant&gt;/ first, falling back
 * to the default approvals directory when the variant has no override for that query.
 */
@QuarkusTest
public class ApprovalQueryTest {

    private static final Path QUERY_FILE_DIRECTORY = Paths.get("src", "test", "resources", "approval", "queries");
    private static final Path APPROVALS_BASE = Paths.get("src", "test", "resources", "approval", "approvals");
    private static final String VARIANT = System.getProperty("approval.variant", "");
    private static final Path VARIANT_OVERRIDES = VARIANT.isBlank() ? null : APPROVALS_BASE.resolve("variants").resolve(VARIANT);

    private static Path activeDirectory() {
        return VARIANT_OVERRIDES != null ? VARIANT_OVERRIDES : APPROVALS_BASE;
    }

    private static final Pattern UUID_PATTERN = Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");

    @DisplayName("Verifying recorded query results")
    @ParameterizedTest(name = "{index} {0} - {4} - variables ({3})")
    @MethodSource("queryFiles")
    public void executeQuery(String queryFile, Headers headers, Map<String, Object> variables, int variableIdx, String description) throws IOException {
        var query = Files.readString(QUERY_FILE_DIRECTORY.resolve(queryFile));

        var response = given()
                .contentType(JSON)
                .body(Map.of("query", query, "variables", variables))
                .headers(headers)
                .post("graphql")
                .body()
                .asPrettyString();

        var approvalName = queryFile.replace(".graphql", "-" + variableIdx + ".result");
        verify(response, new Options()
                .withScrubber(str -> UUID_PATTERN.matcher(str).replaceAll("[UUID]"))
                .forFile().withNamer(new VariantOverrideNamer(approvalName))
                .forFile().withExtension(".json"));
    }

    /**
     * Approved files are looked up in the variant override directory first, falling back
     * to the default approvals directory. Received files always land in the variant
     * override directory (or the default directory when no variant is active), so accepting
     * a divergent result is a simple .received -> .approved rename in place.
     */
    private record VariantOverrideNamer(String approvalName) implements ApprovalNamer {

        @Override
        public File getApprovedFile(String extensionWithDot) {
            if (VARIANT_OVERRIDES != null) {
                var override = VARIANT_OVERRIDES.resolve(approvalName + ".approved" + extensionWithDot).toFile();
                if (override.exists()) {
                    return override;
                }
            }
            return APPROVALS_BASE.resolve(approvalName + ".approved" + extensionWithDot).toFile();
        }

        @Override
        public File getReceivedFile(String extensionWithDot) {
            return activeDirectory().resolve(approvalName + ".received" + extensionWithDot).toFile();
        }

        @Override
        public String getApprovalName() {
            return approvalName;
        }

        @Override
        public String getSourceFilePath() {
            return activeDirectory().toString();
        }

        @Override
        public ApprovalNamer addAdditionalInformation(String info) {
            return this;
        }

        @Override
        public String getAdditionalInformation() {
            return "";
        }
    }

    private static Stream<Arguments> queryFiles() throws IOException {
        return Files.walk(QUERY_FILE_DIRECTORY)
                .filter(it -> it.toString().endsWith("graphql"))
                .map(it -> it.subpath(QUERY_FILE_DIRECTORY.getNameCount(), it.getNameCount()))
                .map(Path::toString)
                .flatMap(ApprovalQueryTest::queryfileToArguments);
    }

    private static Stream<Arguments> queryfileToArguments(String queryFile) {
        var headers = getHeaders(queryFile);
        var variables = getVariables(queryFile);
        var description = getTopLineComment(queryFile);
        AtomicInteger count = new AtomicInteger(0);

        return variables.map(it -> Arguments.of(queryFile, headers, it, count.incrementAndGet(), description.orElse("")));
    }

    /**
     *  Get the first line of the file if it starts with #
     */
    private static Optional<String> getTopLineComment(String queryFile) {
        try {
            var lines = Files.readAllLines(QUERY_FILE_DIRECTORY.resolve(queryFile));
            return lines.stream()
                    .filter(line -> line.startsWith("#"))
                    .findFirst()
                    .map(line -> line.substring(1).trim());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Stream<Map<String, Object>> getVariables(String queryFile) {
        var variablesFilename = queryFile.replaceAll("\\.graphql$", ".variables.json");

        try {
            var is = Files.newInputStream(QUERY_FILE_DIRECTORY.resolve(variablesFilename));
            var objectMapper = JsonMapper.builder()
                    .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                    .build()
                    .registerModule(new JavaTimeModule());

            TypeReference<List<Map<String, Object>>> mapTypeReference = new TypeReference<>() {
            };
            var variables = objectMapper.readValue(is, mapTypeReference);
            return variables.stream();
        } catch (IOException e) {
            return Stream.of(Map.of());
        }
    }

    private static Headers getHeaders(String queryFile) {
        var headersFilename = queryFile.replaceAll("\\.graphql$", ".headers.json");
        var queryHeadersPath = QUERY_FILE_DIRECTORY.resolve(headersFilename);
        var defaultHeadersPath = queryHeadersPath.getParent().resolve("headers.json");

        var defaultHeaders = getHeadersFrom(defaultHeadersPath);
        var queryHeaders = getHeadersFrom(queryHeadersPath);
        var queryHeaderNames = queryHeaders.stream().map(Header::getName).collect(Collectors.toSet());

        var merged = defaultHeaders.stream()
                .filter(it -> !queryHeaderNames.contains(it.getName()))
                .collect(Collectors.toList());
        merged.addAll(queryHeaders);
        return new Headers(merged);
    }

    private static List<Header> getHeadersFrom(Path headersPath) {
        if (!Files.exists(headersPath)) {
            return List.of();
        }

        try {
            var s = Files.readString(headersPath, StandardCharsets.UTF_8);
            var reader = Json.createReader(new StringReader(s)).readObject();
            return reader
                    .entrySet()
                    .stream()
                    .map(it -> new Header(it.getKey(), ((JsonString) it.getValue()).getString()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }
}
