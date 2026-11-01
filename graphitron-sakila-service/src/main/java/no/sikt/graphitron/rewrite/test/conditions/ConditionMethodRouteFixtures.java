package no.sikt.graphitron.rewrite.test.conditions;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.Actor;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.Address;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.City;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.Customer;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.FilmActor;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.Staff;
import org.jooq.Condition;
import org.jooq.Table;
import org.jooq.TableLike;
import org.jooq.impl.DSL;

/**
 * Condition methods whose signatures are the cases of how a condition routes a path hop: the fact
 * documents {@code condition-method-route} and {@code condition-method-route-departures} name them
 * in condition-only path elements, and the store reads each one's parameters to say which table it
 * departs from and which it arrives at.
 *
 * <p>The bodies are not the point and none of them is called; each name is one case, so an
 * overload set is one name and a name stands for nothing outside its case. Parameter 0 is the
 * departure and parameter 1 the arrival, the order the emitter calls a condition in.
 */
public final class ConditionMethodRouteFixtures {

    private ConditionMethodRouteFixtures() {}

    /** Both parameters concrete: routes customer to address. */
    public static Condition customerToAddress(Customer customer, Address address) {
        return DSL.noCondition();
    }

    /** A wildcard arrival, which no generated table is: routes nothing. */
    public static Condition customerToAnyTable(Customer customer, Table<?> target) {
        return DSL.noCondition();
    }

    /** A wildcard departure: departs from every table in the graph's sources. */
    public static Condition anythingToAddress(Table<?> source, Address address) {
        return DSL.noCondition();
    }

    /** A primitive departure names no class, which reads as the wildcard does. */
    public static Condition intToAddress(int source, Address address) {
        return DSL.noCondition();
    }

    /** A primitive arrival names no class, which reads as the wildcard does: routes nothing. */
    public static Condition customerToInt(Customer customer, int target) {
        return DSL.noCondition();
    }

    /** An arrival naming a class no table is generated as: routes nothing. */
    public static Condition customerToRange(Customer customer, FilmLengthRange target) {
        return DSL.noCondition();
    }

    /** Two declarations agreeing on both slots: one route. */
    public static Condition agreeingOnBoth(Customer customer, Address address) {
        return DSL.noCondition();
    }

    /** The second declaration of {@link #agreeingOnBoth(Customer, Address)}, a third parameter beside. */
    public static Condition agreeingOnBoth(Customer customer, Address address, String extra) {
        return DSL.noCondition();
    }

    /** Two declarations arriving at one table from two: one route per departure. */
    public static Condition agreeingOnArrival(Customer customer, Address address) {
        return DSL.noCondition();
    }

    /** The second declaration of {@link #agreeingOnArrival(Customer, Address)}. */
    public static Condition agreeingOnArrival(FilmActor filmActor, Address address) {
        return DSL.noCondition();
    }

    /** Two declarations arriving at two tables: routes nothing. */
    public static Condition divergingArrivals(Customer customer, Address address) {
        return DSL.noCondition();
    }

    /** The second declaration of {@link #divergingArrivals(Customer, Address)}. */
    public static Condition divergingArrivals(FilmActor filmActor, Actor actor) {
        return DSL.noCondition();
    }

    /** A wildcard declaration beside a concrete one: routes nothing. */
    public static Condition wildcardBesideConcrete(Customer customer, Table<?> target) {
        return DSL.noCondition();
    }

    /** The concrete declaration of {@link #wildcardBesideConcrete(Customer, Table)}. */
    public static Condition wildcardBesideConcrete(Customer customer, Address address) {
        return DSL.noCondition();
    }

    /** A declaration arriving at a class no table is, beside a concrete one: routes nothing. */
    public static Condition nonTableBesideConcrete(Customer customer, FilmLengthRange target) {
        return DSL.noCondition();
    }

    /** The concrete declaration of {@link #nonTableBesideConcrete(Customer, FilmLengthRange)}. */
    public static Condition nonTableBesideConcrete(Customer customer, Address address) {
        return DSL.noCondition();
    }

    /** A one-parameter declaration declares no arrival, beside one that does: still routes. */
    public static Condition oneParameterBesideTwo(Customer customer) {
        return DSL.noCondition();
    }

    /** The two-parameter declaration of {@link #oneParameterBesideTwo(Customer)}. */
    public static Condition oneParameterBesideTwo(Customer customer, Address address) {
        return DSL.noCondition();
    }

    /** Not a condition, its return type being no {@link Condition}: routes nothing. */
    public static Object notACondition(Customer customer, Address address) {
        return null;
    }

    /**
     * Two declarations each covering one end of staff to address and neither the pair: a step from
     * staff to address finds staff at parameter 0 of one and address at parameter 1 of the other, and
     * no declaration javac could call with both.
     */
    public static Condition pairOnly(Customer customer, Address address) {
        return DSL.noCondition();
    }

    /** The second declaration of {@link #pairOnly(Customer, Address)}. */
    public static Condition pairOnly(Staff staff, City city) {
        return DSL.noCondition();
    }

    /**
     * Two declarations both taking customer to address, each exact at one end and a wildcard at the
     * other: neither is more specific than the other, so javac refuses the call as ambiguous.
     */
    public static Condition ambiguousPair(Customer customer, Table<?> target) {
        return DSL.noCondition();
    }

    /** The second declaration of {@link #ambiguousPair(Customer, Table)}. */
    public static Condition ambiguousPair(Table<?> source, Address address) {
        return DSL.noCondition();
    }

    /** A {@code TableLike<?>} arrival, which takes any table as {@code Table<?>} does. */
    public static Condition tableLikeArrival(Customer customer, TableLike<?> target) {
        return DSL.noCondition();
    }

    /** An {@code Object} arrival, which accepts a table but names no table type. */
    public static Condition objectArrival(Customer customer, Object target) {
        return DSL.noCondition();
    }

    /** Concrete, and named beside a key, where the key is the route: asked nothing. */
    public static Condition besideAKey(Customer customer, Address address) {
        return DSL.noCondition();
    }
}
