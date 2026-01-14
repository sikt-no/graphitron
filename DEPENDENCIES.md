# Graphitron: Philosophy on Dependencies

## Every Dependency Is a Relationship

When you add a dependency to your project, you’re entering a relationship. You’re trusting that someone else’s code will continue to work, continue to be maintained, and continue to align with your needs—potentially for decades.

Some relationships are worth it. Others become regrets.

For a system expected to run for 30+ years, every dependency deserves scrutiny. Not because dependencies are bad, but because each one is a commitment. The question isn’t “does this library solve my problem today?” It’s “will I be glad I depend on this in fifteen years?”

-----

## Principle 1: Fewer Is Better

The safest dependency is the one you don’t have.

Every dependency you add:

- Can introduce bugs you didn’t write
- Can have security vulnerabilities you need to track
- Can become unmaintained, leaving you with difficult choices
- Can change direction in ways that don’t serve your needs
- Adds complexity that future maintainers must understand

This doesn’t mean avoiding dependencies entirely—that leads to reinventing wheels poorly. It means being intentional. Each dependency should earn its place by providing substantial value that would be costly to replicate.

Before adding a dependency, ask:

- What problem does this solve?
- How much of this library will we actually use?
- What happens if this library disappears tomorrow?
- Can we solve this with what we already have?

Sometimes the answer is “yes, this dependency is clearly worth it.” Sometimes it’s “we can write the fifty lines ourselves and own them forever.”

-----

## Principle 2: Bet on Longevity

Some technologies endure. Others flame out. When choosing dependencies, favor the enduring.

**Signs of longevity:**

- Has existed for many years already
- Backed by a sustainable organization (foundation, profitable company, strong community)
- Solves a fundamental problem that won’t go away
- Has a track record of stability and backward compatibility
- Is based on open standards rather than proprietary innovation

**Warning signs:**

- Backed primarily by venture capital (incentives may not align with long-term maintenance)
- Solves a problem created by recent trends (the problem may disappear with the trend)
- Frequently introduces breaking changes
- Has a single maintainer with no succession plan
- Relies on other dependencies with warning signs

A library that’s been actively maintained for fifteen years is more likely to be maintained for another fifteen than a library released last year. This isn’t always true, but it’s a reasonable heuristic.

Boring is good. Boring means predictable. Predictable means maintainable.

-----

## Principle 3: Standards Outlive Implementations

SQL has been around for nearly fifty years. During that time, countless database abstraction libraries have come and gone. The libraries that aged best were the ones that stayed close to SQL rather than inventing their own paradigms.

This pattern repeats across technology:

- HTTP outlives web frameworks
- SQL outlives ORMs
- JSON outlives serialization libraries
- TCP/IP outlives networking abstractions

When you depend on something that closely follows a standard, you have options. If the library becomes unmaintained, another library implementing the same standard can replace it. Your knowledge transfers. Your mental models remain valid.

When you depend on something proprietary or highly opinionated, you’re locked in. The library’s abstractions become your abstractions. If it dies, those abstractions die with it, and you’re left with code shaped around concepts that no longer exist.

Prefer dependencies that embrace standards over those that hide them.

-----

## Principle 4: Know Which Dependencies to Embrace

Not all dependencies are equal. Some are peripheral—utilities that could be swapped for alternatives. Others are foundational—core technologies that shape your entire architecture.

**Peripheral dependencies** should be contained:

- Wrap their APIs so only one place changes if the dependency changes
- Keep them at the edges of your system
- Don’t let their concepts leak into your core logic

**Foundational dependencies** should be embraced fully:

- They’ve already won the cost-benefit analysis—you’ve made the bet
- Wrapping them creates an inferior abstraction at enormous cost
- Fighting them means fighting your own architecture
- Their concepts *should* permeate your system because they’re now part of how you think

The mistake is treating these the same. Wrapping a foundational dependency is expensive make-work that produces a worse version of what you already have. Not containing a peripheral dependency means pain when you eventually need to change it.

The judgment call is deciding which category a dependency falls into. Ask:

- Does this dependency shape how we think about the problem?
- Would replacing it require rethinking our architecture anyway?
- Is this a bet we’ve already made?

If yes, embrace it. Use it directly. Let its patterns become your patterns. The cost of wrapping exceeds any benefit.

If no, contain it. Keep it at arm’s length. Make it replaceable.

-----

## Principle 5: Generated Code Should Stand Alone

Graphitron generates code. That generated code will run in production for years, possibly decades. What it depends on matters.

Our principle: **generated code should be understandable and runnable with minimal runtime dependencies.**

If you read the generated code, you should be able to understand what it does without deep knowledge of Graphitron’s internals. If Graphitron itself ceased to exist tomorrow, the generated code should continue to work.

This means:

- Generated code uses standard libraries and well-established dependencies
- No proprietary runtime framework required
- A developer can debug the generated code with standard tools
- The generated code is the product, not a wrapper around hidden magic

The generator can be sophisticated. The output should be simple.

-----

## Principle 6: Understand What You Depend On

You should be able to explain every dependency in your project:

- Why it’s there
- What would happen if it disappeared
- What it would take to replace it

If you can’t answer these questions, the dependency is a risk you haven’t assessed.

This doesn’t require reading every line of source code. It means understanding:

- What problem the dependency solves for you
- Whether alternatives exist
- How deeply embedded it is in your system
- Who maintains it and what their incentives are

Dependencies you understand are tools. Dependencies you don’t understand are liabilities.

-----

## In Practice

Graphitron has made deliberate bets on two foundational dependencies:

**jOOQ** for database interaction. jOOQ has been actively maintained since 2009. It generates type-safe Java code from your database schema, stays close to SQL rather than hiding it, and is backed by a sustainable business model. jOOQ uses dual licensing—open source for open source databases, commercial licenses for commercial databases. We’ve purchased the commercial license, which includes rights to the source code. If jOOQ’s maintainers disappeared tomorrow, we could continue. We embrace jOOQ fully—its types and patterns permeate our generated code. We’re not wrapping it; we’re building on it.

**GraphQL-Java** for API implementation. GraphQL-Java is the reference implementation for GraphQL in the Java ecosystem, and it’s fully open source. It implements an open specification governed by the GraphQL Foundation, not a single company. Multiple implementations of GraphQL exist across languages, so we’re betting on the standard as much as the library. We use GraphQL-Java directly without abstraction layers.

These aren’t hidden implementation details. They’re explicit, deliberate choices that shape how Graphitron works.

Peripheral dependencies—utilities for parsing, testing, building—we treat differently. These we contain, keeping them at the edges where they can be replaced without architectural impact.

The distinction matters. Trying to wrap jOOQ or GraphQL-Java would be enormously expensive and would produce worse abstractions than we started with. Not containing our peripheral dependencies would create unnecessary coupling to things that don’t merit it.

-----

## Summary

Dependencies are relationships, and relationships require judgment.

We aim for:

- **Fewer dependencies**, each earning its place
- **Boring dependencies**, with track records of stability
- **Standard-aligned dependencies**, that follow specifications rather than invent paradigms
- **Foundational dependencies embraced**, peripheral dependencies contained
- **Understood dependencies**, that we can explain and evaluate

The goal isn’t purity—it’s sustainability. We want a system that can be maintained for decades, and that means being thoughtful about what we tie ourselves to.

Every dependency we add today is a decision that future maintainers will live with. We try to make decisions they’ll thank us for.