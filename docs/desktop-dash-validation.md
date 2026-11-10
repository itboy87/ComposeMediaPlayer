# Native validation — 2026-11-10

## Platforms exercised

| Platform | Result |
| --- | --- |
| macOS arm64, macOS 27.2 locally and macos-15 CI, JDK 17 | Native DASH/ASS, MP4/HLS and metadata regression execution passed. CI: 18 passed, 1 Widevine test skipped. |
| macOS x86_64, macos-15-intel CI, JDK 17 | Native DASH/ASS, MP4/HLS and metadata regression execution passed. CI: 18 passed, 1 Widevine test skipped. |
| Windows x64, windows-2022 CI, JDK 17 | Native DASH/ASS and Media Foundation MP4/HLS regression execution passed. CI: 16 passed, 3 skipped. |
| Windows arm64, windows-11-arm CI, Microsoft JDK 17 | Native DASH/ASS and Media Foundation MP4/HLS regression execution passed. CI: 16 passed, 3 skipped. |
| Android, iOS arm64/simulator arm64, JavaScript and Wasm | Compiled and published in CI. No native playback execution on these targets; DASH support remains scoped to the desktop implementation. The earlier local Android check was blocked by missing Build Tools 35.0.0. |

The four native CI reports above are from [release validation at commit 5e97899](https://github.com/itboy87/ComposeMediaPlayer/actions/runs/42795909699). Each Windows job skips the Widevine test and two macOS-only native metadata cases. No native playback test failed in this run. Linux x64/arm64 legacy natives were also built for packaging; Linux playback was not executed.

## Executed checks

The complete selected JVM suite passed: **36 passed, 1 skipped**. A subsequent focused run also passed after extending the fixture to an opaque proxy URL.

* DASH uses synthetic moving H.264 video, separate AAC audio, two video representations, and relative fragmented MP4 segments. Tests verify requests for both video representations, changing decoded pixel content, advancing frames/position, non-silent decoded PCM, pause/resume, seeking, completion and native release.
* Authenticated media requests and percent-encoded signed segment queries are checked by the local fixture server. The focused run uses an opaque authenticated proxy URL with an octet-stream response, while the internal transport identifies and rewrites the MPD.
* Native startup cancellation and failed network startup release the pipeline. Existing MP4 and HLS playback is exercised through the platform facade, including source replacement, decoded-frame availability, and safe rejection of draws using retired frames.
* ASS renders styled, positioned text with the bundled font; pixels are present only during the cue interval.
* Existing macOS delayed/opaque metadata startup, BGRA stride/frame handling, and buffered-range tests pass.
* Transport tests verify cross-origin credential stripping, byte-range preservation, and raw binary response preservation.
* Artifact tests reject same-size binary corruption and unindexed native files. Python verification accepts current bundles and rejects a stale source fingerprint. Both macOS runtime bundles pass checksum verification and strict ad-hoc signature checks.
* The normal `:mediaplayer:jvmJar` build passes with the repository's configuration-cache defaults.

After the macOS relocation correction, a fresh local run of native DASH/ASS, lifecycle, HTTP, runtime integrity, metadata and frame tests passed **25 tests with no skips or failures**. Both rebuilt macOS bundles passed checksum validation and strict signature verification on all 59 dylibs per architecture.

DASH integration tests use a clocked test audio sink and probe decoded PCM. They establish successful audio decoding; physical speaker output was not verified. The MP4/HLS regressions use AVFoundation's normal platform path.

## Media3 public samples

The optional online suite was executed on **macOS arm64**: **4 passed, 3 skipped**. The [Media3 sample catalog](https://github.com/androidx/media/blob/release/demos/main/src/main/assets/media.exolist.json) supplied two clear H.264/AAC DASH manifests (including multiple BaseURLs) and two ASS positioning/styling scripts. Both DASH streams produced changing decoded video and non-silent decoded PCM; both ASS scripts produced visible overlays.

The multiple-BaseURL sample exposed a native dashdemux2 assertion on a whole-period single-segment SegmentList without duration. Manifest normalization now supplies the unambiguous period duration while preserving inherited timing. The corresponding unit regression passes.

A combined local run of public samples, deterministic DASH, MP4/HLS lifecycle, transport and bundle-integrity tests completed with **12 passed, 3 skipped, 0 failures**. The skipped cases are real Widevine CENC, CBCS and license renewal, gated on an app-supplied CDM. The public suite also passed on [Windows x64](https://github.com/itboy87/ComposeMediaPlayer/actions/runs/42794742344/job/129756897641), including both clear DASH and both ASS samples; its encrypted cases were skipped. Public checks were not executed on macOS x86_64 or Windows arm64. They can be enabled in the native CI workflow using its `public_samples` input.

## Widevine and signing limits

The original configured Widevine decoding test was **skipped**. Media3 public CENC/CBCS and renewal fixtures and license endpoints have since been added; those encrypted tests remain blocked only on a compatible app-supplied CDM. No claim of successful Crunchyroll or other commercial-provider DRM playback is made. CDM host/certificate verification, provisioning requirements and provider output policies must be checked using that integration test.

The bundles were checked with ad-hoc signatures. Apple Developer signing, hardened-runtime application loading and Apple notarization were not executed. Downstream signing instructions and a manifest-rehash helper are provided in the integration guide.

Initial CI attempts exposed a missing Temurin 17 Windows ARM64 distribution and a macOS Mach-O relocation failure. The final successful run uses Microsoft JDK 17 on Windows ARM64, explicitly selects Xcode 26.2 on macOS, and retains signature layout until relocation is finished before signing again. Publication depends on all four native playback jobs succeeding.

## GitHub Packages release verification

Version [0.11.5-alpha03](https://github.com/itboy87/ComposeMediaPlayer/packages/3178058?version=0.11.5-alpha03) is published from commit `5e97899` to the `itboy87/ComposeMediaPlayer` GitHub Packages registry. All seven publications were checked: multiplatform metadata, Android, iOS arm64, iOS simulator arm64, JavaScript, JVM and Wasm.

The published JVM jar was downloaded independently (174,926,499 bytes). All four runtime bundles passed complete inventory and SHA-256 verification, and their source fingerprints matched the checkout. All six existing macOS/Windows/Linux platform native binaries were present. This verification does not establish Widevine playback or Apple Developer signing/notarization.
