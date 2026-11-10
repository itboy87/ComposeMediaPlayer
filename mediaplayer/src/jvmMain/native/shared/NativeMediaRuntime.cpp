#include <jni.h>
#include <gst/gst.h>
#include <gst/app/gstappsink.h>
#include <gst/video/video.h>
#include <gst/audio/audio.h>
#include <gst/base/gstbasetransform.h>
#include <ass/ass.h>
#include "WidevineHost.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <vector>

extern "C" unsigned int soup_get_major_version(void);

namespace {
std::string string(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* text = env->GetStringUTFChars(value, nullptr);
    if (!text) return {};
    std::string result(text); env->ReleaseStringUTFChars(value, text); return result;
}
std::vector<uint8_t> bytes(JNIEnv* env, jbyteArray value) {
    if (!value) return {};
    std::vector<uint8_t> result(env->GetArrayLength(value));
    env->GetByteArrayRegion(value, 0, static_cast<jsize>(result.size()), reinterpret_cast<jbyte*>(result.data())); return result;
}
jbyteArray byteArray(JNIEnv* env, const std::vector<uint8_t>& value) {
    if (value.size() > INT32_MAX) return nullptr;
    auto result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte*>(value.data()));
    return result;
}

typedef struct { GstBaseTransform parent; WidevineHost* host; } CmpDecrypt;
typedef struct { GstBaseTransformClass parent; } CmpDecryptClass;
G_DEFINE_TYPE(CmpDecrypt, cmp_decrypt, GST_TYPE_BASE_TRANSFORM)

