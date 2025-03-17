package no.sikt.graphitron.definitions.helpers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScalarUtilsTest {

    @Test
    @DisplayName("Should return set of built-in scalar names")
    void builtInScalarNames() {
        Set<String> builtInScalars = ScalarUtils.getBuiltInScalarNames();
        assertThat(builtInScalars).contains("String", "Int", "Boolean");
    }

    @Test
    @DisplayName("Should return map of extended scalar names to ExtendedScalar.-field names")
    void extendedScalars() {
        Map<String, String> extendedScalars = ScalarUtils.getExtendedScalars();
        assertThat(extendedScalars)
                .containsEntry("BigDecimal", "GraphQLBigDecimal")
                .containsEntry("Long", "GraphQLLong");
    }

    @Test
    @DisplayName("Should return map of extended scalar names to Java class names")
    void extendedScalarsTypeMapping() {
        Map<String, String> extendedScalarsTypeMapping = ScalarUtils.getExtendedScalarsTypeMapping();
        assertThat(extendedScalarsTypeMapping).containsEntry("BigDecimal", "java.math.BigDecimal")
                .containsEntry("Long", "java.lang.Long");
    }
}