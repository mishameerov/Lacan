# Подписанная сборка Lacan

GitHub и другие магазины получают один и тот же APK с applicationId `org.lacan`.
Поддерживаются Android 13+ и arm64-v8a. Веса моделей скачиваются отдельно.
AAB — альтернативный формат для магазинов, требующих Android App Bundle; напрямую на телефон он не устанавливается.

## Ключ и обновления

Первый ключ создан локально: `.tools/signing/lacan-release.jks`, alias `lacan`.
Пароли и путь находятся в `keystore.properties` в корне проекта. Оба файла исключены из Git.
Сохраните **оба файла** в защищённой резервной копии. Не публикуйте и не прикладывайте их к релизам.
Для последующих обновлений необходим тот же ключ. Не создавайте новый ключ вместо потерянного.

Debug-сборка подписана другим ключом и имеет тот же applicationId. Release APK нельзя установить поверх неё.
Перед удалением debug-версии сохраните нужные тексты из истории; удаление приложения удаляет его данные и скачанные модели.

## Сборка

Нужны JDK 21, Android SDK и зависимости из `tools/prepare-asr.sh`.

```sh
./tools/prepare-asr.sh
./gradlew :core:test :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease :app:bundleRelease
```

Gradle читает локальный `keystore.properties`:

```properties
storeFile=.tools/signing/lacan-release.jks
storePassword=YOUR_PRIVATE_PASSWORD
keyAlias=lacan
keyPassword=YOUR_PRIVATE_PASSWORD
```

Без этого файла сборка остаётся неподписанной и не предназначена для публикации.
Результаты: `app/build/outputs/apk/release/app-release.apk` и
`app/build/outputs/bundle/release/app-release.aab`.
Перед публикацией проверяйте подпись APK через `apksigner verify --verbose --print-certs`,
выравнивание через `zipalign -c -P 16 4`, подпись AAB через `jarsigner -verify`,
а также отсутствие `android:debuggable=true` в APK.
Версия 0.1.11-prototype — предварительная, versionCode 12; при следующем обновлении versionCode должен увеличиться.
