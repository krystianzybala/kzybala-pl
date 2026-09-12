//! Deterministic record generation and the four layouts (AoS heap
//! objects, AoS packed `#[repr(C)]`, SoA primitive vectors, hybrid
//! hot/cold split) for the "Array of Structures vs Structure of Arrays"
//! lab — the cross-language equivalence contract
//! (../fixtures/aos-vs-soa-fixtures.json). Every record has two "hot"
//! fields and a dataset-specific number of "cold" fields; every layout of
//! a given dataset must reproduce the identical `hot_sum` — layout
//! changes storage, never the data. The only `unsafe` in this lab is the
//! worker-affinity syscalls in `src/bin/aos_soa_evidence.rs`; this crate
//! is safe Rust throughout.

pub const N: usize = 1_000_000;
pub const SEED: u64 = 42;
pub const HOT_DOMAIN: u64 = 1_000_000;

#[inline]
pub fn xorshift64(mut x: u64) -> u64 {
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    x
}

pub struct Generated {
    pub hot_a: Vec<i64>,
    pub hot_b: Vec<i64>,
    pub cold: Vec<Vec<i64>>, // [record][cold word]
}

/// Generation order per record: hotA, hotB, then coldWords cold values.
pub fn generate(cold_words: usize, seed: u64, n: usize) -> Generated {
    let mut hot_a = Vec::with_capacity(n);
    let mut hot_b = Vec::with_capacity(n);
    let mut cold = Vec::with_capacity(n);
    let mut x = seed;
    for _ in 0..n {
        x = xorshift64(x);
        hot_a.push((x % HOT_DOMAIN) as i64);
        x = xorshift64(x);
        hot_b.push((x % HOT_DOMAIN) as i64);
        let mut row = Vec::with_capacity(cold_words);
        for _ in 0..cold_words {
            x = xorshift64(x);
            row.push((x % HOT_DOMAIN) as i64);
        }
        cold.push(row);
    }
    Generated { hot_a, hot_b, cold }
}

pub fn checksum(values: &[i64]) -> i64 {
    let mut c: i64 = 0;
    for &v in values {
        c = c.wrapping_mul(31).wrapping_add(v);
    }
    c
}

/// Row-major (record then cold word) checksum — proves no data loss across layouts.
pub fn checksum_cold(cold: &[Vec<i64>]) -> i64 {
    let mut c: i64 = 0;
    for row in cold {
        for &v in row {
            c = c.wrapping_mul(31).wrapping_add(v);
        }
    }
    c
}

/// The layout-invariance oracle: wrapping sum of (hotA[i] + hotB[i]) over all records.
pub fn expected_hot_sum(hot_a: &[i64], hot_b: &[i64]) -> i64 {
    let mut sum: i64 = 0;
    for i in 0..hot_a.len() {
        sum = sum.wrapping_add(hot_a[i]).wrapping_add(hot_b[i]);
    }
    sum
}

// --- Variant 1: AoS heap objects ---------------------------------------

/// Each record owns its own heap-allocated cold payload — mirrors the
/// Java `AosHeapLayout.Record`'s per-record `long[]` indirection.
pub struct AosHeapRecord {
    pub hot_a: i64,
    pub hot_b: i64,
    pub cold: Vec<i64>,
}

pub struct AosHeapLayout {
    records: Vec<AosHeapRecord>,
}

impl AosHeapLayout {
    pub fn of(data: &Generated) -> Self {
        let n = data.hot_a.len();
        let mut records = Vec::with_capacity(n);
        for i in 0..n {
            records.push(AosHeapRecord {
                hot_a: data.hot_a[i],
                hot_b: data.hot_b[i],
                cold: data.cold[i].clone(),
            });
        }
        AosHeapLayout { records }
    }

    /// One operation = one full pass touching only the hot fields.
    pub fn sum_hot(&self) -> i64 {
        let mut sum: i64 = 0;
        for r in &self.records {
            sum = sum.wrapping_add(r.hot_a).wrapping_add(r.hot_b);
        }
        sum
    }

    pub fn cold_checksum(&self) -> i64 {
        let mut c: i64 = 0;
        for r in &self.records {
            for &v in &r.cold {
                c = c.wrapping_mul(31).wrapping_add(v);
            }
        }
        c
    }
}

// --- Variant 2: AoS packed, #[repr(C)] ----------------------------------

/// One contiguous, fixed-stride record — no allocator indirection, same
/// field order and stride as the Java `AosPackedLayout` (off-heap
/// `MemorySegment`). `COLD` is a compile-time constant per dataset (2, 4
/// or 8 in this lab), which is what makes this a genuine `#[repr(C)]`
/// struct rather than a runtime-sized byte layout.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct AosPackedRecord<const COLD: usize> {
    pub hot_a: i64,
    pub hot_b: i64,
    pub cold: [i64; COLD],
}

