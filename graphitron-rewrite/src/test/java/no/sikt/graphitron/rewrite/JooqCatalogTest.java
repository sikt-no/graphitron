package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.codereferences.dummyreferences.PlatformIdRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit coverage for the reflection helper backing {@link JooqCatalog#hasPlatformIdMethods}.
 * Uses synthetic record stubs rather than a real jOOQ catalog to exercise signature checks
 * (arity, parameter types, return types) in isolation.
 */
class JooqCatalogTest {

    static class MissingBoth {}

    static class MissingSetter {
        public String getId() { return ""; }
    }

    static class MissingGetter {
        public void setId(String id) {}
    }

    static class WrongGetterReturn {
        public int getId() { return 0; }
        public void setId(String id) {}
    }

    static class WrongSetterParam {
        public String getId() { return ""; }
        public void setId(Integer id) {}
    }

    static class NonVoidSetter {
        public String getId() { return ""; }
        public String setId(String id) { return id; }
    }

    @Test
    void detectsPlatformIdPairOnRecordClass() {
        assertThat(JooqCatalog.recordHasPlatformIdMethods(PlatformIdRecord.class)).isTrue();
    }

    @Test
    void rejectsWhenBothMethodsAbsent() {
        assertThat(JooqCatalog.recordHasPlatformIdMethods(MissingBoth.class)).isFalse();
    }

    @Test
    void rejectsWhenOnlySetterPresent() {
        assertThat(JooqCatalog.recordHasPlatformIdMethods(MissingGetter.class)).isFalse();
    }

    @Test
    void rejectsWhenOnlyGetterPresent() {
        assertThat(JooqCatalog.recordHasPlatformIdMethods(MissingSetter.class)).isFalse();
    }

    @Test
    void rejectsWhenGetterReturnTypeIsNotString() {
        assertThat(JooqCatalog.recordHasPlatformIdMethods(WrongGetterReturn.class)).isFalse();
    }

    @Test
    void rejectsWhenSetterParamIsNotString() {
        assertThat(JooqCatalog.recordHasPlatformIdMethods(WrongSetterParam.class)).isFalse();
    }

    @Test
    void rejectsWhenSetterReturnIsNotVoid() {
        assertThat(JooqCatalog.recordHasPlatformIdMethods(NonVoidSetter.class)).isFalse();
    }
}
