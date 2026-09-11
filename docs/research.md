# Исследование Codex for 1C:EDT

Этот документ сохраняет решения **первого этапа 0.1**. Для текущей версии 0.2 см. [исследование app-server](app-server-research.md), [архитектуру](architecture.md) и [результаты тестов](testing.md). Ручная проверка автором в EDT 2026.1.3 подтвердила панель и контекст настоящего BSL-редактора.

Основное исследование выполнено 11 сентября 2026 года до написания реализации. Ссылки OpenAI добавлены при подготовке публикации для плана следующего этапа. Целевая ОС первого этапа — Windows x86_64. Сведения о статусе релизов ниже относятся к дате исследования.

## Решение по версиям

На официальном сайте стабильной версией указана **EDT 2026.1.3**, а **2026.2.0 — релиз-кандидат**. Локально установлена EDT **2026.1.3.25**. Поэтому первая версия плагина ориентирована на 2026.1.3; совместимость с 2026.2 требует отдельной проверки.

| Компонент | Выбор для проекта | Основание |
| --- | --- | --- |
| EDT | 2026.1.3, локальный build 2026.1.3.25 | `configuration/config.ini` установленной EDT |
| Java | JDK 17 Full/FX, JavaSE-17 в bundle | `1cedt.ini`, пример 1С для 2026.1 |
| Локальная Java | AxiomJDK Pro Full 17.0.18+10 | `java -version`, `C:\Program Files\Axiom\AxiomJDK-Pro-17-Full` |
| Maven | 3.9.4 или новее в ветке 3.9.x | BOM примера 1С для 2026.1 |
| Tycho | 4.0.5 | тот же BOM |
| Eclipse target | 2023-12 / Platform 4.30 | target примера и локальный `bundles.info` |
| p2 EDT | `https://edt.1c.ru/downloads/releases/ruby/2026.1/` | target примера 1С для 2026.1 |

Репозиторий `1C-Company/dt-example-plugins` изучен локально, включая историю. Эталон для 2026.1 — commit **921c30ca006d05895d4d599091ea3e55a8afea69** от 31.03.2026. Текущий master **ae9c1f06a01de4f3ee7fe32bf35e284f25e3915f** от 15.07.2026 уже переключён на Java 25, Tycho 5.0.2, Maven 3.9.9 и target EDT 2026.2 / Eclipse 2025-12. Автоматически использовать его версии для установленной EDT нельзя.

Страница настройки среды в руководстве всё ещё упоминает Eclipse 2022-03 и Maven 3.6.3+. Это расходится с версионным примером. Для сборки приоритет отдан конкретному коммиту под 2026.1. Версия IDE для разработки и Target Platform — разные вещи: плагины компилируются против выбранного target, а не произвольного набора библиотек IDE.

## Официальные источники

