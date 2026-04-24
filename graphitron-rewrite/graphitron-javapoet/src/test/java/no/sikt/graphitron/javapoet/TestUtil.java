package no.sikt.graphitron.javapoet;

import javax.lang.model.element.Element;
import java.util.Collection;

final class TestUtil {
    static <E extends Element> E findFirst(Collection<E> elements, String name) {
        for (E element : elements) {
            if (element.getSimpleName().toString().equals(name)) {
                return element;
            }
        }
        throw new IllegalArgumentException(name + " not found in " + elements);
    }
}
