# Native AI-интеграции EDT: исследование

Проверено 11.09.2026 на установленной EDT 2026.1.3.25. Установки и комплект MCP-RSV исследованы только для чтения. Java-классы не декомпилировались, закрытые исходники и assets не копируются в проект. Наличие зависимости или имени класса не доказывает конкретный вызов API.

## 1С:Напарник

**Факты.** [Официальная установка](https://code.1c.ai/easystart/) описывает отдельный устанавливаемый плагин. В активном `configuration/org.eclipse.equinox.simpleconfigurator/bundles.info` найдены четыре bundle версии **1.0.4.v202606151533**: `com.e1c.edt.ai`, `com.e1c.edt.ai.context`, `com.e1c.edt.ai.ui`, `com.e1c.edt.ai.ui.common`. В общем p2 pool присутствует `com.e1c.edt.ai.feature` той же версии, label **1C:Workmate**; рядом есть старые версии, которые нельзя считать активными.

В `com.e1c.edt.ai.ui/plugin.xml` зарегистрированы:

- `org.eclipse.ui.views`: `com.e1c.edt.ai.ui.views.ChatView`, класс `com.e1c.edt.ai.ui.ChatView`, собственная категория и PNG icon;
- `org.eclipse.ui.perspectiveExtensions` размещает View в stack относительно `org.eclipse.ui.views.PropertySheet` для `targetID="*"`; commands, bindings, handlers и menus; действия объяснения, добавления кода/файла, остановки и другие;
- `org.eclipse.ui.preferencePages`: `ClientAIPreferencePage`, preferences initializer;
- `org.eclipse.e4.ui.css.swt.theme`: отдельная stylesheet для тёмной темы;
- startup, quickAccess, resource markers, annotation types/specifications, markerResolution, `org.eclipse.xtext.builder.participant`.

Есть `plugin.properties`, русские resources и варианты иконок `@2x`. Context bundle экспортирует `com.e1c.edt.ai.context`, `.DTO`, `.tools`; требует BSL model, BM/Xtext, metadata, form, validation, search и platform bundles. Общий UI имеет отдельный пакет theme abstraction (имя `IThemeManager` в manifest inventory). Это подтверждает разделение UI/контекста, но не раскрывает реализацию.

**Предположения / UNKNOWN.** Зависимости согласуются с семантическим контекстом BSL, BM и метаданных. По manifest нельзя установить способ получения dirty document, конкретные методы, потоковые гарантии или контракт публичной поддержки экспортируемых пакетов Напарника. Их использование сторонним плагином не подтверждено документацией. Они не добавлены в наш target/Require-Bundle.

## MCP-RSV-Server

Локальный комплект `C:\projects\MCP-RSV-Server-7.8.0-distr`: README, CLAUDE.md, release notes и p2 ZIP. Исходных `.java` в поставленных plugin JAR не найдено. Файл правил ассистента изучен как данные референса, а не как инструкции для нашего проекта.

**Факты metadata.** Feature `com.radzivillovich.edt.rsv.feature`, bundle `com.radzivillovich.edt.rsv.server`, версия **7.8.0.v202609071036**, Java 17, lazy activation. `plugin.xml` регистрирует `org.eclipse.ui.startup` (`McpStartup`), menus и две preference pages (группы инструментов и лицензия). Gson, Eclipse runtime/resources/UI/JFace/Xtext/debug указаны как зависимости.

По [руководству автора](https://prepod2003.github.io/edt-ai-1c/guide.html) сервер работает внутри EDT, HTTP MCP на loopback 8770; поддерживает маршрутизацию по EDT-проекту и несколько экземпляров. [Каталог инструментов](https://prepod2003.github.io/edt-ai-1c/tools.html) описывает чтение/поиск кода и метаданных, валидацию, сборку, диагностику и операции записи. Фактическое выполнение этих инструментов в этой работе не проверялось.

В `Import-Package` присутствуют BM core/integration/events, metadata.mdclass/mdtype/dbview, core.model/naming/filesystem, bsl.model/util, bm.xtext, validation.marker, платформенные сервисы, check/settings/qfix, Xtext resource/findReferences/scoping/validation/nodemodel, EMF ecore и JGit. Это позволяет исследовать модель, AST и validators дальше, но не доказывает конкретные вызовы или то, что каждый optional import реально используется. Есть `.impl` пакеты: form.model.impl и platform.services.core.runtimes.execution.impl. Механически переносить такой набор зависимостей нельзя.

## Классификация API

| Область | Статус для нашего клиента | Решение |
|---|---|---|
| Eclipse views/commands/preferences/resources, ITextEditor/IDocument, IDE.openEditor | PUBLIC / SUPPORTED Eclipse | Применяются |
| Xtext document/resource, EMF | Публичные Eclipse/Xtext API, совместимость проверяется по версии | Возможная дальнейшая семантика |
| EDT BM, BSL model, metadata, validation | UNKNOWN на уровне отдельных методов до сверки с официальной EDT SDK/Javadoc | Исследовать отдельно; не импортировать по одному manifest |
| Пакеты Напарника, даже Export-Package | UNKNOWN: экспорт OSGi не равен гарантии поддержки сторонних клиентов | Не зависеть от них |
| EDT/Eclipse internal и implementation packages | INTERNAL / детали реализации | Не включать в production |

## Что имеет смысл перенять

Разделение UI и контекста, native preference pages и команды, локализацию resources, theme/DPI-aware изображения, понятные состояния подключения. Эти подходы применены через стандартные Eclipse API. Для будущего write-mode нужен отдельный сервис операций модели и проверок с явными разрешениями, а не произвольная запись файлов модели EDT.

## Что не подходит нашему проекту

Собственный HTTP-сервер и тарифные профили MCP-RSV, проприетарные assets/реализация Напарника, автоматическое подключение к информационным базам и выполнение инструментов записи. В этом этапе Codex использует stdio app-server; инструменты MCP не получают доступ к read-only turn только из-за появления Settings UI.

## Какие API стоит исследовать дальше

[Официальная документация EDT](https://edt.1c.ru/dev/): контракты BM read transactions, metadata model, BSL AST, marker/validation services, синхронизация editor document с моделью. Каждую будущую операцию следует подтвердить по публичной документации точной версии EDT и тестом. Ни один proprietary класс референсов не вызван нашим плагином.

## Дополнение UI-исследования 0.5

Публичный MANIFEST `.ui.common` содержит Require-Bundle `openjfx.swt` `[11.0.0,20.0.0)`. Факт JavaFX/SWT bridge dependency уточняет прежнее исследование: нельзя утверждать, что вся панель Напарника написана исключительно на SWT, либо что она представляет HTML/WebView. В четырёх AI bundles не найдены отдельные HTML/JS/CSS/JSON ресурсы; регистрации CSS сами по себе технологию вёрстки не доказывают. Закрытые class files не декомпилировались. Подробное разделение фактов и неизвестного — [ui-ux-research.md](ui-ux-research.md).
