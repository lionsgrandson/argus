package com.example.babymonitor;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.net.ssl.*;

final class ResilientHttp {
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 7000;
    private static final int MAX_HEADER_BYTES = 32 * 1024;

    static String get(String url, int maxBodyBytes) throws Exception {
        URI uri = new URI(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Only absolute https:// URLs are supported");
        }

        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 443;
        String target = uri.getRawPath();
        if (target == null || target.isEmpty()) target = "/";
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            target += "?" + uri.getRawQuery();
        }

        InetAddress[] addresses = ResilientDns.resolve(host);
        Exception last = null;
        for (InetAddress address : addresses) {
            try {
                return getDirect(address, host, port, target, maxBodyBytes);
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) throw last;
        throw new ConnectException("No address available for " + host);
    }

    static String getDirectLiteral(String ip, String tlsHost, String target, int maxBodyBytes) throws Exception {
        InetAddress address = InetAddress.getByName(ip);
        return getDirect(address, tlsHost, 443, target, maxBodyBytes);
    }

    private static String getDirect(InetAddress address, String tlsHost, int port,
                                    String target, int maxBodyBytes) throws Exception {
        Socket tcp = new Socket();
        SSLSocket ssl = null;
        try {
            tcp.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
            tcp.setTcpNoDelay(true);
            tcp.setKeepAlive(true);
            tcp.setSoTimeout(READ_TIMEOUT_MS);

            SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
            ssl = (SSLSocket) sf.createSocket(tcp, tlsHost, port, true);
            SSLParameters params = ssl.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(params);
            ssl.startHandshake();

            OutputStream out = new BufferedOutputStream(ssl.getOutputStream());
            String request = "GET " + target + " HTTP/1.1\r\n"
                    + "Host: " + tlsHost + "\r\n"
                    + "Accept: application/json\r\n"
                    + "User-Agent: ARGUSAndroid/5\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = new BufferedInputStream(ssl.getInputStream());
            byte[] response = readAllLimited(in, maxBodyBytes + MAX_HEADER_BYTES + 4096);
            int headerEnd = findHeaderEnd(response);
            if (headerEnd < 0 || headerEnd > MAX_HEADER_BYTES) {
                throw new IOException("Invalid HTTP response header");
            }

            String header = new String(response, 0, headerEnd, StandardCharsets.ISO_8859_1);
            String[] lines = header.split("\\r\\n");
            if (lines.length == 0) throw new IOException("Missing HTTP status");
            int status = parseStatus(lines[0]);
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status);
            }

            int bodyStart = headerEnd + 4;
            byte[] body = new byte[Math.max(0, response.length - bodyStart)];
            if (body.length > 0) System.arraycopy(response, bodyStart, body, 0, body.length);

            String transferEncoding = headerValue(lines, "Transfer-Encoding");
            if (transferEncoding != null
                    && transferEncoding.toLowerCase(Locale.US).contains("chunked")) {
                body = decodeChunked(body, maxBodyBytes);
            }

            String contentLength = headerValue(lines, "Content-Length");
            if (contentLength != null) {
                try {
                    int expected = Integer.parseInt(contentLength.trim());
                    if (expected >= 0 && expected < body.length) {
                        byte[] exact = new byte[expected];
                        System.arraycopy(body, 0, exact, 0, expected);
                        body = exact;
                    }
                } catch (NumberFormatException ignored) { }
            }

            if (body.length > maxBodyBytes) throw new IOException("HTTP body too large");
            return new String(body, StandardCharsets.UTF_8);
        } finally {
            try {
                if (ssl != null) ssl.close();
                else tcp.close();
            } catch (Exception ignored) { }
        }
    }

    private static byte[] readAllLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (true) {
            int n = in.read(buf);
            if (n < 0) break;
            if (out.size() + n > limit) throw new IOException("HTTP response too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static int findHeaderEnd(byte[] data) {
        for (int i = 0; i + 3 < data.length; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n'
                    && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static int parseStatus(String statusLine) {
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

    private static byte[] decodeChunked(byte[] input, int maxBodyBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        while (pos < input.length) {
            int lineEnd = findCrlf(input, pos);
            if (lineEnd < 0) throw new IOException("Invalid chunked response");
            String sizeText = new String(input, pos, lineEnd - pos, StandardCharsets.US_ASCII).trim();
            int semi = sizeText.indexOf(';');
            if (semi >= 0) sizeText = sizeText.substring(0, semi).trim();

            int size;
            try { size = Integer.parseInt(sizeText, 16); }
            catch (NumberFormatException e) { throw new IOException("Invalid chunk size", e); }

            pos = lineEnd + 2;
            if (size == 0) break;
            if (size < 0 || pos + size > input.length) throw new IOException("Truncated chunk");
            if (out.size() + size > maxBodyBytes) throw new IOException("HTTP body too large");
            out.write(input, pos, size);
            pos += size;

            if (pos + 1 >= input.length || input[pos] != '\r' || input[pos + 1] != '\n') {
                throw new IOException("Invalid chunk terminator");
            }
            pos += 2;
        }
        return out.toByteArray();
    }

    private static int findCrlf(byte[] input, int start) {
        for (int i = start; i + 1 < input.length; i++) {
            if (input[i] == '\r' && input[i + 1] == '\n') return i;
        }
        return -1;
    }

    private ResilientHttp() {}
}
