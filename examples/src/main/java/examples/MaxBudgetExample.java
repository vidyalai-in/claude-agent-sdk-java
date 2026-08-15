package examples;

import java.util.List;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.exceptions.ClaudeSDKException;
import in.vidyalai.claude.sdk.exceptions.QueryFailedException;
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
        System.out.println("=== With Reasonable Budget ($1.00) ===");

        // A budget the query is not expected to reach. Note that a single turn
        // costs more than you might guess — a long system prompt and a large
        // tool set are billed on every call — so keep the headroom generous.
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .maxBudgetUsd(1.00)
                .build();

        try {
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
        } catch (ClaudeSDKException e) {
            // Reaching the budget here means the headroom above was too small
            // for this environment, not that the API misbehaved.
            System.out.println("Budget was reached after all: " + e.getMessage());
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

        // Exceeding the budget is the point of this section, and it surfaces as
        // an exception rather than a returned list: the CLI reports an
        // `error_max_budget_usd` result and then exits non-zero on purpose, so
        // the SDK raises. A budget cap you set yourself is an expected outcome,
        // not a crash — catch it.
        //
        // QueryFailedException still carries everything that arrived before the
        // run stopped, so the turn is not lost: the final ResultMessage names
        // the condition and reports what was actually spent.
        try {
            List<Message> messages = ClaudeSDK.query(
                    "Read the README.md file and summarize it",
                    options);

            printMessages(messages);
            System.out.println("Budget was not reached.");
        } catch (QueryFailedException e) {
            System.out.println("Budget limit exceeded, as expected.");
            printMessages(e.partialMessages());

            ResultMessage result = e.resultMessage();
            if ((result != null) && "error_max_budget_usd".equals(result.subtype())) {
                System.out.printf("Stopped by the budget cap after spending $%.4f%n",
                        (result.totalCostUsd() != null) ? result.totalCostUsd() : 0.0);
                System.out.println("Note: the cost may exceed the budget by up to one API call's");
                System.out.println("worth, because the check runs after each call completes.");
            } else {
                System.out.println("Ended with: " + e.getMessage());
            }
        }
        System.out.println();
    }

    /** Prints the assistant text and the final cost/status of a collected turn. */
    static void printMessages(List<Message> messages) {
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
    }

}
