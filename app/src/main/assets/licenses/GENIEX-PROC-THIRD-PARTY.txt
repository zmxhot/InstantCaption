# Third-Party Notices

`geniex-proc` incorporates or depends on the third-party components listed
below. Each component is distributed under its own license; the terms of
those licenses continue to govern use of the component. The BSD 3-Clause
license applied to first-party `geniex-proc` code does **not** supersede
them.

The authoritative license text for each component lives inside the vendored
tree at the path listed in the *License file* column. Please consult that
file for the full legal text.

---

## Vendored components

### stb (`third-party/stb/`)

- **Upstream:** https://github.com/nothings/stb
- **SPDX:** `MIT OR Unlicense` (dual-licensed; public-domain dedication available)
- **License file:** license notice embedded at the bottom of each header
  (`third-party/stb/stb_image.h`,
  `third-party/stb/stb_image_resize2.h`,
  `third-party/stb/stb_image_write.h`)
- **Usage:** image decoding, resizing, and encoding.

### xtensor (`third-party/xtensor/`)

- **Upstream:** https://github.com/xtensor-stack/xtensor
- **SPDX:** `BSD-3-Clause`
- **License file:** `third-party/xtensor/LICENSE`
- **Usage:** N-dimensional tensor container and expression system.

### xtl (`third-party/xtl/`)

- **Upstream:** https://github.com/xtensor-stack/xtl
- **SPDX:** `BSD-3-Clause`
- **License file:** `third-party/xtl/LICENSE`
- **Usage:** core template library used by xtensor.

### xtensor-io (`third-party/xtensor-io/`)

- **Upstream:** https://github.com/xtensor-stack/xtensor-io
- **SPDX:** `BSD-3-Clause`
- **License file:** `third-party/xtensor-io/LICENSE`
- **Usage:** NumPy `.npy` / `.npz` I/O for xtensor.

### xtensor-blas (`third-party/xtensor-blas/`)

- **Upstream:** https://github.com/xtensor-stack/xtensor-blas
- **SPDX:** `BSD-3-Clause`
- **License file:** `third-party/xtensor-blas/LICENSE`
- **Usage:** BLAS/LAPACK bindings for xtensor.

### xtensor-fftw (`third-party/xtensor-fftw/`)

- **Upstream:** https://github.com/xtensor-stack/xtensor-fftw
- **SPDX:** `BSD-3-Clause`
- **License file:** `third-party/xtensor-fftw/LICENSE`
- **Usage:** FFTW bindings for xtensor.

### tokenizers-cpp (`third-party/tokenizers-cpp/`, git submodule)

- **Upstream:** https://github.com/mlc-ai/tokenizers-cpp
- **SPDX:** `Apache-2.0`
- **License file:** `third-party/tokenizers-cpp/LICENSE`
- **Usage:** BPE / WordPiece / SentencePiece tokenization.
- **Note:** This submodule transitively vendors
  `third-party/tokenizers-cpp/sentencepiece/` (Apache-2.0) and
  `third-party/tokenizers-cpp/msgpack/` (Boost Software License 1.0).
  See the LICENSE files inside each subdirectory for their authoritative
  terms.

### nlohmann/json (`include/json.hpp`)

- **Upstream:** https://github.com/nlohmann/json
- **SPDX:** `MIT`
- **License file:** inline SPDX + copyright block at the top of
  `include/json.hpp`.
- **Usage:** JSON parsing and serialization.

---

## Derived work — llama.cpp

Portions of `src/geniex-sampling/` are derived from or adapted from the
[llama.cpp](https://github.com/ggml-org/llama.cpp) project, which is
distributed under the MIT license. Files that contain derived material
preserve the original llama.cpp copyright notice alongside the Qualcomm
BSD 3-Clause header.

---

If you believe a third-party component is used in this project but not
listed here, please open an issue so we can correct the attribution.
