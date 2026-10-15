package Lab1

import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.time.LocalDateTime
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

// Одна запись о пользователе
case class UserRecord(id: Int, name: String, firstSeen: String, lastSeen: String)

// Хранилище пользователей. Все публичные методы synchronized: с ним одновременно
// работают потоки разных клиентов, поэтому в каждый момент внутри только один поток.
object UserStore:
  private val path = Paths.get(Config.MainStorage, Config.UsersFile)
  private val tmp = Paths.get("users.tmp")   // временный файл для безопасной записи
  private var nextIdValue: Int = 1
  private val users = mutable.Map[Int, UserRecord]()

  // Символ '|' и переводы строк ломают формат файла — заменяем их
  private def clean(name: String): String = name.replaceAll("[|\\r\\n]", "_")

  // Загружаем список при старте сервера
  def load(): Unit = synchronized:
    if Files.exists(path) then
      for line <- Files.readAllLines(path, StandardCharsets.UTF_8).asScala if line.nonEmpty do
        line.split("\\|", 4) match
          case Array(id, name, first, last) =>
            users(id.toInt) = UserRecord(id.toInt, name, first, last)
            if id.toInt >= nextIdValue then nextIdValue = id.toInt + 1
          case _ => println(s"[Сервер] Пропущена повреждённая строка: $line")
    println(s"[Сервер] Загружено пользователей: ${users.size}, следующий id = $nextIdValue")

  // Сохраняем на диск: пишем во временный файл и подменяем основной,
  // чтобы при сбое не остался наполовину записанный файл
  private def save(): Unit =
    val text = users.values.toList.sortBy(_.id)
      .map(u => s"${u.id}|${u.name}|${u.firstSeen}|${u.lastSeen}").mkString("\n")
    Files.writeString(tmp, text, StandardCharsets.UTF_8)
    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)

  // Создать нового пользователя с новым id
  def register(name: String): UserRecord = synchronized:
    val now = LocalDateTime.now().toString
    val rec = UserRecord(nextIdValue, clean(name), now, now)
    nextIdValue += 1
    users(rec.id) = rec
    save()
    rec

  // Вход клиента. requestedId — id, который клиент запомнил с прошлого раза (0 = нет).
  // Если такой пользователь с таким именем есть — это возвращение (true), иначе
  // регистрируем нового (false).
  def login(name: String, requestedId: Int): (UserRecord, Boolean) = synchronized:
    users.get(requestedId) match
      case Some(u) if u.name == clean(name) =>
        val updated = u.copy(lastSeen = LocalDateTime.now().toString)
        users(u.id) = updated
        save()
        (updated, true)
      case _ =>
        (register(name), false)

  // Обновить время последней активности (вызывается при выходе клиента)
  def touch(id: Int): Unit = synchronized:
    users.get(id).foreach: u =>
      users(id) = u.copy(lastSeen = LocalDateTime.now().toString)
      save()