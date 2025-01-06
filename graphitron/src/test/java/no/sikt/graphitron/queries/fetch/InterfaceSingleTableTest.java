package no.sikt.graphitron.queries.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class InterfaceSingleTableTest extends InterfaceTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "/singleTable";
    }


    @Test
    @DisplayName("Default case")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Listed without pagination")
    void listed() {
        assertGeneratedContentMatches("listed");
    }

    @Test
    @DisplayName("Nested")
    void nested() {
        assertGeneratedContentMatches("nested");
    }

    @Test
    @DisplayName("Paginated")
    void paginated() {
        assertGeneratedContentMatches("paginated");
    }
}
