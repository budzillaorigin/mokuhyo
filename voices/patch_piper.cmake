# Patches piper 2023.11.14-2 src/cpp/main.cpp for Mokuhyo's --json-input use (cmake -DFILE=<main.cpp> -P ...).
# Plain string replacement so it runs the same on every OS (no patch/git needed); fails loudly if an anchor moved.
#
# 1. Per-line "length_scale" (speech speed) in --json-input mode, restored after each line like "speaker_id", so
#    one long-running process per voice serves every speed.
# 2. "output_file" is read as UTF-8 (filesystem::u8path) and opened as a path (wide on Windows): a plain
#    std::string path is decoded in the ANSI code page there, which breaks temp dirs under non-ASCII user names.
# 3. The WAV file is closed before its path is printed: upstream prints while the ofstream still buffers the tail,
#    so a reader that acts on the stdout line can see a truncated file.
# 4. (piper.cpp) MOKUHYO_TTS_THREADS caps ONNX Runtime's threads (see the end of this file).
# Always applied to the pristine upstream file (kept as main.cpp.orig), so re-running after this script changes
# gives the same result as a fresh checkout.

if(NOT EXISTS "${FILE}.orig")
  file(COPY_FILE "${FILE}" "${FILE}.orig")
endif()
file(READ "${FILE}.orig" src)

function(replace_once from to)
  string(FIND "${src}" "${from}" at)
  if(at EQUAL -1)
    message(FATAL_ERROR "patch_piper: anchor not found in ${FILE}:\n${from}")
  endif()
  string(REPLACE "${from}" "${to}" out "${src}")
  set(src "${out}" PARENT_SCOPE)
endfunction()

replace_once(
  "    auto speakerId = voice.synthesisConfig.speakerId;\n"
  "    auto speakerId = voice.synthesisConfig.speakerId;\n    auto lengthScale = voice.synthesisConfig.lengthScale; // MOKUHYO_PATCH\n")

replace_once(
  "            filesystem::path(lineRoot[\"output_file\"].get<std::string>());"
  "            filesystem::u8path(lineRoot[\"output_file\"].get<std::string>()); // MOKUHYO_PATCH")

replace_once(
  "      if (lineRoot.contains(\"speaker_id\")) {"
  "      if (lineRoot.contains(\"length_scale\")) { // MOKUHYO_PATCH\n        voice.synthesisConfig.lengthScale = lineRoot[\"length_scale\"].get<float>();\n      }\n\n      if (lineRoot.contains(\"speaker_id\")) {")

replace_once(
  "      // Output audio to WAV file\n      ofstream audioFile(outputPath.string(), ios::binary);\n      piper::textToWavFile(piperConfig, voice, line, audioFile, result);\n      cout << outputPath.string() << endl;\n"
  "      // Output audio to WAV file\n      ofstream audioFile(outputPath, ios::binary); // MOKUHYO_PATCH: wide path on Windows\n      piper::textToWavFile(piperConfig, voice, line, audioFile, result);\n      audioFile.close(); // MOKUHYO_PATCH: the file is complete before we announce it\n      cout << outputPath.u8string() << endl;\n")

replace_once(
  "    voice.synthesisConfig.speakerId = speakerId;\n"
  "    voice.synthesisConfig.speakerId = speakerId;\n    voice.synthesisConfig.lengthScale = lengthScale; // MOKUHYO_PATCH\n")

file(WRITE "${FILE}" "${src}")
message(STATUS "patch_piper: patched ${FILE}")

# 4. piper.cpp next to main.cpp: MOKUHYO_TTS_THREADS caps ONNX Runtime's intra-op threads, so the voice doesn't take
#    every core while the model writes the next sentence (BRIEF_INTERVIEW_SPEED N4). Unset or 0 keeps the default.
get_filename_component(cpp_dir "${FILE}" DIRECTORY)
set(PIPER_CPP "${cpp_dir}/piper.cpp")
if(NOT EXISTS "${PIPER_CPP}.orig")
  file(COPY_FILE "${PIPER_CPP}" "${PIPER_CPP}.orig")
endif()
file(READ "${PIPER_CPP}.orig" src)

replace_once(
  "#include <array>\n"
  "#include <array>\n#include <cstdlib> // MOKUHYO_PATCH\n")

replace_once(
  "  // Slows down performance by ~2x\n  // session.options.SetIntraOpNumThreads(1);\n"
  "  // Slows down performance by ~2x\n  // session.options.SetIntraOpNumThreads(1);\n  if (const char *mokuhyoThreads = std::getenv(\"MOKUHYO_TTS_THREADS\")) { // MOKUHYO_PATCH\n    int n = std::atoi(mokuhyoThreads);\n    if (n > 0) {\n      session.options.SetIntraOpNumThreads(n);\n    }\n  }\n")

file(WRITE "${PIPER_CPP}" "${src}")
message(STATUS "patch_piper: patched ${PIPER_CPP}")
