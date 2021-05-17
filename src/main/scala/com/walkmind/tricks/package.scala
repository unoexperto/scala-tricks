package com.walkmind

import java.lang.management.ManagementFactory
import java.time.{Instant, LocalDate, ZoneId, ZonedDateTime}
import java.util
import java.util.Comparator

import akka.actor.Scheduler
import cats.implicits._
import cats.effect.unsafe.IORuntime
//import cats.effect.unsafe.implicits.global
import cats.effect.{Clock, IO, Sync, Temporal}
import com.typesafe.scalalogging.Logger
import org.apache.commons.lang3.exception.ExceptionUtils
import spray.json.{JsValue, JsonFormat, JsonReader, JsonWriter, RootJsonFormat, RootJsonReader, RootJsonWriter}

import scala.collection.{immutable, mutable}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.reflect.ClassTag
import scala.util.{Either, Failure, Random, Right, Success, Try}

package object tricks {

  def catchNonFatal[A](f: => A): Either[Throwable, A] =
    try {
      Right(f)
    } catch {
      case scala.util.control.NonFatal(t) => Left(t)
    }

  implicit class PimpedTry[T](val value: Try[T]) extends AnyVal {
    def mapRight[C](f: T => C): Try[C] = value match {
      case Success(a) => Success(f(a))
      case _ => value.asInstanceOf[Try[C]]
    }

    def mapLeft(f: Throwable => Throwable): Try[T] = value match {
      case Failure(ex) => Failure(f(ex))
      case _ => value
    }

    def flatMapRight[C](f: T => Try[C]): Try[C] = value match {
      case Success(b) => f(b)
      case _ => value.asInstanceOf[Try[C]]
    }

    def flatMapLeft(f: Throwable => Try[T]): Try[T] = value match {
      case Failure(ex) => f(ex)
      case _ => value
    }
  }

  implicit class PimpedEither[L, R](val value: Either[L, R]) extends AnyVal {

    def sequenceRight[C](f: R => Future[C])(implicit ec: ExecutionContext): Future[Either[L, C]] = value match {
      case v@Left(_) => Future.successful(v.asInstanceOf[Either[L, C]])
      case Right(a) => f(a).map(Right.apply)
    }

    def mapRight[C](f: R => C): Either[L, C] = value match {
      case Right(a) => Right(f(a))
      case _ => value.asInstanceOf[Either[L, C]]
    }

    def mapLeft[C](f: L => C): Either[C, R] = value match {
      case Left(a) => Left(f(a))
      case _ => value.asInstanceOf[Either[C, R]]
    }

    def flatMapRight[C](f: R => Either[L, C]): Either[L, C] = value match {
      case Right(b) => f(b)
      case _ => value.asInstanceOf[Either[L, C]]
    }

    def flatMapLeft[C](f: L => Either[C, R]): Either[C, R] = value match {
      case Left(b) => f(b)
      case _ => value.asInstanceOf[Either[C, R]]
    }

    @inline
    def toOption: Option[R] = value.fold(_ => None, r => Some(r))
  }

  implicit class PimpedLocalDate(val value: LocalDate) extends AnyVal {
    @inline
    def toZonedDateTime: ZonedDateTime = value.atStartOfDay().atZone(ZoneId.of("UTC"))
  }

  def zonedDateTimeFromEpochMillis(millis: Long): ZonedDateTime =
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.of("UTC"))

  def zonedDateTimeFromEpochSeconds(seconds: Long): ZonedDateTime =
    ZonedDateTime.ofInstant(Instant.ofEpochSecond(seconds), ZoneId.of("UTC"))

  def split[A](xs: Seq[A], n: Int): List[Seq[A]] = {
    if (xs.size <= n) xs :: Nil
    else (xs take n) :: split(xs drop n, n)
  }

  def merge[T](items: Option[Seq[T]]*): Option[Seq[T]] =
    items
      .collect {
        case Some(seq) if seq.nonEmpty => seq
      }
      .foldLeft(Option(Seq[T]())) { case (result, item) => result.map(_ ++ item) }

  implicit class ForeachAsync[T](iterable: Iterable[T]) {
    def foreachAsync[U](f: T => Future[U])(implicit ec: ExecutionContext): Future[Unit] = {
      def next(i: Iterator[Future[U]]): Future[Unit] =
        if (i.hasNext) i.next().flatMap(_ => next(i)) else Future.successful(())

      Future(iterable.iterator.map(f)).flatMap(next)
    }
  }

  @inline
  def onlyDefined[T]: PartialFunction[Option[T], T] = {
    case Some(x) => x
  }

  @inline
  def completeLeftAndProduceRight[L, R](value: L): PartialFunction[Either[Promise[L], R], R] = {
    case Left(p) if {
      p.tryComplete(Success(value))
      false
    } => null.asInstanceOf[R]
    case Right(v) => v
  }

  @inline
  def onlyLeftAndDefinedRight[L, R]: PartialFunction[Either[L, Option[R]], Either[L, R]] =
    onlyLeftAndDefinedRight(_ => true)

  @inline
  def onlyLeftAndDefinedRight[L, R](condition: R => Boolean): PartialFunction[Either[L, Option[R]], Either[L, R]] = {
    case v@Left(_) =>
      v.asInstanceOf[Either[L, R]]
    case Right(Some(v)) if condition(v) =>
      Right[L, R](v)
  }

  implicit class Pairs[A, B](p: Iterable[(A, B)]) {
    def toMultiMap: Map[A, List[B]] = p.groupBy(_._1).view.mapValues(_.map(_._2).toList).toMap
  }

  implicit class IterableExt[+T](val items: scala.collection.Iterable[T]) {
    import scala.jdk.CollectionConverters._

    @inline
    private def convOrderingToComparator[K](keyOrdering: Ordering[K]): Comparator[K] =
      new Comparator[K] {
        override def compare(o1: K, o2: K): Int = keyOrdering.compare(o1, o2)
      }

    def groupPairs[K, V](keyValue: T => (K, V))(implicit keyOrdering: Ordering[K]): immutable.Map[K, List[V]] = {
      val result = new util.TreeMap[K, mutable.Builder[V, List[V]]](convOrderingToComparator(keyOrdering))
      items.foreach { elem =>
        val (key, value) = keyValue(elem)
        val bldr = {
          val b = result.get(key)
          if (b == null) {
            val bNew = List.newBuilder[V]
            result.put(key, bNew)
            bNew
          } else
            b
        }
        bldr += value
      }
      result.asScala.toMap.map { case (key, value) => key -> value.result() }
    }

    def groupOrderedBy[K](f: T => K)(implicit keyOrdering: Ordering[K]): immutable.Map[K, List[T]] = {
      new java.util.TreeMap[K, mutable.Builder[T, List[T]]](convOrderingToComparator(keyOrdering))

      val result = new java.util.TreeMap[K, mutable.Builder[T, List[T]]](convOrderingToComparator(keyOrdering))
      items.foreach { elem =>
        val key = f(elem)

        val bldr = {
          val b = result.get(key)
          if (b == null) {
            val bNew = List.newBuilder[T]
            result.put(key, bNew)
            bNew
          } else
            b
        }

        bldr += elem
      }
      result.asScala.toMap.map { case (key, value) => key -> value.result() }
    }
  }

  // FIXME: Fix me with typetag?
  //  import scala.reflect.runtime.universe._
  @inline
  def onlyOfType[T: ClassTag]: PartialFunction[Any, T] = {
    case x: T => x.asInstanceOf[T]
  }

  @inline
  def toType[T](value: Any): T = value.asInstanceOf[T]

  val isDebuggerAttached: Boolean = ManagementFactory.getRuntimeMXBean.getInputArguments.toString.indexOf("jdwp") >= 0

  def getIntEnvVarOrExit(name: String)(implicit logger: Logger): Int =
    Try {
      System.getenv(name).toInt
    }.getOrElse {
      logger.error(s"System variable $name is missing or invalid. Terminating the process.")
      System.exit(-1)
      ???
    }

  def getBoolEnvVarOrExit(name: String)(implicit logger: Logger): Boolean =
    Try {
      System.getenv(name).toBoolean
    }.getOrElse {
      logger.error(s"System variable $name is missing or invalid. Terminating the process.")
      System.exit(-1)
      ???
    }

  def getStringEnvVarOrExit(name: String)(implicit logger: Logger): String =
    Option(System.getenv(name))
      .getOrElse {
        logger.error(s"System variable $name is missing or invalid. Terminating the process")
        System.exit(-1)
        ???
      }

  def getIntEnvVar(name: String, default: Int)(implicit logger: Logger): Int =
    Try {
      System.getenv(name).toInt
    }.getOrElse {
      logger.warn(s"System variable $name is missing or invalid. Default value $default will be used.")
      default
    }

  def getBoolEnvVar(name: String, default: Boolean)(implicit logger: Logger): Boolean =
    Try {
      System.getenv(name).toBoolean
    }.getOrElse {
      logger.warn(s"System variable $name is missing or invalid. Default value $default will be used.")
      default
    }

  def getStringEnvVar(name: String, default: String)(implicit logger: Logger): String =
    Option(System.getenv(name))
      .getOrElse {
        logger.warn(s"System variable $name is missing or invalid. Default value $default will be used.")
        default
      }

  def retryDefault[T](f: => Future[T])(implicit ec: ExecutionContext, s: Scheduler): Future[T] =
    retry(
      f,
      RetryDelays.withJitter(RetryDelays.fibonacci, 0.8, 1.2),
      5,
      5.seconds
    )

  def retry[T](f: => Future[T], delay: Seq[FiniteDuration], retries: Int, defaultDelay: FiniteDuration)(implicit ec: ExecutionContext, s: Scheduler): Future[T] = {
    f recoverWith {
      case _ if retries > 0 =>
        //println(s"Retries: $retries")
        akka.pattern.after(delay.headOption.getOrElse(defaultDelay), s)(retry(f, delay.tail, retries - 1, defaultDelay))
    }
  }

  object RetryDelays {
    def withDefault(delays: List[FiniteDuration], retries: Int, default: FiniteDuration): List[FiniteDuration] = {
      if (delays.length > retries)
        delays.take(retries)
      else
        delays ++ List.fill(retries - delays.length)(default)
    }

    def withJitter(delays: Seq[FiniteDuration], maxJitter: Double, minJitter: Double): Seq[FiniteDuration] =
      delays.map { v =>
        val n = v * (minJitter + (maxJitter - minJitter) * Random.nextDouble())
        FiniteDuration(n.length, n.unit)
      }

    val fibonacci: LazyList[FiniteDuration] = 0.seconds #:: 1.seconds #:: (fibonacci zip fibonacci.tail).map { t => t._1 + t._2 }
  }

  implicit class RootJsonFormatExt[A: ClassTag](val codec: RootJsonFormat[A]) {
    def xmap[B](f: A => B, g: B => A): RootJsonFormat[B] = new RootJsonFormat[B] {
      override def read(json: JsValue): B = f(codec.read(json))

      override def write(obj: B): JsValue = codec.write(g(obj))
    }

    def transform(f: JsValue => JsValue, g: JsValue => JsValue): RootJsonFormat[A] = new RootJsonFormat[A] {
      override def read(json: JsValue): A = codec.read(f(json))

      override def write(obj: A): JsValue = g(codec.write(obj))
    }

    @inline
    def toWriter: RootJsonWriter[A] = codec

    @inline
    def toReader: RootJsonReader[A] = codec
  }

  implicit class JsonFormatExt[A: ClassTag](val codec: JsonFormat[A]) {
    def xmap[B](f: A => B, g: B => A): JsonFormat[B] = new JsonFormat[B] {
      override def read(json: JsValue): B = f(codec.read(json))

      override def write(obj: B): JsValue = codec.write(g(obj))
    }

    def transform(f: JsValue => JsValue, g: JsValue => JsValue): JsonFormat[A] = new JsonFormat[A] {
      override def read(json: JsValue): A = codec.read(f(json))

      override def write(obj: A): JsValue = g(codec.write(obj))
    }

    @inline
    def toWriter: JsonWriter[A] = codec

    @inline
    def toReader: JsonReader[A] = codec
  }

  implicit class RootJsonWriterExt[A: ClassTag](val codec: RootJsonWriter[A]) {
    def xmap[B](g: B => A): RootJsonWriter[B] = new RootJsonWriter[B] {
      override def write(obj: B): JsValue = codec.write(g(obj))
    }
    def transform(g: JsValue => JsValue): RootJsonWriter[A] = new RootJsonWriter[A] {
      override def write(obj: A): JsValue = g(codec.write(obj))
    }
  }

  implicit class JsonWriterExt[A: ClassTag](val codec: JsonWriter[A]) {
    def xmap[B](g: B => A): JsonWriter[B] = new JsonWriter[B] {
      override def write(obj: B): JsValue = codec.write(g(obj))
    }
    def transform(g: JsValue => JsValue): JsonWriter[A] = new JsonWriter[A] {
      override def write(obj: A): JsValue = g(codec.write(obj))
    }
  }

  implicit class RootJsonReaderExt[A: ClassTag](val codec: RootJsonReader[A]) {
    def xmap[B](f: A => B): RootJsonReader[B] = new RootJsonReader[B] {
      override def read(json: JsValue): B = f(codec.read(json))
    }
    def transform(f: JsValue => JsValue): RootJsonReader[A] = new RootJsonReader[A] {
      override def read(json: JsValue): A = codec.read(f(json))
    }
  }

  implicit class JsonReaderExt[A: ClassTag](val codec: JsonReader[A]) {
    def xmap[B](f: A => B): JsonReader[B] = new JsonReader[B] {
      override def read(json: JsValue): B = f(codec.read(json))
    }
    def transform(f: JsValue => JsValue): JsonReader[A] = new JsonReader[A] {
      override def read(json: JsValue): A = codec.read(f(json))
    }
  }

  @inline
  def getRootCause(ex: Throwable): Throwable =
    if (ex != null && ex.getCause != null) ExceptionUtils.getRootCause(ex) else ex

  @inline
  def getRootMessage(ex: Throwable): String = {
    val cause = getRootCause(ex)
    if (cause != null) cause.getMessage else "[null]"
  }

  def timeoutTo[A](fa: IO[A], after: FiniteDuration, fallback: IO[A])(implicit timer: Temporal[IO]): IO[A] =
    IO.race(fa, timer.sleep(after)).flatMap {
      case Left(a) => IO.pure(a)
      case Right(_) => fallback
    }

  def measure[F[_], A](fa: F[A])(implicit F: Sync[F], clock: Clock[F]): F[(A, Long)] =
    for {
      start  <- clock.monotonic
      result <- fa
      finish <- clock.monotonic
    } yield (result, finish.toMillis - start.toMillis)

  // Lazily wraps scala Future. It allows us wrap bunch of Future-s but execute them later sequentially or in parallel.
  @inline
  def toIO[T](body: => Future[T])(implicit ioruntime: IORuntime): IO[T] =
    IO.fromFuture(IO.delay(body))
}
