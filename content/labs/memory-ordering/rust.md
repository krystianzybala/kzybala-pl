# Memory ordering in Rust — atomic orderings

The same broken-publication problem, expressed with Rust's five
`std::sync::atomic::Ordering` variants.

## Relaxed (the bug)

```rust
use std::sync::atomic::{AtomicI32, Ordering};

pub struct RelaxedPublication {
    data: AtomicI32,
    flag: AtomicI32,
}

impl RelaxedPublication {
    pub fn new() -> Self {
        Self { data: AtomicI32::new(0), flag: AtomicI32::new(0) }
    }

    // Publisher thread.
    pub fn publish(&self) {
        self.data.store(42, Ordering::Relaxed);
        self.flag.store(1, Ordering::Relaxed);
    }

    // Observer thread. May legally read flag == 1 and data == 0 — see
    // theory.md "The message-passing litmus test".
    pub fn try_consume(&self) -> bool {
        if self.flag.load(Ordering::Relaxed) == 1 {
            let seen = self.data.load(Ordering::Relaxed);
            return seen == 42; // NOT guaranteed, even after observing flag == 1
        }
        false
    }
}
```

`Ordering::Relaxed` gives atomicity per operation and a total modification
order per location — and nothing about ordering relative to any other
atomic. This is the exact shape of the interactive model's "Broken
publication" scenario.

## Release/Acquire (the fix)

```rust
use std::sync::atomic::{AtomicI32, Ordering};

pub struct ReleaseAcquirePublication {
    data: AtomicI32,
    flag: AtomicI32,
}

impl ReleaseAcquirePublication {
    pub fn new() -> Self {
        Self { data: AtomicI32::new(0), flag: AtomicI32::new(0) }
    }

    pub fn publish(&self) {
        self.data.store(42, Ordering::Relaxed); // ordering comes from the release below
        self.flag.store(1, Ordering::Release);
    }

    pub fn try_consume(&self) -> bool {
        if self.flag.load(Ordering::Acquire) == 1 {
            let seen = self.data.load(Ordering::Relaxed);
            return seen == 42; // guaranteed true: Acquire/Release established happens-before
        }
        false
    }
}
```

`Ordering::Release` on the publishing store paired with `Ordering::Acquire`
on the observing load is Rust's direct equivalent of Java's
`setRelease`/`getAcquire` pair — and the interactive model's
"Release/acquire message passing" scenario. `data`'s own store/load stay
`Relaxed`; the ordering guarantee comes entirely from the `flag`
release/acquire pair, exactly as in the Java example.

## The store-buffering litmus test, and why SeqCst exists

```rust
use std::sync::atomic::{AtomicI32, Ordering};
use std::sync::Arc;
use std::thread;

pub struct StoreBufferingTest {
    x: AtomicI32,
    y: AtomicI32,
}

impl StoreBufferingTest {
    pub fn new() -> Self {
        Self { x: AtomicI32::new(0), y: AtomicI32::new(0) }
    }

    // With `ordering == Ordering::Relaxed`, both threads can observe 0 for
    // the other's write — the classic store-buffering outcome the
    // interactive model's "Store buffering" scenario demonstrates.
    // With `ordering == Ordering::SeqCst`, that specific outcome (both 0)
    // is forbidden — see the "Sequential consistency comparison" scenario.
    pub fn run(self: &Arc<Self>, ordering: Ordering) -> (i32, i32) {
        let a = Arc::clone(self);
        let b = Arc::clone(self);
        let t0 = thread::spawn(move || {
            a.x.store(1, ordering);
            a.y.load(ordering)
        });
        let t1 = thread::spawn(move || {
            b.y.store(1, ordering);
            b.x.load(ordering)
        });
        (t0.join().unwrap(), t1.join().unwrap())
    }
}
```

**Why this needs `SeqCst`, not just `Acquire`/`Release`:** the store-buffering
test has no dependency between `x` and `y` for `Acquire`/`Release` to
attach to — neither thread's operations synchronize with the other's in a
way that rules out both loads observing 0. `SeqCst` is different: it adds
a single total order that *every* SeqCst operation across *every* thread
agrees on, which is exactly the extra guarantee needed to forbid the
both-see-0 outcome. This is the canonical example of a case where
`Acquire`/`Release` genuinely is not enough.

**Getting this wrong is undefined behaviour, not a slow-but-safe
fallback.** Rust's type system does not check that your chosen ordering
actually provides the guarantee your algorithm depends on — this is on you,
exactly as in Java.

The runnable Cargo project (including tests reproducing both the broken
and fixed publication paths, and the store-buffering outcome under both
orderings) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/memory-ordering/code/rust" rel="noopener"><code>content/labs/memory-ordering/code/rust/</code></a>
in this site's repository.
