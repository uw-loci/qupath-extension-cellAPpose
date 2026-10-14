package qupath.ext.cellappose.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import org.apposed.appose.Appose;
import org.apposed.appose.Environment;
import org.apposed.appose.Service;
import org.apposed.appose.Service.ResponseType;
import org.apposed.appose.Service.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.cellappose.core.CellposeModelFamily;
import qupath.ext.cellappose.core.ComputeVariant;
import qupath.ext.cellappose.ui.PythonConsoleWindow;

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
 * {@code deleteEnvironment(family)}. The generic plumbing (manifest staging, pixi,
 * worker shutdown, failure classification, the thread-death retry) lives in the
 * shared copies {@link ApposeSupport} and {@link ApposeTaskRunner}.
 */
public final class ApposeCellposeService {

    private static final Logger logger = LoggerFactory.getLogger(ApposeCellposeService.class);

    private static final String TITLE = "CellAPpose";
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
    private final class EnvHandle {
        private final CellposeModelFamily family;
        private Environment environment;
        private Service pythonService;
        private boolean initialized;
        private String initError;
        private String cachedModelKey;
        // The init inputs behind cachedModelKey, so a replaced worker can reload
        // the same model before the next tile runs.
        private Map<String, Object> cachedModelInputs;
        // Per-tile task script, loaded once from the JAR at build time and reused for
        // every tile (avoids re-reading + re-parsing the script per tile on a WSI).
        private String cachedTaskScript;
        // How ApposeTaskRunner reaches this family's worker and replaces a stale one.
        private final ApposeTaskRunner.WorkerSource workerSource = new ApposeTaskRunner.WorkerSource() {
            @Override
            public Service worker() throws IOException {
                Service service = pythonService;
                if (service != null && !service.isAlive() && initialized && environment != null) {
                    // Died since the build (OOM kill, user killed it): the environment is
                    // still there, so respawn the worker rather than fail the whole run.
                    logger.warn("cellAPpose {} Python worker is not alive; recreating it before the task", family);
                    restartWorker(EnvHandle.this, service);
                    service = pythonService;
                }
                if (service == null) {
                    throw new IOException("cellAPpose " + family + " service is not available");
                }
                return service;
            }

            @Override
            public void replaceWorker(Service stale) throws IOException {
                restartWorker(EnvHandle.this, stale);
            }
        };

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
    /** The variant the user selected (CPU unless they chose otherwise). */
    private static ComputeVariant variant() {
        return ComputeVariant.fromId(qupath.ext.cellappose.CellAPposePrefs.getEnvVariant());
    }

    public static Path getEnvironmentPath(CellposeModelFamily family) {
        return ApposeEnvLocation.resolve(
                qupath.ext.cellappose.CellAPposePrefs.getEnvBaseDir(), family.envName(variant()));
    }