pub struct AosPackedLayout<const COLD: usize> {
    records: Vec<AosPackedRecord<COLD>>,
}

impl<const COLD: usize> AosPackedLayout<COLD> {
    pub fn of(data: &Generated) -> Self {
        let n = data.hot_a.len();
        let mut records = Vec::with_capacity(n);
        for i in 0..n {
            let mut cold = [0i64; COLD];
            cold.copy_from_slice(&data.cold[i]);
            records.push(AosPackedRecord {
                hot_a: data.hot_a[i],
                hot_b: data.hot_b[i],
                cold,
            });
        }
        AosPackedLayout { records }
    }

    pub fn record_stride_bytes(&self) -> usize {
        std::mem::size_of::<AosPackedRecord<COLD>>()
    }

    pub fn total_bytes(&self) -> usize {
        self.record_stride_bytes() * self.records.len()
    }

    /// One operation = one full pass touching only the hot fields.
    pub fn sum_hot(&self) -> i64 {
        let mut sum: i64 = 0;
        for r in &self.records {
            sum = sum.wrapping_add(r.hot_a).wrapping_add(r.hot_b);
        }
        sum
    }

    pub fn cold_checksum(&self) -> i64 {
        let mut c: i64 = 0;
        for r in &self.records {
            for &v in &r.cold {
                c = c.wrapping_mul(31).wrapping_add(v);
            }
        }
        c
    }
}

// --- Variant 3: SoA primitive vectors -----------------------------------

/// Every field gets its own dense vector — `hot_a`/`hot_b` are each fully
/// contiguous, so a hot-field-only scan streams two dense vectors and
/// never touches a cold value.
pub struct SoaLayout {
    hot_a: Vec<i64>,
    hot_b: Vec<i64>,
    cold: Vec<i64>, // flat, row-major: record*cold_words + word
    cold_words: usize,
}

impl SoaLayout {
    pub fn of(data: &Generated, cold_words: usize) -> Self {
        let n = data.hot_a.len();
        let mut cold = Vec::with_capacity(n * cold_words);
        for row in &data.cold {
            cold.extend_from_slice(row);
        }
        SoaLayout {
            hot_a: data.hot_a.clone(),
            hot_b: data.hot_b.clone(),
            cold,
            cold_words,
        }
    }

    pub fn total_bytes(&self) -> usize {
        (self.hot_a.len() + self.hot_b.len() + self.cold.len()) * 8
    }

    /// One operation = one full pass touching only the two hot arrays.
    pub fn sum_hot(&self) -> i64 {
        let mut sum: i64 = 0;
        for i in 0..self.hot_a.len() {
            sum = sum.wrapping_add(self.hot_a[i]).wrapping_add(self.hot_b[i]);
        }
        sum
    }

    pub fn cold_checksum(&self) -> i64 {
        let mut c: i64 = 0;
        let n = self.hot_a.len();
        for i in 0..n {
            let base = i * self.cold_words;
            for w in 0..self.cold_words {
                c = c.wrapping_mul(31).wrapping_add(self.cold[base + w]);
            }
        }
        c
    }
}

// --- Variant 4: hybrid hot/cold split ------------------------------------

/// The two hot fields are packed together, interleaved, in one dense
/// vector (`hot_pairs[2*i]=hot_a[i]`, `hot_pairs[2*i+1]=hot_b[i]`) — a
/// mini-AoS of just the hot fields — while cold data lives in a fully
/// separate region.
pub struct HybridLayout {
    hot_pairs: Vec<i64>, // [2*i]=hot_a[i], [2*i+1]=hot_b[i]
    cold: Vec<i64>,
    cold_words: usize,
}

impl HybridLayout {
    pub fn of(data: &Generated, cold_words: usize) -> Self {
        let n = data.hot_a.len();
        let mut hot_pairs = Vec::with_capacity(n * 2);
        let mut cold = Vec::with_capacity(n * cold_words);
        for i in 0..n {
            hot_pairs.push(data.hot_a[i]);
            hot_pairs.push(data.hot_b[i]);
            cold.extend_from_slice(&data.cold[i]);
        }
        HybridLayout {
            hot_pairs,
            cold,
            cold_words,
        }
    }

    pub fn total_bytes(&self) -> usize {
        (self.hot_pairs.len() + self.cold.len()) * 8
    }

    /// One operation = one full pass over the single interleaved hot stream.
    pub fn sum_hot(&self) -> i64 {
        let mut sum: i64 = 0;
        let mut i = 0;
        while i < self.hot_pairs.len() {
            sum = sum
                .wrapping_add(self.hot_pairs[i])
                .wrapping_add(self.hot_pairs[i + 1]);
            i += 2;
        }
        sum
    }

