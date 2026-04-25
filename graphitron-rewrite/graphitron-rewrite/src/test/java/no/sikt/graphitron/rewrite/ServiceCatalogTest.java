package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link ServiceCatalog#reflectServiceMethod} parameter classification.
 * Exercises the reflection path in isolation with synthetic {@link TestServiceStub} methods;
 * the classifier does not read {@code BuildContext.schema} or {@code BuildContext.catalog},
 * so both may be {@code null} here.
 */
class ServiceCatalogTest {

    private static final String STUB_CLASS = "no.sikt.graphitron.rewrite.TestServiceStub";

    private static ServiceCatalog newCatalog() {
        return new ServiceCatalog(new BuildContext(null, null, null));
    }

    @Test
    void reflectServiceMethod_dslContextParam_classifiedAsDslContextSource() {
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getByIdWithDsl", Set.of("id"), Set.of(), List.of(), null);

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).hasSize(2);
        assertThat(params.get(0)).isInstanceOf(MethodRef.Param.Typed.class);
        assertThat(params.get(0).source()).isInstanceOf(ParamSource.DslContext.class);
        assertThat(params.get(0).typeName()).isEqualTo("org.jooq.DSLContext");
        assertThat(params.get(1).source()).isInstanceOf(ParamSource.Arg.class);
        assertThat(params.get(1).name()).isEqualTo("id");
    }

    @Test
    void reflectServiceMethod_dslContextOnly_noArgs() {
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getWithDsl", Set.of(), Set.of(), List.of(), null);

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).hasSize(1);
        assertThat(params.get(0).source()).isInstanceOf(ParamSource.DslContext.class);
        assertThat(params).noneMatch(p -> p instanceof MethodRef.Param.Sourced);
    }

    @Test
    void reflectServiceMethod_dslContextParamNameCollidesWithArg_typeWins() {
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilteredWithDsl", Set.of("filter"), Set.of(), List.of(), null);

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).hasSize(1);
        assertThat(params.get(0).source()).isInstanceOf(ParamSource.DslContext.class);
    }

    @Test
    void reflectServiceMethod_unrecognisedParam_stillErrors() {
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getWithUnknown", Set.of(), Set.of(), List.of(), null);

        assertThat(result.failed()).isTrue();
        assertThat(result.failureReason()).contains("unrecognized sources type");
    }
}
