package com.peerlink.agent.presence;

import static org.assertj.core.api.Assertions.assertThat;

import com.peerlink.agent.config.AgentCredential;
import com.peerlink.agent.presence.HeartbeatLoop.StopReason;
import com.peerlink.agent.presence.HeartbeatResult.Outcome;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HeartbeatLoopTests {

    private static final UUID PEER_ID = UUID.randomUUID();
    private static final AgentCredential CREDENTIAL = AgentCredential.of("plk_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG");
    private static final Duration INTERVAL = Duration.ofMillis(20);
    private static final BackoffPolicy FAST_BACKOFF = new BackoffPolicy(Duration.ofMillis(5), Duration.ofMillis(40));

    private HeartbeatLoop loop;

    @AfterEach
    void closeLoop() {
        if (loop != null) {
            loop.close();
        }
    }

    @Test
    void sendsHeartbeatsRepeatedlyWhileAccepted() throws InterruptedException {
        ScriptedClient client = new ScriptedClient(() -> accepted());
        loop = start(client);

        assertThat(client.awaitCalls(5)).isTrue();
        assertThat(loop.isStopped()).isFalse();
    }

    @Test
    void networkFailuresAndExceptionsDoNotStopTheLoop() throws InterruptedException {
        ScriptedClient client = new ScriptedClient(() -> accepted());
        client.enqueue(HeartbeatResult::unreachable);
        client.enqueue(() -> new HeartbeatResult(Outcome.SERVER_ERROR, 503, null));
        client.enqueue(() -> {
            throw new IllegalStateException("simulated client bug");
        });
        loop = start(client);

        // Three failures followed by successes: the loop keeps going throughout.
        assertThat(client.awaitCalls(6)).isTrue();
        assertThat(loop.isStopped()).isFalse();
    }

    @Test
    void rejectedCredentialStopsTheLoopForGood() throws InterruptedException {
        ScriptedClient client = new ScriptedClient(() -> new HeartbeatResult(Outcome.REJECTED, 401, null));
        loop = start(client);

        assertThat(loop.awaitStop()).isEqualTo(StopReason.CREDENTIAL_REJECTED);
        Thread.sleep(100);
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void closeTerminatesSchedulingCleanly() throws InterruptedException {
        ScriptedClient client = new ScriptedClient(() -> accepted());
        loop = start(client);
        assertThat(client.awaitCalls(2)).isTrue();

        loop.close();

        assertThat(loop.isTerminated()).isTrue();
        assertThat(loop.awaitStop()).isEqualTo(StopReason.SHUTDOWN);
        int callsAtClose = client.calls.get();
        Thread.sleep(100);
        assertThat(client.calls.get()).isEqualTo(callsAtClose);
    }

    @Test
    void reportsTheListenerPortWithEveryHeartbeat() throws InterruptedException {
        ScriptedClient client = new ScriptedClient(() -> accepted());
        loop = new HeartbeatLoop(client, PEER_ID, CREDENTIAL, INTERVAL, FAST_BACKOFF,
                Executors.newSingleThreadScheduledExecutor(), 9444);
        loop.start();

        assertThat(client.awaitCalls(2)).isTrue();
        assertThat(client.lastReportedPort).isEqualTo(9444);
    }

    private HeartbeatLoop start(PresenceClient client) {
        HeartbeatLoop heartbeatLoop = new HeartbeatLoop(client, PEER_ID, CREDENTIAL, INTERVAL, FAST_BACKOFF,
                Executors.newSingleThreadScheduledExecutor());
        heartbeatLoop.start();
        return heartbeatLoop;
    }

    private static HeartbeatResult accepted() {
        return new HeartbeatResult(Outcome.ACCEPTED, 200, "192.168.1.20");
    }

    /** Returns queued results first, then the default; counts every call. */
    private static final class ScriptedClient implements PresenceClient {

        private final Deque<Supplier<HeartbeatResult>> script = new ArrayDeque<>();
        private final Supplier<HeartbeatResult> fallback;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile CountDownLatch target = new CountDownLatch(0);
        private volatile Integer lastReportedPort;

        ScriptedClient(Supplier<HeartbeatResult> fallback) {
            this.fallback = fallback;
        }

        synchronized void enqueue(Supplier<HeartbeatResult> result) {
            script.add(result);
        }

        boolean awaitCalls(int count) throws InterruptedException {
            synchronized (this) {
                target = new CountDownLatch(Math.max(count - calls.get(), 0));
            }
            return target.await(5, TimeUnit.SECONDS);
        }

        @Override
        public HeartbeatResult sendHeartbeat(UUID peerId, AgentCredential credential, Integer listeningPort) {
            Supplier<HeartbeatResult> next;
            synchronized (this) {
                lastReportedPort = listeningPort;
                calls.incrementAndGet();
                target.countDown();
                next = script.isEmpty() ? fallback : script.poll();
            }
            return next.get();
        }
    }
}
