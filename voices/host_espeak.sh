#!/usr/bin/env bash
# Cross builds (voices/build_mingw.sh): espeak-ng compiles its phoneme data by running the espeak-ng it just built,
# which is a Windows .exe here. Its data.cmake runs "$VALGRIND <exe> args…", so build_mingw.sh sets VALGRIND to this
# script, which drops the .exe and runs a host espeak-ng from the same source instead (the data is platform-neutral).
shift
host="${HOST_ESPEAK_NG:?set HOST_ESPEAK_NG to a host espeak-ng binary}"
# Its shared library sits in ../lib (set here: macOS strips DYLD_* when passing through system shells).
export DYLD_LIBRARY_PATH="$(dirname "$host")/../lib" LD_LIBRARY_PATH="$(dirname "$host")/../lib"
exec "$host" "$@"
