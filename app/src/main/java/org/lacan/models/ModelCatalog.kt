package org.lacan.models

data class ModelFile(val name: String, val url: String, val sha256: String, val sizeBytes: Long) {
    init {
        require(name.matches(Regex("[A-Za-z0-9_.-]+")) && name != "." && name != "..")
        require(url.startsWith("https://"))
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        require(sizeBytes > 0)
    }
}
data class ModelSpec(val id: String, val title: String, val description: String, val files: List<ModelFile>, val engine: String, val featureDim: Int = 80) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
    init { require(id.matches(Regex("[a-z0-9-]+"))); require(files.isNotEmpty()); require(files.map { it.name }.distinct().size == files.size) }
}
object ModelCatalog {
    private const val whisper = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/"
    private const val giga = "https://huggingface.co/BahamutRU/GigaAM-v3-sherpa-onnx/resolve/b2176bb2acabc2a9983ae597e0a16cdb8ef33e77/"
    private const val gigaMultilingual = "https://huggingface.co/fussraider/GigaAM-Multilingual-sherpa-onnx-ctc/resolve/9f5a77e8975211abe8511693accd3a63ee1e9f43/large/"
    private const val whisperBaseOnnx = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/bb53ee204431c90d314c1cc08d28d23e5b7927cc/"
    private const val whisperSmallOnnx = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/8f3c18b358db4d1f2fc1eae49d75cd20989e4309/"
    private const val whisperMediumOnnx = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-medium/resolve/8c31d28503847560985df21f90e14f0c736e075e/"
    private const val whisperLargeOnnx = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-large-v3/resolve/2a6507094dd6020d939d78e3f1834a1d06267fca/"
    private const val parakeet = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/2bda32ec70b097a55adaa07d9a7173915b43cc78/"
    val models = listOf(
        ModelSpec("vosk-small-ru", "Vosk small RU 0.22 · CPU", "Русский, 46 МБ. Эксперимент для сравнения скорости и качества. Без пунктуации; смешанный английский не поддерживается. Язык определяется моделью.", listOf(
            ModelFile("vosk-model-small-ru-0.22.zip", "https://huggingface.co/rhasspy/vosk-models/resolve/e7ac2109d134b5f2404ba95389b2fb51916d4cab/ru/vosk-model-small-ru-0.22.zip", "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4", 46236750)
        ), "vosk"),
        ModelSpec("whisper-base-onnx", "Whisper base ONNX int8", "Русский и английский. 161 МБ; тот же sherpa-onnx, что у GigaAM. Сравните скорость и качество на своей речи.", listOf(
            ModelFile("base-encoder.int8.onnx", whisperBaseOnnx + "base-encoder.int8.onnx", "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11", 29120534),
            ModelFile("base-decoder.int8.onnx", whisperBaseOnnx + "base-decoder.int8.onnx", "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d", 130672026),
            ModelFile("base-tokens.txt", whisperBaseOnnx + "base-tokens.txt", "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126", 816730)
        ), "sherpa-whisper"),
        ModelSpec("whisper-small-onnx", "Whisper small ONNX int8", "Русский и английский. 375 МБ; sherpa-onnx на CPU. Больше памяти и вычислений, чем base; качество на телефоне не проверено.", listOf(
            ModelFile("small-encoder.int8.onnx", whisperSmallOnnx + "small-encoder.int8.onnx", "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9", 112442483),
            ModelFile("small-decoder.int8.onnx", whisperSmallOnnx + "small-decoder.int8.onnx", "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee", 262226114),
            ModelFile("small-tokens.txt", whisperSmallOnnx + "small-tokens.txt", "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126", 816730)
        ), "sherpa-whisper"),
        ModelSpec("whisper-medium-onnx", "Whisper medium ONNX int8 · экспериментальная", "Русский и английский. 946 МБ; sherpa-onnx на CPU. Требует много ОЗУ; распознавание на телефоне ещё не проверено.", listOf(
            ModelFile("medium-encoder.int8.onnx", whisperMediumOnnx + "medium-encoder.int8.onnx", "1c54582b4d829de0089f6cb63bbbdb3bf7555398bacaf855fbecf1a84dfd193e", 374196283),
            ModelFile("medium-decoder.int8.onnx", whisperMediumOnnx + "medium-decoder.int8.onnx", "595d00a338a365a7bfa0ca7f296cabc639583bef770ab6130df90f49a6412747", 571059257),
            ModelFile("medium-tokens.txt", whisperMediumOnnx + "medium-tokens.txt", "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126", 816730)
        ), "sherpa-whisper"),
        ModelSpec("whisper-large-v3-onnx", "Whisper large-v3 ONNX int8 · экспериментальная", "Русский и английский. Полная large-v3, 1,78 ГБ. Очень высокие требования к ОЗУ и CPU; на телефоне ещё не проверена.", listOf(
            ModelFile("large-v3-encoder.int8.onnx", whisperLargeOnnx + "large-v3-encoder.int8.onnx", "d531cf17248acc43e8c09b472a0877055e770877857a5332fc1304b36534ec85", 766671985),
            ModelFile("large-v3-decoder.int8.onnx", whisperLargeOnnx + "large-v3-decoder.int8.onnx", "ebc6bfd88e162a46cb3edee8a7e727e1dcbc65cabecb19e2573695e4d495e1af", 1008265203),
            ModelFile("large-v3-tokens.txt", whisperLargeOnnx + "large-v3-tokens.txt", "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126", 816730)
        ), "sherpa-whisper", featureDim = 128),
        ModelSpec("parakeet-tdt-0-6b-v3", "Parakeet TDT 0.6B v3 ONNX int8 · экспериментальная", "Русский, английский и ещё 23 языка. 670 МБ; пунктуация и регистр, автоопределение языка. NVIDIA, CC BY 4.0; на телефоне ещё не проверена.", listOf(
            ModelFile("encoder.int8.onnx", parakeet + "encoder.int8.onnx", "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247", 652184281),
            ModelFile("decoder.int8.onnx", parakeet + "decoder.int8.onnx", "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e", 11845275),
            ModelFile("joiner.int8.onnx", parakeet + "joiner.int8.onnx", "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3", 6355277),
            ModelFile("tokens.txt", parakeet + "tokens.txt", "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d", 93939)
        ), "sherpa-transducer", featureDim = 128),
        ModelSpec("whisper-tiny", "Whisper tiny Q5 · legacy whisper.cpp", "Предыдущий экспериментальный движок. Русский и английский. Самая компактная Whisper; качество смешанной речи зависит от записи.", listOf(ModelFile("ggml-tiny-q5_1.bin", whisper + "ggml-tiny-q5_1.bin", "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7", 32152673)), "whisper"),
        ModelSpec("whisper-base", "Whisper base Q5 · legacy whisper.cpp", "Предыдущий экспериментальный движок. Русский и английский. Компактная модель предыдущего эксперимента.", listOf(ModelFile("ggml-base-q5_1.bin", whisper + "ggml-base-q5_1.bin", "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898", 59707625)), "whisper"),
        ModelSpec("whisper-small", "Whisper small Q5 · legacy whisper.cpp", "Предыдущий экспериментальный движок. Русский и английский. 190 МБ; больше вычислений и памяти, чем tiny/base.", listOf(ModelFile("ggml-small-q5_1.bin", whisper + "ggml-small-q5_1.bin", "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb", 190085487)), "whisper"),
        ModelSpec("whisper-medium", "Whisper medium Q5 · legacy whisper.cpp", "Предыдущий экспериментальный движок. Русский и английский. 539 МБ; требует много ОЗУ и может распознавать медленно. Проверяйте на коротких записях.", listOf(ModelFile("ggml-medium-q5_0.bin", whisper + "ggml-medium-q5_0.bin", "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f", 539212467)), "whisper"),
        ModelSpec("whisper-large-v3-turbo", "Whisper large-v3-turbo Q5 · legacy whisper.cpp", "Предыдущий экспериментальный движок. Русский и английский. 574 МБ; требует много ОЗУ. Скорость и качество на телефоне не подтверждены.", listOf(ModelFile("ggml-large-v3-turbo-q5_0.bin", whisper + "ggml-large-v3-turbo-q5_0.bin", "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2", 574041195)), "whisper"),
        ModelSpec("gigaam-v3", "GigaAM v3 e2e CTC int8", "Для русского языка, с пунктуацией. Смешанный английский не проверен.", listOf(
            ModelFile("gigaam_v3_e2e_ctc_int8.onnx", giga + "gigaam_v3_e2e_ctc_int8.onnx", "0aacb41f70f0f5aaac4b45dd430337b9e16b180f22c72af04db8516e7609c3c0", 319869121),
            ModelFile("gigaam_v3_e2e_ctc_tokens.txt", giga + "gigaam_v3_e2e_ctc_tokens.txt", "f8eb9b115e2748db9c40a5897cae11dd0678cc0b40fd7e25f8c43b3bf28715e4", 2006)
        ), "sherpa", featureDim = 64),
        ModelSpec("gigaam-v3-fp32", "GigaAM v3 e2e CTC FP32 · экспериментальная", "Русский, с пунктуацией. 886 МБ; версия без int8-квантизации для сравнения. Больше ОЗУ; английский не проверен.", listOf(
            ModelFile("gigaam_v3_e2e_ctc.onnx", giga + "gigaam_v3_e2e_ctc.onnx", "1f3a714b76877724eda494d77e7ed0d15f1c003c60928c9cb03f21d81dc68cc5", 885950432),
            ModelFile("gigaam_v3_e2e_ctc_tokens.txt", giga + "gigaam_v3_e2e_ctc_tokens.txt", "f8eb9b115e2748db9c40a5897cae11dd0678cc0b40fd7e25f8c43b3bf28715e4", 2006)
        ), "sherpa", featureDim = 64),
        ModelSpec("gigaam-multilingual-large", "GigaAM Multilingual 600M int8 · экспериментальная", "Русский и английский. 592 МБ; более крупная GigaAM, без пунктуации, строчные буквы. Требует много ОЗУ; скорость зависит от устройства.", listOf(
            ModelFile("gigaam_multilingual_large_int8.onnx", gigaMultilingual + "model.int8.onnx", "6b6f195026b0f90721cd4593c664becf009a71131550b664eec71446ec351c81", 591645642),
            ModelFile("gigaam_multilingual_large_tokens.txt", gigaMultilingual + "tokens.txt", "9b5df7987cb4ca52c1a468649ce897fab1cd182067416e29fef49dfaa7a856c2", 391)
        ), "sherpa", featureDim = 64)
    )
}
