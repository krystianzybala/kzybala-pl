//! Deterministic source-data generation and the four representation
//! variants — the cross-language equivalence contract
//! (../fixtures/allocation-object-layout-fixtures.json). Every variant
//! reads the identical field values for a given dataset and reproduces
//! the identical checksum; representation changes bytes/instance and
//! construction cost, never the result.
//!
//! Rust has no per-object header the way Java does: a `Box<T>` is just a
//! pointer to a heap-allocated `T`, with no mark word, no klass pointer,
//! nothing but the fields themselves (padded only for `T`'s own
//! alignment). `boxed_object_graph` is still built as individually
//! heap-allocated, pointer-indirected records — the honest analog of
//! Java's "one heap object per record, accessed by reference" — but it
//! is expected to carry none of Java's per-instance header tax, isolating
//! reference-indirection cost from header cost in a way Java's own
//! variants cannot (rust.md).

pub const ORDERS_N: usize = 200_000;
pub const TREE_N: usize = 200_000;
pub const TUPLES_N: usize = 1_000_000;

pub fn xorshift64(x: u64) -> u64 {
    let mut x = x;
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    x
}

// ---------------------------------------------------------------------
// Source data (shared by every variant)
// ---------------------------------------------------------------------

pub struct OrdersSource {
    pub id: Vec<u64>,
    pub price_ticks: Vec<u64>,
    pub quantity: Vec<u32>,
    pub flags: Vec<u32>,
}

pub fn generate_orders(n: usize) -> OrdersSource {
    let mut id = Vec::with_capacity(n);
    let mut price_ticks = Vec::with_capacity(n);
    let mut quantity = Vec::with_capacity(n);
    let mut flags = Vec::with_capacity(n);
    let mut x: u64 = 70;
    for i in 0..n {
        id.push(i as u64);
        x = xorshift64(x);
        price_ticks.push(x % 10_000_000 + 1);
        x = xorshift64(x);
        quantity.push((x % 10_000) as u32 + 1);
        x = xorshift64(x);
        flags.push((x % 16) as u32);
    }
    OrdersSource {
        id,
        price_ticks,
        quantity,
        flags,
    }
}

pub fn expected_orders_checksum(s: &OrdersSource) -> u64 {
    let mut sum = 0u64;
    for i in 0..s.id.len() {
        sum = sum
            .wrapping_add(s.id[i])
            .wrapping_add(s.price_ticks[i])
            .wrapping_add(s.quantity[i] as u64)
            .wrapping_add(s.flags[i] as u64);
    }
    sum
}

pub fn generate_tree_values(n: usize) -> Vec<u64> {
    let mut value = Vec::with_capacity(n);
    let mut x: u64 = 71;
    for _ in 0..n {
        x = xorshift64(x);
        value.push(x % 1_000_000);
    }
    value
}

pub fn expected_tree_checksum(value: &[u64]) -> u64 {
    value.iter().fold(0u64, |acc, &v| acc.wrapping_add(v))
}

pub struct TuplesSource {
    pub x: Vec<i32>,
    pub y: Vec<i32>,
}

pub fn generate_tuples(n: usize) -> TuplesSource {
    let mut xs = Vec::with_capacity(n);
    let mut ys = Vec::with_capacity(n);
    let mut s: u64 = 72;
    for _ in 0..n {
        s = xorshift64(s);
        xs.push((s % 100_000) as i32);
        s = xorshift64(s);
        ys.push((s % 100_000) as i32);
    }
    TuplesSource { x: xs, y: ys }
}

pub fn expected_tuples_checksum(s: &TuplesSource) -> u64 {
    let mut sum = 0u64;
    for i in 0..s.x.len() {
        sum = sum.wrapping_add((s.x[i] + s.y[i]) as u64);
    }
    sum
}

// ---------------------------------------------------------------------
// Variant 1: boxed object graph — individually heap-allocated records
// ---------------------------------------------------------------------

pub mod boxed_object_graph {
    use super::*;

    pub struct OrderRecord {
        pub id: u64,
        pub price_ticks: u64,
        pub quantity: u32,
        pub flags: u32,
    }

    pub struct TreeNode {
        pub value: u64,
        pub left: Option<Box<TreeNode>>,
        pub right: Option<Box<TreeNode>>,
    }

    pub struct Tuple {
        pub x: i32,
        pub y: i32,
    }

