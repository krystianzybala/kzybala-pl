# Memory ordering in Java — VarHandle access modes

Four `VarHandle` access modes, from weakest to strongest, applied to the
same broken-publication problem from the theory above.

## Plain access (the bug)

```java
public class PlainPublication {
    private static final VarHandle DATA, FLAG;
    static {
        try {
            var lookup = MethodHandles.lookup();
            DATA = lookup.findVarHandle(PlainPublication.class, "data", int.class);
            FLAG = lookup.findVarHandle(PlainPublication.class, "flag", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private int data;
    private int flag;

    // Publisher thread.
    public void publish() {
        DATA.set(this, 42);
        FLAG.set(this, 1);
    }

    // Observer thread. May legally read flag == 1 and data == 0 — see
    // theory.md "The message-passing litmus test".
    public boolean tryConsume() {
        if ((int) FLAG.get(this) == 1) {
            int seen = (int) DATA.get(this);
            return seen == 42; // NOT guaranteed to be true, even after seeing flag == 1
        }
        return false;
    }
}
```

`VarHandle.set`/`get` are plain accesses: no atomicity guarantee beyond
what the JLS gives ordinary field access, no ordering guarantee at all.
This is the exact shape of the interactive model's "Broken publication"
scenario, expressed as compileable Java.

## Release/acquire (the fix)

```java
public class ReleaseAcquirePublication {
    private static final VarHandle DATA, FLAG;
    static { /* same lookup as above */ }

    private int data;
    private int flag;

    public void publish() {
        DATA.set(this, 42);           // plain — ordering comes from the release below
        FLAG.setRelease(this, 1);     // release: flushes everything before it in program order
    }

    public boolean tryConsume() {
        if ((int) FLAG.getAcquire(this) == 1) {
            int seen = (int) DATA.get(this);
            return seen == 42; // guaranteed true: the acquire read established happens-before
        }
        return false;
    }
}
```

`setRelease`/`getAcquire` is the JVM's direct expression of the
release/acquire pair this lab's "Release/acquire message passing" scenario
demonstrates: once `tryConsume` observes `flag == 1` via `getAcquire`, the
plain read of `data` is guaranteed to see the publisher's write.

## Opaque access (atomicity without cross-variable ordering)

```java
public class OpaqueCounter {
    private static final VarHandle COUNTER;
    static { /* lookup omitted */ }

    private int counter;

    public void increment() {
        int current = (int) COUNTER.getOpaque(this);
        COUNTER.setOpaque(this, current + 1); // NOT atomic as a read-modify-write!
    }
}
```

**Careful: this is not the same as `getAndAdd`.** `getOpaque`/`setOpaque`
give per-location atomicity and a coherent total order for *that one
field*, but the get-then-set pair above is still two separate operations —
another thread's increment can interleave between them, losing an update.
Opaque access answers "is this one read/write atomic and coherent?", not
"is this whole read-modify-write sequence atomic?" — that needs a genuine
compare-and-set loop or `getAndAdd`, which is what the volatile/RMW example
below and the interactive model's "Relaxed counter" scenario both use.

## Volatile (the strongest VarHandle mode)

```java
public class VolatileCounter {
    private static final VarHandle COUNTER;
    static { /* lookup omitted */ }

    private volatile int counter;

    public void incrementAtomically() {
        COUNTER.getAndAdd(this, 1); // a genuine atomic read-modify-write
    }
}
```

`getAndAdd` is a true compare-and-set-based RMW: atomic, and — because the
field is `volatile` — every increment happens-before the next thread's
increment that observes it. This is the JVM analogue of the interactive
model's relaxed-counter RMW step: the counter itself is always correct
regardless of interleaving, which is the atomicity guarantee; nothing here
says anything about the *ordering* of any other, unrelated field.

**None of these examples let you directly force or observe which
low-level CPU behaviour (store-buffer draining, cache-line state) produced
a given outcome** — that is exactly why the interactive model above exists,
and why `perf c2c`-style tooling (see the [Cache Coherence and MESI](/lab/mesi/)
lab) is a hardware-level complement, not a replacement, for reasoning about
the JMM.

The runnable Maven project (including a test demonstrating the broken and
fixed publication paths) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/memory-ordering/code/java" rel="noopener"><code>content/labs/memory-ordering/code/java/</code></a>
in this site's repository.
