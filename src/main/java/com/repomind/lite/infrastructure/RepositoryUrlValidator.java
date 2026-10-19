package com.repomind.lite.infrastructure;

import com.repomind.lite.errors.BusinessException;
import org.springframework.stereotype.Component;
import org.eclipse.jgit.lib.Repository;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

@Component
public class RepositoryUrlValidator {
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public record Remote(String url, String ref, String key) {
    }

    private final Resolver resolver;

    public RepositoryUrlValidator() {
        this(InetAddress::getAllByName);
    }

    public RepositoryUrlValidator(Resolver resolver) {
        this.resolver = resolver;
    }

    public Remote validate(String value, String requestedRef) {
        try {
            if (value == null || value.length() > 2048) throw invalid();
            URI uri = new URI(value.strip());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443))
                throw invalid();
            host = host.toLowerCase(Locale.ROOT);
            if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") || !host.contains("."))
                throw invalid();
            InetAddress[] addresses = resolver.resolve(host);
            if (addresses.length == 0) throw invalid();
            for (InetAddress address : addresses) if (!isPublic(address)) throw invalid();
            String path = uri.getRawPath();
            if (path == null || path.isBlank() || path.equals("/") || path.contains("%") || path.contains("\\") || path.contains("//") || path.contains("/../") || path.contains("/./"))
                throw invalid();
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);
            if (path.isEmpty() || path.endsWith("/..") || path.endsWith("/.")) throw invalid();
            String url = "https://" + host + path + ".git";
            String ref = requestedRef == null || requestedRef.isBlank() ? "HEAD" : requestedRef.strip();
            if (ref.length() > 255 || (!ref.equals("HEAD") && !ref.matches("[a-fA-F0-9]{7,40}") && !Repository.isValidRefName("refs/heads/" + ref)) || ref.startsWith("-") || ref.contains("@{"))
                throw BusinessException.badRequest("Repository ref is invalid");
            String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((url + "\n" + ref).getBytes(StandardCharsets.UTF_8)));
            return new Remote(url, ref, key);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid();
        }
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress())
            return false;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 255, second = b[1] & 255;
            int third = b[2] & 255;
            return first != 0 && first != 10 && first != 127 && first < 224 && !(first == 100 && second >= 64 && second <= 127) && !(first == 169 && second == 254) && !(first == 172 && second >= 16 && second <= 31) && !(first == 192 && (second == 168 || (second == 0 && (third == 0 || third == 2)) || (second == 88 && third == 99))) && !(first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100))) && !(first == 203 && second == 0 && third == 113);
        }
        return (b[0] & 0xe0) == 0x20 && !((b[0] & 255) == 0x20 && (b[1] & 255) == 0x01 && (b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8);
    }

    private static BusinessException invalid() {
        return BusinessException.badRequest("Repository must use a public HTTPS Git URL without credentials");
    }
}
