package com.github.guoyyit.opcuaserverjava.opcua.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.guoyyit.opcuaserverjava.opcua.OpcUaException;
import com.github.guoyyit.opcuaserverjava.opcua.OpcUaProperties;
import com.github.guoyyit.opcuaserverjava.opcua.StatusCode;
import com.github.guoyyit.opcuaserverjava.opcua.internal.AddressSpace;
import com.github.guoyyit.opcuaserverjava.opcua.internal.ChannelRegistry;
import com.github.guoyyit.opcuaserverjava.opcua.internal.CredentialStore;
import com.github.guoyyit.opcuaserverjava.opcua.internal.Node;
import com.github.guoyyit.opcuaserverjava.opcua.internal.Reference;
import com.github.guoyyit.opcuaserverjava.opcua.internal.SecureChannel;
import com.github.guoyyit.opcuaserverjava.opcua.internal.Session;
import com.github.guoyyit.opcuaserverjava.opcua.internal.SessionRegistry;
import com.github.guoyyit.opcuaserverjava.opcua.internal.Subscription;
import com.github.guoyyit.opcuaserverjava.opcua.internal.SubscriptionManager;
import com.github.guoyyit.opcuaserverjava.opcua.message.Request;
import com.github.guoyyit.opcuaserverjava.opcua.message.Response;
import com.github.guoyyit.opcuaserverjava.opcua.message.ResponseType;
import com.github.guoyyit.opcuaserverjava.opcua.message.RequestType;
import com.github.guoyyit.opcuaserverjava.opcua.message.ServicePayloads;
import com.github.guoyyit.opcuaserverjava.opcua.transport.MessageCodec;
import org.springframework.stereotype.Component;

import com.github.guoyyit.opcuaserverjava.opcua.model.AccessLevel;
import com.github.guoyyit.opcuaserverjava.opcua.model.DataValue;
import com.github.guoyyit.opcuaserverjava.opcua.model.IdentityToken;
import com.github.guoyyit.opcuaserverjava.opcua.model.NodeClass;
import com.github.guoyyit.opcuaserverjava.opcua.model.NodeId;
import com.github.guoyyit.opcuaserverjava.opcua.model.Variant;
import com.github.guoyyit.opcuaserverjava.opcua.message.ServicePayloads.ReferenceDescription;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dispatches every service request and produces typed responses. Publish is
 * asynchronous: its future completes on the subscription's scheduler thread,
 * so the connection reader thread is never blocked and a slow session cannot
 * stall other sessions.
 */
@Component
public class ServiceLayer {

    private final OpcUaProperties properties;
    private final ChannelRegistry channelRegistry;
    private final SessionRegistry sessionRegistry;
    private final SubscriptionManager subscriptionManager;
    private final AddressSpace addressSpace;
    private final CredentialStore credentialStore;
    private final MessageCodec codec;

    private final Map<String, BrowseState> continuationPoints = new ConcurrentHashMap<>();

    public ServiceLayer(OpcUaProperties properties,
                        ChannelRegistry channelRegistry,
                        SessionRegistry sessionRegistry,
                        SubscriptionManager subscriptionManager,
                        AddressSpace addressSpace,
                        CredentialStore credentialStore,
                        MessageCodec codec) {
        this.properties = properties;
        this.channelRegistry = channelRegistry;
        this.sessionRegistry = sessionRegistry;
        this.subscriptionManager = subscriptionManager;
        this.addressSpace = addressSpace;
        this.credentialStore = credentialStore;
        this.codec = codec;
        this.channelRegistry.setCloseListener(channel ->
                sessionRegistry.closeByChannel(channel.channelId()));
        this.sessionRegistry.setCloseListener(session ->
                subscriptionManager.releaseSession(session.token()));
    }

    public CompletableFuture<Response> dispatch(SecureChannel channel, Request request) {
        channel.touch();
        try {
            if (request.type() == null) {
                throw new OpcUaException(StatusCode.BAD_SERVICE_UNSUPPORTED);
            }
            return route(channel, request);
        } catch (OpcUaException e) {
            return errorResponse(channel, request, e.statusCode());
        } catch (RuntimeException e) {
            return errorResponse(channel, request, StatusCode.BAD_INTERNAL_ERROR);
        }
    }

