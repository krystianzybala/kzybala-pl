package pl.kzybala.lab.coload;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A deterministic, single-threaded discrete-event simulation of a load generator driving one
 * FIFO, single-worker server on a virtual (logical) clock — no real sleeping or wall-clock
 * timing, which is what makes this a correctness fixture: every variant's admitted/missed count
 * and response-time totals follow from one recurrence, not from real thread scheduling.
 */
public final class LoadGenSimulator {
    private LoadGenSimulator() {}

    public static Outcome simulate(Variant variant, LoadParams params) {
        return switch (variant) {
            case CLOSED_LOOP -> closedLoop(params);
            case OPEN_LOOP_FIXED_RATE -> openLoop(params, i -> i * params.intervalNanos());
            case POISSON_LIKE_ARRIVALS -> openLoop(params, poissonLikeSchedule(params.intervalNanos()));
            case OMISSION_CORRECTED_RECORDING -> omissionCorrected(params);
            case BURST_SCHEDULE -> openLoop(params, burstSchedule(params.intervalNanos(), params.burstSize()));
        };
    }

    /** Sends only after the previous request completes — response time always equals service time, hiding queueing. */
    private static Outcome closedLoop(LoadParams params) {
        long clock = 0;
        long totalService = 0, totalResponse = 0, maxResponse = 0;
        for (int i = 0; i < params.count(); i++) {
            long serviceTime = params.serviceModel().serviceTimeNanos(i);
            long completion = clock + serviceTime;
            long responseTime = completion - clock; // == serviceTime, always
            totalService += serviceTime;
            totalResponse += responseTime;
            maxResponse = Math.max(maxResponse, responseTime);
            clock = completion;
        }
        return new Outcome(params.count(), params.count(), 0, params.count(), totalService, totalResponse, maxResponse);
    }

    /** Sends at a schedule independent of server state; admits only while under queueCapacity, else "misses" (drops) the schedule. */
    private static Outcome openLoop(LoadParams params, java.util.function.IntToLongFunction intendedSendTime) {
        Deque<Long> inFlightCompletions = new ArrayDeque<>();
        long nextFreeTime = 0;
        int admitted = 0, missed = 0;
        long totalService = 0, totalResponse = 0, maxResponse = 0;

        for (int i = 0; i < params.count(); i++) {
            long sendTime = intendedSendTime.applyAsLong(i);
            while (!inFlightCompletions.isEmpty() && inFlightCompletions.peekFirst() <= sendTime) {
                inFlightCompletions.pollFirst();
            }
            if (inFlightCompletions.size() >= params.queueCapacity()) {
                missed++;
                continue;
            }
            long serviceTime = params.serviceModel().serviceTimeNanos(i);
            long start = Math.max(nextFreeTime, sendTime);
            long completion = start + serviceTime;
            nextFreeTime = completion;
            inFlightCompletions.addLast(completion);

            long responseTime = completion - sendTime;
            admitted++;
            totalService += serviceTime;
            totalResponse += responseTime;
            maxResponse = Math.max(maxResponse, responseTime);
        }
        return new Outcome(params.count(), admitted, missed, admitted, totalService, totalResponse, maxResponse);
    }

    /**
     * Sends like closed loop, but records the Gil-Tene-style corrected sample set: when a stall
     * makes one real response time exceed the intended interval, synthetic samples are added at
     * the intended cadence to represent the requests that should have been sent during the stall
     * but weren't, because the generator was blocked waiting.
     */
    private static Outcome omissionCorrected(LoadParams params) {
        long clock = 0;
        long totalService = 0, totalResponse = 0, maxResponse = 0;
        long recordedSamples = 0;
        long interval = params.intervalNanos();

        for (int i = 0; i < params.count(); i++) {
            long serviceTime = params.serviceModel().serviceTimeNanos(i);
            long completion = clock + serviceTime;
            long responseTime = completion - clock;
            totalService += serviceTime;
            totalResponse += responseTime;
            maxResponse = Math.max(maxResponse, responseTime);
            recordedSamples++; // the one real sample

            if (interval > 0 && responseTime > interval) {
                long missedIntervals = responseTime / interval - 1;
                for (long k = 1; k <= missedIntervals; k++) {
                    long correctedSample = responseTime - k * interval;
                    totalResponse += correctedSample;
                    maxResponse = Math.max(maxResponse, correctedSample);
                    recordedSamples++;
                }
            }
            clock = completion;
        }
        return new Outcome(params.count(), params.count(), 0, recordedSamples, totalService, totalResponse, maxResponse);
    }

    private static java.util.function.IntToLongFunction poissonLikeSchedule(long interval) {
        long[] gapPattern = { interval / 2, interval + interval / 2, (interval * 8) / 10, (interval * 12) / 10, interval };
        return i -> {
            long t = 0;
            for (int k = 0; k < i; k++) {
                t += gapPattern[k % gapPattern.length];
            }
            return t;
        };
    }

    private static java.util.function.IntToLongFunction burstSchedule(long interval, int burstSize) {
        long burstInterval = interval * Math.max(1, burstSize);
        return i -> (i / Math.max(1, burstSize)) * burstInterval;
    }
}
