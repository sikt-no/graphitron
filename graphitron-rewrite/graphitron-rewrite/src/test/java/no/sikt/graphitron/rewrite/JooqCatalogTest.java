package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.codereferences.dummyreferences.PersonIdRecord;
import no.sikt.graphitron.codereferences.dummyreferences.PlatformIdRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit coverage for the reflection helpers backing
 * {@link JooqCatalog#hasPlatformIdAccessors} and {@link JooqCatalog#sqlToAccessorSuffix}.
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

    // --- recordHasPlatformIdAccessors ---

    @Test
    void detectsPlatformIdPairOnRecordClass() {
        assertThat(JooqCatalog.recordHasPlatformIdAccessors(PlatformIdRecord.class, "getId", "setId")).isTrue();
    }

    @Test
    void detectsNonIdPlatformIdPairOnRecordClass() {
        assertThat(JooqCatalog.recordHasPlatformIdAccessors(PersonIdRecord.class, "getPersonId", "setPersonId")).isTrue();
    }

    @Test
    void rejectsWhenBothMethodsAbsent() {
        assertThat(JooqCatalog.recordHasPlatformIdAccessors(MissingBoth.class, "getId", "setId")).isFalse();
    }

    @Test
    void rejectsWhenOnlySetterPresent() {
        assertThat(JooqCatalog.recordHasPlatformIdAccessors(MissingGetter.class, "getId", "setId")).isFalse();
    }

    @Test
    void rejectsWhenOnlyGetterPresent() {
        assertThat(JooqCatalog.recordHasPlatformIdAccessors(MissingSetter.class, "getId", "setId")).isFalse();
    }

    @Test
    void rejectsWhenGetterReturnTypeIsNotString() {
        assertThat(JooqCatalog.recordHasPlatformIdAccessors(WrongGetterReturn.class, "getId", "setId")).isFalse();
    }

    @Test
    void rejectsWhenSetterParamIsNotString() {
        assertThat(JooqCatalog.recordHasPlatformIdAccessors(WrongSetterParam.class, "getId", "setId")).isFalse();
    }

    @Test
    void rejectsWhenSetterReturnIsNotVoid() {
        assertThat(JooqCatalog.recordHasPlatformIdAccessors(NonVoidSetter.class, "getId", "setId")).isFalse();
    }

    // --- sqlToAccessorSuffix ---

    @Test
    void sqlToAccessorSuffix_uppercaseSingleWord() {
        assertThat(JooqCatalog.sqlToAccessorSuffix("ID")).isEqualTo("Id");
    }

    @Test
    void sqlToAccessorSuffix_uppercaseCompound() {
        assertThat(JooqCatalog.sqlToAccessorSuffix("PERSON_ID")).isEqualTo("PersonId");
    }

    @Test
    void sqlToAccessorSuffix_lowercase() {
        assertThat(JooqCatalog.sqlToAccessorSuffix("id")).isEqualTo("Id");
    }

    @Test
    void sqlToAccessorSuffix_camelCase() {
        assertThat(JooqCatalog.sqlToAccessorSuffix("personId")).isEqualTo("PersonId");
    }
}