    private CompletableFuture<Response> ok(SecureChannel channel, Request request,
                                           ResponseType type, Object payload) {
        JsonNode node = payload == null ? null : codec.objectMapper().valueToTree(payload);
        return CompletableFuture.completedFuture(new Response(type, request.requestId(),
                channel.takeResponseSequence(), StatusCode.GOOD.code(), node));
    }

    private CompletableFuture<Response> errorResponse(SecureChannel channel, Request request,
                                                      StatusCode status) {
        return CompletableFuture.completedFuture(new Response(
                responseTypeOf(request == null ? null : request.type()),
                request == null ? 0 : request.requestId(),
                channel == null ? 0 : channel.takeResponseSequence(),
                status.code(), null));
    }

    public static ResponseType responseTypeOf(RequestType type) {
        return type == null ? ResponseType.OPEN_SECURE_CHANNEL : ResponseType.valueOf(type.name());
    }

    private void requireSameChannel(SecureChannel channel, Session session) {
        if (session.channelId() != channel.channelId()) {
            throw new OpcUaException(StatusCode.BAD_SESSION_ID_INVALID);
        }
    }

    private Session requireActivatedSession(SecureChannel channel, String sessionToken) {
        Session session = sessionRegistry.require(sessionToken);
        requireSameChannel(channel, session);
        if (!session.activated()) {
            throw new OpcUaException(StatusCode.BAD_SESSION_NOT_ACTIVATED);
        }
        session.touch();
        return session;
    }

    private CompletableFuture<Response> route(SecureChannel channel, Request request) {
        return switch (request.type()) {
            case OPEN_SECURE_CHANNEL -> openSecureChannel(channel, request);
            case CLOSE_SECURE_CHANNEL -> closeSecureChannel(channel, request);
            case CREATE_SESSION -> createSession(channel, request);
            case ACTIVATE_SESSION -> activateSession(channel, request);
            case CLOSE_SESSION -> closeSession(channel, request);
            case BROWSE -> browse(channel, request);
            case BROWSE_NEXT -> browseNext(channel, request);
            case READ -> read(channel, request);
            case WRITE -> write(channel, request);
            case CREATE_SUBSCRIPTION -> createSubscription(channel, request);
            case DELETE_SUBSCRIPTION -> deleteSubscription(channel, request);
            case CREATE_MONITORED_ITEMS -> createMonitoredItems(channel, request);
            case MODIFY_MONITORED_ITEM -> modifyMonitoredItem(channel, request);
            case SET_MONITORING_MODE -> setMonitoringMode(channel, request);
            case PUBLISH -> publish(channel, request);
            case UNPUBLISH -> unpublish(channel, request);
            case REPUBLISH -> republish(channel, request);
        };
    }

    private CompletableFuture<Response> openSecureChannel(SecureChannel channel, Request request) {
        ServicePayloads.OpenSecureChannelRequest payload = convert(request,
                ServicePayloads.OpenSecureChannelRequest.class);
        long requested = payload == null ? 0 : payload.requestedLifetimeMs();
        long revised = channel.lifetimeMs();
        if (requested > 0) {
            revised = Math.min(requested, properties.getDefaultChannelLifetimeMs() * 10);
            channel.renewLifetime(revised);
        }
        return ok(channel, request, ResponseType.OPEN_SECURE_CHANNEL,
                new ServicePayloads.OpenSecureChannelResponse(channel.channelId(), revised));
    }

    private CompletableFuture<Response> closeSecureChannel(SecureChannel channel, Request request) {
        // Only mark the channel as closing here. The transport sends the
        // success response first, then closes the socket in its finally block;
        // closing now would tear down the socket before the response is written.
        channel.markClosePending();
        return ok(channel, request, ResponseType.CLOSE_SECURE_CHANNEL,
                new ServicePayloads.CloseSecureChannelResponse());
    }

