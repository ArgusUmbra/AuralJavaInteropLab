# AURAL Java clean-room verifier experiment

This is an independent, standard-library-only Java 21 interoperability experiment.
It is not an official AURAL reference implementation or a certification. It now
implements the complete Draft 0.2 Core Reader verification scope and the experimental
Adapter Protocol 1 process boundary. Signatures, provenance, attestations, AUFX
transport, and evidence interpretation are outside this milestone.

`org.auralinterop.manifest.ManifestValidator` is the Draft 0.2 manifest API. Its
`validate(byte[])` method returns an immutable, parser-independent `Manifest`, or
throws `ManifestValidationException` with a stable failure stage. Validation performs
strict UTF-8/I-JSON-profile parsing, schema and semantic checks, JCS canonicalization,
and exact canonical-byte comparison in that order.

## Complete Core Reader verifier

`org.auralinterop.verify.AuralPackageVerifier.verify(Path)` returns a
`VerifiedAuralPackage` only after every applicable Draft 0.2 structural, manifest, and
payload-integrity check succeeds. It otherwise throws `AuralVerificationException`.
The exception exposes a stable `VerificationErrorCategory`, a more specific detail
string, and the underlying ZIP, manifest, or I/O cause where one exists.

```java
AuralPackageVerifier verifier = new AuralPackageVerifier();
VerifiedAuralPackage verified = verifier.verify(Path.of("recording.aural"));

System.out.println(verified.version());
System.out.println(verified.originalByteCount());
System.out.println(verified.computedOriginalSha256());
```

The success result includes the confirmed version, immutable validated manifest,
exact original entry name, streamed original byte count, computed and declared
SHA-256 strings, successful declared/computed CRC32 pairs for both entries, ZIP64 use,
and `COMPLETE` status. It never contains the original recording bytes. There is no
"structurally parsed" success variant: the existing `ZipArchiveStructure` remains a
separate explicitly structural result.

Verification stages are:

1. Enforce the physical package limit and validate classic/ZIP64 records, both exact
   raw entry names, stored methods, metadata, header agreement, ranges, and layout.
2. Read only the validated `manifest.json` data range after enforcing its 1 MiB
   declaration, then call the existing `ManifestValidator.validate(byte[])` for strict
   JSON, schema/semantic rules, and exact JCS byte equality.
3. Require the manifest original and asset identity (`audio/original.wav`, `original`,
   and `audio/wav`) and declared length to agree exactly with the validated ZIP entry.
4. Calculate and compare standard ZIP CRC32 over the actual manifest bytes.
5. Stream the original data range once through a reusable 64 KiB buffer, independently
   count every byte, and incrementally calculate CRC32 and SHA-256 in that pass.
6. Require a 12-byte WAVE identification prefix: `RIFF` or `RF64` at offsets 0-3 and
   `WAVE` at offsets 8-11. No complete WAVE decoding or audio-codec restriction is
   added.
7. Compare actual original count, unsigned 32-bit CRC32, and lowercase hexadecimal
   SHA-256 against their respective ZIP and manifest declarations, then report success.

The controlled failure categories are ZIP structure; manifest parsing/canonicalization;
manifest semantics; missing/mismatched required entry; CRC32; original byte count;
original SHA-256; WAVE identification; resource limit; and I/O/truncated read. The
frozen draft does not make diagnostic precedence normative, so callers should depend
on acceptance versus rejection and the reported failing stage, not assume a malformed
file with multiple defects must always select one category first.

### Reader and mutation model

The complete operation opens one `BoundedFileReader`, which owns one read-only
`FileChannel`, and passes that same reader to the structural parser and every payload
check. The public `ZipStructureParser.parse(BoundedFileReader)` overload leaves its
caller-owned reader open; the path convenience API remains available and closes its
own reader. Payloads are read positionally only within already validated ranges. No
entry is extracted, no evidence byte is written or rewritten, and no high-level ZIP
extraction API controls acceptance.

