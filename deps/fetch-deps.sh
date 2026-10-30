#!/usr/bin/env bash
# Fetch what the build needs and the repo does not carry:
#   deps/wasmcart-native  the shared C host, pinned commit
#   deps/SDL              SDL2 source (Java shell + libSDL2.so), pinned tag
#   deps/libnode          prebuilt android-aarch64 libnode.a + headers
set -euo pipefail
cd "$(dirname "$0")"

WASMCART_NATIVE_REF="${WASMCART_NATIVE_REF:-38bd711dbf527f0f5141c3c26a08835aff0c5a8f}"
SDL_TAG="${SDL_TAG:-release-2.32.10}"
LIBNODE_RELEASE="${LIBNODE_RELEASE:-v26.3.0-jsg9}"

if [ ! -d wasmcart-native ]; then
    echo "fetching wasmcart-native ${WASMCART_NATIVE_REF}..."
    git init -q wasmcart-native
    git -C wasmcart-native remote add origin https://github.com/wasmcart/wasmcart-native.git
    git -C wasmcart-native fetch -q --depth 1 origin "${WASMCART_NATIVE_REF}"
    git -C wasmcart-native checkout -q FETCH_HEAD
fi

if [ ! -d SDL ]; then
    echo "fetching SDL ${SDL_TAG}..."
    git clone -q --depth 1 --branch "${SDL_TAG}" https://github.com/libsdl-org/SDL.git SDL
fi

if [ ! -f libnode/libnode.a ]; then
    echo "fetching libnode ${LIBNODE_RELEASE} (android-aarch64)..."
    mkdir -p libnode
    curl -fsSL "https://github.com/wasmcart/build-libnode/releases/download/${LIBNODE_RELEASE}/libnode-android-aarch64.tar.gz" \
        | tar xz -C libnode
fi

echo "deps ready:"
echo "  wasmcart-native: $(git -C wasmcart-native rev-parse --short HEAD)"
echo "  SDL:             $(git -C SDL describe --tags 2>/dev/null || echo '?')"
echo "  libnode:         $(cat libnode/NODE_VERSION 2>/dev/null || echo '?')"