    /**
     * Fast filesystem check: does the family's pixi environment appear built on
     * disk? Does NOT trigger any downloads or Appose init.
     */
    public static boolean isEnvironmentBuilt(CellposeModelFamily family) {
        return ApposeEnvLocation.isBuilt(getEnvironmentPath(family));
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

            // Manifest and lock come from the same family AND variant, so they
            // cannot be paired wrongly.
            ComputeVariant computeVariant = variant();
            String pixiToml = ApposeSupport.loadResource(RESOURCE_BASE + family.tomlResource(computeVariant));
            String pixiLock = ApposeSupport.loadResource(RESOURCE_BASE + family.lockResource(computeVariant));

            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(ApposeSupport.extensionClassLoader());
            try {
                long setupStart = System.nanoTime();
                // Sync manifest + lock; the staged lock lets the install run
                // --frozen (exact pinned versions, no re-resolution).
                ApposeSupport.syncManifest(getEnvironmentPath(family), pixiToml, pixiLock, logger);

                report(statusCallback, "Building pixi environment (this may take several minutes)...");
                // NOTE: do NOT pass .flags("--frozen") here. Appose's PixiBuilder injects
                // builder flags as GLOBAL pixi args (pixi --frozen ...), which pixi rejects
                // ("unexpected argument '--frozen'"). The committed lock is staged into the
                // env dir by syncManifest above, and the explicit runPixiInstall below runs
                // `pixi install --frozen` (subcommand flag, correct), so the frozen-from-lock
                // intent is preserved without breaking build().
                var envBuilder = Appose.pixi()
                        .content(pixiToml)
                        .scheme("pixi.toml")
                        .name(family.envName(computeVariant))
                        .logDebug();

                // Builder.base() overrides name(), so pass the FULL
                // <base>/<envName> directory -- the same path
                // getEnvironmentPath(family) reports and the manifest was staged
                // into. Setting only one of those would move where we SAY the
                // environment is while Appose kept building elsewhere.
                Path configuredDir = configuredEnvDir(family);
                if (configuredDir != null) {
                    Files.createDirectories(configuredDir);
                    envBuilder.base(configuredDir.toFile());
                    logger.info("Building the {} environment at the configured location: {}", family, configuredDir);
                }
                long buildStart = System.nanoTime();
                h.environment = envBuilder.build();
                logger.info(
                        "cellAPpose {} env configured at {} ({} ms)",
                        family,
                        h.environment.base(),
                        ApposeSupport.elapsedMs(buildStart));

                // Install strictly from the bundled lock (--frozen) so cellpose +
                // torch are the exact pinned versions and pixi never re-resolves.
                runPixiInstall(family, statusCallback);

                report(statusCallback, "Starting Python service...");
                long workerStart = System.nanoTime();
                spawnWorker(h);

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
                        "=== cellAPpose {} environment ===\n  cellpose: {}\n  torch: {}\n  cuda: {}\n  mps: {}\n"
                                + "  path: {}\n  worker start: {} ms\n  setup: {} ms",
                        family,
                        verifyTask.outputs.get("cellpose_version"),
                        verifyTask.outputs.get("torch_version"),
                        verifyTask.outputs.get("cuda_available"),
                        verifyTask.outputs.get("mps_available"),
                        getEnvironmentPath(family),
                        ApposeSupport.elapsedMs(workerStart),
                        ApposeSupport.elapsedMs(setupStart));

                if (computeVariant == ComputeVariant.GPU) {
                    ApposeGpu.checkWorker(h.pythonService, "cellAPpose " + family, logger);
                }

                // Load the per-tile task script ONCE; reuse it for every tile.
                h.cachedTaskScript = loadScript(family.taskScript());

                h.initialized = true;
                // Built AND verified: the only point at which offering to remove
                // the environment this replaced is safe.
                offerPreviousEnvCleanup(family);
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
            String fullMsg = ApposeSupport.collectCauseMessages(e);
            h.initError = e.getMessage();
            h.initialized = false;
            logger.error("Failed to build cellAPpose {} env: {}", family, e.getMessage(), e);
            if (ApposeSupport.looksLikeWindowsFileLock(fullMsg)) {
                logger.warn(
                        "Pixi env install hit a Windows file lock; manual recovery required\n{}",
                        ApposeSupport.windowsFileLockAdvice(getEnvironmentPath(family)));
                report(statusCallback, "Pixi env install failed: Windows file lock. See recovery steps.");
                try {
                    qupath.fx.dialogs.Dialogs.showWarningNotification(
                            TITLE,
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
        long start = System.nanoTime();
        h.cachedModelKey = null;
        Task task = submit(family, family.initScript(), initInputs);
        if (task.error != null && !task.error.isEmpty()) {
            throw new IOException("cellAPpose " + family + " model load failed: " + task.error);
        }
        logger.info("Loaded the {} model in {} ms", family, ApposeSupport.elapsedMs(start));
        h.cachedModelKey = modelCacheKey;
        h.cachedModelInputs = initInputs;
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
            h.cachedModelInputs = null;
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
        h.cachedModelInputs = null;
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
            ApposeSupport.deleteDirectoryRecursively(envPath);
        }
    }

    /** Executes a callable with the extension classloader as TCCL. */
    public static <T> T withExtensionClassLoader(Callable<T> callable) throws Exception {
        return ApposeSupport.withExtensionClassLoader(callable);
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
     * <p>A stale worker (Appose "thread death" before any Python ran) is replaced,
     * the model reloaded, and the task retried, up to 3 attempts with a growing
     * delay; a failure after the script started is not retried. The rules are in
     * {@link ApposeTaskRunner}. A WSI submits this script thousands of times, so
     * a retry that reused the worker would leave a zombie inference holding GPU
     * memory beside its retry.
     */
    private Task submitScript(CellposeModelFamily family, String scriptName, String script, Map<String, Object> inputs)
            throws IOException {
        EnvHandle h = handle(family);
        return ApposeTaskRunner.run(
                "cellAPpose " + family,
                h.workerSource,
                scriptName,
                script,
                inputs,
                new ApposeTaskRunner.Options().maxAttempts(3).retryDelayMs(250));
    }

    /**
     * Creates the family's Python worker and queues its init script. Does NOT
     * start the subprocess; Appose does that on the first task.
     *
     * <p>Caller must hold the monitor and have the extension classloader set as
     * TCCL. The init script, run once, has two jobs: pre-import numpy first to
     * dodge the Windows stdin-reader deadlock, and define cp_utils' helpers
     * (get_torch_device, ...) as worker globals. The vendored cp3/cp4 init
     * scripts expect get_torch_device to already be in scope in appose mode, so
     * without this the model init raises NameError.
     */
    private void spawnWorker(EnvHandle h) throws IOException {
        h.pythonService = ApposeGpu.pinned(h.environment).python();
        wireConsole(h);
        String cpUtils = loadScript("cp_utils.py");
        h.pythonService.init("import numpy\n" + cpUtils);
    }

    /**
     * Replaces the family's worker: stops the stale subprocess, starts a fresh
     * one and reloads the model that was loaded before, so the next tile finds
     * {@code model} in scope. A no-op when {@code stale} is no longer the
     * current worker.
     */
    private void restartWorker(EnvHandle h, Service stale) throws IOException {
        if (h.environment == null) {
            throw new IOException("Cannot restart the cellAPpose " + h.family + " worker: environment not built");
        }
        if (stale != null && h.pythonService != stale) {
            logger.info("cellAPpose {} worker was already replaced; skipping redundant restart", h.family);
            return;
        }
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ApposeSupport.extensionClassLoader());
        try {
            long start = System.nanoTime();
            Service old = h.pythonService;
            h.pythonService = null;
            ApposeSupport.stopWorker(old, 3000, logger);
            spawnWorker(h);
            // Start the subprocess now: Appose starts it lazily on the first task, and an
            // unstarted worker reports isAlive() == false, which the worker source would
            // read as another dead worker and restart again from inside the reload below.
            h.pythonService.start();
            String modelKey = h.cachedModelKey;
            Map<String, Object> modelInputs = h.cachedModelInputs;
            h.cachedModelKey = null;
            if (modelKey != null && modelInputs != null) {
                // Single attempt: a freshly started worker cannot be the stale kind.
                Task task = ApposeTaskRunner.run(
                        "cellAPpose " + h.family,
                        h.workerSource,
                        h.family.initScript(),
                        loadScript(h.family.initScript()),
                        modelInputs,
                        new ApposeTaskRunner.Options().maxAttempts(1));
                if (task.error != null && !task.error.isEmpty()) {
                    throw new IOException("cellAPpose " + h.family + " model reload failed: " + task.error);
                }
                h.cachedModelKey = modelKey;
            }
            logger.info(
                    "cellAPpose {} worker restarted in {} ms{}",
                    h.family,
                    ApposeSupport.elapsedMs(start),
                    modelKey != null ? " and the model reloaded" : "");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to restart the cellAPpose " + h.family + " worker: " + e.getMessage(), e);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    private void wireConsole(EnvHandle h) {
        String prefix = "[" + h.family.envName(variant()) + "] ";
        h.pythonService.debug(msg -> {
            logger.info("[CellAPpose Python]{}{}", prefix, msg);
            PythonConsoleWindow.appendMessage(prefix + msg);
        });
    }

    private void closeService(EnvHandle h) {
        Service service = h.pythonService;
        if (service != null) {
            logger.info("Shutting down cellAPpose {} service...", h.family);
            ApposeSupport.stopWorker(service, 5000, logger);
            h.pythonService = null;
        }
    }

    /** Runs {@code pixi install --frozen} for the family's manifest to resolve all deps. */
    private void runPixiInstall(CellposeModelFamily family, Consumer<String> statusCallback) throws IOException {
        EnvHandle h = handle(family);
        Path envBase = Path.of(h.environment.base());
        Path manifestPath = envBase.resolve("pixi.toml");
        Path pixi = ApposeSupport.findPixiBinary();
        if (pixi == null) {
            throw new IOException("Cannot find pixi binary. The Appose environment may not "
                    + "have been set up correctly. Try rebuilding the environment.");
        }
        report(statusCallback, "Installing Python dependencies (several minutes on first run)...");
        long start = System.nanoTime();
        // --frozen: install the exact versions in the bundled lock; never re-resolve.
        ApposeSupport.runPixiCommand(
                pixi,
                envBase,
                manifestPath,
                line -> {
                    logger.info("[pixi] {}", line);
                    PythonConsoleWindow.appendMessage("[pixi] " + line);
                },
                "install",
                "--frozen");
        logger.info("pixi install --frozen finished in {} ms", ApposeSupport.elapsedMs(start));
    }

    private synchronized void registerShutdownHook() {
        if (shutdownHook != null) {
            return;
        }
        shutdownHook = new Thread(
                () -> {
                    logger.info("JVM shutdown hook: cleaning up cellAPpose Python subprocesses");
                    for (EnvHandle h : handles.values()) {
                        ApposeSupport.stopWorker(h.pythonService, 2000, logger);
                    }
                },
                "CellAPpose-ShutdownHook");
        shutdownHook.setDaemon(false);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    private void removeShutdownHook() {
        ApposeSupport.removeShutdownHook(shutdownHook);
        shutdownHook = null;
    }

    private String loadScript(String scriptFileName) throws IOException {
        return ApposeSupport.loadResource(SCRIPTS_BASE + scriptFileName);
    }

    private static void report(Consumer<String> callback, String message) {
        if (callback != null) {
            callback.accept(message);
        }
    }

    /**
     * The configured directory for one family's environment, or null for the
     * Appose default. Kept beside {@link #getEnvironmentPath} so the reported
     * and built locations cannot drift apart.
     */
    private static Path configuredEnvDir(CellposeModelFamily family) {
        String base = qupath.ext.cellappose.CellAPposePrefs.getEnvBaseDir();
        if (base == null || base.isBlank()) {
            return null;
        }
        return ApposeEnvLocation.resolve(base, family.envName(variant()));
    }

    /**
     * After a VERIFIED build, offer to remove the environment this one replaced.
     *
     * <p>Per family: the two environments are built independently and either can
     * be moved on its own, so a shared record would offer to delete the wrong
     * one. Off the init thread, and a no-op without a UI.
     */
    private void offerPreviousEnvCleanup(CellposeModelFamily family) {
        final Path current = getEnvironmentPath(family);
        final String previous = qupath.ext.cellappose.CellAPposePrefs.getEnvLastBuiltDir(family);
        qupath.ext.cellappose.CellAPposePrefs.setEnvLastBuiltDir(family, current.toString());
        if (previous == null || previous.isBlank() || previous.equals(current.toString())) {
            return;
        }
        try {
            javafx.application.Platform.runLater(() -> {
                try {
                    ApposeEnvLocation.promptCleanup(Path.of(previous), current);
                } catch (Exception e) {
                    logger.warn("Previous-environment cleanup prompt failed: {}", e.getMessage());
                }
            });
        } catch (IllegalStateException e) {
            logger.info("Previous {} environment at {} left in place (no UI available)", family, previous);
        }
    }
}
