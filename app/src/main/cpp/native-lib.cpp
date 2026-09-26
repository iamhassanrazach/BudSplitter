#include <jni.h>
#include <oboe/Oboe.h>
#include <cmath>
#include <vector>
#include <memory>
#include <atomic>
#include <algorithm>
#include <android/log.h>

#define TAG "BudSplitterNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

class JitterAudioBuffer {
public:
    explicit JitterAudioBuffer(size_t capacity)
            : mBuffer(capacity, 0.0f), mCapacity(capacity), mWritePos(0), mReadPos(0.0) {}

    void write(const float* data, size_t frames) {
        if (!data || frames == 0) return;
        for (size_t i = 0; i < frames; ++i) {
            size_t w = mWritePos.load(std::memory_order_relaxed);
            mBuffer[w] = data[i];
            mWritePos.store((w + 1) % mCapacity, std::memory_order_release);
        }
    }

    void read(float* outData, size_t frames) {
        if (!outData || frames == 0) return;

        size_t w = mWritePos.load(std::memory_order_acquire);
        double r = mReadPos.load(std::memory_order_relaxed);

        double occupancy = static_cast<double>(w) - r;
        if (occupancy < 0) occupancy += static_cast<double>(mCapacity);

        constexpr double targetOccupancy = 384.0;
        double drift = (occupancy - targetOccupancy) / 10000.0;
        drift = std::clamp(drift, -0.05, 0.05);
        double step = 1.0 + drift;

        for (size_t i = 0; i < frames; ++i) {
            size_t idx0 = static_cast<size_t>(r) % mCapacity;
            size_t idx1 = (idx0 + 1) % mCapacity;
            double frac = r - std::floor(r);

            outData[i] = static_cast<float>(mBuffer[idx0] * (1.0 - frac) + mBuffer[idx1] * frac);

            r += step;
            if (r >= static_cast<double>(mCapacity)) {
                r -= static_cast<double>(mCapacity);
            }
        }
        mReadPos.store(r, std::memory_order_release);
    }

private:
    std::vector<float> mBuffer;
    const size_t mCapacity;
    std::atomic<size_t> mWritePos;
    std::atomic<double> mReadPos;
};

class SplitAudioEngine : public oboe::AudioStreamDataCallback {
public:
    SplitAudioEngine() : mMicBuffer(16384) {
        mScratchMic.resize(2048, 0.0f);
    }

    ~SplitAudioEngine() {
        stop();
    }

    bool start() {
        // 1. Input Stream (Microphone)
        oboe::AudioStreamBuilder inBuilder;
        inBuilder.setDirection(oboe::Direction::Input)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(oboe::SharingMode::Shared)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(oboe::ChannelCount::Mono)
                ->setInputPreset(oboe::InputPreset::VoiceRecognition)
                ->setDataCallback(&mInputCallback);

        oboe::Result res = inBuilder.openStream(recordingStream);
        if (res != oboe::Result::OK) {
            LOGE("Failed to open input stream: %s", oboe::convertToText(res));
            return false;
        }

        int32_t inSampleRate = recordingStream->getSampleRate();

        // 2. Output Stream (Standard High-Quality Stereo Media Channel)
        oboe::AudioStreamBuilder outBuilder;
        outBuilder.setDirection(oboe::Direction::Output)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(oboe::SharingMode::Shared)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(oboe::ChannelCount::Stereo)
                ->setSampleRate(inSampleRate)
                ->setUsage(oboe::Usage::Media)
                ->setContentType(oboe::ContentType::Music)
                ->setDataCallback(this);

        res = outBuilder.openStream(playbackStream);
        if (res != oboe::Result::OK) {
            LOGE("Failed to open output stream: %s", oboe::convertToText(res));
            recordingStream->close();
            recordingStream.reset();
            return false;
        }

        mInputCallback.setBuffer(&mMicBuffer);

        res = playbackStream->start();
        if (res != oboe::Result::OK) {
            LOGE("Failed to start playback stream: %s", oboe::convertToText(res));
            playbackStream->close();
            playbackStream.reset();
            recordingStream->close();
            recordingStream.reset();
            return false;
        }

        res = recordingStream->start();
        if (res != oboe::Result::OK) {
            LOGE("Failed to start recording stream: %s", oboe::convertToText(res));
            playbackStream->stop();
            playbackStream->close();
            playbackStream.reset();
            recordingStream->close();
            recordingStream.reset();
            return false;
        }

        LOGI("SplitAudioEngine started successfully at %d Hz", inSampleRate);
        return true;
    }

