# Mutation Patterns

This document explores how to structure complex mutations in Graphitron — specifically the code
that lives behind `@service` when generated mutations are not enough.

The pattern described here is **Functional Core, Imperative Shell (FCIS)**. It provides structure
for organizing business logic, keeping it testable, and avoiding overfetching — without adding
framework machinery.

---

## The Two Paths for Mutations

**Generated mutations (`@mutation` directive):**
Work well for straightforward CRUD. Graphitron generates type-safe INSERT, UPDATE, DELETE, and
UPSERT code directly from the schema. No hand-written code required.

**Custom mutations (`@service` directive):**
Required for complex business logic. The directive points to a hand-written Java method.
Graphitron generates the wiring; you write the implementation.

The `@service` escape hatch provides flexibility but no structure. Without a pattern,
implementations tend to mix fetching, validation, business rules, and side effects in one place —
difficult to test and difficult to reason about.

FCIS provides that structure.

---

## Functional Core, Imperative Shell

FCIS divides code into two layers:

**Functional core:**
Pure functions. Takes data as input, returns decisions or commands as output. No side effects —
no database queries, no external calls, no mutations. Highly testable without mocks or a running
database.

**Imperative shell:**
All side effects. Fetches data, passes it to the functional core, executes the decisions the core
returns. Thin by design — orchestration only, no business logic.

The flow is always:

```
Imperative: fetch minimal data
     ↓
Functional: decide what else is needed, build queries
     ↓
Imperative: execute those queries
     ↓
Functional: make the final decision
     ↓
Imperative: execute that decision (store, insert, call services)
```

---

## jOOQ Records Are Just Data

Graphitron uses jOOQ everywhere — no repository pattern, no abstraction layer over the database.
All code can access all tables directly. Does this conflict with having a pure functional core?

No. The distinction is not about *which types* you use — it's about *whether you execute queries*.

A `CustomerRecord` retrieved from the database is just a Java object. The functional core can
accept it, read it, copy it, and set fields on it. None of that is a side effect. The side effect
is the query that fetched it, and the query that stores it — both of which stay in the shell.

```java
// IMPERATIVE — side effect
CustomerRecord customer = dsl.selectFrom(CUSTOMER)
    .where(CUSTOMER.CUSTOMER_ID.eq(id))
    .fetchOne();

// FUNCTIONAL — pure, even though it uses a jOOQ type
public static Decision validate(CustomerRecord customer, String newEmail) {
    if (customer.getActive() == 0) {
        return new Decision.Denied("Inactive customer");
    }
    if (!newEmail.contains("@")) {
        return new Decision.Denied("Invalid email address");
    }
    CustomerRecord updated = customer.copy();
    updated.setEmail(newEmail);
    return new Decision.Allowed(updated);
}

// IMPERATIVE — side effect
customer.store();
```

The functional core uses jOOQ types freely. It just does not call `.fetch()`, `.execute()`,
or `.store()`.

---

## Building Queries Is Pure

jOOQ query objects are data structures. Constructing one has no side effects — it does not touch
the database. Only calling `.fetch()` or `.execute()` does.

```java
// PURE — builds a query object, nothing more
SelectQuery<PaymentRecord> query = dsl.selectFrom(PAYMENT)
    .where(PAYMENT.CUSTOMER_ID.eq(customerId))
    .orderBy(PAYMENT.PAYMENT_DATE.desc())
    .limit(1);

// SIDE EFFECT — executes it
query.fetch();
```

This is the key insight for avoiding overfetching: the functional core can construct the queries
it needs and return them to the shell. The shell executes them. The functional core never has to
know whether a query was expensive — it just expresses what data it needs.

---

## Overfetching

A naive implementation fetches everything upfront:

```java
// Fetches everything regardless of what the business logic needs
CustomerRecord customer   = fetchCustomer(input.customerId());
PaymentRecord lastPayment = fetchLastPayment(input.customerId());  // needed?
List<RentalRecord> rentals = fetchActiveRentals(input.customerId()); // needed?
```

With FCIS, the functional core builds the queries it conditionally needs and returns them. The
shell executes only those:

