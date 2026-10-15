package Lab1

import java.io.*
import java.net.*
import java.nio.file.*
import scala.collection.mutable

// Сборка одного файла из кусков, приходящих по UDP
class FileAssembly(val totalSize: Long, chunkCount: Int):
  private val chunks = new Array[Array[Byte]](chunkCount)
  private var received = 0

  def add(index: Int, data: Array[Byte]): Unit =
    if chunks(index) == null then   // повторный кусок игнорируем
      chunks(index) = data
      received += 1

  def isComplete: Boolean = received == chunks.length
  def assemble(): Array[Byte] = chunks.flatten

// Резервный сервер. Запуск: sbt "runMain backupServer 1"  (1 — номер резервного сервера)
@main def backupServer(idArg: String = ""): Unit =
  val id: String =
    if idArg.nonEmpty then idArg
    else
      var n = 1
      while Files.exists(Paths.get(s"storage_backup_$n/.lock")) do n += 1
      n.toString

  val dir = Paths.get(s"storage_backup_$id")
  Files.createDirectories(dir)

  // Занимаем номер, чтобы параллельный запуск не взял тот же
  val lock = dir.resolve(".lock")
  try Files.createFile(lock)
  catch
    case _: FileAlreadyExistsException =>
      println(s"[Резерв $id] Номер $id уже занят.")
      sys.exit(1)
  lock.toFile.deleteOnExit()


  val socket = MulticastSocket(Config.MulticastPort)
  socket.joinGroup(InetAddress.getByName(Config.MulticastGroup)) // вступаем в группу вещания
  println(s"[Резерв $id] Слушаю группу ${Config.MulticastGroup}:${Config.MulticastPort}")

  val assemblies = mutable.Map[String, FileAssembly]() // файлы, которые сейчас собираются
  val buf = new Array[Byte](16 * 1024)

  while true do
    val packet = DatagramPacket(buf, buf.length)
    socket.receive(packet) // блокируется до прихода пакета

    val in     = DataInputStream(ByteArrayInputStream(packet.getData, 0, packet.getLength))
    val name   = in.readUTF()
    val total  = in.readLong()
    val index  = in.readInt()
    val count  = in.readInt()
    val len    = in.readInt()
    val data   = new Array[Byte](len)
    in.readFully(data)

    val target = dir.resolve(name)
    // Если такой файл уже есть с тем же размером — синхронизировать не нужно
    val upToDate = Files.exists(target) && Files.size(target) == total

    if !upToDate then
      // Если размер изменился (пришла новая версия) — начинаем сборку заново
      val asm = assemblies.get(name) match
        case Some(a) if a.totalSize == total => a
        case _ =>
          val a = FileAssembly(total, count)
          assemblies(name) = a
          a
      asm.add(index, data)
      if asm.isComplete then
        Files.write(target, asm.assemble())
        assemblies.remove(name)
        println(s"[Резерв $id] Синхронизирован файл '$name' ($total байт)")
