package com.github.guoyyit.opcuaserverjava.opcua.transport;

import com.github.guoyyit.opcuaserverjava.opcua.OpcUaProperties;
import com.github.guoyyit.opcuaserverjava.opcua.OpcUaStats;
import com.github.guoyyit.opcuaserverjava.opcua.StatusCode;
import com.github.guoyyit.opcuaserverjava.opcua.internal.ChannelRegistry;
import com.github.guoyyit.opcuaserverjava.opcua.internal.SecureChannel;
import com.github.guoyyit.opcuaserverjava.opcua.internal.SessionRegistry;
import com.github.guoyyit.opcuaserverjava.opcua.message.Request;
import com.github.guoyyit.opcuaserverjava.opcua.message.Response;
import com.github.guoyyit.opcuaserverjava.opcua.message.ResponseType;
import com.github.guoyyit.opcuaserverjava.opcua.server.ServiceLayer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local loopback TCP server. One thread accepts connections; each connection
 * has its own reader thread. Response writes are serialized by the codec's
 * per-stream lock. Long publish responses complete on subscription scheduler
 * threads, so a slow session never blocks other channels.
 */
@Component
public class OpcUaTcpServer {

    private final OpcUaProperties properties;
    private final MessageCodec codec;
    private final ServiceLayer serviceLayer;
    private final ChannelRegistry channelRegistry;
    private final SessionRegistry sessionRegistry;
    private final OpcUaStats stats;

    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private Thread acceptThread;
    private Thread reaperThread;
    private final ExecutorService connectionExecutors =
            Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "opcua-conn");
                thread.setDaemon(true);
                return thread;
            });

    public OpcUaTcpServer(OpcUaProperties properties,
                          MessageCodec codec,
                          ServiceLayer serviceLayer,
                          ChannelRegistry channelRegistry,
                          SessionRegistry sessionRegistry,
                          OpcUaStats stats) {
        this.properties = properties;
        this.codec = codec;
        this.serviceLayer = serviceLayer;
        this.channelRegistry = channelRegistry;
        this.sessionRegistry = sessionRegistry;
        this.stats = stats;
    }

    public int getBoundPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    @PostConstruct
    public void start() {
        try {
            serverSocket = new ServerSocket(properties.getPort());
        } catch (IOException e) {
            throw new IllegalStateException("cannot bind opcua loopback server", e);
        }
        running = true;
        acceptThread = new Thread(this::acceptLoop, "opcua-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        reaperThread = new Thread(this::reapLoop, "opcua-reaper");
        reaperThread.setDaemon(true);
        reaperThread.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                connectionExecutors.submit(() -> handleConnection(socket));
            } catch (SocketException e) {
                if (running) {
                    // transient accept error; continue
                }
            } catch (IOException e) {
                if (running) {
                    // continue accepting
                }
            }
        }
    }

    private void reapLoop() {
        while (running) {
            try {
                Thread.sleep(properties.getReaperIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            channelRegistry.reapExpired();
            sessionRegistry.reapExpired();
        }
    }

    private void handleConnection(Socket socket) {
        SecureChannel channel = null;
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout((int) Math.max(50, properties.getReaperIntervalMs() * 2));
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            channel = channelRegistry.register(socket,
                    properties.getDefaultChannelLifetimeMs());
            while (running && !channel.closed()) {
                Request request;
                try {
                    request = codec.readRequest(in);
                } catch (java.net.SocketTimeoutException e) {
                    // Read timeout: the reaper may have closed the channel while
                    // we were blocked. Re-check and exit promptly if it did.
                    if (channel.closed()) {
                        break;
                    }
                    continue;
                } catch (IOException e) {
                    break;
                }
                if (!channel.isExpectedRequestSequence(request.sequenceNumber())) {
                    // Per-channel sequence violation closes the channel.
                    codec.writeResponse(out, new Response(
                            ServiceLayer.responseTypeOf(request.type()),
                            request.requestId(), channel.takeResponseSequence(),
                            StatusCode.BAD_SEQUENCE_NUMBER_INVALID.code(), null));
                    break;
                }
                channel.advanceExpectedRequestSequence();
                try {
                    Response response;
                    try {
                        response = serviceLayer.dispatch(channel, request).join();
                    } catch (java.util.concurrent.CompletionException e) {
                        response = new Response(
                                ServiceLayer.responseTypeOf(request.type()),
                                request.requestId(), channel.takeResponseSequence(),
                                StatusCode.BAD_INTERNAL_ERROR.code(), null);
                    }
                    codec.writeResponse(out, response);
                    if (channel.closePending()) {
                        break;
                    }
                } catch (RuntimeException e) {
                    codec.writeResponse(out, new Response(
                            ServiceLayer.responseTypeOf(request.type()),
                            request.requestId(), channel.takeResponseSequence(),
                            StatusCode.BAD_INTERNAL_ERROR.code(), null));
                }
            }
        } catch (IOException e) {
            // connection ended
        } finally {
            if (channel != null) {
                channelRegistry.closeChannel(channel);
            } else {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // shutdown best effort
                }
            }
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // shutdown best effort
            }
        }
        connectionExecutors.shutdownNow();
    }
}