The reader records the channel size at open and the verifier rechecks the live channel
size after structural validation and before success. A size change causes a controlled
failure. This catches observable truncation or growth, but one open channel is not an
immutable snapshot: same-size in-place writes, or changes that are made and restored
between observations, cannot in general be proven absent. A completed SHA-256 proves
what bytes were observed and their agreement with the manifest; it is not proof that
the backing file was never concurrently modified.

### Integrity and trust boundary

CRC32 is calculated with `java.util.zip.CRC32` over each complete stored entry and is
compared as an unsigned 32-bit value. Matching local and central declarations are not
treated as evidence that payload bytes are intact. SHA-256 is calculated with Java's
required `MessageDigest` implementation over exactly the stored original byte sequence,
not decoded samples, normalized audio, the whole archive, or concatenated metadata.

A technically valid AURAL Core package establishes internal container and declaration
consistency only. It does not independently confirm when or where a recording was made,
who made it, investigator identity, real-world events, authenticity before packaging,
absence of pre-package edits, or chain of custody. The Draft 0.2 manifest is unsigned.

## Classic and ZIP64 structural parser

`org.auralinterop.zip.ZipStructureParser` directly parses raw ZIP records through one
read-only `FileChannel`, via the existing `BoundedFileReader`. It does not use
`ZipFile`, `ZipInputStream`, extraction, decompression, or entry names as filesystem
paths. High-level extraction APIs are not sufficient for this profile because their
central-directory view does not independently prove local-header agreement, exact raw
name bytes, absence of hidden local records, or a prefix/gap/overlap/trailer-free
physical layout.

The Assignment 008D-3F-7E parser supports classic records plus the permitted ZIP64
subset defined by frozen AURAL Draft 0.2 section 6 and PKWARE APPNOTE 6.3.10
(FINAL, 2022-11-01). It enforces:

- one disk, exactly two entries, and exact raw ASCII names `manifest.json` and
  `audio/original.wav`;
- stored method 0, flags zero except optional UTF-8 bit 11, and no data descriptors;
- zero archive and entry comments, and no extra fields in a classic archive;
- exact ZIP64 EOCD version-1 records (record-size field 44, physical size 56), exact
  20-byte single-disk locators, and no extensible data;
- ordered ZIP64 `0x0001` values only for matching size or local-offset sentinels, with
  no missing, duplicate, unnecessary, surplus, or foreign extra fields;
- resolution of every ZIP64 unsigned 64-bit value before comparison or positioning,
  with rejection of high-bit values and values beyond the relevant AURAL limit;
- version-needed 10, 20, or 45, valid MS-DOS timestamps, and regular-file metadata;
- local/central agreement for version-needed, flags, method, timestamp, CRC32
  declaration, sizes, and filename bytes;
- equal compressed and uncompressed sizes, plus manifest/original declared limits;
- exact entry, central-directory, and EOCD bounds with checked 64-bit arithmetic;
- offset-zero first local header and contiguous, non-overlapping local headers, data,
  central directory, and terminal EOCD; and
- no prefix, hidden local entry, undeclared gap, overlap, data overrun, duplicate name,
  unexpected entry, central-directory residue, or trailer.

Reader order is intentionally not fixed. Local entries are validated by their actual
offsets and may place the audio first. Central-directory enumeration may also use
either order. The result list is returned in physical local-entry order.

Use the parser as follows:

```java
ZipArchiveStructure structure = new ZipStructureParser().parse(archivePath);
ZipEntryStructure manifest = structure.entry("manifest.json");
long manifestDataOffset = manifest.dataOffset();
```

`ZipArchiveStructure`, `ZipEntryStructure`, and `ByteRange` expose immutable or
defensively copied metadata and validated half-open ranges. They do not contain entry
payloads. Entry sizes and local offsets are resolved `long` values; the result also
exposes their raw unsigned 32-bit fields and whether each value came from ZIP64.
Archive results expose whether terminal ZIP64 records were used and their exact
physical ranges. `ZipStructureException.reason()` provides controlled structural
failure classification. The legacy `ZIP64_UNSUPPORTED_IN_THIS_STAGE` enum value and
query method remain source-compatible with 7D callers, but the 7E parser no longer
emits that result for supported ZIP64 structures.

