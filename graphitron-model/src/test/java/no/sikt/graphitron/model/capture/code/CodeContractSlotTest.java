package no.sikt.graphitron.model.capture.code;

import no.sikt.graphitron.model.test.FactStores;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.model.Tables.CODE_CONTRACT_SLOT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds code_contract_slot to graphql-java: every accessor it states is one the library's class
 * declares, under the name it states and returning the class it states. The relation is stated
 * because graphql-java sits on no entry a consumer's build makes nameable, so an upgrade that moves
 * an accessor fails here rather than in the code generated against it.
 */
class CodeContractSlotTest {

    @Test
    void everyStatedAccessorIsOneGraphqlJavaDeclares() throws Exception {
        try (var store = FactStores.inMemory()) {
            var rows = store.dsl().selectFrom(CODE_CONTRACT_SLOT).fetch();
            assertThat(rows).as("the contract states accessors").isNotEmpty();
            for (var row : rows) {
                var method = Class.forName(row.getClassName()).getMethod(row.getMethodName());
                assertThat(method.getParameterCount()).as(row.getClassName() + "#" + row.getMethodName()).isZero();
                assertThat(method.getReturnType().getName())
                    .as(row.getClassName() + "#" + row.getMethodName() + " returns")
                    .isEqualTo(row.getErasedClass());
                String property = Character.toLowerCase(row.getMethodName().charAt(3))
                    + row.getMethodName().substring(4);
                assertThat(row.getSlotName()).as("the slot is the accessor's bean property").isEqualTo(property);
            }
        }
    }
}
