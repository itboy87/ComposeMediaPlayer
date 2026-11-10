#include "WidevineHost.h"
#include <algorithm>
#include <cstring>
#include <stdexcept>
#ifdef _WIN32
#include <windows.h>
#else
#include <dlfcn.h>
#endif

struct WidevineHost::Module {
#ifdef _WIN32
    HMODULE handle;
    void* symbol(const char* name) { return reinterpret_cast<void*>(GetProcAddress(handle, name)); }
#else
    void* handle;
    void* symbol(const char* name) { return dlsym(handle, name); }
#endif
    using Create = void* (*)(int, const char*, uint32_t, GetCdmHostFunc, void*);
    Create create;
    explicit Module(const std::string& path) {
#ifdef _WIN32
        int size = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), -1, nullptr, 0);
        std::vector<wchar_t> wide(size);
        MultiByteToWideChar(CP_UTF8, 0, path.c_str(), -1, wide.data(), size);
        handle = LoadLibraryExW(wide.data(), nullptr, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
#else
        handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
#endif
        if (!handle) throw std::runtime_error("CDM load failed");
        auto initialize = reinterpret_cast<void (*)()>(symbol("InitializeCdmModule_4"));
        create = reinterpret_cast<Create>(symbol("CreateCdmInstance"));
        if (!initialize || !create || !symbol("DeinitializeCdmModule")) { unload(); throw std::runtime_error("CDM ABI mismatch"); }
        initialize();
    }
    void unload() {
        if (!handle) return;
#ifdef _WIN32
        FreeLibrary(handle);
#else
        dlclose(handle);
#endif
        handle = nullptr;
    }
    ~Module() { if (handle) reinterpret_cast<void (*)()>(symbol("DeinitializeCdmModule"))(); unload(); }
    static std::shared_ptr<Module> load(const std::string& path) {
        static std::mutex lock;
        static std::map<std::string, std::weak_ptr<Module>> modules;
        std::lock_guard<std::mutex> guard(lock);
        auto result = modules[path].lock();
        if (!result) { result = std::make_shared<Module>(path); modules[path] = result; }
        return result;
    }
};

class OwnedCdmBuffer final : public cdm::Buffer {
    std::vector<uint8_t> bytes;
    uint32_t size = 0;
public:
    explicit OwnedCdmBuffer(uint32_t capacity) : bytes(capacity) {}
    void Destroy() override { delete this; }
    uint32_t Capacity() const override { return static_cast<uint32_t>(bytes.size()); }
    uint8_t* Data() override { return bytes.data(); }
    void SetSize(uint32_t value) override { size = std::min(value, Capacity()); }
    uint32_t Size() const override { return size; }
};
class OwnedDecryptedBlock final : public cdm::DecryptedBlock {
    cdm::Buffer* buffer = nullptr;
    int64_t timestamp = 0;
public:
    ~OwnedDecryptedBlock() { if (buffer) buffer->Destroy(); }
    void SetDecryptedBuffer(cdm::Buffer* value) override { if (buffer && buffer != value) buffer->Destroy(); buffer = value; }
    cdm::Buffer* DecryptedBuffer() override { return buffer; }
    void SetTimestamp(int64_t value) override { timestamp = value; }
    int64_t Timestamp() const override { return timestamp; }
};

