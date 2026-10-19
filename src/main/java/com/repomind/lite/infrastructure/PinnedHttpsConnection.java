package com.repomind.lite.infrastructure;

import org.eclipse.jgit.transport.http.HttpConnection;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;

/**
 * HTTPS transport pins a validated DNS answer to the socket and validates TLS against the original host.
 */
final class PinnedHttpsConnection implements HttpConnection {
    private final URL url;
    private final InetAddress address;
    private final Map<String, String> requestHeaders = new LinkedHashMap<>();
    private final Map<String, List<String>> responseHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private String method = "GET", responseMessage = "";
    private int status = -1, connectTimeout = 30000, readTimeout = 30000;
    private InputStream input;

    PinnedHttpsConnection(URL url) throws IOException {
        this.url = url;
        if (!url.getProtocol().equals("https") || (url.getPort() != -1 && url.getPort() != 443) || url.getUserInfo() != null)
            throw new IOException("Unsafe Git transport URL");
        var answers = InetAddress.getAllByName(url.getHost());
        if (answers.length == 0) throw new IOException("No Git remote address");
        for (var answer : answers)
            if (!RepositoryUrlValidator.isPublic(answer))
                throw new IOException("Git remote resolves to a private address");
        this.address = answers[0];
    }

    @Override
    public void connect() throws IOException {
        if (status != -1) return;
        Socket plain = new Socket();
        try {
            plain.connect(new InetSocketAddress(address, 443), connectTimeout);
            var tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, url.getHost(), 443, true);
            tls.setSoTimeout(readTimeout);
            var parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            tls.startHandshake();
            OutputStream wire = tls.getOutputStream();
            var header = new StringBuilder(method).append(' ').append(url.getFile().isEmpty() ? "/" : url.getFile()).append(" HTTP/1.1\r\nHost: ").append(url.getHost()).append("\r\nConnection: close\r\n");
            for (var entry : requestHeaders.entrySet())
                if (!Set.of("host", "connection", "content-length", "transfer-encoding", "authorization", "proxy-authorization").contains(entry.getKey().toLowerCase(Locale.ROOT)))
                    header.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            if (method.equals("POST") || output.size() > 0)
                header.append("Content-Length: ").append(output.size()).append("\r\n");
            header.append("\r\n");
            wire.write(header.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            output.writeTo(wire);
            wire.flush();
            var raw = new BufferedInputStream(tls.getInputStream());
            String statusLine = line(raw);
            String[] parts = statusLine.split(" ", 3);
            if (parts.length < 2 || !parts[0].startsWith("HTTP/1.")) throw new IOException("Invalid Git HTTP response");
            status = Integer.parseInt(parts[1]);
            responseMessage = parts.length == 3 ? parts[2] : "";
            int headerBytes = 0;
            for (String value = line(raw); !value.isEmpty(); value = line(raw)) {
                if ((headerBytes += value.length()) > 65536) throw new IOException("Git response headers too large");
                int colon = value.indexOf(':');
                if (colon <= 0) throw new IOException("Invalid Git HTTP header");
                responseHeaders.computeIfAbsent(value.substring(0, colon).strip(), key -> new ArrayList<>()).add(value.substring(colon + 1).strip());
            }
            if (status >= 300 && status < 400) throw new IOException("Git remote redirects are disabled");
            InputStream body = "chunked".equalsIgnoreCase(getHeaderField("Transfer-Encoding")) ? new ChunkedBody(raw) : raw;
            input = new FilterInputStream(body) {
                long bytes;

                @Override
                public int read() throws IOException {
                    int value = super.read();
                    if (value != -1) check(1);
                    return value;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int count = in.read(b, off, len);
                    if (count > 0) check(count);
                    return count;
                }

                private void check(int count) throws IOException {
                    if ((bytes += count) > 256L * 1024 * 1024) {
                        close();
                        throw new IOException("Git response exceeds repository size limit");
                    }
                }

                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        tls.close();
                    }
                }
            };
        } catch (Exception ex) {
            plain.close();
            if (ex instanceof IOException io) throw io;
            throw new IOException("Git HTTPS transport failed", ex);
        }
    }

    private static String line(InputStream in) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int value; (value = in.read()) != -1; ) {
            if (value == '\n')
                return bytes.toString(java.nio.charset.StandardCharsets.ISO_8859_1).replaceFirst("\r$", "");
            if (bytes.size() >= 8192) throw new IOException("HTTP line too long");
            bytes.write(value);
        }
        throw new EOFException("Incomplete HTTP response");
    }

    private static final class ChunkedBody extends InputStream {
        private final InputStream in;
        private long remaining;
        private boolean first = true, finished;

        ChunkedBody(InputStream in) {
            this.in = in;
        }

        private boolean chunk() throws IOException {
            if (finished) return false;
            if (remaining > 0) return true;
            if (!first && !line(in).isEmpty()) throw new IOException("Invalid chunk boundary");
            first = false;
            String header = line(in).split(";", 2)[0];
            try {
                remaining = Long.parseLong(header, 16);
            } catch (NumberFormatException ex) {
                throw new IOException("Invalid chunk length");
            }
            if (remaining == 0) {
                finished = true;
                return false;
            }
            if (remaining < 0) throw new IOException("Invalid chunk length");
            return true;
        }

        @Override
        public int read() throws IOException {
            if (!chunk()) return -1;
            int value = in.read();
            if (value == -1) throw new EOFException();
            remaining--;
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (!chunk()) return -1;
            int count = in.read(b, off, (int) Math.min(len, remaining));
            if (count == -1) throw new EOFException();
            remaining -= count;
            return count;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    @Override
    public InputStream getInputStream() throws IOException {
        connect();
        return input;
    }

    @Override
    public int getResponseCode() throws IOException {
        connect();
        return status;
    }

    @Override
    public String getResponseMessage() throws IOException {
        connect();
        return responseMessage;
    }

    @Override
    public String getHeaderField(String key) {
        var values = responseHeaders.get(key);
        return values == null ? null : values.get(0);
    }

    @Override
    public Map<String, List<String>> getHeaderFields() {
        return Collections.unmodifiableMap(responseHeaders);
    }

    @Override
    public List<String> getHeaderFields(String key) {
        return responseHeaders.getOrDefault(key, List.of());
    }

    @Override
    public String getContentType() {
        return getHeaderField("Content-Type");
    }

    @Override
    public int getContentLength() {
        try {
            return Integer.parseInt(getHeaderField("Content-Length"));
        } catch (Exception ex) {
            return -1;
        }
    }

    @Override
    public OutputStream getOutputStream() {
        return new FilterOutputStream(output) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                check(len);
                out.write(b, off, len);
            }

            @Override
            public void write(int b) throws IOException {
                check(1);
                out.write(b);
            }

            private void check(int len) throws IOException {
                if (output.size() + len > 16 * 1024 * 1024) throw new IOException("Git request too large");
            }
        };
    }

    @Override
    public URL getURL() {
        return url;
    }

    @Override
    public void setRequestMethod(String method) throws ProtocolException {
        if (!Set.of("GET", "POST").contains(method)) throw new ProtocolException("Unsupported Git HTTP method");
        this.method = method;
    }

    @Override
    public String getRequestMethod() {
        return method;
    }

    @Override
    public void setRequestProperty(String key, String value) {
        if (key.contains("\r") || key.contains("\n") || value.contains("\r") || value.contains("\n"))
            throw new IllegalArgumentException("Invalid HTTP header");
        requestHeaders.put(key, value);
    }

    @Override
    public void setConnectTimeout(int value) {
        connectTimeout = Math.max(1, Math.min(value, 30000));
    }

    @Override
    public void setReadTimeout(int value) {
        readTimeout = Math.max(1, Math.min(value, 30000));
    }

    @Override
    public boolean usingProxy() {
        return false;
    }

    @Override
    public void setInstanceFollowRedirects(boolean value) {
    }

    @Override
    public void setFixedLengthStreamingMode(int value) {
    }

    @Override
    public void setChunkedStreamingMode(int value) {
    }

    @Override
    public void setDoOutput(boolean value) {
    }

    @Override
    public void setUseCaches(boolean value) {
    }

    @Override
    public void configure(KeyManager[] key, TrustManager[] trust, SecureRandom random) throws KeyManagementException {
        if (key != null || trust != null) throw new KeyManagementException("Custom Git TLS credentials are disabled");
    }

    @Override
    public void setHostnameVerifier(HostnameVerifier verifier) throws KeyManagementException {
        throw new KeyManagementException("TLS hostname verification cannot be disabled");
    }
}