    pub fn build_orders(s: &OrdersSource) -> Vec<Box<OrderRecord>> {
        (0..s.id.len())
            .map(|i| {
                Box::new(OrderRecord {
                    id: s.id[i],
                    price_ticks: s.price_ticks[i],
                    quantity: s.quantity[i],
                    flags: s.flags[i],
                })
            })
            .collect()
    }

    pub fn sum_orders(records: &[Box<OrderRecord>]) -> u64 {
        let mut sum = 0u64;
        for r in records {
            sum = sum
                .wrapping_add(r.id)
                .wrapping_add(r.price_ticks)
                .wrapping_add(r.quantity as u64)
                .wrapping_add(r.flags as u64);
        }
        sum
    }

    /// Recursion depth is bounded by tree height (~18 for this lab's N),
    /// so plain recursion is safe and idiomatic here — no manual stack
    /// needed, unlike Java's array-based node objects (java.md).
    fn build_subtree(value: &[u64], index: usize) -> Option<Box<TreeNode>> {
        if index >= value.len() {
            return None;
        }
        let left = build_subtree(value, 2 * index + 1);
        let right = build_subtree(value, 2 * index + 2);
        Some(Box::new(TreeNode {
            value: value[index],
            left,
            right,
        }))
    }

    pub fn build_tree(value: &[u64]) -> Box<TreeNode> {
        build_subtree(value, 0).expect("non-empty tree")
    }

    fn sum_subtree(node: &TreeNode) -> u64 {
        let mut sum = node.value;
        if let Some(l) = &node.left {
            sum = sum.wrapping_add(sum_subtree(l));
        }
        if let Some(r) = &node.right {
            sum = sum.wrapping_add(sum_subtree(r));
        }
        sum
    }

    pub fn sum_tree(root: &TreeNode) -> u64 {
        sum_subtree(root)
    }

    pub fn build_tuples(s: &TuplesSource) -> Vec<Box<Tuple>> {
        (0..s.x.len())
            .map(|i| {
                Box::new(Tuple {
                    x: s.x[i],
                    y: s.y[i],
                })
            })
            .collect()
    }

    pub fn sum_tuples(tuples: &[Box<Tuple>]) -> u64 {
        let mut sum = 0u64;
        for t in tuples {
            sum = sum.wrapping_add((t.x + t.y) as u64);
        }
        sum
    }
}

// ---------------------------------------------------------------------
// Variant 2: flat primitive arrays (struct-of-arrays)
// ---------------------------------------------------------------------

pub mod flat_primitive_arrays {
    use super::*;

    pub struct Orders {
        pub id: Vec<u64>,
        pub price_ticks: Vec<u64>,
        pub quantity: Vec<u32>,
        pub flags: Vec<u32>,
    }

    pub fn build_orders(s: &OrdersSource) -> Orders {
        Orders {
            id: s.id.clone(),
            price_ticks: s.price_ticks.clone(),
            quantity: s.quantity.clone(),
            flags: s.flags.clone(),
        }
    }

    pub fn sum_orders(o: &Orders) -> u64 {
        let mut sum = 0u64;
        for i in 0..o.id.len() {
            sum = sum
                .wrapping_add(o.id[i])
                .wrapping_add(o.price_ticks[i])
                .wrapping_add(o.quantity[i] as u64)
                .wrapping_add(o.flags[i] as u64);
        }
        sum
    }

    pub fn build_tree(value: &[u64]) -> Vec<u64> {
        value.to_vec()
    }

    /// Index-arithmetic tree traversal — a real traversal, not a linear scan.
    pub fn sum_tree(value: &[u64]) -> u64 {
        let n = value.len();
        let mut sum = 0u64;
        let mut stack: Vec<usize> = vec![0];
        while let Some(i) = stack.pop() {
            if i >= n {
                continue;
            }
            sum = sum.wrapping_add(value[i]);
            stack.push(2 * i + 1);
            stack.push(2 * i + 2);
        }
        sum
    }

    pub struct Tuples {
        pub x: Vec<i32>,
        pub y: Vec<i32>,
    }

    pub fn build_tuples(s: &TuplesSource) -> Tuples {
        Tuples {
            x: s.x.clone(),
            y: s.y.clone(),
        }
    }

    pub fn sum_tuples(t: &Tuples) -> u64 {
        let mut sum = 0u64;
        for i in 0..t.x.len() {
            sum = sum.wrapping_add((t.x[i] + t.y[i]) as u64);
        }
        sum
    }
}