### ZIP64 architecture and supported profile

The parser first reads the terminal classic EOCD as raw fields. `Zip64EndParser` then
resolves that record either directly or through the immediately preceding locator and
ZIP64 EOCD. Only after terminal consistency and the 4 MiB directory bound are proven
does the existing central parser allocate its bounded directory buffer.

`Zip64ExtraField` is the single entry-resolution layer shared by local and central
headers. It retains the raw sentinels separately from resolved values, follows APPNOTE
ordering (uncompressed size, compressed size, local-header offset, disk start), and
uses only those values selected by corresponding sentinels. Local ZIP64 size extras
require both local size fields, as APPNOTE 4.5.3 specifies. Disk-start expansion is
rejected: a single-disk AURAL entry is representable as classic disk zero, so the
sentinel and extra value would be unnecessary. An entry that actually uses ZIP64 must
declare version-needed 4.5.

Terminal ZIP64 records are required whenever any classic EOCD field is a sentinel.
They must resolve to disk zero, exactly two entries, the bounded central-directory
size and offset, and exact contiguous placement. Every classic non-sentinel field must
equal the corresponding ZIP64 value. The ZIP64 EOCD version-needed is exactly 4.5;
its version-made-by must advertise 4.5 or later while allowing creator systems to
vary.

Positive tests include small sentinel-expanded entry sizes, independently expanded
central sizes, local-only expansion, 64-bit local-header offsets, combined ordered
extras, both physical entry orders, both central enumeration orders, individual and
combined classic EOCD sentinels, mixed classic/ZIP64 terminal metadata, zero-sized
expanded entries, and the exact 1 MiB manifest boundary. These are actual parser
acceptances.

Malformed signatures, lengths, versions, disks, counts, offsets, sizes, record
placement, classic/ZIP64 contradictions, high-bit unsigned values, missing or
duplicate terminal records, and malformed/unneeded/surplus extras are controlled
rejections. Strict physical layout still rejects prefixes, gaps, overlaps, hidden
locals, data descriptors, undeclared records, and trailers.

Structural acceptance means only that the ZIP envelope satisfies the Draft
0.2 ZIP profile. It does **not** validate actual entry CRC32 values, manifest bytes or
semantics, manifest/ZIP cross-entry consistency, WAVE identity, audio byte count,
SHA-256, or complete package validity. It also does not turn successful parsing into a
guarantee against concurrent in-place modification; later verification must consume
the same open archive snapshot and apply a mutation policy.

### Resource safety

The parser checks the 8 GiB + 16 MiB physical archive limit before record-directed
allocation. It caps the central-directory buffer at 4 MiB, requires exactly two
records, bounds local variable fields by their unsigned 16-bit lengths, uses 64-bit
positions and checked range arithmetic, detects short reads, and performs no
decompression or writes. The manifest declaration is capped at 1 MiB and the original
declaration at 8 GiB. Unsigned 64-bit values are rejected before arithmetic when their
high bit is set or their resource-specific limit is exceeded. Normal tests exercise
small sentinel fixtures, near-4-GiB declarations, 4-GiB declarations and offsets,
high-bit rejection, and range overrun without large allocation or disk writes.

### Optional genuine large-file test

`Zip64LargeFileTest` is compiled but never registered in the normal suite. It streams
a real 4 GiB zero-filled original, computes its ZIP CRC32, places the manifest and
central directory beyond the classic offset range, emits exact ZIP64 entry and
terminal records, parses the result, and deletes the temporary archive in `finally`.
It does not rely on a merely large invalid sparse length.

