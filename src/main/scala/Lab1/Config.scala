package Lab1

import java.io.*

// Общие настройки для всех модулей (сервер, резервные серверы, клиент)
object Config:
  val TcpPort         = 5000          // порт основного сервера для клиентов (TCP)
  val MulticastGroup  = "230.0.0.1"   // адрес группы UDP Multicast (диапазон 224.0.0.0 - 239.255.255.255)
  val MulticastPort   = 4446          // порт группового вещания
  val SyncIntervalSec = 60            // период репликации, сек (для быстрого теста можно поставить 10)
  val ChunkSize       = 8000          // размер одного UDP-пакета с данными (UDP-датаграмма ограничена ~64 КБ)
  val MainStorage     = "storage_main" // папка с файлами основного сервера

// Общая вспомогательная функция: сервер использует её при загрузке файла,
// клиент — при скачивании. Читает из сети РОВНО size байт и пишет их в файл.
object IoUtil:
  def copyExactly(in: DataInputStream, out: OutputStream, size: Long): Unit =
    val buf = new Array[Byte](4096)
    var left = size
    while left > 0 do
      val n = in.read(buf, 0, math.min(buf.length.toLong, left).toInt)
      if n < 0 then throw EOFException("Соединение оборвано во время передачи файла")
      out.write(buf, 0, n)
      left -= n

  def skipExactly(in: DataInputStream, size: Long): Unit =
    val buf = new Array[Byte](4096)
    var left = size
    while left > 0 do
      val n = in.read(buf, 0, math.min(buf.length.toLong, left).toInt)
      if n < 0 then throw EOFException("Соединение оборвано во время пропуска данных")
      left -= n