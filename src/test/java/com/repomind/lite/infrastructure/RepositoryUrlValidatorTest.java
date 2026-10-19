package com.repomind.lite.infrastructure;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.*;

class RepositoryUrlValidatorTest {
    private final RepositoryUrlValidator validator = new RepositoryUrlValidator(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")});

    @Test
    void canonicalizesStableRemoteAndDefaultRef() {
        var remote = validator.validate("https://GITHUB.com/acme/demo.git/", null);
        assertThat(remote.url()).isEqualTo("https://github.com/acme/demo.git");
        assertThat(remote.ref()).isEqualTo("HEAD");
        assertThat(remote.key()).isEqualTo(validator.validate("https://github.com/acme/demo", "HEAD").key());
    }

    @Test
    void rejectsCredentialsAndUnsafeRefs() {
        assertThatThrownBy(() -> validator.validate("https://token@github.com/acme/demo", null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> validator.validate("https://github.com/acme/demo", "--upload-pack=evil")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> validator.validate("http://github.com/acme/demo", null)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsEveryPrivateDnsAnswer() {
        var privateDns = new RepositoryUrlValidator(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1")});
        assertThatThrownBy(() -> privateDns.validate("https://github.com/acme/demo", null)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsMappedIpv6AndMetadataAddresses() {
        for (String ip : new String[]{"127.0.0.1", "169.254.169.254", "192.168.1.2", "::1", "fc00::1", "fe80::1", "100.64.0.1", "0.0.0.0"}) {
            var privateDns = new RepositoryUrlValidator(host -> new InetAddress[]{InetAddress.getByName(ip)});
            assertThatThrownBy(() -> privateDns.validate("https://example.com/repo", null)).isInstanceOf(RuntimeException.class);
        }
    }
}