1. [Руководство разработчика плагинов 1С](https://edt.1c.ru/dev/ru/) — Eclipse extension points, Xtext, публичные сервисы EDT.
2. [Настройка среды](https://edt.1c.ru/dev/ru/docs/plugins/project/env-setup/) — специальная среда Eclipse через EDT Start, JDK Full/FX, Maven. Указанная там настройка авторизации p2 относится к EDT до 2021.1.
3. [Структура проекта](https://edt.1c.ru/dev/ru/docs/plugins/project/project-structure/) — BOM, bundles, features, targets, repositories, tests.
4. [Запуск из Eclipse](https://edt.1c.ru/dev/ru/docs/plugins/project/run/) — Run As → Eclipse Application.
5. [Сборка и установка](https://edt.1c.ru/dev/ru/docs/plugins/project/build-install-publish-project/) — Maven/Tycho, p2, Help → Install New Software.
6. [UI плагинов EDT](https://edt.1c.ru/dev/ru/docs/plugins/dev/ui/) — SWT/JFace и механизмы Eclipse.
7. [Официальный репозиторий примера](https://github.com/1C-Company/dt-example-plugins).
8. [BOM для EDT 2026.1](https://github.com/1C-Company/dt-example-plugins/blob/921c30ca006d05895d4d599091ea3e55a8afea69/bom/pom.xml).
9. [Target для EDT 2026.1](https://github.com/1C-Company/dt-example-plugins/blob/921c30ca006d05895d4d599091ea3e55a8afea69/targets/default/default.target).
10. [Launch configuration примера](https://github.com/1C-Company/dt-example-plugins/blob/921c30ca006d05895d4d599091ea3e55a8afea69/bundles/org.example.ui/plugin.launch).
11. [Получение файла и проекта в примере](https://github.com/1C-Company/dt-example-plugins/blob/921c30ca006d05895d4d599091ea3e55a8afea69/bundles/org.example.ui/src/org/example/ui/DataProcessingHandler.java) — адаптер редактора, `IFileEditorInput`, `IFile.getProject()`.
12. [Сайт EDT](https://edt.1c.ru/), [изменения 2026.1](https://edt.1c.ru/docs/new/versiya-2026-1/), [изменения 2026.2](https://edt.1c.ru/docs/new/versiya-2026-2/) — статус релиза, переход 2026.2 на Java 25.
13. [Системные требования](https://edt.1c.ru/docs/intro/requirements.php) — с EDT 2025.1 Java поставляется установщиком.
14. [Eclipse: регистрация View](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/extension-points/org_eclipse_ui_views.html).
15. [Eclipse: ITextEditor](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/ui/texteditor/ITextEditor.html), [ResourceUtil](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/ui/ide/ResourceUtil.html), [MultiPageEditorPart](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/ui/part/MultiPageEditorPart.html).
16. [Eclipse: p2 publisher](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/p2_publisher.html) — официальный запасной путь получения p2 из скомпилированных bundle/feature.
17. [Tycho: p2 repository](https://tycho.eclipseprojects.io/doc/4.0.10/tycho-p2-repository-plugin/plugin-info.html) — assemble/archive/verify, системные требования ветки 4.0.x.
18. [Apache Maven: загрузка](https://maven.apache.org/download.cgi) — на момент проверки стабильный Binary zip 3.9.16.
19. [Target Platform 1С](https://edt.1c.ru/dev/ru/docs/plugins/dev/target-platform/) — актуальные targets следует брать из официального примера; доступ к современным p2 без авторизации.
20. [Импорт примера](https://edt.1c.ru/dev/ru/docs/plugins/project/copy-clone/) — проект типа «Плагины для 1С:EDT» в EDT Start.
21. [OpenAI: Codex IDE extension](https://learn.chatgpt.com/docs/ide) — ориентир интерфейса панели и работы с контекстом редактора. Код VS Code extension не использован.
22. [OpenAI: Codex App Server](https://learn.chatgpt.com/docs/app-server) — сверены назначение сервера, обмен сообщениями JSON-RPC, диалоги и уведомления. Использовано только для плана будущего клиентского слоя; сервер не запускался и не подключался.

## Локальная среда

Рабочий каталог изначально пуст. Проверены PATH, реестр установок, стандартные каталоги Program Files, LocalAppData/Programs, 1C/1CE, EDT Start installations, инструменты Maven в стандартных каталогах и Downloads. Другие проекты в `C:\projects` не исследовались.

Найдены:

- `%LOCALAPPDATA%\1C\1cedtstart\installations\1C_EDT 2026.1\1cedt`;
- библиотеки этой установки по её `configuration/org.eclipse.equinox.simpleconfigurator/bundles.info` в общем p2 pool;
- JDK 17.0.18 Full и поставляемый EDT Start Axiom Full 17.0.16;
- Git 2.53.0;
- штатные Eclipse p2 Publisher/Director в EDT.

Отдельные Apache Maven CLI и Eclipse PDE / «Плагины для 1C:EDT» не найдены. Они автоматически не устанавливаются. Существующие установки и глобальный p2 pool используются только для чтения; результаты проверок и отдельные runtime workspace/configuration должны размещаться внутри этого проекта.

В ходе работы пользователь самостоятельно распаковал Maven 3.9.16 в `C:\projects\codex-edt\apache-maven-3.9.16-bin\apache-maven-3.9.16`. Его CLI проверен и используется для полной сборки. Скрипт `build.ps1` находит этот путь автоматически; Maven и его зависимости исключены из Git. Среда PDE по-прежнему не установлена; для запуска доступен дополнительный скрипт с отдельной configuration установленной EDT.

## Архитектура и границы первого этапа

`EDT → CodexView (SWT/JFace ViewPart) → CodexClient → MockCodexClient`.

`org.eclipse.ui.views` регистрирует обычную перемещаемую View. SWT предоставляет многострочный ввод, кнопку Send и вывод. Асинхронный интерфейс клиента принимает обычные Java-данные. В поставке **0.1** сетевого транспорта, процессов Codex и авторизации не было; во втором этапе этот интерфейс используется для app-server.

Для контекста достаточно публичных Eclipse API: активный редактор, адаптер `ITextEditor`, `ResourceUtil` / `IFile`, `IProject`, `ITextSelection`. Это позволяет получить ресурс проекта и выделение в памяти, включая несохранённые изменения. Собственные классы и внутренние пакеты EDT не нужны. Проект означает workspace-проект файла; семантическую принадлежность к `IV8Project` первая версия не определяет. В неоднозначном случае показывается отсутствие контекста, а не угаданное имя.

Контекст снимается при Send на UI-потоке. Перевод фокуса в панель не должен подменять выделение редактора вводом чата. Для сравнения, бинарных модулей и редакторов без публичного адаптера результат может быть неполным. Чтение БМ/метаданных и подключение к информационной базе не требуются. Версия платформы 1С:Предприятие для этой задачи не выбирается.

## План реализации и проверки

1. Создать Maven reactor по структуре примера: BOM, bundle, feature, target, p2 repository и отдельные тесты.
2. Реализовать нативную View и изолированный mock client; читать контекст через публичные API.
3. Добавить launch configuration для EDT product `com._1c.g5.v8.dt.product.application.rcp`, JavaSE-17 и отдельной workspace.
4. Проверить XML/manifest, выполнить доступную компиляцию и проверку OSGi/публичных зависимостей на локальной EDT.
5. Попытаться выполнить Maven/Tycho; при отсутствии Maven зафиксировать блокировку и точные действия для установки. Дополнительный локальный publisher не считать подтверждением Tycho build.
6. Проверить создание p2 и работу View настолько, насколько позволяет установленная среда. Отдельно описать непроверенные сценарии настоящего BSL-редактора.

Результаты выполненных проверок зафиксированы в `docs/testing.md`, команды — в `docs/build.md`.
