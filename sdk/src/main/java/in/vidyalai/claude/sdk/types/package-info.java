/**
 * Type definitions for the Claude Agent SDK.
 *
 * <p>This package contains all data types for communication with the Claude Code CLI,
 * including messages, content blocks, hooks, permissions, and configuration types.
 *
 * <h2>Type Categories</h2>
 *
 * <h3>Message Types</h3>
 * <ul>
 *   <li>{@link in.vidyalai.claude.sdk.types.message.Message} - Sealed interface for all message types</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.message.UserMessage} - Messages from the user</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.message.AssistantMessage} - Responses from Claude</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.message.SystemMessage} - System-level messages</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.message.ResultMessage} - Final result with cost/usage info</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.message.StreamEvent} - Partial streaming events</li>
 * </ul>
 *
 * <h3>Content Blocks</h3>
 * <ul>
 *   <li>{@link in.vidyalai.claude.sdk.types.message.ContentBlock} - Sealed interface for content blocks</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.message.TextBlock} - Plain text content</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.message.ThinkingBlock} - Claude's reasoning</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.message.ToolUseBlock} - Tool invocation request</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.message.ToolResultBlock} - Result from tool execution</li>
 * </ul>
 *
 * <h3>Permission Types</h3>
 * <ul>
 *   <li>{@link in.vidyalai.claude.sdk.types.permission.PermissionMode} - Permission modes</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.permission.PermissionResult} - Callback result types</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.permission.PermissionUpdate} - Permission update configurations</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.permission.PermissionRuleValue} - Permission rules</li>
 * </ul>
 *
 * <h3>Hook Types</h3>
 * <ul>
 *   <li>{@link in.vidyalai.claude.sdk.types.hook.HookEvent} - Supported hook events</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.hook.HookMatcher} - Hook matcher configuration</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.hook.HookInput} - Input data for hooks</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.hook.HookOutput} - Output from hooks</li>
 * </ul>
 *
 * <h3>SDK Control Protocol Types</h3>
 * <p>Types for bidirectional communication between the SDK and CLI:
 * <ul>
 *   <li>{@link in.vidyalai.claude.sdk.types.control.request.SDKControlRequest} - Wrapper for control requests</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.control.request.SDKControlRequestData} - Base interface for request data</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.control.request.SDKControlInterruptRequest} - Interrupt execution</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.control.request.SDKControlPermissionRequest} - Tool permission callback</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.control.request.SDKControlInitializeRequest} - Initialize control protocol</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.control.request.SDKControlSetPermissionModeRequest} - Set permission mode</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.control.request.SDKControlSetModelRequest} - Set AI model</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.control.request.SDKHookCallbackRequest} - Invoke hook callback</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.control.request.SDKControlMcpMessageRequest} - Route MCP message</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.control.request.SDKControlRewindFilesRequest} - Rewind file checkpoints</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.control.response.SDKControlResponse} - Wrapper for control responses</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.control.response.ControlResponseData} - Base interface for response data</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.control.response.ControlResponse} - Success response</li>
 *   <li>{@link in.vidyalai.claude.sdk.types.control.response.ControlErrorResponse} - Error response</li>
 * </ul>
 *
 * <h2>⚠️ JSON Naming Convention - READ THIS FIRST</h2>
 * <p>
 * This package uses an <b>intentionally mixed naming convention</b> for JSON field names.
 * This is NOT a mistake - it's required by the Claude CLI's control protocol design.
 *
 * <h3>Rule 1: Incoming Data (CLI → SDK) Uses {@code snake_case}</h3>
 * <p>
 * Types that represent data <b>received from the CLI</b> use Python-style {@code snake_case}:
 * <pre>{@code
 * // ✅ CORRECT - Incoming message type
 * public record AssistantMessage(
 *     @JsonProperty("parent_tool_use_id") String parentToolUseId  // snake_case
 * ) implements in.vidyalai.claude.sdk.types.message.Message { }
 * }</pre>
 *
 * <p><b>Why?</b> The CLI sends messages with snake_case field names like {@code "tool_name"},
 * {@code "session_id"}, {@code "is_error"}, etc.
 *
 * <h3>Rule 2: Outgoing Data (SDK → CLI) Uses {@code camelCase}</h3>
 * <p>
 * Types involved in control protocol <b>responses to the CLI</b> use TypeScript-style {@code camelCase}:
 * <pre>{@code
 * // ✅ CORRECT - Outgoing control type
 * public record PermissionRuleValue(
 *     @JsonProperty("toolName") String toolName,        // camelCase
 *     @JsonProperty("ruleContent") String ruleContent   // camelCase
 * ) { }
 * }</pre>
 *
 * <p><b>Why?</b> The CLI's control protocol expects camelCase field names like {@code "updatedInput"},
 * {@code "updatedPermissions"}, {@code "toolName"}, etc.
 *
 * <h3>Quick Decision Guide</h3>
 * <table border="1">
 * <caption>When to Use Which Convention</caption>
 * <thead>
 *   <tr><th>If your type is...</th><th>Use this convention</th><th>Example</th></tr>
 * </thead>
 * <tbody>
 *   <tr>
 *     <td>A message from CLI</td>
 *     <td>{@code @JsonProperty("snake_case")}</td>
 *     <td>AssistantMessage, UserMessage</td>
 *   </tr>
 *   <tr>
 *     <td>A hook input</td>
 *     <td>{@code @JsonProperty("snake_case")}</td>
 *     <td>PreToolUseHookInput</td>
 *   </tr>
 *   <tr>
 *     <td>A permission response</td>
 *     <td>{@code @JsonProperty("camelCase")} or {@code toMap()}</td>
 *     <td>PermissionRuleValue</td>
 *   </tr>
 *   <tr>
 *     <td>A hook output</td>
 *     <td>{@code toMap()} with camelCase</td>
 *     <td>HookOutput</td>
 *   </tr>
 * </tbody>
 * </table>
 *
 * <h3>Why This Mixed Convention?</h3>
 * <ol>
 *   <li>The CLI sends messages in Python style (snake_case)</li>
 *   <li>The CLI's control protocol expects TypeScript style (camelCase)</li>
 *   <li>This exactly matches the Python SDK's behavior for cross-language consistency</li>
 * </ol>
 *
 * <h3>Implementation Patterns</h3>
 * <p>
 * The SDK uses different implementation patterns to handle the mixed naming convention:
 *
 * <h4>Incoming Data (snake_case)</h4>
 * <ul>
 *   <li><b>Explicit {@code @JsonProperty} annotations</b> - Most incoming types use annotations:
 *   <pre>{@code
 *   @JsonProperty("parent_tool_use_id") String parentToolUseId
 *   @JsonProperty("tool_name") String toolName
 *   @JsonProperty("is_error") Boolean isError
 *   }</pre>
 *   </li>
 *   <li><b>Manual parsing</b> - Some types use manual deserialization in
 *   {@link in.vidyalai.claude.sdk.internal.MessageParser} and
 *   {@link in.vidyalai.claude.sdk.internal.QueryHandler}:
 *   <pre>{@code
 *   String toolName = (String) data.get("tool_name");
 *   Map<String, Object> toolInput = (Map<String, Object>) data.get("tool_input");
 *   }</pre>
 *   </li>
 * </ul>
 *
 * <h4>Outgoing Data (camelCase)</h4>
 * <ul>
 *   <li><b>{@code toMap()} methods with camelCase keys</b> - Outgoing types provide manual serialization:
 *   <pre>{@code
 *   public Map<String, Object> toMap() {
 *       Map<String, Object> result = new HashMap<>();
 *       result.put("toolName", toolName);           // camelCase
 *       result.put("updatedInput", updatedInput);   // camelCase
 *       return result;
 *   }
 *   }</pre>
 *   </li>
 *   <li><b>{@code @JsonProperty("camelCase")} annotations</b> - Some outgoing types use annotations:
 *   <pre>{@code
 *   @JsonProperty("toolName") String toolName
 *   @JsonProperty("ruleContent") String ruleContent
 *   }</pre>
 *   </li>
 * </ul>
 *
 * <h3>Examples</h3>
 * <p>
 * <b>Incoming message JSON (snake_case):</b>
 * <pre>{@code
 * {
 *   "type": "assistant",
 *   "parent_tool_use_id": "abc123",
 *   "tool_name": "Bash",
 *   "session_id": "xyz",
 *   "is_error": false
 * }
 * }</pre>
 *
 * <p>
 * <b>Outgoing control response JSON (camelCase):</b>
 * <pre>{@code
 * {
 *   "behavior": "allow",
 *   "updatedInput": {...},
 *   "updatedPermissions": [{
 *     "type": "addRules",
 *     "rules": [{
 *       "toolName": "Bash",
 *       "ruleContent": "allow"
 *     }]
 *   }]
 * }
 * }</pre>
 *
 * @see in.vidyalai.claude.sdk.ClaudeSDK
 * @see in.vidyalai.claude.sdk.ClaudeSDKClient
 * @see in.vidyalai.claude.sdk.internal.MessageParser
 * @see in.vidyalai.claude.sdk.internal.QueryHandler
 */
package in.vidyalai.claude.sdk.types;
