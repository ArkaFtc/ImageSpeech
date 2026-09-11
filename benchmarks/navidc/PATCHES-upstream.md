# NaviDC-OCR GGUF — Required llama.cpp Patches

**Status: confirmed working.** `llama-server` loads and serves NaviDC-OCR-GGUF
successfully with the patches below applied (verified via `./build/bin/llama-server -m
NaviDC-OCR-Q4_K_M.gguf --mmproj NaviDC-OCR-mmproj-q8_0.gguf`).

## Background

[NaviDC-OCR](https://huggingface.co/StarDoc-AI/NaviDC-OCR) is a ~1.2B document-parsing
VLM built from a Qwen2.5-VL-style vision encoder, a Qwen3-flavored text backbone, and a
custom MLP aligner (`model_type: "qwen2_5_vl"`, custom code via `auto_map`). Converting
it to GGUF with the standard `qwen2vl` architecture mapping produces a file that loads
weights correctly but crashes on load with:

```
error loading model: check_tensor_dims: tensor 'blk.0.attn_q.weight' has wrong shape;
expected 1024, 1024, got 1024, 2048, 1, 1
```

### Root cause

The model's `config.json` sets `head_dim: 128` explicitly, both at the top level and
inside `text_config`. This is **not** equal to `hidden_size / num_attention_heads`
(1024 / 16 = 64), which is the assumption stock Qwen2/2.5-VL checkpoints have always
satisfied — so llama.cpp's `qwen2vl` architecture code never needed to distinguish the
two, and hardcodes `n_embd` (1024) as the width for **two** tensors that should instead
scale with `n_head * head_dim` (16 × 128 = 2048): the attention-Q projection (`attn_q`)
and the attention-output projection's input side (`attn_output`).

Confirmed via `gguf_dump.py`: the on-disk GGUF metadata (`qwen2vl.attention.key_length
= 128`, `qwen2vl.attention.value_length = 128`) and the on-disk tensor shapes
(`blk.N.attn_q.weight` = `1024, 2048`) were **already correct** from conversion. The bug
is entirely in the **C++ loader**, not the Python conversion script or the weights
themselves. No reconversion of existing GGUF files is required — only a rebuild of
llama.cpp with the patches below.

The model also has per-head QK-norm (RMSNorm on Q/K before RoPE, same pattern as
Qwen3), which the stock `qwen2vl` architecture doesn't declare tensors for at all.

---

## Patch 1 — `gguf-py/gguf/constants.py`

Add QK-norm tensors to the `QWEN2VL` architecture's tensor list (around the existing
`MODEL_ARCH.QWEN2VL:` block):

```python
MODEL_ARCH.QWEN2VL: [
    MODEL_TENSOR.TOKEN_EMBD,
    MODEL_TENSOR.OUTPUT_NORM,
    MODEL_TENSOR.OUTPUT,
    MODEL_TENSOR.ATTN_NORM,
    MODEL_TENSOR.ATTN_Q,
    MODEL_TENSOR.ATTN_K,
    MODEL_TENSOR.ATTN_V,
    MODEL_TENSOR.ATTN_OUT,
    MODEL_TENSOR.ATTN_Q_NORM,   # added — required for NaviDC-OCR's per-head QK-norm
    MODEL_TENSOR.ATTN_K_NORM,   # added
    MODEL_TENSOR.FFN_NORM,
    MODEL_TENSOR.FFN_GATE,
    MODEL_TENSOR.FFN_DOWN,
    MODEL_TENSOR.FFN_UP,
],
```

**⚠️ Unverified — check before relying on this:** confirm `gguf-py/gguf/tensor_mapping.py`
has an entry mapping the HF state-dict key for this arch's q_norm/k_norm weights (likely
something like `model.language_model.layers.N.self_attn.q_norm.weight` /
`k_norm.weight`) to `MODEL_TENSOR.ATTN_Q_NORM` / `ATTN_K_NORM`. Without that mapping
entry, `convert_hf_to_gguf.py` will silently skip those weights during conversion
(no error) rather than writing them.

---

## Patch 2 — `src/models/qwen2vl.cpp`

Four separate changes in this one file.

### (a) Declare the QK-norm tensors

Right after `layer.wo = create_tensor(...)`:

```cpp
// optional QK-norm (present on custom variants such as NaviDC-OCR; absent on stock Qwen2.5-VL)
layer.attn_q_norm = create_tensor(tn(LLM_TENSOR_ATTN_Q_NORM, "weight", i), {n_embd_head_k}, TENSOR_NOT_REQUIRED);
layer.attn_k_norm = create_tensor(tn(LLM_TENSOR_ATTN_K_NORM, "weight", i), {n_embd_head_k}, TENSOR_NOT_REQUIRED);
```

### (b) Fix the Q tensor shape bug (the actual crash fix)

Find this line (creates the Q/K/V weight tensors):

```cpp
// before — hardcodes Q width as n_embd, wrong when head_dim != hidden_size/num_heads
create_tensor_qkv(layer, i, n_embd, n_embd, n_embd_gqa, n_embd_gqa, 0);
```

Change to:

```cpp
// after — Q width correctly derived from n_head * head_dim
create_tensor_qkv(layer, i, n_embd, n_head * hparams.n_embd_head_k(), n_embd_gqa, n_embd_gqa, 0);
```

`create_tensor_qkv`'s signature (from `src/llama-model.h` / `src/llama-model.cpp`) is:

```cpp
void create_tensor_qkv(llama_layer & layer, int bid,
        int64_t n_embd_, int64_t n_embd_q_, int64_t n_embd_k_, int64_t n_embd_v_,
        int flags);
```

