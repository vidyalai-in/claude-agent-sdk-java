package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.ForkSessionResult;
import in.vidyalai.claude.sdk.types.message.SDKSessionInfo;

/**
 * Local-disk session behaviors ported from the Python SDK's
 * {@code _internal/sessions.py} and {@code _internal/session_mutations.py}:
 * project-dir lookup, directory canonicalization, worktree scanning, 0-byte
 * stubs, falsy fallbacks, append semantics and the smaller parsing rules.
 *
 * <p>Points {@code user.home} at a temp dir, like the other session tests.
 * Worktree tests build real git repos and are skipped when git is missing.
 */
class SessionsParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SID = "12345678-1234-1234-1234-123456789abc";
    private static final String SID2 = "22345678-1234-1234-1234-123456789abc";

    @TempDir
    Path tempDir;

    private Path projects;
    private String originalHome;

    @BeforeEach
    void setUp() throws IOException {
        String configDir = System.getenv("CLAUDE_CONFIG_DIR");
        assumeTrue(configDir == null || configDir.isEmpty(), "CLAUDE_CONFIG_DIR overrides user.home");
        Path home = tempDir.resolve("home");
        projects = home.resolve(".claude").resolve("projects");
        Files.createDirectories(projects);
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void tearDown() {
        if (originalHome != null) {
            System.setProperty("user.home", originalHome);
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static String json(Map<String, Object> entry) {
        try {
            return MAPPER.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String userLine(String uuid, String text) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", "user");
        e.put("uuid", uuid);
        e.put("parentUuid", null);
        e.put("sessionId", SID);
        e.put("timestamp", "2025-01-01T00:00:00Z");
        e.put("message", Map.of("role", "user", "content", text));
        return json(e);
    }

    private Path projectDirFor(String path) throws IOException {
        Path dir = projects.resolve(Sessions.sanitizePath(path));
        Files.createDirectories(dir);
        return dir;
    }

    private static Path writeSession(Path projectDir, String sessionId, String... lines) throws IOException {
        Path file = projectDir.resolve(sessionId + ".jsonl");
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    private static String real(Path p) throws IOException {
        return p.toRealPath().toString();
    }

    private static List<String> ids(List<SDKSessionInfo> infos) {
        List<String> out = new ArrayList<>();
        for (SDKSessionInfo i : infos) {
            out.add(i.sessionId());
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // #8 findProjectDir
    // ---------------------------------------------------------------------

    @Test
    void shortPathDoesNotPrefixMatchASiblingProject() throws IOException {
        writeSession(projectDirFor("/home/u/app-v2"), SID, userLine("m1", "other project"));

        assertThat(Sessions.findProjectDir("/home/u/app")).isNull();
        assertThat(Sessions.listSessions("/home/u/app", null, false)).isEmpty();
    }

    @Test
    void exactMatchMustBeADirectory() throws IOException {
        Files.writeString(projects.resolve(Sessions.sanitizePath("/x/file")), "not a dir");
        assertThat(Sessions.findProjectDir("/x/file")).isNull();
    }

    @Test
    void longPathFallsBackOnlyToPrefixPlusDash() throws IOException {
        String longPath = "/" + "a".repeat(250);
        String prefix = Sessions.sanitizePath(longPath).substring(0, Sessions.MAX_SANITIZED_LENGTH);
        assertThat(Sessions.getProjectDir(longPath)).doesNotExist();

        Files.createDirectories(projects.resolve(prefix + "zzz"));
        assertThat(Sessions.findProjectDir(longPath)).isNull();

        Path bunHashed = Files.createDirectories(projects.resolve(prefix + "-bunhash"));
        assertThat(Sessions.findProjectDir(longPath)).isEqualTo(bunHashed);
    }

    // ---------------------------------------------------------------------
    // #9 canonicalization
    // ---------------------------------------------------------------------

    private Path symlinkTo(Path target, String name) throws IOException {
        Path link = tempDir.resolve(name);
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symlinks not supported: " + e);
        }
        return link;
    }

    @Test
    void canonicalizeResolvesSymlinksDotsAndAMissingTail() throws IOException {
        Path realDir = Files.createDirectories(tempDir.resolve("real"));
        Path link = symlinkTo(realDir, "link");

        assertThat(Sessions.canonicalizePath(link.toString())).isEqualTo(real(realDir));
        assertThat(Sessions.canonicalizePath(link + "/./sub/../missing/x"))
                .isEqualTo(real(realDir) + "/missing/x");
    }

    @Test
    void canonicalizeNormalizesToNfc() {
        String nfd = tempDir.resolve("cafe\u0301").toString();
        assertThat(Sessions.canonicalizePath(nfd)).endsWith("caf\u00e9");
    }

    @Test
    void readsAndWritesThroughASymlinkedDirectory() throws IOException {
        Path realDir = Files.createDirectories(tempDir.resolve("real"));
        Path link = symlinkTo(realDir, "link");
        Path file = writeSession(projectDirFor(real(realDir)), SID, userLine("m1", "hello"));

        assertThat(ids(Sessions.listSessions(link.toString(), null, false))).containsExactly(SID);
        SDKSessionInfo info = Sessions.getSessionInfo(SID, link.toString());
        assertThat(info).isNotNull();
        assertThat(Sessions.getSessionMessages(SID, link.toString(), null, 0)).hasSize(1);

        SessionMutations.renameSession(SID, "Renamed", link.toString());
        assertThat(Files.readString(file)).contains("\"customTitle\":\"Renamed\"");
    }

    @Test
    void nfdDirectoryFindsTheNfcProjectDir() throws IOException {
        String nfc = tempDir.resolve("caf\u00e9").toString();
        String nfd = tempDir.resolve("cafe\u0301").toString();
        assertThat(Sessions.sanitizePath(nfc)).isNotEqualTo(Sessions.sanitizePath(nfd));
        writeSession(projectDirFor(nfc), SID, userLine("m1", "hello"));

        assertThat(ids(Sessions.listSessions(nfd, null, false))).containsExactly(SID);
    }

    @Test
    void configDirOverrideIsNfcNormalized() {
        Path projectsDir = Sessions.getProjectsDirForEnv(Map.of("CLAUDE_CONFIG_DIR", "/x/cafe\u0301"));
        assertThat(projectsDir.toString()).isEqualTo("/x/caf\u00e9/projects");
    }

    // ---------------------------------------------------------------------
    // #22 0-byte stubs
    // ---------------------------------------------------------------------

    @Test
    void readSessionLiteTreatsAnEmptyFileAsMissing() throws IOException {
        Path stub = Files.createFile(tempDir.resolve("stub.jsonl"));
        assertThat(Sessions.readSessionLite(stub)).isNull();
    }

    @SuppressWarnings("null") // info is asserted non-null first
    @Test
    void emptyStubsAreSkippedWhenSearchingAllProjects() throws IOException {
        Files.createFile(projectDirFor("/a/one").resolve(SID + ".jsonl"));
        writeSession(projectDirFor("/a/two"), SID, userLine("m1", "found"));
        Files.createFile(projectDirFor("/a/three").resolve(SID + ".jsonl"));

        SDKSessionInfo info = Sessions.getSessionInfo(SID, null);
        assertThat(info).isNotNull();
        assertThat(info.summary()).isEqualTo("found");
        assertThat(Sessions.getSessionMessages(SID, null, null, 0)).hasSize(1);
    }

    // ---------------------------------------------------------------------
    // #24 falsy fallbacks
    // ---------------------------------------------------------------------

    @SuppressWarnings("null") // info is asserted non-null first
    @Test
    void emptyCustomTitleFallsThroughToAiTitle() throws IOException {
        writeSession(projectDirFor("/p"), SID,
                userLine("m1", "hello"),
                "{\"type\":\"custom-title\",\"customTitle\":\"\",\"sessionId\":\"" + SID + "\"}",
                "{\"type\":\"ai-title\",\"aiTitle\":\"AI title\",\"sessionId\":\"" + SID + "\"}");

        SDKSessionInfo info = Sessions.getSessionInfo(SID, "/p");
        assertThat(info).isNotNull();
        assertThat(info.customTitle()).isEqualTo("AI title");
        assertThat(info.summary()).isEqualTo("AI title");
    }

    @SuppressWarnings("null") // info is asserted non-null first
    @Test
    void emptyGitBranchAndCwdFallBack() throws IOException {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("type", "user");
        first.put("uuid", "m1");
        first.put("cwd", "");
        first.put("gitBranch", "main");
        first.put("message", Map.of("role", "user", "content", "hello"));
        writeSession(projectDirFor("/p"), SID,
                json(first),
                "{\"type\":\"system\",\"uuid\":\"m2\",\"gitBranch\":\"\"}");

        SDKSessionInfo info = Sessions.getSessionInfo(SID, "/p");
        assertThat(info).isNotNull();
        assertThat(info.gitBranch()).isEqualTo("main");
        assertThat(info.cwd()).isEqualTo("/p");
    }

    @Test
    void forkTitleSkipsAnEmptyCustomTitle() throws IOException {
        Path dir = projectDirFor("/p");
        writeSession(dir, SID,
                userLine("11111111-1111-1111-1111-111111111111", "hello"),
                "{\"type\":\"custom-title\",\"customTitle\":\"\",\"sessionId\":\"" + SID + "\"}",
                "{\"type\":\"ai-title\",\"aiTitle\":\"AI title\",\"sessionId\":\"" + SID + "\"}");

        ForkSessionResult fork = SessionMutations.forkSession(SID, "/p", null, null);
        String forked = Files.readString(dir.resolve(fork.sessionId() + ".jsonl"));
        assertThat(forked).contains("\"customTitle\":\"AI title (fork)\"");
    }

    // ---------------------------------------------------------------------
    // Tier 3: field extraction, strict JSON lines, simpleHash
    // ---------------------------------------------------------------------

    @Test
    void lastFieldIgnoresATruncatedValue() {
        String text = "{\"customTitle\":\"Good\"}\n{\"customTitle\":\"Trunc";
        assertThat(Sessions.extractLastJsonStringField(text, "customTitle")).isEqualTo("Good");
        assertThat(Sessions.extractJsonStringField("{\"customTitle\":\"Trunc", "customTitle")).isNull();
        assertThat(Sessions.extractLastJsonStringField("{\"customTitle\":\"Tr\\\"unc", "customTitle")).isNull();
    }

    @Test
    void invalidEscapeIsReturnedRaw() {
        // Python returns the raw text when json.loads rejects it; no partial unescaping.
        assertThat(Sessions.unescapeJsonString("a\\nb\\q")).isEqualTo("a\\nb\\q");
    }

    @Test
    void transcriptParsingRejectsConcatenatedObjects() {
        String content = userLine("a", "one") + userLine("b", "two") + "\n" + userLine("c", "three") + "\n";
        List<Map<String, Object>> entries = Sessions.parseTranscriptEntries(content);
        assertThat(entries).extracting(e -> e.get("uuid")).containsExactly("c");
    }

    @Test
    void forkSkipsConcatenatedObjectLines() throws IOException {
        Path dir = projectDirFor("/p");
        writeSession(dir, SID,
                userLine("11111111-1111-1111-1111-111111111111", "one")
                        + userLine("22222222-2222-2222-2222-222222222222", "two"),
                userLine("33333333-3333-3333-3333-333333333333", "three"));

        ForkSessionResult fork = SessionMutations.forkSession(SID, "/p", null, "t");
        List<String> lines = Files.readAllLines(dir.resolve(fork.sessionId() + ".jsonl"));
        assertThat(lines).hasSize(2); // one message + the custom-title entry
        assertThat(lines.get(0)).contains("three");
    }

    @Test
    void simpleHashOfIntMinValueIsPositive() {
        // h == Integer.MIN_VALUE; JS/Python Math.abs gives 2^31 = "zik0zk" in base 36.
        assertThat(Sessions.simpleHash("/p/stozpnhfaf\u50fb")).isEqualTo("zik0zk");
        assertThat(Sessions.simpleHash("")).isEqualTo("0");
    }

    // ---------------------------------------------------------------------
    // #12 append semantics, tier-3 mutation rules
    // ---------------------------------------------------------------------

    @Test
    void tryAppendPropagatesPermissionDenied() throws IOException {
        Path file = Files.writeString(tempDir.resolve("ro.jsonl"), "line1\n");
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "no POSIX permissions");
        }
        assumeTrue(!Files.isWritable(file), "running as a user that ignores permissions");

        assertThatThrownBy(() -> SessionMutations.tryAppend(file, "x\n"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void renameSurfacesAWriteFailureInsteadOfNotFound() throws IOException {
        Path file = writeSession(projectDirFor("/p"), SID, userLine("m1", "hello"));
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "no POSIX permissions");
        }
        assumeTrue(!Files.isWritable(file), "running as a user that ignores permissions");

        assertThatThrownBy(() -> SessionMutations.renameSession(SID, "t", "/p"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> SessionMutations.renameSession(SID, "t", null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void uppercaseSessionIdIsAccepted() throws IOException {
        String upper = SID.toUpperCase(java.util.Locale.ROOT);
        Path file = writeSession(projectDirFor("/p"), upper, userLine("11111111-1111-1111-1111-111111111111", "hi"));

        SessionMutations.renameSession(upper, "Upper", "/p");
        SessionMutations.tagSession(upper, "tag", "/p");
        String content = Files.readString(file);
        assertThat(content).contains("\"customTitle\":\"Upper\"");
        assertThat(content).contains("\"tag\":\"tag\"");
        assertThat(SessionMutations.forkSession(upper, "/p", null, null)).isNotNull();
    }

    @Test
    void uppercaseUpToMessageIdIsValid() throws IOException {
        String msg = "ABCDEF12-1111-1111-1111-111111111111";
        writeSession(projectDirFor("/p"), SID, userLine(msg, "hi"), userLine("22222222-2222-2222-2222-222222222222", "more"));
        ForkSessionResult fork = SessionMutations.forkSession(SID, "/p", msg, null);
        List<String> lines = Files.readAllLines(projects.resolve(Sessions.sanitizePath("/p"))
                .resolve(fork.sessionId() + ".jsonl"));
        assertThat(lines).hasSize(2);
    }

    @Test
    void emptyUpToMessageIdMeansNoCutoff() throws IOException {
        Path dir = projectDirFor("/p");
        writeSession(dir, SID,
                userLine("11111111-1111-1111-1111-111111111111", "one"),
                userLine("22222222-2222-2222-2222-222222222222", "two"));

        ForkSessionResult fork = SessionMutations.forkSession(SID, "/p", "", null);
        assertThat(Files.readAllLines(dir.resolve(fork.sessionId() + ".jsonl"))).hasSize(3);
    }

    @Test
    void forkFileIsOwnerReadWriteOnly() throws IOException {
        Path dir = projectDirFor("/p");
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        writeSession(dir, SID, userLine("11111111-1111-1111-1111-111111111111", "one"));

        ForkSessionResult fork = SessionMutations.forkSession(SID, "/p", null, null);
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(dir.resolve(fork.sessionId() + ".jsonl"));
        assertThat(PosixFilePermissions.toString(perms)).isEqualTo("rw-------");
    }

    // ---------------------------------------------------------------------
    // #19 #20 #21 #22 worktrees (real git repos)
    // ---------------------------------------------------------------------

    private static void git(Path cwd, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-c");
        cmd.add("user.email=t@example.com");
        cmd.add("-c");
        cmd.add("user.name=t");
        cmd.add("-c");
        cmd.add("commit.gpgsign=false");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
        pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            assumeTrue(false, "git not available: " + e);
            return;
        }
        try {
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
            if (p.exitValue() != 0) {
                throw new AssertionError("git " + String.join(" ", args) + ": " + out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    /** Creates {@code repo} with one commit; returns its real path. */
    private Path initRepo(String name) throws IOException {
        Path repo = Files.createDirectories(tempDir.resolve(name));
        git(repo, "init", "-q");
        git(repo, "commit", "-q", "--allow-empty", "-m", "init");
        return repo.toRealPath();
    }

    /** Adds a worktree beside {@code repo}; returns its real path. */
    private Path addWorktree(Path repo, String name) throws IOException {
        Path wt = repo.resolveSibling(name);
        git(repo, "worktree", "add", "-q", "-b", name, wt.toString());
        return wt.toRealPath();
    }

    @Test
    void listSessionsPathOverloadIncludesWorktreesByDefault() throws IOException {
        Path repo = initRepo("repo");
        Path wt = addWorktree(repo, "repo-wt");
        writeSession(projectDirFor(repo.toString()), SID, userLine("m1", "main"));
        writeSession(projectDirFor(wt.toString()), SID2, userLine("m1", "worktree"));

        assertThat(ids(ClaudeSDK.listSessions(repo))).containsExactlyInAnyOrder(SID, SID2);
        assertThat(ids(ClaudeSDK.listSessions(repo, null, false))).containsExactly(SID);
    }

    @Test
    void withOneWorktreeOnlyTheDirectoryIsRead() throws IOException {
        Path repo = initRepo("repo");
        Path sub = Files.createDirectories(repo.resolve("packages").resolve("app"));
        writeSession(projectDirFor(repo.toString()), SID, userLine("m1", "repo root"));
        writeSession(projectDirFor(sub.toString()), SID2, userLine("m1", "subdir"));

        assertThat(ids(Sessions.listSessions(sub.toString(), null, true))).containsExactly(SID2);
    }

    @Test
    void worktreeScanMatchesExactNamesOnly() throws IOException {
        Path repo = initRepo("repo");
        Path wt = addWorktree(repo, "repo-wt");
        writeSession(projectDirFor(repo.toString()), SID, userLine("m1", "main"));
        // A different project whose name merely starts with the worktree's.
        writeSession(projectDirFor(wt + "-foo"), SID2, userLine("m1", "unrelated"));

        assertThat(ids(Sessions.listSessions(repo.toString(), null, true))).containsExactly(SID);
    }

    @Test
    void worktreeScanIncludesTheGivenSubdirectory() throws IOException {
        Path repo = initRepo("repo");
        Path wt = addWorktree(repo, "repo-wt");
        Path sub = Files.createDirectories(repo.resolve("sub"));
        writeSession(projectDirFor(sub.toString()), SID, userLine("m1", "sub"));
        writeSession(projectDirFor(wt.toString()), SID2, userLine("m1", "wt"));

        assertThat(ids(Sessions.listSessions(sub.toString(), null, true))).containsExactlyInAnyOrder(SID, SID2);
    }

    @Test
    void worktreeNamesCompareCaseInsensitivelyOnWindows() throws IOException {
        Path repo = initRepo("repo");
        Path wt = addWorktree(repo, "repo-wt");
        writeSession(projectDirFor(repo.toString()), SID, userLine("m1", "main"));
        Path upper = projects.resolve(Sessions.sanitizePath(wt.toString()).toUpperCase(java.util.Locale.ROOT));
        Files.createDirectories(upper);
        writeSession(upper, SID2, userLine("m1", "upper"));

        assertThat(ids(Sessions.listSessions(repo.toString(), null, true))).containsExactly(SID);
        String osName = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            assertThat(ids(Sessions.listSessions(repo.toString(), null, true)))
                    .containsExactlyInAnyOrder(SID, SID2);
        } finally {
            System.setProperty("os.name", osName);
        }
    }

    @SuppressWarnings("null") // info is asserted non-null first
    @Test
    void lookupsSkipAnEmptyStubAndFallBackToTheWorktree() throws IOException {
        Path repo = initRepo("repo");
        Path wt = addWorktree(repo, "repo-wt");
        Files.createFile(projectDirFor(repo.toString()).resolve(SID + ".jsonl"));
        writeSession(projectDirFor(wt.toString()), SID, userLine("m1", "in worktree"));

        assertThat(Sessions.getSessionMessages(SID, repo.toString(), null, 0)).hasSize(1);
        SDKSessionInfo info = Sessions.getSessionInfo(SID, repo.toString());
        assertThat(info).isNotNull();
        assertThat(info.summary()).isEqualTo("in worktree");
    }

    @Test
    void renameAndTagFallBackToTheWorktree() throws IOException {
        Path repo = initRepo("repo");
        Path wt = addWorktree(repo, "repo-wt");
        Path file = writeSession(projectDirFor(wt.toString()), SID, userLine("m1", "in worktree"));
        projectDirFor(repo.toString()); // the repo has its own project dir, without this session

        ClaudeSDK.renameSession(SID, "Renamed", repo);
        ClaudeSDK.tagSession(SID, "wt-tag", repo);

        String content = Files.readString(file);
        assertThat(content).contains("\"customTitle\":\"Renamed\"");
        assertThat(content).contains("\"tag\":\"wt-tag\"");
        assertThatThrownBy(() -> ClaudeSDK.renameSession(SID2, "x", repo))
                .isInstanceOf(FileNotFoundException.class);
    }
}
