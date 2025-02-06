package no.sikt.graphitron.servlet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/***
 * Se https://graphql-http.com/ for den autorative audit suiten fra GraphQL over HTTP-arbeidsgruppen.
 * Bruk kommandoen: `npm run test` i `fsapi-rest/fsapi-rest` for å kjøre disse testene lokalt.
 *
 * Følgende enhetstester skal ikke gjenskape testene i den autorative audit-suiten.
 */
public class GraphqlHttpRequestTest {
    @ParameterizedTest
    @CsvSource(delimiterString = " | ", value = {
            "application/graphql-response+json | application/graphql-response+json;charset=utf-8,application/json;charset=utf-8",
            "application/graphql-response+json | application/graphql-response+json;charset=utf-8,application/json;charset=utf-8;q=0.9",
            "application/graphql-response+json | application/graphql-response+json;charset=utf-8;q=0.9,application/json;charset=utf-8;q=0.9",
            "application/json                  | application/graphql-response+json;charset=utf-8;q=0.9,application/json;charset=utf-8",
            "application/json                  | application/graphql-response+json;charset=utf-8;q=0.8,application/json;charset=utf-8;q=0.9",
            "application/json                  | */*",
            "application/json                  | ",


    })
    public void testParseRequestedMediaType(String expected, String s) {
        var actual = GraphqlHttpRequest.parseRequestedMediaType(s);
        Assertions.assertEquals(expected, actual);
    }
    @ParameterizedTest
    @CsvSource(delimiterString = " | ", value = {
            "true  | application/json",
            "false | application/graphql-response+json",
            "false | application/xml",
    })
    public void testPostedMediaTypeSupported(boolean expected, String s) {
        var actual = GraphqlHttpRequest.postedMediaTypeSupported(s);
        Assertions.assertEquals(expected, actual);
    }

    @ParameterizedTest
    @CsvSource(delimiterString = " | ", value = {
            "false | query { __typename }                             | ",
            "true  | mutation { __typename }                          | ",
            "false | query Q { __typename } mutation M { __typename } | Q",
            "true  | query Q { __typename } mutation M { __typename } | M",
    })
    public void testIsMutation(boolean expected, String s, String operationName) {
        var actual = GraphqlHttpRequest.isMutation(s, operationName);
        Assertions.assertEquals(expected, actual);
    }
}
