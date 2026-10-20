package com.repomind.lite.infrastructure;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

class GitProxyTest {
    @Test
    void parsesProxySettings() {
        assertThat(GitRepositoryService.parseProxy("")).isNull();
        assertThat(GitRepositoryService.parseProxy(null)).isNull();
        var parsed = GitRepositoryService.parseProxy("127.0.0.1:7897");
        assertThat(parsed.getHostString()).isEqualTo("127.0.0.1");
        assertThat(parsed.getPort()).isEqualTo(7897);
        assertThat(GitRepositoryService.parseProxy("http://proxy.local:8080").getPort()).isEqualTo(8080);
        assertThatThrownBy(() -> GitRepositoryService.parseProxy("socks5://127.0.0.1:1080")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GitRepositoryService.parseProxy("http://user:pw@127.0.0.1:7897")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GitRepositoryService.parseProxy("127.0.0.1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sendsConnectForTargetHostAndReportsProxyRefusal() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var request = new CompletableFuture<String>();
            var thread = new Thread(() -> {
                try (Socket client = server.accept()) {
                    var reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
                    request.complete(reader.readLine());
                    client.getOutputStream().write("HTTP/1.1 403 Forbidden\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                } catch (IOException ex) {
                    request.completeExceptionally(ex);
                }
            });
            thread.start();
            var proxy = InetSocketAddress.createUnresolved("127.0.0.1", server.getLocalPort());
            var connection = new PinnedHttpsConnection(new URL("https://github.com/acme/demo.git/info/refs"), true, proxy);
            assertThatThrownBy(connection::connect)
                    .isInstanceOf(ConnectException.class)
                    .hasMessageContaining("via proxy 127.0.0.1:" + server.getLocalPort())
                    .hasMessageContaining("403");
            assertThat(request.get(5, TimeUnit.SECONDS)).isEqualTo("CONNECT github.com:443 HTTP/1.1");
        }
    }

    @Test
    void unreachableProxyIsReportedAsUnreachable() throws Exception {
        int port;
        try (var probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        var connection = new PinnedHttpsConnection(new URL("https://github.com/acme/demo.git/info/refs"), true,
                InetSocketAddress.createUnresolved("127.0.0.1", port));
        assertThatThrownBy(connection::connect)
                .isInstanceOf(ConnectException.class)
                .hasMessageContaining("via proxy");
    }
}
