#include <jni.h>
#include "GlassesVTOOnLoad.hpp"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    return margelo::nitro::glassesvto::initialize(vm);
}
