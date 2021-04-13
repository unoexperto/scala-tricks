package com.walkmind.scodec.pgcopy

import java.net.{Inet4Address, Inet6Address, InetAddress}
import java.nio.ByteBuffer
import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate, LocalDateTime, ZoneOffset, ZonedDateTime}
import java.util.UUID

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.walkmind.scodec.SCodecExt
import org.postgresql.copy.CopyOut
import org.postgresql.jdbc.PgConnection
import scodec._
import scodec.bits._
import scodec.codecs.{bits, byte, bytes, constant, double, fallback, float, int32, int8, long, short16, utf8, variableSizeBytes}

// FIXME: https://github.com/bytefish/PgBulkInsert/blob/master/PgBulkInsert/src/main/java/de/bytefish/pgbulkinsert/pgsql/handlers/BigDecimalValueHandler.java
trait PgCopyCodecs extends SCodecExt {
  // https://github.com/postgres/postgres/blob/master/src/backend/utils/adt/varchar.c
  val psqlUtf8: Codec[String] = variableSizeBytes(int32, utf8)
  // https://github.com/postgres/postgres/blob/master/src/backend/utils/adt/bool.c
  val psqlBool: Codec[Boolean] = variableSizeBytes(int32, byte.xmap[Boolean](_ == 1, v => if (v) 1 else 0))
  // https://github.com/postgres/postgres/blob/master/src/backend/utils/adt/int.c
  val psqlInt: Codec[Int] = variableSizeBytes(int32, int32)
  // https://github.com/postgres/postgres/blob/master/src/backend/utils/adt/int8.c
  val psqlLong: Codec[Long] = variableSizeBytes(int32, long(64))
  val psqlBytes: Codec[Array[Byte]] = variableSizeBytes(int32, bytes).xmap[Array[Byte]](_.toArray, ByteVector.apply)
  val psqlShort: Codec[Short] = variableSizeBytes(int32, short16)
  // https://github.com/postgres/postgres/blob/master/src/backend/utils/adt/float.c
  val psqlDouble: Codec[Double] = variableSizeBytes(int32, double) // 8 bytes
  // https://github.com/postgres/postgres/blob/master/src/backend/utils/adt/float.c
  val psqlReal: Codec[Float] = variableSizeBytes(int32, float) // 4 bytes
  val psqlInetAddress: Codec[InetAddress] = {

    val addrCodec = variableSizeBytes(int8, bytes).xmap[InetAddress](v => InetAddress.getByAddress(v.toArray), { v => ByteVector(v.getAddress) })

    case class Header(version: Byte, mask: Int, isCidr: Byte)
    case class AddrWrapped(header: Header, addr: InetAddress)

    val headerCodec = (byte :: int8 :: byte).as[Header]
    val addrCodecAndPrefix = (headerCodec :: addrCodec).as[AddrWrapped]

    variableSizeBytes(int32, addrCodecAndPrefix).xmap[InetAddress](_.addr, {
      case addr: Inet4Address => AddrWrapped(Header(2, 32, 0), addr)
      case addr: Inet6Address => AddrWrapped(Header(3, 128, 0), addr)
    })
  }

  val psqlJsonb: Codec[String] = variableSizeBytes(int32, (constant(ByteVector.fromByte(1)) :: utf8).as[Tuple1[String]].xmap[String](_._1, Tuple1.apply))

  // https://github.com/postgres/postgres/blob/master/src/backend/utils/adt/timestamp.c
  // https://www.postgresql.org/docs/current/static/datatype-datetime.html
  val psqlInstant: Codec[Instant] = {
    val diffMicro = ChronoUnit.MICROS.between(
      LocalDateTime.of(1970, 1, 1, 0, 0),
      LocalDateTime.of(2000, 1, 1, 0, 0))

    psqlLong.xmap[Instant]({ epoch =>
      val javaEpochMicro = diffMicro + epoch
      Instant.ofEpochMilli(javaEpochMicro / 1000).plus(javaEpochMicro % 1000, ChronoUnit.MICROS)
    }, { time =>
      val javaEpochMicro = time.toEpochMilli * 1000 + time.getNano % 1000000 / 1000
      javaEpochMicro - diffMicro
    })
  }

  val psqlUtcDateTime: Codec[ZonedDateTime] =
    psqlInstant.xmap[ZonedDateTime]({ v => ZonedDateTime.ofInstant(v, ZoneOffset.UTC) }, { v => v.toInstant })

  val psqlLocalDate: Codec[LocalDate] = {
    val javaEpoch = LocalDate.of(1970, 1, 1)
    val psqlEpoch = LocalDate.of(2000, 1, 1)
    val diffDays = ChronoUnit.DAYS.between(javaEpoch, psqlEpoch)

    psqlInt.xmap[LocalDate]({ epoch =>
      psqlEpoch.plusDays(epoch)
    }, { date =>
      (date.toEpochDay - diffDays).toInt
    })
  }

  val psqlUUID: Codec[UUID] = variableSizeBytes(int32, scodec.codecs.uuid)

  def psqlNullable[T](codec: Codec[T]): Codec[Option[T]] =
    fallback(constant(ByteVector.fromInt(-1, 4)), codec).xmap[Option[T]]({
      case Left(_) => None
      case Right(v) => Some(v)
    }, {
      case None => Left(())
      case Some(v) => Right(v)
    })

  def psqlRowOpt[T](codec: Codec[T]): Codec[Option[T]] =
    fallback(constant(ByteVector.fromInt(-1, 2)), codec).xmap[Option[T]]({
      case Left(_) => None
      case Right(v) => Some(v)
    }, {
      case None => Left(())
      case Some(v) => Right(v)
    })