GstCaps* transformCaps(GstBaseTransform*, GstPadDirection direction, GstCaps* caps, GstCaps* filter) {
    auto result = gst_caps_new_empty();
    for (guint i = 0; i < gst_caps_get_size(caps); ++i) {
        auto structure = gst_structure_copy(gst_caps_get_structure(caps, i));
        if (direction == GST_PAD_SINK) {
            auto type = gst_structure_get_string(structure, "original-media-type");
            if (!type) { gst_structure_free(structure); continue; }
            gst_structure_set_name(structure, type);
            gst_structure_remove_fields(structure, "original-media-type", "protection-system", "cipher-mode", nullptr);
        } else {
            const std::string original = gst_structure_get_name(structure);
            gst_structure_set_name(structure, "application/x-cenc");
            gst_structure_set(structure, "original-media-type", G_TYPE_STRING, original.c_str(),
                "protection-system", G_TYPE_STRING, "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed", nullptr);
        }
        gst_caps_append_structure(result, structure);
    }
    if (filter) { auto intersection = gst_caps_intersect_full(filter, result, GST_CAPS_INTERSECT_FIRST); gst_caps_unref(result); return intersection; }
    return result;
}
gboolean protectionEvent(GstBaseTransform* transform, GstEvent* event) {
    auto self = reinterpret_cast<CmpDecrypt*>(transform);
    if (GST_EVENT_TYPE(event) == GST_EVENT_PROTECTION) {
        const gchar *system = nullptr, *origin = nullptr; GstBuffer* data = nullptr;
        gst_event_parse_protection(event, &system, &data, &origin);
        if (self->host && system && g_ascii_strcasecmp(system, "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed") == 0) {
            GstMapInfo map{};
            if (gst_buffer_map(data, &map, GST_MAP_READ)) {
                self->host->initData(std::vector<uint8_t>(map.data, map.data + map.size)); gst_buffer_unmap(data, &map);
            }
        }
    }
    return GST_BASE_TRANSFORM_CLASS(cmp_decrypt_parent_class)->sink_event(transform, event);
}
std::vector<uint8_t> protectionBytes(const GstStructure* info, const char* key) {
    auto value = gst_structure_get_value(info, key);
    if (!value || !GST_VALUE_HOLDS_BUFFER(value)) return {};
    auto buffer = gst_value_get_buffer(value); GstMapInfo map{};
    if (!gst_buffer_map(buffer, &map, GST_MAP_READ)) return {};
    std::vector<uint8_t> result(map.data, map.data + map.size); gst_buffer_unmap(buffer, &map); return result;
}
GstFlowReturn decryptBuffer(GstBaseTransform* transform, GstBuffer* buffer) {
    auto self = reinterpret_cast<CmpDecrypt*>(transform);
    auto meta = gst_buffer_get_protection_meta(buffer);
    if (!meta) return GST_FLOW_OK;
    gboolean encrypted = TRUE; gst_structure_get_boolean(meta->info, "encrypted", &encrypted);
    if (!encrypted) { gst_buffer_remove_meta(buffer, &meta->meta); return GST_FLOW_OK; }
    if (!self->host) { GST_ELEMENT_ERROR(transform, STREAM, DECRYPT, ("Widevine client is missing"), (nullptr)); return GST_FLOW_ERROR; }
    auto kid = protectionBytes(meta->info, "kid");
    auto iv = protectionBytes(meta->info, "iv");
    auto packedSubsamples = protectionBytes(meta->info, "subsamples");
    guint count = 0; gst_structure_get_uint(meta->info, "subsample_count", &count);
    if (kid.empty() || (iv.size() != 8 && iv.size() != 16) || count > packedSubsamples.size() / 6) { self->host->fail(15); return GST_FLOW_ERROR; }
    std::vector<cdm::SubsampleEntry> subsamples;
    uint64_t total = 0;
    for (guint i = 0; i < count; ++i) {
        auto p = packedSubsamples.data() + i * 6;
        uint32_t clear = (uint32_t(p[0]) << 8) | p[1];
        uint32_t cipher = (uint32_t(p[2]) << 24) | (uint32_t(p[3]) << 16) | (uint32_t(p[4]) << 8) | p[5];
        subsamples.push_back({clear, cipher}); total += uint64_t(clear) + cipher;
    }
    GstMapInfo map{};
    if (!gst_buffer_map(buffer, &map, GST_MAP_READWRITE)) return GST_FLOW_ERROR;
    if (map.size > UINT32_MAX || (count && total != map.size)) { gst_buffer_unmap(buffer, &map); self->host->fail(15); return GST_FLOW_ERROR; }
    cdm::InputBuffer_2 input{};
    input.data = map.data; input.data_size = static_cast<uint32_t>(map.size);
    input.key_id = kid.data(); input.key_id_size = static_cast<uint32_t>(kid.size());
    input.iv = iv.data(); input.iv_size = static_cast<uint32_t>(iv.size());
    input.subsamples = subsamples.data(); input.num_subsamples = count;
    input.timestamp = GST_BUFFER_PTS_IS_VALID(buffer) ? static_cast<int64_t>(GST_BUFFER_PTS(buffer) / GST_USECOND) : 0;
    auto mode = gst_structure_get_string(meta->info, "cipher-mode");
    input.encryption_scheme = mode && std::strcmp(mode, "cbcs") == 0 ? cdm::EncryptionScheme::kCbcs : cdm::EncryptionScheme::kCenc;
    gst_structure_get_uint(meta->info, "crypt_byte_block", &input.pattern.crypt_byte_block);
    gst_structure_get_uint(meta->info, "skip_byte_block", &input.pattern.skip_byte_block);
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(30);
    std::vector<uint8_t> output;
    cdm::Status status = cdm::kNoKey;
    while (!self->host->cancelled && !self->host->error && std::chrono::steady_clock::now() < deadline) {
        status = self->host->decrypt(input, output);
        if (status != cdm::kNoKey) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    if (status == cdm::kSuccess) std::memcpy(map.data, output.data(), output.size());
    gst_buffer_unmap(buffer, &map);
    if (self->host->cancelled) return GST_FLOW_FLUSHING;
    if (status != cdm::kSuccess) { if (!self->host->error) self->host->fail(status == cdm::kNoKey ? 12 : 15); return GST_FLOW_ERROR; }
    gst_buffer_remove_meta(buffer, &meta->meta); return GST_FLOW_OK;
}
void cmp_decrypt_class_init(CmpDecryptClass* klass) {
    auto element = GST_ELEMENT_CLASS(klass);
    gst_element_class_set_static_metadata(element, "App-supplied Widevine decryption", "Decryptor", "Temporary CDM sessions", "ComposeMediaPlayer");
    auto sinkCaps = gst_caps_from_string("application/x-cenc, protection-system=(string)edef8ba9-79d6-4ace-a3c8-27dcd51d21ed");
    auto srcCaps = gst_caps_new_any();
    gst_element_class_add_pad_template(element, gst_pad_template_new("sink", GST_PAD_SINK, GST_PAD_ALWAYS, sinkCaps));
    gst_element_class_add_pad_template(element, gst_pad_template_new("src", GST_PAD_SRC, GST_PAD_ALWAYS, srcCaps));
    gst_caps_unref(sinkCaps); gst_caps_unref(srcCaps);
    auto base = GST_BASE_TRANSFORM_CLASS(klass);
    base->transform_caps = transformCaps; base->sink_event = protectionEvent; base->transform_ip = decryptBuffer;
}
void cmp_decrypt_init(CmpDecrypt* self) { self->host = nullptr; gst_base_transform_set_in_place(GST_BASE_TRANSFORM(self), TRUE); }

struct Player {
    GstElement *pipeline = nullptr, *sink = nullptr;
    GstBus* bus = nullptr;
    std::unique_ptr<WidevineHost> cdm;
    std::atomic<bool> cancelled{false};
    std::atomic<int> error{0};
    std::atomic<uint64_t> audioBuffers{0}, audioFrames{0};
    std::atomic<double> audioPeak{0};
    std::atomic<int> channels{0}, sampleRate{0};
    std::mutex frameLock;
    std::vector<uint8_t> frame;
    int width = 0, height = 0, parN = 1, parD = 1;
    uint64_t sequence = 0, pts = 0;
    bool desiredPlaying = false, eos = false, live = false;
    int buffering = 100;
    std::string userAgent;
    std::string audioLanguage;
    ~Player() {
        cancelled = true; if (cdm) cdm->cancel();
        if (pipeline) { gst_element_set_state(pipeline, GST_STATE_NULL); gst_object_unref(pipeline); }
        if (bus) gst_object_unref(bus);
    }
};
std::mutex playersLock;
std::map<jlong, std::shared_ptr<Player>> players;
std::atomic<jlong> nextId{1};
std::shared_ptr<Player> player(jlong id) { std::lock_guard<std::mutex> lock(playersLock); auto i = players.find(id); return i == players.end() ? nullptr : i->second; }
GstFlowReturn capture(GstAppSink* sink, gpointer data, bool preroll) {
    auto p = static_cast<Player*>(data);
    auto sample = preroll ? gst_app_sink_pull_preroll(sink) : gst_app_sink_pull_sample(sink);
    if (!sample) return GST_FLOW_EOS;
    GstVideoInfo info{}; GstVideoFrame frame{};
    if (!p->cancelled && gst_video_info_from_caps(&info, gst_sample_get_caps(sample)) &&
        GST_VIDEO_INFO_FORMAT(&info) == GST_VIDEO_FORMAT_BGRA && gst_video_frame_map(&frame, &info, gst_sample_get_buffer(sample), GST_MAP_READ)) {
        int width = GST_VIDEO_INFO_WIDTH(&info), height = GST_VIDEO_INFO_HEIGHT(&info);
        if (width > 0 && height > 0 && uint64_t(width) * height <= 32 * 1024 * 1024) {
            std::lock_guard<std::mutex> lock(p->frameLock);
            p->frame.resize(size_t(width) * height * 4);
            auto source = static_cast<uint8_t*>(GST_VIDEO_FRAME_PLANE_DATA(&frame, 0));
            int stride = GST_VIDEO_FRAME_PLANE_STRIDE(&frame, 0);
            for (int row = 0; row < height; ++row) std::memcpy(p->frame.data() + size_t(row) * width * 4, source + ptrdiff_t(row) * stride, size_t(width) * 4);
            p->width = width; p->height = height; p->parN = GST_VIDEO_INFO_PAR_N(&info); p->parD = GST_VIDEO_INFO_PAR_D(&info);
            p->pts = GST_BUFFER_PTS_IS_VALID(gst_sample_get_buffer(sample)) ? GST_BUFFER_PTS(gst_sample_get_buffer(sample)) : 0;
            ++p->sequence;
        }
        gst_video_frame_unmap(&frame);
    }
    gst_sample_unref(sample); return p->cancelled ? GST_FLOW_FLUSHING : GST_FLOW_OK;
}
GstFlowReturn newSample(GstAppSink* sink, gpointer data) { return capture(sink, data, false); }
GstFlowReturn newPreroll(GstAppSink* sink, gpointer data) { return capture(sink, data, true); }
GstPadProbeReturn audioProbe(GstPad* pad, GstPadProbeInfo* probe, gpointer data) {
    auto p = static_cast<Player*>(data);
    if (GST_PAD_PROBE_INFO_TYPE(probe) & GST_PAD_PROBE_TYPE_BUFFER) {
        auto buffer = GST_PAD_PROBE_INFO_BUFFER(probe); auto caps = gst_pad_get_current_caps(pad);
        GstAudioInfo info{}; GstMapInfo map{};
        if (caps && gst_audio_info_from_caps(&info, caps) && gst_buffer_map(buffer, &map, GST_MAP_READ)) {
            p->channels = GST_AUDIO_INFO_CHANNELS(&info); p->sampleRate = GST_AUDIO_INFO_RATE(&info);
            p->audioBuffers++; p->audioFrames += map.size / std::max(1, GST_AUDIO_INFO_BPF(&info));
            double peak = 0;
            for (size_t offset = 0; offset + sizeof(float) <= map.size; offset += sizeof(float)) {
                float value; std::memcpy(&value, map.data + offset, sizeof(float)); if (std::isfinite(value)) peak = std::max(peak, std::abs(double(value)));
            }
            p->audioPeak = std::max(p->audioPeak.load(), peak); gst_buffer_unmap(buffer, &map);
        }
        if (caps) gst_caps_unref(caps);
    }
    return GST_PAD_PROBE_OK;
}
void sourceSetup(GstElement*, GstElement* source, gpointer data) {
    auto p = static_cast<Player*>(data);
    if (g_object_class_find_property(G_OBJECT_GET_CLASS(source), "user-agent")) g_object_set(source, "user-agent", p->userAgent.c_str(), nullptr);
    if (g_object_class_find_property(G_OBJECT_GET_CLASS(source), "timeout")) g_object_set(source, "timeout", 15u, nullptr);
}
gint selectStream(GstElement*, GstStreamCollection*, GstStream*, gpointer);
void elementSetup(GstElement*, GstElement* element, gpointer data) {
    auto p = static_cast<Player*>(data);
    auto factory = gst_element_get_factory(element);
    if (factory && std::strcmp(gst_plugin_feature_get_name(GST_PLUGIN_FEATURE(factory)), "decodebin3") == 0)
        g_signal_connect(element, "select-stream", G_CALLBACK(selectStream), p);
    if (G_TYPE_CHECK_INSTANCE_TYPE(element, cmp_decrypt_get_type())) reinterpret_cast<CmpDecrypt*>(element)->host = p->cdm.get();
}
gint selectStream(GstElement*, GstStreamCollection* collection, GstStream* stream, gpointer data) {
    auto p = static_cast<Player*>(data);
    if (!(gst_stream_get_stream_type(stream) & GST_STREAM_TYPE_AUDIO)) return (gst_stream_get_stream_type(stream) & GST_STREAM_TYPE_VIDEO) ? 1 : 0;
    if (p->audioLanguage.empty()) return -1;
    GstStream* chosen = nullptr;
    for (guint i=0; i<gst_stream_collection_get_size(collection); ++i) {
        auto candidate = gst_stream_collection_get_stream(collection, i);
        if (!(gst_stream_get_stream_type(candidate) & GST_STREAM_TYPE_AUDIO)) continue;
        if (!chosen) chosen = candidate;
        auto tags = gst_stream_get_tags(candidate); gchar* language = nullptr;
        if (tags && gst_tag_list_get_string(tags, GST_TAG_LANGUAGE_CODE, &language)) {
            std::string wanted=p->audioLanguage, actual=language;
            if (g_ascii_strcasecmp(actual.c_str(),wanted.c_str())==0 || actual == wanted.substr(0,wanted.find('-'))) { chosen=candidate; g_free(language); gst_tag_list_unref(tags); break; }
            g_free(language);
        }
        if(tags) gst_tag_list_unref(tags);
    }
    return chosen == stream ? 1 : 0;
}
void drainBus(Player& p) {
    while (auto message = gst_bus_pop(p.bus)) {
        if (GST_MESSAGE_TYPE(message) == GST_MESSAGE_ERROR) {
            GError* error = nullptr; gchar* debug = nullptr; gst_message_parse_error(message, &error, &debug);
            p.error = p.cdm && p.cdm->error ? p.cdm->error.load() : (error && error->domain == GST_RESOURCE_ERROR ? 2 : 3);
            if (error) g_error_free(error); g_free(debug);
        } else if (GST_MESSAGE_TYPE(message) == GST_MESSAGE_EOS) p.eos = true;
        else if (GST_MESSAGE_TYPE(message) == GST_MESSAGE_BUFFERING) {
            gst_message_parse_buffering(message, &p.buffering);
            if (!p.live && p.desiredPlaying) gst_element_set_state(p.pipeline, p.buffering < 100 ? GST_STATE_PAUSED : GST_STATE_PLAYING);
        }
        gst_message_unref(message);
    }
}

struct AssRenderer {
    ASS_Library* library = nullptr; ASS_Renderer* renderer = nullptr; ASS_Track* track = nullptr;
    ~AssRenderer() { if (track) ass_free_track(track); if (renderer) ass_renderer_done(renderer); if (library) ass_library_done(library); }
};
std::mutex assLock;
std::map<jlong, std::unique_ptr<AssRenderer>> subtitles;
void discardAssLog(int, const char*, va_list, void*) {}
}

