package no.sikt.graphitron.rewrite.walker;

import graphql.Scalars;
import graphql.language.SourceLocation;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.ErrorChannelWalkerError;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.model.OutcomeType;
import no.sikt.graphitron.rewrite.model.WalkerResult;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R244 walker unit-tier tests. Exercises {@link ErrorChannelWalker#walk} over the happy path
 * (single and multiple mapped {@code @error} types) and each {@link ErrorChannelWalkerError} arm
 * the walker raises (rule 7, rule 8, handler-accessor-missing). The walker reads the flattened
 * {@code @error}-type list off the {@link OutcomeType}'s errors field, so the SDL polymorphism
 * shapes (single / union / interface / list) collapse to the same {@code List<ErrorType>} input
 * here; they are an upstream classification concern, asserted at the pipeline tier.
 */
@UnitTier
class ErrorChannelWalkerTest {

    private final ErrorChannelWalker walker = new ErrorChannelWalker();

    @Test
    void walk_singleErrorType_producesMappedWithScreamingSnakeName() {
        var et = errorType("NotFound", exception("java.lang.RuntimeException"));
        var result = walker.walk(outcome("FilmPayload", "errors", et), emptySchema(),
            loader(), t -> String.class);

        var mapped = ok(result);
        assertThat(mapped.mappingsConstantName()).isEqualTo("FILM_PAYLOAD");
        assertThat(mapped.mappedErrorTypes()).extracting(ErrorType::name).containsExactly("NotFound");
    }

    @Test
    void walk_multipleErrorTypes_carriesAllInSourceOrder() {
        var a = errorType("NotFound", exception("java.lang.RuntimeException"));
        var b = errorType("Conflict", exception("java.lang.IllegalStateException"));
        var result = walker.walk(outcome("SakPayload", "errors", a, b), emptySchema(),
            loader(), t -> String.class);

        var mapped = ok(result);
        assertThat(mapped.mappedErrorTypes()).extracting(ErrorType::name)
            .containsExactly("NotFound", "Conflict");
    }

    @Test
    void walk_twoValidationHandlers_raisesChannelRuleViolationRule7() {
        var a = errorType("ValidationA", validation());
        var b = errorType("ValidationB", validation());
        var result = walker.walk(outcome("FormPayload", "errors", a, b), emptySchema(),
            loader(), t -> String.class);

        assertThat(err(result)).anySatisfy(e -> {
            assertThat(e).isInstanceOf(ErrorChannelWalkerError.ChannelRuleViolation.class);
            var v = (ErrorChannelWalkerError.ChannelRuleViolation) e;
            assertThat(v.ruleNumber()).isEqualTo(7);
            assertThat(v.payloadTypeName()).isEqualTo("FormPayload");
            assertThat(v.errorsFieldName()).isEqualTo("errors");
        });
    }

    @Test
    void walk_duplicateMatchCriteria_raisesChannelRuleViolationRule8() {
        var et = errorType("Dup",
            exception("java.lang.RuntimeException"),
            exception("java.lang.RuntimeException"));
        var result = walker.walk(outcome("DupPayload", "errors", et), emptySchema(),
            loader(), t -> String.class);

        assertThat(err(result)).anySatisfy(e -> {
            assertThat(e).isInstanceOf(ErrorChannelWalkerError.ChannelRuleViolation.class);
            assertThat(((ErrorChannelWalkerError.ChannelRuleViolation) e).ruleNumber()).isEqualTo(8);
        });
    }

    @Test
    void walk_missingHandlerAccessor_raisesHandlerSourceAccessorMissing() {
        var et = errorType("DetailError", exception(ErrorWithoutAccessor.class.getName()));
        var result = walker.walk(outcome("ThingPayload", "errors", et),
            schemaWithDetailError(), loader(), t -> String.class);

        assertThat(err(result)).anySatisfy(e -> {
            assertThat(e).isInstanceOf(ErrorChannelWalkerError.HandlerSourceAccessorMissing.class);
            var m = (ErrorChannelWalkerError.HandlerSourceAccessorMissing) e;
            assertThat(m.errorTypeName()).isEqualTo("DetailError");
            assertThat(m.missingFieldName()).isEqualTo("detail");
            assertThat(m.handlerClassName()).isEqualTo(ErrorWithoutAccessor.class.getName());
            assertThat(m.available()).contains("getSomethingElse");
        });
    }

    @Test
    void walk_presentHandlerAccessor_producesMapped() {
        var et = errorType("DetailError", exception(ErrorWithAccessor.class.getName()));
        var result = walker.walk(outcome("ThingPayload", "errors", et),
            schemaWithDetailError(), loader(), t -> String.class);

        assertThat(ok(result).mappedErrorTypes()).extracting(ErrorType::name)
            .containsExactly("DetailError");
    }

    // ===== fixtures =====

    /** Handler source class missing the {@code detail} accessor. */
    public static class ErrorWithoutAccessor {
        public String getSomethingElse() { return null; }
    }

    /** Handler source class exposing a {@code getDetail()} accessor. */
    public static class ErrorWithAccessor {
        public String getDetail() { return null; }
    }

    // ===== helpers =====

    private static ErrorChannel.Mapped ok(WalkerResult<ErrorChannel.Mapped> r) {
        assertThat(r).isInstanceOf(WalkerResult.Ok.class);
        return ((WalkerResult.Ok<ErrorChannel.Mapped>) r).carrier();
    }

    private static List<?> err(WalkerResult<ErrorChannel.Mapped> r) {
        assertThat(r).isInstanceOf(WalkerResult.Err.class);
        return ((WalkerResult.Err<ErrorChannel.Mapped>) r).errors();
    }

    private static OutcomeType outcome(String typeName, String errorsFieldName, ErrorType... errorTypes) {
        var loc = new SourceLocation(1, 1);
        var errorsField = new ChildField.ErrorsField(
            typeName, errorsFieldName, loc, List.of(errorTypes), new ChildField.Transport.WrapperArm());
        var backing = new GraphitronType.PojoResultType.Backed(typeName, loc, "com.example." + typeName);
        return new OutcomeType(backing, errorsField, List.of());
    }

    private static ErrorType errorType(String name, ErrorType.Handler... handlers) {
        return new ErrorType(name, new SourceLocation(1, 1), List.of(handlers));
    }

    private static ErrorType.ExceptionHandler exception(String className) {
        return new ErrorType.ExceptionHandler(className, Optional.empty(), Optional.empty());
    }

    private static ErrorType.ValidationHandler validation() {
        return new ErrorType.ValidationHandler(Optional.empty());
    }

    private static ClassLoader loader() {
        return ErrorChannelWalkerTest.class.getClassLoader();
    }

    private static GraphQLSchema emptySchema() {
        return GraphQLSchema.newSchema().query(dummyQuery()).build();
    }

    private static GraphQLSchema schemaWithDetailError() {
        var detailError = GraphQLObjectType.newObject()
            .name("DetailError")
            .field(GraphQLFieldDefinition.newFieldDefinition().name("message").type(Scalars.GraphQLString).build())
            .field(GraphQLFieldDefinition.newFieldDefinition().name("detail").type(Scalars.GraphQLString).build())
            .build();
        return GraphQLSchema.newSchema().query(dummyQuery()).additionalType(detailError).build();
    }

    private static GraphQLObjectType dummyQuery() {
        return GraphQLObjectType.newObject()
            .name("Query")
            .field(GraphQLFieldDefinition.newFieldDefinition().name("dummy").type(Scalars.GraphQLString).build())
            .build();
    }
}
