package com.walkmind.scodec

import scodec.{Attempt, Codec, DecodeResult, Decoder, Encoder, Err, SizeBound}
import scodec.bits.BitVector
import scodec.codecs.int32

import scala.collection.compat.Factory
import scala.collection.generic.CanBuildFrom
import scala.collection.immutable.Seq

trait SCodecExt {
  @inline
  def listOfNWithHeader[H, T](headerCodec: Codec[H], valueCodec: Codec[T], headerToCount: H => Int): Codec[(H, List[T])] =
    seqOfNWithHeader[List, H, T](headerCodec, valueCodec, headerToCount)

  @inline
  def vectorOfNWithHeader[H, T](headerCodec: Codec[H], valueCodec: Codec[T], headerToCount: H => Int): Codec[(H, Vector[T])] =
    seqOfNWithHeader[Vector, H, T](headerCodec, valueCodec, headerToCount)

  def seqOfNWithHeader[F[_], H, T](hCodec: Codec[H], vCodec: Codec[T], count: H => Int)(implicit cbf: Factory[T, F[T]], eva: F[T] <:< Seq[T]): Codec[(H, F[T])] = {

    class SeqCodec(limitOpt: Option[Int] = None) extends Codec[F[T]] {
      override def sizeBound: SizeBound = limitOpt.fold(SizeBound.unknown)(lim => vCodec.sizeBound * lim.toLong)
      override def encode(items: F[T]): Attempt[BitVector] = Encoder.encodeSeq(vCodec)(items)
      override def decode(buffer: BitVector): Attempt[DecodeResult[F[T]]] = Decoder.decodeCollect[F, T](vCodec, limitOpt)(buffer)
      override def toString = s"sequence($vCodec)"
    }

    hCodec
      .flatZip(header => new SeqCodec(Some(count(header))))
      .narrow[(H, F[T])]({
      case (header, xs) =>
        val cnt = count(header)
        if (xs.size == cnt)
          Attempt.successful(header -> xs)
        else
          Attempt.failure(Err(s"Insufficient number of elements: decoded ${xs.size} but should have decoded $cnt"))
    }, { case (header, items) =>
      header -> items
    })
      .withToString(s"seqOfNWithHeader($int32, $vCodec)")
  }
}
