package com.retailstore.feedback.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

/**
 * Service for interacting with Google's Gemini AI using REST API
 */
@Service
public class GeminiService {

    @Value("${gemini.api-key}")
    private String apiKey;

    @Value("${gemini.model}")
    private String model;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    /** Free tier quota is per minute, so a 429 is worth waiting out rather than failing. */
    private static final int MAX_ATTEMPTS = 4;
    private static final long INITIAL_BACKOFF_MS = 4000;

    /**
     * Sends a prompt to Gemini and returns the response
     *
     * @param prompt The prompt to send to Gemini
     * @return The response from Gemini
     */
    public String generateContent(String prompt) {
        long backoff = INITIAL_BACKOFF_MS;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return callGemini(prompt);
            } catch (HttpClientErrorException.TooManyRequests e) {
                // Free tier requests per minute exceeded. Wait and retry rather than losing the entry.
                if (attempt == MAX_ATTEMPTS) {
                    System.err.println("Gemini rate limit still hit after " + MAX_ATTEMPTS + " attempts");
                    return "Error: rate limited by Gemini after " + MAX_ATTEMPTS + " attempts";
                }
                System.err.println("Rate limited by Gemini, retrying in " + backoff + "ms (attempt "
                        + attempt + " of " + MAX_ATTEMPTS + ")");
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return "Error: interrupted while waiting on Gemini rate limit";
                }
                backoff *= 2;
            } catch (Exception e) {
                System.err.println("Error calling Gemini API: " + e.getMessage());
                return "Error: " + e.getMessage();
            }
        }

        return "No response from Gemini";
    }

    /**
     * One call to generateContent. The API key travels in the x-goog-api-key header rather than
     * as a query parameter, so it does not end up in access logs or proxy history.
     */
    private String callGemini(String prompt) throws Exception {
        // Create the request body using Jackson
        ObjectNode requestBody = objectMapper.createObjectNode();
        ArrayNode contents = objectMapper.createArrayNode();
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode parts = objectMapper.createArrayNode();
        ObjectNode textPart = objectMapper.createObjectNode();

        textPart.put("text", prompt);
        parts.add(textPart);
        content.set("parts", parts);
        contents.add(content);
        requestBody.set("contents", contents);

        // Add generation config for better JSON responses
        ObjectNode generationConfig = objectMapper.createObjectNode();
        generationConfig.put("temperature", 0.7);
        generationConfig.put("maxOutputTokens", 1024);
        generationConfig.put("responseMimeType", "application/json");
        requestBody.set("generationConfig", generationConfig);

        // Set headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey);

        String url = GEMINI_API_BASE + model + ":generateContent";

        HttpEntity<String> request = new HttpEntity<>(requestBody.toString(), headers);

        ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                request,
                String.class
        );

        // Parse the response
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            ObjectNode responseJson = (ObjectNode) objectMapper.readTree(response.getBody());
            ArrayNode candidates = (ArrayNode) responseJson.get("candidates");

            if (candidates != null && !candidates.isEmpty()) {
                ObjectNode candidate = (ObjectNode) candidates.get(0);
                ObjectNode candidateContent = (ObjectNode) candidate.get("content");

                if (candidateContent != null) {
                    ArrayNode candidateParts = (ArrayNode) candidateContent.get("parts");

                    if (candidateParts != null && !candidateParts.isEmpty()
                            && candidateParts.get(0).hasNonNull("text")) {
                        return candidateParts.get(0).get("text").asText();
                    }
                }

                // A 200 with no text means generation stopped early, usually a safety filter
                // or the token limit. Say which, otherwise this is impossible to debug.
                return "Error: empty candidate from Gemini (finishReason: "
                        + candidate.path("finishReason").asText("unknown") + ")";
            }

            return "Error: no candidates from Gemini (blockReason: "
                    + responseJson.path("promptFeedback").path("blockReason").asText("unknown") + ")";
        }

        return "No response from Gemini";
    }

    /**
     * Test method to verify API connection
     */
    public void testConnection() {
        String testPrompt = "Say 'Hello, World!' if you can hear me.";
        String response = generateContent(testPrompt);
        System.out.println("Gemini API Test Response: " + response);
    }
}
