#include <jni.h>
#include "NitroVtoOnLoad.hpp"

namespace filament {
class VirtualMachineEnv {
 public:
  static jint JNI_OnLoad(JavaVM* vm);
};
} // namespace filament

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  filament::VirtualMachineEnv::JNI_OnLoad(vm);
  return margelo::nitro::nitrovto::initialize(vm);
}
