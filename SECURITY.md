# Security policy

## Reporting a vulnerability

**Use [GitHub's private vulnerability reporting](https://github.com/aoreshkov/rabosh/security/advisories/new).**
Do not open a public issue for a suspected vulnerability, and do not put one in a pull request
description.

Private reporting gives us a place to work on a fix, request a CVE and publish an advisory without
the details being public first. It is the only reporting channel for this project; there is no
security mailing address to write to.

What helps, roughly in order:

- The version, or the commit, and the JDK you were on.
- A document, a query or a store directory that reproduces it. Small is more useful than realistic.
- What you expected the engine to do and what it did instead.
- Whether it reproduces on a fresh store, or only on one with particular history.

You should get an acknowledgement within 72 hours. There is no bounty programme — this is a
single-maintainer project — but you will be credited in the advisory unless you would rather not be.

## Supported versions

rabosh is pre-1.0 and there is no release with long-term support. Fixes go to the latest release
line and nowhere else.

| Version | Supported |
| --- | --- |
| Latest `0.x` release | Yes |
| Anything earlier | No — upgrade |

The **on-disk format** is a separate promise and a stronger one: a store written by an earlier
release opens on every later one. Upgrading to take a security fix does not mean rewriting your
data. See [COMPATIBILITY.md](COMPATIBILITY.md).

## What is in scope

rabosh is an embedded engine. It runs inside your process, reads files your process can already
read, and opens no sockets. That shapes what a vulnerability in it looks like.

**In scope.** The engine consumes two kinds of input, and both are assumed hostile:

- **Documents.** The JSON parser and the Variant encoder/reader accept arbitrary input. A malformed,
  deeply nested, adversarially sized or otherwise hostile document must produce a thrown exception.
  It must not produce a crash, an out-of-bounds read, an unbounded allocation driven by a length
  field in the input, or silent acceptance of a value the reader will later misinterpret.
- **Store files.** Segments, the write-ahead log, the manifest and every sidecar are read through
  the FFM API from mapped memory, and every one of them carries a checksum. A corrupt or crafted
  store file must be **reported** — as corruption, or as a format this build cannot read — never
  acted upon. A length or offset read out of a file must be bounds-checked against the file before
  it is used. Reading past the end of a mapped segment, or an infinite loop driven by a field in a
  file, is a vulnerability rather than a robustness bug.

Also in scope: anything that lets a query return a document a snapshot should not be able to see,
and anything in the build or release pipeline — a workflow that can be made to run attacker-supplied
code, a way to get an artefact signed that should not have been.

**Out of scope.**

- Reading a store you do not trust *is* the threat model above, so a report needs the crafted file
  or the steps that make one — "a corrupt file caused an exception" is the engine working.
- Resource use that is proportional to input you supplied on purpose. A very large document takes
  memory; that is not a denial of service.
- `--enable-native-access` and the FFM API are a trust boundary the *host application* crosses when
  it embeds rabosh. Anything a caller can do by passing a hand-built `MemorySegment` directly to an
  internal API is out of scope; the boundary is the public API in `rabosh-api` and the file formats.
- A source or binary incompatibility is not a vulnerability. The Kotlin API is *tiered*, not frozen:
  [STABILITY.md](STABILITY.md) lists a stable core that moves only under a deprecation cycle, and
  everything else — marked `@RaboshExperimental` where the compiler can enforce it — may change in
  any release. Either way the report belongs on the issue tracker rather than here.

## Verifying what you got

Every jar published to Maven Central is signed, and every release carries a build provenance
attestation tying the artefact to the workflow run and commit that produced it:

```sh
gh attestation verify rabosh-api-<version>.jar --repo aoreshkov/rabosh
```

If that does not verify, the jar did not come from this repository's release pipeline. That is worth
reporting.

### The PGP signing key

Central requires a `.asc` beside every deployed file, so every artefact carries one. A signature is
only worth as much as the value you check it against, though, and until now this repository published
no fingerprint at all — which made the `.asc` files verifiable against *whatever key the keyserver
handed back*, which is not verification.

> **TODO — the two values below are not yet filled in.** They are held by the release maintainer and
> are not derivable from this repository. Until they are here, treat the `gh attestation verify`
> command above as the stronger check: it ties an artefact to a workflow run and a commit, and needs
> no out-of-band value to trust.

| | |
|---|---|
| Fingerprint | `TODO: 40 hex characters, no spaces` |
| Expires | `TODO: YYYY-MM-DD` |

```sh
gpg --recv-keys <fingerprint>
gpg --verify rabosh-api-<version>.jar.asc rabosh-api-<version>.jar
```

**The expiry is recorded here because it is a date that fails late and expensively.** Central's
default key validity is two years, and an expired key is not caught by anything in this repository —
it is caught by Central *rejecting the deployment*, after the tag has been pushed and, from
2026-10-01, after the release has spent its share of the monthly file budget. The tag ruleset makes
that unrecoverable: the fix ships as the next patch version. So the date belongs somewhere a person
reads before tagging, and this is that place.
