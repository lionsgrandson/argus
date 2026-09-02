package com.example.babymonitor;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import javax.net.ssl.*;

final class SecureWebSocket {
    interface Listener {
        void onOpen();
        void onText(String text);
        void onBinary(byte[] data);
        void onClosed(String reason);
        void onError(Exception error);
    }

    private final URI uri;
    private final String authToken;
    private final String role;
    private final Listener listener;
    private volatile Socket socket;
    private volatile InputStream in;
    private volatile OutputStream out;
    private volatile boolean open;
    private final Object writeLock = new Object();
    private final SecureRandom random = new SecureRandom();

    SecureWebSocket(String baseUrl, PairingConfig pairing, String role, Listener listener) throws Exception {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new ErrorReporter.ArgusException("E200", "כתובת שרת החיבור חסרה", "Relay URL is empty");
        }
        URI base = new URI(baseUrl.trim());
        if (!"wss".equalsIgnoreCase(base.getScheme())) {
            throw new ErrorReporter.ArgusException("E200", "כתובת שרת החיבור אינה מאובטחת", "Relay URL must start with wss://");
        }
        if (base.getHost() == null) {
            throw new ErrorReporter.ArgusException("E200", "כתובת שרת החיבור אינה תקינה", "Relay URL has no host");
        }
        String path = base.getRawPath();
        if (path == null || path.isEmpty()) path = "/ws";
        String q = "room=" + enc(pairing.roomId) + "&role=" + enc(role) + "&v=4";
        if (base.getRawQuery() != null && !base.getRawQuery().isEmpty()) q = base.getRawQuery() + "&" + q;
        this.uri = new URI("wss", null, base.getHost(), base.getPort(), path, q, null);
        this.authToken = pairing.authToken;
        this.role = role;
        this.listener = listener;
    }

    void connect() throws Exception {
        try {
            doConnect();
            ErrorReporter.clear(role);
        } catch (Exception e) {
            closeSilently();
            throw e;
        }
    }

    private void doConnect() throws Exception {
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 443;

        SSLSocket ssl = connectTlsWithFallback(host, port);
        socket = ssl;
        in = new BufferedInputStream(ssl.getInputStream());
        out = new BufferedOutputStream(ssl.getOutputStream());

        byte[] keyBytes = new byte[16];
        random.nextBytes(keyBytes);
        String wsKey = Base64.getEncoder().encodeToString(keyBytes);
        String hostHeader = host + ((uri.getPort() > 0 && uri.getPort() != 443) ? ":" + port : "");
        String target = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        String request = "GET " + target + " HTTP/1.1\r\n" +
                "Host: " + hostHeader + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + wsKey + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "Authorization: Bearer " + authToken + "\r\n" +
                "User-Agent: ARGUSAndroid/4\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String header = readHttpHeader(in);
        String[] lines = header.split("\\r\\n");
        if (lines.length == 0) {
            throw new ErrorReporter.ArgusException("E204", "שרת החיבור לא החזיר תשובה תקינה", "No HTTP response from relay");
        }
        int status = httpStatus(lines[0]);
        if (status != 101) {
            String serverCode = headerValue(lines, "X-Argus-Error");
            if ("E102".equals(serverCode) || status == 426) {
                throw new ErrorReporter.ArgusException("E102", "גרסת חיבור ישנה אופסה. יש לעדכן את ARGUS", lines[0]);
            }
            if ("E205".equals(serverCode) || status == 401 || status == 403 || status == 409) {
                throw new ErrorReporter.ArgusException("E205", "שרת החיבור דחה את קוד החיבור", lines[0]);
            }
            throw new ErrorReporter.ArgusException("E204", "שרת החיבור החזיר תשובה לא תקינה", lines[0]);
        }

        String accept = headerValue(lines, "Sec-WebSocket-Accept");
        String expected = expectedAccept(wsKey);
        if (!expected.equals(accept)) {
            throw new ErrorReporter.ArgusException("E203", "אימות חיבור האבטחה נכשל", "Invalid WebSocket accept header");
        }

        open = true;
        listener.onOpen();
        Thread reader = new Thread(this::readLoop, "ArgusWS");
        reader.setDaemon(true);
        reader.start();

        Thread heartbeat = new Thread(this::heartbeatLoop, "ArgusWSHeartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    boolean isOpen() { return open; }

    void sendText(String text) throws IOException {
        sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    void sendBinary(byte[] data) throws IOException {
        sendFrame(0x2, data);
    }

    void close() {
        if (!open && socket == null) return;
        try { if (open) sendFrame(0x8, new byte[0]); } catch (Exception ignored) { }
        open = false;
        closeSilently();
    }

    private void closeSilently() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        socket = null;
    }

    private static SSLSocket connectTlsWithFallback(String host, int port) throws Exception {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses == null || addresses.length == 0) throw new UnknownHostException(host);

        Exception lastError = null;
        for (InetAddress address : addresses) {
            Socket tcp = new Socket();
            SSLSocket ssl = null;
            try {
                tcp.connect(new InetSocketAddress(address, port), 8000);
                tcp.setTcpNoDelay(true);
                tcp.setKeepAlive(true);
                tcp.setSoTimeout(30000);
                SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
                ssl = (SSLSocket) sf.createSocket(tcp, host, port, true);
                SSLParameters params = ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(params);
                ssl.startHandshake();
                return ssl;
            } catch (Exception e) {
                lastError = e;
                try { if (ssl != null) ssl.close(); else tcp.close(); } catch (Exception ignored) { }
            }
        }

        if (lastError != null) throw lastError;
        throw new ConnectException("Unable to connect to relay");
    }

    private void heartbeatLoop() {
        while (open) {
            try {
                Thread.sleep(10000);
                if (open) sendFrame(0x9, "hb".getBytes(StandardCharsets.US_ASCII));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                closeSilently();
                return;
            }
        }
    }

    private void readLoop() {
        String reason = "connection closed";
        boolean unexpectedClose = false;
        try {
            while (open) {
                int b0 = in.read();
                if (b0 < 0) {
                    unexpectedClose = true;
                    throw new EOFException("Relay closed the socket");
                }
                int b1 = readRequired(in);
                int opcode = b0 & 0x0F;
                boolean fin = (b0 & 0x80) != 0;
                if (!fin) throw new IOException("Fragmented frames are not supported");
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) len = ((long) readRequired(in) << 8) | readRequired(in);
                else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | readRequired(in);
                }
                if (len > 1024 * 1024) {
                    throw new ErrorReporter.ArgusException("E209", "השרת שלח חבילת מידע גדולה מדי", "Frame too large: " + len);
                }
                byte[] mask = masked ? readExactly(in, 4) : null;
                byte[] payload = readExactly(in, (int) len);
                if (masked) {
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
                }

                if (opcode == 0x1) listener.onText(new String(payload, StandardCharsets.UTF_8));
                else if (opcode == 0x2) listener.onBinary(payload);
                else if (opcode == 0x8) {
                    reason = "server closed connection";
                    unexpectedClose = true;
                    break;
                } else if (opcode == 0x9) sendFrame(0xA, payload);
                else if (opcode == 0xA) { }
            }
        } catch (Exception e) {
            if (open) {
                unexpectedClose = true;
                listener.onError(e);
            }
            reason = e.getMessage() == null ? "connection error" : e.getMessage();
        } finally {
            open = false;
            closeSilently();
            if (!unexpectedClose) ErrorReporter.clear(role);
            listener.onClosed(reason);
        }
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        if (!open || out == null) throw new IOException("WebSocket is not connected");
        synchronized (writeLock) {
            ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 16);
            frame.write(0x80 | (opcode & 0x0F));
            int len = payload.length;
            if (len < 126) frame.write(0x80 | len);
            else if (len <= 65535) {
                frame.write(0x80 | 126);
                frame.write((len >>> 8) & 0xFF);
                frame.write(len & 0xFF);
            } else {
                frame.write(0x80 | 127);
                long l = len;
                for (int i = 7; i >= 0; i--) frame.write((int) ((l >>> (8 * i)) & 0xFF));
            }
            byte[] mask = new byte[4];
            random.nextBytes(mask);
            frame.write(mask);
            for (int i = 0; i < payload.length; i++) frame.write(payload[i] ^ mask[i & 3]);
            out.write(frame.toByteArray());
            out.flush();
        }
    }

    private static String readHttpHeader(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        while (buf.size() < 16384) {
            int b = in.read();
            if (b < 0) throw new EOFException("Relay closed during handshake");
            buf.write(b);
            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') return buf.toString("US-ASCII");
            else state = (b == '\r') ? 1 : 0;
        }
        throw new IOException("Relay HTTP header too large");
    }

    private static int httpStatus(String statusLine) {
        if (statusLine == null) return -1;
        String[] parts = statusLine.split(" ");
        if (parts.length < 2) return -1;
        try { return Integer.parseInt(parts[1]); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private static String headerValue(String[] lines, String name) {
        for (String line : lines) {
            int idx = line.indexOf(':');
            if (idx > 0 && name.equalsIgnoreCase(line.substring(0, idx).trim())) {
                return line.substring(idx + 1).trim();
            }
        }
        return null;
    }

    private static String expectedAccept(String key) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] digest = sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
        return Base64.getEncoder().encodeToString(digest);
    }

    private static int readRequired(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    private static byte[] readExactly(InputStream in, int len) throws IOException {
        byte[] out = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(out, off, len - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
        return out;
    }

    private static String enc(String s) throws UnsupportedEncodingException {
        return URLEncoder.encode(s, "UTF-8");
    }
}
