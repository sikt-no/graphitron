/*
 * Copyright (C) 2014 Google, Inc.
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

import com.google.testing.compile.CompilationRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@RunWith(JUnit4.class)
public final class ClassNameTest {
    @Rule
    public CompilationRule compilationRule = new CompilationRule();

    @Test
    public void bestGuessForString_simpleClass() {
        assertThat(ClassName.bestGuess(String.class.getName()))
                .isEqualTo(ClassName.get("java.lang", "String"));
    }

    @Test
    public void bestGuessNonAscii() {
        ClassName className = ClassName.bestGuess(
                "com.\ud835\udc1andro\ud835\udc22d.\ud835\udc00ctiv\ud835\udc22ty");
        assertEquals("com.\ud835\udc1andro\ud835\udc22d", className.packageName());
        assertEquals("\ud835\udc00ctiv\ud835\udc22ty", className.simpleName());
    }

    static class OuterClass {
        static class InnerClass {}
    }

    @Test
    public void bestGuessForString_nestedClass() {
        assertThat(ClassName.bestGuess(Map.Entry.class.getCanonicalName()))
                .isEqualTo(ClassName.get("java.util", "Map", "Entry"));
        assertThat(ClassName.bestGuess(OuterClass.InnerClass.class.getCanonicalName()))
                .isEqualTo(ClassName.get("no.sikt.graphitron.javapoet",
                        "ClassNameTest", "OuterClass", "InnerClass"));
    }

    @Test
    public void bestGuessForString_defaultPackage() {
        assertThat(ClassName.bestGuess("SomeClass"))
                .isEqualTo(ClassName.get("", "SomeClass"));
        assertThat(ClassName.bestGuess("SomeClass.Nested"))
                .isEqualTo(ClassName.get("", "SomeClass", "Nested"));
        assertThat(ClassName.bestGuess("SomeClass.Nested.EvenMore"))
                .isEqualTo(ClassName.get("", "SomeClass", "Nested", "EvenMore"));
    }

    @Test
    public void bestGuessForString_confusingInput() {
        assertBestGuessThrows("");
        assertBestGuessThrows(".");
        assertBestGuessThrows(".Map");
        assertBestGuessThrows("java");
        assertBestGuessThrows("java.util");
        assertBestGuessThrows("java.util.");
        assertBestGuessThrows("java..util.Map.Entry");
        assertBestGuessThrows("java.util..Map.Entry");
        assertBestGuessThrows("java.util.Map..Entry");
        assertBestGuessThrows("com.test.$");
        assertBestGuessThrows("com.test.LooksLikeAClass.pkg");
        assertBestGuessThrows("!@#$gibberish%^&*");
    }

    private void assertBestGuessThrows(String s) {
        try {
            ClassName.bestGuess(s);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void createNestedClass() {
        ClassName foo = ClassName.get("com.example", "Foo");
        ClassName bar = foo.nestedClass("Bar");
        assertThat(bar).isEqualTo(ClassName.get("com.example", "Foo", "Bar"));
        ClassName baz = bar.nestedClass("Baz");
        assertThat(baz).isEqualTo(ClassName.get("com.example", "Foo", "Bar", "Baz"));
    }

    static class $Outer {
        static class $Inner {}
    }

    @Test
    public void classNameFromTypeElement() {
        Elements elements = compilationRule.getElements();
        TypeElement object = elements.getTypeElement(Object.class.getCanonicalName());
        assertThat(ClassName.get(object).toString()).isEqualTo("java.lang.Object");
        TypeElement outer = elements.getTypeElement($Outer.class.getCanonicalName());
        assertThat(ClassName.get(outer).toString()).isEqualTo("no.sikt.graphitron.javapoet.ClassNameTest.$Outer");
        TypeElement inner = elements.getTypeElement($Outer.$Inner.class.getCanonicalName());
        assertThat(ClassName.get(inner).toString()).isEqualTo(
                "no.sikt.graphitron.javapoet.ClassNameTest.$Outer.$Inner");
    }

    /**
     * Buck builds with "source-based ABI generation" and those builds don't support
     * {@link TypeElement#getKind()}. Test to confirm that we don't use that API.
     */
    @Test
    public void classNameFromTypeElementDoesntUseGetKind() {
        Elements elements = compilationRule.getElements();

        // Create a simple proxy implementation instead of using Mockito.spy
        TypeElement object = createTypeElementProxy(
                elements.getTypeElement(Object.class.getCanonicalName()));
        assertThat(ClassName.get(object).toString()).isEqualTo("java.lang.Object");

        TypeElement outer = createTypeElementProxy(
                elements.getTypeElement($Outer.class.getCanonicalName()));
        assertThat(ClassName.get(outer).toString())
                .isEqualTo("no.sikt.graphitron.javapoet.ClassNameTest.$Outer");

        TypeElement inner = createTypeElementProxy(
                elements.getTypeElement($Outer.$Inner.class.getCanonicalName()));
        assertThat(ClassName.get(inner).toString())
                .isEqualTo("no.sikt.graphitron.javapoet.ClassNameTest.$Outer.$Inner");
    }

    /**
     * Creates a proxy for TypeElement that throws on getKind() without using Mockito.spy
     */
    private TypeElement createTypeElementProxy(TypeElement original) {
        return (TypeElement) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{TypeElement.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getKind")) {
                        throw new AssertionError("getKind() should not be called");
                    }

                    if (method.getName().equals("getEnclosingElement")) {
                        Object result = method.invoke(original, args);
                        if (result instanceof TypeElement) {
                            return createTypeElementProxy((TypeElement) result);
                        }
                        return result;
                    }

                    return method.invoke(original, args);
                });
    }

    @Test
    public void classNameFromClass() {
        assertThat(ClassName.get(Object.class).toString())
                .isEqualTo("java.lang.Object");
        assertThat(ClassName.get(OuterClass.InnerClass.class).toString())
                .isEqualTo("no.sikt.graphitron.javapoet.ClassNameTest.OuterClass.InnerClass");
        assertThat((ClassName.get(new Object() {}.getClass())).toString())
                .isEqualTo("no.sikt.graphitron.javapoet.ClassNameTest$1");
        assertThat((ClassName.get(new Object() {
            Object inner = new Object() {};
        }.inner.getClass())).toString())
                .isEqualTo("no.sikt.graphitron.javapoet.ClassNameTest$2$1");
        assertThat((ClassName.get($Outer.class)).toString())
                .isEqualTo("no.sikt.graphitron.javapoet.ClassNameTest.$Outer");
        assertThat((ClassName.get($Outer.$Inner.class)).toString())
                .isEqualTo("no.sikt.graphitron.javapoet.ClassNameTest.$Outer.$Inner");
    }

    @Test
    public void peerClass() {
        assertThat(ClassName.get(Double.class).peerClass("Short"))
                .isEqualTo(ClassName.get(Short.class));
        assertThat(ClassName.get("", "Double").peerClass("Short"))
                .isEqualTo(ClassName.get("", "Short"));
        assertThat(ClassName.get("a.b", "Combo", "Taco").peerClass("Burrito"))
                .isEqualTo(ClassName.get("a.b", "Combo", "Burrito"));
    }

    @Test
    public void fromClassRejectionTypes() {
        try {
            ClassName.get(int.class);
            fail();
        } catch (IllegalArgumentException ignored) {
        }
        try {
            ClassName.get(void.class);
            fail();
        } catch (IllegalArgumentException ignored) {
        }
        try {
            ClassName.get(Object[].class);
            fail();
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Test
    public void reflectionName() {
        assertEquals("java.lang.Object", TypeName.OBJECT.reflectionName());
        assertEquals("java.lang.Thread$State", ClassName.get(Thread.State.class).reflectionName());
        assertEquals("java.util.Map$Entry", ClassName.get(Map.Entry.class).reflectionName());
        assertEquals("Foo", ClassName.get("", "Foo").reflectionName());
        assertEquals("Foo$Bar$Baz", ClassName.get("", "Foo", "Bar", "Baz").reflectionName());
        assertEquals("a.b.c.Foo$Bar$Baz", ClassName.get("a.b.c", "Foo", "Bar", "Baz").reflectionName());
    }

    @Test
    public void canonicalName() {
        assertEquals("java.lang.Object", TypeName.OBJECT.canonicalName());
        assertEquals("java.lang.Thread.State", ClassName.get(Thread.State.class).canonicalName());
        assertEquals("java.util.Map.Entry", ClassName.get(Map.Entry.class).canonicalName());
        assertEquals("Foo", ClassName.get("", "Foo").canonicalName());
        assertEquals("Foo.Bar.Baz", ClassName.get("", "Foo", "Bar", "Baz").canonicalName());
        assertEquals("a.b.c.Foo.Bar.Baz", ClassName.get("a.b.c", "Foo", "Bar", "Baz").canonicalName());
    }
}
