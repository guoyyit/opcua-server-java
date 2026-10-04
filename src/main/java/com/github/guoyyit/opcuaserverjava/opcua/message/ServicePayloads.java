package com.github.guoyyit.opcuaserverjava.opcua.message;

import com.github.guoyyit.opcuaserverjava.opcua.model.DataValue;
import com.github.guoyyit.opcuaserverjava.opcua.model.DiscardPolicy;
import com.github.guoyyit.opcuaserverjava.opcua.model.IdentityToken;
import com.github.guoyyit.opcuaserverjava.opcua.model.MonitoringMode;
import com.github.guoyyit.opcuaserverjava.opcua.model.NodeId;

import java.util.List;

/**
 * Service request and response payload records (Jackson serialized).
 */
public final class ServicePayloads {

    private ServicePayloads() {
    }

    public record OpenSecureChannelRequest(long requestedLifetimeMs) {
    }

    public record OpenSecureChannelResponse(Integer channelId, long revisedLifetimeMs) {
    }

    public record CloseSecureChannelRequest() {
    }

    public record CloseSecureChannelResponse() {
    }

    public record CreateSessionRequest(long requestedSessionTimeoutMs) {
    }

    public record CreateSessionResponse(String sessionToken, long revisedSessionTimeoutMs) {
    }

    public record ActivateSessionRequest(String sessionToken, IdentityToken identity) {
    }

    public record ActivateSessionResponse(String policyId) {
    }

    public record CloseSessionRequest(String sessionToken) {
    }

    public record CloseSessionResponse() {
    }

    public record BrowseRequest(String sessionToken,
                                NodeId nodeId,
                                long continuationThreshold) {
    }

    public record ReferenceDescription(NodeId referenceTypeId,
                                       boolean isForward,
                                       NodeId nodeId,
                                       String browseName,
                                       String displayName,
                                       String nodeClass) {
    }

    public record BrowseResponse(String continuationPoint, List<ReferenceDescription> references) {
    }

    public record BrowseNextRequest(String sessionToken,
                                    String continuationPoint,
                                    boolean releaseContinuationPoint) {
    }

    public record BrowseNextResponse(String continuationPoint, List<ReferenceDescription> references) {
    }

    public record ReadRequest(String sessionToken,
                              List<NodeId> nodeIds,
                              Integer attributeId,
                              long maxAgeMs) {
    }

    public record ReadResult(NodeId nodeId, long statusCode, DataValue value) {
    }

    public record ReadResponse(List<ReadResult> results) {
    }

    public record WriteValue(NodeId nodeId, DataValue value) {
    }

    public record WriteRequest(String sessionToken, List<WriteValue> values) {
    }

    public record WriteResponse(List<Long> results) {
    }

    public record CreateSubscriptionRequest(String sessionToken,
                                            long requestedPublishingIntervalMs,
                                            long requestedLifetimeMs,
                                            long maxKeepAliveCount,
                                            long publishingEnabled,
                                            long notificationRetentionWindow) {
    }

    public record CreateSubscriptionResponse(long subscriptionId,
                                             long revisedPublishingIntervalMs,
                                             long revisedLifetimeMs) {
    }

    public record DeleteSubscriptionRequest(String sessionToken, long subscriptionId) {
    }

    public record DeleteSubscriptionResponse() {
    }

    public record MonitoredItemCreateRequest(NodeId nodeId,
                                             long samplingIntervalMs,
                                             int queueSize,
                                             DiscardPolicy discardPolicy) {
    }

    public record MonitoredItemCreateResult(long monitoredItemId,
                                            long revisedSamplingIntervalMs,
                                            long statusCode) {
    }

    public record CreateMonitoredItemsRequest(String sessionToken,
                                              long subscriptionId,
                                              List<MonitoredItemCreateRequest> items) {
    }

    public record CreateMonitoredItemsResponse(List<MonitoredItemCreateResult> results) {
    }

    public record ModifyMonitoredItemRequest(String sessionToken,
                                             long subscriptionId,
                                             long monitoredItemId,
                                             long samplingIntervalMs,
                                             int queueSize) {
    }

    public record ModifyMonitoredItemResponse(long revisedSamplingIntervalMs, long statusCode) {
    }

    public record SetMonitoringModeRequest(String sessionToken,
                                           long subscriptionId,
                                           List<Long> monitoredItemIds,
                                           MonitoringMode monitoringMode) {
    }

    public record SetMonitoringModeResponse(List<Long> results) {
    }

    public record NotificationMessage(long sequenceNumber,
                                      java.time.Instant publishTime,
                                      List<DataChangeNotification> notifications,
                                      boolean keepAlive) {
    }

    public record DataChangeNotification(long monitoredItemId,
                                         NodeId nodeId,
                                         DataValue value,
                                         boolean overflow) {
    }

    public record PublishRequest(String sessionToken,
                                 long subscriptionId,
                                 long timeoutHintMs) {
    }

    public record PublishResponse(long subscriptionId, NotificationMessage notification) {
    }

    public record UnpublishRequest(String sessionToken, long subscriptionId) {
    }

    public record UnpublishResponse() {
    }

    public record RepublishRequest(String sessionToken,
                                   long subscriptionId,
                                   long retransmitSequenceNumber) {
    }

    public record RepublishResponse(long subscriptionId, NotificationMessage notification) {
    }
}
