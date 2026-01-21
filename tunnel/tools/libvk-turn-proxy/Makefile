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

export GOARCH := $(NDK_GO_ARCH_MAP_$(ANDROID_ARCH_NAME))
# Use linux instead of android to avoid wlynxg/anet linkname issues
# Linux static binaries work fine on Android
export GOOS := linux
# Disable CGO for static binary
export CGO_ENABLED := 0

GO_VERSION := 1.24.3
GO_PLATFORM := $(shell uname -s | tr '[:upper:]' '[:lower:]')-$(NDK_GO_ARCH_MAP_$(shell uname -m))
GO_TARBALL := go$(GO_VERSION).$(GO_PLATFORM).tar.gz
GO_HASH_darwin-amd64 := 13e6fe3fcf65689d77d40e633de1e31c6febbdbcb846eb05fc2434ed2213e92b
GO_HASH_darwin-arm64 := 64a3fa22142f627e78fac3018ce3d4aeace68b743eff0afda8aae0411df5e4fb
GO_HASH_linux-amd64 := 3333f6ea53afa971e9078895eaa4ac7204a8c6b5c68c10e6bc9a33e8e391bdd8

# vk-turn-proxy repository
VK_TURN_PROXY_REPO := https://github.com/cacggghp/vk-turn-proxy.git
VK_TURN_PROXY_COMMIT := HEAD

# Mark default as PHONY to always rebuild
.PHONY: default
default: $(DESTDIR)/libvkturnproxy.so

$(GRADLE_USER_HOME)/caches/golang/$(GO_TARBALL):
	@echo "[VK-TURN-PROXY] Downloading Go $(GO_VERSION)..."
	mkdir -p "$(dir $@)"
	flock "$@.lock" -c ' \
	[ -f "$@" ] && exit 0; \
	curl -o "$@.tmp" "https://dl.google.com/go/$(GO_TARBALL)" && \
	echo "$(GO_HASH_$(GO_PLATFORM))  $@.tmp" | sha256sum -c && \
	mv "$@.tmp" "$@"'

$(BUILDDIR)/go-$(GO_VERSION)/.prepared: $(GRADLE_USER_HOME)/caches/golang/$(GO_TARBALL)
	@echo "[VK-TURN-PROXY] Extracting Go $(GO_VERSION) to $(BUILDDIR)/go-$(GO_VERSION)..."
	mkdir -p "$(dir $@)"
	flock "$@.lock" -c ' \
	[ -f "$@" ] && exit 0; \
	tar -C "$(dir $@)" --strip-components=1 -xzf "$^" && \
	touch "$@"'

$(BUILDDIR)/vk-turn-proxy/.prepared:
	@echo "[VK-TURN-PROXY] Cloning vk-turn-proxy repository..."
	mkdir -p "$(dir $@)"
	flock "$@.lock" -c ' \
	[ -f "$@" ] && exit 0; \
	git clone --depth 1 $(VK_TURN_PROXY_REPO) "$(BUILDDIR)/vk-turn-proxy/src" && \
	touch "$@"'
	@echo "[VK-TURN-PROXY] Repository cloned to $(BUILDDIR)/vk-turn-proxy/src"
	@echo "[VK-TURN-PROXY] Patching source for IPv4-only networking..."
	find "$(BUILDDIR)/vk-turn-proxy/src" -name "*.go" -exec sed -i 's/"tcp"/"tcp4"/g' {} \;
	find "$(BUILDDIR)/vk-turn-proxy/src" -name "*.go" -exec sed -i 's/"udp"/"udp4"/g' {} \;
	@echo "[VK-TURN-PROXY] IPv4 patching complete"

$(DESTDIR)/libvkturnproxy.so: export PATH := $(BUILDDIR)/go-$(GO_VERSION)/bin/:$(PATH)
$(DESTDIR)/libvkturnproxy.so: $(BUILDDIR)/go-$(GO_VERSION)/.prepared $(BUILDDIR)/vk-turn-proxy/.prepared
	@echo "[VK-TURN-PROXY] Building libvkturnproxy.so for GOARCH=$(GOARCH)..."
	@echo "[VK-TURN-PROXY] PATH=$(PATH)"
	@echo "[VK-TURN-PROXY] Go version: $$($(BUILDDIR)/go-$(GO_VERSION)/bin/go version)"
	mkdir -p "$(DESTDIR)"
	cd "$(BUILDDIR)/vk-turn-proxy/src/client" && $(BUILDDIR)/go-$(GO_VERSION)/bin/go build -ldflags="-buildid= -s -w" -v -trimpath -buildvcs=false -o "$@"
	@echo "[VK-TURN-PROXY] Build complete: $@"
	@ls -la "$@"

.DELETE_ON_ERROR:
