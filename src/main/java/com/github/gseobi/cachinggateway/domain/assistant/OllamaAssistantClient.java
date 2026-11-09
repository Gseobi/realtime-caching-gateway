package com.github.gseobi.cachinggateway.domain.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gseobi.assistant.contract.ModelInference;
import com.github.gseobi.knowledge.contract.SourceCitation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "gateway.assistant.enabled", havingValue = "true")
class OllamaAssistantClient implements ModelInference {
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final URI origin;

    OllamaAssistantClient(ObjectMapper json, @Value("${gateway.assistant.origin}") URI origin) {
        String host = origin.getHost();
        boolean loopback = host != null && Set.of("localhost", "127.0.0.1", "[::1]").contains(host);
        if (host == null || origin.getUserInfo() != null || origin.getQuery() != null
                || origin.getFragment() != null || !Set.of("", "/").contains(origin.getPath())
                || !("https".equals(origin.getScheme()) || ("http".equals(origin.getScheme()) && loopback))) {
            throw new IllegalArgumentException("Assistant origin must be a trusted HTTPS origin or loopback HTTP");
        }
        this.json = json;
        this.origin = origin;
    }

    List<double[]> embed(String model, int dimensions, List<String> inputs, Instant deadline) {
        JsonNode result = post("/api/embed", Map.of("model", model, "input", inputs, "truncate", false), deadline);
        JsonNode embeddings = result.path("embeddings");
        if (!model.equals(result.path("model").asText()) || !embeddings.isArray() || embeddings.size() != inputs.size()) {
            throw new IllegalStateException("Unexpected embedding response");
        }
        var vectors = new ArrayList<double[]>(inputs.size());
        for (JsonNode row : embeddings) {
            if (!row.isArray() || row.size() != dimensions) throw new IllegalStateException("Wrong embedding dimensions");
            double[] vector = new double[dimensions];
            double norm = 0;
            for (int i = 0; i < dimensions; i++) {
                JsonNode value = row.get(i);
                if (!value.isNumber() || !Double.isFinite(value.asDouble())) throw new IllegalStateException("Invalid vector");
                vector[i] = value.asDouble();
                norm = Math.hypot(norm, vector[i]);
            }
            if (!Double.isFinite(norm) || norm == 0) throw new IllegalStateException("Invalid vector norm");
            for (int i = 0; i < dimensions; i++) vector[i] /= norm;
            vectors.add(vector);
        }
        return vectors;
    }

    @Override
    public Reply generate(Request request) {
        var sources = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < request.evidence().size(); i++) {
            sources.add(Map.of("id", i + 1, "text", request.evidence().get(i).text()));
        }
        var schema = Map.of("type", "object", "additionalProperties", false,
                "required", List.of("answer", "citations"),
                "properties", Map.of("answer", Map.of("type", "string"),
                        "citations", Map.of("type", "array", "items", Map.of("type", "integer"))));
        try {
            var result = post("/api/chat", Map.of("model", request.modelRevision(), "stream", false,
                    "format", schema, "options", Map.of("num_predict", request.maxOutputTokens(),
                            "num_ctx", 4096, "temperature", 0),
                    "messages", List.of(Map.of("role", "system", "content", request.instructions()),
                            Map.of("role", "user", "content", json.writeValueAsString(
                                    Map.of("question", request.question(), "untrustedSources", sources))))),
                    request.deadline());
            if (!request.modelRevision().equals(result.path("model").asText()) || !result.path("done").asBoolean()
                    || "length".equals(result.path("done_reason").asText())
                    || !result.path("message").path("content").isTextual()) {
                throw new IllegalStateException("Unexpected chat response");
            }
            JsonNode answer = json.readTree(result.path("message").path("content").textValue());
            JsonNode ids = answer.path("citations");
            JsonNode text = answer.path("answer");
            if (!text.isTextual() || text.textValue().length() > 4000 || !ids.isArray()
                    || ids.size() > request.evidence().size()) throw new IllegalStateException("Invalid model output");
            if (ids.isEmpty() && text.textValue().isBlank()) throw new NoAnswer();
            var citations = new LinkedHashSet<SourceCitation>();
            for (var id : ids) {
                if (!id.isIntegralNumber() || !id.canConvertToInt() || id.intValue() < 1
                        || id.intValue() > request.evidence().size()) throw new IllegalStateException("Unknown citation");
                var chunk = request.evidence().get(id.intValue() - 1);
                citations.add(new SourceCitation(chunk.document(), chunk.chunkId(), chunk.index()));
            }
            return new Reply(text.textValue(), result.path("model").asText(), List.copyOf(citations));
        } catch (IOException e) {
            throw new IllegalStateException("Malformed model response");
        }
    }

    private JsonNode post(String path, Map<String, ?> data, Instant deadline) {
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) throw new Deadline();
        try {
            var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(remaining)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(data))).build();
            var future = client.sendAsync(request, response -> {
                var bytes = new ByteArrayOutputStream();
                var subscriber = HttpResponse.BodySubscribers.ofByteArrayConsumer(part -> part.ifPresent(chunk -> {
                    if (chunk.length > MAX_RESPONSE_BYTES - bytes.size()) {
                        throw new IllegalStateException("Model response exceeded limit");
                    }
                    bytes.writeBytes(chunk);
                }));
                return HttpResponse.BodySubscribers.mapping(subscriber, ignored -> bytes.toByteArray());
            });
            try {
                var response = future.get(Math.max(1, Duration.between(Instant.now(), deadline).toMillis()),
                        TimeUnit.MILLISECONDS);
                if (response.statusCode() != 200) throw new IllegalStateException("Model request failed");
                return json.readTree(response.body());
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new Deadline();
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new Deadline();
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof java.net.http.HttpTimeoutException) throw new Deadline();
                throw new IllegalStateException("Model transport failed");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Malformed model response");
        }
    }

    static final class Deadline extends RuntimeException {}
    static final class NoAnswer extends RuntimeException {}
}
