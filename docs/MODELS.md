# Models

Every weight file the app can download or bundle, with developer, country, license and source (CLAUDE.md rule 13).
Generated from `content/models/manifest.json` by `tools/models/models_doc.py`; edit the manifest, not this table.

Excluded by policy: all models from organizations based in the PRC and their derivatives (Qwen, DeepSeek, Yi,
GLM, InternLM, MiniCPM, …; rule 13); Gemma and Llama (non-permissive terms; rule 6, owner may opt in).
One owner exception exists: Chatterbox Multilingual, used only at build time (D-041; see below).

## Catalog

| id | Tier | Role | Model | Developer (country) | License | Size | SHA-256 | Source |
|---|---|---|---|---|---|---|---|---|
| `phi-4-mini-instruct-q4km` | A | default | Phi-4-mini-instruct 3.8B | Microsoft (United States) | MIT | 2.49 GB | `01999f17c39c…` | https://huggingface.co/microsoft/Phi-4-mini-instruct |
| `granite-3.3-2b-instruct-q4km` | A | alternate | IBM Granite 3.3 2B Instruct | IBM (United States) | Apache-2.0 | 1.55 GB | `555b91485955…` | https://huggingface.co/ibm-granite/granite-3.3-2b-instruct |
| `eurollm-9b-instruct-q4km` | B | default | EuroLLM-9B-Instruct | UTTER consortium (Unbabel, Instituto Superior Técnico, University of Edinburgh, et al.) (European Union) | Apache-2.0 | 5.58 GB | `785a3b288353…` | https://huggingface.co/utter-project/EuroLLM-9B-Instruct |
| `granite-3.3-8b-instruct-q4km` | B | alternate | IBM Granite 3.3 8B Instruct | IBM (United States) | Apache-2.0 | 4.94 GB | `758fb00abcec…` | https://huggingface.co/ibm-granite/granite-3.3-8b-instruct |
| `mistral-7b-instruct-v0.3-q4km` | B | alternate | Mistral-7B-Instruct v0.3 | Mistral AI (France) | Apache-2.0 | 4.37 GB | `1270d22c0fbb…` | https://huggingface.co/mistralai/Mistral-7B-Instruct-v0.3 |
| `mistral-nemo-instruct-2407-q4km` | C | default | Mistral-Nemo-Instruct-2407 12B | Mistral AI and NVIDIA (France / United States) | Apache-2.0 | 7.48 GB | `7c1a10d202d8…` | https://huggingface.co/mistralai/Mistral-Nemo-Instruct-2407 |
| `phi-4-q4km` | C | alternate | Phi-4 14B | Microsoft (United States) | MIT | 9.05 GB | `009aba717c09…` | https://huggingface.co/microsoft/phi-4 |
| `mistral-small-3.2-24b-instruct-2506-q4km` | D | default | Mistral-Small-3.2-24B-Instruct-2506 | Mistral AI (France) | Apache-2.0 | 14.33 GB | `80f5bda68f15…` | https://huggingface.co/mistralai/Mistral-Small-3.2-24B-Instruct-2506 |
| `whisper-small` | bundled | default | Whisper small (multilingual) | OpenAI (United States) | MIT | 0.49 GB | `1be3a9b20638…` | https://github.com/openai/whisper |
| `whisper-large-v3-turbo` | STT | alternate | Whisper large-v3-turbo (multilingual) | OpenAI (United States) | MIT | 1.62 GB | `1fc70f774d38…` | https://github.com/openai/whisper |

Weights are fetched from the GGUF/ggml conversions named in each entry's `provenance.conversion` (conversion only; the developer and license are the original model's). The app verifies every file's SHA-256 before use.

## Drafting models (tools/, not shipped)

The owner's Ollama server (BRIEF §7.1) runs the approved list in `tools/models/approved_models.json`: `mistral-small3.2:24b-instruct-2506-q8_0` (Mistral AI, France, Apache-2.0) drafts all content and is the reference grader; `gpt-oss:20b` (OpenAI, US, Apache-2.0) is the second-opinion checker.

