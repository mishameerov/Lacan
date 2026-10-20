package org.lacan.models

data class ModelFile(val name: String, val url: String, val sha256: String, val sizeBytes: Long) {
    init {
        require(name.matches(Regex("[A-Za-z0-9_.-]+")) && name != "." && name != "..")
        require(url.startsWith("https://"))
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        require(sizeBytes > 0)
    }
}
data class ModelSpec(val id: String, val title: String, val description: String, val files: List<ModelFile>, val engine: String) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
    init { require(id.matches(Regex("[a-z0-9-]+"))); require(files.isNotEmpty()); require(files.map { it.name }.distinct().size == files.size) }
}
object ModelCatalog {
    private const val whisper = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/"
    private const val giga = "https://huggingface.co/BahamutRU/GigaAM-v3-sherpa-onnx/resolve/b2176bb2acabc2a9983ae597e0a16cdb8ef33e77/"
    private const val gigaMultilingual = "https://huggingface.co/fussraider/GigaAM-Multilingual-sherpa-onnx-ctc/resolve/9f5a77e8975211abe8511693accd3a63ee1e9f43/large/"
    val models = listOf(
        ModelSpec("whisper-tiny", "Whisper tiny Q5", "Русский и английский. Самая компактная Whisper; качество смешанной речи зависит от записи.", listOf(ModelFile("ggml-tiny-q5_1.bin", whisper + "ggml-tiny-q5_1.bin", "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7", 32152673)), "whisper"),
        ModelSpec("whisper-base", "Whisper base Q5", "Русский и английский. Компактная модель; сравните результат со small на своей речи.", listOf(ModelFile("ggml-base-q5_1.bin", whisper + "ggml-base-q5_1.bin", "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898", 59707625)), "whisper"),
        ModelSpec("whisper-small", "Whisper small Q5", "Русский и английский. 190 МБ; больше вычислений и памяти, чем tiny/base. Начните сравнение с этой модели.", listOf(ModelFile("ggml-small-q5_1.bin", whisper + "ggml-small-q5_1.bin", "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb", 190085487)), "whisper"),
        ModelSpec("whisper-medium", "Whisper medium Q5 · экспериментальная", "Русский и английский. 539 МБ; требует много ОЗУ и может распознавать медленно. Проверяйте на коротких записях.", listOf(ModelFile("ggml-medium-q5_0.bin", whisper + "ggml-medium-q5_0.bin", "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f", 539212467)), "whisper"),
        ModelSpec("whisper-large-v3-turbo", "Whisper large-v3-turbo Q5 · экспериментальная", "Русский и английский. 574 МБ; требует много ОЗУ. Скорость и качество на вашем устройстве нужно сравнить с medium.", listOf(ModelFile("ggml-large-v3-turbo-q5_0.bin", whisper + "ggml-large-v3-turbo-q5_0.bin", "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2", 574041195)), "whisper"),
        ModelSpec("gigaam-v3", "GigaAM v3 e2e CTC int8", "Для русского языка, с пунктуацией. Смешанный английский не проверен.", listOf(
            ModelFile("gigaam_v3_e2e_ctc_int8.onnx", giga + "gigaam_v3_e2e_ctc_int8.onnx", "0aacb41f70f0f5aaac4b45dd430337b9e16b180f22c72af04db8516e7609c3c0", 319869121),
            ModelFile("gigaam_v3_e2e_ctc_tokens.txt", giga + "gigaam_v3_e2e_ctc_tokens.txt", "f8eb9b115e2748db9c40a5897cae11dd0678cc0b40fd7e25f8c43b3bf28715e4", 2006)
        ), "sherpa"),
        ModelSpec("gigaam-v3-fp32", "GigaAM v3 e2e CTC FP32 · экспериментальная", "Русский, с пунктуацией. 886 МБ; версия без int8-квантизации для сравнения. Больше ОЗУ; английский не проверен.", listOf(
            ModelFile("gigaam_v3_e2e_ctc.onnx", giga + "gigaam_v3_e2e_ctc.onnx", "1f3a714b76877724eda494d77e7ed0d15f1c003c60928c9cb03f21d81dc68cc5", 885950432),
            ModelFile("gigaam_v3_e2e_ctc_tokens.txt", giga + "gigaam_v3_e2e_ctc_tokens.txt", "f8eb9b115e2748db9c40a5897cae11dd0678cc0b40fd7e25f8c43b3bf28715e4", 2006)
        ), "sherpa"),
        ModelSpec("gigaam-multilingual-large", "GigaAM Multilingual 600M int8 · экспериментальная", "Русский и английский. 592 МБ; более крупная GigaAM, без пунктуации, строчные буквы. Требует много ОЗУ; скорость зависит от устройства.", listOf(
            ModelFile("gigaam_multilingual_large_int8.onnx", gigaMultilingual + "model.int8.onnx", "6b6f195026b0f90721cd4593c664becf009a71131550b664eec71446ec351c81", 591645642),
            ModelFile("gigaam_multilingual_large_tokens.txt", gigaMultilingual + "tokens.txt", "9b5df7987cb4ca52c1a468649ce897fab1cd182067416e29fef49dfaa7a856c2", 391)
        ), "sherpa")
    )
}
