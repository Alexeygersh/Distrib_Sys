package Lab1

import java.io.*
import java.net.*
import java.nio.file.*
import java.util.concurrent.*
import scala.jdk.CollectionConverters.*

// ---------------------------------------------------------------------------
// Сессия одного подключённого клиента: id + имя + поток для отправки ему данных
// ---------------------------------------------------------------------------
class ClientSession(val id: Int, val name: String, out: DataOutputStream):
  def label: String = s"$name#$id"   // как показываем клиента в логах и уведомлениях

  // Писать в сокет клиента могут сразу несколько потоков (его собственный обработчик
  // и потоки других клиентов, рассылающие уведомления), поэтому блокируем out.
  def send(msgType: String, text: String): Unit =
    out.synchronized:
      out.writeUTF(msgType)
      out.writeUTF(text)
      out.flush()

  // Отправка файла клиенту (скачивание)
  def sendFile(fileName: String, path: Path): Unit =
    out.synchronized:
      out.writeUTF("FILE")
      out.writeUTF(fileName)
      out.writeLong(Files.size(path))
      Files.copy(path, out)
      out.flush()

// ---------------------------------------------------------------------------
// Обработка одного клиента (выполняется в отдельном потоке на каждого клиента)
//
// Клиент -> сервер:
//   имя клиента (UTF), сохранённый id (int, 0 = нет) — сразу после подключения
//   "UPLOAD", имя файла, размер (long), байты  — загрузить файл на сервер
//   "LIST"                                     — запросить список файлов
//   "DOWNLOAD", имя файла                      — скачать файл
//   "QUIT"                                     — выйти
//
// Сервер -> клиент:
//   (тип, текст), где тип: "WELCOME" (текст = "id|статус", статус: NEW/RETURNING/BUSY),
//                          "OK", "ERR", "NEW_FILE", "FILES"
//   "FILE", имя файла, размер (long), байты    — ответ на DOWNLOAD
// ---------------------------------------------------------------------------
object ClientHandler:
  // Список активных клиентов: ключ — id. ConcurrentHashMap безопасна для многих потоков.
  val clients = ConcurrentHashMap[Int, ClientSession]()
  // id выдаёт UserStore (под блокировкой synchronized), поэтому отдельный счётчик не нужен

  def handle(socket: Socket): Unit =
    var session: Option[ClientSession] = None
    try
      val in = DataInputStream(BufferedInputStream(socket.getInputStream))
      val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream))

      val name = in.readUTF()   // имя клиента
      val savedId = in.readInt()   // id, который клиент запомнил с прошлого раза (0 = нет)

      // Узнаём пользователя по паре (имя, id) или регистрируем нового
      val (rec, returning) = UserStore.login(name, savedId)
      var s = ClientSession(rec.id, rec.name, out)
      var status = if returning then "RETURNING" else "NEW"

      // Если этот id уже в сети, putIfAbsent вернёт существующую сессию — тогда выдаём новый id
      if clients.putIfAbsent(s.id, s) != null then
        val fresh = UserStore.register(name)
        s = ClientSession(fresh.id, fresh.name, out)
        clients.put(s.id, s)
        status = "BUSY"
      session = Some(s) // сессия создана

      println(s"[Сервер] Клиент подключился: ${s.label} ($status)")
      printClients()
      s.send("WELCOME", s"${s.id}|$status")   // сообщаем клиенту его id и статус

      var running = true
      while running do
        in.readUTF() match
          case "UPLOAD"   => receiveFile(in, s)
          case "LIST"     => sendList(s)
          case "DOWNLOAD" => sendRequestedFile(in.readUTF(), s)
          case "QUIT"     => running = false
          case other      => s.send("ERR", s"Неизвестная команда: $other")
    catch
      case e: IOException => println(s"[Сервер] Связь с клиентом потеряна: ${e.getMessage}")
    finally
      // Клиент ушёл — убираем из списка активных
      session.foreach: s =>
        UserStore.touch(s.id) // запоминаем время последней активности
        clients.remove(s.id)
        println(s"[Сервер] Клиент отключился: ${s.label}")
        printClients()
      socket.close()

  // Приём файла от клиента и сохранение в папку сервера
  private def receiveFile(in: DataInputStream, s: ClientSession): Unit =
    val rawName = in.readUTF()
    val size = in.readLong()
    // getFileName оставляет только имя файла (защита от путей вида ../../x)
    val fileName = Paths.get(rawName).getFileName.toString

    if Config.ProtectedFiles.contains(fileName) then
      // Клиент уже прислал заголовок и начал слать байты.
      // Их нужно вычитать из сокета, иначе они останутся
      // в буфере и следующий in.readUTF() прочитает мусор вместо команды.
      IoUtil.skipExactly(in, size)
      s.send("ERR", s"Файл '$fileName' защищён и не может быть загружен")
      println(s"[Сервер] Отклонена загрузка защищённого файла '$fileName' от ${s.label}")
      return
    val target = Paths.get(Config.MainStorage, fileName)

    val fos = BufferedOutputStream(Files.newOutputStream(target))
    try IoUtil.copyExactly(in, fos, size)
    finally fos.close()

    println(s"[Сервер] Получен файл '$fileName' ($size байт) от ${s.label}")
    s.send("OK", s"Файл '$fileName' сохранён на сервере")
    notifyOthers(s, fileName)


  // Отправка клиенту списка файлов, лежащих на сервере
  private def sendList(s: ClientSession): Unit =
    val files = Files.list(Paths.get(Config.MainStorage)).iterator().asScala
      .filter(Files.isRegularFile(_))
      .map(p => s"  ${p.getFileName} (${Files.size(p)} байт)")
      .toList
    val text = if files.isEmpty then "На сервере пока нет файлов" else "Файлы на сервере:\n" + files.mkString("\n")
    s.send("FILES", text)

  // Отправка клиенту запрошенного файла (скачивание)
  private def sendRequestedFile(rawName: String, s: ClientSession): Unit =
    val fileName = Paths.get(rawName).getFileName.toString

    if Config.ProtectedFiles.contains(fileName) then
      s.send("ERR", s"Файл '$fileName' защищён и недоступен для скачивания")
      println(s"[Сервер] Отклонено скачивание защищённого файла '$fileName' клиентом ${s.label}")
    else
      val path = Paths.get(Config.MainStorage, fileName)
      if !Files.isRegularFile(path) then
        s.send("ERR", s"Файла '$fileName' нет на сервере")
      else
        s.sendFile(fileName, path)
        println(s"[Сервер] Файл '$fileName' отправлен клиенту ${s.label}")

  // Уведомляем остальных активных клиентов о новом файле
  private def notifyOthers(uploader: ClientSession, fileName: String): Unit =
    for c <- clients.values.asScala if c.id != uploader.id do
      try c.send("NEW_FILE", s"Новый файл '$fileName' (загрузил ${uploader.label})")
      catch case _: IOException => ()

  private def printClients(): Unit =
    println(s"[Сервер] Активные клиенты: ${clients.values.asScala.map(_.label).mkString(", ")}")

