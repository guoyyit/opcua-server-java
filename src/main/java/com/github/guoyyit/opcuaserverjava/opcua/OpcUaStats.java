package com.github.guoyyit.opcuaserverjava.opcua;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Live server resource counters used for loopback verification.
 */
@Component
public class OpcUaStats {

    private final AtomicInteger channels = new AtomicInteger();
    private final AtomicInteger sessions = new AtomicInteger();
    private final AtomicInteger subscriptions = new AtomicInteger();
    private final AtomicInteger monitoredItems = new AtomicInteger();
    private final AtomicInteger pendingPublishes = new AtomicInteger();

    public int channelCount() {
        return channels.get();
    }

    public int sessionCount() {
        return sessions.get();
    }

    public int subscriptionCount() {
        return subscriptions.get();
    }

    public int monitoredItemCount() {
        return monitoredItems.get();
    }

    public int pendingPublishCount() {
        return pendingPublishes.get();
    }

    public void channelIncrement() {
        channels.incrementAndGet();
    }

    public void channelDecrement() {
        channels.decrementAndGet();
    }

    public void sessionIncrement() {
        sessions.incrementAndGet();
    }

    public void sessionDecrement() {
        sessions.decrementAndGet();
    }

    public void subscriptionIncrement() {
        subscriptions.incrementAndGet();
    }

    public void subscriptionDecrement() {
        subscriptions.decrementAndGet();
    }

    public void monitoredItemIncrement() {
        monitoredItems.incrementAndGet();
    }

    public void monitoredItemDecrement() {
        monitoredItems.decrementAndGet();
    }

    public void monitoredItemDecrementBy(int count) {
        monitoredItems.addAndGet(-count);
    }

    public void pendingPublishIncrement() {
        pendingPublishes.incrementAndGet();
    }

    public void pendingPublishDecrement() {
        pendingPublishes.decrementAndGet();
    }
}
