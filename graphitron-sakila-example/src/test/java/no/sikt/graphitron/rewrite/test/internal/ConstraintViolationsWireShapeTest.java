package no.sikt.graphitron.rewrite.test.internal;

import graphql.GraphQLError;
import graphql.Scalars;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.MergedField;
import graphql.execution.ResultPath;
import graphql.language.Field;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import jakarta.validation.constraints.Size;
import no.sikt.graphitron.generated.schema.ConstraintViolations;
import no.sikt.graphitron.generated.schema.GraphitronContext;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit coverage for the generator-emitted {@link ConstraintViolations} helper: the wire shape
 * of a validation error, read off the {@link GraphQLError} it returns.
 *
 * <p>What earns a test here rather than at the execution tier is {@code extensions}. The execution
 * cases observe a validation error through an {@code @error} type's {@code path} and
 * {@code message} fields, but no sakila {@code @error} type exposes the extensions map, so
 * {@code extensions.constraint} (which the error-channel how-to promises) is visible only on the
 * {@code GraphQLError} itself. The column-length error ({@code sizeExceeded}) and a Bean Validation
 * error ({@code toGraphQLError}) are built side by side so the two cannot drift.
 */
@UnitTier
class ConstraintViolationsWireShapeTest {

    private static final String FIELD = "createFilmsWithErrors";

    /** A bean with one constraint the column-length check mirrors, for a real Jakarta violation. */
    static final class TitleBean {
        @Size(max = 3)
        final String title;

        TitleBean(String title) {
            this.title = title;
        }
    }

    /** A list whose elements carry the constraint, for a violation with a container-element node. */
    static final class TitlesBean {
        final List<@Size(max = 3) String> titles;

        TitlesBean(List<String> titles) {
            this.titles = titles;
        }
    }

    @Test
    void sizeExceeded_carriesTheMessageThePathAndTheSizeConstraint() {
        var error = ConstraintViolations.sizeExceeded(env(), List.of("in", 1, "title"), 255);

        assertThat(error.getMessage()).isEqualTo("size must be between 0 and 255");
        assertThat(error.getPath()).containsExactly(FIELD, "in", 1, "title");
        assertThat(error.getExtensions()).containsExactly(Map.entry("constraint", "Size"));
    }

    @Test
    void aBeanValidationViolation_hasTheSameShape() {
        var env = env();
        var violation = GraphitronContext.GraphitronContextImpl.INSTANCE.getValidator(env)
            .validate(new TitleBean("toolong")).iterator().next();

        var jakarta = ConstraintViolations.toGraphQLError(violation, env, List.of("in"));
        var length = ConstraintViolations.sizeExceeded(env, List.of("in", "title"), 3);

        assertThat(jakarta.getPath()).containsExactly(FIELD, "in", "title").isEqualTo(length.getPath());
        assertThat(jakarta.getExtensions()).containsExactly(Map.entry("constraint", "Size"))
            .isEqualTo(length.getExtensions());
        assertThat(jakarta.getMessage()).isEqualTo(violation.getMessage());
    }

    @Test
    void aContainerElementViolation_putsTheElementIndexIntoThePath() {
        var env = env();
        var violation = GraphitronContext.GraphitronContextImpl.INSTANCE.getValidator(env)
            .validate(new TitlesBean(List.of("ok", "toolong"))).iterator().next();

        assertThat(ConstraintViolations.toGraphQLError(violation, env, List.of("in")).getPath())
            .containsExactly(FIELD, "in", "titles", 1);
    }

    @Test
    void aListElementsViolation_carriesTheElementIndexAfterTheArgumentName() {
        // A list of input objects is validated element by element, the element's index following the
        // argument name, so a violation lands under the element it belongs to.
        var env = env();
        var violation = GraphitronContext.GraphitronContextImpl.INSTANCE.getValidator(env)
            .validate(new TitleBean("toolong")).iterator().next();

        var error = ConstraintViolations.toGraphQLError(violation, env, List.of("in", 1));
        var length = ConstraintViolations.sizeExceeded(env, List.of("in", 1, "title"), 3);

        assertThat(error.getPath()).containsExactly(FIELD, "in", 1, "title").isEqualTo(length.getPath());
    }

    private static DataFetchingEnvironment env() {
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .mergedField(MergedField.newMergedField(Field.newField(FIELD).build()).build())
            .executionStepInfo(ExecutionStepInfo.newExecutionStepInfo()
                .type(Scalars.GraphQLString)
                .path(ResultPath.rootPath().segment(FIELD))
                .build())
            .build();
    }
}
