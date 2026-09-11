# Сборка и запуск

Команды ниже выполняются из корня проекта в PowerShell. `C:\projects\codex-edt` — пример каталога клонирования; при другом расположении замените его в командах. `%LOCALAPPDATA%` означает локальный каталог профиля Windows; в PowerShell ему соответствует `$env:LOCALAPPDATA`.

## Подтверждённая среда

- Windows x86_64.
- EDT 2026.1.3.25:
  `%LOCALAPPDATA%\1C\1cedtstart\installations\1C_EDT 2026.1\1cedt`.
- Axiom JDK Full 17.0.18:
  `C:\Program Files\Axiom\AxiomJDK-Pro-17-Full`.
- Maven 3.9.16, локальная распаковка при проверке:
  `C:\projects\codex-edt\apache-maven-3.9.16-bin\apache-maven-3.9.16`.
- Tycho 4.0.5 скачивается Maven как зависимость сборки.

Почему выбраны эти версии: [исследование](research.md). EDT 2026.2 / Java 25 требуют отдельного target и проверки.

Дистрибутивы JDK, Maven и EDT не входят в Git-репозиторий. Tycho и Target Platform загружаются при сборке; для UI-теста нужен графический сеанс Windows. Если EDT ещё не установлена, используйте [официальную страницу 1С](https://edt.1c.ru/) и выбирайте ветку **2026.1**, соответствующую target проекта. Для `-JavaHome` нужен каталог Full JDK 17 с `bin/javac.exe`, а не только JRE. Скрипты не устанавливают инструменты самостоятельно.

## Сборка одной командой

```powershell
Set-Location C:\projects\codex-edt
.\scripts\build.ps1
```

При необходимости явно укажите пути:

```powershell
.\scripts\build.ps1 -MavenHome 'C:\projects\codex-edt\apache-maven-3.9.16-bin\apache-maven-3.9.16' -JavaHome 'C:\Program Files\Axiom\AxiomJDK-Pro-17-Full'
```

Скрипт временно задаёт JAVA_HOME только своему процессу, использует проектный `.mvn/settings.xml` и кэш `.m2/repository`. Пользовательские/глобальные Maven settings не подключаются. Завершение должно содержать **BUILD SUCCESS**; ненулевой код завершения является ошибкой.

Эквивалентная Maven-команда из корня при уже заданном JAVA_HOME:

```powershell
& .\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin\mvn.cmd --batch-mode --show-version --no-transfer-progress --settings .mvn/settings.xml --global-settings .mvn/settings.xml '-Dmaven.repo.local=C:\projects\codex-edt\.m2\repository' '-Dtycho.localArtifacts=ignore' clean verify
```

Результаты:

- `bundles/com.admglobal.codex.edt/target/*.jar` — плагин;
- `features/com.admglobal.codex.edt.feature/target/*.jar` — feature;
- `repositories/com.admglobal.codex.edt.repository/target/repository/` — установочный p2;
- ZIP в `repositories/com.admglobal.codex.edt.repository/target/` — переносимая поставка;
- `tests/com.admglobal.codex.edt.tests/target/surefire-reports/` — результаты UI-теста;
- `.runtime/logs/maven-build.log` — полный журнал сборки.

p2 содержит индексы content/artifacts, feature и bundle. Зависимости среды и test bundle в поставку не включаются. Первый запуск скачивает Eclipse/EDT p2 и Maven-артефакты; он заметно дольше последующих.

## Отдельная EDT без установки PDE

```powershell
.\scripts\start-dev-edt.ps1
```

Скрипт читает установленную EDT, создаёт в `.runtime/development` отдельные configuration, user-home и workspace, подключает собранный bundle через отдельный `bundles.info` и запускает штатный EDT product. Исходная установка и её p2-профиль не обновляются. Это удобный запуск собранного JAR; горячей перекомпиляции и Java-отладки PDE здесь нет.

Если EDT или JDK установлены в другом месте, передайте параметры явно, например:

```powershell
.\scripts\start-dev-edt.ps1 -EdtHome 'C:\tools\edt-2026.1\1cedt' -JavaHome 'C:\tools\jdk-17-full'
```

Замените примерные пути на существующие каталоги своей установки.

Для отдельного запуска используются `scripts/development-preferences.ini`: отключены автоматическая регистрация URI-схем в Windows, Oomph setup и планировщик обновлений. Это настройки только тестового процесса Eclipse; они не являются API EDT и не входят в поставляемый bundle. Ключи проверены по установленным библиотекам Eclipse 4.30, механизм `-pluginCustomization` описан в [Eclipse runtime options](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/misc/runtime-options.html). Временные DLL JNA также распаковываются внутри `.runtime`.

Перед новой сборкой закройте тестовую EDT: Windows может удерживать JAR открытым. Если нужно только подготовить параметры, выполните:

```powershell
.\scripts\start-dev-edt.ps1 -PrepareOnly
```

Открытие панели: **Window → Show View → Other… → ADM Global → Codex**. Перетащите вкладку вправо. Для первого сообщения проект не нужен.

## Полноценная среда разработки плагинов PDE

Отдельная среда PDE нужна для Java-отладки кнопкой Debug и запуска из Eclipse. Для Maven-сборки и запуска через `start-dev-edt.ps1` она не требуется. При первичной проверке проекта такая среда отсутствовала, поэтому путь PDE ниже описан по официальному руководству и пока не проверен вручную.

Официальные инструкции [по среде](https://edt.1c.ru/dev/ru/docs/plugins/project/env-setup/) и [импорту проекта](https://edt.1c.ru/dev/ru/docs/plugins/project/copy-clone/) предлагают специальный Eclipse через **1C:EDT Start**.

1. Запустите установленный **1C:EDT Start** через меню Windows.
2. Создайте новую среду/проект и выберите тип **«Плагины для 1C:EDT»**. Это отдельный продукт; не выбирайте обновление существующей рабочей EDT.
3. В мастере выберите доступную среду Eclipse для разработки плагинов. Руководство 1С на момент исследования называет **2022-03**; фактический каталог Start может предлагать другую версию. Версию target нашего проекта оставьте **2026.1**.
4. Для новой рабочей области укажите `C:\projects\codex-edt\.runtime\pde-workspace`. Если мастер позволяет указать каталог установки самой среды, используйте `C:\projects\codex-edt\.tools\edt-plugin-ide`.
5. Если Start запросит Java, используйте уже установленный **Axiom Full JDK 17**. Не меняйте Java рабочей EDT.
6. Дождитесь загрузки компонентов и запустите созданную среду.

Если Start отсутствует, начните со [страницы загрузки EDT](https://edt.1c.ru/) и ветки **2026.1**, установите компонент программы запуска **1C:EDT Start**, затем повторите действия выше. Названия промежуточных кнопок зависят от версии Start; весь мастер при первичной проверке проекта не проходился.

В открывшейся среде:

1. **File → Import… → Maven → Existing Maven Projects**.
2. Root Directory: `C:\projects\codex-edt`. Отметьте только POM нашего reactor: корневой, bom, bundles, features, repositories, targets, tests и их дочерние модули. Исключите `.research`, `.m2`, `.runtime`, Maven distribution.
3. Если Maven importer отсутствует, импортируйте **General → Existing Projects into Workspace** для bundle и tests. Target откройте через **File → Open File…**.
4. Откройте `targets/default/default.target`. Дождитесь разрешения содержимого, нажмите **Set as Active Target Platform**.
5. В **Window → Preferences → Java → Installed JREs** добавьте каталог Full JDK 17 через **Add → Standard VM → Directory…**. В **Execution Environments → JavaSE-17** выберите этот JDK.
6. Дождитесь завершения фоновой сборки; в **Problems** не должно быть Java/manifest errors.
7. Откройте файл `Codex-EDT.launch` в bundle. Через его контекстное меню выберите **Run As → Codex-EDT** либо откройте **Run → Run Configurations… → Eclipse Application → Codex-EDT**.
8. Проверьте **Main → Run a product**: `com._1c.g5.v8.dt.product.application.rcp`; **Plug-ins → Validate Plug-ins** не должен сообщать обязательные отсутствующие зависимости. На вкладке Plug-ins исключите test bundle, если он выбран автоматически для интерактивной работы.
9. Нажмите **Run** или **Debug**. Вторая EDT использует `.runtime/pde-edt-workspace`.

PDE/Target Platform могут докачивать зависимости. Если target в IDE не разрешается, не переключайте его на произвольную более новую EDT: сохраните сообщение в Problems/Error Log для диагностики.

## Если Maven отсутствует

Для локальной установки Maven:

1. Откройте [официальные загрузки Apache Maven](https://maven.apache.org/download.cgi).
2. Скачайте **Binary zip archive** стабильной **3.9.x**, минимум 3.9.4. На дату исследования — **apache-maven-3.9.16-bin.zip**. Не выбирайте Source и Maven 4 RC.
3. Распакуйте в `C:\projects\codex-edt\.tools`, чтобы существовал `.tools\apache-maven-3.9.16\bin\mvn.cmd`.
4. Установщика у ZIP нет. PATH и глобальные переменные менять не нужно.
5. Выполните `.\scripts\build.ps1`.

## Установка и удаление плагина

Штатный способ описан в [руководстве 1С](https://edt.1c.ru/dev/ru/docs/plugins/project/build-install-publish-project/).

1. В обычной EDT: **Help → Install New Software… → Add…**.
2. **Local…** → каталог `repositories/com.admglobal.codex.edt.repository/target/repository`, либо **Archive…** → ZIP из `target`.
3. Выберите **ADM Global → Codex for 1C:EDT**, затем **Next**.
4. Проверьте состав устанавливаемых компонентов и условия предварительной поставки; завершите мастер. Если EDT предупреждает о неподписанном артефакте, проверяйте, что это собранный вами плагин ADM Global.
5. Перезапустите EDT и откройте Codex через Show View.

Удаление: **Help → About → Installation Details → Installed Software → Codex for 1C:EDT → Uninstall…**, затем перезапуск. Установка в вашу обычную EDT в ходе разработки автоматически не выполняется.

## Дополнительная локальная проверка

```powershell
.\scripts\local-check.ps1
```

Этот скрипт компилирует Java установленным `javac` против **активных версий** библиотек из `bundles.info`, упаковывает JAR и запускает штатный Eclipse p2 Publisher и SWT smoke harness. Результат сохраняется в новой папке `.runtime/local-<version>`. Это проверка библиотек именно локальной EDT; она не заменяет Maven/Tycho.

Harness запускает Eclipse workbench без автоматических сервисов БМ EDT и без пользовательских проектов. Он не доказывает работу специфического BSL-редактора EDT. Для чистой публикации без UI доступен параметр `-SkipSmoke`.

Полный EDT product можно проверить после Maven-сборки командой:

```powershell
.\scripts\start-dev-edt.ps1 -Smoke
```

Она запускает отдельную одноразовую workspace `.runtime/edt-smoke-<timestamp>`, подключает test bundle, выполняет UI-тест после старта EDT и закрывает окно. Результат — `result.txt` и `edt.log` в этой папке. В обычном интерактивном запуске test bundle не подключается. Не закрывайте тестовое окно до завершения сценария.

## Типовые проблемы

- **Maven not found** — проверьте наличие `bin/mvn.cmd`, передайте `-MavenHome`.
- **RequireJavaVersion** — проект проверяется на JDK 17; Java 25 предназначена для отдельной будущей цели EDT 2026.2.
- **Unknown packaging eclipse-plugin** — запускайте Maven из корня reactor, чтобы загрузилось Tycho extension.
- **Could not resolve target / download timeout** — проверьте доступ к Maven Central, `edt.1c.ru` и `download.eclipse.org`; полный журнал находится в `.runtime/logs`.
- **Нет Eclipse Application в Run As** — в IDE отсутствует PDE либо bundle импортирован не как Plug-in Project.
- **Нет Codex в Show View** — проверьте активный target, подключённый bundle/feature и вкладку Error Log.
- **Workspace is in use** — закройте предыдущую тестовую EDT; не удаляйте lock у запущенной среды.
