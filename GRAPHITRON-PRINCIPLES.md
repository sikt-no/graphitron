# Graphitron: Design Principles

## Context: Building for Decades

Graphitron is being developed as part of modernizing Norway’s national Student Information System. This system has been in continuous production for approximately 30 years. It has survived the rise and fall of many technologies: client-server architectures, CORBA, SOAP, REST, and countless frameworks that were once considered essential.

We expect to be in business for at least 30 more years.

This context shapes everything about how we build Graphitron. We’re not optimizing for the fastest path to production or the most fashionable architecture. We’re optimizing for a system that will still be maintainable, adaptable, and comprehensible decades from now—likely by people who haven’t been born yet.

The principles below reflect this long-term thinking.

-----

## Principle 1: The Database Is Your Ally

Relational databases have been around for 50 years. They’ve proven remarkably durable—not because the industry lacks innovation, but because they solve fundamental problems well.

A properly designed database does more than store data:

- **It enforces integrity.** Constraints, foreign keys, and transactions ensure data stays consistent even when applications have bugs.
- **It controls access.** Row-level security can enforce who sees what, independent of application code.
- **It survives application rewrites.** The database often outlives the applications built on top of it.

Many modern architectures treat the database as a dumb storage layer—something to be abstracted away or hidden behind services. We take the opposite view: **the database is a partner in keeping data safe and correct.**

Graphitron is designed to work with your database, not around it. It generates code that respects database constraints, leverages database capabilities, and assumes the database is a trustworthy component of your system.

This isn’t about a specific database product. It’s about recognizing that decades of engineering have gone into making databases reliable, and that reliability is worth using.

-----

## Principle 2: Data Modeling Is a Collaborative Act

Data modeling is not a technical specialty that developers do in isolation. It’s a conversation about what the system should be able to represent—and that conversation requires the people who understand the business.

Domain experts know things developers can’t know on their own:

- What entities matter to the business
- How those entities relate to each other
- What questions the business needs to answer
- What rules govern the data

Developers know things domain experts can’t know on their own:

- How to represent those concepts in a database
- What trade-offs different representations involve
- How the structure affects performance and maintainability

Neither group has the complete picture. Good data models emerge from collaboration.

We’ve found that involving domain experts directly in database and API schema design works well. There’s a learning curve—reading an entity-relationship diagram or a GraphQL schema takes some practice. But data modeling isn’t black magic. It’s not even very technical. It’s fundamentally about naming things and describing relationships.

Business people understand their data. They work with it daily. A finance manager knows what a transaction is, what an account is, how they relate. They can tell you the rules. What they can’t do is translate that knowledge into a schema without help—and what developers can’t do is invent that knowledge without asking.

The alternative—developers guessing at business requirements, or working from incomplete specifications—produces schemas that don’t quite fit. Those mismatches compound over time.

-----

## Principle 3: Separate Business Logic from API Code

An API is a way to expose capabilities. It is not the place to define those capabilities.

This distinction matters because:

**APIs change faster than business logic.** The rules for how student enrollment works are relatively stable. The preferred way to expose those rules to clients changes every few years. SOAP gave way to REST gave way to GraphQL. Something else will come next.

**Multiple APIs may expose the same logic.** A mobile app, a web application, a batch integration, and an administrative tool may all need access to the same underlying capabilities—but through different interfaces optimized for their needs.

**API code is hard to test in isolation.** Business rules mixed into API handlers become difficult to verify without spinning up the full API infrastructure.

Graphitron enforces this separation by design. It generates the mechanical parts of API implementation—the data fetching, the response formatting, the query optimization—but expects business logic to live elsewhere. When you need to apply business rules, you provide them through explicit extension points, not by modifying generated code.

The goal is that your business logic remains portable. If GraphQL falls out of favor in ten years, your core logic shouldn’t need to change. Only the API layer—which is generated anyway—needs replacement.

-----

## Principle 4: The API Is a Means, Not an End

It’s easy to fall into the trap of treating the API as the product. It isn’t. **Solving business needs is the product.** The API is one tool for doing that.

This has practical implications:

**Design from needs, not from data.** The question isn’t “how do we expose our database tables?” It’s “what do users need to accomplish, and what data access patterns support that?” These questions lead to different API designs.