#define JNI(name) extern "C" JNIEXPORT
#define METHOD(name) Java_io_github_kdroidfilter_composemediaplayer_desktop_NativeMediaBridge_##name

JNI(init) jint JNICALL METHOD(nInitialize)(JNIEnv* env, jclass, jstring root) {
    static std::once_flag once; static int initialized = 0;
    auto directory = string(env, root);
    std::call_once(once, [&] {
        // Keep libsoup in the import table, including MSVC builds with dead-code elimination.
        if (soup_get_major_version() != 3) return;
        g_setenv("GST_PLUGIN_SYSTEM_PATH_1_0", "", TRUE);
        g_setenv("GST_PLUGIN_PATH_1_0", (directory + "/plugins").c_str(), TRUE);
        g_setenv("GST_REGISTRY_FORK", "no", TRUE);
        gchar* identity = g_compute_checksum_for_string(G_CHECKSUM_SHA256, directory.c_str(), -1);
        std::string registryDirectory = std::string(g_get_user_cache_dir()) + "/composemediaplayer/gstreamer/" + identity;
        g_free(identity);
        g_mkdir_with_parents(registryDirectory.c_str(), 0700);
        g_setenv("GST_REGISTRY_1_0", (registryDirectory + "/registry.bin").c_str(), TRUE);
        gst_debug_set_active(FALSE);
        GError* error = nullptr;
        if (!gst_init_check(nullptr, nullptr, &error)) { if (error) g_error_free(error); return; }
        gst_debug_set_active(FALSE);
        gst_registry_fork_set_enabled(FALSE);
        gst_registry_scan_path(gst_registry_get(), (directory + "/plugins").c_str());
        if (!gst_element_register(nullptr, "cmpwidevinedecrypt", GST_RANK_PRIMARY + 100, cmp_decrypt_get_type())) return;
        for (auto name : {"playbin3", "dashdemux2", "appsink", "videoconvert", "audioconvert", "avdec_h264", "avdec_aac"}) {
            auto factory = gst_element_factory_find(name); if (!factory) return; gst_object_unref(factory);
        }
        initialized = 1;
    });
    return initialized;
}
JNI(abi) jint JNICALL METHOD(nAbiVersion)(JNIEnv*, jclass) { return 1; }
JNI(create) jlong JNICALL METHOD(nCreate)(JNIEnv* env, jclass, jstring uri, jstring agent, jstring cdmPath, jbyteArray certificate, jboolean testAudio, jstring language) {
    auto p = std::make_shared<Player>(); p->userAgent = string(env, agent); p->audioLanguage = string(env, language);
    auto path = string(env, cdmPath);
    if (!path.empty()) { p->cdm = std::make_unique<WidevineHost>(path, bytes(env, certificate)); if (p->cdm->error) p->error = p->cdm->error.load(); }
    p->pipeline = gst_element_factory_make("playbin3", nullptr);
    if (!p->pipeline) return 0;
    p->sink = gst_element_factory_make("appsink", nullptr);
    if (!p->sink) return 0;
    GstCaps* caps = gst_caps_from_string("video/x-raw,format=BGRA");
    g_object_set(p->sink, "caps", caps, "sync", TRUE, "max-buffers", 2u, "drop", TRUE, "enable-last-sample", FALSE, nullptr); gst_caps_unref(caps);
    GstAppSinkCallbacks callbacks{}; callbacks.new_sample = newSample; callbacks.new_preroll = newPreroll;
    gst_app_sink_set_callbacks(GST_APP_SINK(p->sink), &callbacks, p.get(), nullptr);
    const char* audioSink = testAudio ? "fakesink sync=true" :
#ifdef _WIN32
        "wasapi2sink";
#else
        "osxaudiosink";
#endif
    std::string audio = std::string("audioconvert ! audioresample ! audio/x-raw,format=F32LE,layout=interleaved ! scaletempo ! identity name=pcm ! ") + audioSink;
    GError* error = nullptr; auto audioBin = gst_parse_bin_from_description(audio.c_str(), TRUE, &error);
    if (!audioBin) { if (error) g_error_free(error); return 0; }
    auto identity = gst_bin_get_by_name(GST_BIN(audioBin), "pcm"); auto pad = gst_element_get_static_pad(identity, "src");
    gst_pad_add_probe(pad, GST_PAD_PROBE_TYPE_BUFFER, audioProbe, p.get(), nullptr); gst_object_unref(pad); gst_object_unref(identity);
    g_signal_connect(p->pipeline, "source-setup", G_CALLBACK(sourceSetup), p.get());
    g_signal_connect(p->pipeline, "element-setup", G_CALLBACK(elementSetup), p.get());
    g_object_set(p->pipeline, "uri", string(env, uri).c_str(), "video-sink", p->sink, "audio-sink", audioBin, nullptr);
    // playbin owns sinks after the floating refs are consumed by its properties.
    p->bus = gst_element_get_bus(p->pipeline);
    p->live = gst_element_set_state(p->pipeline, GST_STATE_PAUSED) == GST_STATE_CHANGE_NO_PREROLL;
    auto id = nextId++; { std::lock_guard<std::mutex> lock(playersLock); players[id] = p; } return id;
}
JNI(cancel) void JNICALL METHOD(nCancel)(JNIEnv*, jclass, jlong id) { auto p = player(id); if (p) { p->cancelled = true; if (p->cdm) p->cdm->cancel(); } }
JNI(close) void JNICALL METHOD(nClose)(JNIEnv*, jclass, jlong id) {
    std::shared_ptr<Player> p;
    { std::lock_guard<std::mutex> lock(playersLock); auto i = players.find(id); if (i != players.end()) { p = std::move(i->second); players.erase(i); } }
}
JNI(play) void JNICALL METHOD(nSetPlaying)(JNIEnv*, jclass, jlong id, jboolean playing) {
    auto p = player(id); if (!p || p->cancelled) return;
    p->desiredPlaying = playing;
    gst_element_set_state(p->pipeline, playing && (p->live || p->buffering == 100) ? GST_STATE_PLAYING : GST_STATE_PAUSED);
}
JNI(volume) void JNICALL METHOD(nSetVolume)(JNIEnv*, jclass, jlong id, jdouble value) { auto p = player(id); if (p) g_object_set(p->pipeline, "volume", std::clamp(double(value), 0.0, 1.0), nullptr); }
JNI(seek) jboolean JNICALL METHOD(nSeek)(JNIEnv*, jclass, jlong id, jdouble seconds, jdouble rate) {
    auto p = player(id); if (!p || p->cancelled || !std::isfinite(seconds) || !std::isfinite(rate) || rate <= 0) return FALSE;
    p->eos = false;
    return gst_element_seek(p->pipeline, rate, GST_FORMAT_TIME, GstSeekFlags(GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_ACCURATE),
        GST_SEEK_TYPE_SET, static_cast<gint64>(std::max(0.0, double(seconds)) * GST_SECOND), GST_SEEK_TYPE_NONE, GST_CLOCK_TIME_NONE);
}
JNI(snapshot) jdoubleArray JNICALL METHOD(nSnapshot)(JNIEnv* env, jclass, jlong id) {
    auto p = player(id); if (!p) return nullptr; drainBus(*p);
    gint64 position = 0, duration = 0; gst_element_query_position(p->pipeline, GST_FORMAT_TIME, &position); gst_element_query_duration(p->pipeline, GST_FORMAT_TIME, &duration);
    GstState state = GST_STATE_NULL; gst_element_get_state(p->pipeline, &state, nullptr, 0);
    std::lock_guard<std::mutex> lock(p->frameLock);
    double data[] = { std::max<gint64>(0, position) / double(GST_SECOND), std::max<gint64>(0, duration) / double(GST_SECOND),
        state == GST_STATE_PLAYING ? 1.0 : 0.0, double(p->cdm && p->cdm->error ? p->cdm->error.load() : p->error.load()),
        p->eos ? 1.0 : 0.0, double(p->width), double(p->height), double(p->audioBuffers), double(p->audioFrames), p->audioPeak,
        double(p->channels), double(p->sampleRate), double(p->parN), double(p->parD), double(p->sequence), double(p->buffering) };
    auto result = env->NewDoubleArray(16); env->SetDoubleArrayRegion(result, 0, 16, data); return result;
}
JNI(frame) jbyteArray JNICALL METHOD(nReadFrame)(JNIEnv* env, jclass, jlong id, jlong previous, jlongArray out) {
    auto p = player(id); if (!p || env->GetArrayLength(out) < 7) return nullptr;
    std::lock_guard<std::mutex> lock(p->frameLock);
    if (p->frame.empty() || p->sequence == uint64_t(previous)) return nullptr;
    jlong info[] = {p->width, p->height, p->width * 4, jlong(p->sequence), jlong(p->pts), p->parN, p->parD};
    env->SetLongArrayRegion(out, 0, 7, info); return byteArray(env, p->frame);
}
JNI(ranges) jdoubleArray JNICALL METHOD(nBufferedRanges)(JNIEnv* env, jclass, jlong id) {
    auto p = player(id); if (!p) return nullptr;
    auto query = gst_query_new_buffering(GST_FORMAT_TIME); std::vector<double> ranges;
    bool supported = gst_element_query(p->pipeline, query);
    if (supported) for (guint i = 0; i < gst_query_get_n_buffering_ranges(query); ++i) {
        gint64 start, stop; if (gst_query_parse_nth_buffering_range(query, i, &start, &stop) && start >= 0 && stop > start) {
            ranges.push_back(start / double(GST_SECOND)); ranges.push_back(stop / double(GST_SECOND));
        }
    }
    gst_query_unref(query); if (!supported) return nullptr;
    auto result = env->NewDoubleArray(static_cast<jsize>(ranges.size())); if (!ranges.empty()) env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(ranges.size()), ranges.data()); return result;
}
JNI(license) jbyteArray JNICALL METHOD(nPollLicense)(JNIEnv* env, jclass, jlong id) {
    auto p = player(id); LicenseMessage message;
    if (!p || !p->cdm || !p->cdm->poll(message)) return nullptr;
    std::vector<uint8_t> data(8 + message.bytes.size());
    for (int i = 0; i < 4; ++i) { data[i] = uint8_t(message.id >> (24 - i * 8)); data[4 + i] = uint8_t(message.type >> (24 - i * 8)); }
    std::copy(message.bytes.begin(), message.bytes.end(), data.begin() + 8); return byteArray(env, data);
}
JNI(updateLicense) void JNICALL METHOD(nUpdateLicense)(JNIEnv* env, jclass, jlong id, jint token, jbyteArray data) {
    auto p = player(id); if (p && p->cdm) p->cdm->license(token, bytes(env, data));
}
JNI(licenseFailed) void JNICALL METHOD(nLicenseFailed)(JNIEnv*, jclass, jlong id) { auto p = player(id); if (p && p->cdm) p->cdm->fail(12); }
JNI(livePlayers) jint JNICALL METHOD(nLivePlayers)(JNIEnv*, jclass) { std::lock_guard<std::mutex> lock(playersLock); return static_cast<jint>(players.size()); }

