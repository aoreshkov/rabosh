## What this changes

<!-- One or two sentences. What is different after this is merged? -->

## Why

<!-- The problem, not the patch. If this is a bug fix, what did the bug do? If it is a design change,
     what did the previous design make hard or get wrong? -->

## How it was verified

<!-- Which test says this is right, and how would it fail if it were wrong? "Added a test" is less
     useful than "IndexPlannerDifferentialTest now covers a negated leaf over a mixed-type path,
     which the old normaliser answered wrongly for string values". -->

## Checklist

- [ ] `./gradlew build` passes locally, on the platform I develop on.
- [ ] `./gradlew -p build-logic check` passes, if I touched `build-logic`.
- [ ] I ran `./gradlew updateKotlinAbi` and committed the dumps, **or** this changes no public API.
- [ ] This adds no new dependency, **or** it was agreed in an issue first.
- [ ] This changes no byte written to disk, **or** [COMPATIBILITY.md](../COMPATIBILITY.md) is updated
      in the same pull request and no existing id was renumbered.
- [ ] If this touches the planner, the index sidecars or the column statistics: it is verified
      against a brute-force scan over the same data, not only against its own expectations.

<!-- Windows CI is not a formality. A mapped file cannot be deleted on Windows, so the resource-leak
     assertions only actually assert anything there — a change that holds a segment open too long
     passes on Linux and fails on Windows. If the Windows leg fails and the Linux one does not, that
     is the finding rather than flakiness. -->