**Expect multiple paths to the same data.** A student record might be accessed through a search interface, a direct lookup, a batch export, or a real-time notification. Each path exists because it serves a different need. The underlying data is the same; the access patterns differ.

**Optimize for the consumer.** An API exists to serve its clients. If the API is elegant but clients struggle to use it effectively, the API has failed. Pragmatic APIs that solve real problems beat theoretically pure APIs that don’t.

Graphitron supports this by making it straightforward to expose the same underlying data through multiple query patterns. You’re not locked into a single way of accessing data just because that’s how the generator works.

-----

## Principle 5: Stability Through Simplicity

Complex systems fail in complex ways. Simple systems fail in understandable ways.

For a system that needs to run for decades, understandable failures are far more valuable than sophisticated features. When something breaks at 2 AM ten years from now, the person debugging it needs to be able to comprehend what the code does and why.

This drives several choices in Graphitron:

**Generate readable code.** The output should be code that a developer can read, understand, and debug without special tools. If you need to understand what’s happening, you can read the generated source.

**Minimize runtime dependencies.** Generated code shouldn’t require a complex runtime framework. Fewer moving parts means fewer things that can break, fewer things that need updates, and fewer things that might become unmaintained.

**Prefer explicit over implicit.** When the mapping between API and database is visible in the schema annotations, future maintainers can understand the relationship. Magic that “just works” becomes mystery that “just broke” when context is lost.

**Fail at build time.** Problems discovered during code generation are far cheaper than problems discovered in production. Graphitron validates mappings before generating code, catching misconfigurations early.

-----

## Principle 6: Technology Choices Are Temporary

We’ve chosen GraphQL as our API technology. We believe it’s a good choice today. We don’t believe it’s the final choice.

Looking back 30 years, the technologies that seemed permanent turned out to be temporary. Looking forward 30 years, we should assume the same. The specific technologies we use today are implementation details, not permanent commitments.

Graphitron is built with this impermanence in mind:

**The database schema is more durable than the API schema.** Tables and columns tend to outlive the APIs built on them. Graphitron treats the database as the stable foundation.

**Business logic should be independent of API technology.** Rules about how the system behaves shouldn’t be entangled with how those behaviors are exposed.

**Generated code can be regenerated.** When technology changes, regenerating the API layer should be straightforward. The investment is in the mappings and the business logic, not in hand-written API code.

This isn’t about predicting what comes next. It’s about structuring the system so that whatever comes next can be accommodated without rewriting everything.

-----

## Principle 7: Be Deliberate About Dependencies

Every dependency is a commitment. You’re trusting that someone else’s code will continue to work, continue to be maintained, and continue to align with your needs—potentially for decades.

For a long-lived system, every dependency deserves scrutiny:

- What problem does it solve?
- Has it been maintained for years, or is it new and unproven?
- Does it follow standards, or invent its own paradigms?
- What happens if it disappears?

**Bet on longevity.** A library maintained for fifteen years is more likely to be maintained for another fifteen than something released last year. Boring is good. Boring means predictable.

**Bet on standards.** SQL outlives ORMs. HTTP outlives web frameworks. Dependencies that follow standards can be replaced by other implementations of the same standard. Dependencies that invent paradigms lock you in.

**Distinguish foundational from peripheral.** Some dependencies are utilities at the edges—these should be few and contained. Others are foundational bets that shape your architecture—these should be chosen carefully and then embraced fully. The mistake is treating them the same.

Foundational dependencies aren’t risks to minimize; they’re partnerships to invest in. Once you’ve chosen them, use them directly. Let their patterns become your patterns. The alternative—wrapping foundational dependencies in abstraction layers—produces enormous cost for little benefit.

-----

## Summary

These principles share a common theme: **respect for time.**

Respect for the past—building on proven foundations like relational databases rather than chasing novelty.

Respect for the present—solving real business needs rather than building elegant abstractions.

Respect for the future—making choices that future maintainers will be able to understand and adapt.

Graphitron is a tool for building systems that last. The technical details matter, but they matter in service of this larger goal: creating software that continues to serve its purpose long after the people who built it have moved on.