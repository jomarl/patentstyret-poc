package patentstyret;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class Trademarks {
    private static final Logger LOGGER = LoggerFactory.getLogger("trademarks");
    private static final Map<String, Processed> processedDocs = new HashMap<>();
    private static final AtomicInteger numberOfDuplicates = new AtomicInteger();
    private static final ObjectMapper om = new ObjectMapper();

    private static final boolean verbose = System.getenv("VERBOSE") != null;
    private static final String apiKey = System.getenv("API_KEY");

    public static void main(String[] args) throws JsonProcessingException {
        if (apiKey == null) {
            LOGGER.warn("API_KEY environment variable required");
            System.exit(0);
        }

        TrademarksApi api = new TrademarksApi(apiKey, "https://api.patentstyret.no/external/opendata/register/Trademark/v1");

        TrademarksApi.PagedResponse<JsonNode> page = api.findAll(0);
        LOGGER.info("Total number of hits: {}", page.getTotalHitsCount());

        handleResponse(page);

        while (page != null && page.maybeHasNextPage()) {
            page = api.findAll(page.nextPageNumber());
            if (page != null) {
                handleResponse(page);

                if (page.getPageNumber() > 500) {
                    LOGGER.info("Processed > 500 pages. Stopping");
                    System.exit(0);
                }
            }
            if (numberOfDuplicates.get() > 100) {
                LOGGER.error("Observed more than 100 duplicates(?). Stopping.");
                break;
            }
        }
    }

    private static void handleResponse(TrademarksApi.PagedResponse<JsonNode> page) throws JsonProcessingException {
        LOGGER.info("Page {} documents (applicationId/registrationId):", page.getPageNumber());
        for (JsonNode document : page.getResults()) {
            String id = extractId(document);
            LOGGER.info("\t{}", id);
            if (processedDocs.containsKey(id)) {
                Processed processed = processedDocs.get(id);
                LOGGER.warn("\t\tDocument already processed: {} on page {}", id, processed.page);
                LOGGER.info("\t\tDocument content identical: {}", processed.document.equals(document));
                if (verbose) {
                    LOGGER.warn("Current: {}", om.writerWithDefaultPrettyPrinter().writeValueAsString(document));
                    LOGGER.warn("Previous: {}", om.writerWithDefaultPrettyPrinter().writeValueAsString(processed.document));
                }
                numberOfDuplicates.incrementAndGet();
            }
            processedDocs.put(id, new Processed(document, page.getPageNumber()));
        }
    }

    private static String extractId(JsonNode node) {
        String applicationId = node.path("trademarkApplication")
                .path("trademarkBag")
                .path("trademark").get(0) //assuming 1 trademark
                .path("trademarkTypeChoice1")
                .path("applicationNumber").get(0) // assuming 1 applicationNumber
                .path("applicationNumberText").textValue();

        String registrationId = node.path("trademarkApplication")
                .path("trademarkBag")
                .path("trademark").get(0) // assuming 1 trademark
                .path("trademarkTypeChoice1")
                .path("registrationNumber").asText(null);

        // applicationNumberText or applicationNumberText/registrationId
        return registrationId == null ? applicationId : applicationId + "/" + registrationId;
    }

    private record Processed(JsonNode document, int page) {};

}
