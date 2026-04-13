# Graphitron Principles

Graphitron is being built as part of modernizing Norway's national Student Information System — a system that has been running for about 30 years and that we expect to run for 30 more. That context shapes every decision here. We're not optimizing for the fastest path to production. We're optimizing for a system that will still be maintainable decades from now, probably by people who haven't been born yet.

## The database is your ally

Relational databases have been around for 50 years for good reasons. A well-designed database enforces integrity, controls access through row-level security, and outlives every application built on top of it. We've watched architectures come and go; databases tend to stay.

Graphitron is designed to work *with* the database, not around it. The generated code respects database constraints and assumes the database is a trustworthy partner in keeping data safe — not a dumb storage layer to be abstracted away.

## Data modeling is collaborative

The people who understand the business — registrars, finance managers, administrators — understand their data. They know what entities matter, how they relate, and what rules govern them. Developers know how to express that in a schema. Neither group has the complete picture.

We've found that involving domain experts directly in schema design works well. Reading an ER diagram or a GraphQL schema takes some practice, but it's not black magic. Business people understand their data; they work with it daily. Getting them into the conversation early avoids schemas that don't quite fit — and those mismatches compound over time.

## Separate business logic from API code

APIs change faster than business logic. GraphQL replaced REST, which replaced SOAP. Something else will come next. The rules for how student enrollment works are relatively stable; how you expose those rules to clients is not.

Graphitron generates the mechanical parts — data fetching, response formatting, query optimization. Business logic lives elsewhere, accessible through explicit extension points. If GraphQL falls out of favor in ten years, the generated API layer can be replaced. Your business logic shouldn't need to change.

## Stability through simplicity

Complex systems fail in complex ways. Simple systems fail in understandable ways.

Generated code should be readable without special tools. Fewer runtime dependencies mean fewer things to break, update, or watch go unmaintained. Explicit mappings in schema annotations mean future maintainers can understand the relationship between API and database without reverse-engineering magic. Problems caught at build time are far cheaper than problems caught in production.

## Technology choices are temporary

We believe GraphQL is a good choice today. We don't believe it's permanent — history suggests it isn't. The database schema is more durable than the API schema. Business logic should be independent of API technology. Generated code can be regenerated.

This isn't about predicting what comes next. It's about structuring the system so that whatever comes next can be accommodated without rewriting everything.

## Be deliberate about dependencies

Every dependency is a commitment to trust someone else's code for potentially decades. Boring is good — a library maintained for fifteen years is more likely to be maintained for another fifteen. Standards outlive frameworks: SQL outlives ORMs, HTTP outlives web frameworks. Dependencies that follow standards can be replaced; dependencies that invent paradigms lock you in.

For foundational dependencies, half-measures are worse than full commitment. Wrapping jOOQ in an abstraction layer produces enormous cost for little benefit. We chose it; we use it directly and let its patterns become ours.
