# Lacan: переносимое ускорение распознавания на Android

Исследование первичных источников, 21 октября 2026 года. Это план интеграции и проверки, а не результат измерения ускорения. В этой работе телефон, сборки и распознавание не запускались. Установленная версия 0.1.3 использует CPU.

Обновление после исследования: [Vosk CPU проверен в 0.1.4](vosk-cpu-probe.md); [NNAPI discovery на A56](a56-npu-access.md) показал reference CPU; [изолированная проверка Parakeet GPU](parakeet-gpu-probe.md) подтвердила OpenCL-делегирование и один encoder-проход на синтетическом входе. Ниже сохранены исследовательские выводы; полный GPU-путь распознавания в Lacan ещё не интегрирован.

## Решение

Строить поддержку по возможностям чипа, драйвера и конкретной модели, а не по марке телефона. Xiaomi, OnePlus и Samsung выпускают устройства с разными SoC; даже одинаковый SoC не гарантирует одинаковую прошивку и набор доступных драйверов.

Практическая архитектура: **переносимый GPU-бэкенд + дополнительные NPU-бэкенды + рабочий CPU-путь**. Выбирать проверенную комбинацию по полной скорости, качеству и расходу ресурсов. Жёсткое правило «NPU всегда лучше GPU, а GPU лучше CPU» неверно: частичная передача графа ускорителю и синхронизация могут сделать весь процесс медленнее.

Приоритет исследования изменён по новым находкам:

1. Проверить **нативный ONNX Runtime WebGPU/Vulkan** на возможность сохранить существующий GigaAM FP32 ONNX. Сначала сборка/операторы/интеграция, без массовой конвертации моделей.
2. Сравнить два конкретных пути **Parakeet TDT v3: LiteRT GPU и ExecuTorch Vulkan**. Для них найдены модельные экспортеры и Android-примеры. Это позволяет проверить движки независимо от разработки собственного конвертера GigaAM.
3. Для выбранного общего стека переносить **GigaAM e2e CTC**, затем расширять NPU-покрытие по чипам. GigaAM FP32 остаётся пользовательским эталоном качества русского языка.
4. Крупный Whisper исследовать отдельно: ncnn Vulkan имеет настоящий Whisper pipeline; опубликованные LiteRT medium/large-v3 существуют, но их декодер без KV-cache и ограниченная проверка качества делают их экспериментом, а не готовым ускорением.

Первый пункт — короткая статическая проверка сборки и операторов ORT; первым сравнением реального GPU-распознавания остаются готовые пути Parakeet. Это порядок ограниченных экспериментов, не предложение сразу встроить все движки в APK.

## Что уже есть в Lacan

