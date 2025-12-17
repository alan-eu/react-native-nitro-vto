#include <jni.h>
#include "NitroVtoOnLoad.hpp"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    return margelo::nitro::nitrovto::initialize(vm);
}
