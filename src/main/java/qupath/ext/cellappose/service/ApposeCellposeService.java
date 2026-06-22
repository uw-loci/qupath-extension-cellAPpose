package qupath.ext.cellappose.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apposed.appose.Appose;
import org.apposed.appose.Environment;
import org.apposed.appose.Service;
import org.apposed.appose.Service.ResponseType;
import org.apposed.appose.Service.Task;
import org.apposed.appose.TaskException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.cellappose.core.CellposeModelFamily;
import qupath.ext.cellappose.ui.PythonConsoleWindow;
import qupath.lib.common.GeneralTools;

/**
 * Singleton managing two Appose environments -- one per {@link CellposeModelFamily}.
 *
 * <p>Cellpose 3 and Cellpose-SAM cannot coexist in one Python environment, so each
 * family gets its own {@code Environment} + {@code Service} + cached-model state,
 * wrapped in an {@link EnvHandle}. Each family is built lazily on first use; both
 * services route Python output to the same {@link PythonConsoleWindow}.
 *
 * <p>This generalizes PPM's {@code ApposePPMService} single-env helpers to be
 * family-keyed: {@code getEnvironmentPath(family)}, {@code isEnvironmentBuilt(family)},
 * {@code buildEnvironment(family, callback)}, {@code runTile(family, inputs)},
 * {@code deleteEnvironment(family)}. The TCCL swap, syncManifest, init+verify, and
 * shutdown-hook patterns are copied from PPM.
 */
public final class ApposeCellposeService {

    private static final Logger logger = LoggerFactory.getLogger(ApposeCellposeService.class);

    private static final String RESOURCE_BASE = "qupath/ext/cellappose/";
    private static final String SCRIPTS_BASE = RESOURCE_BASE + "scripts/";

    private static ApposeCellposeService instance;

    private final Map<CellposeModelFamily, EnvHandle> handles = new EnumMap<>(CellposeModelFamily.class);
    private Thread shutdownHook;

    private ApposeCellposeService() {}

    /** Gets the singleton instance. */
    public static synchronized ApposeCellposeService getInstance() {
        if (instance == null) {
            instance = new ApposeCellposeService();
        }
        return instance;
    }

    /** Per-family state: the Appose env + service + the cached-model key. */
    private static final class EnvHandle {
        private final CellposeModelFamily family;
        private Environment environment;
        private Service pythonService;
        private boolean initialized;
        private String initError;
        private String cachedModelKey;
        // Per-tile task script, loaded once from the JAR at build time and reused for
        // every tile (avoids re-reading + re-parsing the script per tile on a WSI).
        private String cachedTaskScript;

        EnvHandle(CellposeModelFamily family) {
            this.family = family;
        }
    }

    private synchronized EnvHandle handle(CellposeModelFamily family) {
        return handles.computeIfAbsent(family, EnvHandle::new);
    }

    // ==================== Public family-keyed surface ====================

    /**
     * Returns the path where the given family's Appose pixi environment lives.
     */
    public static Path getEnvironmentPath(CellposeModelFamily family) {
        return Path.of(System.getProperty("user.home"), ".local", "share", "appose", family.envName());
    }

    /**
     * Fast filesystem check: does the family's pixi environment appear built on
     * disk? Does NOT trigger any downloads or Appose init.
     */
    public static boolean isEnvironmentBuilt(CellposeModelFamily family) {
        Path envDir = getEnvironmentPath(family);
        return Files.isDirectory(envDir.resolve(".pixi"));
    }

    /** Whether the family's service is initialized and ready to run tiles. */
    public synchronized boolean isAvailable(CellposeModelFamily family) {
        EnvHandle h = handle(family);
        return h.initialized && h.initError == null && h.pythonService != null;
    }

    /** The family's last init error, or null. */
    public synchronized String getInitError(CellposeModelFamily family) {
        return handle(family).initError;
    }

