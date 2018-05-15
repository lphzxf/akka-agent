package mesh.utils

import java.io.{ByteArrayOutputStream, OutputStream, OutputStreamWriter, PrintWriter}
import java.util.concurrent.atomic.AtomicLong

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString

object DubboFlow {

  private val HEADER_LENGTH = 16
  private val requestId = new AtomicLong()

  private val MAGIC = 0xdabb.toShort
  private val FLAG_REQUEST = 0x80.toByte
  private val FLAG_TWOWAY = 0x40.toByte
  private val FLAG_EVENT = 0x20.toByte
  private val interface = "com.alibaba.dubbo.performance.demo.provider.IHelloService"
  private val method = "hash"
  private val pType = "Ljava/lang/String;"

  def encodeRequestData(out: OutputStream, path: String, method: String, parameterTypes: String, arguments: Array[Byte]): Unit = {
    val writer = new PrintWriter(new OutputStreamWriter(out))
    JsonUtils.writeObject("2.6.0", writer)
    JsonUtils.writeObject(path, writer)
    JsonUtils.writeObject("0.0.0", writer)
    JsonUtils.writeObject(method, writer)
    JsonUtils.writeObject(parameterTypes, writer)
    JsonUtils.writeBytes(arguments, writer)
    JsonUtils.writeObject(Map("path" -> path, "dubbo" -> "2.6.0"), writer)
  }

  def map2DubboByteString(requestId: Long, parameter: ByteString): ByteString = {

    val header = new Array[Byte](HEADER_LENGTH)
    // set magic number.
    Bytes.short2bytes(MAGIC, header)

    // set request and serialization flag.
    header(2) = (FLAG_REQUEST | 6).toByte

    header(2) = (header(2) | FLAG_TWOWAY).toByte

    Bytes.long2bytes(requestId, header, 4)

    val out = new ByteArrayOutputStream()

    val writer = new PrintWriter(new OutputStreamWriter(out))

    JsonUtils.writeBytes(parameter.toArray, writer)

    val bos = new ByteArrayOutputStream
    encodeRequestData(bos,
      interface,
      method, pType,
      out.toByteArray)

    val len = bos.size()

    Bytes.int2bytes(len, header, 12)

    ByteString(header ++ bos.toByteArray)
  }

  def flow(requestId: Long) = Flow[ByteString].map(map2DubboByteString(requestId, _))

  def unpackingDubboByteString(data: ByteString) = {
    def unpacking(data: ByteString, list: List[(Long, ByteString)]): List[(Long, ByteString)] = {
      if (data.size > 16) {
        val id = Bytes.bytes2long(data.toArray, 4)
        val string = data.drop(18).takeWhile(_ != '\n')
        unpacking(data.drop(19 + string.length), (id -> string) :: list)
      } else list
    }

    unpacking(data, List.empty)
  }

  val slicer = "parameter=".map(_.toByte)
  val quote = '\"'.toByte
  val quoteAndCarriageReturn = ByteString("\"\r\n")
  val httpStatus = ByteString("HTTP/1.1 200 OK\r\n")
  val kAlive = ByteString("Connection: Keep-Alive\r\n")
  val ctype = ByteString("Content-Type: application/octet-stream\r\n")
  val headerDelimter = ByteString("\r\n")

  def cLength(length: Int) = {
    ByteString(s"Content-Length: $length\r\n")
  }

  val connectionIdFlow: Flow[(Long, ByteString), ByteString, NotUsed] =
    Flow[(Long, ByteString)].map {
      case (cid, bs) =>
        val n = bs.indexOfSlice(slicer)
        val s = bs.drop(n + slicer.size)
        val d = quote +: (s ++ quoteAndCarriageReturn)
        map2DubboByteString(cid, d)

    }

  val decoder = Flow[ByteString].flatMapConcat {
    bs =>
      Source(
        unpackingDubboByteString(bs).map { t =>
          val resp = httpStatus ++ cLength(t._2.size) ++ kAlive ++ ctype ++ headerDelimter ++ t._2
          (t._1, resp)
        }
      )
  }


}