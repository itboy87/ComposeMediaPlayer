# Desktop DASH, Widevine and ASS

The new desktop backend decodes DASH using a bundled GStreamer 1.28.8 runtime. Existing plain MP4/HLS requests retain the platform player. Configured media requests use the shared pipeline so their headers can be scoped safely. macOS and Windows are supported by the new backend; the Linux backend remains separate.

```kotlin
val player = createVideoPlayerState()
(player as DefaultVideoPlayerState).configureWidevine(WidevineRuntime(cdmLibraryPath))
player.openSource(MediaSource(
    uri = resolvedManifestUrl,
    mimeType = "application/dash+xml",
    headers = mediaHeaders,
    preferredAudioLanguage = "ja-JP",
    subtitles = listOf(SubtitleTrack("English", "en-US", subtitleUrl, "ass", emptyMap())),
    widevine = WidevineConfiguration(licenseUrl, licenseHeaders),
))
```

Only resolved values enter this API. Media, subtitle and license headers are independent. Additional media-header origins must be declared explicitly. Cross-origin redirects drop headers. The default license transport sends the binary CDM challenge and passes raw response bytes back to the CDM. A custom suspending transport may unwrap a provider response.

The application supplies a compatible Widevine CDM library for the current architecture. The library does not acquire or redistribute it. Only temporary sessions and software-accessible compressed samples are supported. CDM rejection, unavailable verification/provisioning, protected-output requirements and expired licenses are reported as DRM errors. Actual commercial-provider compatibility requires an authorized CDM/fixture test; clear DASH tests cannot establish it.

`events` identifies each opened source by a monotonically increasing `sourceId`. The caller owns provider playback leases, keep-alives, maximum pause/session limits, release requests and fresh-token acquisition. The library never invokes provider renew/release URLs. Preserve `drm*` and session fields in the consuming application's model/mapper.

ASS rendering uses libass, including script styling, placement and timing. Noto Sans provides a bundled fallback. SRT and WebVTT remain available. Subtitle URLs and headers must never inherit account authorization from the media source.

## Builds and application signing

`tools/build_media_runtime.py` downloads checksum-pinned upstream SDKs into `mediaplayer/build/native-sdk`; it does not install a runtime for end users. It stages selected plugins and their native dependency closure, then emits `bundle.properties` with source identity, ABI, architecture and file SHA-256 hashes. `--verify BUNDLE` rejects stale source identities or changed files.

`./gradlew :mediaplayer:extractMediaRuntime` stages the runtime for application packaging. On macOS, the build rewrites dylib dependencies to `@loader_path` before signing. Extract before the downstream signing pass. Sign nested libraries first, run `python3 tools/rehash_signed_runtime.py BUNDLE` to regenerate the hashes for the signed bytes, then sign/notarize the enclosing application. Runtime extraction never rewrites signed files. Set `composemediaplayer.runtimeDir` to the prepared architecture-specific directory to load it directly. Host-provided CDM signing/verification requirements belong to the application integration.

The runtime loader verifies the complete bundle and uses a locked, content-addressed cache. `composemediaplayer.cacheDir` can select an application cache directory. No system GStreamer/VLC/mpv installation is used.

## Validation

`COMPOSE_MEDIA_PLAYER_RUN_DASH_TEST=1 ./gradlew :mediaplayer:jvmTest --tests '*NativeDashIntegrationTest'` enables real native DASH/PCM and ASS tests. Fixtures are synthetic test video plus a generated sine wave. The test audio sink verifies non-silent decoded PCM and clock progression; it does not establish physical audio-device output. The native CI matrix targets macOS arm64/x86_64 and Windows x64/arm64.

Real Widevine validation is enabled by `COMPOSE_MEDIA_PLAYER_WIDEVINE_CONFIG`, pointing to a private Java properties file containing `cdm`, `manifest`, `license`, optional `media.header.NAME` values, and `license.header.NAME` values. Run `--tests '*WidevineNativeIntegrationTest'`. Keep the file outside the repository. Without this configuration the test is explicitly skipped; no commercial DRM playback success is inferred.

## Media3 public samples

The optional online suite uses URLs from [Media3's sample catalog](https://github.com/androidx/media/blob/release/demos/main/src/main/assets/media.exolist.json), catalog Git blob `1913a74cdb4e955b380c806ed0df6bd742e109bc`. Selected public metadata is recorded in `media3-public-samples.properties`; the media itself is fetched during testing and is not redistributed.

```sh
COMPOSE_MEDIA_PLAYER_RUN_PUBLIC_TEST=1 ./gradlew :mediaplayer:jvmTest --tests '*Media3PublicNativeIntegrationTest' --no-configuration-cache
```

This runs clear H.264/AAC and multiple-BaseURL DASH decoding plus the public ASS positioning/styling scripts. Public movie tests allow static title cards while requiring actual changing decoded pixels and non-silent PCM. Online failures are reported as failures; the deterministic fixture suite remains independent of upstream availability.

For actual Widevine CENC, CBCS and short-license renewal testing, also set `COMPOSE_MEDIA_PLAYER_WIDEVINE_CDM` to a compatible app-supplied CDM library. Media3 supplies the public MPDs and test-license endpoints; no provider account credentials are required by these test configurations. Without a CDM, the three encrypted cases are explicitly skipped. The `public_samples` input on the native CI workflow enables the online suite manually.

Whole-period single-segment `SegmentList` manifests can omit segment duration. The transport supplies a nanosecond-timescale duration derived from an explicit Period duration (or a single Period's presentation duration) when there is no inherited timing, explicit duration/timescale/offset, or SegmentTimeline. This avoids a dashdemux2 assertion without altering inherited timing.
