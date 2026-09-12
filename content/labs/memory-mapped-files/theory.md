# Memory-mapped files and page faults — theory

## Performance question and hypothesis

**Question:** when does mmap remove copying, and when do page faults turn
latency into a lottery?

**Hypothesis:** mapped access can simplify large-file reads and sharing,
but cold pages, dirty writeback and mapping lifecycle dominate tail
behavior.

**What would disprove it:** if a freshly-mapped, never-before-touched
region read no slower than an already-touched one (page faults would have
no observable cost), or if buffered reads that issue one syscall per
record outperformed sequential mmap access on the same data (mmap would
not actually be removing per-record copy overhead), or if random-order
mapped access cost the same as sequential mapped access over the same
data (page-level locality wouldn't matter, contradicting the whole premise
of "the fault is per page, not per byte") — any of these would mean the
mechanism this lab teaches isn't doing what it claims.

## Learning objective

Show that `mmap` does not make file I/O free — it *relocates* the cost
from explicit `read()` calls to implicit page faults the CPU triggers the
first time each page is touched, and from an application-managed heap
buffer to OS-managed, evictable page-cache memory. Build the same
fixed-record read/write logic three ways (buffered, sequential-mapped,
random-mapped) plus a mapped-write-and-flush variant, and use the
differences between them to make that relocated cost visible and
measurable rather than assumed.

## Prerequisites

- The [Cache Locality and Working Set](/lab/cache-locality-working-set/)
  lab — page faults are the same "someone has to fetch what you're about
  to touch" idea as a cache miss, just one memory-hierarchy level up (RAM
  page vs. cache line) and orders of magnitude more expensive when the
  fetch is a disk read rather than a DRAM access.
- The [Object Serialization vs Fixed Binary Layout](/lab/fixed-binary-serialization/)
  lab — this lab's fixed 64-byte record format is exactly that lab's fixed
  wire layout, applied to a file's bytes instead of a network message's.

### Pre-lab diagnostic

A team maps a 10 GB file and reads it sequentially once; it takes 40
seconds. They map the *same* file again in the same process and read it
sequentially a second time; it takes 0.4 seconds. Nothing about the code
changed between the two reads. What changed?

(Answer at the end of this page.)

## Terminology

