#include <jni.h>
#include <node.h>
#include <cstdlib>
#include <cstring>
#include <vector>
#include <string>

// Node's libuv requires argv strings in one contiguous allocation, kept for its entire lifetime.
extern "C" JNIEXPORT jint JNICALL
Java_top_izuna_folia_android_NodeMusicRuntime_startNode(JNIEnv* env, jclass, jobjectArray arguments) {
    const jsize count = env->GetArrayLength(arguments);
    std::vector<std::string> strings;
    size_t bytes = 0;
    for (jsize i = 0; i < count; i++) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
        const char* chars = env->GetStringUTFChars(value, nullptr);
        strings.emplace_back(chars);
        bytes += strings.back().size() + 1;
        env->ReleaseStringUTFChars(value, chars);
        env->DeleteLocalRef(value);
    }
    char* storage = static_cast<char*>(std::calloc(bytes, 1));
    if (!storage) return -1;
    std::vector<char*> argv(count + 1, nullptr);
    char* cursor = storage;
    for (jsize i = 0; i < count; i++) {
        argv[i] = cursor;
        std::memcpy(cursor, strings[i].c_str(), strings[i].size());
        cursor += strings[i].size() + 1;
    }
    const int result = node::Start(count, argv.data());
    std::free(storage);
    return result;
}
