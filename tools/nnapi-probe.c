#include <android/NeuralNetworks.h>
#include <android/NeuralNetworksTypes.h>
#include <stdint.h>
#include <stdio.h>

static const char *type_name(int32_t type) {
  switch (type) {
  case ANEURALNETWORKS_DEVICE_UNKNOWN: return "UNKNOWN";
  case ANEURALNETWORKS_DEVICE_OTHER: return "OTHER";
  case ANEURALNETWORKS_DEVICE_CPU: return "CPU";
  case ANEURALNETWORKS_DEVICE_GPU: return "GPU";
  case ANEURALNETWORKS_DEVICE_ACCELERATOR: return "ACCELERATOR";
  default: return "UNRECOGNIZED";
  }
}

int main(void) {
  uint32_t count = 0;
  int result = ANeuralNetworks_getDeviceCount(&count);
  if (result != ANEURALNETWORKS_NO_ERROR) {
    fprintf(stderr, "getDeviceCount failed: %d\n", result);
    return 1;
  }

  printf("nnapi_device_count=%u\n", count);
  for (uint32_t i = 0; i < count; ++i) {
    ANeuralNetworksDevice *device = NULL;
    const char *name = "<unavailable>";
    const char *version = "<unavailable>";
    int32_t type = -1;
    int64_t feature_level = -1;

    result = ANeuralNetworks_getDevice(i, &device);
    if (result != ANEURALNETWORKS_NO_ERROR) {
      printf("device[%u] getDevice_error=%d\n", i, result);
      continue;
    }
    int name_result = ANeuralNetworksDevice_getName(device, &name);
    int type_result = ANeuralNetworksDevice_getType(device, &type);
    int version_result = ANeuralNetworksDevice_getVersion(device, &version);
    int feature_result =
        ANeuralNetworksDevice_getFeatureLevel(device, &feature_level);

    printf("device[%u] name=%s type=%s(%d) version=%s feature_level=%lld "
           "status={name:%d,type:%d,version:%d,feature:%d}\n",
           i, name, type_name(type), type, version, (long long)feature_level,
           name_result, type_result, version_result, feature_result);
  }
  return 0;
}
