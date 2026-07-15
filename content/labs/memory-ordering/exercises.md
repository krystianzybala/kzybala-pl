# Memory ordering in Java and Rust — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (the flag that "works on my machine")

A colleague publishes a configuration object from one thread to another:

```java
// writer                                // reader
config = buildConfig();                  while (!ready) { /* spin */ }
ready = true;                            use(config);
```

`ready` is `volatile`. `config` is a plain field. They argue: "`ready` is
volatile, so when the reader sees `true`, everything before it in the
writer is visible — I read that volatile gives ordering."

It works in every test on their x86-64 desktop. On an ARM server it
crashes with a half-initialized `config` about once per million handoffs.

**Task:** decide whether the colleague's reasoning is right, wrong, or
right-for-the-wrong-reason — and explain both the x86 behavior and the
ARM failures. Careful: this one is subtler than "plain fields have no
ordering."

**Success criteria:** your answer correctly identifies what the JMM
actually guarantees here, names which *reorderings* are and are not
possible on each side (writer vs reader), and explains why the failure is
rare rather than constant on ARM.

<details>
<summary>Hint</summary>

The writer's side is actually fine: a volatile store is a release, so
`config = …` cannot move after `ready = true`. Now look *only* at the
reader — the volatile read is an acquire, so what could still go wrong?
Check what `use(config)` reads: the reference, or the object's *fields*?

</details>

<details>
<summary>Solution</summary>

The reasoning is right about the JMM guarantee and the code is, in fact,
correct **as written**: volatile store = release, volatile read = acquire;
seeing `ready == true` establishes happens-before with everything the
writer did earlier, including `config`'s construction. So the described
crash cannot be caused by *this* code as shown — which is the real
diagnostic skill: the bug must be a difference between the code as shown
and the code as run. The classic culprits to hunt for: (a) the writer
*reassigns* `config` after setting `ready` (later writes are not covered
by the earlier release); (b) `buildConfig()` leaks `this` or publishes
the object through some other non-volatile channel that the reader
actually uses; (c) the reader caches `config` in a local before the
acquire loop; or (d) `ready` is not actually volatile in the failing
build (a refactor dropped it — the x86 version keeps "working" because
x86's TSO makes the missing acquire/release nearly free to get away with,
while ARM's weaker model exposes it rarely but fatally). The lab lesson:
"works on x86, fails rarely on ARM" is the signature of a missing
ordering edge, and absence of failures is never evidence of correctness.

</details>

## Exercise 2 — Implementation (pay only for the ordering you need)

The lab's companion code (`code/java/`, `code/rust/`) includes the
publication example implemented with sequentially-consistent operations
(`volatile` / `SeqCst`) everywhere.

**Task:** re-implement it with the *minimum* ordering that keeps it
correct — `setRelease`/`getAcquire` VarHandle modes in Java,
`store(Release)`/`load(Acquire)` in Rust — and add a stress test that
hammers the handoff across two threads.

**Success criteria (measure and prove, don't assert):**

1. Correctness: the stress test asserts the reader never observes the
   flag without the payload (run it long enough to be meaningful on your
   machine, and on the weakest-ordered hardware you have access to).
2. You can point to the exact release/acquire *pair* and state what each
   side orders (payload-before-flag on the writer; flag-before-payload on
   the reader) — if you cannot name the pair, the reduction is a guess.
3. Disassembly or emitted-instruction inspection (e.g.
   `-XX:+PrintAssembly` / `cargo asm`, where available) shows the SC
   version and your version differ on your hardware — or, if they compile
   identically (common on x86), you can explain why the source-level
   contract still matters (portability to weaker hardware, compiler
   freedom).
4. A deliberately broken variant (both sides `Relaxed`/plain) is included
   for teaching, clearly labeled, with a comment explaining why passing
   tests would *not* make it correct.

<details>
<summary>Hint</summary>

Only the flag needs ordering semantics; the payload fields can be plain —
that is the entire point of publication. If your stress test never fails
the broken variant on x86, that is expected (TSO); say so rather than
concluding the broken variant is fine — and prefer running it on ARM
(e.g. an Apple-silicon dev machine is fine for the *correctness* stress,
even though its timings are never publication evidence).

</details>