## Build-time voice model (renders shipped clips; not in the app)

**Chatterbox Multilingual** (`chatterbox-multilingual`, `ResembleAI/chatterbox`, `t3_mtl23ls_v3` + `s3gen`): developer
Resemble AI (United States), MIT. **Contains a PRC-origin component:** its speech tokenizer is `speech_tokenizer_v2_25hz`
from CosyVoice2-0.5B (Alibaba / FunAudioLLM, built on SenseVoice), and its speaker encoder is CAMPPlus. Used under the
owner's named exception to rule 13 (**D-041**, 2026-10-04) — the only exception. It runs on the owner's RTX 5090 at build
time (`tools/voices/chatterbox_render.py`) to pre-render listening clips; nothing of it ships in the installers except the
rendered audio, which carries Resemble's PerTh watermark (`docs/PRIVACY.md`). Chinese text is segmented with ICU before
rendering, so Chatterbox's optional PRC-origin `pkuseg` segmenter is never installed. Reference voices are donated TTS
voices only (`voices/chatterbox_voices.json`).

## Rater calibration

Exact and within-one-step agreement between the app's OPI estimate (`opi_rate`) and instructor ratings of the same practice recordings (`tools/models/calibrate.py`). Rows marked *fixture* ran on the harness's own fixture samples, not instructor ratings: they test the harness and do not calibrate anything (the app says "uncalibrated").

| Language | Tier | Model | Samples | Exact | Within 1 step | Status |
|---|---|---|---|---|---|---|
| es | A | `phi4-mini:3.8b` | 3 | 66% | 100% | fixture |
| es | B | `hf.co/bartowski/EuroLLM-9B-Instruct-GGUF:Q4_K_M` | 3 | 33% | 100% | fixture |
| es | C | `mistral-nemo:12b` | 3 | 33% | 100% | fixture |
| es | D | `mistral-small3.2:24b-instruct-2506-q8_0` | 3 | 66% | 100% | fixture |
| ja | A | `phi4-mini:3.8b` | 2 | 0% | 100% | fixture |
| ja | B | `hf.co/bartowski/EuroLLM-9B-Instruct-GGUF:Q4_K_M` | 2 | 0% | 50% | fixture |
| ja | C | `mistral-nemo:12b` | 2 | 50% | 100% | fixture |
| ja | D | `mistral-small3.2:24b-instruct-2506-q8_0` | 2 | 100% | 100% | fixture |

## Speaking eval

