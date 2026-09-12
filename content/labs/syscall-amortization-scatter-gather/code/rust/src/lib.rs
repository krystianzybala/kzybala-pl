//! Syscall amortization and scatter/gather I/O over TCP loopback
//! (127.0.0.1) — this lab tests exclusively over loopback and labels
//! that fact everywhere a result appears. Wire format identical to the
//! Java side: a 16-byte header (8-byte LE sequence + 8-byte LE payload
//! length) followed by a deterministic variable-length payload.

use std::io::{IoSlice, Read, Result as IoResult, Write};
use std::net::{TcpListener, TcpStream};

pub const HEADER_SIZE: usize = 16;

pub fn build_header(sequence: u64, payload_length: u64) -> Vec<u8> {
    let mut header = vec![0u8; HEADER_SIZE];
    header[0..8].copy_from_slice(&sequence.to_le_bytes());
    header[8..16].copy_from_slice(&payload_length.to_le_bytes());
    header
}

pub fn build_payload(sequence: u64, length: usize) -> Vec<u8> {
    (0..length)
        .map(|i| ((sequence.wrapping_add(i as u64)) & 0xFF) as u8)
        .collect()
}

pub fn read_u64(buf: &[u8]) -> u64 {
    u64::from_le_bytes(buf[0..8].try_into().unwrap())
}

pub fn payload_matches(payload: &[u8], sequence: u64) -> bool {
    payload
        .iter()
        .enumerate()
        .all(|(i, &b)| b == ((sequence.wrapping_add(i as u64)) & 0xFF) as u8)
}

/// `write_calls` counts application-level `write`/`write_vectored`
/// invocations — an honest PROXY for kernel crossings, never a verified
/// syscall count. See the "counting application calls instead of
/// syscalls" trap; real syscall counts require strace on the
/// native-Linux evidence host.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WriteAccounting {
    pub write_calls: u64,
    pub bytes_written: u64,
    pub partial_writes: u64,
    pub messages_sent: u64,
}

/// A connected pair of `TcpStream`s over loopback — every benchmark and
/// test in this lab uses loopback only.
pub struct LoopbackConnection {
    pub client: TcpStream,
    pub server: TcpStream,
}

impl LoopbackConnection {
    pub fn open() -> IoResult<Self> {
        let listener = TcpListener::bind("127.0.0.1:0")?;
        let port = listener.local_addr()?.port();
        let client = TcpStream::connect(("127.0.0.1", port))?;
        let (server, _) = listener.accept()?;
        Ok(LoopbackConnection { client, server })
    }
}

/// Writes `buffers` fully via repeated `write_vectored` calls, correctly
/// handling partial writes by rebuilding the `IoSlice` array from each
/// buffer's own remaining-offset state every iteration (avoiding
/// `IoSlice`'s borrow-lifetime constraints on in-place slicing).
fn write_vectored_all(stream: &mut TcpStream, buffers: &[Vec<u8>]) -> IoResult<WriteAccounting> {
    let mut offsets = vec![0usize; buffers.len()];
    let mut write_calls = 0u64;
    let mut bytes_written = 0u64;
    let mut partial_writes = 0u64;

    loop {
        let slices: Vec<IoSlice> = buffers
            .iter()
            .zip(offsets.iter())
            .filter(|(b, &off)| off < b.len())
            .map(|(b, &off)| IoSlice::new(&b[off..]))
            .collect();
        if slices.is_empty() {
            break;
        }
        let total_remaining: usize = slices.iter().map(|s| s.len()).sum();
        let n = stream.write_vectored(&slices)?;
        write_calls += 1;
        bytes_written += n as u64;
        if n < total_remaining {
            partial_writes += 1;
        }
        let mut remaining = n;
        for (b, off) in buffers.iter().zip(offsets.iter_mut()) {
            if *off >= b.len() {
                continue;
            }
            let avail = b.len() - *off;
            let take = remaining.min(avail);
            *off += take;
            remaining -= take;
            if remaining == 0 {
                break;
            }
        }
    }

    Ok(WriteAccounting {
        write_calls,
        bytes_written,
        partial_writes,
        messages_sent: 0, // filled in by the caller
    })
}

fn write_all_counted(stream: &mut TcpStream, buf: &[u8]) -> IoResult<(u64, u64, u64)> {
    let mut written = 0usize;
    let mut write_calls = 0u64;
    let mut partial_writes = 0u64;
    while written < buf.len() {
        let n = stream.write(&buf[written..])?;
        write_calls += 1;
        if written + n < buf.len() {
            partial_writes += 1;
        }
        written += n;
    }
    Ok((write_calls, written as u64, partial_writes))
}