// ---------------------------------------------------------------------
// Variant 3: packed off-heap struct — a raw byte buffer, manual offsets
// ---------------------------------------------------------------------

pub mod packed_off_heap_struct {
    use super::*;

    // Unlike Java, which needs the FFM API to escape the object header,
    // an ordinary `Vec<Record>` in Rust is ALREADY a packed, header-free
    // struct array (`flat_primitive_arrays`'s Orders/Tuples come close
    // to this too, field-by-field). This module still builds an
    // explicit raw-byte buffer with manual offsets — structurally
    // parallel to Java's `MemorySegment` technique — to keep this lab's
    // four-variant matrix honest and comparable, not because Rust needs
    // the escape hatch (rust.md). Every access uses
    // `to_ne_bytes`/`from_ne_bytes` on byte slices, which is safe
    // regardless of the buffer's alignment — no `unsafe` anywhere in
    // this module, and no risk of the "using packed unaligned fields
    // unsafely" trap.

    const ORDER_STRIDE: usize = 24; // id(8) + price_ticks(8) + quantity(4) + flags(4)

    pub struct Orders {
        buf: Vec<u8>,
        n: usize,
    }

    pub fn build_orders(s: &OrdersSource) -> Orders {
        let n = s.id.len();
        let mut buf = vec![0u8; ORDER_STRIDE * n];
        for i in 0..n {
            let base = i * ORDER_STRIDE;
            buf[base..base + 8].copy_from_slice(&s.id[i].to_ne_bytes());
            buf[base + 8..base + 16].copy_from_slice(&s.price_ticks[i].to_ne_bytes());
            buf[base + 16..base + 20].copy_from_slice(&s.quantity[i].to_ne_bytes());
            buf[base + 20..base + 24].copy_from_slice(&s.flags[i].to_ne_bytes());
        }
        Orders { buf, n }
    }

    pub fn record_stride_bytes(_o: &Orders) -> usize {
        ORDER_STRIDE
    }

    pub fn sum_orders(o: &Orders) -> u64 {
        let mut sum = 0u64;
        for i in 0..o.n {
            let base = i * ORDER_STRIDE;
            let id = u64::from_ne_bytes(o.buf[base..base + 8].try_into().unwrap());
            let price_ticks = u64::from_ne_bytes(o.buf[base + 8..base + 16].try_into().unwrap());
            let quantity = u32::from_ne_bytes(o.buf[base + 16..base + 20].try_into().unwrap());
            let flags = u32::from_ne_bytes(o.buf[base + 20..base + 24].try_into().unwrap());
            sum = sum
                .wrapping_add(id)
                .wrapping_add(price_ticks)
                .wrapping_add(quantity as u64)
                .wrapping_add(flags as u64);
        }
        sum
    }

    pub struct Tree {
        buf: Vec<u8>,
        n: usize,
    }

    pub fn build_tree(value: &[u64]) -> Tree {
        let n = value.len();
        let mut buf = vec![0u8; 8 * n];
        for i in 0..n {
            buf[i * 8..i * 8 + 8].copy_from_slice(&value[i].to_ne_bytes());
        }
        Tree { buf, n }
    }

    /// Index-arithmetic tree traversal over the packed buffer.
    pub fn sum_tree(t: &Tree) -> u64 {
        let mut sum = 0u64;
        let mut stack: Vec<usize> = vec![0];
        while let Some(i) = stack.pop() {
            if i >= t.n {
                continue;
            }
            let base = i * 8;
            sum = sum.wrapping_add(u64::from_ne_bytes(
                t.buf[base..base + 8].try_into().unwrap(),
            ));
            stack.push(2 * i + 1);
            stack.push(2 * i + 2);
        }
        sum
    }

    pub struct Tuples {
        buf: Vec<u8>,
        n: usize,
    }

    pub fn build_tuples(s: &TuplesSource) -> Tuples {
        let n = s.x.len();
        let mut buf = vec![0u8; 8 * n];
        for i in 0..n {
            let base = i * 8;
            buf[base..base + 4].copy_from_slice(&s.x[i].to_ne_bytes());
            buf[base + 4..base + 8].copy_from_slice(&s.y[i].to_ne_bytes());
        }
        Tuples { buf, n }
    }

    pub fn sum_tuples(t: &Tuples) -> u64 {
        let mut sum = 0u64;
        for i in 0..t.n {
            let base = i * 8;
            let x = i32::from_ne_bytes(t.buf[base..base + 4].try_into().unwrap());
            let y = i32::from_ne_bytes(t.buf[base + 4..base + 8].try_into().unwrap());
            sum = sum.wrapping_add((x + y) as u64);
        }
        sum
    }
}

