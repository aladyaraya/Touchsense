# Agent memory package

This folder is the portable, durable handoff package for TouchScene agents.

- [`MEMORY.md`](MEMORY.md): canonical human-readable product and engineering
  memory.
- [`state.json`](state.json): compact machine-readable state for orchestration,
  indexing, or quick inspection.
- [`INSTA360_ACE_SDK.md`](INSTA360_ACE_SDK.md): verified Ace Android SDK
  capabilities and the recommended integration boundary.
- [`REQUIREMENTS_AND_TECH_ROUTE.md`](REQUIREMENTS_AND_TECH_ROUTE.md): current
  functional requirements, interaction states, image pipeline, haptic grammar,
  speech route, architecture, and 48-hour build order.
- [`AGENTS.md`](AGENTS.md): mandatory operating instructions and read order.
- Repository root [`../AGENTS.md`](../AGENTS.md): discovery pointer for agents
  that automatically load root instructions.

The source code and test suite remain the authority for implementation details.
When memory and code disagree, inspect the code and tests, then update both
memory files with the corrected evidence state.

## Update contract

After a material change:

1. Update the date and relevant section in `MEMORY.md`.
2. Add a dated item to its decision log when a product or architecture choice
   changes.
3. Update matching fields in `state.json`.
4. Record whether evidence is `verified`, `simulated`, `compiled_only`, or
   `unverified`.
5. Never convert an assumption into a verified fact without an artifact or test
   result.