  case class Bound(max: Int, min: Int)

  // List of OIDs - org.postgresql.core.Oid
  case class ArrayHeader(hasNulls: Boolean, oid: Int, bounds: Vector[Bound])

  val boundCodec: Codec[Bound] = (int32 :: int32).as[Bound]

  val arrayHeaderCodec: Codec[ArrayHeader] = {
    case class ArrayHeaderPrefix(dimensions: Int, hasNulls: Boolean, oid: Int)
    val arrayHeaderPrefixCodec: Codec[ArrayHeaderPrefix] = (int32 :: int32.xmap[Boolean](_ == 1, v => if (v) 1 else 0) :: int32).as[ArrayHeaderPrefix]

    vectorOfNWithHeader[ArrayHeaderPrefix, Bound](arrayHeaderPrefixCodec, boundCodec, h => h.dimensions).xmap[ArrayHeader](
      { case (p, bounds) => ArrayHeader(p.hasNulls, p.oid, bounds) },
      { h => ArrayHeaderPrefix(h.bounds.size, h.hasNulls, h.oid) -> h.bounds }
    )
  }

  case class PsqlArrayWrapper[T](header: ArrayHeader, arr: Vector[T])

  // https://github.com/postgres/postgres/blob/master/src/backend/utils/adt/arrayfuncs.c
  def psqlArrayCodec[T](implicit itemCodec: Codec[T]): Codec[PsqlArrayWrapper[T]] =
    variableSizeBytes(int32, vectorOfNWithHeader[ArrayHeader, T](arrayHeaderCodec, itemCodec, h => h.bounds.map(_.max).reduceLeftOption(_ * _).getOrElse(0)))
      .xmap[PsqlArrayWrapper[T]]({ case (header, items) =>
      PsqlArrayWrapper(header, items)
    }, v =>
      v.header -> v.arr
    )

  case class PgCopyHeader(flags: Int, dummy: BitVector)

  val pgCopyHeaderCodec: Codec[PgCopyHeader] =
    (constant(hex"5047434F50590AFF0D0A00") :: int32 :: variableSizeBytes(int32, bits)).as[PgCopyHeader]

  case class PgCopyRow[T](count: Short, value: T)

  def pgCopyRowCodec[T](codec: Codec[T]): Codec[PgCopyRow[T]] =
    (short16 :: codec).as[PgCopyRow[T]]

  def bitVectorForPgCopy(pgConnection: PgConnection, query: String, onClose: => Unit): BitVector = {
    val arr = new Array[Byte](32 * 1024 * 1024)
    val buf = ByteBuffer.wrap(arr)

    BitVector.unfold[CopyOut] {
      pgConnection.getCopyAPI.copyOut(query)
    } { co =>

      var chunk: BitVector = null
      do {
        val bytes = co.readFromCopy(true)
        if (bytes != null) {
          if (buf.remaining() > bytes.length) {
            buf.put(bytes)
          } else {
            chunk = ByteVector.apply(arr, 0, buf.capacity() - buf.remaining()).toBitVector
            buf.clear()
            buf.put(bytes)
          }
        } else {
          chunk = BitVector.apply(buf)
          buf.clear()
        }
      } while (chunk == null)

      if (chunk.nonEmpty)
        Some(chunk -> co)
      else {
        onClose
        None
      }
    }
  }

  def sourceByteStringForPgCopy(pgConnection: PgConnection, query: String, bufferSize: Option[Int] = Some(64 * 1024), onClose: => Unit): Source[ByteString, NotUsed] = {

    val buffer =
      bufferSize.map { size =>
        val arr = new Array[Byte](size)
        val buf = ByteBuffer.wrap(arr)
        arr -> buf
      }

    Source.unfoldResource[ByteString, CopyOut](() => {
      pgConnection.getCopyAPI.copyOut(query)
    }, { co =>
      var chunk: ByteString = null
      buffer match {
        case Some((arr, buf)) =>
          if (co.isActive)
            do {
              val bytes = co.readFromCopy(true)
              if (bytes != null) {
                if (buf.remaining() > bytes.length) {
                  buf.put(bytes)
                } else {
                  chunk = ByteString.fromArray(arr, 0, buf.capacity() - buf.remaining())
                  buf.clear()
                  buf.put(bytes)
                }
              } else {
                chunk = ByteString.fromArray(arr, 0, buf.capacity() - buf.remaining())
                buf.clear()
              }
            } while (chunk == null)
          else
            chunk = ByteString.empty

        case None =>
          val bytes = co.readFromCopy(true)
          chunk = if (bytes != null) ByteString.fromArray(bytes) else ByteString.empty
      }

      if (chunk.nonEmpty)
        Some(chunk)
      else
        None
    }, _ => onClose)
  }

  @deprecated("Use SourceExt.fromMmap with FlowExt.scodecStreamParser instead", "2.14")
  def sourceFromPgCopyBitVector[T: Codec](bv: BitVector): Source[T, NotUsed] = {
    val rowCodec = psqlRowOpt(pgCopyRowCodec(implicitly[Codec[T]]))
    Source.unfold({
      val res = pgCopyHeaderCodec.decode(bv).require
      res.value -> res.remainder
    }) { case (header, bits) =>
      if (bits.nonEmpty) {
        val res = rowCodec.decode(bits).require
        val remainder = res.remainder
        Some((header -> remainder) -> res.value)
      } else
        None
    }
      .collect { case Some(v) => v.value }
  }
}

object PgCopyCodecs extends PgCopyCodecs