    private CompletableFuture<Response> createSession(SecureChannel channel, Request request) {
        ServicePayloads.CreateSessionRequest payload = convert(request,
                ServicePayloads.CreateSessionRequest.class);
        long requested = payload == null ? 0 : payload.requestedSessionTimeoutMs();
        Session session = sessionRegistry.create(channel.channelId(), requested);
        return ok(channel, request, ResponseType.CREATE_SESSION,
                new ServicePayloads.CreateSessionResponse(session.token(), session.timeoutMs()));
    }

    private CompletableFuture<Response> activateSession(SecureChannel channel, Request request) {
        ServicePayloads.ActivateSessionRequest payload = convert(request,
                ServicePayloads.ActivateSessionRequest.class);
        Session session = sessionRegistry.require(payload.sessionToken());
        requireSameChannel(channel, session);
        IdentityToken identity = payload.identity() == null
                ? IdentityToken.anonymous()
                : payload.identity();
        Set<String> roles = credentialStore.rolesOf(identity);
        session.activate(identity.policyId(), roles);
        return ok(channel, request, ResponseType.ACTIVATE_SESSION,
                new ServicePayloads.ActivateSessionResponse(identity.policyId()));
    }

    private CompletableFuture<Response> closeSession(SecureChannel channel, Request request) {
        ServicePayloads.CloseSessionRequest payload = convert(request,
                ServicePayloads.CloseSessionRequest.class);
        Session session = sessionRegistry.require(payload.sessionToken());
        requireSameChannel(channel, session);
        sessionRegistry.closeSession(session);
        return ok(channel, request, ResponseType.CLOSE_SESSION,
                new ServicePayloads.CloseSessionResponse());
    }

    private CompletableFuture<Response> browse(SecureChannel channel, Request request) {
        ServicePayloads.BrowseRequest payload = convert(request, ServicePayloads.BrowseRequest.class);
        Session session = requireActivatedSession(channel, payload.sessionToken());
        List<Node> targets = addressSpace.browseSnapshot(payload.nodeId());
        List<Reference> references = addressSpace.browse(payload.nodeId());
        List<ReferenceDescription> snapshot = new ArrayList<>(targets.size());
        for (Node target : targets) {
            Reference reference = references.stream()
                    .filter(r -> r.targetId().equals(target.nodeId()))
                    .findFirst().orElse(null);
            snapshot.add(new ReferenceDescription(
                    reference == null ? NodeId.numeric(35) : reference.referenceTypeId(),
                    true, target.nodeId(), target.browseName(), target.displayName(),
                    target.nodeClass().name()));
        }
        if (continuationPoints.size() >= properties.getMaxContinuationPoints()) {
            throw new OpcUaException(StatusCode.BAD_CONTINUATION_POINT_LIMIT);
        }
        String token = UUID.randomUUID().toString();
        BrowseState state = new BrowseState(session.token(), snapshot,
                payload.continuationThreshold() <= 0 ? Math.max(1, snapshot.size())
                        : payload.continuationThreshold());
        continuationPoints.put(token, state);
        List<ReferenceDescription> firstPage = state.nextPage();
        String nextToken = state.exhausted() ? null : token;
        if (nextToken == null) {
            continuationPoints.remove(token);
        }
        return ok(channel, request, ResponseType.BROWSE,
                new ServicePayloads.BrowseResponse(nextToken, firstPage));
    }

    private CompletableFuture<Response> browseNext(SecureChannel channel, Request request) {
        ServicePayloads.BrowseNextRequest payload =
                convert(request, ServicePayloads.BrowseNextRequest.class);
        Session session = requireActivatedSession(channel, payload.sessionToken());
        BrowseState state = continuationPoints.get(payload.continuationPoint());
        if (state == null || !state.sessionToken().equals(session.token())) {
            throw new OpcUaException(StatusCode.BAD_NO_CONTINUATION_POINT);
        }
        List<ReferenceDescription> page;
        String nextToken;
        if (payload.releaseContinuationPoint()) {
            continuationPoints.remove(payload.continuationPoint());
            page = List.of();
            nextToken = null;
        } else {
            page = state.nextPage();
            nextToken = state.exhausted() ? null : payload.continuationPoint();
            if (nextToken == null) {
                continuationPoints.remove(payload.continuationPoint());
            }
        }
        return ok(channel, request, ResponseType.BROWSE_NEXT,
                new ServicePayloads.BrowseNextResponse(nextToken, page));
    }

