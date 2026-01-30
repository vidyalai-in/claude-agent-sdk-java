package examples;

import java.util.List;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;

/**
 * Example demonstrating max_budget_usd option for cost control.
 * <p>
 * Shows how to use budget limits to control API costs:
 * <ul>
 * <li>Running without budget limit</li>
 * <li>Setting reasonable budget that won't be exceeded</li>
 * <li>Setting tight budget that will be exceeded</li>
 * </ul>
 * <p>
 * Note: Budget checking happens after each API call completes,
 * so the final cost may slightly exceed the specified budget.
 */
public class MaxBudgetExample {

    public static void main(String[] args) {
        System.out.println("This example demonstrates using maxBudgetUsd to control API costs.\n");

        // Example 1: Without budget limit
        withoutBudget();

        // Example 2: With reasonable budget
        withReasonableBudget();

        // Example 3: With tight budget that will be exceeded
        withTightBudget();

        System.out.println("\nNote: Budget checking happens after each API call completes,");
        System.out.println("so the final cost may slightly exceed the specified budget.\n");
    }

    /**
     * Example without budget limit.
     */
    static void withoutBudget() {
        System.out.println("=== Without Budget Limit ===");

        List<Message> messages = ClaudeSDK.query("What is 2 + 2?");

        for (Message msg : messages) {
            if (msg instanceof AssistantMessage assistant) {
                System.out.println("Claude: " + assistant.getTextContent());
            } else if (msg instanceof ResultMessage result) {
                if (result.totalCostUsd() != null) {
                    System.out.printf("Total cost: $%.4f\n", result.totalCostUsd());
                }
                System.out.println("Status: " + result.subtype());
            }
        }
        System.out.println();
    }

    /**
     * Example with budget that won't be exceeded.
     */
    static void withReasonableBudget() {
        System.out.println("=== With Reasonable Budget ($0.10) ===");

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .maxBudgetUsd(0.10) // 10 cents - plenty for a simple query
                .build();

        List<Message> messages = ClaudeSDK.query("What is 2 + 2?", options);

        for (Message msg : messages) {
            if (msg instanceof AssistantMessage assistant) {
                System.out.println("Claude: " + assistant.getTextContent());
            } else if (msg instanceof ResultMessage result) {
                if (result.totalCostUsd() != null) {
                    System.out.printf("Total cost: $%.4f\n", result.totalCostUsd());
                }
                System.out.println("Status: " + result.subtype());
            }
        }
        System.out.println();
    }

    /**
     * Example with very tight budget that will likely be exceeded.
     */
    static void withTightBudget() {
        System.out.println("=== With Tight Budget ($0.0001) ===");

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .maxBudgetUsd(0.0001) // Very small budget - will be exceeded quickly
                .build();

        List<Message> messages = ClaudeSDK.query(
                "Read the README.md file and summarize it",
                options);

        for (Message msg : messages) {
            if (msg instanceof AssistantMessage assistant) {
                System.out.println("Claude: " + assistant.getTextContent());
            } else if (msg instanceof ResultMessage result) {
                if (result.totalCostUsd() != null) {
                    System.out.printf("Total cost: $%.4f\n", result.totalCostUsd());
                }
                System.out.println("Status: " + result.subtype());

                // Check if budget was exceeded
                if ("error_max_budget_usd".equals(result.subtype())) {
                    System.out.println("⚠️  Budget limit exceeded!");
                    System.out.println("Note: The cost may exceed the budget by up to one API call's worth");
                }
            }
        }
        System.out.println();
    }

}
