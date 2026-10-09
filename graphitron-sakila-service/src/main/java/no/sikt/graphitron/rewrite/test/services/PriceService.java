package no.sikt.graphitron.rewrite.test.services;

import org.jooq.Field;
import org.jooq.impl.DSL;

import java.math.BigDecimal;

/**
 * Methods an editor jumps to and finds references of by name and arity: {@link #price} and
 * {@link #shifted} take nothing, and {@link #pick} is overloaded at zero and two arguments, so a
 * jump has to tell the two apart by the arity a reference resolves at.
 */
public final class PriceService {

    private PriceService() {}

    public static Field<BigDecimal> price() {
        return DSL.inline(BigDecimal.ZERO);
    }

    public static Object shifted() {
        return null;
    }

    public static Object pick() {
        return null;
    }

    public static Object pick(Object a, Object b) {
        return a;
    }
}