    pub fn cold_checksum(&self) -> i64 {
        let mut c: i64 = 0;
        let n = self.hot_pairs.len() / 2;
        for i in 0..n {
            let base = i * self.cold_words;
            for w in 0..self.cold_words {
                c = c.wrapping_mul(31).wrapping_add(self.cold[base + w]);
            }
        }
        c
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn market_quotes_generation_matches_the_fixture() {
        let g = generate(4, SEED, N);
        assert_eq!(checksum(&g.hot_a), -8491869883421422093);
        assert_eq!(checksum(&g.hot_b), 1964254207014998244);
        assert_eq!(&g.hot_a[..5], &[805674, 29075, 418483, 94591, 868695]);
        assert_eq!(&g.hot_b[..5], &[905471, 704114, 552213, 125452, 853729]);
        assert_eq!(checksum_cold(&g.cold), -2526446158705005686);
        assert_eq!(&g.cold[0], &[320954, 629736, 84162, 275427]);
        assert_eq!(expected_hot_sum(&g.hot_a, &g.hot_b), 999610704015);
    }

    #[test]
    fn positions_generation_matches_the_fixture() {
        let g = generate(8, SEED, N);
        assert_eq!(checksum(&g.hot_a), -6590571356256859184);
        assert_eq!(checksum(&g.hot_b), 458225929394024333);
        assert_eq!(checksum_cold(&g.cold), -3846297679626790457);
        assert_eq!(expected_hot_sum(&g.hot_a, &g.hot_b), 999893110961);
    }

    #[test]
    fn spatial_points_generation_matches_the_fixture() {
        let g = generate(2, SEED, N);
        assert_eq!(checksum(&g.hot_a), 9215335623524743799u64 as i64);
        assert_eq!(checksum(&g.hot_b), -4924883481048905347);
        assert_eq!(checksum_cold(&g.cold), 3239977018108460599u64 as i64);
        assert_eq!(expected_hot_sum(&g.hot_a, &g.hot_b), 1000104865222);
    }

    #[test]
    fn all_four_layouts_agree_for_market_quotes() {
        let g = generate(4, SEED, N);
        let expected_hot_sum = expected_hot_sum(&g.hot_a, &g.hot_b);
        let expected_cold_checksum = checksum_cold(&g.cold);

        let heap = AosHeapLayout::of(&g);
        assert_eq!(heap.sum_hot(), expected_hot_sum);
        assert_eq!(heap.cold_checksum(), expected_cold_checksum);

        let soa = SoaLayout::of(&g, 4);
        assert_eq!(soa.sum_hot(), expected_hot_sum);
        assert_eq!(soa.cold_checksum(), expected_cold_checksum);

        let hybrid = HybridLayout::of(&g, 4);
        assert_eq!(hybrid.sum_hot(), expected_hot_sum);
        assert_eq!(hybrid.cold_checksum(), expected_cold_checksum);

        let packed = AosPackedLayout::<4>::of(&g);
        assert_eq!(packed.sum_hot(), expected_hot_sum);
        assert_eq!(packed.cold_checksum(), expected_cold_checksum);
        assert_eq!(packed.record_stride_bytes(), 48);
    }

    #[test]
    fn all_four_layouts_agree_for_positions() {
        let g = generate(8, SEED, N);
        let expected_hot_sum = expected_hot_sum(&g.hot_a, &g.hot_b);
        let expected_cold_checksum = checksum_cold(&g.cold);

        assert_eq!(AosHeapLayout::of(&g).sum_hot(), expected_hot_sum);
        assert_eq!(SoaLayout::of(&g, 8).sum_hot(), expected_hot_sum);
        assert_eq!(HybridLayout::of(&g, 8).sum_hot(), expected_hot_sum);
        let packed = AosPackedLayout::<8>::of(&g);
        assert_eq!(packed.sum_hot(), expected_hot_sum);
        assert_eq!(packed.cold_checksum(), expected_cold_checksum);
        assert_eq!(packed.record_stride_bytes(), 80);
    }

    #[test]
    fn all_four_layouts_agree_for_spatial_points() {
        let g = generate(2, SEED, N);
        let expected_hot_sum = expected_hot_sum(&g.hot_a, &g.hot_b);
        let expected_cold_checksum = checksum_cold(&g.cold);

        assert_eq!(AosHeapLayout::of(&g).sum_hot(), expected_hot_sum);
        assert_eq!(SoaLayout::of(&g, 2).sum_hot(), expected_hot_sum);
        assert_eq!(HybridLayout::of(&g, 2).sum_hot(), expected_hot_sum);
        let packed = AosPackedLayout::<2>::of(&g);
        assert_eq!(packed.sum_hot(), expected_hot_sum);
        assert_eq!(packed.cold_checksum(), expected_cold_checksum);
        assert_eq!(packed.record_stride_bytes(), 32);
    }
}
