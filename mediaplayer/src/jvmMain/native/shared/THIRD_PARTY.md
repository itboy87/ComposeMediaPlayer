# Native runtime dependencies

* GStreamer 1.28.8 and selected plugins: LGPL-2.1-or-later; https://gstreamer.freedesktop.org/src/
* FFmpeg (gst-libav), GLib, libsoup and the transitive dependencies selected by the upstream GStreamer distribution: corresponding upstream license notices are copied from SDK share/licenses and share/doc into each bundle. Source recipes and source URLs: https://gitlab.freedesktop.org/gstreamer/cerbero/-/tree/1.28.8/recipes
* libass: ISC; https://github.com/libass/libass
* Chromium CDM public interface: BSD, revision recorded in vendor/REVISION; full notice in vendor/LICENSE. This is the interface only; no Widevine implementation is included.
* Noto Sans fallback font: SIL Open Font License 1.1, full license in fonts/OFL.txt. Source: https://github.com/google/fonts/tree/main/ofl/notosans

The project's MIT license does not replace these dependency licenses. Bundle dependency notices accompany the binaries. Proprietary CDMs are supplied separately by the host application.
