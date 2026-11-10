// Placeholder for the game's companion libraries (libfmod.so, libMediaDecoders_Android.so,
// libMallocRedirect.so). Each is built from this file with PH_LIBRARY_NAME set; it only reports
// when it is loaded, so the E2E runner can check that the launcher loaded every game library.
// libfmod.so additionally links generated/jni_fmod.cpp for the org.fmod natives.
#include "jni_log.h"

#ifndef PH_LIBRARY_NAME
#error "PH_LIBRARY_NAME must be defined by the build"
#endif

namespace {

__attribute__((constructor)) void onLibraryLoaded() {
    PH_LOGI("lib loaded %s", PH_LIBRARY_NAME);
}

}  // namespace
