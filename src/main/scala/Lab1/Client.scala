package Lab1

import java.io.*
import java.net.*
import java.nio.file.*
import scala.jdk.CollectionConverters.*


// ---------------------------------------------------------------------------
// Локальный файл client_ids.txt: здесь клиент помнит свой id для каждого имени
// (формат строк: имя|id). Так при следующем запуске сервер узнаёт нас по id.
// ---------------------------------------------------------------------------
object IdFile:
  val idFile = Paths.get("client_ids.txt")

  def loadSavedId(name: String): Int =
    var result = 0
    if Files.exists(idFile) then
      for line <- Files.readAllLines(idFile).asScala do
        line.split("\\|", 2) match
          case Array(n, i) if n == name => result = i.toIntOption.getOrElse(0)
          case _ => ()
    result

  def saveId(name: String, id: Int): Unit =
    // оставляем строки других имён и добавляем/заменяем строку для нашего имени
    val others =
      if Files.exists(idFile) then
        Files.readAllLines(idFile).asScala.filter(l => l.nonEmpty && !l.startsWith(name + "|")).toList
      else Nil
    Files.write(idFile, (others :+ s"$name|$id").asJava)

// Клиент. Имя вводится при старте, id выдаёт сервер.
// Команды: list | upload <путь> | download <имя файла> | quit
@main def client(host: String = "localhost"): Unit =
  // 1. Спрашиваем имя (пустое не принимаем)
  var name = ""
  while name.isEmpty do
    val line = scala.io.StdIn.readLine("Введите имя клиента: ")
    if line == null then sys.exit(0)
    name = line.trim.replaceAll("[|\\r\\n]", "_") // '|' запрещён (ломает формат файлов)

  // 2. Подключаемся и представляемся серверу
  val socket = Socket(host, Config.TcpPort)
  val in = DataInputStream(BufferedInputStream(socket.getInputStream))
  val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream))
  val savedId = IdFile.loadSavedId(name) // id с прошлого раза (0, если мы ещё не заходили)
  out.writeUTF(name)
  out.writeInt(savedId)
  out.flush()

  // 3. Первый ответ сервера — WELCOME с текстом "id|статус" (читаем в основном потоке)
  in.readUTF() // тип сообщения ("WELCOME")
  val parts = in.readUTF().split("\\|")
  val id = parts(0).toInt // id, выданный (или подтверждённый) сервером
  val status = parts(1)
  status match
    case "NEW" =>
      IdFile.saveId(name, id) // запоминаем id для следующих запусков
      println("Новый пользователь зарегистрирован.")
    case "RETURNING" =>
      println("С возвращением!")
    case _ => // "BUSY"
      println("Ваш прежний id уже в сети, выдан временный новый id (он не сохраняется).")

  // Папка для скачанных файлов; у каждого клиента своя, чтобы не мешали друг другу
  val downloadDir = Paths.get(s"downloads_$id")
  Files.createDirectories(downloadDir)
  println(s"Вы вошли как $name#$id")
  println("Команды: list | upload <путь> | download <имя файла> | quit")
  print(">")

  // 4. Отдельный поток: слушает всё, что присылает сервер (ответы, уведомления, файлы),
  //    пока основной поток ждёт ввод пользователя.
  val listener = Thread(() =>
    try
      while true do
        in.readUTF() match
          case "FILE" =>
            // Сервер присылает файл: имя, размер, байты
            val fileName = Paths.get(in.readUTF()).getFileName.toString
            val size = in.readLong()
            val fos = BufferedOutputStream(Files.newOutputStream(downloadDir.resolve(fileName)))
            try IoUtil.copyExactly(in, fos, size)
            finally fos.close()
            println(s"\n<< Файл '$fileName' скачан в папку $downloadDir")
          case msgType =>
            // Обычное текстовое сообщение: OK, ERR, NEW_FILE, FILES
            println(s"\n<< [$msgType] ${in.readUTF()}")
    catch
      case _: IOException =>
        println("Соединение с сервером закрыто.")
        System.exit(0)
  )
  listener.setDaemon(true) // поток не мешает завершению программы
  listener.start()

  // 5. Основной поток: читаем команды с клавиатуры
  var running = true
  while running do
    val line = scala.io.StdIn.readLine()
    if line == null then running = false
    else
      line.trim.split(" ", 2).toList match
        case "list" :: _ =>
          out.writeUTF("LIST")
          out.flush()
        case "upload" :: path :: Nil =>
          val p = Paths.get(path.trim)
          if !Files.isRegularFile(p) then println("Файл не найден: " + p)
          else
            out.writeUTF("UPLOAD")
            out.writeUTF(p.getFileName.toString)
            out.writeLong(Files.size(p))
            Files.copy(p, out) // копируем байты файла в сокет
            out.flush()
        case "download" :: fileName :: Nil =>
          out.writeUTF("DOWNLOAD")
          out.writeUTF(fileName.trim)
          out.flush()
        case "quit" :: _ =>
          out.writeUTF("QUIT")
          out.flush()
          running = false
        case _ =>
          println("Команды: list | upload <путь> | download <имя файла> | quit")
          print(">")

  socket.close()