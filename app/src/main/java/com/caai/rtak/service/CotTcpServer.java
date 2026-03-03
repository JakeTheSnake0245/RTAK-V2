package com.caai.rtak.service;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight TCP server that speaks the TAK Protocol (CoT XML over TCP).
 * <p>
 * ATAK / WinTAK / iTAK clients connect here. Received CoT events are
 * forwarded to Reticulum via the bridge; events arriving from Reticulum
 * are pushed out to all connected TAK clients.
 * <p>
 * Protocol: Each CoT XML event is delimited by {@code </event>}.
 * (TAK Protocol Version 0 — plain XML, newline / tag delimited.)
 */
public class CotTcpServer {

    private static final String TAG = "CotTcpServer";
    private static final int DEFAULT_PORT = 8087;

    public interface CotListener {
        /** Called when a CoT event arrives from a connected TAK client. */
        void onCotFromClient(String cotXml, String clientId);

        /** Called when a TAK client connects. */
        void onClientConnected(String clientId);

        /** Called when a TAK client disconnects. */
        void onClientDisconnected(String clientId);
    }

    /** Supplies keepalive CoT XML (e.g. t-x-c-t ping). */
    public interface KeepaliveProvider {
        String buildKeepalive();
    }

    private static final int KEEPALIVE_INTERVAL_SECONDS = 5;

    private final int port;
    private final CotListener listener;
    private KeepaliveProvider keepaliveProvider;
    private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private ExecutorService executor;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> keepaliveFuture;

    private ServerSocket serverSocket;
    private volatile boolean running = false;

    public CotTcpServer(int port, CotListener listener) {
        this.port = port;
        this.listener = listener;
    }

    public CotTcpServer(CotListener listener) {
        this(DEFAULT_PORT, listener);
    }

    public void setKeepaliveProvider(KeepaliveProvider provider) {
        this.keepaliveProvider = provider;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    /**
     * Start the TCP server in a background thread.
     */
    public void start() {
        if (running) return;
        running = true;
        executor = Executors.newCachedThreadPool();
        scheduler = Executors.newSingleThreadScheduledExecutor();

        executor.submit(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
                Log.i(TAG, "TAK TCP server listening on localhost:" + port);

                while (running) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        String clientId = clientSocket.getRemoteSocketAddress().toString();
                        Log.i(TAG, "TAK client connected: " + clientId);

                        ClientHandler handler = new ClientHandler(clientSocket, clientId);
                        clients.put(clientId, handler);
                        executor.submit(handler);

                        if (listener != null) {
                            listener.onClientConnected(clientId);
                        }
                    } catch (IOException e) {
                        if (running) {
                            Log.w(TAG, "Accept/setup error: " + e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Server socket error", e);
            }
        });

        // Start keepalive pings to prevent ATAK "Data reception timeout"
        keepaliveFuture = scheduler.scheduleAtFixedRate(() -> {
            if (!clients.isEmpty() && keepaliveProvider != null) {
                try {
                    String ping = keepaliveProvider.buildKeepalive();
                    if (ping != null) {
                        broadcastToClients(ping);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Keepalive send failed: " + e.getMessage());
                }
            }
        }, KEEPALIVE_INTERVAL_SECONDS, KEEPALIVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Stop the server and disconnect all clients.
     */
    public void stop() {
        running = false;

        if (keepaliveFuture != null) {
            keepaliveFuture.cancel(false);
        }
        scheduler.shutdownNow();

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "Error closing server socket", e);
        }

        for (ClientHandler handler : clients.values()) {
            handler.close();
        }
        clients.clear();
        executor.shutdownNow();
        Log.i(TAG, "TAK TCP server stopped");
    }

    // ── Broadcasting to TAK clients ───────────────────────────────────

    /**
     * Send a CoT event to all connected TAK clients.
     *
     * @param cotXml The CoT XML to broadcast.
     */
    public void broadcastToClients(String cotXml) {
        byte[] data = (cotXml + "\n").getBytes(StandardCharsets.UTF_8);
        for (Map.Entry<String, ClientHandler> entry : clients.entrySet()) {
            try {
                entry.getValue().send(data);
            } catch (Exception e) {
                Log.w(TAG, "Failed to send to client " + entry.getKey() + "; closing.");
                clients.remove(entry.getKey());
                entry.getValue().close();
            }
        }
    }

    /**
     * Send a CoT event to a specific TAK client.
     */
    public void sendToClient(String clientId, String cotXml) {
        ClientHandler handler = clients.get(clientId);
        if (handler != null) {
            try {
                handler.send((cotXml + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w(TAG, "Failed to send to client " + clientId + "; closing.");
                clients.remove(clientId);
                handler.close();
            }
        }
    }

    public int getConnectedClientCount() {
        return clients.size();
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return running;
    }

    // ── Client Handler (inner class) ──────────────────────────────────

    private class ClientHandler implements Runnable {

        private final Socket socket;
        private final String clientId;
        private final OutputStream outputStream;
        private volatile boolean intentionallyClosed = false;

        ClientHandler(Socket socket, String clientId) throws IOException {
            this.socket = socket;
            this.clientId = clientId;
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            this.outputStream = socket.getOutputStream();
        }

        @Override
        public void run() {
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                );

                StringBuilder buffer = new StringBuilder();
                char[] readBuf = new char[4096];
                int bytesRead;

                while (running && !socket.isClosed()) {
                    bytesRead = reader.read(readBuf);
                    if (bytesRead == -1) break;

                    buffer.append(readBuf, 0, bytesRead);

                    // Extract complete CoT events (delimited by </event>)
                    String content = buffer.toString();
                    int endIdx;
                    while ((endIdx = content.indexOf("</event>")) != -1) {
                        int endPos = endIdx + "</event>".length();
                        String event = content.substring(0, endPos).trim();

                        // Find the start of the XML event
                        int startIdx = event.lastIndexOf("<event");
                        if (startIdx >= 0) {
                            String cotXml = event.substring(startIdx);
                            Log.d(TAG, "CoT from TAK client [" + clientId + "] (" +
                                    cotXml.length() + " bytes)");

                            if (listener != null) {
                                listener.onCotFromClient(cotXml, clientId);
                            }
                        }

                        content = content.substring(endPos);
                    }
                    buffer = new StringBuilder(content);
                }
            } catch (IOException e) {
                if (running && !intentionallyClosed) {
                    Log.d(TAG, "Client " + clientId + " I/O error: " + e.getMessage());
                }
            } finally {
                close();
                clients.remove(clientId);
                if (listener != null) {
                    listener.onClientDisconnected(clientId);
                }
                Log.i(TAG, "TAK client disconnected: " + clientId);
            }
        }

        void send(byte[] data) throws IOException {
            if (outputStream != null && !socket.isClosed()) {
                synchronized (outputStream) {
                    outputStream.write(data);
                    outputStream.flush();
                }
            }
        }

        void close() {
            intentionallyClosed = true;
            try {
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                // Ignore
            }
        }
    }
}
