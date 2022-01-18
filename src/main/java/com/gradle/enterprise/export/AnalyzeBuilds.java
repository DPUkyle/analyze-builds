package com.gradle.enterprise.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Sets;
import com.google.common.io.Resources;
import com.google.common.util.concurrent.ForwardingBlockingQueue;
import com.google.common.util.concurrent.MoreExecutors;
import okhttp3.ConnectionPool;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BinaryOperator;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableSortedMap.copyOfSorted;
import static java.time.Instant.now;

public final class AnalyzeBuilds {

    private static final HttpUrl GRADLE_ENTERPRISE_SERVER_URL = HttpUrl.parse("https://ge.gradle.org");
    private static final String EXPORT_API_ACCESS_KEY = System.getenv("EXPORT_API_ACCESS_KEY");
    private static final int MAX_BUILD_SCANS_STREAMED_CONCURRENTLY = 30;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ZERO)
                .readTimeout(Duration.ZERO)
                .retryOnConnectionFailure(true)
                .connectionPool(new ConnectionPool(MAX_BUILD_SCANS_STREAMED_CONCURRENTLY, 30, TimeUnit.SECONDS))
                .authenticator(Authenticators.bearerToken(EXPORT_API_ACCESS_KEY))
                .protocols(ImmutableList.of(Protocol.HTTP_1_1))
                .build();
        httpClient.dispatcher().setMaxRequests(MAX_BUILD_SCANS_STREAMED_CONCURRENTLY);
        httpClient.dispatcher().setMaxRequestsPerHost(MAX_BUILD_SCANS_STREAMED_CONCURRENTLY);

        EventSource.Factory eventSourceFactory = EventSources.createFactory(httpClient);
