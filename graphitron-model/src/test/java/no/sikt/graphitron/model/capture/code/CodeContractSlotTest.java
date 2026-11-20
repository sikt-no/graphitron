package no.sikt.graphitron.model.capture.code;

import no.sikt.graphitron.model.test.FactStores;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.CODE_CONTRACT_SLOT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds code_contract_slot to graphql-java: every accessor it states is one the library's class
 * declares, under the name it states and returning the class it states, and every getter the
 * library's class offers, inherited ones included, is one it states. The relation is stated
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
                assertThat(method.getReturnType().getTypeName())
                    .as(row.getClassName() + "#" + row.getMethodName() + " returns")
                    .isEqualTo(row.getErasedClass());
                String property = Character.toLowerCase(row.getMethodName().charAt(3))
                    + row.getMethodName().substring(4);
                assertThat(row.getSlotName()).as("the slot is the accessor's bean property").isEqualTo(property);
            }
        }
    }

    /**
     * An @error type's field reads a get or is getter, and an inherited one is as readable as a
     * declared one: graphql.GraphqlErrorException offers getLocalizedMessage, getStackTrace and
     * getSuppressed through Throwable. A getter the contract leaves out is a field refused as
     * unreadable that the generated code would read.
     */
    @Test
    void everyGetterAStatedClassOffersIsStated() throws Exception {
        try (var store = FactStores.inMemory()) {
            var stated = store.dsl().selectFrom(CODE_CONTRACT_SLOT).fetch().stream()
                .collect(Collectors.groupingBy(r -> r.getClassName(),
                    Collectors.toMap(r -> r.getSlotName(), r -> r.getErasedClass())));
            assertThat(stated).as("the contract states graphql.GraphqlErrorException")
                .containsKey("graphql.GraphqlErrorException");
            for (var entry : stated.entrySet()) {
                Map<String, String> offered = new TreeMap<>();
                Arrays.stream(Class.forName(entry.getKey()).getMethods())
                    .filter(m -> m.getParameterCount() == 0 && !m.getName().equals("getClass"))
                    .filter(m -> isGetter(m.getName()))
                    .forEach(m -> offered.put(property(m), m.getReturnType().getTypeName()));
                assertThat(entry.getValue()).as("the slots " + entry.getKey() + " offers")
                    .containsExactlyInAnyOrderEntriesOf(offered);
            }
        }
    }

    private static boolean isGetter(String name) {
        return name.length() > 3 && name.startsWith("get") && Character.isUpperCase(name.charAt(3))
            || name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2));
    }

    private static String property(Method method) {
        String rest = method.getName().substring(method.getName().startsWith("is") ? 2 : 3);
        return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
    }
}
