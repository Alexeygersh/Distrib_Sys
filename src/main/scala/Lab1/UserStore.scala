package Lab1

import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.time.LocalDateTime

// Одна запись о пользователе
case class UserRecord(id: Int, name: String, firstSeen: String, lastSeen: String)

object UserStore:
  private val path = Paths.get(Config.MainStorage, "users.json")
  private var nextIdValue: Int = 1
  private val users = scala.collection.mutable.Map[Int, UserRecord]()

  // Загружаем список при старте сервера
  def load(): Unit =
    if Files.exists(path) then
      val lines = Files.readAllLines(path, StandardCharsets.UTF_8).toArray.map(_.toString)
      // формат: id|name|firstSeen|lastSeen
      for line <- lines if line.nonEmpty do
        val Array(id, name, first, last) = line.split("\\|", 4)
        users(id.toInt) = UserRecord(id.toInt, name, first, last)
        if id.toInt >= nextIdValue then nextIdValue = id.toInt + 1
    println(s"[Сервер] Загружено пользователей: ${users.size}, следующий id = $nextIdValue")

  // Сохраняем на диск
  def save(): Unit =
    val text = users.values.map(u => s"${u.id}|${u.name}|${u.firstSeen}|${u.lastSeen}").mkString("\n")
    Files.writeString(path, text, StandardCharsets.UTF_8)

  // Выдать новый id и зарегистрировать пользователя
  def register(name: String): Int = synchronized:
    val now = LocalDateTime.now().toString
    val id  = nextIdValue
    nextIdValue += 1
    users(id) = UserRecord(id, name, now, now)
    save()
    id

  // Обновить время последнего входа (если такой пользователь уже есть)
  def touch(id: Int): Unit = synchronized:
    users.get(id).foreach: u =>
      users(id) = u.copy(lastSeen = LocalDateTime.now().toString)
      save()