//        Stream<String> builds = queryBuildsFromPast(Duration.ofHours(2), eventSourceFactory);
        Stream<String> builds = loadLocalBuilds("local-builds-28-days.txt");
        BuildStatistics result = builds
                .parallel()
                .map(buildId -> {
                    ProcessBuildInfo projectFilter = new ProcessBuildInfo(buildId);
                    eventSourceFactory.newEventSource(requestBuildInfo(buildId), projectFilter);
                    return projectFilter.getResult();
                })
                .map(future -> future.thenCompose(filterResult -> {
                    if (filterResult.matches) {
                        ProcessTaskEvents buildProcessor = new ProcessTaskEvents(filterResult.buildId, filterResult.maxWorkers);
                        eventSourceFactory.newEventSource(requestTaskEvents(filterResult.buildId), buildProcessor);
                        return buildProcessor.getResult();
                    } else {
                        return CompletableFuture.completedFuture(BuildStatistics.EMPTY);
                    }
                }))
                .map(statsResult -> {
                    try {
                        return statsResult.get(1, TimeUnit.HOURS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .reduce(BuildStatistics::merge)
                .orElse(BuildStatistics.EMPTY);

        result.print();

        // Cleanly shuts down the HTTP client, which speeds up process termination
        shutdown(httpClient);
    }

    private static Stream<String> loadLocalBuilds(String fileName) throws IOException {
        return Resources.readLines(Resources.getResource(fileName), StandardCharsets.UTF_8).stream();
    }

    @NotNull
    private static Stream<String> queryBuildsFromPast(Duration duration, EventSource.Factory eventSourceFactory) {
        StreamableQueue<String> buildQueue = new StreamableQueue<>("FINISHED");
        FilterBuildsByBuildTool buildToolFilter = new FilterBuildsByBuildTool(eventSourceFactory, buildQueue);
        eventSourceFactory.newEventSource(requestBuilds(now().minus(duration)), buildToolFilter);
        return buildQueue.stream();
    }

    @NotNull
    private static Request requestBuilds(Instant since) {
        return new Request.Builder()
                .url(GRADLE_ENTERPRISE_SERVER_URL.resolve("/build-export/v2/builds/since/" + since.toEpochMilli()))
                .build();
    }

    @NotNull
    private static Request requestBuildInfo(String buildId) {
        return new Request.Builder()
                .url(GRADLE_ENTERPRISE_SERVER_URL.resolve("/build-export/v2/build/" + buildId + "/events?eventTypes=ProjectStructure,UserTag,BuildModes"))
                .build();
    }

    @NotNull
    private static Request requestTaskEvents(String buildId) {
        return new Request.Builder()
                .url(GRADLE_ENTERPRISE_SERVER_URL.resolve("/build-export/v2/build/" + buildId + "/events?eventTypes=TaskStarted,TaskFinished"))
                .build();
    }

    private static class FilterBuildsByBuildTool extends PrintFailuresEventSourceListener {
        private final EventSource.Factory eventSourceFactory;
        private final StreamableQueue<String> buildQueue;

        private FilterBuildsByBuildTool(EventSource.Factory eventSourceFactory, StreamableQueue<String> buildQueue) {
            this.eventSourceFactory = eventSourceFactory;
            this.buildQueue = buildQueue;
        }

        @Override
        public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
            System.out.println("Streaming builds...");
        }

        @Override
        public void onEvent(@NotNull EventSource eventSource, @Nullable String id, @Nullable String type, @NotNull String data) {
            JsonNode json = parse(data);
            JsonNode buildToolJson = json.get("toolType");
            if (buildToolJson != null && buildToolJson.asText().equals("gradle")) {
                String buildId = json.get("buildId").asText();
                try {
                    buildQueue.put(buildId);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        @Override
        public void onClosed(@NotNull EventSource eventSource) {
            System.out.println("Finished querying builds");
            try {
                buildQueue.close();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class ProcessBuildInfo extends PrintFailuresEventSourceListener {
        public static class Result {
            public final String buildId;
            public final boolean matches;
            public final int maxWorkers;

            public Result(String buildId, boolean matches, int maxWorkers) {
                this.buildId = buildId;
                this.matches = matches;
                this.maxWorkers = maxWorkers;
            }
        }

        private final String buildId;
        private final CompletableFuture<Result> result = new CompletableFuture<>();
        private final List<String> rootProjects = new ArrayList<>();
        private final List<String> tags = new ArrayList<>();
        private int maxWorkers;

        private ProcessBuildInfo(String buildId) {
            this.buildId = buildId;
        }

        public CompletableFuture<Result> getResult() {
            return result;
        }

        @Override
        public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
        }

        @Override
        public void onEvent(@NotNull EventSource eventSource, @Nullable String id, @Nullable String type, @NotNull String data) {
            // System.out.println("Event: " + type + " - " + data);
            if (type.equals("BuildEvent")) {
                JsonNode eventJson = parse(data);
                long timestamp = eventJson.get("timestamp").asLong();
                String eventType = eventJson.get("type").get("eventType").asText();
                int delta;
                switch (eventType) {
                    case "ProjectStructure":
                        String rootProject = eventJson.get("data").get("rootProjectName").asText();
                        rootProjects.add(rootProject);
                        break;
                    case "UserTag":
                        String tag = eventJson.get("data").get("tag").asText();
                        tags.add(tag);
                        break;
                    case "BuildModes":
                        maxWorkers = eventJson.get("data").get("maxWorkers").asInt();
                        break;
                    default:
                        throw new AssertionError("Unknown event type: " + eventType);
                }

            }
        }

        @Override
        public void onClosed(@NotNull EventSource eventSource) {
            boolean matches;
            BuildStatistics stats;
            if (!rootProjects.contains("gradle")) {
                matches = false;
            } else if (!tags.contains("LOCAL")) {
                matches = false;
            } else {
                System.out.println("Found build " + buildId);
                matches = true;
            }
            result.complete(new Result(buildId, matches, maxWorkers));
        }
    }

    private static class ProcessTaskEvents extends PrintFailuresEventSourceListener {
        private final String buildId;
        private final int maxWorkers;
        private final CompletableFuture<BuildStatistics> result = new CompletableFuture<>();
        private final Map<Long, TaskInfo> tasks = new HashMap<>();

        private static class TaskInfo {
            public final String type;
            public final long startTime;
            public long finishTime;
            public String outcome;

            public TaskInfo(String type, long startTime) {
                this.type = type;
                this.startTime = startTime;
            }
        }

        private ProcessTaskEvents(String buildId, int maxWorkers) {
            this.buildId = buildId;
            this.maxWorkers = maxWorkers;
        }

        public CompletableFuture<BuildStatistics> getResult() {
            return result;
        }

        @Override
        public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
        }

        @Override
        public void onEvent(@NotNull EventSource eventSource, @Nullable String id, @Nullable String type, @NotNull String data) {
            // System.out.println("Event: " + type + " !! " + id + " - " + data);
            if (type.equals("BuildEvent")) {
                JsonNode eventJson = parse(data);
                long timestamp = eventJson.get("timestamp").asLong();
                String eventType = eventJson.get("type").get("eventType").asText();
                long eventId = eventJson.get("data").get("id").asLong();
                int delta;
                switch (eventType) {
                    case "TaskStarted":
                        tasks.put(eventId, new TaskInfo(
                                eventJson.get("data").get("className").asText(),
                                timestamp
                        ));
                        break;
                    case "TaskFinished":
                        TaskInfo task = tasks.get(eventId);
                        task.finishTime = timestamp;
                        task.outcome = eventJson.get("data").get("outcome").asText();
                        break;
                    default:
                        throw new AssertionError("Unknown event type: " + eventType);
                }
            }
        }

        @Override
        public void onClosed(@NotNull EventSource eventSource) {
            System.out.println("Finished processing build " + buildId);
            SortedMap<Long, Integer> startStopEvents = new TreeMap<>();
            AtomicInteger taskCount = new AtomicInteger(0);
            SortedMap<String, Long> taskTimes = new TreeMap<>();
            tasks.values().stream()
                    .filter(task -> true
                            && !task.type.startsWith("gradlebuild.integrationtests.tasks.")
                            && !task.type.equals("org.gradle.api.tasks.testing.Test")
                            && (task.outcome.equals("success") || task.outcome.equals("failed"))
                    )
                    .forEach(task -> {
                        taskCount.incrementAndGet();
                        add(taskTimes, task.type, task.finishTime - task.startTime);
                        add(startStopEvents, task.startTime, 1);
                        add(startStopEvents, task.finishTime, -1);
                    });

            int concurrencyLevel = 0;
            long lastTimeStamp = 0;
            SortedMap<Integer, Long> histogram = new TreeMap<>();
            for (Map.Entry<Long, Integer> entry : startStopEvents.entrySet()) {
                long timestamp = entry.getKey();
                int delta = entry.getValue();
                if (concurrencyLevel != 0) {
                    long duration = timestamp - lastTimeStamp;
                    add(histogram, concurrencyLevel, duration);
                }
                concurrencyLevel += delta;
                lastTimeStamp = timestamp;
            }
            result.complete(new BuildStatistics(1, taskCount.get(), copyOfSorted(taskTimes), copyOfSorted(histogram), ImmutableSortedMap.of(maxWorkers, 1)));
        }
    }

    private static <K> void add(Map<K, Integer> map, K key, int delta) {
        map.compute(key, (k, value) -> (value == null ? 0 : value) + delta);
    }

    private static <K> void add(Map<K, Long> map, K key, long delta) {
        map.compute(key, (k, value) -> (value == null ? 0 : value) + delta);
    }

    private static <K extends Comparable<K>, V> ImmutableSortedMap<K, V> mergeMaps(Map<K, V> a, Map<K, V> b, V zero, BinaryOperator<V> add) {
        ImmutableSortedMap.Builder<K, V> merged = ImmutableSortedMap.naturalOrder();
        for (K key : Sets.union(a.keySet(), b.keySet())) {
            merged.put(key, add.apply(a.getOrDefault(key, zero), b.getOrDefault(key, zero)));
        }
        return merged.build();
    }

    private static class PrintFailuresEventSourceListener extends EventSourceListener {
        @Override
        public void onFailure(@NotNull EventSource eventSource, @Nullable Throwable t, @Nullable Response response) {
            if (t != null) {
                System.err.println("FAILED: " + t.getMessage());
                t.printStackTrace();
            }
            if (response != null) {
                System.err.println("Bad response: " + response);
                System.err.println("Response body: " + getResponseBody(response));
            }
            eventSource.cancel();
            this.onClosed(eventSource);
        }

        private String getResponseBody(Response response) {
            try {
                return response.body().string();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static JsonNode parse(String data) {
        try {
            return MAPPER.readTree(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static void shutdown(OkHttpClient httpClient) {
        httpClient.dispatcher().cancelAll();
        MoreExecutors.shutdownAndAwaitTermination(httpClient.dispatcher().executorService(), Duration.ofSeconds(10));
    }

    private static class BuildStatistics {
        public static final BuildStatistics EMPTY = new BuildStatistics(0, 0, ImmutableSortedMap.of(), ImmutableSortedMap.of(), ImmutableSortedMap.of()) {
            @Override
            public void print() {
                System.out.println("No matching builds found");
            }
        };

        private final int buildCount;
        private final int taskCount;
        private final ImmutableSortedMap<String, Long> taskTimes;
        private final ImmutableSortedMap<Integer, Long> workerTimes;
        private final ImmutableSortedMap<Integer, Integer> maxWorkers;

        public BuildStatistics(
                int buildCount,
                int taskCount,
                ImmutableSortedMap<String, Long> taskTimes,
                ImmutableSortedMap<Integer, Long> workerTimes,
                ImmutableSortedMap<Integer, Integer> maxWorkers
        ) {
            this.buildCount = buildCount;
            this.taskCount = taskCount;
            this.taskTimes = taskTimes;
            this.workerTimes = workerTimes;
            this.maxWorkers = maxWorkers;
        }

        public void print() {
            System.out.println("Statistics for " + buildCount + " builds with " + taskCount + " tasks");

            System.out.println("Concurrency levels:");
            int maxConcurrencyLevel = workerTimes.lastKey();
            for (int concurrencyLevel = maxConcurrencyLevel; concurrencyLevel >= 1; concurrencyLevel--) {
                System.out.printf("%d: %d ms%n", concurrencyLevel, workerTimes.getOrDefault(concurrencyLevel, 0L));
            }

            System.out.println("Task times:");
            taskTimes.forEach((taskType, count) -> System.out.printf("%s: %d%n", taskType, count));

            System.out.println("Max workers:");
            int mostWorkers = maxWorkers.lastKey();
            for (int maxWorker = mostWorkers; maxWorker >= 1; maxWorker--) {
                System.out.printf("%d: %d builds%n", maxWorker, maxWorkers.getOrDefault(maxWorker, 0));
            }
        }

        public static BuildStatistics merge(BuildStatistics a, BuildStatistics b) {
            ImmutableSortedMap<String, Long> taskTimes = mergeMaps(a.taskTimes, b.taskTimes, 0L, (aV, bV) -> aV + bV);
            ImmutableSortedMap<Integer, Long> workerTimes = mergeMaps(a.workerTimes, b.workerTimes, 0L, (aV, bV) -> aV + bV);
            ImmutableSortedMap<Integer, Integer> maxWorkers = mergeMaps(a.maxWorkers, b.maxWorkers, 0, (aV, bV) -> aV + bV);
            return new BuildStatistics(
                    a.buildCount + b.buildCount,
                    a.taskCount + b.taskCount,
                    taskTimes,
                    workerTimes,
                    maxWorkers
            );
        }
    }

    private static class StreamableQueue<T> extends ForwardingBlockingQueue<T> {
        private final BlockingQueue<T> delegate;
        private final T poison;

        public StreamableQueue(T poison) {
            this.delegate = new LinkedBlockingQueue<>();
            this.poison = poison;
        }

        @Override
        protected BlockingQueue<T> delegate() {
            return delegate;
        }

        public void close() throws InterruptedException {
            put(poison);
        }

        @Override
        public Stream<T> stream() {
            return Stream.generate(() -> {
                        try {
                            T value = take();
                            if (value == poison) {
                                put(poison);
                            }
                            return value;
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .takeWhile(value -> value != poison);
        }
    }
}
