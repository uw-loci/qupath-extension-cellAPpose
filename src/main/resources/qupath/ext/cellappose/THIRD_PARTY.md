# Third-party notices -- qupath-extension-cellAPpose

This extension bundles and builds on third-party work. The notices below ship
with the JAR.

## Vendored Python scripts (BSD-3-Clause)

The Cellpose Python scripts under `qupath/ext/cellappose/scripts/`:

- `cp3.py`, `cp3_init.py`
- `cp4.py`, `cp4_init.py`
- `cp_utils.py`

are vendored from the **imglib2-cellpose** project, copyright (c) 2026 the
Appose developers, licensed **BSD-3-Clause**:

- Source repository: <https://github.com/Image-Analysis-Hub/imglib2-cellpose>
- Vendored from path: `src/main/resources/` (cp3.py, cp3_init.py, cp4.py,
  cp4_init.py, cp_utils.py) on the `main` branch.

The upstream BSD-3-Clause copyright headers are preserved verbatim in each `.py`
file. The only local edits are a small, documented set of default-shim lines and
a symmetry output mirror -- see `docs/APPOSE_CELLPOSE_PORTING.md` (section 3,
"Changes we made to the vendored scripts") for the complete change table.
`verify_env.py` is original to this project, not vendored.

The full upstream license is reproduced verbatim below, exactly as it appears in
`LICENSE.txt` of the imglib2-cellpose repository (retrieved from the `main`
branch). Note: the copyright line ("Appose developpers") and the clause-3
organization name ("the My Company") are the upstream project's own wording,
reproduced unchanged -- they are NOT unfilled placeholders left by this project.
We reproduce the upstream text as-is per the requirement to preserve third-party
license notices verbatim.

```
Copyright (c) 2026, Appose developpers

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the My Company nor the names of its contributors
   may be used to endorse or promote products derived from this software without
   specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Architecture inspiration (Apache-2.0, not copied)

The whole-slide tiling and tile-seam object-merge architecture in the Java
`core/` package (Cellpose2D, TileMerger, LabelToObjects, CellposeBuilder) is
**inspired by** BIOP's `qupath-extension-cellpose`
(<https://github.com/BIOP/qupath-extension-cellpose>, Apache-2.0). No source was
copied; only the high-level pattern (QuPath-side tiling, per-tile Cellpose,
geometry merge across seams) informed the design.

## Underlying method (Cellpose)

- Cellpose: Stringer, C., Wang, T., Michaelos, M., Pachitariu, M. (2021).
  Cellpose: a generalist algorithm for cellular segmentation. Nature Methods.
- Cellpose-SAM / Cellpose 4: the SAM-based generalist Cellpose model.

## Host (GPL)

This extension links QuPath, which is GPL-licensed. The extension's own license
is settled by the project license review before release.