// ---------------------------------------------------------------------------
// Репликация: раз в SyncIntervalSec секунд рассылаем все файлы по UDP Multicast
// Формат одного пакета:
//   имя файла (UTF), размер файла (long), номер куска (int), всего кусков (int),
//   длина данных (int), данные
// Большие файлы разделяются по ChunkSize байт.
// ---------------------------------------------------------------------------
object Replicator:
  private val scheduler = Executors.newSingleThreadScheduledExecutor() // отдельный поток-таймер

  def start(): Unit =
    val task: Runnable = () =>
      try syncAll()
      catch case e: Exception => println(s"[Репликация] Ошибка: ${e.getMessage}")
    scheduler.scheduleAtFixedRate(task, 10, Config.SyncIntervalSec, TimeUnit.SECONDS)

  private def syncAll(): Unit =
    val files = Files.list(Paths.get(Config.MainStorage)).iterator().asScala
      .filter(p => Files.isRegularFile(p) && !Config.ProtectedFiles.contains(p.getFileName.toString))
      .toList
    println(s"[Репликация] Рассылаю файлов: ${files.size}")
    val socket = DatagramSocket()
    try
      val group = InetAddress.getByName(Config.MulticastGroup)
      for f <- files do
        val data = Files.readAllBytes(f)
        val chunks = math.max(1, (data.length + Config.ChunkSize - 1) / Config.ChunkSize)
        for i <- 0 until chunks do
          val from = i * Config.ChunkSize
          val len  = math.min(Config.ChunkSize, data.length - from)

          val bytes = ByteArrayOutputStream()
          val dos = DataOutputStream(bytes)
          dos.writeUTF(f.getFileName.toString)
          dos.writeLong(data.length.toLong)
          dos.writeInt(i)
          dos.writeInt(chunks)
          dos.writeInt(len)
          dos.write(data, from, len)
          dos.flush()

          val packet = bytes.toByteArray
          socket.send(DatagramPacket(packet, packet.length, group, Config.MulticastPort))
          Thread.sleep(2) // небольшая пауза, чтобы не терять пакеты при отправке
    finally socket.close()

// ---------------------------------------------------------------------------
// Точка входа основного сервера
// ---------------------------------------------------------------------------
@main def mainServer(): Unit =
  Files.createDirectories(Paths.get(Config.MainStorage))
  UserStore.load() // загружаем пользователей до старта
  Replicator.start() // запускаем периодическую репликацию (свой поток)

  val serverSocket = ServerSocket(Config.TcpPort)
  println(s"[Сервер] Запущен, порт ${Config.TcpPort}. Ожидаю клиентов...")

  while true do
    val socket = serverSocket.accept() // ждём нового клиента
    val t = Thread(() => ClientHandler.handle(socket)) // на каждого клиента — свой поток
    t.start()