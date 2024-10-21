package no.fellesstudentsystem.graphitron.queries.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ReferenceErrorTest extends ReferenceTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "/error";
    }

    @Test
    @DisplayName("Reference without key from table without primary key")
    void onFieldFromTableWithoutPrimaryKey() {
        assertThatThrownBy(() -> generateFiles("noKeyFromTableWithoutPrimaryKey"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Code generation failed for ActorInfo.actor as the table ACTOR_INFO must have a primary key in order to reference another table without a foreign key.");
    }

    @Test // TODO: Better error message
    @DisplayName("Ambiguous table path")
    void tableWithMultiplePaths() {
        assertThatThrownBy(() -> generateFiles("/tableWithMultiplePaths"))
                .isInstanceOf(NoSuchElementException.class);
    }
}