| Term | Meaning in this lab |
|---|---|
| Page fault | The CPU trap that occurs when a program accesses a virtual page with no physical mapping yet — for a file-backed mapping, the OS handles it by reading that page's data from storage. |
| Minor fault | A page fault the OS satisfies without a storage read (the data is already in the page cache from some earlier access) — cheap, a memory-management bookkeeping cost only. |
| Major fault | A page fault the OS satisfies by actually reading from storage — orders of magnitude more expensive than a minor fault. |
| Page cache | The OS's cache of file-backed pages in RAM, shared across all processes mapping (or reading) the same file — this is what makes a second read of the same file fast regardless of which process does it. |
| First touch | The first access to a given page after mapping — the one access in a sequence of reads to that page that can trigger a fault; every subsequent access to the same page (until it's evicted) is a cheap hit. |
| Dirty page | A mapped page that has been written to in memory but not yet written back to the underlying file. |
| `msync`/`force()` | The operation that forces dirty pages to be written back to storage before returning, rather than leaving that to the OS's own writeback schedule. |

## Buffered read vs mapped read: where the copy goes

A buffered read (`FileChannel.read(ByteBuffer)` in Java,
`Read::read_exact` in Rust) issues an explicit system call that copies
bytes from the OS page cache into a buffer your program allocated. A
memory-mapped read never issues a per-record system call at all: the
mapping makes the file's bytes appear directly in your process's address
space, and the *first* access to each page triggers a page fault that the
OS resolves by making that page's page-cache frame visible at the mapped
address — no additional copy into an application buffer. This is why
sequential mmap access in this lab reads its fixed-size records directly
out of a `MappedByteBuffer`/`&[u8]` with no `byte[]`/`Vec<u8>` allocated
per record, while the buffered variant necessarily allocates (or reuses)
one.

## Warm vs cold, honestly scoped

The words "warm" and "cold" describe *page-cache residency*, a property
of the operating system, not of the mapping API. A mapping over a file
whose pages are already in the page cache (because something read it
recently — possibly even a different process) will not fault at all on
first touch; a mapping over a file whose pages have been evicted (or never
read) will fault on every page it touches for the first time. Forcing a
genuinely cold read on demand requires evicting those pages from the OS's
page cache, which on Linux is `posix_fadvise(POSIX_FADV_DONTNEED)` (or, at
the extreme, dropping the whole system's page cache — a privileged,
system-wide operation this lab's guardrails explicitly forbid using
silently: "dropping caches without root/disclosure").

This lab's implementation is honest about what a development machine
without that capability can and cannot demonstrate: its `coldMappedSequentialRead`/`cold_mapped_sequential_read`
variant maps the file *freshly* on every invocation rather than reusing an
already-mapped, already-read buffer — this isolates the cost of
establishing a new mapping and its first traversal, which is a real,
measurable difference from reusing a warm mapping, but it is **not** a
guarantee that the underlying pages were actually evicted from the OS page
cache. Genuine cold-vs-warm page-cache disambiguation (using
`posix_fadvise` where the runner detects it is available) is native-Linux
evidence — see "Assumptions and scope" below.

## Random access and page-level locality

A page fault happens once per *page* a mapping touches, not once per
*byte*. Reading records sequentially means many records share the same
page (a typical 4 KiB page holds 64 fixed 64-byte records), so most reads
after the first touch of that page are free. Reading the same records in
random order defeats that locality: a fully-shuffled access pattern is
far more likely to touch a page that hasn't been visited yet on each
step, spreading the fault cost across many more distinct page-fault
events even though the exact same bytes are read overall. This is the
same working-set idea as CPU cache locality, one level up the memory
hierarchy.

## Mapped write and flush

Writing into a writable mapping (`MapMode.READ_WRITE`/`MmapMut`) marks the
touched pages dirty in memory without necessarily writing them back to
storage immediately — the OS decides when to flush dirty pages on its own
schedule unless the program forces it. `force()`/`flush()` is the explicit
request to write every dirty page back before returning, which is why
this lab measures the write loop and the flush as two logically separate
costs: the write loop's cost is dominated by memory writes, while the
flush's cost is dominated by actual storage I/O.

## Visualization 1: virtual-to-page mapping (textual fallback)

```
Process virtual address space          File on disk (backed by page cache)
+------------------+                   +------------------+
| mapped region     | ----first touch--> | page 0 (fault: minor or major) |
| (records 0..63)   |                   +------------------+
+------------------+                   | page 1 (fault on first touch)  |
| mapped region      | -----------------> +------------------+
| (records 64..127)  |                   | page 2 ...                     |
+------------------+                   +------------------+
```

Once a page's fault has been resolved, every later access to any byte
within that page — from this process or another one mapping the same
file — is a plain memory access, no fault at all.

## Visualization 2: fault timeline (textual fallback)

```
Sequential access over N pages, all initially unmapped:
  page 1: FAULT (resolve) -> read record A, B, C, D (free)
  page 2: FAULT (resolve) -> read record E, F, G, H (free)
  page 3: FAULT (resolve) -> read record I, J, K, L (free)
  ...
  -> one fault per page, amortized over every record on that page.

Random access over the same N pages, order shuffled:
  touch page 7: FAULT -> read one record -> jump elsewhere
  touch page 2: FAULT -> read one record -> jump elsewhere
  touch page 9: FAULT -> read one record -> jump elsewhere
  ...
  -> a much higher fraction of accesses land on a not-yet-touched page,
     because the access order no longer clusters by page.
```

## Visualization 3: warm/cold comparison (textual fallback)

```
                         Illustrative dev-run shape (see benchmark.md for real numbers)
Buffered read (1 syscall/record):        slowest — dominates by syscall count, not page faults
Cold/fresh-mapped sequential read:       faster than buffered, includes fresh-mapping + first-touch cost
Warm (reused) mapped sequential read:    fastest read path — no new mapping, no first-touch faults
Random mapped access (same data):        between cold and buffered — page-level locality lost
Mapped write + flush:                    write cost (memory) + flush cost (storage), reported separately
```

## Known traps

- **Dropping caches without root/disclosure.** Forcing OS-wide page-cache
  eviction (e.g. `echo 3 > /proc/sys/vm/drop_caches` on Linux) requires
  root and affects every other process on the host — using it silently
  (undisclosed) to fabricate a "cold" number is exactly what this lab's
  guardrails forbid; any such step must be explicit, reversible, and
  disclosed in the run's provenance.
- **Confusing page cache with process memory.** A mapped file's pages
  count toward the process's resident set size (RSS) while mapped, but
  they are reclaimable OS cache, not memory the process "owns" the way a
  heap allocation is — treating RSS growth from a mapping as an allocation
  leak conflates two different kinds of memory.
- **Excluding first touch.** Benchmarking only a mapping's *second* pass
  (after the pages are already faulted in) and reporting it as "mmap
  performance" silently discards the one cost (first-touch page faults)
  that is often mmap's whole story on a cold-cache workload.
- **Depending on forced unmapping hacks.** Relying on a specific GC/JIT
  finalization timing (Java) or a non-obvious drop-order trick (Rust) to
  "make sure the mapping is really gone" before reusing a file is fragile
  and non-portable; this lab uses explicit scoping (`try`-with-resources
  patterns, RAII drop) instead.

## Assumptions and scope

- Every dataset in this lab uses the same fixed 64-byte record layout
  (id, value, 48-byte payload) — see the
  [Object Serialization vs Fixed Binary Layout](/lab/fixed-binary-serialization/)
  lab for why a fixed layout is what makes direct, allocation-free field
  access over a mapped region possible in the first place.
- Genuine OS-page-cache cold-vs-warm disambiguation (via
  `posix_fadvise(POSIX_FADV_DONTNEED)`) is only available on the
  native-Linux evidence host; this repository's development workstation
  cannot force real cache eviction without root, so its "cold" variant
  measures fresh-mapping-plus-first-touch cost only, not verified
  page-cache absence — this is disclosed explicitly in benchmark.md, never
  silently presented as equivalent.
- The `largerThanRam` dataset profile (design.md's "larger-than-RAM
  optional profile") is capability-detected and not exercised on this
  repository's development or reference hosts by default; it is marked
  evidence-unavailable rather than run with a size that doesn't actually
  exceed RAM (which would defeat its purpose) or fabricated.
- Correctness (every record's id/value/payload round-tripping exactly) is
  verified by a fixture shared identically by both languages' test suites
  before any timing is trusted.

## Pre-lab diagnostic — answer

Nothing in the *code* changed — what changed is **page-cache residency**.
The first read had to fault in every page from storage (the file had
never been read before, so every page access was at least a minor fault,
and on a cold system likely a slower major fault reading from disk); the
second read found every one of those same pages already resident in the
OS page cache from the first pass, so every access was a cheap memory
read with no fault resolution needed at all. The mapping API itself did
nothing differently between the two reads — the entire 100x speedup is
the page cache, which is exactly why this lab insists on measuring
"warm" and "cold" as separate, honestly-labeled variants rather than
reporting a single "mmap read" number.
