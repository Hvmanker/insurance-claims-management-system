package com.hvmanker.insurance.approval.integration;

import com.hvmanker.insurance.approval.domain.ApprovalSource;
import com.hvmanker.insurance.approval.service.DecisionRaceHook;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class LatchingDecisionRaceHook extends DecisionRaceHook {
    private CountDownLatch humanEntered = new CountDownLatch(1);
    private CountDownLatch humanRelease = new CountDownLatch(1);
    private CountDownLatch autoEntered = new CountDownLatch(1);
    private CountDownLatch autoRelease = new CountDownLatch(1);

    @Override
    public void beforeConditionalUpdate(ApprovalSource source) {
        try {
            if (source == ApprovalSource.HUMAN) {
                humanEntered.countDown();
                if (!humanRelease.await(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("human release latch timed out");
                }
            } else if (source == ApprovalSource.AUTO) {
                autoEntered.countDown();
                if (!autoRelease.await(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("auto release latch timed out");
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    public CountDownLatch humanEntered() {
        return humanEntered;
    }

    public CountDownLatch humanRelease() {
        return humanRelease;
    }

    public CountDownLatch autoEntered() {
        return autoEntered;
    }

    public CountDownLatch autoRelease() {
        return autoRelease;
    }

    public void resetForNextRun() {
        humanEntered = new CountDownLatch(1);
        humanRelease = new CountDownLatch(1);
        autoEntered = new CountDownLatch(1);
        autoRelease = new CountDownLatch(1);
    }
}
