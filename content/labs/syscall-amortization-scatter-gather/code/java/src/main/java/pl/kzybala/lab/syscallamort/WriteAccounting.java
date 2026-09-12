package pl.kzybala.lab.syscallamort;

/**
 * {@code writeCalls} counts application-level {@code SocketChannel.write}
 * invocations — an honest PROXY for kernel crossings, never a verified
 * syscall count. A single {@code write()} call is, in the common case,
 * one system call, but this application-level code cannot itself prove
 * that (TCP/Nagle behavior, OS-level coalescing, or JIT/runtime
 * particulars could in principle change the correspondence) — see the
 * "counting application calls instead of syscalls" trap. Real syscall
 * counts require an external tool (strace on Linux) applied to the
 * native-Linux evidence run, not this in-process count.
 */
public record WriteAccounting(long writeCalls, long bytesWritten, long partialWrites, long messagesSent) {
}