The opt-in run has an apparent and worst-case physical size of about 4.01 GiB plus a
512 MiB safety margin. It checks usable space before creation. Use a dedicated
temporary directory and invoke only after accepting that disk and time cost:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
$cp = '..\build\classes-main;..\build\classes-test'
java -cp $cp org.auralinterop.tests.Zip64LargeFileTest --run D:\dedicated-aural-temp
```

The test cleans up on success or failure. If the JVM or machine is terminated, remove
any `aural-zip64-large-*.aural` file from that dedicated directory manually. This test
was designed but was not run for Assignment 7E because explicit authorization is
required.

### Frozen public Core Reader evaluation

The test harness reads the frozen public case manifest as its external oracle; production
code never reads case IDs, fixture names, fixture hashes, or expected outcomes. All 33
required Draft 0.2 cases pass. The normal test output records the ID, expected outcome,
actual outcome, PASS/FAIL, and actual category for each rejection.

| Public case | Expected | Actual | Rejection category |
|---|---:|---:|---|
| invalid-altered-original | reject | reject | original SHA-256 |
| invalid-asset-hash | reject | reject | original SHA-256 |
| invalid-compression | reject | reject | ZIP structure |
| invalid-critical-extension | reject | reject | manifest semantic |
| invalid-duplicate-entry | reject | reject | resource limit (exact entry count) |
| invalid-duplicate-key | reject | reject | manifest parsing/canonicalization |
| invalid-entry-comment | reject | reject | ZIP structure |
| invalid-excessive-declared-size | reject | reject | resource limit |
| invalid-excessive-depth | reject | reject | resource limit |
| invalid-extra-field | reject | reject | ZIP structure |
| invalid-fraction | reject | reject | manifest parsing/canonicalization |
| invalid-local-central-crc | reject | reject | ZIP structure |
| invalid-malformed-json | reject | reject | manifest parsing/canonicalization |
| invalid-malformed-local-header | reject | reject | ZIP structure |
| invalid-negative-zero | reject | reject | manifest parsing/canonicalization |
| invalid-missing-required-zip64-end | reject | reject | ZIP structure |
| invalid-noncanonical | reject | reject | manifest parsing/canonicalization |
| invalid-noncharacter | reject | reject | manifest parsing/canonicalization |
| invalid-special-file | reject | reject | ZIP structure |
| invalid-redundant-zip64-end | reject | reject | ZIP structure |
| invalid-trailer | reject | reject | ZIP structure |
| invalid-unknown-member | reject | reject | manifest semantic |
| invalid-unnecessary-zip64-extra | reject | reject | ZIP structure |
| invalid-unpaired-surrogate | reject | reject | manifest parsing/canonicalization |
| invalid-unsafe-filename | reject | reject | required entry mismatch |
| invalid-unsafe-integer | reject | reject | manifest parsing/canonicalization |
| invalid-utf8 | reject | reject | manifest parsing/canonicalization |
| invalid-version | reject | reject | manifest semantic |
| valid-independent | accept | accept | - |
| valid-minimal | accept | accept | - |
| valid-unicode | accept | accept | - |
| valid-required-zip64-end | accept | accept | - |
| valid-unknown-optional | accept | accept | - |

The two optional Draft 0.1 fixtures are not accepted as Draft 0.2 and are covered as
controlled-rejection capability tests. No legacy Draft 0.1 verifier is implemented.
Passing this frozen corpus is a regression result, not independent certification or a
claim of third-party adoption.

### Recorded specification interpretations

The frozen text intentionally makes several metadata fields non-semantic but does not
fully spell out every malformed encoding. This experiment records these choices
without treating them as new normative requirements:

- “valid MS-DOS modification timestamps” is interpreted as valid hour/minute/second
  bit ranges and a real Gregorian date in the representable 1980–2107 range.
- Version-needed 10, 20, and 45 are all accepted for a classic stored entry. Merely
  using 45 does not imply that ZIP64 records must exist.
- The DOS directory bit in the low attribute byte and the Unix file-type nibble in the
  high word are checked regardless of creator system. A zero Unix type or regular-file
  type is accepted; permission bits, creator platform, and internal attributes vary.
- A second EOCD is rejected when it creates undeclared bytes or contradictory terminal
  placement. EOCD-signature octets occurring inside legitimate entry data are not
  globally banned.
- Frozen Draft 0.2 section 6.4 explicitly requires terminal ZIP64 records whenever
  at least one classic EOCD field uses a ZIP64 sentinel and prohibits those records
  when no classic EOCD field uses a sentinel. The Java parser enforces both directions,
  including rejection of missing-required and redundant terminal ZIP64 envelopes.

Small classic and ZIP64 packages are completely verified in the normal suite, including
sentinel-expanded entry sizes and a ZIP64 terminal envelope. All counters and offsets
in the complete verifier are `long`, and original memory use stays fixed at 64 KiB.
The existing optional >4 GiB test still validates structural parsing only; it was
reviewed for compatibility but was not run or expanded during this assignment. A real
>4 GiB full-verification streaming run therefore remains undemonstrated. Its residual
risk is environmental and integration behavior at genuine large offsets, despite the
small ZIP64 and synthetic boundary coverage.

## Experimental Adapter Protocol 1

`org.auralinterop.adapter.AuralConformanceAdapter` is a one-request process adapter for
`verify-package`. It enforces the published 16,384-byte request limit, exact request
members and types, protocol identifier/version, operation, input role, and SHA-256
syntax. It permits the protocol's leading and trailing JSON whitespace. A usable input
path is passed directly to `AuralPackageVerifier`; controlled package verification
failures become `outcome: "reject"`, while malformed requests, unsupported operations,
unusable paths, and unexpected failures exit nonzero without a stdout response.

The adapter never receives or reads an expected result. Its `case_id`, request size,
request SHA-256, and fixture filename do not determine acceptance. The supplied runner
has already authenticated request size and SHA-256; the adapter validates those fields'
protocol shape, then obtains its verdict exclusively from package verification.

Build before invoking the launcher; compilation is deliberately separate from protocol
stdout:

```powershell
Set-Location C:\CodexLab\AuralJavaInteropLab\java
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

