# Frontend numerical fixtures

`upstream-80000.f32be` and `upstream-12345.f32be` contain all 64,000
bin-major output features from Google's unmodified `MelSpectroProcessor.kt`
in the Parakeet Android sample from
[`google-ai-edge/litert-samples` at `f1e415713f0354920c263c66e65ebd4b138c7e67`](https://github.com/google-ai-edge/litert-samples/tree/f1e415713f0354920c263c66e65ebd4b138c7e67).
Each file is big-endian IEEE 754 float32.
The files were generated on JDK 21 with the sample's bundled
`jlibrosa-1.1.8-SNAPSHOT-jar-with-dependencies.jar` (SHA-256
`dc6ec9537f6e1e4f300b4021e214cc7f22bc661161d3a64ecd57b6a113a6a077`).
These are DSP fixtures; generating them does not execute a model.

Configuration, taken from the sample's `model_metadata.json`:

```kotlin
MelSpectroProcessor(
    16000,
    LogMelSpectroConfig(
        nFFT = 512, nMels = 128, hopLength = 160,
        nFrames = 500, preemphasis = 0.97f,
        transpose = false, normType = "standard",
    ),
)
```

To regenerate, compile the upstream `MelSpectroProcessor.kt`,
`AudioPreprocessor.kt`, and `LogMelSpectroConfig` against that jar, then run:

```kotlin
for (size in listOf(80000, 12345)) {
    val audio = FloatArray(size) { i -> (((i * 73) % 997) - 498) / 1024f }
    val features = processor.process(audio)
    DataOutputStream(FileOutputStream("upstream-$size.f32be")).use { out ->
        features.forEach(out::writeFloat)
    }
}
```

SHA-256 checksums:

```
7589d6ceb6b1747543dab52bb006f058d23c7087a6dbdd3002da05d0053b6330  upstream-80000.f32be
3d689b43b9a1f2980a69a1bfe809fc8f8a80e49717bc2963bf018dac20c177d4  upstream-12345.f32be
```

The frontend retains upstream Float accumulation and sample standard deviation
(`N - 1`) to preserve numerical parity. It returns zeros for fewer than two
valid frames, avoiding the reference implementation's invalid short-input
reflection padding and division by zero. It rejects more than five seconds;
the engine must split and retain every sample before calling the frontend.
