//! Rust counterpart of `CoReplayHarness`: replays the deterministic
//! pause-injection or burst dataset through naive and corrected/response
//! recording. Distribution content is fixture-exact (verified before
//! printing); only the recording wall time is a host measurement. Exits
//! non-zero on any fixture deviation.

use clocks_latency_lab::{
    bimodal_sequence, burst_response_times, pause_injected_sequence, record_all, record_corrected,
    Percentiles,
};
use std::time::Instant;

fn pjson(p: &Percentiles) -> String {
    format!(
        "{{\"count\":{},\"p50\":{},\"p95\":{},\"p99\":{},\"p999\":{},\"max\":{}}}",
        p.count, p.p50, p.p95, p.p99, p.p999, p.max
    )
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let mut dataset = "pause".to_string();
    let mut i = 1;
    while i + 1 < args.len() {
        match args[i].as_str() {
            "--dataset" => dataset = args[i + 1].clone(),
            other => panic!("unknown option {other}"),
        }
        i += 2;
    }

    let (first_name, second_name, first_p, second_p, wall, fixtures_ok) = match dataset.as_str() {
        "pause" => {
            let seq = pause_injected_sequence(42, 100_000);
            let t0 = Instant::now();
            let naive = record_all(&seq);
            let corrected = record_corrected(&seq, 1000);
            let wall = t0.elapsed().as_nanos();
            let np = Percentiles::of(&naive);
            let cp = Percentiles::of(&corrected);
            let ok = np.p99 == 56223 && cp.p999 == 4_997_119 && cp.count == 837_236;
            ("naive", "corrected", np, cp, wall, ok)
        }
        "burst" => {
            let service = bimodal_sequence(42, 100_000);
            let response = burst_response_times(42, 100_000, 10, 50_000);
            let t0 = Instant::now();
            let sh = record_all(&service);
            let rh = record_all(&response);
            let wall = t0.elapsed().as_nanos();
            let sp = Percentiles::of(&sh);
            let rp = Percentiles::of(&rh);
            let ok = sp.p50 == 1009 && rp.p50 == 21023 && rp.p999 == 316_159;
            ("serviceTime", "responseTime", sp, rp, wall, ok)
        }
        other => panic!("unknown --dataset {other} (pause|burst)"),
    };

    println!("{{");
    println!("  \"harness\": \"co_replay (deterministic distribution replay, Rust)\",");
    println!("  \"dataset\": \"{dataset}\",");
    println!("  \"{first_name}\": {},", pjson(&first_p));
    println!("  \"{second_name}\": {},", pjson(&second_p));
    println!("  \"recordingWallNanosFor200kValues\": {wall},");
    println!("  \"fixtureExact\": {fixtures_ok},");
    println!("  \"note\": \"distribution content is deterministic (shared fixtures); only recordingWallNanos is a host measurement\"");
    println!("}}");
    if !fixtures_ok {
        std::process::exit(1);
    }
}
