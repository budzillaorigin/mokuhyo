// See tsumugi_whisper.h for why this file exists. It is the only place that includes whisper.h.

#include "tsumugi_whisper.h"
#include <whisper/whisper.h>

void *tsumugi_whisper_init(const char *model_path, int use_gpu) {
    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = use_gpu != 0;
    return whisper_init_from_file_with_params(model_path, params);
}

void tsumugi_whisper_free(void *context) {
    if (context) whisper_free((struct whisper_context *)context);
}

int tsumugi_whisper_full(void *context, const float *samples, int count, const char *language, int threads) {
    if (!context) return -1;
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.translate = false;
    params.no_timestamps = false;
    params.token_timestamps = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.n_threads = threads > 0 ? threads : 1;
    params.language = language;
    return whisper_full((struct whisper_context *)context, params, samples, count);
}

int tsumugi_whisper_n_segments(void *context) {
    return context ? whisper_full_n_segments((struct whisper_context *)context) : 0;
}

int64_t tsumugi_whisper_segment_t0(void *context, int index) {
    return whisper_full_get_segment_t0((struct whisper_context *)context, index);
}

int64_t tsumugi_whisper_segment_t1(void *context, int index) {
    return whisper_full_get_segment_t1((struct whisper_context *)context, index);
}

const char *tsumugi_whisper_segment_text(void *context, int index) {
    return whisper_full_get_segment_text((struct whisper_context *)context, index);
}
