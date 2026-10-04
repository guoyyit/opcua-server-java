package com.github.guoyyit.opcuaserverjava.opcua.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.opcuaserverjava.opcua.message.Request;
import com.github.guoyyit.opcuaserverjava.opcua.message.RequestType;
import com.github.guoyyit.opcuaserverjava.opcua.message.Response;
import com.github.guoyyit.opcuaserverjava.opcua.message.ServicePayloads;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal loopback client maintaining one TCP connection that becomes a secure
 * channel. It keeps an independent per-channel request sequence and serializes
 * request writes so concurrent publish threads never reorder frames.
 */
public class OpcUaClient implements Closeable {

    private final String host;
    private final int port;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final AtomicLong requestSequence = new AtomicLong(1);
    private final AtomicLong requestIdGenerator = new AtomicLong(1);
    private final Object writeLock = new Object();

    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private volatile Integer channelId;
    private volatile String sessionToken;
    private final Map<Long, CompletableFuture<Response>> pending = new ConcurrentHashMap<>();
    private Thread readerThread;
    private volatile boolean closed;
    private volatile Throwable terminalError;

    public OpcUaClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void connect() throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        input = new DataInputStream(socket.getInputStream());
        output = new DataOutputStream(socket.getOutputStream());
        readerThread = new Thread(this::readLoop, "opcua-client-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    public Integer channelId() {
        return channelId;
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
    }

    public String sessionToken() {
        return sessionToken;
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    /**
     * Opens the secure channel and records the server assigned channel id.
     */
    public ServicePayloads.OpenSecureChannelResponse openSecureChannel(long lifetimeMs) throws IOException {
        Response response = exchange(RequestType.OPEN_SECURE_CHANNEL,
                new ServicePayloads.OpenSecureChannelRequest(lifetimeMs));
        ServicePayloads.OpenSecureChannelResponse payload =
                objectMapper.treeToValue(response.payload(),
                        ServicePayloads.OpenSecureChannelResponse.class);
        this.channelId = payload.channelId();
        return payload;
    }

    public Response exchange(RequestType type, Object payload) throws IOException {
        if (terminalError != null) {
            throw new IOException("secure channel is closed", terminalError);
        }
        long sequence;
        long requestId;
        CompletableFuture<Response> future = new CompletableFuture<>();
        byte[] body;
        synchronized (writeLock) {
            if (terminalError != null) {
                throw new IOException("secure channel is closed", terminalError);
            }
            sequence = requestSequence.getAndIncrement();
            requestId = requestIdGenerator.getAndIncrement();
            pending.put(requestId, future);
            try {
                Request request = new Request(type, channelId, sequence, requestId,
                        payload == null ? null : objectMapper.valueToTree(payload));
                body = objectMapper.writeValueAsBytes(request);
                output.writeInt(body.length);
                output.write(body);
                output.flush();
            } catch (IOException e) {
                pending.remove(requestId);
                throw e;
            }
        }
        try {
            return future.join();
        } catch (java.util.concurrent.CompletionException e) {
            throw new IOException("request failed", e.getCause());
        }
    }

    private void readLoop() {
        Throwable failure = null;
        try {
            while (!closed) {
                int length = input.readInt();
                if (length <= 0) {
                    throw new IOException("invalid frame length: " + length);
                }
                byte[] buffer = new byte[length];
                input.readFully(buffer);
                Response response = objectMapper.readValue(buffer, Response.class);
                CompletableFuture<Response> future = pending.remove(response.requestId());
                if (future != null) {
                    future.complete(response);
                }
            }
        } catch (Throwable t) {
            failure = t;
        } finally {
            terminalError = failure == null
                    ? new IOException("connection ended") : failure;
            if (!closed) {
                IOException error = failure instanceof IOException io
                        ? io : new IOException("connection ended", failure);
                failAllPending(error);
            }
        }
    }

    private void failAllPending(IOException error) {
        for (CompletableFuture<Response> future : pending.values()) {
            future.completeExceptionally(error);
        }
        pending.clear();
    }

    /**
     * Issues a Publish request. Meant to be called from a dedicated consumer
     * thread to emulate OPC UA long polling.
     */
    public ServicePayloads.PublishResponse publish(ServicePayloads.PublishRequest request) throws IOException {
        Response response = exchange(RequestType.PUBLISH, request);
        if (response.statusCode() != 0L) {
            throw new ServiceException(response.statusCode());
        }
        return objectMapper.treeToValue(response.payload(), ServicePayloads.PublishResponse.class);
    }

    @Override
    public void close() {
        closed = true;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // close best effort
            }
        }
    }

    /** Client side view of a non-good service status. */
    public static class ServiceException extends IOException {
        private final long statusCode;

        public ServiceException(long statusCode) {
            super("statusCode=0x" + Long.toHexString(statusCode));
            this.statusCode = statusCode;
        }

        public long statusCode() {
            return statusCode;
        }
    }
}
