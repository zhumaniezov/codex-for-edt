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
- Для настоящих ответов: установленный Codex CLI **0.153.4** с действующим входом пользователя. Для обычной сборки и тестов он не нужен.

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

- `bundles/io.github.zhumaniezov.codex.edt/target/*.jar` — плагин;
- `features/io.github.zhumaniezov.codex.edt.feature/target/*.jar` — feature;
- `repositories/io.github.zhumaniezov.codex.edt.repository/target/repository/` — установочный p2;
- ZIP в `repositories/io.github.zhumaniezov.codex.edt.repository/target/` — переносимая поставка;
- `tests/io.github.zhumaniezov.codex.edt.tests/target/surefire-reports/` — результаты тестов протокола, процессов и SWT;
- `.runtime/logs/maven-build.log` — полный журнал сборки.

p2 содержит индексы content/artifacts, feature, основной bundle и `org.commonmark` **0.30.0**. CommonMark загружается из Maven Central через Maven location в `.target`; это самостоятельная Java-библиотека без браузера. Зависимости самой EDT и test bundle в поставку не включаются. Первый запуск скачивает Eclipse/EDT p2 и Maven-артефакты; он заметно дольше последующих.

## Отдельная EDT без установки PDE

```powershell
.\scripts\start-dev-edt.ps1
```

Скрипт читает установленную EDT, создаёт в `.runtime/development` отдельные configuration, user-home и workspace, подключает собранный bundle и CommonMark из p2 через отдельный `bundles.info` и запускает штатный EDT product. Исходная установка и её p2-профиль не обновляются. Вывод очередного запуска сохраняется в `.runtime/development/edt.log` и `edt.stderr.log`; журнал Eclipse по-прежнему находится в `workspace/.metadata/.log`. Это удобный запуск собранного JAR; горячей перекомпиляции и Java-отладки PDE здесь нет.

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

Открытие панели: **Window → Show View → Other… → Codex → Codex**. Перетащите вкладку вправо. Для отправки сообщения откройте проект с существующим физическим каталогом и его BSL-модуль.

## Codex и авторизация

Плагин ищет исполняемый файл в таком порядке: Java property `codex.edt.executable`, переменная `CODEX_EDT_EXECUTABLE`, PATH, локальная установка приложения OpenAI Codex в `%LOCALAPPDATA%/OpenAI/Codex/bin`. Для запуска из скрипта можно явно передать путь:

```powershell
.\scripts\start-dev-edt.ps1 -CodexExecutable 'C:\tools\codex\codex.exe'
```

Замените пример фактическим путём. В PDE добавьте `-Dcodex.edt.executable=...` в **Arguments → VM arguments**. Глобальные переменные менять не требуется.

При сообщении «Требуется вход в Codex» выполните в PowerShell `& 'полный путь к codex.exe' login`, завершите официальный вход, затем нажмите **Повторить подключение** в панели. Уже действующий вход используется через `account/read`. Плагин не читает auth-файлы и не хранит ключи. Вход через браузер из самой панели пока не реализован.

Протокол проверен по schema установленной версии 0.153.4. После обновления Codex нужно повторить проверки; гарантии совместимости с непроверенной версией нет. Детали — в [исследовании app-server](app-server-research.md).

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
2. **Local…** → каталог `repositories/io.github.zhumaniezov.codex.edt.repository/target/repository`, либо **Archive…** → ZIP из `target`.
3. Выберите **Codex → Codex for 1C:EDT**, затем **Next**.
4. Проверьте состав устанавливаемых компонентов и условия предварительной поставки; завершите мастер. Если EDT предупреждает о неподписанном артефакте, проверяйте, что это собранный вами плагин Codex.
5. Перезапустите EDT и откройте Codex через Show View.

Удаление: **Help → About → Installation Details → Installed Software → Codex for 1C:EDT → Uninstall…**, затем перезапуск. Установка в вашу обычную EDT в ходе разработки автоматически не выполняется.

## Дополнительная локальная проверка

```powershell
.\scripts\local-check.ps1
```

Сначала выполните `build.ps1`: CommonMark берётся из собранного p2, а не устанавливается глобально. Этот скрипт компилирует Java установленным `javac` против **активных версий** библиотек из `bundles.info`, упаковывает JAR и запускает штатный Eclipse p2 Publisher и SWT smoke harness. Результат сохраняется в новой папке `.runtime/local-<version>`. Это проверка библиотек именно локальной EDT; она не заменяет Maven/Tycho.

Harness запускает Eclipse workbench без автоматических сервисов БМ EDT и без пользовательских проектов. Он не доказывает работу специфического BSL-редактора EDT. Для чистой публикации без UI доступен параметр `-SkipSmoke`.

Полный EDT product можно проверить после Maven-сборки командой:

```powershell
.\scripts\start-dev-edt.ps1 -Smoke
```

