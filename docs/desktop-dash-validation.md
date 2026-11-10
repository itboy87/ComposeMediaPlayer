# Native validation — 2026-11-10

## Platforms exercised

| Platform | Result |
| --- | --- |
| macOS arm64, macOS 27.2, JDK 17 | Executed native DASH/ASS and existing AVFoundation regression tests successfully. |
| macOS x86_64 | Cross-compiled the runtime and verified its dependency manifest and 59 dylib signatures. Playback was not executed. |
| Windows x64 and arm64 | Build/package scripts and native CI jobs added. Neither target was compiled or executed on this machine. |
| iOS simulator arm64 and JavaScript | Kotlin compatibility compilation passed. This does not add DASH support to these targets. |
| Android | Additional compile check blocked by missing Android Build Tools 35.0.0. |

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

DASH integration tests use a clocked test audio sink and probe decoded PCM. They establish successful audio decoding; physical speaker output was not verified. The MP4/HLS regressions use AVFoundation's normal platform path.

## Media3 public samples

The optional online suite was executed on **macOS arm64**: **4 passed, 3 skipped**. The [Media3 sample catalog](https://github.com/androidx/media/blob/release/demos/main/src/main/assets/media.exolist.json) supplied two clear H.264/AAC DASH manifests (including multiple BaseURLs) and two ASS positioning/styling scripts. Both DASH streams produced changing decoded video and non-silent decoded PCM; both ASS scripts produced visible overlays.

The multiple-BaseURL sample exposed a native dashdemux2 assertion on a whole-period single-segment SegmentList without duration. Manifest normalization now supplies the unambiguous period duration while preserving inherited timing. The corresponding unit regression passes.

A combined run of public samples, deterministic DASH, MP4/HLS lifecycle, transport and bundle-integrity tests completed with **12 passed, 3 skipped, 0 failures**. The skipped cases are real Widevine CENC, CBCS and license renewal, gated on an app-supplied CDM. These public checks were not executed on macOS x86_64 or Windows. They can be enabled in the native CI workflow using its `public_samples` input.

## Widevine and signing limits

The original configured Widevine decoding test was **skipped**. Media3 public CENC/CBCS and renewal fixtures and license endpoints have since been added; those encrypted tests remain blocked only on a compatible app-supplied CDM. No claim of successful Crunchyroll or other commercial-provider DRM playback is made. CDM host/certificate verification, provisioning requirements and provider output policies must be checked using that integration test.

The bundles were checked with ad-hoc signatures. Apple Developer signing, hardened-runtime application loading and Apple notarization were not executed. Downstream signing instructions and a manifest-rehash helper are provided in the integration guide.

The added CI matrix covers native execution on macOS arm64/x86_64 and Windows x64/arm64. Those remote jobs have not been run from this local change.
