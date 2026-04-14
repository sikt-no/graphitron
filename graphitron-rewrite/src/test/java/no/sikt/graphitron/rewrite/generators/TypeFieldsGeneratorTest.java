package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.RewriteConfig;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TypeFieldsGenerator}.
 *
 * <p>{@code *Fields} classes are currently empty placeholders — the class is generated but contains
 * no methods until reference/subselect fields are introduced. All data fetching (column, query,
 * DataLoader-backed) belongs in the corresponding {@code *Fetchers} class.
 */
class TypeFieldsGeneratorTest {

    private static final TableRef FILM_TABLE = new TableRef("film", "FILM", "Film", List.of());

    @BeforeEach
    void setup() {
        RewriteConfig.setProperties(Set.of(), "", "fake.code.generated", DEFAULT_JOOQ_PACKAGE);
    }

    @AfterEach
    void teardown() {
        RewriteConfig.clear();
    }

    @Test
    void generate_classNameIsTypeNamePlusFields() {
        assertThat(TypeFieldsGenerator.generateForType("Film").name()).isEqualTo("FilmFields");
    }

    @Test
    void generate_emptyClass_hasNoMethods() {
        assertThat(TypeFieldsGenerator.generateForType("Film").methodSpecs()).isEmpty();
    }

    @Test
    void generate_isPublic() {
        assertThat(TypeFieldsGenerator.generateForType("Film").modifiers())
            .contains(javax.lang.model.element.Modifier.PUBLIC);
    }
}
