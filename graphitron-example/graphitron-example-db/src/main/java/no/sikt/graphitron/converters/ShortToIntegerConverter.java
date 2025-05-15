package no.sikt.graphitron.converters;

import org.jooq.Converter;

import java.util.Optional;

public class ShortToIntegerConverter implements Converter<Short, Integer> {

    @Override
    public Integer from(Short databaseObject) {
        return Optional.ofNullable(databaseObject).map(Short::intValue).orElse(null);
    }

    @Override
    public Short to(Integer userObject) {
        return Optional.ofNullable(userObject).map(Integer::shortValue).orElse(null);
    }

    @Override
    public Class<Short> fromType() {
        return Short.class;
    }

    @Override
    public Class<Integer> toType() {
        return Integer.class;
    }
}