    private CompletableFuture<Response> read(SecureChannel channel, Request request) {
        ServicePayloads.ReadRequest payload = convert(request, ServicePayloads.ReadRequest.class);
        requireActivatedSession(channel, payload.sessionToken());
        List<ServicePayloads.ReadResult> results = new ArrayList<>(payload.nodeIds().size());
        for (NodeId nodeId : payload.nodeIds()) {
            try {
                Node node = addressSpace.requireNode(nodeId);
                if (node.nodeClass() == NodeClass.VARIABLE
                        && !node.accessLevels().contains(AccessLevel.CURRENT_READ)) {
                    results.add(new ServicePayloads.ReadResult(nodeId,
                            StatusCode.BAD_NOT_READABLE.code(), null));
                    continue;
                }
                results.add(new ServicePayloads.ReadResult(nodeId, StatusCode.GOOD.code(),
                        node.nodeClass() == NodeClass.VARIABLE ? node.dataValue() : null));
            } catch (OpcUaException e) {
                results.add(new ServicePayloads.ReadResult(nodeId, e.statusCode().code(), null));
            }
        }
        return ok(channel, request, ResponseType.READ,
                new ServicePayloads.ReadResponse(results));
    }

    private CompletableFuture<Response> write(SecureChannel channel, Request request) {
        ServicePayloads.WriteRequest payload =
                convert(request, ServicePayloads.WriteRequest.class);
        Session session = requireActivatedSession(channel, payload.sessionToken());
        List<Long> results = new ArrayList<>(payload.values().size());
        for (ServicePayloads.WriteValue writeValue : payload.values()) {
            results.add(writeOne(session, writeValue).code());
        }
        return ok(channel, request, ResponseType.WRITE,
                new ServicePayloads.WriteResponse(results));
    }

    private StatusCode writeOne(Session session, ServicePayloads.WriteValue writeValue) {
        try {
            Node node = addressSpace.requireNode(writeValue.nodeId());
            if (node.nodeClass() != NodeClass.VARIABLE) {
                return StatusCode.BAD_NOT_WRITABLE;
            }
            if (!node.accessLevels().contains(AccessLevel.CURRENT_WRITE)) {
                return StatusCode.BAD_NOT_WRITABLE;
            }
            DataValue current = node.dataValue();
            DataValue incoming = writeValue.value();
            if (incoming == null || incoming.value() == null) {
                return StatusCode.BAD_TYPE_MISMATCH;
            }
            Class<?> expected = current == null || current.value() == null
                    ? null : current.value().valueType();
            if (expected != null && !Variant.isAssignable(expected, incoming.value().value())) {
                return StatusCode.BAD_TYPE_MISMATCH;
            }
            Object coerced = incoming.value().value();
            if (coerced instanceof Number number && expected != Number.class) {
                coerced = coerceNumber(number, expected);
            }
            DataValue stamped = new DataValue(incoming.value(),
                    incoming.statusCode() == null ? StatusCode.GOOD : incoming.statusCode(),
                    java.time.Instant.now());
            if (coerced != incoming.value().value()) {
                stamped = new DataValue(new Variant(coerced), stamped.statusCode(), stamped.sourceTime());
            }
            node.dataValue(stamped);
            return StatusCode.GOOD;
        } catch (OpcUaException e) {
            return e.statusCode();
        }
    }

    private static Object coerceNumber(Number number, Class<?> expected) {
        if (expected == Integer.class) {
            return number.intValue();
        }
        if (expected == Long.class) {
            return number.longValue();
        }
        if (expected == Double.class) {
            return number.doubleValue();
        }
        if (expected == Float.class) {
            return number.floatValue();
        }
        if (expected == Short.class) {
            return number.shortValue();
        }
        if (expected == Byte.class) {
            return number.byteValue();
        }
        return number;
    }

