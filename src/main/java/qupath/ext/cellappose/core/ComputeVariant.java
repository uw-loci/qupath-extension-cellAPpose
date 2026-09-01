package qupath.ext.cellappose.core;

/**
 * Which bundled Python environment to install for a Cellpose family: CPU-only
 * or CUDA.
 *
 * <p>A pixi lockfile pins exact package builds, and a CPU build of PyTorch
 * cannot use a GPU -- so this is not one environment that uses a GPU when
 * present. It is either a CPU build that installs everywhere and never
 * accelerates, or a CUDA build that accelerates and <em>cannot install at
 * all</em> without an NVIDIA GPU: pixi validates the {@code __cuda} virtual
 * package on EVERY install, i.e. every QuPath launch, so a GPU-pinned
 * environment refuses to start rather than running slowly. That is what blocked
 * an HPC deployment (qupath-extension-cell-analysis-tools#15).
 *
 * <p>Cellpose benefits from a GPU far more than most of what these extensions
 * do -- segmentation is the workload GPUs are for -- so unlike QP-CAT the GPU
 * variant here is worth having. CPU remains the default because it installs
 * anywhere, and because an environment that cannot be built is worse than one
 * that is slow.
 *
 * <p>Composes with {@link CellposeModelFamily}: the variant is part of the
 * environment NAME, so cp3-CPU, cp3-GPU, cp4-CPU and cp4-GPU can coexist.
 */
public enum ComputeVariant {

    /** CPU-only. Installs on any machine. The default. */
    CPU("CPU (works everywhere)"),

    /** CUDA. Requires an NVIDIA GPU -- the environment cannot install without one. */
    GPU("GPU / CUDA (requires an NVIDIA GPU)");

    private final String displayLabel;

    ComputeVariant(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    public String displayLabel() {
        return displayLabel;
    }

    @Override
    public String toString() {
        return displayLabel;
    }

    /** Parse a stored preference value, falling back to the safe default. */
    public static ComputeVariant fromId(String id) {
        if (id != null) {
            for (ComputeVariant v : values()) {
                if (v.name().equalsIgnoreCase(id.strip())) {
                    return v;
                }
            }
        }
        return CPU;
    }
}