JNI(assCreate) jlong JNICALL METHOD(nAssCreate)(JNIEnv* env, jclass, jbyteArray data, jstring font) {
    auto content = bytes(env, data); auto path = string(env, font);
    auto p = std::make_unique<AssRenderer>(); p->library = ass_library_init(); if (!p->library) return 0;
    ass_set_message_cb(p->library, discardAssLog, nullptr);
    p->renderer = ass_renderer_init(p->library); if (!p->renderer) return 0;
    ass_set_fonts(p->renderer, path.empty() ? nullptr : path.c_str(), "sans-serif", ASS_FONTPROVIDER_AUTODETECT, nullptr, 1);
    p->track = ass_read_memory(p->library, reinterpret_cast<char*>(content.data()), content.size(), nullptr); if (!p->track) return 0;
    auto id = nextId++; std::lock_guard<std::mutex> lock(assLock); subtitles[id] = std::move(p); return id;
}
JNI(assClose) void JNICALL METHOD(nAssClose)(JNIEnv*, jclass, jlong id) { std::lock_guard<std::mutex> lock(assLock); subtitles.erase(id); }
JNI(assRender) jbyteArray JNICALL METHOD(nAssRender)(JNIEnv* env, jclass, jlong id, jlong time, jint width, jint height) {
    if (width <= 0 || height <= 0 || uint64_t(width) * height > 16 * 1024 * 1024) return nullptr;
    std::lock_guard<std::mutex> lock(assLock); auto item = subtitles.find(id); if (item == subtitles.end()) return nullptr;
    auto& p = *item->second; ass_set_frame_size(p.renderer, width, height);
    int changed = 0; auto image = ass_render_frame(p.renderer, p.track, time, &changed);
    std::vector<uint8_t> output(size_t(width) * height * 4, 0);
    for (; image; image = image->next) {
        const unsigned opacity = 255 - (image->color & 255);
        const unsigned color[] = {(image->color >> 8) & 255, (image->color >> 16) & 255, (image->color >> 24) & 255};
        for (int y = 0; y < image->h; ++y) for (int x = 0; x < image->w; ++x) {
            int dx = image->dst_x + x, dy = image->dst_y + y;
            if (dx < 0 || dy < 0 || dx >= width || dy >= height) continue;
            unsigned alpha = (unsigned(image->bitmap[size_t(y) * image->stride + x]) * opacity + 127) / 255;
            auto pixel = output.data() + (size_t(dy) * width + dx) * 4;
            for (int c = 0; c < 3; ++c) pixel[c] = uint8_t((color[c] * alpha + unsigned(pixel[c]) * (255 - alpha) + 127) / 255);
            pixel[3] = uint8_t(alpha + (unsigned(pixel[3]) * (255 - alpha) + 127) / 255);
        }
    }
    return byteArray(env, output);
}
