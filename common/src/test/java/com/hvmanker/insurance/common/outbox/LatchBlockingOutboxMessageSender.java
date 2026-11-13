package com.hvmanker.insurance.common.outbox;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only sender used from {@link OutboxConcurrentPublisherConfig}: blocks on an optional latch
 * before delegating to the real Kafka sender.
 */
final class LatchBlockingOutboxMessageSender implements OutboxMessageSender {

    private final AtomicInteger sendInvocations = new AtomicInteger();
    private final AtomicBoolean enteredSend = new AtomicBoolean();
    private volatile CountDownLatch blockLatch;
    private volatile OutboxMessageSender delegate = record -> {};

    void setDelegate(OutboxMessageSender delegate) {
        this.delegate = delegate;
    }

    void setBlockLatch(CountDownLatch blockLatch) {
        this.blockLatch = blockLatch;
    }

    void clearBlockLatch() {
        this.blockLatch = null;
    }

    int sendInvocations() {
        return sendInvocations.get();
    }

    void resetSendInvocations() {
        sendInvocations.set(0);
        enteredSend.set(false);
    }

    boolean hasEnteredSend() {
        return enteredSend.get();
    }

    @Override
    public void send(OutboxRecord record) throws InterruptedException, ExecutionException, TimeoutException {
        sendInvocations.incrementAndGet();
        enteredSend.set(true);
        CountDownLatch latch = blockLatch;
        if (latch != null) {
            latch.await();
        }
        delegate.send(record);
    }
}
