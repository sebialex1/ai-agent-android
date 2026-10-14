# On-device model catalogue verification

Engine: LiteRT-LM 0.18.0 (`com.google.ai.edge.litertlm:litertlm-android`), which loads `.litertlm` bundles only.
Checked on 2026-10-14 against the Hugging Face API (file list, byte sizes, pinned commit, gating, licence)
and an unauthenticated `HEAD` on the download URL (HTTP 200 required, the app downloads without a login).
All sizes are exact bytes reported by Hugging Face. "Tiers" = recommended phone RAM (GB), a recommendation only:
a phone below the tier gets a non-blocking warning and can continue.

## Added

| Model id | Source (repo, file) | Format | Size | Licence | Tiers | Engine compat | Status |
|---|---|---|---|---|---|---|---|
| local/smollm2-135m-instruct | https://huggingface.co/litert-community/SmolLM2-135M-Instruct `SmolLM2_135M_Instruct.litertlm` | .litertlm | 142,819,328 (143 MB) | Apache-2.0 | 1, 2 | LiteRT-LM, no tool calling | added |
| local/lfm2.5-230m | https://huggingface.co/litert-community/LFM2.5-230M `LFM2.5-230M_int4.litertlm` | .litertlm int4 | 176,756,720 (177 MB) | LFM Open License v1.0 | 2 | LiteRT-LM >= 0.15 (card: tested 0.16) | added |
| local/qwen3-0.6b | https://huggingface.co/litert-community/Qwen3-0.6B `Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm` | .litertlm int4 | 344,671,744 (345 MB) | Apache-2.0 | 2, 3 | needs 0.18.0 (card), thinking + tools | added |
| local/lfm2.5-350m | https://huggingface.co/litert-community/LFM2.5-350M `LFM2.5-350M_int4.litertlm` | .litertlm int4 | 247,747,696 (248 MB) | LFM Open License v1.0 | 2, 3 | LiteRT-LM | added (available) |
| local/smollm2-360m-instruct | https://huggingface.co/litert-community/SmolLM2-360M-Instruct `SmolLM2_360M_instruct.litertlm` | .litertlm | 373,719,040 (374 MB) | Apache-2.0 | 2, 3 | LiteRT-LM, no tool calling | added (available) |
| local/qwen3.5-0.8b | https://huggingface.co/litert-community/Qwen3.5-0.8B `Qwen3.5-0.8B_int8.litertlm` (text-only, not the VL file) | .litertlm int8 | 964,861,904 (965 MB) | Apache-2.0 | 4 | card: checked on 0.18.0 | added |
| local/qwen3-1.7b | https://huggingface.co/litert-community/Qwen3-1.7B `Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm` | .litertlm int4 | 977,184,032 (977 MB) | Apache-2.0 | 4, 5, 6, 7 | needs 0.18.0 (card) | added |
| local/smollm2-1.7b-instruct | https://huggingface.co/litert-community/SmolLM2-1.7B-Instruct `SmolLM2-1_7B-Instruct_dynamic_wi8_afp32.litertlm` | .litertlm int8 | 1,730,949,280 (1.7 GB) | Apache-2.0 | 4, 5, 6, 7 | LiteRT-LM, no tool calling | added |
| local/lfm2.5-2.6b | https://huggingface.co/litert-community/LFM2.5-2.6B `LFM2.5-2.6B_int4.litertlm` | .litertlm int4 | 1,668,151,680 (1.7 GB) | LFM Open License v1.0 | 7, 8 | CPU, GPU with >= 0.16 | added (available), thinking + tools |
| local/qwen3-4b | https://huggingface.co/litert-community/Qwen3-4B `qwen3_4b_mixed_int4.litertlm` | .litertlm int4 | 2,659,053,760 (2.7 GB) | Apache-2.0 | 8 | needs 0.18.0; context only 2048 in this file | added |

Existing entries kept: local/lfm2.5-1.2b-instruct (tier 6), local/gemma-4-e2b-it (tier 8), local/gemma-4-e4b-it (tier 12, unchanged). No tier above 9 GB was added.

## Skipped (not verifiable for this app)

| Requested | Finding | Status |
|---|---|---|
| local/gemma-3-1b | `litert-community/Gemma3-1B-IT` has a `.litertlm` (`gemma3-1b-it-int4.litertlm`, 584,417,280 B) but the repo is gated behind the Gemma licence click-through: unauthenticated download returns HTTP 401, and the app downloads with no login. Third-party mirrors have unclear licensing/provenance. | skipped |
| local/llama-3.2-1b | `litert-community/Llama-3.2-1B` (`llama3_2_1b_mixed_int4_gpu.litertlm`, 963,903,488 B, Llama 3.2 Community License) is gated: HTTP 401 without login. (`Llama-3.2-1B-Instruct` under litert-community does not exist.) | skipped |
| local/gemma-3-4b | `litert-community/Gemma3-4B-IT` only ships web `.task` files (MediaPipe, not loadable by LiteRT-LM) and is gated. Google points Android users to Gemma 3n / Gemma 4 instead (Gemma 4 E2B/E4B are already in the catalogue). | skipped |

Users with a Hugging Face login can still use Gemma 3 1B or Llama 3.2 1B through "Import" of a downloaded `.litertlm` file.
