package com.repomind.lite.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;

@Component
public class DnsResolver implements RepositoryUrlValidator.Resolver {
    private static final Logger log = LoggerFactory.getLogger(DnsResolver.class);
    private static final Duration DNS_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(InetAddress[] addresses, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        // Check cache first
        CacheEntry cached = cache.get(host);
        if (cached != null && !cached.isExpired()) {
            log.debug("DNS cache hit for {}", host);
            return cached.addresses();
        }

        // Resolve with timeout
        Future<InetAddress[]> future = executor.submit(() -> InetAddress.getAllByName(host));
        try {
            InetAddress[] addresses = future.get(DNS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            // Cache the result
            cache.put(host, new CacheEntry(addresses,
                System.currentTimeMillis() + CACHE_TTL.toMillis()));

            log.debug("DNS resolved {} to {} addresses", host, addresses.length);
            return addresses;
        } catch (TimeoutException ex) {
            future.cancel(true);
            log.warn("DNS resolution timeout for {}", host);
            throw new UnknownHostException("DNS resolution timeout for " + host +
                " (waited " + DNS_TIMEOUT.toSeconds() + " seconds)");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new UnknownHostException("DNS resolution interrupted for " + host);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof UnknownHostException) {
                throw (UnknownHostException) cause;
            }
            throw new UnknownHostException("DNS resolution failed for " + host + ": " + cause.getMessage());
        }
    }
}