/// Variant: single small write — two separate `write()` calls per message (header, then payload).
pub fn single_small_write(
    stream: &mut TcpStream,
    message_count: u64,
    payload_size: usize,
) -> IoResult<WriteAccounting> {
    let mut write_calls = 0u64;
    let mut bytes_written = 0u64;
    let mut partial_writes = 0u64;
    for seq in 0..message_count {
        let header = build_header(seq, payload_size as u64);
        let payload = build_payload(seq, payload_size);
        for buf in [&header, &payload] {
            let (wc, bw, pw) = write_all_counted(stream, buf)?;
            write_calls += wc;
            bytes_written += bw;
            partial_writes += pw;
        }
    }
    Ok(WriteAccounting {
        write_calls,
        bytes_written,
        partial_writes,
        messages_sent: message_count,
    })
}

/// Variant: coalesced buffer — header and payload copied into ONE buffer, one `write()` call per message.
pub fn coalesced_buffer(
    stream: &mut TcpStream,
    message_count: u64,
    payload_size: usize,
) -> IoResult<WriteAccounting> {
    let mut write_calls = 0u64;
    let mut bytes_written = 0u64;
    let mut partial_writes = 0u64;
    for seq in 0..message_count {
        let header = build_header(seq, payload_size as u64);
        let payload = build_payload(seq, payload_size);
        let mut combined = Vec::with_capacity(header.len() + payload.len()); // the extra copy this variant pays for
        combined.extend_from_slice(&header);
        combined.extend_from_slice(&payload);
        let (wc, bw, pw) = write_all_counted(stream, &combined)?;
        write_calls += wc;
        bytes_written += bw;
        partial_writes += pw;
    }
    Ok(WriteAccounting {
        write_calls,
        bytes_written,
        partial_writes,
        messages_sent: message_count,
    })
}

/// Variant: scatter/gather write — header and payload written from TWO separate buffers in one `write_vectored` call.
pub fn scatter_gather_write(
    stream: &mut TcpStream,
    message_count: u64,
    payload_size: usize,
) -> IoResult<WriteAccounting> {
    let mut total = WriteAccounting {
        write_calls: 0,
        bytes_written: 0,
        partial_writes: 0,
        messages_sent: message_count,
    };
    for seq in 0..message_count {
        let header = build_header(seq, payload_size as u64);
        let payload = build_payload(seq, payload_size);
        let result = write_vectored_all(stream, &[header, payload])?;
        total.write_calls += result.write_calls;
        total.bytes_written += result.bytes_written;
        total.partial_writes += result.partial_writes;
    }
    Ok(total)
}

/// Variant: size-bounded batch — accumulates `batch_size` messages' header+payload
/// buffers, then issues ONE `write_vectored` call covering the entire batch.
pub fn size_bounded_batch(
    stream: &mut TcpStream,
    message_count: u64,
    payload_size: usize,
    batch_size: u64,
) -> IoResult<WriteAccounting> {
    let mut total = WriteAccounting {
        write_calls: 0,
        bytes_written: 0,
        partial_writes: 0,
        messages_sent: message_count,
    };
    let mut seq = 0u64;
    while seq < message_count {
        let n = batch_size.min(message_count - seq);
        let mut buffers = Vec::with_capacity((n * 2) as usize);
        for i in 0..n {
            buffers.push(build_header(seq + i, payload_size as u64));
            buffers.push(build_payload(seq + i, payload_size));
        }
        let result = write_vectored_all(stream, &buffers)?;
        total.write_calls += result.write_calls;
        total.bytes_written += result.bytes_written;
        total.partial_writes += result.partial_writes;
        seq += n;
    }
    Ok(total)
}

fn read_exact_counted(stream: &mut TcpStream, buf: &mut [u8]) -> IoResult<()> {
    stream.read_exact(buf)
}

