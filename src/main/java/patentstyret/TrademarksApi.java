package patentstyret;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

public class TrademarksApi {
    private static final Logger LOGGER = LoggerFactory.getLogger("trademarks.api");
    private final String apiKey;
    private final String apiUrl;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TypeReference<PagedResponse<JsonNode>> typeRef = new TypeReference<>() {};
    private static final HttpRequestInterceptor requestInterceptor = (request, context) -> {
        LOGGER.info("Request URL: {}", request.getRequestLine().getUri());
        if (request instanceof HttpEntityEnclosingRequest) {
            HttpEntity entity = ((HttpEntityEnclosingRequest) request).getEntity();
            if (entity != null) {
                try {
                    String requestBody = EntityUtils.toString(entity);
                    LOGGER.info("Request Body: {}", requestBody);
                } catch (IOException e) {
                    LOGGER.error("Error reading request body", e);
                }
            }
        }
    };

    public TrademarksApi(String apiKey, String apiUrl) {
        this.apiKey = Objects.requireNonNull(apiKey);
        this.apiUrl = Objects.requireNonNull(apiUrl);
        this.httpClient = HttpClients.custom()
                .setConnectionManager(new PoolingHttpClientConnectionManager())
                .addInterceptorFirst(requestInterceptor)
                .disableCookieManagement()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(10000)
                        .setSocketTimeout(1000 * 60 * 5)
                        .build())
                .build();
    }


    public PagedResponse<JsonNode> findAll(int pageNumber) {
        String body = objectMapper.createObjectNode()
                .put("applicationDateFrom", "0001-01-01")
                .put("pageSize", 50)
                .put("pageNumber", pageNumber)
                .toString();

        HttpPost request = createRequest(apiUrl + "/search/json", body);
        return executeWithRetry(request, typeRef);
    }

    private <T> T executeWithRetry(HttpPost request, TypeReference<T> type) {
        RetryPolicy<Object> retryPolicy = new RetryPolicy<>()
                .withBackoff(500, 50000, ChronoUnit.MILLIS, 5)
                .withMaxRetries(5)
                .onRetry((e) -> LOGGER.warn("Retrying request {}. Error: {}", EntityUtils.toString(request.getEntity()), e))
                .onRetriesExceeded((e) -> LOGGER.error("Retries exceeded for request {}", EntityUtils.toString(request.getEntity())))
                .withDelayOn((result, failure, context) -> failure.getRetryAfter(), RateLimitedException.class)
                .handle(RetryException.class);

        return Failsafe.with(retryPolicy).get(() -> execute(request, type));

    }

    private <T> T execute(HttpPost request, TypeReference<T> type) {
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            return switch (response.getStatusLine().getStatusCode()) {
                case 200 -> objectMapper.readValue(response.getEntity().getContent(), type);
                case 429 -> throw new RateLimitedException(Long.parseLong(response.getFirstHeader("Retry-After").getValue()));
                case 401 -> throw new RuntimeException("Unauthorized " + objectMapper.readValue(response.getEntity().getContent(), JsonNode.class).textValue());
                default ->
                        throw new RetryException("Error from api " + objectMapper.readValue(response.getEntity().getContent(), JsonNode.class));
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private HttpPost createRequest(String url, String body) {
        HttpPost request = new HttpPost(url);
        request.setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString());
        request.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        request.setHeader("Ocp-Apim-Subscription-Key", this.apiKey);
        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        return request;
    }


    private static class RetryException extends RuntimeException {
        RetryException(String message) {
            super(message);
        }
    }

    private static class RateLimitedException extends RetryException {
        private final long retryAfter;

        RateLimitedException(long retryAfter) {
            super("Retry-After " + retryAfter);
            this.retryAfter = retryAfter;
        }

        Duration getRetryAfter() {
            return Duration.ofMillis(this.retryAfter * 2);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PagedResponse<T> {
        private final int totalHitsCount;
        private final int pageNumber;
        private final int pageSize;
        private final List<T> results;

        @JsonCreator
        public PagedResponse(@JsonProperty("totalHitsCount") int totalHitsCount,
                             @JsonProperty("pageNumber") int pageNumber,
                             @JsonProperty("pageSize") int pageSize,
                             @JsonProperty("results") List<T> results) {
            this.totalHitsCount = totalHitsCount;
            this.pageNumber = pageNumber;
            this.pageSize = pageSize;
            this.results = results;
        }

        public List<T> getResults() {
            return results;
        }

        public boolean maybeHasNextPage() {
            return results != null && !results.isEmpty() && results.size() == pageSize;
        }

        public int nextPageNumber() {
            return pageNumber + 1;
        }

        public int getPageNumber() {
            return pageNumber;
        }

        public int getTotalHitsCount() {
            return totalHitsCount;
        }
    }
}