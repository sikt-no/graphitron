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
import org.approvaltests.namer.NamerWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

/***
 * Runs all queries found under resources/approval/queries
 * and compares with previously verified results found under resources/approval/approvals.
 * To pass the test, the query must return the same result as the previously verified result.
 */
@QuarkusTest
public class ApprovalQueryTest {

    private static final Path QUERY_FILE_DIRECTORY = Paths.get("src", "test", "resources", "approval", "queries");
    private static final Path APPROVALS_FILE_DIRECTORY = Paths.get("src", "test", "resources", "approval", "approvals");

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

        verify(response, new Options()
                .withScrubber(str -> UUID_PATTERN.matcher(str).replaceAll("[UUID]"))
                .forFile().withNamer(new NamerWrapper(
                        () -> queryFile.replace(".graphql", "-" + variableIdx + ".result"),
                        APPROVALS_FILE_DIRECTORY::toString))
                .forFile().withExtension(".json"));
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
