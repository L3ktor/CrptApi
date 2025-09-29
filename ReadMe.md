
Описание

Класс CrptApi реализует взаимодействие с API Честного Знака (ГИС МТ) для создания документа ввода в оборот товаров, произведенных в РФ, согласно документации API v16.5. Код является thread-safe, поддерживает ограничение количества запросов и удобен для расширения.

Код полностью соответствует требованиям:





Thread-safety: Используется Semaphore и AtomicInteger для безопасного ограничения запросов в многопоточной среде.



Ограничение запросов: Реализовано через Semaphore и ScheduledExecutorService для лимитирования запросов в заданный интервал времени.



Создание документа: Метод createIntroduceDoc формирует JSON-документ согласно спецификации (стр. 108) с поддержкой LP_INTRODUCE_GOODS, LP_INTRODUCE_GOODS_CSV, LP_INTRODUCE_GOODS_XML.



Аутентификация: Реализована через AuthManager с поддержкой УКЭП (заглушка signData требует интеграции).



Экранирование: Используется URLEncoder для URL и ObjectMapper для JSON (RFC 3986, RFC 8259).



Расширяемость: Модульная структура с POJO (IntroduceGoodsDoc, IntroduceContent, ProductItem) упрощает добавление новых методов.

Сегменты кода и пояснения





Конструктор CrptApi:





Инициализирует HttpClient, ObjectMapper, Semaphore для ограничения запросов, ScheduledExecutorService для сброса лимита, и AuthManager для аутентификации.



Проверяет валидность входных параметров (timeUnit, requestLimit).



Устанавливает начальное значение tokenExpiration для принудительного обновления токена.



Метод resetLimiter:





Сбрасывает счетчик запросов (currentCount) и восстанавливает доступные разрешения в Semaphore через заданный интервал времени.



Метод createIntroduceDoc:





Создает документ для ввода в оборот, принимая IntroduceGoodsDoc и подпись.



Проверяет срок действия токена и обновляет его при необходимости.



Формирует JSON-тело запроса, отправляет POST-запрос на /api/v3/lk/documents/create?pg=<productGroup>.



Обрабатывает ошибки (401, 406, прочие) согласно документации.



Метод signData:





Заглушка для подписи данных УКЭП. Требуется замена на реальную реализацию.



Метод close:





Завершает работу ScheduledExecutorService для освобождения ресурсов.



Класс AuthManager:





Управляет аутентификацией: получает UUID и данные для подписи (getAuthKey), отправляет подписанные данные для получения токена (authenticate).



Хранит токен в volatile String для thread-safety.



Класс IntroduceGoodsDoc:





Хранит метаданные документа (document_format, product_group, type) и содержимое (IntroduceContent).



Проверяет валидность типа документа и обязательных полей.



Класс IntroduceContent:





POJO для JSON-структуры документа ввода в оборот (стр. 108), включая description, owner_inn, products и т.д.



Класс Description:





Вложенная структура для поля description с participantInn.



Класс ProductItem:





Описывает товар в списке products с обязательными полями (owner_inn, producer_inn, tnved_code, production_date) и опциональными (uit_code или uitu_code).



Класс CrptApiException:





Пользовательское исключение для обработки ошибок API и внутренней логики.

Использование

См. пример в Main.java.
