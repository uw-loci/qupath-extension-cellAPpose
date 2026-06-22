###
# QPSC cellAPpose environment verification script.
#
# This file is NOT vendored from imglib2-cellpose; it is original to the
# qupath-extension-cellAPpose project. It imports cellpose + torch and reports
# their versions plus GPU availability so the Java service can confirm the env
# resolved correctly after a build. ASCII-only (Windows cp1252).
###
import sys

# numpy must be imported before anything that imports it, to avoid the Windows
# Appose stdin-reader deadlock (apposed/appose#23, numpy/numpy#24290).
import numpy  # noqa: F401

cellpose_version = "unknown"
torch_version = "unknown"
cuda_available = False
mps_available = False
import_error = ""

try:
    import cellpose

    # Cellpose 4 (Cellpose-SAM) does not expose a top-level __version__, so fall
    # back to the installed package metadata.
    cellpose_version = getattr(cellpose, "__version__", None)
    if not cellpose_version:
        try:
            import importlib.metadata as _md

            cellpose_version = _md.version("cellpose")
        except Exception:  # noqa: BLE001
            cellpose_version = "unknown"
except Exception as exc:  # noqa: BLE001
    import_error = "cellpose import failed: " + str(exc)

try:
    import torch

    torch_version = getattr(torch, "__version__", "unknown")
    try:
        cuda_available = bool(torch.cuda.is_available())
    except Exception:  # noqa: BLE001
        cuda_available = False
    try:
        mps_available = bool(torch.backends.mps.is_available())
    except Exception:  # noqa: BLE001
        mps_available = False
except Exception as exc:  # noqa: BLE001
    if import_error:
        import_error = import_error + "; torch import failed: " + str(exc)
    else:
        import_error = "torch import failed: " + str(exc)

print("cellAPpose verify_env: cellpose=" + str(cellpose_version)
      + " torch=" + str(torch_version)
      + " cuda=" + str(cuda_available)
      + " mps=" + str(mps_available), file=sys.stderr)

task.outputs["cellpose_version"] = cellpose_version
task.outputs["torch_version"] = torch_version
task.outputs["cuda_available"] = cuda_available
task.outputs["mps_available"] = mps_available
task.outputs["import_error"] = import_error
