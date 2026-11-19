package no.sikt.graphitron.rewrite.test.services;

/**
 * A session handle whose members differ only in boxing: {@code brukerId} a primitive {@code long},
 * {@code sokerId} a boxed {@code Long}. What {@link SessionMemberService}'s parameters take under
 * {@code $session.<member>}, so a binding is judged on the box alone. Never constructed.
 */
public record SessionMemberHandle(long brukerId, Long sokerId) {}