```java
// FUNCTIONAL CORE
public record MutationPlan(
    List<DataQuery> dataQueries,
    List<UpdateQuery<?>> updates,
    List<ValidationError> errors
) {}

public sealed interface DataQuery {
    record ForPayment(SelectQuery<PaymentRecord> query) implements DataQuery {}
    record ForRentals(SelectQuery<RentalRecord> query)  implements DataQuery {}
}

public static MutationPlan plan(DSLContext dsl, CustomerRecord customer, UpdateInput input) {
    if (customer.getActive() == 0) {
        return new MutationPlan(List.of(), List.of(),
            List.of(new ValidationError("Inactive customer")));
    }

    // Premium customers need a payment check — build that query conditionally
    if (customer.getStoreId() == PREMIUM_STORE_ID) {
        var paymentQuery = dsl.selectFrom(PAYMENT)
            .where(PAYMENT.CUSTOMER_ID.eq(customer.getCustomerId()))
            .orderBy(PAYMENT.PAYMENT_DATE.desc())
            .limit(1);
        return new MutationPlan(List.of(new ForPayment(paymentQuery)), List.of(), List.of());
    }

    // Simple case — no extra data needed, build the update directly
    var update = dsl.update(CUSTOMER)
        .set(CUSTOMER.EMAIL, input.email())
        .set(CUSTOMER.LAST_UPDATE, LocalDateTime.now())
        .where(CUSTOMER.CUSTOMER_ID.eq(customer.getCustomerId()));
    return new MutationPlan(List.of(), List.of(update), List.of());
}

public static MutationPlan finalize(
    DSLContext dsl, CustomerRecord customer, PaymentRecord payment, UpdateInput input
) {
    if (payment.getAmount().compareTo(MIN_PREMIUM) < 0) {
        return new MutationPlan(List.of(), List.of(),
            List.of(new ValidationError("Insufficient payment history")));
    }
    var update = dsl.update(CUSTOMER)
        .set(CUSTOMER.EMAIL, input.email())
        .set(CUSTOMER.LAST_UPDATE, LocalDateTime.now())
        .where(CUSTOMER.CUSTOMER_ID.eq(customer.getCustomerId()));
    return new MutationPlan(List.of(), List.of(update), List.of());
}
```

```java
// IMPERATIVE SHELL
@Transactional
public Result updateEmail(UpdateInput input) {
    var customer = dsl.selectFrom(CUSTOMER)
        .where(CUSTOMER.CUSTOMER_ID.eq(input.customerId()))
        .fetchOne();

    var plan = BusinessLogic.plan(dsl, customer, input);

    if (!plan.errors().isEmpty()) return Result.failure(plan.errors());

    // Execute additional queries only if the plan asked for them
    PaymentRecord payment = null;
    for (var q : plan.dataQueries()) {
        switch (q) {
            case ForPayment fq -> payment = fq.query().fetchOne();  // side effect
            case ForRentals rq -> rentals = rq.query().fetch();     // side effect
        }
    }

    // Finalize if we fetched additional data
    if (payment != null) {
        plan = BusinessLogic.finalize(dsl, customer, payment, input);
        if (!plan.errors().isEmpty()) return Result.failure(plan.errors());
    }

    plan.updates().forEach(UpdateQuery::execute);  // side effect

    return Result.success();
}
```

---

## When to Use FCIS

Not every `@service` mutation needs this pattern.

**Use FCIS when:**
- Business logic is complex enough that you want to test it without a database
- Data needs are conditional — what you fetch depends on business rules
- The mutation affects multiple tables and the orchestration is non-trivial
- The same business rules are invoked from multiple places

**Skip FCIS when:**
- The mutation is simple enough to read at a glance
- The data requirements are unconditional — you always need the same records
- Adding the plan/finalize split would be longer than just writing the mutation directly

*Illustrative comparison (not in the codebase):*

```java
// Simple enough that FCIS adds no value — just write it directly
@Transactional
public Result deactivateCustomer(int customerId) {
    dsl.update(CUSTOMER)
        .set(CUSTOMER.ACTIVE, (byte) 0)
        .set(CUSTOMER.LAST_UPDATE, LocalDateTime.now())
        .where(CUSTOMER.CUSTOMER_ID.eq(customerId))
        .execute();
    return Result.success();
}
```

---

## Relationship to Graphitron's Principles

**Principle 3 — Separate business logic from API code.**
FCIS is the pattern for what goes on the other side of that separation. Graphitron generates the
API layer. FCIS structures the business logic layer. They are independent.

**Principle 5 — Stability through simplicity.**
The pattern works because it has no moving parts. There is no framework machinery — just plain
Java records, pure functions, and jOOQ query objects. Someone reading this code in ten years will
find it straightforward.

**The 30-year view.**
GraphQL may be replaced. If it is, Graphitron regenerates the API layer. Business logic written
with FCIS is not entangled with the API layer and does not change.

---

## FCIS Is Independent of Graphitron

FCIS is a pattern for organizing `@service` implementations. Graphitron has no opinion about it —
the framework does not know or care whether you use this pattern, a different one, or none at all.

This is intentional. Graphitron's job is to generate the API mechanics. How you structure
business logic is your decision.

Use FCIS if it fits. Ignore it if it doesn't. The `@service` directive works either way.

---

**See also:**
- [Graphitron Principles](graphitron-principles.md) — Design philosophy and long-term thinking
- [Runtime Extension Points](runtime-extension-points.md) — Transaction management via `getDslContext()`
- [Java Codegen README](../graphitron-codegen-parent/graphitron-java-codegen/README.md) — `@service` and `@mutation` directive reference