`n_embd_gqa` (used for K/V) was verified to already resolve correctly via
`hparams.n_embd_v_gqa(il)` — no change needed there, only Q was broken.

### (c) Apply QK-norm in the graph

Right after `build_qkv(...)` produces `Qcur`/`Kcur`/`Vcur`, and **before** the
`ggml_rope_multi(...)` call:

```cpp
if (model.layers[il].attn_q_norm) {
    Qcur = build_norm(Qcur, model.layers[il].attn_q_norm, NULL, LLM_NORM_RMS, il);
    cb(Qcur, "Qcur_normed", il);
}
if (model.layers[il].attn_k_norm) {
    Kcur = build_norm(Kcur, model.layers[il].attn_k_norm, NULL, LLM_NORM_RMS, il);
    cb(Kcur, "Kcur_normed", il);
}
```

### (d) Fix the attention-output tensor shape bug

Same root cause as (b), one line down. `wo` projects the concatenated multi-head
attention output (width = `n_head * head_dim` = 2048) back down to `n_embd` (1024) — but
its input dimension was also hardcoded to `n_embd`:

```cpp
// before:
layer.wo = create_tensor(tn(LLM_TENSOR_ATTN_OUT, "weight", i), {n_embd, n_embd}, 0);
// after:
layer.wo = create_tensor(tn(LLM_TENSOR_ATTN_OUT, "weight", i), {n_head * hparams.n_embd_head_k(), n_embd}, 0);
```

This one only surfaces *after* fixing (b) — the loader checks tensors in order and stops
at the first mismatch, so `attn_output`'s error doesn't appear until `attn_q`'s is fixed.
If you're patching from scratch, apply both (b) and (d) together to avoid two separate
rebuild/test cycles.

The other three `{n_embd}`-shaped tensors in this file (`output_norm`, `attn_norm`,
`ffn_norm`) are RMSNorm weights over the hidden state itself and are correctly sized as
`n_embd` regardless of head_dim — do not change those.

All C++ additions/changes here use `TENSOR_NOT_REQUIRED` / `if (...)` guards where
applicable, so this stays **backward-compatible** with stock Qwen2.5-VL GGUFs that don't
have QK-norm tensors — one llama.cpp build works for both.

---

## Rebuild (no reconversion needed)

```bash
cmake --build build --config Release -j --target llama-server llama-cli llama-mtmd-cli
```

The existing `.gguf` file's weights and metadata were correct from the original
conversion — only the C++ loader's shape expectations were wrong. Point `llama-server`
at the same GGUF file you already have; no need to re-run `convert_hf_to_gguf.py` or
`llama-quantize`.

```bash
./build/bin/llama-server -m NaviDC-OCR-Q4_K_M.gguf --mmproj NaviDC-OCR-mmproj-q8_0.gguf
```

**⚠️ On WSL:** make sure you're running the binary you just built
(`./build/bin/llama-server`), not a `llama.exe` that resolves onto your Windows PATH
(`which llama.exe` will show something under `/mnt/c/...` if so). WSL will happily exec a
Windows binary from PATH if the Linux one isn't referenced explicitly, which will silently
run your old, unpatched build and reproduce the exact same crash.

---

## Diagnostic trail (how this was isolated)

For reference / reproducibility if similar issues come up on other custom-architecture
conversions:

1. `gguf_dump.py ... | grep -i length` confirmed `qwen2vl.attention.key_length` and
   `value_length` were already correctly written as `128` — ruled out a Python
   conversion/metadata bug.
2. `grep -n "n_embd_head_k" src/llama-model.cpp` and `src/llama-hparams.cpp` confirmed
   the hparams struct itself (`n_embd_head_k_full`, loaded via
   `ml.get_key(LLM_KV_ATTENTION_KEY_LENGTH, ...)`) was also correct, and the
   `n_embd_head_k(il)` accessor just returns it — ruled out a general hparams-loading bug.
3. `grep -n "ATTN_Q\|create_tensor" src/models/qwen2vl.cpp` located the actual bug: the
   `create_tensor_qkv(...)` call for this architecture hardcodes Q's output width as
   `n_embd` instead of deriving it from head count × head_dim.
4. Confirmed `n_embd_gqa` (used for K/V in the same call) already resolves correctly via
   `hparams.n_embd_v_gqa(il)`, so only the Q argument needed fixing.
5. After fixing (b) and rebuilding, the loader advanced past `attn_q` and failed on
   `blk.0.attn_output.weight` with the same shape-mismatch pattern (`expected 1024,1024,
   got 2048,1024`) — same root cause, different tensor, since `wo`'s input side has the
   identical `n_head * head_dim` vs `n_embd` divergence.
6. `grep -n "n_embd, n_embd\|{n_embd}" src/models/qwen2vl.cpp` swept the rest of the file
   for other tensors still using the unscaled `n_embd`, confirming `wo` was the only
   remaining offender (the three norm-weight tensors are correctly `{n_embd}` regardless
   of head_dim, and were left unchanged).

**Confirmed:** with patches (a)–(d) applied and a rebuild, `llama-server` loads
NaviDC-OCR-Q4_K_M.gguf and its mmproj file successfully with no tensor-shape errors.
**Still worth doing before calling this fully done:** run an actual OCR inference pass on
a real image and confirm the output text is coherent, not just that the model loads —
loading cleanly confirms shapes match, not that every tensor's *semantic* meaning lines
up correctly (e.g. the M-RoPE `dimension_sections` `[16, 24, 24, 0]` interacting with the
now-correctly-sized 2048-wide Q hasn't been separately checked for numerical correctness,
only shape correctness).