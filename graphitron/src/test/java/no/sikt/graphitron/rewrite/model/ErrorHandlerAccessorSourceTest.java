package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ClientMessage;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ExceptionClass;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage of {@link ErrorType.Handler#accessorSourceClassName()}: the class whose accessors
 * back an {@code @error} type's extra fields, one row per handler arm. The generated client
 * exception reads from its superclass, which is what lets the accessor check run on a clean build.
 */
@UnitTier
class ErrorHandlerAccessorSourceTest {

    private static final ClientMessage FROM_SOURCE = new ClientMessage.FromSource();

    @Test
    void classpathExceptionHandler_readsTheNamedClass() {
        var h = new ErrorType.ExceptionHandler(
            new ExceptionClass.ClasspathClass("java.lang.IllegalStateException"), Optional.empty(), FROM_SOURCE);
        assertThat(h.accessorSourceClassName()).isEqualTo("java.lang.IllegalStateException");
    }

    @Test
    void generatedClientExceptionHandler_readsTheSuperclass() {
        var h = new ErrorType.ExceptionHandler(
            new ExceptionClass.GeneratedClientException("com.example.schema.GraphitronClientException"),
            Optional.empty(), FROM_SOURCE);
        assertThat(h.exceptionClassName()).isEqualTo("com.example.schema.GraphitronClientException");
        assertThat(h.accessorSourceClassName()).isEqualTo("graphql.GraphqlErrorException");
    }

    @Test
    void sqlStateHandler_readsSqlException() {
        assertThat(new ErrorType.SqlStateHandler("23503", Optional.empty(), FROM_SOURCE).accessorSourceClassName())
            .isEqualTo("java.sql.SQLException");
    }

    @Test
    void vendorCodeHandler_readsSqlException() {
        assertThat(new ErrorType.VendorCodeHandler("1", Optional.empty(), FROM_SOURCE).accessorSourceClassName())
            .isEqualTo("java.sql.SQLException");
    }

    @Test
    void validationHandler_readsGraphQLError() {
        assertThat(new ErrorType.ValidationHandler().accessorSourceClassName())
            .isEqualTo("graphql.GraphQLError");
    }
}