`tools/models/eval_speaking.py`, last run 2026-10-01 (Ollama on the owner's RTX 5090). 60 prompts per language through the app's own prompts and validators; score = mean of json validity, target-language purity, register (judged by the reference model) and rating agreement (estimate within one ILR step of the intended level). Below 0.60 the model picker warns for that language. Latency is the 5090 server's, not a laptop's.

| model | lang | score | tier | json | purity | register | agreement | median latency |
|---|---|---|---|---|---|---|---|---|
| `eurollm-9b-instruct-q4km` | ar | 0.78 | B | 1.00 | 0.82 | 0.82 | 0.45 | 2.2 s |
| `eurollm-9b-instruct-q4km` | es | 0.76 | B | 1.00 | 0.88 | 0.72 | 0.45 | 28.4 s |
| `eurollm-9b-instruct-q4km` | ja | 0.79 | B | 1.00 | 0.88 | 0.82 | 0.45 | 23.2 s |

## Interviewer coherence

`tools/models/eval_speaking.py --coherence`, last run 2026-10-05 (Ollama on the owner's RTX 5090). Whole practice interviews through the app's own interview session; the reference model plays a learner at ILR 1 to 2+ answering each actual question, then judges each interviewer turn (topic changes between questions are allowed). Targets (BRIEF_PHASE8 N-00b): ≥ 90 % sensible turns and < 10 % scripted fallbacks for Tier B and above.

| model | lang | turns | sensible | model turns sensible | scripted fallbacks | meets |
|---|---|---|---|---|---|---|
| `hf.co/bartowski/EuroLLM-9B-Instruct-GGUF:Q4_K_M` | ar | 22 | 91% | 90% | 5% | yes |
| `hf.co/bartowski/EuroLLM-9B-Instruct-GGUF:Q4_K_M` | de | 22 | 68% | 65% | 9% | no |
| `hf.co/bartowski/EuroLLM-9B-Instruct-GGUF:Q4_K_M` | es | 22 | 95% | 95% | 5% | yes |
| `hf.co/bartowski/EuroLLM-9B-Instruct-GGUF:Q4_K_M` | fa | 22 | 45% | 35% | 23% | no |
| `hf.co/bartowski/EuroLLM-9B-Instruct-GGUF:Q4_K_M` | fr | 22 | 86% | 85% | 9% | no |
| `hf.co/bartowski/EuroLLM-9B-Instruct-GGUF:Q4_K_M` | id | 22 | 59% | 55% | 9% | no |
| `hf.co/bartowski/EuroLLM-9B-Instruct-GGUF:Q4_K_M` | ja | 22 | 73% | 81% | 27% | no |
| `hf.co/bartowski/EuroLLM-9B-Instruct-GGUF:Q4_K_M` | ko | 22 | 82% | 79% | 14% | no |
| `hf.co/bartowski/EuroLLM-9B-Instruct-GGUF:Q4_K_M` | pt-BR | 22 | 86% | 86% | 5% | no |
| `hf.co/bartowski/EuroLLM-9B-Instruct-GGUF:Q4_K_M` | ru | 22 | 68% | 65% | 9% | no |
| `hf.co/bartowski/EuroLLM-9B-Instruct-GGUF:Q4_K_M` | zh-Hans | 22 | 82% | 79% | 14% | no |
| `mistral-nemo:12b` | ar | 22 | 86% | 86% | 0% | no |
| `mistral-nemo:12b` | de | 22 | 86% | 86% | 0% | no |
| `mistral-nemo:12b` | es | 22 | 82% | 82% | 0% | no |
| `mistral-nemo:12b` | fa | 22 | 86% | 86% | 0% | no |
| `mistral-nemo:12b` | fr | 22 | 86% | 86% | 0% | no |
| `mistral-nemo:12b` | id | 22 | 95% | 95% | 0% | yes |
| `mistral-nemo:12b` | ja | 22 | 86% | 86% | 0% | no |
| `mistral-nemo:12b` | ko | 22 | 82% | 81% | 5% | no |
| `mistral-nemo:12b` | pt-BR | 22 | 91% | 91% | 0% | yes |
| `mistral-nemo:12b` | ru | 22 | 86% | 86% | 0% | no |
| `mistral-nemo:12b` | zh-Hans | 22 | 86% | 86% | 0% | no |
| `mistral-small3.2:24b-instruct-2506-q8_0` | ar | 22 | 91% | 91% | 0% | yes |
| `mistral-small3.2:24b-instruct-2506-q8_0` | de | 22 | 86% | 85% | 9% | no |
| `mistral-small3.2:24b-instruct-2506-q8_0` | es | 22 | 77% | 77% | 0% | no |
| `mistral-small3.2:24b-instruct-2506-q8_0` | fa | 22 | 82% | 82% | 0% | no |
| `mistral-small3.2:24b-instruct-2506-q8_0` | fr | 22 | 95% | 95% | 0% | yes |
| `mistral-small3.2:24b-instruct-2506-q8_0` | id | 22 | 73% | 73% | 0% | no |
| `mistral-small3.2:24b-instruct-2506-q8_0` | ja | 22 | 82% | 82% | 0% | no |
| `mistral-small3.2:24b-instruct-2506-q8_0` | ko | 22 | 95% | 95% | 0% | yes |
| `mistral-small3.2:24b-instruct-2506-q8_0` | pt-BR | 22 | 91% | 91% | 0% | yes |
| `mistral-small3.2:24b-instruct-2506-q8_0` | ru | 22 | 100% | 100% | 0% | yes |
| `mistral-small3.2:24b-instruct-2506-q8_0` | zh-Hans | 22 | 86% | 86% | 0% | no |
