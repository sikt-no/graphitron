/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified by Sikt for internal use in the Graphitron project.
 */
package no.sikt.graphitron.javapoet;

import org.junit.Test;

import javax.lang.model.element.Modifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public class FieldSpecTest {
    @Test
    public void equalsAndHashCode() {
        FieldSpec a = FieldSpec.builder(int.class, "foo").build();
        FieldSpec b = FieldSpec.builder(int.class, "foo").build();
        assertThat(a.equals(b)).isTrue();
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a.toString()).isEqualTo(b.toString());
        a = FieldSpec.builder(int.class, "FOO", Modifier.PUBLIC, Modifier.STATIC).build();
        b = FieldSpec.builder(int.class, "FOO", Modifier.PUBLIC, Modifier.STATIC).build();
        assertThat(a.equals(b)).isTrue();
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a.toString()).isEqualTo(b.toString());
    }

    @Test
    public void nullAnnotationsAddition() {
        try {
            FieldSpec.builder(int.class, "foo").addAnnotations(null);
            fail();
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage())
                    .isEqualTo("annotationSpecs == null");
        }
    }

    @Test
    public void modifyAnnotations() {
        FieldSpec.Builder builder = FieldSpec.builder(int.class, "foo")
                .addAnnotation(Override.class)
                .addAnnotation(SuppressWarnings.class);

        builder.annotations.remove(1);
        assertThat(builder.build().annotations()).hasSize(1);
    }

    @Test
    public void modifyModifiers() {
        FieldSpec.Builder builder = FieldSpec.builder(int.class, "foo")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC);

        builder.modifiers.remove(1);
        assertThat(builder.build().modifiers()).containsExactly(Modifier.PUBLIC);
    }
}
