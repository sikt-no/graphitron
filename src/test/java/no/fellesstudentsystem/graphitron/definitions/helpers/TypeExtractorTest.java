package no.fellesstudentsystem.graphitron.definitions.helpers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;

import static no.fellesstudentsystem.graphitron.definitions.helpers.TypeExtractor.extractType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TypeExtractorTest {
    // Type declarations for tests
    private List<String> listOfStrings;
    private List<? extends Number> wildcardList;
    private List<List<String>> listOfLists;

    @Test
    @DisplayName("Extract type from Class object should return the Class itself")
    void classType() {
        assertThat(extractType(String.class)).isEqualTo(String.class);
    }

    @Test
    @DisplayName("Extract type from ParameterizedType (List<String>) should return the actual type argument (String)")
    void parameterizedType() throws NoSuchFieldException {
        Type type = getClass().getDeclaredField("listOfStrings").getGenericType();
        assertThat(extractType(type)).isEqualTo(String.class);
    }

    @Test
    @DisplayName("Extract type from WildcardType (List<? extends Number>) should return the upper bound (Number)")
    void wildcardType() throws NoSuchFieldException {
        Type type = getClass().getDeclaredField("wildcardList").getGenericType();
        assertThat(extractType(type)).isEqualTo(Number.class);
    }

    @Test
    @DisplayName("Extract type from nested ParameterizedType (List<List<String>>) should return the raw type (List)")
    void nestedParameterizedType() throws NoSuchFieldException {
        Type type = getClass().getDeclaredField("listOfLists").getGenericType();
        assertThat(extractType(type)).isEqualTo(List.class);
    }

    @Test
    @DisplayName("Extract type from unsupported Type should throw IllegalArgumentException")
    void invalidType() {
        Type invalidType = new Type(){};
        assertThatThrownBy(() -> extractType(invalidType))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot extract class from type");
    }
}