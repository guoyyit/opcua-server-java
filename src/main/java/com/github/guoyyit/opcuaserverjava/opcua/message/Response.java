package com.github.guoyyit.opcuaserverjava.opcua.message;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Wire envelope for every server response.
 */
public record Response(ResponseType type,
                       long requestId,
                       long sequenceNumber,
                       long statusCode,
                       JsonNode payload) {
}
