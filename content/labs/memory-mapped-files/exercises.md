# Memory-mapped files and page faults — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (the benchmark that "proves" mmap has no cost)

A colleague benchmarks `warmMmapSequentialRead` in a loop of 1,000
iterations inside a single JMH trial, reusing the same `MappedByteBuffer`
for every iteration, and reports "mmap sequential read: 0.03 ms — mmap has
essentially zero cost, always use it." Their number is not fabricated; it
faithfully measures what they actually ran.

**Task:** explain what real, non-zero cost this measurement has
structurally excluded, and why nothing in the loop as described would ever
expose it.

**Success criteria:** you name the specific cost (not just "warm-up," but
the actual OS-level mechanism), and you propose the minimal change to
their loop that would make that cost visible again.

<details>
<summary>Hint</summary>

They reused the *same* `MappedByteBuffer` for all 1,000 iterations. How
many times does that mean the file's pages actually got mapped and
first-touched?

</details>

<details>
<summary>Solution</summary>

Reusing the same mapping for 1,000 iterations means the file's pages are
mapped and first-touched exactly **once**, on the very first iteration —
every one of the other 999 iterations reads pages that are already
resident and already faulted in, which is exactly the "warm" state this
lab documents. The measurement isn't wrong about what it measured (warm,
already-mapped sequential access really can be that cheap); it's wrong
about what it's being used to conclude ("mmap has essentially zero cost,
always"), because it structurally never re-creates a mapping or touches an
unfaulted page after the first iteration. The minimal fix: map the file
freshly *inside* the timed loop (as `coldMappedSequentialRead` does) if
the claim is meant to cover the fresh-mapping/first-touch case at all.

</details>

## Exercise 2 — Implementation (a partial-scan variant)

The lab's variants all read either the whole file sequentially or the
whole file in a shuffled order. **Task:** implement a new variant,
`partialScanChecksum` (Java) / `partial_scan_checksum` (Rust), that reads
only every *k*-th record (e.g. every 64th, so consecutive reads land in
different pages most of the time) across the full 1,048,576-record
dataset.

**Success criteria (measure, don't assert):**

1. Correctness: your variant's checksum matches the sum of
   `checksumRecord`/`checksum_record` over exactly the records it visits
   (not the full-file checksum), verified against a small fixture the same
   way the existing correctness tests are structured.
2. Measure its cost relative to full sequential and full random access
   over the same dataset, and report where it falls.
3. Explain, from the fault-per-page mechanism in theory.md, why this
   variant's fault count should be close to `record_count / k` even though
   the pattern is not shuffled — name the property of the access pattern
   that makes it behave more like random access than sequential access
   despite being perfectly regular.

<details>
<summary>Hint</summary>

Faults happen per *page*, not per access. If your stride `k` is large
enough that consecutive visited records are almost never on the same
page, what does that do to the fault-to-record ratio compared to
sequential access?

</details>

## Exercise 3 — Evidence interpretation (perf stat page-fault counters)

Below is a **synthetic teaching example** in `perf stat`'s output format —
constructed for this exercise, not captured from any run. It is
educational material for practicing counter interpretation only: it is
never used as measurement evidence, never supports this lab's performance
conclusions, and never enters a comparison or maturity calculation. (The
lab's real page-fault evidence comes exclusively from the native-Linux
evidence runner and is imported with full provenance; see
`benchmark.md`.) Three runs over the identical 1,048,576-record file,
labels removed:

```
Run A:
        41,082,911      minor-faults
             1,204      major-faults
     1,834,221,004      cycles

Run B:
           271,442      minor-faults
                 3      major-faults
       412,904,881      cycles

Run C:
           264,890      minor-faults
             8,912      major-faults
     9,204,441,207      cycles
```

**Task:** match each run to one of: buffered read, warm mmap sequential
read, cold/fresh mmap sequential read on a genuinely evicted page cache.
Justify each match from the counters, not just the cycle count. Then state
one conclusion this data **cannot** support.

**Success criteria:** all three identifications are correct, your
reasoning for each cites the specific counter relationship that
distinguishes it (not just "it's slower/faster"), and your "cannot
conclude" statement is genuinely unsupported by this data rather than
merely cautious.

<details>
<summary>Hint</summary>

Minor faults and major faults measure different things. Which of the
three runs' minor-fault count is far too high to be "one fault per 4 KiB
page over a 64 MB file" (~16,384 pages)? What does a very high minor-fault
count with almost no major faults suggest about *what* is generating the
faults, if not page-cache misses?

</details>

<details>
<summary>Solution</summary>

**Run A is buffered read.** ~41 million minor faults over a file with only
~16,384 pages makes no sense as page faults from *mapping* the file at
all — buffered read never maps anything. This count is consistent with
per-syscall or per-allocation kernel bookkeeping overhead counted as minor
faults by `perf stat` in some configurations (e.g. `brk`/heap-growth
faults from JVM/allocator activity across a million `read()` calls) —
the mechanism under test here is syscall volume, not mmap page faults at
all, which is exactly why buffered read is the slowest by cycle count.

**Run C is cold/fresh mmap sequential read.** 8,912 major faults is the
right order of magnitude for "most of ~16,384 4 KiB pages required an
actual storage read" (major fault = page cache miss, satisfied from
disk/storage), and its cycle count is by far the highest of the two mmap
runs — consistent with major faults being far more expensive than minor
ones.

**Run B is warm mmap sequential read.** ~271K minor faults (roughly
consistent with per-page bookkeeping across the mapping, not per-record)
and only 3 major faults — almost every page was already resident, so the
OS satisfied each fault cheaply from the page cache without touching
storage at all. Its cycle count is the lowest of the three by a wide
margin.

**What this data cannot support:** any claim about how long a major fault
takes in absolute time on a *different* storage medium (NVMe vs spinning
disk vs network filesystem) — major-fault latency is dominated by the
storage device, not shown here at all; this data shows only fault
*counts*, not per-fault latency. It also cannot be used to estimate what
fraction of Run A's cycles came from faults specifically, since Run A's
fault counter here isn't measuring page faults from a mapping in the
first place.

</details>
