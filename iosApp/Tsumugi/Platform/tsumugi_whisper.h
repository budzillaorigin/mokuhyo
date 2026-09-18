// Plain-C facade over whisper.cpp for Swift.
//
// llama.framework and whisper.framework each ship their own, different copy of ggml.h. Importing both Clang
// modules into one Swift module fails on device builds ("'GGML_PREC_…' from module 'llama' is not present in
// definition of 'enum ggml_prec' in module 'whisper'"). So Swift never imports the whisper module: it sees only
// these ggml-free declarations (via Tsumugi-Bridging-Header.h), and tsumugi_whisper.c is the only translation
// unit that includes whisper.h.

#ifndef TSUMUGI_WHISPER_H
#define TSUMUGI_WHISPER_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/// Loads a ggml whisper model; returns an opaque context or NULL.
void *tsumugi_whisper_init(const char *model_path, int use_gpu);

void tsumugi_whisper_free(void *context);

/// Greedy transcription of 16 kHz mono float samples. Returns 0 on success.
int tsumugi_whisper_full(void *context, const float *samples, int count, const char *language, int threads);

int tsumugi_whisper_n_segments(void *context);

/// Segment start/end in whisper's 10 ms units.
int64_t tsumugi_whisper_segment_t0(void *context, int index);
int64_t tsumugi_whisper_segment_t1(void *context, int index);

/// UTF-8 segment text owned by the context (valid until the next transcription or free).
const char *tsumugi_whisper_segment_text(void *context, int index);

#ifdef __cplusplus
}
#endif

#endif
