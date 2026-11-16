package in.vidyalai.claude.sdk.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * First-prompt extraction: the single implementation behind
 * {@code SDKSessionInfo.firstPrompt}, the summary fallback, fork titles and
 * the {@link in.vidyalai.claude.sdk.types.session.SessionSummary} fold.
 *
 * <p>Mirrors the Python SDK's {@code _extract_first_prompt_from_head} and
 * {@code _fold_first_prompt} (which share {@code _SKIP_FIRST_PROMPT_PATTERN}
 * and {@code _COMMAND_NAME_RE}).
 *
 * <p>This class is internal and not part of the public API.
 */
public final class FirstPrompt {

    /** Maximum length, in code points, of an extracted prompt before the ellipsis. */
    static final int MAX_PROMPT_LENGTH = 200;

    /**
     * Auto-generated or system messages skipped when looking for the first
     * meaningful user prompt. Applied with {@link Matcher#lookingAt()}, as
     * Python applies it with {@code re.match}. {@code UNIX_LINES} makes
     * {@code .} and {@code $} treat only {@code \n} as a line terminator, as
     * Python's {@code re} does.
     */
    static final Pattern SKIP_FIRST_PROMPT_PATTERN = Pattern.compile(
            "^(?:<local-command-stdout>|<session-start-hook>|<tick>|<goal>|"
                    + "\\[Request interrupted by user[^\\]]*\\]|"
                    + "\\s*<ide_opened_file>[\\s\\S]*</ide_opened_file>\\s*$|"
                    + "\\s*<ide_selection>[\\s\\S]*</ide_selection>\\s*$)",
            Pattern.UNIX_LINES);

    static final Pattern COMMAND_NAME_RE = Pattern.compile(
            "<command-name>(.*?)</command-name>", Pattern.UNIX_LINES);

    private FirstPrompt() {
    }

    /**
     * Extracts the first meaningful user prompt from a JSONL head chunk.
     *
     * <p>Skips tool_result messages, {@code isMeta}, {@code isCompactSummary},
     * command-name messages (the first command name is the fallback) and
     * auto-generated patterns. Truncates to 200 code points plus an ellipsis.
     *
     * @return the prompt, or {@code ""} when none is found
     */
    public static String extractFromHead(String head) {
        String[] commandFallback = { "" };
        int start = 0;
        int headLen = head.length();

        while (start < headLen) {
            int newlineIdx = head.indexOf('\n', start);
            String line;
            if (newlineIdx >= 0) {
                line = head.substring(start, newlineIdx);
                start = newlineIdx + 1;
            } else {
                line = head.substring(start);
                start = headLen;
            }

            if (!line.contains("\"type\":\"user\"") && !line.contains("\"type\": \"user\"")) {
                continue;
            }
            if (line.contains("\"tool_result\"")) {
                continue;
            }
            if (line.contains("\"isMeta\":true") || line.contains("\"isMeta\": true")) {
                continue;
            }
            if (line.contains("\"isCompactSummary\":true") || line.contains("\"isCompactSummary\": true")) {
                continue;
            }

            Map<String, Object> entry = Sessions.parseJsonObjectLine(line);
            if (entry == null || !"user".equals(entry.get("type"))) {
                continue;
            }
            Object message = entry.get("message");
            if (!(message instanceof Map<?, ?> msg)) {
                continue;
            }

            String prompt = fromTexts(textBlocks(msg.get("content")), name -> {
                if (commandFallback[0].isEmpty()) {
                    commandFallback[0] = name;
                }
            });
            if (prompt != null) {
                return prompt;
            }
        }
        return commandFallback[0];
    }

    /**
     * The text strings of a user message's {@code content}: the string
     * itself, or every {@code {"type":"text","text":<string>}} block of a
     * block list.
     */
    public static List<String> textBlocks(@Nullable Object content) {
        if (content instanceof String s) {
            return List.of(s);
        }
        List<String> texts = new ArrayList<>();
        if (content instanceof List<?> list) {
            for (Object block : list) {
                if (block instanceof Map<?, ?> b
                        && "text".equals(b.get("type"))
                        && b.get("text") instanceof String s) {
                    texts.add(s);
                }
            }
        }
        return texts;
    }

    /**
     * Applies the per-text rules to one message's text blocks, in order.
     *
     * <p>Each text has its newlines collapsed to spaces and is stripped; empty
     * texts are skipped. A text containing a {@code <command-name>} tag is
     * skipped after its command name is passed to {@code onCommandName} (the
     * caller keeps the first as its fallback). A text matching the skip
     * pattern is skipped. The first remaining text is the prompt.
     *
     * @return the truncated prompt, or {@code null} when every text was skipped
     */
    public static @Nullable String fromTexts(List<String> texts, Consumer<String> onCommandName) {
        for (String raw : texts) {
            String result = PyStrings.strip(raw.replace("\n", " "));
            if (result.isEmpty()) {
                continue;
            }

            Matcher cmdMatch = COMMAND_NAME_RE.matcher(result);
            if (cmdMatch.find()) {
                onCommandName.accept(cmdMatch.group(1));
                continue;
            }

            if (SKIP_FIRST_PROMPT_PATTERN.matcher(result).lookingAt()) {
                continue;
            }

            return truncate(result);
        }
        return null;
    }

    /**
     * Truncates to {@value #MAX_PROMPT_LENGTH} code points (as Python slices
     * {@code str}), strips trailing whitespace, and appends an ellipsis.
     */
    static String truncate(String text) {
        if (text.codePointCount(0, text.length()) <= MAX_PROMPT_LENGTH) {
            return text;
        }
        String cut = text.substring(0, text.offsetByCodePoints(0, MAX_PROMPT_LENGTH));
        return PyStrings.rstrip(cut) + "\u2026";
    }
}