// ---------------------------------------------------------------------
// Variant 4: reused mutable holder — one instance, streamed
// ---------------------------------------------------------------------

pub mod reused_mutable_holder {
    use super::*;

    struct OrderHolder {
        id: u64,
        price_ticks: u64,
        quantity: u32,
        flags: u32,
    }

    pub fn stream_orders(s: &OrdersSource) -> u64 {
        let mut holder = OrderHolder {
            id: 0,
            price_ticks: 0,
            quantity: 0,
            flags: 0,
        };
        let mut sum = 0u64;
        for i in 0..s.id.len() {
            holder.id = s.id[i];
            holder.price_ticks = s.price_ticks[i];
            holder.quantity = s.quantity[i];
            holder.flags = s.flags[i];
            sum = sum
                .wrapping_add(holder.id)
                .wrapping_add(holder.price_ticks)
                .wrapping_add(holder.quantity as u64)
                .wrapping_add(holder.flags as u64);
        }
        sum
    }

    #[allow(unused_assignments)] // the initial 0 is a placeholder, overwritten by the loop's first iteration
    pub fn stream_tree(value: &[u64]) -> u64 {
        let mut holder: u64 = 0;
        let mut sum = 0u64;
        for &v in value {
            holder = v;
            sum = sum.wrapping_add(holder);
        }
        sum
    }

    struct TupleHolder {
        x: i32,
        y: i32,
    }

    pub fn stream_tuples(s: &TuplesSource) -> u64 {
        let mut holder = TupleHolder { x: 0, y: 0 };
        let mut sum = 0u64;
        for i in 0..s.x.len() {
            holder.x = s.x[i];
            holder.y = s.y[i];
            sum = sum.wrapping_add((holder.x + holder.y) as u64);
        }
        sum
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn orders_quotes_all_variants_agree() {
        let s = generate_orders(ORDERS_N);
        assert_eq!(s.id[0], 0);
        assert_eq!(s.price_ticks[0], 7_834_695);
        assert_eq!(s.quantity[0], 5558);
        assert_eq!(s.flags[0], 6);

        let expected = expected_orders_checksum(&s);
        assert_eq!(expected, 1_022_629_873_049);

        assert_eq!(
            boxed_object_graph::sum_orders(&boxed_object_graph::build_orders(&s)),
            expected
        );
        assert_eq!(
            flat_primitive_arrays::sum_orders(&flat_primitive_arrays::build_orders(&s)),
            expected
        );
        assert_eq!(
            packed_off_heap_struct::sum_orders(&packed_off_heap_struct::build_orders(&s)),
            expected
        );
        assert_eq!(reused_mutable_holder::stream_orders(&s), expected);
    }

    #[test]
    fn tree_nodes_all_variants_agree() {
        let value = generate_tree_values(TREE_N);
        assert_eq!(value[0], 327_111);
        assert_eq!(value[1], 663_972);

        let expected = expected_tree_checksum(&value);
        assert_eq!(expected, 100_091_314_403);

        assert_eq!(
            boxed_object_graph::sum_tree(&boxed_object_graph::build_tree(&value)),
            expected
        );
        assert_eq!(
            flat_primitive_arrays::sum_tree(&flat_primitive_arrays::build_tree(&value)),
            expected
        );
        assert_eq!(
            packed_off_heap_struct::sum_tree(&packed_off_heap_struct::build_tree(&value)),
            expected
        );
        assert_eq!(reused_mutable_holder::stream_tree(&value), expected);
    }

    #[test]
    fn small_tuples_all_variants_agree() {
        let s = generate_tuples(TUPLES_N);
        assert_eq!(s.x[0], 22792);
        assert_eq!(s.y[0], 27340);

        let expected = expected_tuples_checksum(&s);
        assert_eq!(expected, 99_994_993_638);

        assert_eq!(
            boxed_object_graph::sum_tuples(&boxed_object_graph::build_tuples(&s)),
            expected
        );
        assert_eq!(
            flat_primitive_arrays::sum_tuples(&flat_primitive_arrays::build_tuples(&s)),
            expected
        );
        assert_eq!(
            packed_off_heap_struct::sum_tuples(&packed_off_heap_struct::build_tuples(&s)),
            expected
        );
        assert_eq!(reused_mutable_holder::stream_tuples(&s), expected);
    }
}
