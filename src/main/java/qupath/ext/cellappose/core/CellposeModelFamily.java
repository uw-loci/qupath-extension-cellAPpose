package qupath.ext.cellappose.core;

/**
 * The two Cellpose model families this extension supports.
 *
 * <p>Cellpose 3 ({@code cellpose>=3,<4}) and Cellpose-SAM / Cellpose 4
 * ({@code cellpose>=4,<5}) require mutually exclusive PyPI constraints, so they
 * cannot share a single Python environment. Each family therefore carries its
 * own Appose environment name, bundled pixi manifest, init script, and per-tile
 * task script. The {@code ApposeCellposeService} keys its environment handles by
 * this enum.
 */
public enum CellposeModelFamily {

    /** Cellpose 3.x: explicit cell/nuclei channel pairing, classic built-in models. */
    CP3("cellappose-cp3", "cp3.toml", "cp3_init.py", "cp3.py", "Cellpose 3"),

    /** Cellpose-SAM / Cellpose 4.x: channel-flexible, no cell/nuclei split. */
    CP4("cellappose-cp4", "cp4.toml", "cp4_init.py", "cp4.py", "Cellpose-SAM (CP4)");

    private final String envName;
    private final String tomlResource;
    private final String initScript;
    private final String taskScript;
    private final String displayLabel;

    CellposeModelFamily(
            String envName, String tomlResource, String initScript, String taskScript, String displayLabel) {
        this.envName = envName;
        this.tomlResource = tomlResource;
        this.initScript = initScript;
        this.taskScript = taskScript;
        this.displayLabel = displayLabel;
    }

    /** Appose environment name, e.g. {@code cellappose-cp3}. */
    public String envName() {
        return envName;
    }

    /** Bundled pixi manifest resource filename, e.g. {@code cp3.toml}. */
    public String tomlResource() {
        return tomlResource;
    }

    /**
     * Bundled pixi lockfile resource filename, e.g. {@code cp3.lock}. Derived
     * from {@link #tomlResource()} (.toml -> .lock). Staged into the env dir as
     * pixi.lock so the env installs with --frozen.
     */
    public String lockResource() {
        return tomlResource.replaceAll("\\.toml$", ".lock");
    }

    /** Model-caching init script filename, e.g. {@code cp3_init.py}. */
    public String initScript() {
        return initScript;
    }

    /** Per-tile task script filename, e.g. {@code cp3.py}. */
    public String taskScript() {
        return taskScript;
    }

    /** Human-readable label for the UI. */
    public String displayLabel() {
        return displayLabel;
    }
}