    /**
     * Builds the family's pixi environment and starts its Python service.
     * Slow the first time (downloads PyTorch + Cellpose), instant afterward.
     * Does NOT load a model -- call {@link #ensureModel} before running tiles.
     *
     * @param family         the model family
     * @param statusCallback optional progress callback (may be null)
     * @throws IOException on resource load or build failure
     */
    public synchronized void buildEnvironment(CellposeModelFamily family, Consumer<String> statusCallback)
            throws IOException {
        EnvHandle h = handle(family);
        if (h.initialized) {
            report(statusCallback, "Already initialized");
            return;
        }
        try {
            report(statusCallback, "Loading environment configuration...");
            logger.info("Initializing cellAPpose env for {}...", family);

            String pixiToml = loadResource(RESOURCE_BASE + family.tomlResource());
            String pixiLock = loadResource(RESOURCE_BASE + family.lockResource());

            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(ApposeCellposeService.class.getClassLoader());
            try {
                // Sync manifest + lock; the staged lock lets the install run
                // --frozen (exact pinned versions, no re-resolution).
                syncManifest(family, pixiToml, pixiLock);

                report(statusCallback, "Building pixi environment (this may take several minutes)...");
                // NOTE: do NOT pass .flags("--frozen") here. Appose's PixiBuilder injects
                // builder flags as GLOBAL pixi args (pixi --frozen ...), which pixi rejects
                // ("unexpected argument '--frozen'"). The committed lock is staged into the
                // env dir by syncManifest above, and the explicit runPixiInstall below runs
                // `pixi install --frozen` (subcommand flag, correct), so the frozen-from-lock
                // intent is preserved without breaking build().
                h.environment = Appose.pixi()
                        .content(pixiToml)
                        .scheme("pixi.toml")
                        .name(family.envName())
                        .logDebug()
                        .build();
                logger.info("cellAPpose {} env configured at: {}", family, h.environment.base());

                // Install strictly from the bundled lock (--frozen) so cellpose +
                // torch are the exact pinned versions and pixi never re-resolves.
                runPixiInstall(family, statusCallback);

                report(statusCallback, "Starting Python service...");
                h.pythonService = h.environment.python();
                wireConsole(h);

                // Init script, run once; its non-underscore globals persist into every
                // subsequent task scope (Appose init-export semantics). Two jobs:
                //   1. Pre-import numpy first to dodge the Windows stdin-reader deadlock.
                //   2. Define cp_utils' helpers (get_torch_device, ...) as worker globals.
                // The vendored cp3/cp4/init scripts only "from cp_utils import ..." under
                // their non-appose branch; in appose mode they expect get_torch_device to
                // already be in scope (upstream injects it the same way), so without this
                // the model init raises NameError: name 'get_torch_device' is not defined.
                String cpUtils = loadScript("cp_utils.py");
                h.pythonService.init("import numpy\n" + cpUtils);

                report(statusCallback, "Verifying installed packages...");
                String verifyScript = loadScript("verify_env.py");
                Task verifyTask = h.pythonService.task(verifyScript);
                verifyTask.listen(event -> {
                    if (event.responseType == ResponseType.FAILURE || event.responseType == ResponseType.CRASH) {
                        logger.error("cellAPpose {} verify failed: {}", family, verifyTask.error);
                    }
                });
                verifyTask.waitFor();

                String importError = String.valueOf(verifyTask.outputs.get("import_error"));
                if (importError != null && !importError.isEmpty() && !"null".equals(importError)) {
                    throw new IOException("Python verification failed: " + importError);
                }
                logger.info(
                        "=== cellAPpose {} environment ===\n  cellpose: {}\n  torch: {}\n  cuda: {}\n  mps: {}\n  path: {}",
                        family,
                        verifyTask.outputs.get("cellpose_version"),
                        verifyTask.outputs.get("torch_version"),
                        verifyTask.outputs.get("cuda_available"),
                        verifyTask.outputs.get("mps_available"),
                        getEnvironmentPath(family));

                // Load the per-tile task script ONCE; reuse it for every tile.
                h.cachedTaskScript = loadScript(family.taskScript());

                h.initialized = true;
                h.initError = null;
                registerShutdownHook();
                report(statusCallback, "Setup complete!");
            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }
        } catch (Exception e) {
            // Match failure signatures against the full cause chain -- pixi's
            // BuildException only says "pixi build failed" at the top; the
            // actionable Windows file-lock text lives in a nested cause.
            String fullMsg = collectCauseMessages(e);
            h.initError = e.getMessage();
            h.initialized = false;
            logger.error("Failed to build cellAPpose {} env: {}", family, e.getMessage(), e);
            if (looksLikeWindowsFileLock(fullMsg)) {
                logger.warn(
                        "Pixi env install hit a Windows file lock; manual recovery required\n{}",
                        windowsFileLockAdvice(family.envName()));
                report(statusCallback, "Pixi env install failed: Windows file lock. See recovery steps.");
                try {
                    qupath.fx.dialogs.Dialogs.showWarningNotification(
                            "CellAPpose",
                            "Pixi env install failed: a file was locked by another process. Close QuPath,"
                                    + " delete the .pixi folder, and relaunch. See the log for full recovery steps.");
                } catch (Exception fxEx) {
                    // FX not running; log advice already emitted.
                }
            }
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    /**
     * Ensures the family's Python service has the requested model loaded and cached.
     * Runs the family init script (which exports {@code model} via {@code task.export})
     * only when the model-cache key has changed.
     *
     * @param family        the model family
     * @param modelCacheKey a key identifying the model+gpu configuration
     * @param initInputs    the init-script inputs (model name / custom path / gpu)
     * @throws IOException if the service is not built or the init task fails
     */
    public synchronized void ensureModel(
            CellposeModelFamily family, String modelCacheKey, Map<String, Object> initInputs) throws IOException {
        EnvHandle h = handle(family);
        if (!isAvailable(family)) {
            throw new IOException("cellAPpose " + family + " service is not available"
                    + (h.initError != null ? ": " + h.initError : ""));
        }
        if (modelCacheKey.equals(h.cachedModelKey)) {
            return; // already cached
        }
        logger.info("Loading {} model (cache key {})...", family, modelCacheKey);
        Task task = submit(family, family.initScript(), initInputs);
        if (task.error != null && !task.error.isEmpty()) {
            throw new IOException("cellAPpose " + family + " model load failed: " + task.error);
        }
        h.cachedModelKey = modelCacheKey;
    }

    /**
     * Runs a single tile through the family's per-tile task script. The caller has
     * already loaded the model via {@link #ensureModel}.
     *
     * @param family the model family
     * @param inputs the full bare-globals input map (from CellposeParameters)
     * @return the completed task (outputs + any error)
     * @throws IOException if the service is unavailable or the task fails
     */
    public synchronized Task runTile(CellposeModelFamily family, Map<String, Object> inputs) throws IOException {
        if (!isAvailable(family)) {
            throw new IOException("cellAPpose " + family + " service is not available");
        }
        EnvHandle h = handle(family);
        // Reuse the task script loaded once at build time (avoids per-tile JAR reads).
        String script = h.cachedTaskScript;
        if (script == null) {
            script = loadScript(family.taskScript());
            h.cachedTaskScript = script;
        }
        Task task = submitScript(family, family.taskScript(), script, inputs);
        if (task.error != null && !task.error.isEmpty()) {
            throw new IOException("cellAPpose " + family + " tile task failed: " + task.error);
        }
        return task;
    }

    /** Shuts down both family services (closes Python subprocesses). */
    public synchronized void shutdown() {
        for (EnvHandle h : handles.values()) {
            closeService(h);
            h.initialized = false;
            h.cachedModelKey = null;
        }
        removeShutdownHook();
        logger.info("cellAPpose services shut down");
    }

    /**
     * Deletes the family's Appose environment from disk. The family's service must
     * be shut down first.
     */
    public synchronized void deleteEnvironment(CellposeModelFamily family) throws IOException {
        EnvHandle h = handle(family);
        closeService(h);
        h.initialized = false;
        h.cachedModelKey = null;
        if (h.environment != null) {
            try {
                logger.info("Deleting cellAPpose {} env via API: {}", family, h.environment.base());
                h.environment.delete();
                h.environment = null;
                return;
            } catch (Exception e) {
                logger.warn("environment.delete() failed, falling back to manual delete: {}", e.getMessage());
                h.environment = null;
            }
        }
        Path envPath = getEnvironmentPath(family);
        if (Files.exists(envPath)) {
            logger.info("Deleting cellAPpose env directory: {}", envPath);
            deleteDirectoryRecursively(envPath);
        }
    }

    /** Executes a callable with the extension classloader as TCCL. */
    public static <T> T withExtensionClassLoader(Callable<T> callable) throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ApposeCellposeService.class.getClassLoader());
        try {
            return callable.call();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    // ==================== Internal helpers ====================

    /** Submits a task by loading the named script from the JAR (used for init scripts). */
    private Task submit(CellposeModelFamily family, String scriptName, Map<String, Object> inputs) throws IOException {
        String script;
        try {
            script = loadScript(scriptName);
        } catch (IOException e) {
            throw new IOException("Failed to load script: " + scriptName, e);
        }
        return submitScript(family, scriptName, script, inputs);
    }

    /**
     * Submits a task using a pre-loaded script string (used for the cached per-tile script).
     *
     * <p>Retries on the well-known Appose "thread death" race: under repeated submission a prior
     * task's worker-thread cleanup event is occasionally misattributed to the next task's UUID, so
     * Java sees an immediate FAILURE even though the Python side is healthy. This bites on the first
     * real per-tile run (a WSI submits the per-tile script thousands of times) even though our
     * submission is serialized. On a {@code thread death} TaskException we sleep 250 ms and resubmit,
     * up to 3 total attempts. Pattern from the DL pixel classifier / fiber-analysis services.
     */
    private Task submitScript(CellposeModelFamily family, String scriptName, String script, Map<String, Object> inputs)
            throws IOException {
        EnvHandle h = handle(family);
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ApposeCellposeService.class.getClassLoader());
        try {
            int maxAttempts = 3;
            TaskException lastThreadDeath = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    Task task = h.pythonService.task(script, inputs);
                    task.listen(event -> {
                        if (event.responseType == ResponseType.CRASH) {
                            logger.error("cellAPpose {} '{}' CRASH: {}", family, scriptName, task.error);
                        } else if (event.responseType == ResponseType.FAILURE) {
                            logger.error("cellAPpose {} '{}' FAILURE: {}", family, scriptName, task.error);
                        }
                    });
                    task.waitFor();
                    return task;
                } catch (TaskException e) {
                    String message =
                            e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                    if (message.contains("thread death") && attempt < maxAttempts) {
                        lastThreadDeath = e;
                        logger.warn(
                                "cellAPpose {} '{}' hit Appose thread-death on attempt {}/{}; retrying",
                                family,
                                scriptName,
                                attempt,
                                maxAttempts);
                        Thread.sleep(250);
                        continue;
                    }
                    throw new IOException(
                            "cellAPpose " + family + " '" + scriptName + "' failed: " + e.getMessage(), e);
                }
            }
            throw new IOException(
                    "cellAPpose " + family + " '" + scriptName + "' failed after " + maxAttempts
                            + " attempts (Appose thread death): "
                            + (lastThreadDeath == null ? "" : lastThreadDeath.getMessage()),
                    lastThreadDeath);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("cellAPpose " + family + " '" + scriptName + "' interrupted", e);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    private void wireConsole(EnvHandle h) {
        String prefix = "[" + h.family.envName() + "] ";
        h.pythonService.debug(msg -> {
            logger.info("[CellAPpose Python]{}{}", prefix, msg);
            PythonConsoleWindow.appendMessage(prefix + msg);
        });
    }

    private void closeService(EnvHandle h) {
        if (h.pythonService != null) {
            try {
                logger.info("Shutting down cellAPpose {} service...", h.family);
                h.pythonService.close();
                if (h.pythonService.isAlive()) {
                    long deadline = System.currentTimeMillis() + 5000;
                    while (h.pythonService.isAlive() && System.currentTimeMillis() < deadline) {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (h.pythonService.isAlive()) {
                    logger.warn("cellAPpose {} did not exit gracefully, force-killing", h.family);
                    h.pythonService.kill();
                }
            } catch (Exception e) {
                try {
                    h.pythonService.kill();
                } catch (Exception ignored) {
                    // nothing more we can do
                }
                logger.warn("Error during cellAPpose {} shutdown: {}", h.family, e.getMessage());
            }
            h.pythonService = null;
        }
    }

    /** Runs {@code pixi install} for the family's manifest to resolve all deps. */
    private void runPixiInstall(CellposeModelFamily family, Consumer<String> statusCallback) throws IOException {
        EnvHandle h = handle(family);
        Path envBase = Path.of(h.environment.base());
        Path manifestPath = envBase.resolve("pixi.toml");
        Path pixi = findPixiBinary();
        if (pixi == null) {
            throw new IOException("Cannot find pixi binary. The Appose environment may not "
                    + "have been set up correctly. Try rebuilding the environment.");
        }
        report(statusCallback, "Installing Python dependencies (several minutes on first run)...");
        // --frozen: install the exact versions in the bundled lock; never re-resolve.
        java.util.List<String> command =
                java.util.List.of(pixi.toString(), "install", "--frozen", "--manifest-path", manifestPath.toString());
        logger.info("Running: {}", command);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(envBase.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                logger.info("[pixi] {}", line);
                PythonConsoleWindow.appendMessage("[pixi] " + line);
            }
        }
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("pixi install interrupted", e);
        }
        if (exitCode != 0) {
            throw new IOException("pixi install failed (exit code " + exitCode + "):\n" + output);
        }
    }

    private Path findPixiBinary() {
        Path apposeDir = Path.of(System.getProperty("user.home"), ".local", "share", "appose");
        String pixiName = GeneralTools.isWindows() ? "pixi.exe" : "pixi";
        Path pixi = apposeDir.resolve(".pixi").resolve("bin").resolve(pixiName);
        if (Files.isRegularFile(pixi)) {
            return pixi;
        }
        try {
            Process p = new ProcessBuilder(pixiName, "--version")
                    .redirectErrorStream(true)
                    .start();
            if (p.waitFor() == 0) {
                return Path.of(pixiName);
            }
        } catch (IOException | InterruptedException ignored) {
            // not on PATH
        }
        return null;
    }

    /**
     * Sync the family's on-disk pixi.toml AND pixi.lock with the bundled
     * versions. The lock pins the full dependency tree and the env installs
     * with --frozen, so the lock is staged into the env dir: first run stages
     * the lock (Appose writes the manifest); a change to either file rewrites
     * both and wipes .pixi/ for a clean reinstall; otherwise the lock is
     * re-staged if a prior wipe removed it.
     */
    private void syncManifest(CellposeModelFamily family, String expectedToml, String expectedLock) {
        try {
            Path envDir = getEnvironmentPath(family);
            Path pixiTomlFile = envDir.resolve("pixi.toml");
            Path lockFile = envDir.resolve("pixi.lock");

            if (!Files.exists(pixiTomlFile)) {
                Files.createDirectories(envDir);
                Files.writeString(lockFile, expectedLock, StandardCharsets.UTF_8);
                return;
            }
            String existing = Files.readString(pixiTomlFile, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .strip();
            String exToml = expectedToml.replace("\r\n", "\n").strip();
            String onLock = Files.exists(lockFile)
                    ? Files.readString(lockFile, StandardCharsets.UTF_8)
                            .replace("\r\n", "\n")
                            .strip()
                    : "";
            String exLock = expectedLock.replace("\r\n", "\n").strip();
            if (existing.equals(exToml) && onLock.equals(exLock)) {
                if (!Files.exists(lockFile)) {
                    Files.writeString(lockFile, expectedLock, StandardCharsets.UTF_8);
                }
                return;
            }
            logger.info("cellAPpose {} manifest/lock changed -- forcing rebuild", family);
            Files.writeString(pixiTomlFile, expectedToml, StandardCharsets.UTF_8);
            Files.writeString(lockFile, expectedLock, StandardCharsets.UTF_8);
            Path pixiDir = envDir.resolve(".pixi");
            if (Files.isDirectory(pixiDir)) {
                try {
                    deleteDirectoryRecursively(pixiDir);
                } catch (IOException e) {
                    Path renamed = envDir.resolve(".pixi_old_" + System.currentTimeMillis());
                    try {
                        Files.move(pixiDir, renamed);
                    } catch (IOException e2) {
                        logger.warn("Could not delete or rename .pixi/ -- env may not rebuild: {}", e2.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to sync pixi manifest/lock (will build anyway): {}", e.getMessage());
        }
    }

    private static void deleteDirectoryRecursively(Path directory) throws IOException {
        java.nio.file.FileVisitor<Path> visitor = new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        };
        Files.walkFileTree(directory, visitor);
    }

    private synchronized void registerShutdownHook() {
        if (shutdownHook != null) {
            return;
        }
        shutdownHook = new Thread(
                () -> {
                    logger.info("JVM shutdown hook: cleaning up cellAPpose Python subprocesses");
                    for (EnvHandle h : handles.values()) {
                        Service svc = h.pythonService;
                        if (svc != null) {
                            try {
                                svc.close();
                                if (svc.isAlive()) {
                                    Thread.sleep(2000);
                                }
                                if (svc.isAlive()) {
                                    svc.kill();
                                }
                            } catch (Exception e) {
                                try {
                                    svc.kill();
                                } catch (Exception ignored) {
                                    // give up
                                }
                            }
                        }
                    }
                },
                "CellAPpose-ShutdownHook");
        shutdownHook.setDaemon(false);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    private void removeShutdownHook() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // JVM already shutting down
            }
            shutdownHook = null;
        }
    }

    private String loadScript(String scriptFileName) throws IOException {
        return loadResource(SCRIPTS_BASE + scriptFileName);
    }

    private static String loadResource(String resourcePath) throws IOException {
        try (InputStream is = ApposeCellposeService.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        }
    }

    /** Concatenate the message of a throwable and its entire cause chain.
     *  pixi's BuildException only says "pixi build failed" at the top; the
     *  actionable "failed to link ... os error 32" text lives in a nested
     *  cause, so failure-signature detection must see the whole chain. */
    private static String collectCauseMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int guard = 0;
        for (Throwable c = t; c != null && guard < 20; c = c.getCause(), guard++) {
            if (c.getMessage() != null) {
                sb.append(c.getMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    /** True when the Pixi build failed because Windows held an exclusive lock
     *  on a file the conda link step needed to replace (canonical signature:
     *  "failed to link" + "os error 32" / "being used by another process").
     *  Do NOT auto-wipe -- the blocking process may still be writing. */
    private static boolean looksLikeWindowsFileLock(String message) {
        if (message == null) return false;
        return message.contains("failed to link")
                && (message.contains("os error 32") || message.contains("being used by another process"));
    }

    /** Recovery instructions for the Windows file-lock failure mode during the
     *  Pixi env install. Surfaced to the log; a short notification points here. */
    private static String windowsFileLockAdvice(String envName) {
        return "The Python environment could not finish building because another"
                + " process is holding a file open inside the env directory.\n\n"
                + "RECOVERY STEPS (Windows):\n"
                + "  1. Close QuPath completely (File -> Quit).\n"
                + "  2. Open Task Manager -- end any leftover java.exe or python.exe"
                + " running under your user.\n"
                + "  3. In PowerShell:\n"
                + "       Remove-Item -Recurse -Force \"$env:USERPROFILE\\.local\\share\\appose\\"
                + envName + "\\.pixi\"\n"
                + "  4. (If step 3 fails: reboot Windows -- guaranteed to release every file handle.)\n"
                + "  5. (Optional) Add an antivirus exclusion for"
                + " %USERPROFILE%\\.local\\share\\appose\\ to prevent repeat occurrences.\n"
                + "  6. Relaunch QuPath. The env will rebuild from the bundled lock and the"
                + " link step will succeed.";
    }

    private static void report(Consumer<String> callback, String message) {
        if (callback != null) {
            callback.accept(message);
        }
    }
}