    private CompletableFuture<Response> createSubscription(SecureChannel channel, Request request) {
        ServicePayloads.CreateSubscriptionRequest payload =
                convert(request, ServicePayloads.CreateSubscriptionRequest.class);
        Session session = requireActivatedSession(channel, payload.sessionToken());
        Subscription subscription = subscriptionManager.create(session,
                payload.requestedPublishingIntervalMs(),
                payload.requestedLifetimeMs(),
                payload.notificationRetentionWindow());
        return ok(channel, request, ResponseType.CREATE_SUBSCRIPTION,
                new ServicePayloads.CreateSubscriptionResponse(subscription.subscriptionId(),
                        subscription.publishingIntervalMs(), subscription.lifetimeMs()));
    }

    private CompletableFuture<Response> deleteSubscription(SecureChannel channel, Request request) {
        ServicePayloads.DeleteSubscriptionRequest payload =
                convert(request, ServicePayloads.DeleteSubscriptionRequest.class);
        Session session = requireActivatedSession(channel, payload.sessionToken());
        subscriptionManager.delete(session, payload.subscriptionId());
        return ok(channel, request, ResponseType.DELETE_SUBSCRIPTION,
                new ServicePayloads.DeleteSubscriptionResponse());
    }

    private CompletableFuture<Response> createMonitoredItems(SecureChannel channel, Request request) {
        ServicePayloads.CreateMonitoredItemsRequest payload =
                convert(request, ServicePayloads.CreateMonitoredItemsRequest.class);
        Session session = requireActivatedSession(channel, payload.sessionToken());
        Subscription subscription = subscriptionManager.require(session, payload.subscriptionId());
        List<ServicePayloads.MonitoredItemCreateResult> results = new ArrayList<>();
        boolean willExceed = subscription.monitoredItemCount() + payload.items().size()
                > properties.getMaxMonitoredItemsPerSubscription();
        for (ServicePayloads.MonitoredItemCreateRequest itemRequest : payload.items()) {
            if (willExceed) {
                results.add(new ServicePayloads.MonitoredItemCreateResult(0,
                        itemRequest.samplingIntervalMs(),
                        StatusCode.BAD_TOO_MANY_MONITORED_ITEMS.code()));
                continue;
            }
            try {
                addressSpace.requireNode(itemRequest.nodeId());
                if (itemRequest.queueSize() > properties.getMaxNotificationQueueDepth()) {
                    results.add(new ServicePayloads.MonitoredItemCreateResult(0,
                            itemRequest.samplingIntervalMs(),
                            StatusCode.BAD_NOTIFICATION_QUEUE_LIMIT.code()));
                    continue;
                }
                var item = subscription.createMonitoredItem(itemRequest);
                results.add(new ServicePayloads.MonitoredItemCreateResult(item.itemId(),
                        item.samplingIntervalMs(), StatusCode.GOOD.code()));
            } catch (OpcUaException e) {
                results.add(new ServicePayloads.MonitoredItemCreateResult(0,
                        itemRequest.samplingIntervalMs(), e.statusCode().code()));
            }
        }
        return ok(channel, request, ResponseType.CREATE_MONITORED_ITEMS,
                new ServicePayloads.CreateMonitoredItemsResponse(results));
    }

    private CompletableFuture<Response> modifyMonitoredItem(SecureChannel channel, Request request) {
        ServicePayloads.ModifyMonitoredItemRequest payload =
                convert(request, ServicePayloads.ModifyMonitoredItemRequest.class);
        Session session = requireActivatedSession(channel, payload.sessionToken());
        Subscription subscription = subscriptionManager.require(session, payload.subscriptionId());
        var item = subscription.requireMonitoredItem(payload.monitoredItemId());
        if (payload.queueSize() <= 0) {
            throw new OpcUaException(StatusCode.BAD_MONITORED_ITEM_QUEUE_SIZE_INVALID);
        }
        if (payload.queueSize() > properties.getMaxNotificationQueueDepth()) {
            throw new OpcUaException(StatusCode.BAD_NOTIFICATION_QUEUE_LIMIT);
        }
        item.modify(Math.max(payload.samplingIntervalMs(), properties.getMinSamplingIntervalMs()),
                payload.queueSize());
        return ok(channel, request, ResponseType.MODIFY_MONITORED_ITEM,
                new ServicePayloads.ModifyMonitoredItemResponse(
                        item.samplingIntervalMs(), StatusCode.GOOD.code()));
    }