void* WidevineHost::host(int version, void* self) {
    auto p = static_cast<WidevineHost*>(self);
    if (version == 11) return static_cast<cdm::Host_11*>(p);
    if (version == 10) return static_cast<cdm::Host_10*>(p);
    return nullptr;
}
WidevineHost::WidevineHost(const std::string& path, const std::vector<uint8_t>& certificate) {
    worker = std::thread([this] { run(); });
    sync([this, path, certificate] {
        try {
            module = Module::load(path);
            v11 = static_cast<cdm::ContentDecryptionModule_11*>(module->create(11, "com.widevine.alpha", 18, host, this));
            if (!v11) v10 = static_cast<cdm::ContentDecryptionModule_10*>(module->create(10, "com.widevine.alpha", 18, host, this));
            if (!v11 && !v10) { fail(11); return; }
            pendingCertificate = certificate;
            certificateReady = certificate.empty();
            if (v11) v11->Initialize(false, false, false); else v10->Initialize(false, false, false);

        } catch (...) { fail(11); }
    });
}
void WidevineHost::post(std::function<void()> job) {
    std::lock_guard<std::mutex> guard(mutex);
    if (!stopping) jobs.push_back(std::move(job));
    changed.notify_all();
}
void WidevineHost::sync(std::function<void()> job) {
    auto complete = std::make_shared<std::promise<void>>();
    auto future = complete->get_future();
    post([job = std::move(job), complete] { try { job(); complete->set_value(); } catch (...) { complete->set_exception(std::current_exception()); } });
    future.get();
}
void WidevineHost::run() {
    std::unique_lock<std::mutex> lock(mutex);
    while (!stopping) {
        if (!jobs.empty()) {
            auto job = std::move(jobs.front()); jobs.pop_front();
            lock.unlock(); job(); lock.lock();
        } else if (!timers.empty() && timers.begin()->first <= std::chrono::steady_clock::now()) {
            void* context = timers.begin()->second; timers.erase(timers.begin());
            lock.unlock();
            if (!cancelled) { if (v11) v11->TimerExpired(context); else if (v10) v10->TimerExpired(context); }
            lock.lock();
        } else if (timers.empty()) changed.wait(lock);
        else changed.wait_until(lock, timers.begin()->first);
    }
}
void WidevineHost::cancel() { cancelled = true; changed.notify_all(); }
WidevineHost::~WidevineHost() {
    cancel();
    sync([this] {
        auto copy = sessions;
        for (const auto& session : copy) {
            if (v11) v11->CloseSession(promise++, session.data(), static_cast<uint32_t>(session.size()));
            else if (v10) v10->CloseSession(promise++, session.data(), static_cast<uint32_t>(session.size()));
        }
        if (v11) v11->Destroy(); if (v10) v10->Destroy();
        v11 = nullptr; v10 = nullptr; module.reset();
    });
    { std::lock_guard<std::mutex> guard(mutex); stopping = true; jobs.clear(); timers.clear(); messages.clear(); pending.clear(); }
    changed.notify_all(); worker.join();
}
void WidevineHost::initData(const std::vector<uint8_t>& data) {
    post([this, data] {
        if (cancelled || error || !initializationData.insert(data).second) return;
        if (!initialized || !certificateReady) { waitingInitialization.push_back(data); initializationData.erase(data); return; }
        if (v11) v11->CreateSessionAndGenerateRequest(promise++, cdm::kTemporary, cdm::kCenc, data.data(), static_cast<uint32_t>(data.size()));
        else v10->CreateSessionAndGenerateRequest(promise++, cdm::kTemporary, cdm::kCenc, data.data(), static_cast<uint32_t>(data.size()));
    });
}
bool WidevineHost::poll(LicenseMessage& message) {
    std::lock_guard<std::mutex> guard(mutex);
    if (messages.empty() || cancelled) return false;
    message = std::move(messages.front()); messages.pop_front(); pending[message.id] = message; return true;
}
void WidevineHost::license(uint32_t id, const std::vector<uint8_t>& bytes) {
    std::string session;
    { std::lock_guard<std::mutex> guard(mutex); auto item = pending.find(id); if (item == pending.end()) return;
      session = item->second.session; pending.erase(item); }
    post([this, session, bytes] {
        if (cancelled || error) return;
        if (v11) v11->UpdateSession(promise++, session.data(), static_cast<uint32_t>(session.size()), bytes.data(), static_cast<uint32_t>(bytes.size()));
        else v10->UpdateSession(promise++, session.data(), static_cast<uint32_t>(session.size()), bytes.data(), static_cast<uint32_t>(bytes.size()));
    });
}
cdm::Status WidevineHost::decrypt(const cdm::InputBuffer_2& input, std::vector<uint8_t>& output) {
    auto status = cdm::kDecryptError;
    sync([this, &input, &output, &status] {
        if (cancelled || error || !initialized) return;
        OwnedDecryptedBlock block;
        status = v11 ? v11->Decrypt(input, &block) : v10->Decrypt(input, &block);
        if (status == cdm::kSuccess) {
            auto buffer = block.DecryptedBuffer();
            if (!buffer || buffer->Size() != input.data_size) { status = cdm::kDecryptError; return; }
            output.assign(buffer->Data(), buffer->Data() + buffer->Size());
        }
    });
    return status;
}
cdm::Buffer* WidevineHost::Allocate(uint32_t capacity) {
    if (capacity > 128 * 1024 * 1024) return nullptr;
    try { return new OwnedCdmBuffer(capacity); } catch (...) { return nullptr; }
}
void WidevineHost::SetTimer(int64_t delay, void* context) {
    std::lock_guard<std::mutex> guard(mutex);
    timers.emplace(std::chrono::steady_clock::now() + std::chrono::milliseconds(std::max<int64_t>(0, delay)), context);
    changed.notify_all();
}
cdm::Time WidevineHost::GetCurrentWallTime() { return std::chrono::duration<double>(std::chrono::system_clock::now().time_since_epoch()).count(); }
void WidevineHost::drainInitialization() {
    auto waiting = std::move(waitingInitialization);
    waitingInitialization.clear();
    for (const auto& data : waiting) initData(data);
}
void WidevineHost::OnInitialized(bool success) {
    initialized = success;
    if (!success) { fail(11); return; }
    // No subsequent CDM call may occur until OnInitialized has returned.
    post([this] {
        if (cancelled || error) return;
        if (!pendingCertificate.empty()) {
            certificatePromise = promise++;
            if (v11) v11->SetServerCertificate(certificatePromise, pendingCertificate.data(), static_cast<uint32_t>(pendingCertificate.size()));
            else v10->SetServerCertificate(certificatePromise, pendingCertificate.data(), static_cast<uint32_t>(pendingCertificate.size()));
        } else drainInitialization();
    });
}
void WidevineHost::OnResolvePromise(uint32_t id) {
    if (certificatePromise && id == certificatePromise) { certificateReady = true; drainInitialization(); }
}
void WidevineHost::OnResolveNewSessionPromise(uint32_t, const char* id, uint32_t size) { sessions.emplace(id, size); }
void WidevineHost::OnSessionClosed(const char* id, uint32_t size) { sessions.erase(std::string(id, size)); }
void WidevineHost::OnSessionMessage(const char* session, uint32_t sessionSize, cdm::MessageType type, const char* bytes, uint32_t size) {
    if (cancelled) return;
    if (type != cdm::kLicenseRequest && type != cdm::kLicenseRenewal) { fail(14); return; }
    if (size > 4 * 1024 * 1024) { fail(12); return; }
    std::lock_guard<std::mutex> guard(mutex);
    messages.push_back({messageId++, static_cast<uint32_t>(type), std::string(session, sessionSize), std::vector<uint8_t>(bytes, bytes + size)});
}
void WidevineHost::OnSessionKeysChange(const char*, uint32_t, bool, const cdm::KeyInformation* keys, uint32_t count) {
    for (uint32_t i = 0; i < count; ++i) {
        if (keys[i].status == cdm::kOutputRestricted || keys[i].status == cdm::kOutputDownscaled) fail(14);
        if (keys[i].status == cdm::kExpired) fail(13);
        if (keys[i].status == cdm::kInternalError) fail(15);
    }
    changed.notify_all();
}
void WidevineHost::OnExpirationChange(const char*, uint32_t, cdm::Time expiry) { if (expiry > 0 && expiry <= GetCurrentWallTime()) fail(13); }
void WidevineHost::SendPlatformChallenge(const char*, uint32_t, const char*, uint32_t) {
    post([this] { cdm::PlatformChallengeResponse response{}; if (v11) v11->OnPlatformChallengeResponse(response); else if (v10) v10->OnPlatformChallengeResponse(response); });
}
void WidevineHost::QueryOutputProtectionStatus() {
    post([this] { if (v11) v11->OnQueryOutputProtectionStatus(cdm::kQueryFailed, 0, 0); else if (v10) v10->OnQueryOutputProtectionStatus(cdm::kQueryFailed, 0, 0); });
}
void WidevineHost::RequestStorageId(uint32_t version) {
    post([this, version] { if (v11) v11->OnStorageId(version, nullptr, 0); else if (v10) v10->OnStorageId(version, nullptr, 0); });
}
