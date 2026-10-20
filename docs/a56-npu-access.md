# Samsung Galaxy A56: что известно о доступе к NPU

## Вывод

У Galaxy A56 есть Exynos 1580 с NPU, однако наличие NPU не подтверждает, что приложения Lacan могут передать ему ONNX-модель. В текущем приложении все sherpa-движки явно выбирают `provider = "cpu"`. В закреплённом sherpa-onnx 1.12.32 AAR виден NNAPI Execution Provider внутри `libonnxruntime.so`, но текущая конфигурация его не запрашивает.

Read-only диагностика подключённого SM-A566E показала Android API 37 / Android 17 и службу `android.npumanager.INpuManagerService` с именем `npu`. Native NNAPI discovery из временного бинарника вернул **одно устройство: `nnapi-reference`, type `CPU`, version `A566EXXUEDZJ1`, feature level 8**. В этой NNAPI enumeration для shell-процесса аппаратный accelerator device не объявлен. Это сильнее одного только списка служб, но ещё не измеряет возможные различия контекста приложения и shell.

Поэтому точный ответ: **NPU физически присутствует и системная NPU Manager служба видна; доступный для приложения NNAPI-ускоритель на прошивке пока не подтверждён; фактическое делегирование модели и ускорение не проверялись.** Служба NPU Manager координирует ресурсы и не доказывает доступность вычислительного backend или совместимость модели.

## Наблюдения

- Samsung указывает Exynos 1580 как процессор с улучшенным NPU на [официальной странице Exynos 1580](https://semiconductor.samsung.com/processor/mobile-processor/exynos-1580/).
- Устройство, видимое через ADB: модель SM-A566E (`a56`); `ro.build.version.sdk=37`, `ro.build.version.release=17`. Серийный идентификатор здесь намеренно не сохраняется.
- Публичный список Android services содержит `npu: [android.npumanager.INpuManagerService]`. Другие совпадения фильтра относятся к словам вроде `input` и не являются NNAPI.
- Временная C-программа вызывала только `ANeuralNetworks_getDeviceCount`, `ANeuralNetworks_getDevice` и getters имени, типа, версии и feature level. Она обнаружила один NNAPI device типа CPU (`nnapi-reference`), то есть reference CPU, а не Samsung accelerator. Устройство не запускало модель. Бинарник `/data/local/tmp/lacan-nnapi-probe` после запуска удалён; локальная сборка также удалена.
- В `app/src/main/java/org/lacan/asr/SherpaEngine.kt`, `SherpaWhisperEngine.kt` и `SherpaTransducerEngine.kt` выставлен CPU provider.
- В `app/libs/sherpa-onnx-1.12.32.aar` находятся arm64 `libonnxruntime.so` и `libsherpa-onnx-jni.so`. В `libonnxruntime.so` есть символы/строки `OrtSessionOptionsAppendExecutionProvider_Nnapi`, `NnapiExecutionProvider` и сообщений о NNAPI. AAR-манифест задаёт minSdk 21. Это подтверждает присутствие NNAPI EP-кода в библиотеке, но не его вызов приложением, сборку с нужными флагами или назначение операций на аппаратный ускоритель.
- Официальная документация Android говорит, что NNAPI доступна начиная с Android 8.1/API 27 и deprecated с Android 15. Deprecated не означает автоматического удаления HAL или невозможности вызова на Android 17. [Документация Android NNAPI](https://developer.android.com/ndk/guides/neuralnetworks).
- В руководстве sherpa-onnx 1.12.32 есть требование собирать Android с `ANDROID_PLATFORM=android-27` для NNAPI. [Скрипт сборки версии 1.12.32](https://github.com/k2-fsa/sherpa-onnx/blob/v1.12.32/build-android-arm64-v8a.sh). В готовом AAR minSdk 21 не говорит, включена ли NNAPI в используемой конфигурации и как её активировать через API sherpa.

## Что осталось неизвестным

Эта проверка обнаружила только NNAPI reference CPU в shell-контексте. Для исключения различий контекста нужна такая же discovery-проверка из обычного приложения. Для подтверждения фактического NPU выполнения потребуются одновременно:

1. Обнаруженный NNAPI accelerator с vendor/device/version, без CPU reference device.
2. Явно включённый NNAPI EP при построении сессии и лог ORT о назначенных NNAPI-партициях; оставшиеся CPU-партиции следует учитывать отдельно.
3. Проверка конкретного графа и численных результатов на той же модели, включая стабильность и время выполнения.

Ни одно из этих трёх условий в рамках этой проверки не запускалось: аудио, инференс, сборки и изменение настроек устройства не выполнялись.