    void stop() {
        if (recordingStream) {
            recordingStream->stop();
            recordingStream->close();
            recordingStream.reset();
        }
        if (playbackStream) {
            playbackStream->stop();
            playbackStream->close();
            playbackStream.reset();
        }
        LOGI("SplitAudioEngine stopped safely.");
    }

    void setMicGain(float gain) {
        mMicGain.store(gain, std::memory_order_relaxed);
    }

    void setRoutingMode(int mode) {
        mRoutingMode.store(mode, std::memory_order_relaxed);
    }

    class EngineInputCallback : public oboe::AudioStreamDataCallback {
    public:
        void setBuffer(JitterAudioBuffer* buf) { mBuf = buf; }

        oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) override {
            if (mBuf && audioData && numFrames > 0) {
                mBuf->write(static_cast<const float*>(audioData), static_cast<size_t>(numFrames));
            }
            return oboe::DataCallbackResult::Continue;
        }
    private:
        JitterAudioBuffer* mBuf = nullptr;
    } mInputCallback;

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) override {
        auto* outputBuffer = static_cast<float*>(audioData);
        if (!outputBuffer || numFrames <= 0) {
            return oboe::DataCallbackResult::Continue;
        }

        size_t framesToRead = static_cast<size_t>(numFrames);
        if (framesToRead > mScratchMic.size()) {
            framesToRead = mScratchMic.size();
        }

        mMicBuffer.read(mScratchMic.data(), framesToRead);

        float gain = mMicGain.load(std::memory_order_relaxed);
        int mode = mRoutingMode.load(std::memory_order_relaxed);

        for (size_t i = 0; i < framesToRead; ++i) {
            float rawSample = mScratchMic[i] * gain;
            if (std::abs(rawSample) < 1.0e-15f) rawSample = 0.0f;
            float mic = std::tanh(rawSample);

            if (mode == 0) {
                // SPLIT: Left receives 0 from engine (media routes here), Right receives ambient mic
                outputBuffer[i * 2]     = 0.0f;
                outputBuffer[i * 2 + 1] = mic;
            } else {
                // DUAL: Ambient mic broadcast to both ears
                outputBuffer[i * 2]     = mic * 0.7f;
                outputBuffer[i * 2 + 1] = mic * 0.7f;
            }
        }

        return oboe::DataCallbackResult::Continue;
    }

private:
    std::shared_ptr<oboe::AudioStream> recordingStream;
    std::shared_ptr<oboe::AudioStream> playbackStream;
    JitterAudioBuffer mMicBuffer;
    std::vector<float> mScratchMic;
    std::atomic<float> mMicGain{2.0f};
    std::atomic<int> mRoutingMode{0};
};

static std::unique_ptr<SplitAudioEngine> gEngine;

extern "C" JNIEXPORT void JNICALL
Java_com_example_budsplitter_MainActivity_nativeStartAudio(JNIEnv*, jobject) {
    if (!gEngine) {
        gEngine = std::make_unique<SplitAudioEngine>();
        if (!gEngine->start()) {
            gEngine.reset();
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_budsplitter_MainActivity_nativeStopAudio(JNIEnv*, jobject) {
    if (gEngine) {
        gEngine->stop();
        gEngine.reset();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_budsplitter_MainActivity_nativeSetMicGain(JNIEnv*, jobject, jfloat gain) {
    if (gEngine) {
        gEngine->setMicGain(gain);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_budsplitter_MainActivity_nativeSetRoutingMode(JNIEnv*, jobject, jint mode) {
    if (gEngine) {
        gEngine->setRoutingMode(mode);
    }
}