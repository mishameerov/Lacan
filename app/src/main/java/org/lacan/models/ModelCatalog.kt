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
    val models = listOf(
        ModelSpec("whisper-tiny", "Whisper tiny Q5", "Мультиязычная модель: русский, английский и смешанная речь.", listOf(ModelFile("ggml-tiny-q5_1.bin", whisper + "ggml-tiny-q5_1.bin", "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7", 32152673)), "whisper"),
        ModelSpec("whisper-base", "Whisper base Q5", "Мультиязычная модель с повышенной точностью; требует больше памяти.", listOf(ModelFile("ggml-base-q5_1.bin", whisper + "ggml-base-q5_1.bin", "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898", 59707625)), "whisper"),
        ModelSpec("gigaam-v3", "GigaAM v3 e2e CTC int8", "Для русского языка, с пунктуацией. Смешанный английский не проверен.", listOf(
            ModelFile("gigaam_v3_e2e_ctc_int8.onnx", giga + "gigaam_v3_e2e_ctc_int8.onnx", "0aacb41f70f0f5aaac4b45dd430337b9e16b180f22c72af04db8516e7609c3c0", 319869121),
            ModelFile("gigaam_v3_e2e_ctc_tokens.txt", giga + "gigaam_v3_e2e_ctc_tokens.txt", "f8eb9b115e2748db9c40a5897cae11dd0678cc0b40fd7e25f8c43b3bf28715e4", 2006)
        ), "sherpa")
    )
}
