package com.github.guoyyit.opcuaserverjava.opcua.internal;

import com.github.guoyyit.opcuaserverjava.opcua.OpcUaException;
import com.github.guoyyit.opcuaserverjava.opcua.OpcUaProperties;
import com.github.guoyyit.opcuaserverjava.opcua.OpcUaStats;
import com.github.guoyyit.opcuaserverjava.opcua.StatusCode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Creates and tracks subscriptions per session and releases everything on
 * session/channel close. Different sessions never share delivery state.
 */
@Component
public class SubscriptionManager {

    private final OpcUaProperties properties;
    private final OpcUaStats stats;
    private final AddressSpace addressSpace;
    private final Map<Long, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, Long> subscriptionCountBySession = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    public SubscriptionManager(OpcUaProperties properties, OpcUaStats stats, AddressSpace addressSpace) {
        this.properties = properties;
        this.stats = stats;
        this.addressSpace = addressSpace;
    }

    public Subscription create(Session session,
                               long requestedPublishingIntervalMs,
                               long lifetimeMs,
                               long retentionWindowMs) {
        String sessionToken = session.token();
        long current = subscriptionCountBySession.getOrDefault(sessionToken, 0L);
        if (current >= properties.getMaxSubscriptionsPerSession()) {
            throw new OpcUaException(StatusCode.BAD_NOTIFICATION_QUEUE_LIMIT);
        }
        long subscriptionId = idGenerator.incrementAndGet();
        long publishingInterval = Math.max(requestedPublishingIntervalMs,
                properties.getMinSamplingIntervalMs());
        long effectiveLifetime = lifetimeMs <= 0 ? publishingInterval * 1000 : lifetimeMs;
        long effectiveRetention = retentionWindowMs <= 0 ? publishingInterval * 10 : retentionWindowMs;
        Subscription subscription = new Subscription(
                subscriptionId,
                sessionToken,
                publishingInterval,
                effectiveLifetime,
                effectiveRetention,
                addressSpace,
                new Subscription.OpcUaStatsSink() {
                    @Override
                    public void pendingPublishDelta(int delta) {
                        if (delta > 0) {
                            stats.pendingPublishIncrement();
                        } else {
                            stats.pendingPublishDecrement();
                        }
                    }

                    @Override
                    public void monitoredItemDelta(int delta) {
                        if (delta > 0) {
                            stats.monitoredItemIncrement();
                        } else {
                            stats.monitoredItemDecrement();
                        }
                    }
                },
                properties.getMaxMonitoredItemsPerSubscription(),
                properties.getMaxNotificationQueueDepth());
        subscriptions.put(subscriptionId, subscription);
        subscriptionCountBySession.merge(sessionToken, 1L, Long::sum);
        stats.subscriptionIncrement();
        return subscription;
    }

    public Subscription require(Session session, long subscriptionId) {
        Subscription subscription = subscriptions.get(subscriptionId);
        if (subscription == null) {
            throw new OpcUaException(StatusCode.BAD_SUBSCRIPTION_ID_INVALID);
        }
        if (!subscription.sessionToken().equals(session.token())) {
            // Do not reveal that the subscription exists for another session.
            throw new OpcUaException(StatusCode.BAD_SUBSCRIPTION_ID_INVALID);
        }
        return subscription;
    }

    public int count() {
        return subscriptions.size();
    }

    public void delete(Session session, long subscriptionId) {
        Subscription subscription = require(session, subscriptionId);
        removeSubscription(subscription);
    }

    private void removeSubscription(Subscription subscription) {
        if (subscriptions.remove(subscription.subscriptionId(), subscription)) {
            stats.subscriptionDecrement();
            int itemCount = subscription.drainMonitoredItemStats();
            if (itemCount > 0) {
                stats.monitoredItemDecrementBy(itemCount);
            }
            subscriptionCountBySession.computeIfPresent(subscription.sessionToken(),
                    (token, count) -> count <= 1 ? null : count - 1);
            subscription.shutdown();
        }
    }

    /**
     * Releases every subscription of a closing session. All scheduler threads,
     * monitored item queues, retained notifications and pending publishes are
     * freed; a slow consumer cannot delay this for other sessions.
     */
    public void releaseSession(String sessionToken) {
        for (Subscription subscription : subscriptions.values()) {
            if (subscription.sessionToken().equals(sessionToken)) {
                removeSubscription(subscription);
            }
        }
        subscriptionCountBySession.remove(sessionToken);
    }
}
