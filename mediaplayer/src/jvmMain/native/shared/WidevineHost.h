#pragma once
#include "vendor/content_decryption_module.h"
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <functional>
#include <future>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <vector>

struct LicenseMessage { uint32_t id; uint32_t type; std::string session; std::vector<uint8_t> bytes; };

// All CDM entrypoints, timers and destruction execute on one owned thread. Network I/O never
// executes on this thread: the JVM polls messages and returns the corresponding raw response.
class WidevineHost final : public cdm::Host_11, public cdm::Host_10 {
public:
    explicit WidevineHost(const std::string& path, const std::vector<uint8_t>& certificate);
    ~WidevineHost();
    void cancel();
    void initData(const std::vector<uint8_t>& data);
    bool poll(LicenseMessage& message);
    void license(uint32_t id, const std::vector<uint8_t>& bytes);
    void fail(int code) { error.store(code); changed.notify_all(); }
    cdm::Status decrypt(const cdm::InputBuffer_2& input, std::vector<uint8_t>& output);
    std::atomic<int> error{0};
    std::atomic<bool> cancelled{false};
private:
    struct Module;
    std::shared_ptr<Module> module;
    cdm::ContentDecryptionModule_11* v11 = nullptr;
    cdm::ContentDecryptionModule_10* v10 = nullptr;
    std::mutex mutex;
    std::condition_variable changed;
    std::deque<std::function<void()>> jobs;
    std::deque<LicenseMessage> messages;
    std::map<uint32_t, LicenseMessage> pending;
    std::set<std::vector<uint8_t>> initializationData;
    std::set<std::string> sessions;
    std::multimap<std::chrono::steady_clock::time_point, void*> timers;
    std::thread worker;
    bool stopping = false;
    bool initialized = false;
    bool certificateReady = true;
    uint32_t certificatePromise = 0;
    std::vector<uint8_t> pendingCertificate;
    void drainInitialization();
    std::vector<std::vector<uint8_t>> waitingInitialization;
    uint32_t promise = 1;
    uint32_t messageId = 1;
    void run();
    void post(std::function<void()> job);
    void sync(std::function<void()> job);
    static void* host(int version, void* self);

    cdm::Buffer* Allocate(uint32_t capacity) override;
    void SetTimer(int64_t delay, void* context) override;
    cdm::Time GetCurrentWallTime() override;
    void OnInitialized(bool success) override;
    void OnResolveKeyStatusPromise(uint32_t, cdm::KeyStatus) override {}
    void OnResolveNewSessionPromise(uint32_t, const char*, uint32_t) override;
    void OnResolvePromise(uint32_t id) override;
    void OnRejectPromise(uint32_t, cdm::Exception, uint32_t, const char*, uint32_t) override { fail(12); }
    void OnSessionMessage(const char*, uint32_t, cdm::MessageType, const char*, uint32_t) override;
    void OnSessionKeysChange(const char*, uint32_t, bool, const cdm::KeyInformation*, uint32_t) override;
    void OnExpirationChange(const char*, uint32_t, cdm::Time) override;
    void OnSessionClosed(const char*, uint32_t) override;
    void SendPlatformChallenge(const char*, uint32_t, const char*, uint32_t) override;
    void EnableOutputProtection(uint32_t mask) override { if (mask) fail(14); }
    void QueryOutputProtectionStatus() override;
    void OnDeferredInitializationDone(cdm::StreamType, cdm::Status) override {}
    cdm::FileIO* CreateFileIO(cdm::FileIOClient*) override { return nullptr; }
    void RequestStorageId(uint32_t version) override;
    void ReportMetrics(cdm::MetricName, uint64_t) override {}
};
