package com.databuff.apm.ingest.nginx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Resolve caller service name from IP through an external HTTP API.
 *
 * <p>Features:</p>
 * <ul>
 *     <li>TTL cache</li>
 *     <li>Same-IP single-flight</li>
 *     <li>Global concurrency control for different IPs</li>
 *     <li>Bounded wait and IP fallback when concurrency limit is reached</li>
 *     <li>Successful "not found" result is cached as IP itself</li>
 *     <li>Transient HTTP/network failures are cached briefly</li>
 * </ul>
 */
public final class IpServiceResolver implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IpServiceResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Default cache TTL: 1 hour. */
    private static final long DEFAULT_TTL_MS = 60L * 60L * 1000L;

    /** Maximum number of cache entries. */
    private static final int DEFAULT_MAX_SIZE = 50_000;

    /** HTTP connect timeout. */
    private static final int CONNECT_TIMEOUT_MS = 1_000;

    /** HTTP read timeout. */
    private static final int READ_TIMEOUT_MS = 1_500;

    /** Maximum number of remote requests executing concurrently. */
    private static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 5;

    /** Maximum time to wait for an outbound request permit before falling back to the IP. */
    private static final long REQUEST_PERMIT_WAIT_MS = 100L;

    /** Short cache duration for transient lookup failures or a saturated request limiter. */
    private static final long FAILURE_CACHE_TTL_MS = 5_000L;

    /** Maximum number of failed IPs waiting for asynchronous retry. */
    private static final int DEFAULT_RETRY_QUEUE_CAPACITY = 10_000;

    /** Delay between asynchronous retry requests. */
    private static final long RETRY_DELAY_MS = FAILURE_CACHE_TTL_MS;

    private final String endpoint;
    private final String singleFieldName;
    private final Cache cache;
    private final Semaphore semaphore;
    private final BlockingQueue<String> retryQueue;
    private final Set<String> retryQueued = ConcurrentHashMap.newKeySet();
    private final ExecutorService retryExecutor;
    private final AtomicBoolean retryWorkerStarted = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public IpServiceResolver(String endpoint, String singleFieldName) {
        this(endpoint, singleFieldName, DEFAULT_TTL_MS, DEFAULT_MAX_SIZE, DEFAULT_MAX_CONCURRENT_REQUESTS);
    }

    public IpServiceResolver(String endpoint, String singleFieldName, long ttlMs, int maxSize) {
        this(endpoint, singleFieldName, ttlMs, maxSize, DEFAULT_MAX_CONCURRENT_REQUESTS);
    }

    public IpServiceResolver(String endpoint, String singleFieldName, int maxConcurrentRequests) {
        this(endpoint, singleFieldName, DEFAULT_TTL_MS, DEFAULT_MAX_SIZE, maxConcurrentRequests);
    }

    public IpServiceResolver(String endpoint, String singleFieldName, long ttlMs, int maxSize, int maxConcurrentRequests) {
        this.endpoint = normalizeEndpoint(endpoint);
        this.singleFieldName = singleFieldName;
        this.retryQueue = this.endpoint == null
                ? null
                : new LinkedBlockingQueue<>(DEFAULT_RETRY_QUEUE_CAPACITY);
        this.retryExecutor = this.endpoint == null
                ? null
                : Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "ip-service-resolver-retry");
                    thread.setDaemon(true);
                    return thread;
                });
        this.cache = this.endpoint == null
                ? null
                : new Cache(ttlMs, maxSize, FAILURE_CACHE_TTL_MS, this::enqueueRetry);
        this.semaphore = new Semaphore(Math.max(1, maxConcurrentRequests), true);
    }

    /**
     * Resolve service name.
     *
     * @param host bare IP, IP:port, IPv6 or [IPv6]:port
     * @return resolved service name, otherwise IP itself
     */
    public String resolve(String host) {
        if (host == null || host.isBlank()) {
            return host;
        }
        String ip = bareIp(host.trim());
        if (ip == null || ip.isBlank()) {
            return host.trim();
        }
        if (cache == null) {
            return ip;
        }
        return cache.getOrLoad(ip, () -> fetchRemote(ip));
    }

    /**
     * Remote query with global concurrency control.
     *
     * <p>When concurrency is full, wait briefly and use the IP fallback instead of blocking the
     * ingestion thread for an unbounded time.</p>
     */
    private FetchResult fetchRemote(String ip) {
        if (!isIpAddress(ip)) {
            return FetchResult.success(ip);
        }
        String cached = cache.get(ip);
        if (cached != null) {
            log.debug("IpServiceResolver skip remote query, cache hit, ip={}", ip);
            return FetchResult.success(cached);
        }
        boolean acquired = false;
        try {
            if (!semaphore.tryAcquire(REQUEST_PERMIT_WAIT_MS, TimeUnit.MILLISECONDS)) {
                log.debug("IpServiceResolver request limit reached, use IP fallback, ip={}", ip);
                return FetchResult.failure();
            }
            acquired = true;
            return doFetch(ip);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("IpServiceResolver interrupted while waiting for permit, ip={}", ip);
            return FetchResult.failure();
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    /**
     * Execute actual HTTP request.
     */
    private FetchResult doFetch(String ip) {
        long startedAt = System.nanoTime();
        HttpURLConnection connection = null;
        try {
            String url = endpoint + (endpoint.contains("?") ? "&" : "?") + "ip=" + URLEncoder.encode(ip, StandardCharsets.UTF_8);
            log.debug("IpServiceResolver querying ip={}, endpoint={}", ip, endpoint);
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                log.warn("IpServiceResolver HTTP {}, ip={}, durationMs={}", status, ip, elapsedMs(startedAt));
                return FetchResult.failure();
            }
            String body = readBody(connection);
            if (body == null || body.isBlank()) {
                log.debug("IpServiceResolver no service found, cache ip itself, ip={}, durationMs={}", ip, elapsedMs(startedAt));
                return FetchResult.success(ip);
            }
            String serviceName = extractServiceName(body);
            if (serviceName == null || serviceName.isBlank()) {
                log.debug("IpServiceResolver service not found, cache ip itself, ip={}, durationMs={}", ip, elapsedMs(startedAt));
                return FetchResult.success(ip);
            }
            serviceName = normalizeServiceName(serviceName);
            log.debug("IpServiceResolver resolved ip={}, service={}, durationMs={}", ip, serviceName, elapsedMs(startedAt));
            return FetchResult.success(serviceName);
        } catch (Exception e) {
            log.warn("IpServiceResolver request failed, ip={}, durationMs={}, error={}", ip, elapsedMs(startedAt), e.getMessage());
            return FetchResult.failure();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Enqueue a failed qualifying IP once and start the retry worker lazily.
     *
     * <p>The queue is deliberately bounded so a resolver outage cannot grow memory without
     * limit. The current request has already returned the IP fallback; the retry only warms the
     * cache for a later log record.</p>
     */
    private void enqueueRetry(String ip) {
        if (retryQueue == null || closed.get() || !isIpAddress(ip) || bareIp(ip) == null) {
            return;
        }
        if (!retryQueued.add(ip)) {
            return;
        }
        if (!retryQueue.offer(ip)) {
            retryQueued.remove(ip);
            log.debug("IpServiceResolver retry queue full, drop ip={}", ip);
            return;
        }
        startRetryWorker();
    }

    private void startRetryWorker() {
        if (retryExecutor == null || closed.get()
                || !retryWorkerStarted.compareAndSet(false, true)) {
            return;
        }
        try {
            retryExecutor.execute(this::retryLoop);
        } catch (RejectedExecutionException e) {
            retryWorkerStarted.set(false);
            retryQueue.clear();
            retryQueued.clear();
            log.debug("IpServiceResolver retry worker rejected", e);
        }
    }

    /** Process at most one retry every RETRY_DELAY_MS. */
    private void retryLoop() {
        String currentIp = null;
        try {
            while (!closed.get()) {
                currentIp = retryQueue.take();
                TimeUnit.MILLISECONDS.sleep(RETRY_DELAY_MS);
                if (closed.get()) {
                    break;
                }
                retryOne(currentIp);
                currentIp = null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (currentIp != null && !closed.get()) {
                retryQueued.remove(currentIp);
                enqueueRetry(currentIp);
            }
        } finally {
            retryWorkerStarted.set(false);
            if (!closed.get() && retryQueue != null && !retryQueue.isEmpty()) {
                startRetryWorker();
            }
        }
    }

    private void retryOne(String ip) {
        boolean retryAgain = false;
        try {
            // Do not replace a successful value that another request populated meanwhile.
            if (!cache.prepareRetry(ip)) {
                return;
            }
            resolve(ip);
            retryAgain = cache.isRetryable(ip);
        } catch (RuntimeException e) {
            retryAgain = true;
            log.debug("IpServiceResolver asynchronous retry failed, ip={}", ip, e);
        } finally {
            retryQueued.remove(ip);
        }
        if (retryAgain) {
            enqueueRetry(ip);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (retryExecutor != null) {
            retryExecutor.shutdownNow();
        }
        if (retryQueue != null) {
            retryQueue.clear();
        }
        retryQueued.clear();
    }

    /**
     * Parse response.
     *
     * <p>Preferred format:</p>
     *
     * <pre>
     * {
     *   "appModule": "uletm",
     *   "appName": "apm-service"
     * }
     * </pre>
     *
     * <p>Result: uletm/apm-service</p>
     */
    private String extractServiceName(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            String module = textOrNull(root, "appModule");
            String appName = textOrNull(root, "appName");
            if (hasText(module) && hasText(appName)) {
                return module.trim() + "/" + appName.trim();
            }
            if (hasText(module)) {
                return module.trim();
            }
            if (hasText(appName)) {
                return appName.trim();
            }
            if (hasText(singleFieldName)) {
                String value = textOrNull(root, singleFieldName);
                if (hasText(value)) {
                    return value.trim();
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("IpServiceResolver cannot parse response: {}", e.getMessage());
            throw new IllegalStateException("Cannot parse resolver response", e);
        }
    }

    private static String textOrNull(JsonNode root, String field) {
        if (root == null || field == null) {
            return null;
        }
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Normalize host into bare IP.
     *
     * <pre>
     * 10.1.1.1:8080       -> 10.1.1.1
     * [2001:db8::1]:8080  -> 2001:db8::1
     * 2001:db8::1         -> 2001:db8::1
     * </pre>
     */
    static String bareIp(String host) {
        if (!hasText(host)) {
            return host;
        }
        String value = host.trim();
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close > 0) {
                value= value.substring(1, close);
            }
            if(!value.startsWith("172.25")){
                return null;
            }
        }
        int firstColon = value.indexOf(':');
        int lastColon = value.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon) {
            String port = value.substring(lastColon + 1);
            if (!port.isEmpty() && port.chars().allMatch(Character::isDigit)) {
                value = value.substring(0, lastColon);
            }
        }
        if(!value.startsWith("172.25")){
            return null;
        }
        return value;
    }

    /**
     * Lightweight IP validation.
     */
    public static boolean isIpAddress(String value) {
        if (!hasText(value)) {
            return false;
        }
        String v = value.trim();
        if (v.chars().allMatch(c -> c == '.' || (c >= '0' && c <= '9'))) {
            return true;
        }
        return v.contains(":");
    }

    /**
     * Strip trailing "-group" from service name.
     */
    public static String normalizeServiceName(String serviceName) {
        if (!hasText(serviceName)) {
            return serviceName;
        }
        String value = serviceName.trim();
        return value.endsWith("-group") ? value.substring(0, value.length() - "-group".length()) : value;
    }

    private static String readBody(HttpURLConnection connection) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
            return result.toString();
        }
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static String normalizeEndpoint(String endpoint) {
        if (!hasText(endpoint)) {
            return null;
        }
        String value = endpoint.trim();
        return "none".equalsIgnoreCase(value) ? null : value;
    }

    /**
     * Remote query result.
     *
     * <p>success=true:</p>
     * <ul>
     *     <li>value = serviceName when resolved</li>
     *     <li>value = IP when service does not exist</li>
     * </ul>
     *
     * <p>success=false:</p>
     * <ul>
     *     <li>HTTP error</li>
     *     <li>timeout</li>
     *     <li>connection failure</li>
     *     <li>JSON parsing failure</li>
     *     <li>interrupted</li>
     * </ul>
     */
    private record FetchResult(boolean success, String value) {

        static FetchResult success(String value) {
            return new FetchResult(true, value);
        }

        static FetchResult failure() {
            return new FetchResult(false, null);
        }
    }

    /**
     * TTL Cache + Same-IP SingleFlight.
     *
     * <p>Cache value is always an actual String:</p>
     *
     * <pre>
     * 10.1.1.1 -> uletm/apm-service
     *
     * or
     *
     * 10.1.1.2 -> 10.1.1.2
     * </pre>
     */
    private static final class Cache {

        private final long ttlMs;
        private final int maxSize;
        private final long failureTtlMs;
        private final Consumer<String> retryHandler;

        /**
         * Completed cache entries.
         */
        private final Map<String, Entry> entries = new ConcurrentHashMap<>();

        /**
         * Currently loading IPs.
         *
         * <p>Guarantees that the same IP has at most one remote request.</p>
         */
        private final Map<String, CompletableFuture<FetchResult>> loading = new ConcurrentHashMap<>();

        private Cache(long ttlMs, int maxSize, long failureTtlMs, Consumer<String> retryHandler) {
            this.ttlMs = Math.max(1L, ttlMs);
            this.maxSize = Math.max(1, maxSize);
            this.failureTtlMs = Math.max(1L, failureTtlMs);
            this.retryHandler = retryHandler;
        }

        /**
         * Get cached value or load from remote.
         */
        String getOrLoad(String key, Supplier<FetchResult> loader) {
            String cached = get(key);
            if (cached != null) {
                return cached;
            }
            CompletableFuture<FetchResult> future = new CompletableFuture<>();
            CompletableFuture<FetchResult> existing = loading.putIfAbsent(key, future);
            if (existing != null) {
                log.debug("IpServiceResolver wait for in-flight query, ip={}", key);
                try {
                    FetchResult result = existing.join();
                    return result.success() ? result.value() : key;
                } catch (RuntimeException e) {
                    log.warn("IpServiceResolver waiting for in-flight query failed, ip={}, error={}", key, e.getMessage());
                    return key;
                }
            }
            try {
                /*
                 * Double-check after becoming SingleFlight owner.
                 *
                 * Another thread may have populated the cache immediately
                 * before this thread became owner.
                 */
                cached = get(key);
                if (cached != null) {
                    FetchResult result = FetchResult.success(cached);
                    future.complete(result);
                    return cached;
                }
                FetchResult result = loader.get();
                if (result == null || !result.success() || !hasText(result.value())) {
                    result = FetchResult.failure();
                    put(key, key, failureTtlMs, true);
                    requestRetry(key);
                } else {
                    put(key, result.value(), ttlMs);
                }
                future.complete(result);
                return result.success() ? result.value() : key;
            } catch (RuntimeException | Error e) {
                future.completeExceptionally(e);
                throw e;
            } finally {
                loading.remove(key, future);
            }
        }

        /**
         * Read cache.
         *
         * <p>Expired entries are served stale and refreshed asynchronously.</p>
         */
        private String get(String key) {
            Entry entry = entries.get(key);
            if (entry == null) {
                return null;
            }
            if (entry.expireAt() <= System.currentTimeMillis()) {
                requestRetry(key);
            }
            return entry.value();
        }

        private void requestRetry(String key) {
            if (retryHandler == null) {
                return;
            }
            try {
                retryHandler.accept(key);
            } catch (RuntimeException e) {
                log.debug("IpServiceResolver failed to enqueue retry, ip={}", key, e);
            }
        }

        /**
         * Put value into cache.
         */
        private void put(String key, String value, long ttl) {
            put(key, value, ttl, false);
        }

        private void put(String key, String value, long ttl, boolean retryable) {
            if (value == null) {
                return;
            }
            entries.put(key, new Entry(value, System.currentTimeMillis() + Math.max(1L, ttl), retryable));
            while (entries.size() > maxSize && evictOne()) {
                // Keep the cache bounded even when multiple threads insert concurrently.
            }
        }

        private boolean prepareRetry(String key) {
            Entry entry = entries.get(key);
            if (entry == null) {
                return true;
            }
            if (entry.expireAt() <= System.currentTimeMillis()) {
                entries.remove(key, entry);
                return true;
            }
            return entry.retryable() && entries.remove(key, entry);
        }

        private boolean isRetryable(String key) {
            Entry entry = entries.get(key);
            if (entry == null) {
                return false;
            }
            if (entry.expireAt() <= System.currentTimeMillis()) {
                entries.remove(key, entry);
                return false;
            }
            return entry.retryable();
        }

        /**
         * Prefer removing an expired entry.
         *
         * <p>If no expired entry exists, remove one arbitrary entry.</p>
         */
        private boolean evictOne() {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Entry> entry : entries.entrySet()) {
                if (entry.getValue().expireAt() <= now && entries.remove(entry.getKey(), entry.getValue())) {
                    return true;
                }
            }
            String key = entries.keySet().stream().findFirst().orElse(null);
            return key != null && entries.remove(key) != null;
        }

        int size() {
            return entries.size();
        }

        int loadingSize() {
            return loading.size();
        }

        private record Entry(String value, long expireAt, boolean retryable) {
        }
    }
}