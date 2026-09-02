package com.databuff.apm.ingest.nginx;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kafka consumer for nginx access logs (modern consumer API for Kafka 0.10+).
 * <p>
 * Kafka 上的消息本身就是 JSON 字符串（nginx 访问日志，或 ES {@code _source} 包装），用
 * {@link StringDeserializer} 直接读取。每个 poll 批次收集 JSON 后批量交给
 * {@link NginxIngestService#ingestLogLines(List)}：内部将 JSON 日志转为 OTLP 请求并喂给
 * 标准 {@code /v1/traces} + {@code /v1/metrics} + {@code /v1/logs} 管线落盘 Doris，与 agent
 * 上传同构，全程无需 protobuf 序列化信封。
 * <p>
 * 消费采用手动提交（{@code enable.auto.commit=false}）：每个 poll 批次在全部入库成功后才
 * {@code commitSync}，之后再拉取下一批；提交失败会重试，仍失败则本批偏移保持落后，重平衡或
 * 重启后重新消费（at-least-once，最多重复一个批次，不丢数据）。
 * Managed as a Spring bean with initMethod/destroyMethod.
 */
public class NginxKafkaReceiver {

    private static final Logger log = LoggerFactory.getLogger(NginxKafkaReceiver.class);

    /** Poll retries for transient errors (network blips, coordinator timeouts) before giving up. */
    private static final int MAX_POLL_RETRIES = 3;
    private static final long POLL_RETRY_BACKOFF_MS = 2000L;

    private final String bootstrapServers;
    private final String topic;
    private final String groupId;
    private final int maxPollRecords;
    private final int pollTimeoutMs;
    private final String autoOffsetReset;
    private final NginxIngestService ingestService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;
    private KafkaConsumer<String, String> consumer;

    public NginxKafkaReceiver(
            String bootstrapServers,
            String topic,
            String groupId,
            int maxPollRecords,
            int pollTimeoutMs,
            String autoOffsetReset,
            NginxIngestService ingestService) {
        this.bootstrapServers = bootstrapServers;
        this.topic = topic;
        this.groupId = groupId;
        this.maxPollRecords = maxPollRecords;
        this.pollTimeoutMs = pollTimeoutMs;
        this.autoOffsetReset = autoOffsetReset;
        this.ingestService = ingestService;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        validateBrokerConnection();

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
//        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords); // max.poll.records
        props.put("max.poll.records", maxPollRecords); // 低版本的jar 没有MAX_POLL_RECORDS_CONFIG这个常量
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        // Manual commit: each poll batch is committed only after it has been fully ingested,
        // so offsets never lead processing. At-least-once is preserved (a crash between
        // processing and commit re-delivers the batch), but the duplicate window shrinks from
        // the old 5s auto-commit interval to a single in-flight batch.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        this.consumer = new KafkaConsumer<>(props);
        this.consumer.subscribe(Collections.singletonList(topic));

        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nginx-kafka-receiver");
            t.setDaemon(true);
            return t;
        });

        executor.submit(this::pollLoop);
        log.info("Nginx Kafka receiver started: topic={}, group={}, bootstrap={}, autoOffsetReset={}", topic, groupId, bootstrapServers, autoOffsetReset);
    }

    private void validateBrokerConnection() {
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        adminProps.put(AdminClientConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG, 10000);

        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            adminClient.listTopics().names().get(10, TimeUnit.SECONDS);
            log.info("Kafka broker connection validated: {}", bootstrapServers);
        } catch (Exception e) {
            String errorMsg = String.format("Failed to connect to Kafka broker at %s: %s", bootstrapServers, e.getMessage());
            log.error(errorMsg, e);
            throw new IllegalStateException(errorMsg, e);
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (consumer != null) {
            consumer.wakeup();
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
        if (consumer != null) {
            consumer.close();
        }
        log.info("Nginx Kafka receiver stopped");
    }

    private void pollLoop() {
        while (running.get()) {
            ConsumerRecords<String, String> records = null;
            // Poll with bounded retries. Transient errors (network blips, coordinator timeouts)
            // are retried with backoff; a persistent failure or shutdown (WakeupException) stops
            // the loop instead of spinning forever.
            for (int attempt = 1; attempt <= MAX_POLL_RETRIES && records == null; attempt++) {
                try {
                    records = consumer.poll(Duration.ofMillis(pollTimeoutMs));
                } catch (WakeupException we) {
                    // consumer.wakeup() was called from stop() — exit promptly, no retry
                    break;
                } catch (Exception e) {
                    if (!running.get()) {
                        break;
                    }
                    if (attempt == MAX_POLL_RETRIES) {
                        log.error("Nginx Kafka poll failed after {} attempts, consumer stopped: {}",
                                MAX_POLL_RETRIES, e.getMessage(), e);
                        break;
                    }
                    log.warn("Nginx Kafka poll failed (attempt {}/{}), retrying: {}",
                            attempt, MAX_POLL_RETRIES, e.getMessage());
                    try {
                        Thread.sleep(POLL_RETRY_BACKOFF_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (records == null) {
                break;
            }
            if (records.isEmpty()) {
                continue;
            }

            List<String> batch = new ArrayList<>(records.count());
            for (ConsumerRecord<String, String> record : records) {
                String value = record.value();
                if (value != null && !value.isBlank()) {
                    batch.add(value);
                }
            }
            try {
                int total = ingestService.ingestLogLines(batch);
                if (total > 0) {
                    log.debug("Ingested {} spans from {} nginx log lines on topic {}", total, batch.size(), topic);
                }
                // Commit this batch synchronously BEFORE polling the next one.
                // A failed commit (transient network/rebalance) is retried a few times; if it
                // still fails, offsets stay behind and the batch is re-delivered after a
                // rebalance/restart (at-least-once), never lost.
                commitSyncWithRetry();
            } catch (Exception e) {
                if (running.get()) {
                    log.error("Nginx Kafka batch ingest/commit error: {}", e.getMessage(), e);
                }
            }
        }
    }

    private void commitSyncWithRetry() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                consumer.commitSync();
                return;
            } catch (Exception e) {
                if (attempt == 3 || !running.get()) {
                    throw e;
                }
                log.warn("Nginx Kafka offset commit failed (attempt {}/3), retrying: {}", attempt, e.getMessage());
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
