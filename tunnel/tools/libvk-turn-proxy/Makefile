# SPDX-License-Identifier: Apache-2.0
#
# Makefile for building vk-turn-proxy client for Android

BUILDDIR ?= $(CURDIR)/build
DESTDIR ?= $(CURDIR)/out

NDK_GO_ARCH_MAP_x86 := 386
NDK_GO_ARCH_MAP_x86_64 := amd64
NDK_GO_ARCH_MAP_arm := arm
NDK_GO_ARCH_MAP_arm64 := arm64
NDK_GO_ARCH_MAP_mips := mipsx
NDK_GO_ARCH_MAP_mips64 := mips64x

comma := ,
CLANG_FLAGS := --target=$(TARGET) --sysroot=$(SYSROOT)
export CGO_CFLAGS := $(CLANG_FLAGS) $(subst -mthumb,-marm,$(CFLAGS))
export CGO_LDFLAGS := $(CLANG_FLAGS) $(patsubst -Wl$(comma)--build-id=%,-Wl$(comma)--build-id=none,$(LDFLAGS)) -Wl,-soname=libvkturnproxy.so
export GOARCH := $(NDK_GO_ARCH_MAP_$(ANDROID_ARCH_NAME))
export GOOS := android
export CGO_ENABLED := 1

# vk-turn-proxy requires Go 1.25+ for github.com/wlynxg/anet compatibility
GO_VERSION := 1.25.6
GO_PLATFORM := $(shell uname -s | tr '[:upper:]' '[:lower:]')-$(NDK_GO_ARCH_MAP_$(shell uname -m))
GO_TARBALL := go$(GO_VERSION).$(GO_PLATFORM).tar.gz
GO_HASH_darwin-amd64 := e2b5b237f5c262931b8e280ac4b8363f156e19bfad5270c099998932819670b7
GO_HASH_darwin-arm64 := 984521ae978a5377c7d782fd2dd953291840d7d3d0bd95781a1f32f16d94a006
GO_HASH_linux-amd64 := f022b6aad78e362bcba9b0b94d09ad58c5a70c6ba3b7582905fababf5fe0181a

# vk-turn-proxy repository
VK_TURN_PROXY_REPO := https://github.com/cacggghp/vk-turn-proxy.git
VK_TURN_PROXY_COMMIT := HEAD

default: $(DESTDIR)/libvkturnproxy.so

$(GRADLE_USER_HOME)/caches/golang/$(GO_TARBALL):
	mkdir -p "$(dir $@)"
	flock "$@.lock" -c ' \
	[ -f "$@" ] && exit 0; \
	curl -o "$@.tmp" "https://dl.google.com/go/$(GO_TARBALL)" && \
	echo "$(GO_HASH_$(GO_PLATFORM))  $@.tmp" | sha256sum -c && \
	mv "$@.tmp" "$@"'

$(BUILDDIR)/go-$(GO_VERSION)/.prepared: $(GRADLE_USER_HOME)/caches/golang/$(GO_TARBALL)
	mkdir -p "$(dir $@)"
	flock "$@.lock" -c ' \
	[ -f "$@" ] && exit 0; \
	tar -C "$(dir $@)" --strip-components=1 -xzf "$^" && \
	touch "$@"'

$(BUILDDIR)/vk-turn-proxy/.prepared:
	mkdir -p "$(dir $@)"
	flock "$@.lock" -c ' \
	[ -f "$@" ] && exit 0; \
	git clone --depth 1 $(VK_TURN_PROXY_REPO) "$(BUILDDIR)/vk-turn-proxy/src" && \
	touch "$@"'

$(DESTDIR)/libvkturnproxy.so: export PATH := $(BUILDDIR)/go-$(GO_VERSION)/bin/:$(PATH)
$(DESTDIR)/libvkturnproxy.so: $(BUILDDIR)/go-$(GO_VERSION)/.prepared $(BUILDDIR)/vk-turn-proxy/.prepared
	cd "$(BUILDDIR)/vk-turn-proxy/src/client" && go build -ldflags="-buildid=" -v -trimpath -buildvcs=false -o "$@"

.DELETE_ON_ERROR:
