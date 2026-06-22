# qupath-extension-cellAPpose

Whole-slide Cellpose cell- and nuclei-segmentation for QuPath. CellAPpose runs
**Cellpose 3** and **Cellpose-SAM (Cellpose 4)** across regions far larger than a
single Cellpose call: QuPath owns the tiling, downsampling, and tile-seam object
merging, while the heavy Python segmentation runs in an Appose/Pixi-managed
environment. The Cellpose Python is vendored from the Fiji/Appose
[`imglib2-cellpose`](https://github.com/Image-Analysis-Hub/imglib2-cellpose)
project; the QuPath-side tiling and seam-merge architecture is inspired by
[BIOP's `qupath-extension-cellpose`](https://github.com/BIOP/qupath-extension-cellpose).

> # ⚠️ TEST REPOSITORY -- NOT FOR GENERAL USE
>
> **This is an experimental testbed, published only so a handful of collaborators
> can try it. It is NOT intended for general use, NOT supported, and does NOT
> have full functionality.** Expect missing features, rough edges, and breaking
> changes without notice. Do not depend on it for any real work.
>
> - **Incomplete.** Whole functional areas are unfinished or unvalidated. Behavior,
>   interfaces, parameter names, and output formats may change or break between
>   revisions with no migration path.
> - **Research/experimental only. Not for clinical decision-making** or any
>   production pathology workflow.
> - **Not catalog-distributed and no releases.** There is no QuPath catalog entry
>   and no GitHub release; you build and install the JAR by hand.
> - **Linux/WSL only, in practice.** Built and smoke-tested on Linux/WSL. macOS and
>   Windows are **unverified** and may not work at all.
> - **No support and no warranty.** Issues and pull requests may go unanswered.

## Install

1. Build (or download) `qupath-extension-cellAPpose-*-all.jar`. The local build
   produces it at `build/libs/qupath-extension-cellAPpose-0.1.0-SNAPSHOT-all.jar`
   (there is no GitHub release in v1):

   ```
   ./gradlew shadowJar
   ```

2. Drop the `-all.jar` into `~/QuPath/v0.7/extensions/` (Linux) or the equivalent
   `extensions/` directory on Windows/macOS.
3. Restart QuPath. New extensions do not load on the fly -- a restart is required.
4. The extension appears under `Extensions > CellAPpose`.

**Migration:** not applicable -- new extension. No prior preferences, project
metadata, or UI surfaces are being replaced.

## First-run environment build

The first time you run a given Cellpose family, CellAPpose builds a Python
(Appose / Pixi) environment under `~/.local/share/appose/`. This is a
**multi-GB, multi-minute download** (PyTorch plus Cellpose).

There are **two environments**, because the two Cellpose majors cannot coexist in
one Python env (`cellpose>=3,<4` for CP3 vs `cellpose>=4,<5` for CP4):

- `cellappose-cp3` -- Cellpose 3
- `cellappose-cp4` -- Cellpose-SAM (Cellpose 4)

Each is built **lazily**, only the first time you pick that family, so you do not
pay for both unless you use both. A user who runs both families pays roughly **2x
disk footprint** (PyTorch is the bulk and is duplicated) -- an accepted testbed
tradeoff.

- **Build it ahead of time** via `Extensions > CellAPpose > Manage
  Environments...`. The Run dialog will otherwise build the environment silently
  on the first Run, which makes that first run look like it is hanging.
- **GPU:** CUDA PyTorch is used on Linux/Windows when an NVIDIA GPU is present;
  Apple Silicon uses MPS; otherwise the run **falls back to CPU** (slower, not an
  error). The "Use GPU if available" option controls this.
- The Run dialog shows a live banner -- blue `[i] ... environment is READY` when
  the env exists, amber `[!] Environment ... is not built` when the first Run
  will build it.

## Quick start

1. **First time with a given family:** open `Extensions > CellAPpose > Manage
   Environments...` and build the `cellappose-cp3` or `cellappose-cp4`
   environment. Wait for it to report ready (several GB, several minutes). You can
   skip this and let the first Run build it, but then that run appears to hang
   while the download proceeds.
2. Open an image in QuPath.
3. Draw (or select) an annotation around the region you want segmented -- or plan
   to run on the whole image.
4. `Extensions > CellAPpose > Run Cellpose Detection...`.
5. Pick the **Model family** (CP3 / CP4), set the **Diameter** and channels,
   accept the default tiling, choose **Run on: Selected annotations** or **Whole
   image**, and click **Run**.
6. Detected objects appear in the QuPath hierarchy, constrained to the parent
   region, with tile seams already merged. A "Cellpose detection complete"
   notification fires when it finishes.

Watch `Extensions > CellAPpose > Python Console` for Cellpose/Appose output and
tracebacks -- Appose reserves stdout for its protocol, so Python diagnostics go
there, not to the QuPath log.

The whole pipeline is also scriptable from the QuPath Groovy editor via
`Cellpose2D.builder(...)` -- see the
[user guide](documentation/cellappose.md#6-scripting-the-cellpose2dbuilder-api).

See the **[user guide](documentation/cellappose.md)** for parameters, tiling
guidance, output types, scripting, environment management, and troubleshooting.

## Sources & attribution

CellAPpose stands on external work, named here per this project's
cite-sources-in-user-facing-docs policy. The full vendored-file list and the exact
local edits are in [`docs/APPOSE_CELLPOSE_PORTING.md`](docs/APPOSE_CELLPOSE_PORTING.md);
the shipped notice is [`src/main/resources/qupath/ext/cellappose/THIRD_PARTY.md`](src/main/resources/qupath/ext/cellappose/THIRD_PARTY.md).

- **Python segmentation scripts -- vendored** from
  [`Image-Analysis-Hub/imglib2-cellpose`](https://github.com/Image-Analysis-Hub/imglib2-cellpose)
  (**BSD-3-Clause**). The five vendored files (`cp3.py`, `cp3_init.py`, `cp4.py`,
  `cp4_init.py`, `cp_utils.py`) keep their upstream copyright headers verbatim;
  local edits are a small documented set of default-shim lines plus a symmetry
  output mirror.
- **Tiling + tile-seam merge architecture -- inspired by**
  [BIOP's `qupath-extension-cellpose`](https://github.com/BIOP/qupath-extension-cellpose)
  (**Apache-2.0**). Pattern only; no source was copied.
- **Method -- Cellpose.** Stringer, C., Wang, T., Michaelos, M., Pachitariu, M.
  (2021). *Cellpose: a generalist algorithm for cellular segmentation.* Nature
  Methods 18, 100-106. Cellpose-SAM / Cellpose 4 is the SAM-based generalist
  Cellpose model.
- **Host -- QuPath (GPL).** This extension links QuPath, which is GPL-licensed.

## License

**No license is granted.** This testbed repository intentionally ships **without a
license file**, so no rights to use, copy, modify, or redistribute the code are
granted to anyone. All rights reserved. It is published solely for a small group
of collaborators to look at and try; treat it as "all rights reserved" unless and
until a license is added.

Note for context (not a grant of rights): this extension links QuPath (GPLv3) and
reuses a helper copied from the GPLv3 `qupath-extension-ppm`, so any future public
release would need a GPL-compatible license. The vendored Python scripts under
`src/main/resources/qupath/ext/cellappose/scripts/` carry their own upstream
**BSD-3-Clause** license and copyright notices, preserved and attributed in
`THIRD_PARTY.md`; nothing here overrides those upstream terms.
