package no.sikt.graphitron.code;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.mappings.RoutineReflection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.setProperties;
import static no.sikt.graphitron.mappings.RoutineReflection.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Reflection - Use reflection on jOOQ routine code")
public class RoutineReflectionTest {
    @BeforeEach
    public void setup() {
        setProperties();
    }

    @AfterEach
    public void destroy() {
        GeneratorConfig.clear();
    }

    @Test
    @DisplayName("Resolves a unique routine by bare name")
    public void resolveUniqueByBareName() {
        assertThat(resolveRoutine("get_customer_balance")).hasSize(1);
        assertThat(resolveRoutine("inventory_in_stock")).hasSize(1);
    }

    @Test
    @DisplayName("Matching is case-insensitive")
    public void resolveIsCaseInsensitive() {
        assertThat(resolveRoutine("GET_CUSTOMER_BALANCE")).hasSize(1);
        assertThat(resolveRoutine("Get_Customer_Balance")).hasSize(1);
    }

    @Test
    @DisplayName("Returns empty for unknown routine name")
    public void resolveUnknownReturnsEmpty() {
        assertThat(resolveRoutine("no_such_routine")).isEmpty();
    }

    @Test
    @DisplayName("Returns multiple matches when the same routine exists in multiple schemas")
    public void resolveAmbiguousAcrossSchemas() {
        // last_day exists in both public and utils (see test fixture)
        var matches = resolveRoutine("last_day");
        assertThat(matches).hasSize(2);
        assertThat(matches.stream().map(RoutineReflection::schemaFromProcedure).sorted().toList())
                .containsExactly("public", "utils");
    }

    @Test
    @DisplayName("Schema-qualified lookup disambiguates colliding routines")
    public void resolveQualifiedDisambiguates() {
        var publicLastDay = resolveRoutine("public.last_day");
        var utilsLastDay = resolveRoutine("utils.last_day");
        assertThat(publicLastDay).hasSize(1);
        assertThat(utilsLastDay).hasSize(1);
        assertThat(publicLastDay.get(0)).isNotEqualTo(utilsLastDay.get(0));
    }

    @Test
    @DisplayName("Schema-qualified lookup works for non-colliding routines too")
    public void resolveQualifiedForUniqueRoutine() {
        assertThat(resolveRoutine("public.get_customer_balance")).hasSize(1);
        assertThat(resolveRoutine("utils.only_here")).hasSize(1);
    }

    @Test
    @DisplayName("Schema-qualified lookup returns empty when the schema does not contain the routine")
    public void resolveQualifiedWrongSchema() {
        // only_here exists only in utils, not in public
        assertThat(resolveRoutine("public.only_here")).isEmpty();
        // get_customer_balance exists only in public, not in utils
        assertThat(resolveRoutine("utils.get_customer_balance")).isEmpty();
    }

    @Test
    @DisplayName("Null and blank inputs resolve to empty")
    public void resolveNullAndBlank() {
        assertThat(resolveRoutine(null)).isEmpty();
        assertThat(resolveRoutine("")).isEmpty();
        assertThat(resolveRoutine("   ")).isEmpty();
    }

    @Test
    @DisplayName("Functions have return parameters")
    public void functionHasReturnParameter() {
        var key = uniqueMatch(resolveRoutine("get_customer_balance"));
        assertThat(isFunction(key)).isTrue();
        assertThat(getReturnType(key)).hasValue(BigDecimal.class);
    }

    @Test
    @DisplayName("Can get return type for different functions")
    public void getReturnTypes() {
        assertThat(getReturnType(uniqueMatch(resolveRoutine("inventory_in_stock")))).hasValue(Boolean.class);
        assertThat(getReturnType(uniqueMatch(resolveRoutine("public.last_day")))).hasValue(LocalDate.class);
    }

    @Test
    @DisplayName("Can get IN parameters for a routine")
    public void getInParametersForRoutine() {
        var params = getInParameters(uniqueMatch(resolveRoutine("get_customer_balance")));
        assertThat(params).hasSize(2);
        assertThat(params.get(0).getName()).isEqualTo("p_customer_id");
        assertThat(params.get(1).getName()).isEqualTo("p_effective_date");
    }

    @Test
    @DisplayName("Can get IN parameters for single-parameter routine")
    public void getInParametersForSingleParamRoutine() {
        var params = getInParameters(uniqueMatch(resolveRoutine("inventory_in_stock")));
        assertThat(params).hasSize(1);
        assertThat(params.get(0).getName()).isEqualTo("p_inventory_id");
        assertThat(params.get(0).getType()).isEqualTo(Long.class);
    }

    @Test
    @DisplayName("Returns empty for non-existent routine parameters")
    public void emptyForNonExistentRoutineParameters() {
        assertThat(getInParameters("no_such_schema.no_such_routine")).isEmpty();
        assertThat(getOutParameters("no_such_schema.no_such_routine")).isEmpty();
    }

    private static String uniqueMatch(List<String> matches) {
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }
}