- arm64-v8a, minSdk 33, targetSdk 36; текущий APK не поддерживает Android 12.
- sherpa-onnx 1.12.32 с собственным `libonnxruntime.so`; GigaAM, Whisper и Parakeet явно выбирают `provider = "cpu"`.
- whisper.cpp v1.8.7: Vulkan не включён в CMake, JNI устанавливает `use_gpu = false`.
- Отсутствие отдельных vendor-библиотек в AAR не доказывает отсутствие встроенных execution providers. Нужны точная конфигурация сборки и перечисление доступных провайдеров.
- В исходниках закреплённой версии sherpa Android-сборка по умолчанию использует API 21; NNAPI-ветка требует API 27 при сборке. В локальной JNI-библиотеке обнаружена строка соответствующего CPU fallback. Это повод проверить/пересобрать native-стек, а не обещать включение NNAPI одним параметром. [Скрипт сборки](https://github.com/k2-fsa/sherpa-onnx/blob/v1.12.32/build-android-arm64-v8a.sh), [выбор провайдера](https://github.com/k2-fsa/sherpa-onnx/blob/v1.12.32/sherpa-onnx/csrc/session.cc).

## Переносимые GPU-пути

| Путь | Что подтверждено источниками | Что нужно доказать в Lacan |
| --- | --- | --- |
| **ORT native WebGPU → Dawn → Vulkan** | Текущая документация прямо перечисляет Android. Это нативный C/C++ backend, не браузер и не облако. | Согласованная Android-сборка ORT/sherpa, наличие нужных kernels, фактические GPU-партиции, память и скорость. Готовый drop-in AAR для нашего стека не установлен. |
| **LiteRT CompiledModel GPU** | Android GPU через OpenCL/OpenGL; есть Parakeet ASR sample и опубликованные TDT v3 артефакты. Общий API также имеет vendor NPU-интеграции. | Совместимость конкретного GPU, русского/смешанного текста, padding и host decoder; новый адаптер для `.tflite`. |
| **ExecuTorch Vulkan** | Модельный экспорт Parakeet TDT v3, Vulkan runner и Android-путь. | Закрепить версии exporter/runtime, проверить quantization и реальные партиции. Наличие Whisper runner само по себе не подтверждает Whisper Vulkan. |
| **ncnn Vulkan** | Upstream Whisper example с encoder/decoder/projection и KV-cache. | Портирование Android/JNI, модельные экспорты, качество и скорость. Пример отключает FP16: не менять точность без проверки. |
| **whisper.cpp Vulkan** | Готовая архитектура Whisper и поддержка Vulkan; можно повторно использовать GGML. | Только отдельный резервный эксперимент: плохой CPU-результат не измеряет GPU, но пользователь не выбрал этот движок основным. |
| **MNN, IREE, Arm VGF, transcribe.cpp** | Реальные GPU/compiler-пути с разной зрелостью. У transcribe.cpp есть GigaAM RNN-T, отличающийся от нашего CTC. | Нет проверенного здесь готового Android-пути для всех наших моделей. Использовать при конкретном преимуществе; не множить зависимости заранее. |

Исправление прежнего вывода: утверждение об отсутствии общего Android GPU-пути у ORT было слишком широким. [Native WebGPU EP](https://onnxruntime.ai/docs/execution-providers/WebGPU-ExecutionProvider.html) теперь документирует Android/Vulkan. Это не означает, что он включён в установленный Lacan. См. также [LiteRT GPU](https://developers.google.com/edge/litert/next/gpu), [Parakeet ExecuTorch](https://github.com/pytorch/executorch/blob/main/examples/models/parakeet/README.md), [ncnn Whisper](https://github.com/Tencent/ncnn/blob/master/examples/whisper.cpp).

Для широкого покрытия проверять минимум Adreno, Mali/Immortalis и Xclipse; PowerVR и прочие GPU считать отдельными непроверенными конфигурациями. Наличие Vulkan loader не гарантирует подходящее устройство и необходимые расширения. OpenCL не гарантирован публичным Android NDK. Custom ROM может менять доступность драйверов независимо от характеристик SoC.

## NPU: матрица вместо привязки к Samsung

| Семейство | Основной кандидат | Границы подтверждённого покрытия |
| --- | --- | --- |
| **Qualcomm / Snapdragon** | QNN через LiteRT, ExecuTorch или ORT QNN | LiteRT перечисляет 8 Gen 1/8+ Gen 1/8 Gen 2/8 Gen 3/8 Elite/8 Elite Gen 5. Это не полный список QNN и не гарантия для старых 7xx/8xx. Их проверять по версии SDK/HTP отдельно. |
| **MediaTek / Dimensity** | LiteRT NeuroPilot; ExecuTorch Neuron | LiteRT: 7300/8300/9000/9200/9300/9400/9500. В документации ExecuTorch уже: 9300/9400. Для чипов вне этих списков — GPU/CPU и отдельная проверка NNAPI. |
| **Samsung / Exynos** | LiteRT или ExecuTorch LiteCore | Публичные списки: 2500/2600. Наш Exynos 1580 в них отсутствует; NPU A56 не считать поддержанным. |
| **Google Tensor / Pixel** | LiteRT Tensor SDK | G5/G6, AOT-only, beta/signup. G1–G4 не подтверждены этим SDK: исследовать GPU и существующие NNAPI-драйверы. Есть существенные ограничения SDK на распространение и benchmarks. |
| **UNISOC и неуказанные SoC** | Сначала GPU/CPU; диагностический NNAPI-путь | Публичный готовый NPU backend для наших моделей не подтверждён. Маркетингового наличия NPU недостаточно. |

Источники матрицы: [Qualcomm](https://developers.google.com/edge/litert/next/qualcomm), [MediaTek](https://developers.google.com/edge/litert/next/mediatek), [Samsung](https://developers.google.com/edge/litert/next/samsung), [Tensor SDK](https://developers.google.com/edge/litert/next/tensor-sdk). Полные ссылки, SDK-версии и границы доступны в [подробном исследовании NPU](research/android-npu-platforms.md).

**NNAPI сохраняем как ограниченный путь для существующих драйверов.** Он deprecated с Android 15, но приложения могут продолжать его использовать, HAL поддерживается. При проверке отключать reference CPU NNAPI и отдельно учитывать CPU-партиции ORT. Это не универсальная гарантия NPU и не перспективный основной интерфейс. [Android NNAPI](https://developer.android.com/ndk/guides/neuralnetworks), [ORT NNAPI](https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html).

**Android 17 NPU Manager не заменяет движок.** Он координирует ресурсы/загрузку/приоритеты; выполнение графа остаётся задачей vendor runtime. Наличие службы на A56 не подтверждает совместимость GigaAM. [AOSP](https://source.android.com/docs/core/perf/npu-manager).

## Модели, гибридное выполнение и память

Сначала ускорять большие вычисления encoder; feature extraction, CTC collapse или host decoding оставлять на CPU, если перенос не даёт выигрыша. Для Parakeet TDT нужно сохранить predictor/joiner и duration decoding; его нельзя подменить CTC. Для Whisper отдельно важны язык, токены и KV-cache. Гибрид CPU+GPU/NPU — нормальная архитектура, если не копировать данные туда-обратно после каждой маленькой операции.

GigaAM FP32 ONNX — наиболее полезный контроль для ORT GPU. Наличие Conv/MatMul/Softmax в backend ещё не подтверждает весь граф: проверяются типы, shapes, opsets и динамические длины. Dynamic INT8 Whisper может содержать `DynamicQuantizeLinear`/`MatMulInteger`, отсутствующие в просмотренном native WebGPU registry; тогда понадобится другой экспорт или дорогой CPU fallback. Размер INT8-файла не равен объёму GPU-памяти и не гарантирует integer execution. [Native kernels](https://github.com/microsoft/onnxruntime/blob/main/onnxruntime/core/providers/webgpu/webgpu_execution_provider.cc).

Для LiteRT опубликован Parakeet TDT v3 FP32 (~2.42 GB), INT8 (~614 MB), stateful и AOT варианты. Их наличие ускоряет исследование, но размер FP32 требует предварительного бюджета RAM. Google sample различает TDT и CTC на разных устройствах — поддержку одного нельзя переносить на другой. Whisper medium/large-v3 LiteRT опубликованы, но без decoder KV-cache и с ограниченной CPU-проверкой на корейском/английском. Это причина сначала проверить runtime на Parakeet, а не обещать быстрый large-v3. [Артефакты Parakeet](https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3), [Google ASR sample](https://github.com/google-ai-edge/litert-samples/tree/main/samples/litert/speech_recognition), [подробности конвертации](research/android-asr-conversion.md).

Для NPU обычно нужны ограниченные/фиксированные shapes и совместимая схема quantization. Современный QNN поддерживает и floating-point HTP, поэтому правило «NPU только INT8» также слишком грубое. Конкретный backend определяет контракт. Калибровку статического INT8 проводить на русском, английском, смешанной речи, шуме и тишине; качество проверять на отдельном наборе. Fixed-window модели сами по себе не становятся streaming-моделями: длины, padding, overlap и склейка остаются ответственностью Lacan.

## Контракт интеграции

Сохранить `AsrEngine` и жизненный цикл диктовки, добавить описание совместимых артефактов и реализаций:

- `modelId`, точная revision/hash, языки, frontend/tokenizer, формат, точность, window/mask/cache contract;
- runtime и его версия, допустимые providers, зависимости и ограничения ABI/драйвера;
- capability probe и результат компиляции/распределения графа;
- отдельно **запрошенный backend**, **фактический backend** и оставшиеся CPU-партиции;
- локальный профиль совместимости по model hash/runtime/SoC/OS/driver; сброс после их изменения.

Автовыбор должен использовать только прошедшие квалификацию комбинации. Ошибку инициализации можно обработать CPU fallback той же логической модели; не менять язык/модель молча. Неподдержанный или нестабильный backend отключать до изменения профиля, а не повторять аварийную загрузку на каждой диктовке. При native-crash/OOM не рассчитывать на обычный Kotlin catch: либо отдельный процесс для экспериментального runtime, либо отказ от такой комбинации в основной сборке.

Нельзя подложить второй несовместимый `libonnxruntime.so` рядом с sherpa. Нужна согласованная сборка или процессная изоляция. Не объединять все исследуемые runtimes в один тяжёлый APK до выбора работающего пути. Для поставки проверять arm64, 16 KB native pages, память и cancellation; CPU XNNPACK/NEON/thread tuning остаётся отдельной полезной оптимизацией, но не должно называться GPU.

## Открытость и работа без серверов

Предпочтение — воспроизводимый открытый GPU-стек с необязательными vendor-оптимизациями. Открытый frontend не делает vendor runtime/firmware открытым. Совместимость лицензий proprietary-компонентов с GPL и права на распространение нужно установить для конкретных файлов; динамическая загрузка сама по себе не решает вопрос.

Особенно важен Tensor SDK: опубликованные условия содержат ограничения распространения SDK и созданных с ним продуктов, сравнительных benchmarks и публикации результатов. До выяснения разрешений это исследовательский кандидат, а не обещанный GPL-backend. [Условия Tensor SDK](https://developers.google.com/edge/litert/next/tensor_ml_terms).

Standalone APK должен иметь все runtime-зависимости до offline-запуска; Play Feature Delivery не подходит как обязательное условие для устройств без GMS. Модели скачиваются отдельно с hash/revision, затем выполняются локально. Драйвер, которого нет в прошивке, загрузка модели не создаёт. Облачные compile/profile сервисы производителей не обязательны для локальной архитектуры; пользовательское аудио туда не отправляется.

## Последовательность проверок

| Этап | Ограниченная работа | Условие продолжения |
| --- | --- | --- |
| 1. Статическая совместимость | Зафиксировать версии, лицензии, manifest, kernels, формат/формы графа, native packaging; ORT WebGPU проверить до смены стека. | Есть воспроизводимый build/export path без неразрешимых операторных или лицензионных препятствий. |
| 2. CPU parity | Один короткий набор RU/EN/mixed/silence/boundary; сравнить frontend, logits/tokens и итог с исходной моделью. | Конвертация сохраняет смысл, пунктуацию и языки в заданном бюджете ошибок. |
| 3. GPU qualification | Parakeet LiteRT и ExecuTorch либо GigaAM ORT при успешном этапе 1; короткие cold/warm прогоны. | Реальные GPU-партиции, корректность, приемлемые RAM/отмена, выигрыш полной задержки. |
| 4. NPU qualification | Одна подтверждённая модель на доступных Qualcomm и MediaTek, затем поддержанные Exynos/Tensor при допустимых условиях. | Фактическая делегация и выигрыш; успех на одном vendor не распространяется на другой. |
| 5. Переносимость/длительность | Adreno, Mali/Immortalis, Xclipse, затем PowerVR/другие; stock и custom ROM, no-GMS; 10 минут непрерывной диктовки после коротких gates. | Нет деградации качества, накопления памяти, недопустимого thermal throttling; надёжный fallback. |

Измерять cold/warm загрузку, real-time factor (время обработки / длительность аудио), финальную задержку, peak RAM/native/GPU memory, WER/CER, пунктуацию, смешанный язык, энергию и нагрев. Сравнение runtimes — одинаковые исходные веса, аудио и decoding policy; изменение quantization учитывать отдельным экспериментом. Не выдавать kernel-only или desktop цифры за ускорение диктовки на телефоне. Логи по умолчанию не содержат аудио или текст.

Все эти тесты — следующий этап. В текущем исследовании они не выполнялись.

## Подробные материалы

- [Платформы NPU, SDK, offline и лицензии](research/android-npu-platforms.md)
- [GPU runtimes, реальные ASR-примеры и ограничения](research/android-gpu-runtimes.md)
- [Точные модели, артефакты, frontend и конвертация](research/android-asr-conversion.md)
