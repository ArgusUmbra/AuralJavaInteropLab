# Assignment 008D-3F-7G engineering audit

## Baseline

- Baseline branch: `main`.
- Baseline commit: `1ba8be43c1d1af0827786d05452422280eab5162` (`Add independent Java AURAL Core package verification`).
- Baseline worktree: clean.
- Baseline tests: 401 passed, 0 failed.
- Runtime/compiler: OpenJDK and `javac` 21.0.6.
- Frozen input: all 43 entries in `frozen-input/SHA256SUMS.txt` passed SHA-256 verification.
- Frozen source repository: `C:\CodexLab\AuralOpenStandard\aural-open-standard`, branch `main`, commit `b2b60b38989ddca9f692a6f1b7b919c5850ec11f`, clean before and after evaluation.
- Retained large-file check: no workspace file at or above 10 MiB.

## Published contract applied

The authority was the frozen `CONFORMANCE-ADAPTER-PROTOCOL-1-EXPERIMENTAL.md`, supported
by the frozen conformance-kit documentation and public Core Reader manifest. Protocol 1
starts a fresh child for each case, sends one UTF-8 JSON request on stdin, closes stdin,
and expects one UTF-8 JSON response on stdout followed by exit zero. The request limit
is 16,384 bytes; the response limit is 65,536 bytes. Only `verify-package` with role
`package` is supported. Unknown identifiers, versions, operations, roles, malformed
requests, unusable paths, and unexpected failures are nonzero process errors with no
stdout response. Controlled invalid-package results are `reject` responses with exit
zero. The runner owns expected results, comparison, timeout enforcement, and scoring.

No compatibility-critical ambiguity affected the official run. The protocol does not
require the adapter to recompute request `size` or `sha256`; it states that the runner
verifies them before launch and that the adapter passes `input.path` to its verifier.
Accordingly, Java validates their envelope types and syntax but does not use them, the
case ID, or the filename to decide acceptance.

## Implementation and separation audit

- Entry point: `org.auralinterop.adapter.AuralConformanceAdapter`.
- Launcher: `java/aural-adapter.cmd`, with classpath resolved from `%~dp0`.
- Verification authority: existing `org.auralinterop.verify.AuralPackageVerifier` only.
- Production adapter source contains no public case inventory, fixture hashes, expected
  outcomes, known-invalid names, or conformance scoring logic.
- Controlled invalid-package `AuralVerificationException` values map to `reject`;
  incomplete I/O, path-access, protocol, and unexpected execution failures cannot
  masquerade as package rejection.
- Stdout contains only the completed protocol response. Diagnostics for process errors
  go to stderr. Compilation is a separate prerequisite and never occurs in the launcher.
- The original AURAL Python and Node verifiers and their internal tests were not
  inspected. Only published documentation, the public case manifest, runner `--help`,
  and the runner's documented CLI were used.

## Verification results

- Java suite after implementation: 421 passed, 0 failed, including 20 adapter-specific
  process tests.
- Launcher integration covers an absolute archive path containing spaces and Unicode,
  with a working directory outside the Java project.
- Official runner: unchanged `tools/run_aural_conformance.py` from source commit
  `b2b60b38989ddca9f692a6f1b7b919c5850ec11f`.
- Public manifest SHA-256:
  `eea283420315768e4bce2d5824408d671d34c08db06482da845d75dc3118e204`.
- Required result: `PASS`; 30/30 passed, 0 failed, 0 execution errors, 0 timeouts,
  0 skips.
- Optional Draft 0.1 cases were not selected and do not affect the Core Reader result.
- Machine-readable evidence: `reports/java-core-reader-0.2-report.json`.
- Report SHA-256:
  `afad66d801784df398b12824ae4435c3c1394db78b2d90ae69e9d1c6cc04623d`.

This is external conformance test evidence for the experimental Draft 0.2 Core Reader,
not official certification, security certification, or proof outside the published
case inventory.