`aural-adapter.cmd` resolves its classpath from its own location, so the caller's
working directory is irrelevant. It requires only Java 21 on `PATH` and accepts the
request on stdin; it does not change PowerShell execution policy or launch an IDE.

Run the official conformance runner from the AURAL source checkout against the frozen
manifest as follows:

```powershell
python tools\run_aural_conformance.py `
  --profile core-reader-0.2 `
  --manifest C:\CodexLab\AuralJavaInteropLab\frozen-input\tests\fixtures\conformance\core-reader-0.2-cases.v1.json `
  --report C:\CodexLab\AuralJavaInteropLab\reports\java-core-reader-0.2-report.json `
  --timeout 10 `
  --adapter C:\CodexLab\AuralJavaInteropLab\java\aural-adapter.cmd
```

The recorded synchronization run passed all 33 required Draft 0.2 Core Reader cases
with no failures, execution errors, timeouts, or skips. The report is test evidence, not
official certification or security certification. Protocol 1 defines no format field
in its envelope: supported AURAL format/version identification remains part of the
delegated package verifier.

Provenance signatures, detached signed events, team attestations, AUFX transport, and
evidence interpretation remain separate capabilities.

Build from the `java` directory on Windows without changing machine policy:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

Build and run all foundation tests:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\test.ps1
```

The project has no third-party dependencies and is compiled with `javac --release 21`.
The build also enables `-Xlint:all -Werror`. The normal suite contains 424 tests:
all 304 pre-7F tests remain registered; 65 independent complete-verifier cases were
added (21 positive and 44 negative); all 33 required public Core Reader cases are
separate oracle-driven regressions; two optional legacy fixtures confirm the explicit
Draft 0.1 capability boundary; and 20 separate adapter process tests cover acceptance,
rejection, malformed envelopes, unsupported values, field types, byte limits, output
discipline, path failures, surrounding whitespace, launcher relocation behavior,
spaces, and Unicode. The optional >4 GiB test is not registered or included in that
count.
