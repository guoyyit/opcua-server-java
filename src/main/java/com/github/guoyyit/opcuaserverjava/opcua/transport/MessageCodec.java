package com.github.guoyyit.opcuaserverjava.opcua.transport;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.opcuaserverjava.opcua.message.Request;
import com.github.guoyyit.opcuaserverjava.opcua.message.Response;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Length-prefixed JSON framing over TCP: a 4 byte big-endian unsigned length
 * followed by a UTF-8 JSON document.
 */
@Component
public class MessageCodec {

    private static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;

    private final ObjectMapper objectMapper;

    public MessageCodec() {
        this.objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    public Request readRequest(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length <= 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("invalid frame length: " + length);
        }
        byte[] buffer = new byte[length];
        in.readFully(buffer);
        return objectMapper.readValue(new String(buffer, StandardCharsets.UTF_8), Request.class);
    }

    public void writeResponse(DataOutputStream out, Response response) throws IOException {
        byte[] body = objectMapper.writeValueAsBytes(response);
        synchronized (out) {
            out.writeInt(body.length);
            out.write(body);
            out.flush();
        }
    }

    public static boolean isEndOfStream(IOException e) {
        return e instanceof EOFException || e.getMessage() == null
                || e.getMessage().contains("closed") || e.getMessage().contains("Broken pipe");
    }
}