/// Reads `message_count` messages and validates each. `per_message_delay_millis > 0`
/// deliberately slows the receiver to induce real TCP backpressure (and
/// therefore real partial writes on the sender side) — used only by the
/// "backpressured receiver" scenario, never by the other four variants.
pub fn receive(
    stream: &mut TcpStream,
    message_count: u64,
    per_message_delay_millis: u64,
) -> IoResult<u64> {
    let mut delivered = 0u64;
    let mut header = [0u8; HEADER_SIZE];
    for _ in 0..message_count {
        read_exact_counted(stream, &mut header)?;
        let seq = read_u64(&header[0..8]);
        let payload_len = read_u64(&header[8..16]) as usize;
        let mut payload = vec![0u8; payload_len];
        read_exact_counted(stream, &mut payload)?;
        if payload_matches(&payload, seq) {
            delivered += 1;
        }
        if per_message_delay_millis > 0 {
            std::thread::sleep(std::time::Duration::from_millis(per_message_delay_millis));
        }
    }
    Ok(delivered)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::thread;

    #[test]
    fn message_format_round_trips_sequence_and_payload() {
        for &size in &[8usize, 64, 256, 1024] {
            for &seq in &[0u64, 1, 255, 256, 100_000] {
                let header = build_header(seq, size as u64);
                assert_eq!(read_u64(&header[0..8]), seq);
                assert_eq!(read_u64(&header[8..16]), size as u64);
                let payload = build_payload(seq, size);
                assert!(payload_matches(&payload, seq));
            }
        }
    }

    #[test]
    fn single_small_write_delivers_every_message() {
        let message_count = 2000u64;
        let payload_size = 256usize;
        let conn = LoopbackConnection::open().unwrap();
        let mut server = conn.server;
        let mut client = conn.client;
        let receiver = thread::spawn(move || receive(&mut server, message_count, 0).unwrap());
        let accounting = single_small_write(&mut client, message_count, payload_size).unwrap();
        let delivered = receiver.join().unwrap();
        assert_eq!(delivered, message_count);
        assert_eq!(accounting.write_calls, message_count * 2);
    }

    #[test]
    fn coalesced_buffer_delivers_every_message_with_one_write_call_per_message() {
        let message_count = 2000u64;
        let payload_size = 256usize;
        let conn = LoopbackConnection::open().unwrap();
        let mut server = conn.server;
        let mut client = conn.client;
        let receiver = thread::spawn(move || receive(&mut server, message_count, 0).unwrap());
        let accounting = coalesced_buffer(&mut client, message_count, payload_size).unwrap();
        let delivered = receiver.join().unwrap();
        assert_eq!(delivered, message_count);
        assert_eq!(accounting.write_calls, message_count);
    }

    #[test]
    fn scatter_gather_write_delivers_every_message_with_one_write_call_per_message() {
        let message_count = 2000u64;
        let payload_size = 256usize;
        let conn = LoopbackConnection::open().unwrap();
        let mut server = conn.server;
        let mut client = conn.client;
        let receiver = thread::spawn(move || receive(&mut server, message_count, 0).unwrap());
        let accounting = scatter_gather_write(&mut client, message_count, payload_size).unwrap();
        let delivered = receiver.join().unwrap();
        assert_eq!(delivered, message_count);
        assert_eq!(accounting.write_calls, message_count);
    }

    #[test]
    fn size_bounded_batch_delivers_every_message_with_far_fewer_write_calls_than_messages() {
        let message_count = 2000u64;
        let payload_size = 64usize;
        let batch_size = 20u64;
        let conn = LoopbackConnection::open().unwrap();
        let mut server = conn.server;
        let mut client = conn.client;
        let receiver = thread::spawn(move || receive(&mut server, message_count, 0).unwrap());
        let accounting =
            size_bounded_batch(&mut client, message_count, payload_size, batch_size).unwrap();
        let delivered = receiver.join().unwrap();
        assert_eq!(delivered, message_count);
        assert!(accounting.write_calls <= message_count / batch_size + 5);
    }

    #[test]
    fn backpressured_receiver_still_delivers_every_message_despite_induced_partial_writes() {
        let message_count = 300u64;
        let payload_size = 4096usize; // large, with a slow receiver, to fill the socket send buffer
        let conn = LoopbackConnection::open().unwrap();
        let mut server = conn.server;
        let mut client = conn.client;
        let receiver = thread::spawn(move || receive(&mut server, message_count, 2).unwrap());
        let accounting = scatter_gather_write(&mut client, message_count, payload_size).unwrap();
        let delivered = receiver.join().unwrap();
        assert_eq!(delivered, message_count);
        assert_eq!(
            accounting.bytes_written,
            message_count * (HEADER_SIZE as u64 + payload_size as u64)
        );
    }
}
