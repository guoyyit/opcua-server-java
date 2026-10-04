package com.github.guoyyit.opcuaserverjava.opcua.message;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Wire envelope for every client request. Each secure channel keeps its own
 * request sequence numbers; requests carry a correlation id for multiplexing.
 */
public record Request(RequestType type,
                      Integer channelId,
                      long sequenceNumber,
                      long requestId,
                      JsonNode payload) {
}
