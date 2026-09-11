# Исследование UI/UX: версия 0.5

Baseline: `36e9baf`, v0.4 принята вручную. Исследование выполнено 11.09.2026 до реализации редизайна.

## Официальный Codex IDE

[Codex IDE](https://learn.chatgpt.com/docs/codex/ide) показывает sidebar с компактными действиями, областью диалога и composer, где рядом находятся модель, режим и контекст. [Prompting](https://learn.chatgpt.com/docs/prompting) описывает открытые файлы, выделение и явные ссылки на workspace paths. Контекстные chips делают приложенный материал видимым до отправки. Списки разговоров и серверные threads отделены от текущего turn; техническая диагностика не должна занимать основной экран.

[Sandbox](https://learn.chatgpt.com/docs/sandboxing) показывает permissions control под composer: запрос подтверждения, делегирование подтверждений, полный доступ и профили зависят от конфигурации. `approval_policy:never` означает отсутствие интерактивного запроса, а не разрешение всех действий. Для нашей политики read-only расширение прав не предоставляется. Внешний вид кнопки не должен менять эту семантику.

[OpenVSX](https://open-vsx.org/extension/openai/chatgpt) использован как продуктовый ориентир. Частный код расширения и assets не копируются. Публичный [openai/codex](https://github.com/openai/codex), локальная исследовательская копия `rust-v0.153.4` и schema установленного **0.153.4** — источник протокола. `UserInput` включает text, image/localImage, audio/localAudio, skill, mention; универсальный upload документа не подменяем типом image или недоказанной семантикой mention. Для файлов в текущем cwd достаточно дополнительного текстового перечня путей: агент читает сохранённые файлы штатно. Прикрепление — ссылка, не загрузка и не снимок несохранённого другого редактора.

Точные пиксельные размеры официального extension не являются публичным контрактом. Собственная композиция: компактный header; короткий список с активной строкой; свободная область диалога; выделенный скруглённый composer; режимы и контекст над нижней строкой выбора модели; круглая основная кнопка. Контекст выбора виден, второстепенные действия малозаметны, tooltip поясняют иконки.

## 1С:Напарник: факты и границы исследования

[Официальное подключение](https://code.1c.ai/easystart/) и metadata установленной EDT исследованы только для чтения. Bundles `com.e1c.edt.ai`, `.context`, `.ui`, `.ui.common` версии `1.0.4.v202606151533`. `plugin.xml` регистрирует ChatView, perspective stack рядом с PropertySheet, commands/handlers/menus, preferences и CSS theme extension. Иконки имеют light/dark и @2x варианты. Есть штатные startup hooks.

**Новая находка:** Require-Bundle у `.ui.common` содержит `openjfx.swt` диапазона `[11.0.0,20.0.0)` наряду с Eclipse UI Forms и CSS theme. Это факт зависимости от моста JavaFX/SWT. В четырёх исследованных JAR не найдены отдельные HTML/HTM/JS/CSS/JSON ресурсы. Регистрация CSS у `.ui` есть, но само наличие registration не доказывает web UI. Контент может генерироваться программно или поставляться другой зависимостью.

**Не установлено:** используется ли JavaFX для всей панели, отдельных контролов либо Browser/WebView; точная реализация layout и обработки document buffer. Class files не декомпилируются и не анализируются для копирования. Поэтому утверждать «Напарник полностью native SWT» или «это HTML UI» нельзя. Подробные extension points и API-классификация — [edt-ai-references.md](edt-ai-references.md).

## Решение: native SWT/JFace

Оставляем SWT Text/Combo/Table/StyledText для редактирования, выбора, истории и accessibility. Небольшие Canvas используются для геометрии icon buttons и круглой основной кнопки, с keyboard/accessible action/state/focus. ThemePalette вычисляет уровни поверхности, границ и выделения из текущих цветов Eclipse; ресурсы принадлежат View и освобождаются при dispose. Layout реагирует на узкую колонку, шрифты остаются JFace.

Это позволяет добиться нужной иерархии без Browser, JavaFX/WebView, JS bridge, HTML sanitization и новых p2 runtime dependencies. Browser теоретически удобнее для богатой вёрстки сообщений, сложных таблиц и вложенных карточек, но здесь увеличивает риски темы, фокуса, screen reader и запуска EDT без функциональной необходимости.

## Что переносим и чего избегаем

| Подход | Решение |
|---|---|
| Composer как отдельная поверхность, круглая основная кнопка, компактные режимы | Реализовать нативно |
| Контекст/вложения в виде видимых удаляемых chips | Ссылки только на существующие файлы текущего проекта, максимум 5; проверка real path, без копирования содержимого |
| Роли, интервалы сообщений, различимые code blocks | StyledText + presentation metadata, без тяжёлых карточек |
| Активный чат, относительное время, полное название в tooltip | Штатная Table с оформлением строк |
| Approval panel и policy chip | Foundation, без wiring новых RPC; read-only не допускает auto-approve |
| Общий подход к темам, ресурсам и settings Напарника | Использовать публичные Eclipse API, собственные ресурсы |
| Закрытые Java-классы, assets, internal EDT UI API | Не использовать |
| WebView/HTML ради визуального сходства | Не добавлять |

Backend methods и sandbox не расширяются. Существующие lifecycle, turn routing и серверная история сохраняются. Внешние файлы вне проекта, uploads, multi-editor dirty attachments, write mode и применение diff не входят в этот этап.

## Использованные публичные Eclipse API

[StyledText](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/swt/custom/StyledText.html): диапазоны стилей, фон/отступ строк, позиции для прокрутки, выделение и ссылки. [Accessible](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/swt/accessibility/Accessible.html): имя, роль, состояние и action для Canvas-кнопки. API проверены компиляцией против SWT установленной EDT; например, disabled-state использует `ACC.STATE_DISABLED`.

Реализация использует собственные геометрические glyphs для header/Send/Stop/plus/remove, логические размеры 28/36, antialias и focus ring. Это наш Java-код под MIT проекта. Официальные assets не добавлены; прежняя иконка View сохранена. Размеры и интервалы задаются в SWT logical units, не в физических пикселях снимка.

Проектный выбор в меню + открывает штатный FileDialog в cwd. Переход диалога за пределы проекта возможен как операция просмотра, но выбранный внешний файл не принимается. Проверка real path повторяется перед отправкой. Автоподтверждение явно недоступно; панель будущего запроса не подключена к серверу. Такое поведение не выдаётся за полноценную поддержку uploads/approvals.