    private CompletableFuture<Response> setMonitoringMode(SecureChannel channel, Request request) {
        ServicePayloads.SetMonitoringModeRequest payload =
                convert(request, ServicePayloads.SetMonitoringModeRequest.class);
        Session session = requireActivatedSession(channel, payload.sessionToken());
        Subscription subscription = subscriptionManager.require(session, payload.subscriptionId());
        List<Long> results = new ArrayList<>();
        for (Long itemId : payload.monitoredItemIds()) {
            try {
                subscription.requireMonitoredItem(itemId).setMonitoringMode(payload.monitoringMode());
                results.add(StatusCode.GOOD.code());
            } catch (OpcUaException e) {
                results.add(e.statusCode().code());
            }
        }
        return ok(channel, request, ResponseType.SET_MONITORING_MODE,
                new ServicePayloads.SetMonitoringModeResponse(results));
    }

    private CompletableFuture<Response> publish(SecureChannel channel, Request request) {
        ServicePayloads.PublishRequest payload =
                convert(request, ServicePayloads.PublishRequest.class);
        Session session = requireActivatedSession(channel, payload.sessionToken());
        Subscription subscription = subscriptionManager.require(session, payload.subscriptionId());
        long responseSequence = channel.takeResponseSequence();
        return subscription.enqueuePublish(payload.timeoutHintMs())
                .handle((notification, error) -> {
                    if (error != null || notification == null) {
                        return new Response(ResponseType.PUBLISH, request.requestId(),
                                responseSequence,
                                StatusCode.BAD_PUBLISH_NOT_ALLOWED.code(), null);
                    }
                    Object publishPayload =
                            new ServicePayloads.PublishResponse(subscription.subscriptionId(),
                                    notification);
                    return new Response(ResponseType.PUBLISH, request.requestId(),
                            responseSequence, StatusCode.GOOD.code(),
                            codec.objectMapper().valueToTree(publishPayload));
                });
    }

    private CompletableFuture<Response> unpublish(SecureChannel channel, Request request) {
        ServicePayloads.UnpublishRequest payload =
                convert(request, ServicePayloads.UnpublishRequest.class);
        Session session = requireActivatedSession(channel, payload.sessionToken());
        Subscription subscription = subscriptionManager.require(session, payload.subscriptionId());
        subscription.cancelPendingPublishes();
        return ok(channel, request, ResponseType.UNPUBLISH,
                new ServicePayloads.UnpublishResponse());
    }

    private CompletableFuture<Response> republish(SecureChannel channel, Request request) {
        ServicePayloads.RepublishRequest payload =
                convert(request, ServicePayloads.RepublishRequest.class);
        Session session = requireActivatedSession(channel, payload.sessionToken());
        Subscription subscription = subscriptionManager.require(session, payload.subscriptionId());
        var notification = subscription.republish(payload.retransmitSequenceNumber());
        return ok(channel, request, ResponseType.REPUBLISH,
                new ServicePayloads.RepublishResponse(subscription.subscriptionId(), notification));
    }

    private static final class BrowseState {
        private final String sessionToken;
        private final List<ReferenceDescription> snapshot;
        private final long pageSize;
        private int offset;

        BrowseState(String sessionToken, List<ReferenceDescription> snapshot, long pageSize) {
            this.sessionToken = sessionToken;
            this.snapshot = List.copyOf(snapshot);
            this.pageSize = pageSize;
        }

        String sessionToken() {
            return sessionToken;
        }

        synchronized boolean exhausted() {
            return offset >= snapshot.size();
        }

        synchronized List<ReferenceDescription> nextPage() {
            if (exhausted()) {
                return List.of();
            }
            int end = (int) Math.min(snapshot.size(), offset + pageSize);
            List<ReferenceDescription> page = List.copyOf(snapshot.subList(offset, end));
            offset = end;
            return page;
        }
    }

    private <T> T convert(Request request, Class<T> type) {
        if (request.payload() == null) {
            return null;
        }
        try {
            return codec.objectMapper().treeToValue(request.payload(), type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new OpcUaException(StatusCode.BAD_INTERNAL_ERROR, "malformed payload");
        }
    }
}
