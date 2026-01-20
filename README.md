# Android GUI for [WireGuard](https://www.wireguard.com/)

**[Download from the Play Store](https://play.google.com/store/apps/details?id=com.wireguard.android)**

This is an Android GUI for [WireGuard](https://www.wireguard.com/). It [opportunistically uses the kernel implementation](https://git.zx2c4.com/android_kernel_wireguard/about/), and falls back to using the non-root [userspace implementation](https://git.zx2c4.com/wireguard-go/about/).

## VK Turn Proxy Integration

This fork includes VK Turn Proxy integration that allows routing WireGuard traffic through VK TURN servers. This can be useful when direct WireGuard connections are blocked.

### Features

- VK Call link support for TURN authentication
- Configurable WireGuard server address and port
- Adjustable local listen port (default: 9000)
- MTU configuration (recommended: 1420)
- Multiple connections support (1-64)
- UDP mode option
- Real-time connection logs viewer

### How to Use

1. Create a VK call link at vk.com (or find an existing one)
2. Set up your WireGuard server with [vk-turn-proxy server](https://github.com/cacggghp/vk-turn-proxy)
3. Open WireGuard app and go to "VK Turn Proxy" from the menu
4. Enter:
   - VK Call Link (e.g., `https://vk.com/call/join/...`)
   - WireGuard Server Address (your server IP)
   - WireGuard Server Port (default: 56000)
   - Listen Port (default: 9000)
   - MTU (recommended: 1420)
   - Connections (1-16, use 1 for more stable connection with ~5 Mbit/s limit)
5. In your WireGuard tunnel config, set endpoint to `127.0.0.1:9000`
6. Set MTU to 1420 in your WireGuard config
7. Add this app to WireGuard excluded applications
8. Start the proxy, then enable your WireGuard tunnel

### Requirements

The VK Turn Proxy requires the native `vk-turn-proxy` binary to be installed. See [https://github.com/cacggghp/vk-turn-proxy](https://github.com/cacggghp/vk-turn-proxy) for more information.

## Building

```
$ git clone --recurse-submodules https://git.zx2c4.com/wireguard-android
$ cd wireguard-android
$ ./gradlew assembleRelease
```

macOS users may need [flock(1)](https://github.com/discoteq/flock).

## Automated Builds

This repository includes GitHub Actions workflow for automated builds. On every push to main/master branches and pull requests, the workflow will:
- Build Debug and Release APKs
- Upload APKs as artifacts

When a tag starting with `v` is pushed (e.g., `v1.0.0`), the workflow will also:
- Create a GitHub Release
- Attach Debug and Release APKs to the release

## Embedding

The tunnel library is [on Maven Central](https://search.maven.org/artifact/com.wireguard.android/tunnel), alongside [extensive class library documentation](https://javadoc.io/doc/com.wireguard.android/tunnel).

```
implementation 'com.wireguard.android:tunnel:$wireguardTunnelVersion'
```

The library makes use of Java 8 features, so be sure to support those in your gradle configuration with [desugaring](https://developer.android.com/studio/write/java8-support#library-desugaring):

```
compileOptions {
    sourceCompatibility JavaVersion.VERSION_17
    targetCompatibility JavaVersion.VERSION_17
    coreLibraryDesugaringEnabled = true
}
dependencies {
    coreLibraryDesugaring "com.android.tools:desugar_jdk_libs:2.0.3"
}
```

## Translating

Please help us translate the app into several languages on [our translation platform](https://crowdin.com/project/WireGuard).
