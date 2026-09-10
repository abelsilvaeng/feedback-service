package com.retailstore.feedback.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.retailstore.feedback.model.FeedbackEntry;
import com.retailstore.feedback.model.EnhancedFeedback;
import com.retailstore.feedback.model.FeedbackSummary;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service for processing feedback data and enhancing it with AI insights.
 */
@Service
public class FeedbackService {

    @Autowired
    private GeminiService geminiService;

    // Path to sentiment analysis output file
    private static final String SENTIMENT_FILE_PATH = "sentiment_feedback_output.txt";

    // Patterns for parsing feedback file
    private static final Pattern FEEDBACK_PATTERN = Pattern.compile("Feedback #(\\d+)");
    private static final Pattern CUSTOMER_PATTERN = Pattern.compile("Customer:\\s*(.+)");
    private static final Pattern DEPARTMENT_PATTERN = Pattern.compile("Department:\\s*(.+)");
    private static final Pattern DATE_PATTERN = Pattern.compile("Date:\\s*(.+)");
    private static final Pattern COMMENT_PATTERN = Pattern.compile("Comment:\\s*(.+)");
    private static final Pattern SENTIMENT_PATTERN = Pattern.compile("Sentiment:\\s*(.+)");

    // Patterns for reading the JSON Gemini sends back
    private static final Pattern CATEGORY_PATTERN =
            Pattern.compile("\"category\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern INSIGHT_PATTERN =
            Pattern.compile("\"actionableInsight\"\\s*:\\s*\"([^\"]+)\"");

    // How many entries go to Gemini in one call, and the room its answer needs
    private static final int BATCH_SIZE = 10;
    private static final int MAX_BATCH_TOKENS = 8192;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Cache for feedback data
    private List<EnhancedFeedback> enhancedFeedbackCache = null;

    /**
     * Reads feedback data from the sentiment analysis output file.
     *
     * @return List of FeedbackEntry objects
     * @throws IOException If an I/O error occurs
     */
    public List<FeedbackEntry> readFeedbackData() throws IOException {
        List<FeedbackEntry> entries = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(SENTIMENT_FILE_PATH))) {
            StringBuilder entryText = new StringBuilder();
            String line;

            boolean inDetailedSection = false;
            while ((line = reader.readLine()) != null) {
                // Check if we've reached the detailed feedback section
                if (line.contains("## Detailed Feedback Entries")) {
                    inDetailedSection = true;
                    continue;
                }

                if (!inDetailedSection) {
                    continue;
                }

                // Process each line
                if (line.trim().isEmpty() && entryText.length() > 0) {
                    // We've reached the end of an entry
                    FeedbackEntry entry = parseFeedbackEntry(entryText.toString());
                    if (entry != null) {
                        entries.add(entry);
                    }
                    entryText = new StringBuilder();
                } else {
                    entryText.append(line).append("\n");
                }
            }

            // Process the last entry if there is one
            if (entryText.length() > 0) {
                FeedbackEntry entry = parseFeedbackEntry(entryText.toString());
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }

        return entries;
    }

    /**
     * Parses a feedback entry from text.
     *
     * @param text Text containing a feedback entry
     * @return FeedbackEntry object or null if parsing fails
     */
    private FeedbackEntry parseFeedbackEntry(String text) {
        FeedbackEntry entry = new FeedbackEntry();

        // Extract feedback ID
        Matcher idMatcher = FEEDBACK_PATTERN.matcher(text);
        if (idMatcher.find()) {
            entry.setId(Integer.parseInt(idMatcher.group(1)));
        } else {
            return null;
        }

        // Extract customer
        Matcher customerMatcher = CUSTOMER_PATTERN.matcher(text);
        if (customerMatcher.find()) {
            entry.setCustomer(customerMatcher.group(1));
        }

        // Extract department
        Matcher departmentMatcher = DEPARTMENT_PATTERN.matcher(text);
        if (departmentMatcher.find()) {
            entry.setDepartment(departmentMatcher.group(1));
        }

        // Extract date
        Matcher dateMatcher = DATE_PATTERN.matcher(text);
        if (dateMatcher.find()) {
            entry.setDate(dateMatcher.group(1));
        }

        // Extract comment
        Matcher commentMatcher = COMMENT_PATTERN.matcher(text);
        if (commentMatcher.find()) {
            entry.setComment(commentMatcher.group(1));
        }

        // Extract sentiment
        Matcher sentimentMatcher = SENTIMENT_PATTERN.matcher(text);
        if (sentimentMatcher.find()) {
            entry.setSentiment(sentimentMatcher.group(1));
        }

        return entry;
    }

    /**
     * Enhances feedback with AI-generated categories and actionable insights.
     *
     * @return List of EnhancedFeedback objects
     * @throws IOException If an I/O error occurs
     */
    public synchronized List<EnhancedFeedback> getEnhancedFeedback() throws IOException {
        // Return cached data if available
        if (enhancedFeedbackCache != null) {
            return enhancedFeedbackCache;
        }

        List<FeedbackEntry> entries = readFeedbackData();
        List<EnhancedFeedback> enhancedEntries = new ArrayList<>();

        // Entries go to Gemini in batches rather than one call each. Fifty separate calls
        // exhaust the free tier quota, which is measured per minute, and every throttled entry
        // silently degrades to "Uncategorized" on the dashboard.
        for (int start = 0; start < entries.size(); start += BATCH_SIZE) {
            List<FeedbackEntry> batch = entries.subList(start, Math.min(start + BATCH_SIZE, entries.size()));
            enhancedEntries.addAll(enhanceBatch(batch));
            System.out.println("Enhanced " + enhancedEntries.size() + " of " + entries.size() + " entries");
        }

        // Cache the enhanced feedback
        enhancedFeedbackCache = enhancedEntries;

        return enhancedEntries;
    }

    /**
     * Categorises a batch of entries in a single Gemini call.
     *
     * <p>Any entry the model leaves out of its answer falls back to its own call, so a partial
     * or malformed batch response costs accuracy on a few entries rather than all of them.
     *
     * @param batch Entries to categorise together
     * @return The same entries, enhanced
     */
    private List<EnhancedFeedback> enhanceBatch(List<FeedbackEntry> batch) {
        StringBuilder items = new StringBuilder();
        for (FeedbackEntry entry : batch) {
            items.append("- id: ").append(entry.getId())
                    .append(" | department: ").append(entry.getDepartment())
                    .append(" | sentiment: ").append(entry.getSentiment())
                    .append(" | comment: ").append(entry.getComment())
                    .append("\n");
        }

        String prompt = String.format("""
            You are an AI assistant specialized in customer feedback analysis.
            For EACH feedback below:
            1. Categorize it into exactly one of these categories: Product Quality, Customer Service, Store Experience, Website/App, Delivery, Price/Value, Inventory/Stock, or Other.
            2. Write one specific actionable insight or recommendation.

            Return a JSON array. One object per feedback, with the fields "id" (the number given),
            "category" and "actionableInsight". Return every id, in the order given, and nothing else.
            Keep each insight to one sentence.

            Customer feedback:
            %s
            """, items);

        Map<Integer, JsonNode> byId = new HashMap<>();
        try {
            String response = geminiService.generateContent(prompt, MAX_BATCH_TOKENS);
            JsonNode root = objectMapper.readTree(response);

            // The model may return the array bare or wrapped in an object under some key.
            JsonNode array = root.isArray() ? root : firstArrayIn(root);
            if (array != null) {
                for (JsonNode node : array) {
                    if (node.hasNonNull("id")) {
                        byId.put(node.get("id").asInt(), node);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Batch enhancement failed, falling back to one call per entry: " + e.getMessage());
        }

        List<EnhancedFeedback> result = new ArrayList<>();
        for (FeedbackEntry entry : batch) {
            JsonNode node = byId.get(entry.getId());
            if (node == null) {
                // Not in the batch answer, ask for this one on its own.
                result.add(enhanceFeedback(entry));
                continue;
            }

            EnhancedFeedback enhanced = new EnhancedFeedback(entry);
            enhanced.setCategory(node.path("category").asText("Uncategorized"));
            enhanced.setActionableInsight(
                    node.path("actionableInsight").asText("No specific action recommended."));
            result.add(enhanced);
        }

        return result;
    }

    /** Finds the first array value in an object, for when the model wraps its answer. */
    private JsonNode firstArrayIn(JsonNode root) {
        for (JsonNode child : root) {
            if (child.isArray()) {
                return child;
            }
        }
        return null;
    }

    /**
     * Enhances a single feedback entry with AI-generated category and actionable insight.
     *
     * @param entry FeedbackEntry to enhance
     * @return EnhancedFeedback with AI-generated category and actionable insight
     */
    private EnhancedFeedback enhanceFeedback(FeedbackEntry entry) {
        EnhancedFeedback enhancedEntry = new EnhancedFeedback(entry);

        // Create a prompt for Gemini
        String prompt = String.format("""
            You are an AI assistant specialized in customer feedback analysis.
            Analyze the following customer feedback and:
            1. Categorize the feedback into one of these categories: Product Quality, Customer Service, Store Experience, Website/App, Delivery, Price/Value, Inventory/Stock, or Other.
            2. Provide a specific actionable insight or recommendation based on the feedback.

            Format your response as JSON with two fields: "category" and "actionableInsight".
            Keep your response concise but insightful.

            Customer Feedback:
            Comment: %s
            Department: %s
            Sentiment: %s

            Provide the category and actionable insight as JSON:
            """, entry.getComment(), entry.getDepartment(), entry.getSentiment());

        try {
            // Call Gemini API and parse the response
            String response = geminiService.generateContent(prompt);

            // Parse JSON response
            // This is a simple parsing approach - for production, use a proper JSON parser
            String jsonResponse = response.trim();

            // Extract category
            Matcher categoryMatcher = CATEGORY_PATTERN.matcher(jsonResponse);
            if (categoryMatcher.find()) {
                enhancedEntry.setCategory(categoryMatcher.group(1));
            } else {
                enhancedEntry.setCategory("Uncategorized");
            }

            // Extract actionable insight
            Matcher insightMatcher = INSIGHT_PATTERN.matcher(jsonResponse);
            if (insightMatcher.find()) {
                enhancedEntry.setActionableInsight(insightMatcher.group(1));
            } else {
                enhancedEntry.setActionableInsight("No specific action recommended.");
            }

        } catch (Exception e) {
            // Handle API errors gracefully
            enhancedEntry.setCategory("Error in processing");
            enhancedEntry.setActionableInsight("Could not generate insight due to API error: " + e.getMessage());
        }

        return enhancedEntry;
    }

    /**
     * Generates a summary of the feedback data for the dashboard.
     *
     * @return FeedbackSummary object
     * @throws IOException If an I/O error occurs
     */
    public FeedbackSummary generateFeedbackSummary() throws IOException {
        List<EnhancedFeedback> allFeedback = getEnhancedFeedback();
        FeedbackSummary summary = new FeedbackSummary();

        // Set total feedback count
        summary.setTotalFeedback(allFeedback.size());

        // Count sentiments
        Map<String, Integer> sentimentCounts = new HashMap<>();
        for (EnhancedFeedback feedback : allFeedback) {
            String sentiment = feedback.getSentiment();
            sentimentCounts.put(sentiment, sentimentCounts.getOrDefault(sentiment, 0) + 1);
        }
        summary.setSentimentCounts(sentimentCounts);

        // Count categories
        Map<String, Integer> categoryCounts = new HashMap<>();
        for (EnhancedFeedback feedback : allFeedback) {
            String category = feedback.getCategory();
            categoryCounts.put(category, categoryCounts.getOrDefault(category, 0) + 1);
        }
        summary.setCategoryCounts(categoryCounts);

        // Count departments
        Map<String, Integer> departmentCounts = new HashMap<>();
        for (EnhancedFeedback feedback : allFeedback) {
            String department = feedback.getDepartment();
            departmentCounts.put(department, departmentCounts.getOrDefault(department, 0) + 1);
        }
        summary.setDepartmentCounts(departmentCounts);

        // Get recent feedback (last 5 entries)
        List<EnhancedFeedback> recentFeedback = allFeedback.stream()
                .sorted(Comparator.comparing(EnhancedFeedback::getId).reversed())
                .limit(5)
                .collect(Collectors.toList());
        summary.setRecentFeedback(recentFeedback);

        return summary;
    }
}