Она запускает отдельную одноразовую workspace `.runtime/edt-smoke-<timestamp>`, подключает test bundle, выполняет тесты протокола, процессов и UI после старта EDT и закрывает окно. Результат — `result.txt` и `edt.log` в этой папке. В обычном интерактивном запуске test bundle не подключается. Не закрывайте тестовое окно до завершения сценария.

Отдельная проверка настоящего Codex:

```powershell
.\scripts\start-dev-edt.ps1 -Smoke -Live
```

Требуются существующая авторизация и доступ к сервису Codex. Тесты расходуют лимиты пользователя: проверяют сохранённое выделение, пустой файл на диске с несохранённой процедурой без выделения, затем выделение внутри этого буфера, серверную историю и resume. Запросы отправляются из View; проверяются streaming, физический cwd и SHA-256 всех файлов. Информационные базы не запускаются. `-Live` разрешён только вместе с `-Smoke`.

## Типовые проблемы

- **Maven not found** — проверьте наличие `bin/mvn.cmd`, передайте `-MavenHome`.
- **RequireJavaVersion** — проект проверяется на JDK 17; Java 25 предназначена для отдельной будущей цели EDT 2026.2.
- **Unknown packaging eclipse-plugin** — запускайте Maven из корня reactor, чтобы загрузилось Tycho extension.
- **Could not resolve target / download timeout** — проверьте доступ к Maven Central, `edt.1c.ru` и `download.eclipse.org`; полный журнал находится в `.runtime/logs`.
- **Нет Eclipse Application в Run As** — в IDE отсутствует PDE либо bundle импортирован не как Plug-in Project.
- **Нет Codex в Show View** — проверьте активный target, подключённый bundle/feature и вкладку Error Log.
- **Workspace is in use** — закройте предыдущую тестовую EDT; не удаляйте lock у запущенной среды.
- **Codex не найден** — укажите существующий `codex.exe` через `-CodexExecutable`.
- **Требуется вход в Codex** — выполните штатный `codex login` и переподключитесь.
- **Codex отключён / ошибка** — подробности в **Window → Show View → Error Log**, затем **Повторить подключение**. Не включайте полный доступ для исправления ошибки.
- **У проекта нет физического каталога** — откройте файловый проект EDT; путь вида `/Проект/src/...` является путём ресурса Eclipse и не подходит для cwd.

## Настройки панели 0.3

**⋯ → Codex for 1C:EDT** (либо Window → Preferences) открывает настройки Enter и диагностики. Они сохраняются только в Eclipse workspace. Выбор модели и reasoning действует в текущей сессии и не переписывает `~/.codex/config.toml`.

Для продолжения чата выберите строку в «Чаты». Если его проект находится в другом каталоге, откройте соответствующий модуль; если каталог удалён или недоступен, resume даст ошибку. Занятый другим клиентом thread может быть недоступен для возобновления — завершите работу с ним в исходном клиенте, не завершайте чужие процессы.

Maven location поддерживается современной PDE. Если старая среда разработки не разрешает CommonMark, используйте уже работающий запуск через `start-dev-edt.ps1`; обновление/установка среды PDE не выполняется автоматически.


## Проверка восстановления панели в двух запусках

После сборки выполните с новым ASCII-именем проверки:

```powershell
.\scripts\start-dev-edt.ps1 -Smoke -RestartPhase seed -RestartName restore-031 -CodexExecutable 'C:\projects\codex-edt\.runtime\missing-codex.exe'
.\scripts\start-dev-edt.ps1 -Smoke -RestartPhase restore -RestartName restore-031 -CodexExecutable 'C:\projects\codex-edt\.runtime\missing-codex.exe'
```

Первый процесс открывает View и завершает EDT, оставляя панель открытой. Второй использует ту же `.runtime/edt-restart-restore-031/workspace` и проверяет уже восстановленную панель. Несуществующий executable намеренно проверяет независимость UI от подключения. Для проверки реального подключения уберите `-CodexExecutable`. Эти команды не очищают persisted state.

Файлы результата: `result-seed.txt`, `result-restore.txt`; stdout/stderr сохраняются раздельно в `edt-<phase>.log` и `edt-<phase>.stderr.log`. Скрипт запускает процесс скрытым штатным `Start-Process` и проверяет завершение и результат. Это также устраняет ложную остановку PowerShell 5.1 на предупреждениях Java из stderr. В обычном smoke логи называются `edt.log` и `edt.stderr.log`.

Для диагностики копии настоящего EDT-проекта есть `-RestartProject 'имя проекта'`: на seed импортируется уже подготовленный каталог проекта внутри этой тестовой workspace. Настройка предназначена только для изолированной копии без подключений к информационным базам. Имя передаётся в диагностический JVM-параметр через UTF-8/Base64, чтобы Java 17 на Windows не исказила кириллицу в аргументном файле. Производственная View не использует этот параметр.
