# designguard

A library of deterministic design checks for AI-written Java code. See README.md.

## Rules for working here

- Run `mise exec -- mvn verify` before reporting any change as done. It runs the enforcer, compiler lint, tests,
  ArchUnit, PMD and CPD. Done means it exits 0.
- Write the failing test first, then the code.
- When PMD, ArchUnit or the compiler flags something, fix the code. Don't add `@SuppressWarnings`, `NOPMD`,
  exclusions or relaxed thresholds. Changing `config/pmd/ruleset.xml` or `ArchitectureTest` needs the user's approval.
- designguard runs on Java 17+: the build uses Java 25 with `--release 17`, so javac rejects newer APIs. Test
  fixtures compile for 17 by default (`CompiledProject.release(n)` for another). Tests of rules that read classes are
  `@ParameterizedClass` over `CompiledProject#releases`, so they run on each version's class files; tests of newer
  syntax or APIs use `@EnabledForJreRange`. To run the tests on another JVM:
  `mise exec -- mvn verify -Djvm=$(mise where java@temurin-17)/bin/java`. CI runs them on 17, 21, 25 and 27.
- Layers: `model` (immutable data), `service` (checks), `view` (output formats), `controller` (public entry point).
  `ArchitectureTest` enforces who may depend on whom.
- Every rule belongs in `RuleFiltersContractTest`, with code in `LegacyShop` that breaks it, so it honours `that()`,
  `excluding()` and `frozen()`. A rule that reports fields, methods, constructors or calls also belongs in
  `MemberIgnoringContractTest`, which checks that `ignoring()` skips an annotated member.
- `DesignGuardSelfTest` runs designguard's own rules on this repo. A new rule that passes here belongs in it. A rule
  change that breaks it is either a false positive to fix in the rule (with a regression test) or a real finding to
  fix in the code.
