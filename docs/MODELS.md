# Models

Every weight file the app can download or bundle, with developer, country, license and source (CLAUDE.md rule 13).
Generated from `content/models/manifest.json` by `tools/models/models_doc.py`; edit the manifest, not this table.

Excluded by policy: all models from organizations based in the PRC and their derivatives (Qwen, DeepSeek, Yi,
GLM, InternLM, MiniCPM, …; rule 13); Gemma and Llama (non-permissive terms; rule 6, owner may opt in).

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

## Speaking eval

`tools/models/eval_speaking.py`, last run 2026-10-01 (Ollama on the owner's RTX 5090). 60 prompts per language through the app's own prompts and validators; score = mean of json validity, target-language purity, register (judged by the reference model) and rating agreement (estimate within one ILR step of the intended level). Below 0.60 the model picker warns for that language. Latency is the 5090 server's, not a laptop's.

| model | lang | score | tier | json | purity | register | agreement | median latency |
|---|---|---|---|---|---|---|---|---|
| `eurollm-9b-instruct-q4km` | ar | 0.78 | B | 1.00 | 0.82 | 0.82 | 0.45 | 2.2 s |
| `eurollm-9b-instruct-q4km` | es | 0.76 | B | 1.00 | 0.88 | 0.72 | 0.45 | 28.4 s |
| `eurollm-9b-instruct-q4km` | ja | 0.79 | B | 1.00 | 0.88 | 0.82 | 0.45 | 23.2 s |
