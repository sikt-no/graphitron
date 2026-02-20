package no.sikt.graphitron.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_NODE;

@DisplayName("Cycle validation - Detects cycles in input and output types")
public class CycleValidationTest extends ValidationTest {

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "cycle";
    }

    /*
    * Input cycles
    * */

    @Test
    @DisplayName("Input types without cycles should pass validation")
    void inputTypeNoCycle() {
        getProcessedSchema("inputTypeNoCycle", Set.of(CUSTOMER_NODE));
        assertNoWarnings();
    }

    @Test
    @DisplayName("Input types with cycle should be detected")
    void inputTypeCycle() {
        assertErrorsContain("inputTypeCycle",
                "Cycle detected: 'InputA.b' -> 'InputB.a' -> 'InputA'. Input type cycles are not allowed.");
    }

    @Test
    @DisplayName("Self-referencing input type is detected as a cycle")
    void inputTypeSelfReference() {
        assertErrorsContain("inputTypeSelfReference",
                "'InputA.a' -> 'InputA'");
    }

    /*
    * Output cycles
    * */

    @Test
    @DisplayName("Object type cycle broken by resolver should pass validation")
    void objectTypeCycleWithResolver() {
        getProcessedSchema("objectTypeCycleWithResolver");
        assertNoWarnings();
    }

    @Test
    @DisplayName("Object type cycle without resolver is detected exactly once")
    void objectTypeCycle() {
        assertErrorsContainOnce("objectTypeCycle",
                "Cycle detected", // Make sure the same cycle is not reported multiple times with different path starting point
                "Cycle detected: 'TypeB.a' -> 'TypeA.b' -> 'TypeB'. A resolver is required to break the cycle, " +
                        "for example by adding @splitQuery to (one of) the field(s)."
                );
    }

    @Test
    @DisplayName("Self-referencing object type is detected as a cycle")
    void objectTypeSelfReference() {
        assertErrorsContain("objectTypeSelfReference",
                "'TypeA.a' -> 'TypeA'");
    }

    @Test
    @DisplayName("All field paths in a cycle are included in the error message")
    void multiplePathsInCycle() {
        assertErrorsContain("multiplePathsInCycle",
                "'TypeB.path1'/'TypeB.path2' -> 'TypeA.path1'/'TypeA.path2' -> 'TypeB'"
        );
    }

    @Test
    @DisplayName("Cycle with dead-end sibling branch should not include the sibling in the error path")
    void cycleWithDeadEndSibling() {
        assertErrorsContain("cycleWithDeadEndSibling", "'TypeB.a' -> 'TypeA.b' -> 'TypeB'");
        assertErrorsDoNotContain("cycleWithDeadEndSibling", "'DeadEnd");
    }

    @Test
    @DisplayName("Self-referencing interface should be detected as a cycle")
    void interfaceCycle() {
        assertErrorsContain("interfaceCycle",
                "'I.i' -> 'I'");
    }

    @Test
    @DisplayName("Cycle through interface field should report cycle through interface and implementing types")
    void cycleThroughInterfaceField() {
        assertErrorsContain("cycleThroughInterfaceField",
                "'TypeA.i' -> 'InterfaceI.a' -> 'TypeA'",
                "'Impl.a' -> 'TypeA.i' -> 'Impl'"
        );
    }

    @Test
    @DisplayName("Cycle through interface field with multiple paths")
    void cycleThroughInterfaceFieldWithMultiplePaths() {
        assertErrorsContain("cycleThroughInterfaceFieldWithMultiplePaths",
                "'TypeA.i' -> 'InterfaceI.a1'/'InterfaceI.a2' -> 'TypeA'",
                "'Impl.a1'/'Impl.a2' -> 'TypeA.i' -> 'Impl'"
        );
    }

    @Test
    @DisplayName("Cycle through field returning interface, but not through interface field should report cycle through implementing type")
    void cycleThroughFieldReturningInterface() {
        assertErrorsContainOnce("cycleThroughFieldReturningInterface",
                "Cycle detected",
                "'Impl.a' -> 'TypeA.i' -> 'Impl'"
        );
    }

    @Test
    @DisplayName("Cycle through union should be detected")
    void cycleThroughUnion() {
        assertErrorsContain("cycleThroughUnion",
                "'TypeB.a' -> 'TypeA.u' -> 'TypeB'"
        );
    }

    @Test
    @DisplayName("Multiple cycles through union should be detected and reported separately")
    void multipleCyclesThroughUnion() {
        assertErrorsContain("multipleCyclesThroughUnion",
                "'TypeB.path1' -> 'TypeA.u' -> 'TypeB'",
                "'TypeA.u' -> 'TypeC.path2' -> 'TypeA'"
        );
    }
}